/*
 * libliquidbounce_audiocodec  --  Ogg/Vorbis + Opus + MP4(m4a/m4s)/AAC decoder for the Music module.
 *
 * Built with the Android NDK against a minimal static FFmpeg (see build-android-arm64.sh in this
 * directory). The Java side only ever sees one call per frame:
 *
 *     long open(String path, long startAtMs, int targetRate, int targetChannels, boolean allowResample)
 *     int  readFrame(long handle, short[] out)      -> samples written, 0 = EOF, <0 = error
 *     int  getSampleRate(long handle) / getChannels(long handle) / getTotalSamples(long handle)
 *     long getPositionSamples(long handle)
 *     void setStopFlag(long handle, boolean stop)   -- checked inside the decode loop
 *     void close(long handle)
 *
 * PCM is always interleaved signed 16-bit little endian, which is exactly what AudioTrack/OpenAL
 * in this client consumes. Everything is 1:1 with the Java PcmAudioParser contract so the Kotlin
 * AudioEngine keeps its existing chunking (1152 * channels floats).
 */

#include <jni.h>
#include <android/log.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>

#define LOG_TAG "LBAudioCodec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* The largest single readFrame() the Java side asks for (MAX_PCM_FRAME_SAMPLES * channels). */
#define MAX_OUT_SAMPLES (65536 * 2)

typedef struct AudioDecoder {
    AVFormatContext *format;
    AVCodecContext  *codec;
    SwrContext      *swr;
    AVPacket        *packet;
    AVFrame         *frame;
    int              streamIndex;
    int              outRate;         /* rate the caller asked for (0 = keep source rate) */
    int              outChannels;     /* channels the caller asked for (0 = keep source)   */
    int              sampleRate;      /* effective output rate                              */
    int              channels;        /* effective output channel count                     */
    int              sourceRate;
    int              sourceChannels;
    int64_t          totalSamples;    /* 0 when unknown                                     */
    int64_t          position;        /* frames emitted so far                              */
    int64_t          positionBase;    /* absolute offset of the first emitted frame         */
    int64_t          seekTarget;      /* startAtMs expressed in output-rate samples         */
    int64_t          startSample;     /* leading samples still to drop after the seek       */
    int              alignPending;    /* first frame has not been aligned to seekTarget yet */
    int              eof;
    volatile int     stop;
    char             lastError[256];
} AudioDecoder;

static void set_error(AudioDecoder *d, const char *message, int code) {
    if (!d) return;
    if (code != 0) {
        char detail[128];
        av_strerror(code, detail, sizeof(detail));
        snprintf(d->lastError, sizeof(d->lastError), "%s: %s", message, detail);
    } else {
        snprintf(d->lastError, sizeof(d->lastError), "%s", message);
    }
}

static void decoder_free(AudioDecoder *d) {
    if (!d) return;
    if (d->swr)    swr_free(&d->swr);
    if (d->frame)  av_frame_free(&d->frame);
    if (d->packet) av_packet_free(&d->packet);
    if (d->codec)  avcodec_free_context(&d->codec);
    if (d->format) avformat_close_input(&d->format);
    free(d);
}

/* Opens the container, picks the best audio stream and starts the decoder. */
static AudioDecoder *decoder_open(const char *path, int64_t startAtMs, int wantRate, int wantChannels) {
    AudioDecoder *d = (AudioDecoder *) calloc(1, sizeof(AudioDecoder));
    if (!d) return NULL;

    int rc = avformat_open_input(&d->format, path, NULL, NULL);
    if (rc < 0) { set_error(d, "无法打开音频文件", rc); goto fail; }

    rc = avformat_find_stream_info(d->format, NULL);
    if (rc < 0) { set_error(d, "无法读取音频流信息", rc); goto fail; }

    d->streamIndex = av_find_best_stream(d->format, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (d->streamIndex < 0) { set_error(d, "文件里没有音频流", d->streamIndex); goto fail; }

    AVStream *stream = d->format->streams[d->streamIndex];
    const AVCodec *codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) { set_error(d, "该格式没有内置解码器", AVERROR_DECODER_NOT_FOUND); goto fail; }

    d->codec = avcodec_alloc_context3(codec);
    if (!d->codec) { set_error(d, "无法分配解码器", AVERROR(ENOMEM)); goto fail; }

    rc = avcodec_parameters_to_context(d->codec, stream->codecpar);
    if (rc < 0) { set_error(d, "无法配置解码器", rc); goto fail; }

    if (startAtMs > 0) {
        /* Seek before opening so the decoder never wastes time on skipped packets. */
        int64_t target = stream->time_base.den
                ? av_rescale_q(startAtMs * 1000LL,
                               (AVRational) {1, 1000000},
                               stream->time_base)
                : 0;
        avformat_seek_file(d->format, d->streamIndex, INT64_MIN, target, target, 0);
    }

    rc = avcodec_open2(d->codec, codec, NULL);
    if (rc < 0) { set_error(d, "无法启动解码器", rc); goto fail; }

    d->packet = av_packet_alloc();
    d->frame  = av_frame_alloc();
    if (!d->packet || !d->frame) { set_error(d, "内存不足", AVERROR(ENOMEM)); goto fail; }

    d->sourceRate     = d->codec->sample_rate;
    d->sourceChannels = d->codec->ch_layout.nb_channels;
    if (d->sourceRate <= 0 || d->sourceChannels <= 0) {
        set_error(d, "音频流参数无效", 0); goto fail;
    }

    d->outRate     = wantRate     > 0 ? wantRate     : d->sourceRate;
    d->outChannels = wantChannels > 0 ? wantChannels : d->sourceChannels;
    if (d->outChannels < 1) d->outChannels = 1;
    if (d->outChannels > 8) d->outChannels = 8;
    d->sampleRate = d->outRate;
    d->channels   = d->outChannels;

    if (startAtMs > 0) {
        /* The demuxer seek lands on the sample before the target; the exact remainder is
         * computed from the first decoded frame's timestamp in convert_frame(). */
        d->seekTarget    = av_rescale(startAtMs, d->sampleRate, 1000);
        d->startSample   = d->seekTarget;
        d->positionBase  = d->seekTarget;
        d->alignPending  = 1;
    }
    if (stream->duration > 0) {
        /* Convert the container duration to the output rate we are emitting. */
        int64_t sourceSamples = av_rescale_q(stream->duration, stream->time_base, (AVRational) {1, d->sourceRate});
        d->totalSamples = av_rescale(sourceSamples, d->sampleRate, d->sourceRate);
        if (d->startSample > 0 && d->totalSamples > d->startSample) {
            d->totalSamples -= d->startSample;
        }
    }

    LOGI("opened %s: codec=%s %d Hz / %d ch -> %d Hz / %d ch (start=%" PRId64 " ms, total=%" PRId64 ")",
         path, codec->name, d->sourceRate, d->sourceChannels, d->sampleRate, d->channels,
         startAtMs, d->totalSamples);
    return d;

fail:
    decoder_free(d);
    return NULL;
}

/* Builds (once) a resampler from the decoder output format to interleaved s16. */
static int ensure_swr(AudioDecoder *d) {
    if (d->swr) return 0;
    AVChannelLayout outLayout;
    av_channel_layout_default(&outLayout, d->channels);

    int rc = swr_alloc_set_opts2(&d->swr,
                                 &outLayout, AV_SAMPLE_FMT_S16, d->sampleRate,
                                 &d->codec->ch_layout, d->codec->sample_fmt, d->codec->sample_rate,
                                 0, NULL);
    av_channel_layout_uninit(&outLayout);
    if (rc < 0 || !d->swr) { set_error(d, "无法创建重采样器", rc); return -1; }

    rc = swr_init(d->swr);
    if (rc < 0) { set_error(d, "无法初始化重采样器", rc); return -1; }
    return 0;
}

/* Converts one decoded frame into interleaved s16 and copies it into out[]. */
static int convert_frame(AudioDecoder *d, short *out, int capacity) {
    if (ensure_swr(d) < 0) return -1;

    /* Align the first frame with startAtMs: a container seek lands on the packet before the
     * requested time, so only the remainder is dropped here. */
    if (d->alignPending) {
        d->alignPending = 0;
        if (d->frame->pts != AV_NOPTS_VALUE) {
            AVStream *stream = d->format->streams[d->streamIndex];
            int64_t origin = stream->start_time == AV_NOPTS_VALUE ? 0 : stream->start_time;
            int64_t inSource = av_rescale_q(d->frame->pts - origin, stream->time_base,
                                            (AVRational) {1, d->codec->sample_rate});
            int64_t atOutput = av_rescale(inSource, d->sampleRate, d->codec->sample_rate);
            d->startSample = d->seekTarget - atOutput;
            if (d->startSample < 0) d->startSample = 0;
        }
    }

    int maxSamples = (int) av_rescale_rnd(
            swr_get_delay(d->swr, d->codec->sample_rate) + d->frame->nb_samples,
            d->sampleRate, d->codec->sample_rate, AV_ROUND_UP);
    if (maxSamples <= 0) return 0;

    uint8_t *buffer = NULL;
    int rc = av_samples_alloc(&buffer, NULL, d->channels, maxSamples, AV_SAMPLE_FMT_S16, 0);
    if (rc < 0) { set_error(d, "无法分配输出缓冲", rc); return -1; }

    int produced = swr_convert(d->swr, &buffer, maxSamples,
                               (const uint8_t **) d->frame->extended_data, d->frame->nb_samples);
    if (produced < 0) {
        av_freep(&buffer);
        set_error(d, "重采样失败", produced);
        return -1;
    }

    /* Honour startAtMs for whatever the seek could not skip. */
    int skip = 0;
    if (d->startSample > 0) {
        if (d->startSample >= produced) {
            d->startSample -= produced;
            av_freep(&buffer);
            return 0;
        }
        skip = (int) (d->startSample * d->channels);
        d->startSample = 0;
    }

    int samples = produced * d->channels - skip;
    if (samples > capacity) samples = capacity;
    if (samples > 0) {
        memcpy(out, (int16_t *) buffer + skip, (size_t) samples * sizeof(int16_t));
        d->position += samples / d->channels;
    }
    av_freep(&buffer);
    return samples > 0 ? samples : 0;
}

/* Reads one PCM frame. Returns samples per interleaved buffer, 0 at EOF, < 0 on error. */
static int decoder_read(AudioDecoder *d, short *out, int capacity) {
    if (!d || capacity <= 0) return -1;
    if (d->eof) return 0;

    while (!d->stop) {
        int rc = avcodec_receive_frame(d->codec, d->frame);
        if (rc == 0) {
            int samples = convert_frame(d, out, capacity);
            av_frame_unref(d->frame);
            if (samples < 0) return -1;
            if (samples > 0) return samples;
            continue;   /* frame fully consumed by the start-offset skip */
        }
        if (rc == AVERROR_EOF) {
            /* Flush the resampler tail before reporting EOF. */
            if (d->swr && ensure_swr(d) == 0) {
                uint8_t *buffer = NULL;
                int maxSamples = (int) av_rescale_rnd(swr_get_delay(d->swr, d->outRate) + 256,
                                                      d->outRate, d->outRate, AV_ROUND_UP);
                if (av_samples_alloc(&buffer, NULL, d->channels, maxSamples, AV_SAMPLE_FMT_S16, 0) >= 0) {
                    int produced = swr_convert(d->swr, &buffer, maxSamples, NULL, 0);
                    if (produced > 0) {
                        int samples = produced * d->channels;
                        if (samples > capacity) samples = capacity;
                        memcpy(out, buffer, (size_t) samples * sizeof(int16_t));
                        d->position += samples / d->channels;
                        av_freep(&buffer);
                        return samples;
                    }
                    av_freep(&buffer);
                }
            }
            d->eof = 1;
            return 0;
        }
        if (rc != AVERROR(EAGAIN)) {
            set_error(d, "解码失败", rc);
            return -1;
        }

        rc = av_read_frame(d->format, d->packet);
        if (rc < 0) {
            av_packet_unref(d->packet);
            if (rc == AVERROR_EOF || avio_feof(d->format->pb)) {
                avcodec_send_packet(d->codec, NULL);   /* enter drain mode */
                continue;
            }
            set_error(d, "读取音频数据失败", rc);
            return -1;
        }
        if (d->packet->stream_index != d->streamIndex) {
            av_packet_unref(d->packet);
            continue;
        }
        rc = avcodec_send_packet(d->codec, d->packet);
        av_packet_unref(d->packet);
        if (rc < 0 && rc != AVERROR(EAGAIN)) {
            set_error(d, "送入解码数据失败", rc);
            return -1;
        }
    }
    return 0;   /* stop flag set */
}

/* ------------------------------------------------------------------ JNI ---- */

static inline AudioDecoder *from_handle(jlong handle) {
    return (AudioDecoder *) (intptr_t) handle;
}

JNIEXPORT jstring JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_parser_NativeAudioCodec_nativeVersion(JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buffer[128];
    snprintf(buffer, sizeof(buffer), "FFmpeg %s / avcodec %u", av_version_info(), avcodec_version());
    return (*env)->NewStringUTF(env, buffer);
}

/*
 * Lists the demuxers this build actually carries, e.g. "mov,ogg,mp3,flac".
 * The music player surfaces this so a missing demuxer is visible instead of looking like
 * "every song is broken".
 */
JNIEXPORT jstring JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_parser_NativeAudioCodec_nativeFormats(JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buffer[512];
    size_t used = 0;
    buffer[0] = '\0';
    void *iterator = NULL;
    const AVInputFormat *format;
    while ((format = av_demuxer_iterate(&iterator)) != NULL) {
        if (format->name == NULL) continue;
        /* Names are comma separated alias lists ("mov,mp4,m4a,..."); report the primary alias. */
        size_t length = strcspn(format->name, ",");
        if (length == 0) continue;
        if (used + length + 2 >= sizeof(buffer)) break;
        if (used > 0) buffer[used++] = ',';
        memcpy(buffer + used, format->name, length);
        used += length;
        buffer[used] = '\0';
    }
    return (*env)->NewStringUTF(env, buffer);
}

JNIEXPORT jlong JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_parser_NativeAudioCodec_nativeOpen(
        JNIEnv *env, jclass clazz, jstring jpath, jlong startAtMs,
        jint wantRate, jint wantChannels) {
    (void) clazz;
    if (!jpath) return 0;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return 0;
    AudioDecoder *d = decoder_open(path, startAtMs, wantRate, wantChannels);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return (jlong) (intptr_t) d;
}

JNIEXPORT jint JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_parser_NativeAudioCodec_nativeReadFrame(
        JNIEnv *env, jclass clazz, jlong handle, jshortArray out, jint capacity) {
    (void) clazz;
    AudioDecoder *d = from_handle(handle);
    if (!d || !out) return -1;
    if (capacity <= 0 || capacity > MAX_OUT_SAMPLES) capacity = MAX_OUT_SAMPLES;

    jshort *samples = (*env)->GetShortArrayElements(env, out, NULL);
    if (!samples) return -1;
    int rc = decoder_read(d, (short *) samples, capacity);
    (*env)->ReleaseShortArrayElements(env, out, samples, 0);
    return rc;
}

JNIEXPORT jint JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_parser_NativeAudioCodec_nativeSampleRate(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    AudioDecoder *d = from_handle(handle);
    return d ? d->sampleRate : 0;
}

JNIEXPORT jint JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_parser_NativeAudioCodec_nativeChannels(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    AudioDecoder *d = from_handle(handle);
    return d ? d->channels : 0;
}

JNIEXPORT jlong JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_parser_NativeAudioCodec_nativeTotalSamples(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    AudioDecoder *d = from_handle(handle);
    return d ? d->totalSamples : 0;
}

JNIEXPORT jlong JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_parser_NativeAudioCodec_nativePositionSamples(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    AudioDecoder *d = from_handle(handle);
    return d ? d->positionBase + d->position : 0;
}

JNIEXPORT void JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_parser_NativeAudioCodec_nativeSetStop(
        JNIEnv *env, jclass clazz, jlong handle, jboolean stop) {
    (void) env; (void) clazz;
    AudioDecoder *d = from_handle(handle);
    if (d) d->stop = stop ? 1 : 0;
}

JNIEXPORT jstring JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_parser_NativeAudioCodec_nativeLastError(JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    AudioDecoder *d = from_handle(handle);
    if (!d || d->lastError[0] == '\0') return NULL;
    return (*env)->NewStringUTF(env, d->lastError);
}

JNIEXPORT void JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_parser_NativeAudioCodec_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    decoder_free(from_handle(handle));
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm; (void) reserved;
    LOGI("libliquidbounce_audiocodec loaded (%s)", av_version_info());
    return JNI_VERSION_1_6;
}
