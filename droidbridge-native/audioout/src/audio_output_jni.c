/*
 * libliquidbounce_audioout  --  Android PCM playback sink for the Music module.
 *
 * WHY THIS EXISTS
 * ---------------
 * The Java side used to reach `android.media.AudioTrack` with `Class.forName`. That can never work
 * in a launcher like Pojav/Zalith: the game runs on the launcher's own OpenJDK, whose bootclasspath
 * only contains java.* classes. `android.media.AudioTrack` lives in the *platform* ART boot image,
 * which the launcher never loads, so the lookup fails with ClassNotFoundException and every track
 * died on "Android AudioTrack API 不可用" right after the decoder had opened the file fine.
 *
 * Android's native NDK API, however, is fully usable from any .so loaded into the process:
 *   AAudio  (API 26+, <aaudio/AAudio.h>)  -- preferred
 *   OpenSL ES (API 9+, <SLES/OpenSLES.h>) -- fallback
 * Both are system libraries the app's own process already links against, so nothing has to reach
 * into the Java framework at all.
 *
 * The JNI surface is deliberately tiny and blocking-friendly, because the Kotlin AudioEngine writes
 * from a single worker thread:
 *
 *   String nativeBackend()                      -> "AAudio 1.2" / "OpenSL ES" / null when unavailable
 *   long   nativeOpen(int rate, int channels)   -> handle, 0 = failed (see nativeLastError())
 *   int    nativeWrite(long h, byte[] pcm, int offset, int length)  -> bytes accepted, <0 = error
 *   int    nativeDrain(long h, int timeoutMs)   -> 1 drained, 0 timeout, <0 error
 *   void   nativePause(long h, boolean pause)
 *   int    nativePlayedFrames(long h)           -> total frames handed to the device (int, wraps)
 *   void   nativeFlush(long h)
 *   void   nativeClose(long h)
 *   String nativeLastError(long h)
 *
 * PCM is always interleaved signed 16-bit little endian, which is what both backends want.
 *
 * IMPORTANT: the ring buffer is drained by the device callback, and `nativeWrite` blocks while it is
 * full. That is the back-pressure the decoder worker relies on to keep roughly a second of audio
 * queued; it must never be called from the render thread.
 */

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <pthread.h>

/*
 * AAudio (API 26+) is reached through dlopen instead of a hard link, so this .so still loads on an
 * older device: libaaudio.so does not exist there, and a NEEDED entry on it would make the whole
 * library fail to load — which would also take the OpenSL ES fallback down with it.
 */
#if defined(__ANDROID_API__) && __ANDROID_API__ >= 26
#define LBAO_HAVE_AAUDIO 1
#include <aaudio/AAudio.h>
#else
#define LBAO_HAVE_AAUDIO 0
#endif

/* OpenSL ES is available from API 9 and is still present on every current Android release. */
#define LBAO_HAVE_OPENSL 1
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#define LOG_TAG "LBAudioOut"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Roughly one second of queued audio, clamped so a 8 kHz mono stream does not get a huge buffer and
 * a 192 kHz stereo stream does not get a tiny one. */
#define MIN_RING_BYTES (16 * 1024)
#define MAX_RING_BYTES (1024 * 1024)

#if LBAO_HAVE_AAUDIO
/*
 * Everything AAudio, resolved once. A null entry means the platform's libaaudio.so does not provide
 * that symbol, in which case AAudio is treated as unavailable and OpenSL ES takes over.
 */
typedef struct AaudioApi {
    int available;
    aaudio_result_t (*createStreamBuilder)(AAudioStreamBuilder **);
    void (*builderSetDirection)(AAudioStreamBuilder *, aaudio_direction_t);
    void (*builderSetPerformanceMode)(AAudioStreamBuilder *, aaudio_performance_mode_t);
    void (*builderSetSharingMode)(AAudioStreamBuilder *, aaudio_sharing_mode_t);
    void (*builderSetFormat)(AAudioStreamBuilder *, aaudio_format_t);
    void (*builderSetSampleRate)(AAudioStreamBuilder *, int32_t);
    void (*builderSetChannelCount)(AAudioStreamBuilder *, int32_t);
    void (*builderSetDataCallback)(AAudioStreamBuilder *, AAudioStream_dataCallback, void *);
    aaudio_result_t (*builderOpenStream)(AAudioStreamBuilder *, AAudioStream **);
    void (*builderDelete)(AAudioStreamBuilder *);
    aaudio_result_t (*requestStart)(AAudioStream *);
    aaudio_result_t (*requestPause)(AAudioStream *);
    aaudio_result_t (*requestStop)(AAudioStream *);
    aaudio_result_t (*closeStream)(AAudioStream *);
    int32_t (*getSampleRate)(AAudioStream *);
    int32_t (*getChannelCount)(AAudioStream *);
} AaudioApi;

static AaudioApi g_aaudio;
static pthread_once_t g_aaudioOnce = PTHREAD_ONCE_INIT;

static void aaudio_resolve(void) {
    void *library = dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        /* API < 26: expected, not an error worth a warning. */
        g_aaudio.available = 0;
        return;
    }
#define LBAO_SYM(field, name) g_aaudio.field = (void *) dlsym(library, name)
    LBAO_SYM(createStreamBuilder, "AAudio_createStreamBuilder");
    LBAO_SYM(builderSetDirection, "AAudioStreamBuilder_setDirection");
    LBAO_SYM(builderSetPerformanceMode, "AAudioStreamBuilder_setPerformanceMode");
    LBAO_SYM(builderSetSharingMode, "AAudioStreamBuilder_setSharingMode");
    LBAO_SYM(builderSetFormat, "AAudioStreamBuilder_setFormat");
    LBAO_SYM(builderSetSampleRate, "AAudioStreamBuilder_setSampleRate");
    LBAO_SYM(builderSetChannelCount, "AAudioStreamBuilder_setChannelCount");
    LBAO_SYM(builderSetDataCallback, "AAudioStreamBuilder_setDataCallback");
    LBAO_SYM(builderOpenStream, "AAudioStreamBuilder_openStream");
    LBAO_SYM(builderDelete, "AAudioStreamBuilder_delete");
    LBAO_SYM(requestStart, "AAudioStream_requestStart");
    LBAO_SYM(requestPause, "AAudioStream_requestPause");
    LBAO_SYM(requestStop, "AAudioStream_requestStop");
    LBAO_SYM(closeStream, "AAudioStream_close");
    LBAO_SYM(getSampleRate, "AAudioStream_getSampleRate");
    LBAO_SYM(getChannelCount, "AAudioStream_getChannelCount");
#undef LBAO_SYM

    g_aaudio.available = g_aaudio.createStreamBuilder && g_aaudio.builderSetDirection &&
        g_aaudio.builderSetPerformanceMode && g_aaudio.builderSetSharingMode &&
        g_aaudio.builderSetFormat && g_aaudio.builderSetSampleRate &&
        g_aaudio.builderSetChannelCount && g_aaudio.builderSetDataCallback &&
        g_aaudio.builderOpenStream && g_aaudio.builderDelete && g_aaudio.requestStart &&
        g_aaudio.requestPause && g_aaudio.requestStop && g_aaudio.closeStream &&
        g_aaudio.getSampleRate && g_aaudio.getChannelCount;
    if (!g_aaudio.available) {
        LOGW("libaaudio.so is present but incomplete; using OpenSL ES");
    }
}

static int aaudio_api(void) {
    pthread_once(&g_aaudioOnce, aaudio_resolve);
    return g_aaudio.available;
}
#endif /* LBAO_HAVE_AAUDIO */

typedef enum {
    BACKEND_NONE = 0,
    BACKEND_AAUDIO = 1,
    BACKEND_OPENSL = 2,
} Backend;

typedef struct AudioSink {
    Backend backend;
    int rate;
    int channels;
    int frameSize;          /* bytes per frame */
    int ringBytes;

    /* Shared ring buffer. `writePos`/`readPos` are byte offsets inside `ring`, monotonically
     * increasing and only ever compared modulo `ringBytes`. */
    uint8_t *ring;
    int64_t writePos;
    int64_t readPos;
    int64_t playedFrames;

    pthread_mutex_t lock;
    pthread_cond_t dataReady;   /* signalled by nativeWrite, waited on by the AAudio callback */
    pthread_cond_t spaceReady;  /* signalled by the callback, waited on by nativeWrite */

    int paused;
    int closed;
    int failed;
    int running;

    char lastError[256];

#if LBAO_HAVE_AAUDIO
    AAudioStream *aaudio;
#endif
    /* OpenSL ES objects. Kept as void* so the struct compiles even when OpenSL headers are absent. */
    void *slEngine;
    void *slMix;
    void *slPlayer;
    void *slQueue;
    pthread_t feeder;
    int feederStarted;
} AudioSink;

static void set_error(AudioSink *sink, const char *message) {
    if (!sink) return;
    pthread_mutex_lock(&sink->lock);
    snprintf(sink->lastError, sizeof(sink->lastError), "%s", message);
    sink->failed = 1;
    pthread_mutex_unlock(&sink->lock);
}

static void set_error_code(AudioSink *sink, const char *message, int code) {
    if (!sink) return;
    pthread_mutex_lock(&sink->lock);
    snprintf(sink->lastError, sizeof(sink->lastError), "%s (code %d)", message, code);
    sink->failed = 1;
    pthread_mutex_unlock(&sink->lock);
}

static void now_plus_ms(struct timespec *ts, int ms) {
    clock_gettime(CLOCK_REALTIME, ts);
    ts->tv_sec += ms / 1000;
    ts->tv_nsec += (long) (ms % 1000) * 1000000L;
    if (ts->tv_nsec >= 1000000000L) {
        ts->tv_sec += 1;
        ts->tv_nsec -= 1000000000L;
    }
}

/* Copies at most `wanted` bytes out of the ring; returns how many were available. */
static int ring_read(AudioSink *sink, void *dest, int wanted) {
    int available = (int) (sink->writePos - sink->readPos);
    if (available <= 0) return 0;
    if (wanted > available) wanted = available;
    if (wanted > sink->ringBytes) wanted = sink->ringBytes;

    int offset = (int) (sink->readPos % sink->ringBytes);
    int first = sink->ringBytes - offset;
    if (first > wanted) first = wanted;
    memcpy(dest, sink->ring + offset, (size_t) first);
    if (wanted > first) {
        memcpy((uint8_t *) dest + first, sink->ring, (size_t) (wanted - first));
    }
    sink->readPos += wanted;
    sink->playedFrames += wanted / sink->frameSize;
    pthread_cond_signal(&sink->spaceReady);
    return wanted;
}

#if LBAO_HAVE_AAUDIO
/*
 * AAudio pulls from this callback. It is called on a high-priority audio thread, so it must never
 * block indefinitely: it copies whatever is queued and zero-fills the rest (a short gap is far
 * better than a glitch-free-but-frozen stream).
 *
 * The callback waits for data only when the sink is empty and still playing, and only for a short,
 * bounded time, which removes the initial "first frame crackles" case without risking a watchdog.
 */
static aaudio_data_callback_result_t aaudio_callback(
        AAudioStream *stream, void *userData, void *audioData, int32_t numFrames) {
    (void) stream;
    AudioSink *sink = (AudioSink *) userData;
    if (!sink || sink->closed) {
        memset(audioData, 0, (size_t) numFrames * (size_t) sink->frameSize);
        return AAUDIO_CALLBACK_RESULT_STOP;
    }

    int wanted = numFrames * sink->frameSize;
    int got = 0;

    pthread_mutex_lock(&sink->lock);
    if (!sink->paused && !sink->failed) {
        got = ring_read(sink, audioData, wanted);
        if (got == 0) {
            /* Nothing queued yet: wait for the writer for at most 20 ms so the very first callback
             * after open() does not produce a silent burst. */
            struct timespec deadline;
            now_plus_ms(&deadline, 20);
            while (got == 0 && !sink->paused && !sink->closed && !sink->failed) {
                if (pthread_cond_timedwait(&sink->dataReady, &sink->lock, &deadline) != 0) break;
                got = ring_read(sink, audioData, wanted);
            }
        }
    }
    pthread_mutex_unlock(&sink->lock);

    if (got < wanted) {
        memset((uint8_t *) audioData + got, 0, (size_t) (wanted - got));
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static int aaudio_open(AudioSink *sink) {
    if (!aaudio_api()) {
        snprintf(sink->lastError, sizeof(sink->lastError), "AAudio 不可用（API < 26 或 libaaudio.so 缺失）");
        return -1;
    }

    AAudioStreamBuilder *builder = NULL;
    aaudio_result_t result = g_aaudio.createStreamBuilder(&builder);
    if (result != AAUDIO_OK || !builder) {
        snprintf(sink->lastError, sizeof(sink->lastError), "AAudio 无法创建 StreamBuilder（code %d）", result);
        return -1;
    }

    g_aaudio.builderSetDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    g_aaudio.builderSetPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    g_aaudio.builderSetSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    g_aaudio.builderSetFormat(builder, AAUDIO_FORMAT_PCM_I16);
    g_aaudio.builderSetSampleRate(builder, sink->rate);
    g_aaudio.builderSetChannelCount(builder, sink->channels);
    g_aaudio.builderSetDataCallback(builder, aaudio_callback, sink);

    result = g_aaudio.builderOpenStream(builder, &sink->aaudio);
    g_aaudio.builderDelete(builder);
    if (result != AAUDIO_OK || !sink->aaudio) {
        snprintf(sink->lastError, sizeof(sink->lastError), "AAudio 无法打开输出流（code %d）", result);
        sink->aaudio = NULL;
        return -1;
    }

    /* The device may settle on a different rate/channel count. Re-read it so the ring arithmetic
     * matches what the callback actually delivers. */
    int32_t actualRate = g_aaudio.getSampleRate(sink->aaudio);
    int32_t actualChannels = g_aaudio.getChannelCount(sink->aaudio);
    if (actualRate > 0) sink->rate = actualRate;
    if (actualChannels > 0) sink->channels = actualChannels;
    sink->frameSize = sink->channels * 2;

    result = g_aaudio.requestStart(sink->aaudio);
    if (result != AAUDIO_OK) {
        snprintf(sink->lastError, sizeof(sink->lastError), "AAudio 无法启动输出流（code %d）", result);
        g_aaudio.closeStream(sink->aaudio);
        sink->aaudio = NULL;
        return -1;
    }
    return 0;
}

#endif /* LBAO_HAVE_AAUDIO */

/*
 * OpenSL ES fallback. OpenSL has no pull callback for a buffer queue, so the sink runs its own
 * feeder thread that copies ring -> queued buffers exactly like the AAudio callback would.
 */
#if LBAO_HAVE_OPENSL

#define SL_QUEUE_COUNT 4
#define SL_QUEUE_FRAMES 1024

static void *opensl_feeder(void *arg) {
    AudioSink *sink = (AudioSink *) arg;
    int bytesPerBuffer = SL_QUEUE_FRAMES * sink->frameSize;
    uint8_t *buffer = (uint8_t *) malloc((size_t) bytesPerBuffer);
    if (!buffer) return NULL;

    SLAndroidSimpleBufferQueueItf queue = (SLAndroidSimpleBufferQueueItf) sink->slQueue;
    while (!sink->closed) {
        pthread_mutex_lock(&sink->lock);
        if (sink->paused || sink->failed) {
            struct timespec delay;
            now_plus_ms(&delay, 20);
            pthread_cond_timedwait(&sink->dataReady, &sink->lock, &delay);
            pthread_mutex_unlock(&sink->lock);
            continue;
        }
        int got = ring_read(sink, buffer, bytesPerBuffer);
        pthread_mutex_unlock(&sink->lock);

        if (got <= 0) {
            /* Nothing queued: sleep briefly instead of spinning, and wake up early on new data. */
            pthread_mutex_lock(&sink->lock);
            struct timespec delay;
            now_plus_ms(&delay, 5);
            pthread_cond_timedwait(&sink->dataReady, &sink->lock, &delay);
            pthread_mutex_unlock(&sink->lock);
            continue;
        }
        if (got < bytesPerBuffer) {
            memset(buffer + got, 0, (size_t) (bytesPerBuffer - got));
        }
        if ((*queue)->Enqueue(queue, buffer, (SLuint32) bytesPerBuffer) != SL_RESULT_SUCCESS) {
            pthread_mutex_lock(&sink->lock);
            struct timespec delay;
            now_plus_ms(&delay, 5);
            pthread_cond_timedwait(&sink->spaceReady, &sink->lock, &delay);
            pthread_mutex_unlock(&sink->lock);
        }
    }
    free(buffer);
    return NULL;
}

static int opensl_open(AudioSink *sink) {
    SLObjectItf engineObject = NULL;
    if (slCreateEngine(&engineObject, 0, NULL, 0, NULL, NULL) != SL_RESULT_SUCCESS || !engineObject) {
        snprintf(sink->lastError, sizeof(sink->lastError), "OpenSL 无法创建引擎");
        return -1;
    }
    if ((*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) {
        snprintf(sink->lastError, sizeof(sink->lastError), "OpenSL 引擎 Realize 失败");
        (*engineObject)->Destroy(engineObject);
        return -1;
    }
    sink->slEngine = engineObject;

    SLEngineItf engine = NULL;
    if ((*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engine) != SL_RESULT_SUCCESS || !engine) {
        snprintf(sink->lastError, sizeof(sink->lastError), "OpenSL 引擎接口不可用");
        return -1;
    }

    SLObjectItf mixObject = NULL;
    if ((*engine)->CreateOutputMix(engine, &mixObject, 0, NULL, NULL) != SL_RESULT_SUCCESS || !mixObject) {
        snprintf(sink->lastError, sizeof(sink->lastError), "OpenSL 无法创建 OutputMix");
        return -1;
    }
    if ((*mixObject)->Realize(mixObject, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) {
        snprintf(sink->lastError, sizeof(sink->lastError), "OpenSL OutputMix Realize 失败");
        return -1;
    }
    sink->slMix = mixObject;

    SLDataLocator_AndroidSimpleBufferQueue locator = {
        SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, SL_QUEUE_COUNT
    };
    SLDataFormat_PCM format = {
        SL_DATAFORMAT_PCM,
        (SLuint32) sink->channels,
        (SLuint32) (sink->rate * 1000),
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_PCMSAMPLEFORMAT_FIXED_16,
        sink->channels == 1 ? SL_SPEAKER_FRONT_CENTER
                            : (SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT),
        SL_BYTEORDER_LITTLEENDIAN,
    };
    SLDataSource source = { &locator, &format };
    SLDataLocator_OutputMix outputLocator = { SL_DATALOCATOR_OUTPUTMIX, mixObject };
    SLDataSink output = { &outputLocator, NULL };

    const SLInterfaceID ids[] = { SL_IID_ANDROIDSIMPLEBUFFERQUEUE };
    const SLboolean required[] = { SL_BOOLEAN_TRUE };

    SLObjectItf playerObject = NULL;
    SLresult result = (*engine)->CreateAudioPlayer(engine, &playerObject, &source, &output, 1, ids, required);
    if (result != SL_RESULT_SUCCESS || !playerObject) {
        snprintf(sink->lastError, sizeof(sink->lastError), "OpenSL 无法创建 AudioPlayer（code %d）", result);
        return -1;
    }
    if ((*playerObject)->Realize(playerObject, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) {
        snprintf(sink->lastError, sizeof(sink->lastError), "OpenSL AudioPlayer Realize 失败");
        return -1;
    }

    SLAndroidSimpleBufferQueueItf queue = NULL;
    if ((*playerObject)->GetInterface(playerObject, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &queue)
            != SL_RESULT_SUCCESS || !queue) {
        snprintf(sink->lastError, sizeof(sink->lastError), "OpenSL BufferQueue 接口不可用");
        return -1;
    }
    sink->slPlayer = playerObject;
    sink->slQueue = queue;

    SLPlayItf play = NULL;
    if ((*playerObject)->GetInterface(playerObject, SL_IID_PLAY, &play) != SL_RESULT_SUCCESS || !play) {
        snprintf(sink->lastError, sizeof(sink->lastError), "OpenSL Play 接口不可用");
        return -1;
    }
    if ((*play)->SetPlayState(play, SL_PLAYSTATE_PLAYING) != SL_RESULT_SUCCESS) {
        snprintf(sink->lastError, sizeof(sink->lastError), "OpenSL 无法开始播放");
        return -1;
    }
    if (pthread_create(&sink->feeder, NULL, opensl_feeder, sink) != 0) {
        snprintf(sink->lastError, sizeof(sink->lastError), "OpenSL 无法启动喂数据线程");
        return -1;
    }
    sink->feederStarted = 1;
    return 0;
}
#endif /* LBAO_HAVE_OPENSL */

/* ------------------------------------------------------------------ JNI ---- */

static AudioSink *from_handle(jlong handle) {
    return (AudioSink *) (intptr_t) handle;
}

/*
 * Which backend this build can offer. Reported as a string so the music window can show it and a
 * Pojav log explains itself without anyone having to guess.
 */
JNIEXPORT jstring JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_android_NativeAudioOutput_nativeBackend(JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buffer[128];
#if LBAO_HAVE_AAUDIO
    if (aaudio_api()) {
        snprintf(buffer, sizeof(buffer), "AAudio");
        return (*env)->NewStringUTF(env, buffer);
    }
#endif
#if LBAO_HAVE_OPENSL
    snprintf(buffer, sizeof(buffer), "OpenSL ES");
#else
    snprintf(buffer, sizeof(buffer), "none");
#endif
    return (*env)->NewStringUTF(env, buffer);
}

JNIEXPORT jlong JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_android_NativeAudioOutput_nativeOpen(
        JNIEnv *env, jclass clazz, jint rate, jint channels) {
    (void) env; (void) clazz;
    if (rate < 8000 || rate > 192000) return 0;
    if (channels < 1) channels = 1;
    if (channels > 2) channels = 2;

    AudioSink *sink = (AudioSink *) calloc(1, sizeof(AudioSink));
    if (!sink) return 0;

    sink->rate = rate;
    sink->channels = channels;
    sink->frameSize = channels * 2;
    sink->backend = BACKEND_NONE;

    long wanted = (long) rate * sink->frameSize; /* ~1 second */
    if (wanted < MIN_RING_BYTES) wanted = MIN_RING_BYTES;
    if (wanted > MAX_RING_BYTES) wanted = MAX_RING_BYTES;
    sink->ringBytes = (int) (wanted - (wanted % sink->frameSize));
    sink->ring = (uint8_t *) calloc(1, (size_t) sink->ringBytes);
    if (!sink->ring) {
        free(sink);
        return 0;
    }

    pthread_mutex_init(&sink->lock, NULL);
    pthread_cond_init(&sink->dataReady, NULL);
    pthread_cond_init(&sink->spaceReady, NULL);

#if LBAO_HAVE_AAUDIO
    if (aaudio_api() && aaudio_open(sink) == 0) {
        sink->backend = BACKEND_AAUDIO;
        LOGI("AAudio sink open: %d Hz / %d ch", sink->rate, sink->channels);
        return (jlong) (intptr_t) sink;
    }
    LOGW("AAudio failed: %s", sink->lastError);
#endif
#if LBAO_HAVE_OPENSL
    if (opensl_open(sink) == 0) {
        sink->backend = BACKEND_OPENSL;
        LOGI("OpenSL sink open: %d Hz / %d ch", sink->rate, sink->channels);
        return (jlong) (intptr_t) sink;
    }
    LOGW("OpenSL failed: %s", sink->lastError);
#endif

    if (sink->lastError[0] == '\0') {
        snprintf(sink->lastError, sizeof(sink->lastError), "本机没有可用的原生音频后端（AAudio/OpenSL 均不可用）");
    }
    LOGE("no native audio backend: %s", sink->lastError);
    pthread_cond_destroy(&sink->spaceReady);
    pthread_cond_destroy(&sink->dataReady);
    pthread_mutex_destroy(&sink->lock);
    free(sink->ring);
    free(sink);
    return 0;
}

JNIEXPORT jint JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_android_NativeAudioOutput_nativeWrite(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray pcm, jint offset, jint length) {
    (void) clazz;
    AudioSink *sink = from_handle(handle);
    if (!sink || !pcm || length <= 0) return 0;
    if (sink->failed || sink->closed) return -1;

    int aligned = length - (length % sink->frameSize);
    if (aligned <= 0) return 0;

    jbyte *bytes = (*env)->GetByteArrayElements(env, pcm, NULL);
    if (!bytes) return -1;

    int written = 0;
    while (written < aligned) {
        pthread_mutex_lock(&sink->lock);
        if (sink->failed || sink->closed) {
            pthread_mutex_unlock(&sink->lock);
            break;
        }
        if (sink->paused) {
            pthread_mutex_unlock(&sink->lock);
            break;
        }
        int space = sink->ringBytes - (int) (sink->writePos - sink->readPos);
        if (space <= 0) {
            /* Queue full: wait for the device to consume something. Bounded so a dead device cannot
             * wedge the worker forever. */
            struct timespec deadline;
            now_plus_ms(&deadline, 500);
            pthread_cond_timedwait(&sink->spaceReady, &sink->lock, &deadline);
            pthread_mutex_unlock(&sink->lock);
            continue;
        }
        int chunk = aligned - written;
        if (chunk > space) chunk = space;
        chunk -= chunk % sink->frameSize;
        if (chunk <= 0) {
            pthread_mutex_unlock(&sink->lock);
            continue;
        }

        int start = (int) (sink->writePos % sink->ringBytes);
        int first = sink->ringBytes - start;
        if (first > chunk) first = chunk;
        memcpy(sink->ring + start, bytes + offset + written, (size_t) first);
        if (chunk > first) {
            memcpy(sink->ring, bytes + offset + written + first, (size_t) (chunk - first));
        }
        sink->writePos += chunk;
        written += chunk;
        pthread_cond_signal(&sink->dataReady);
        pthread_mutex_unlock(&sink->lock);
    }

    (*env)->ReleaseByteArrayElements(env, pcm, bytes, JNI_ABORT);
    return written;
}

JNIEXPORT void JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_android_NativeAudioOutput_nativePause(
        JNIEnv *env, jclass clazz, jlong handle, jboolean pause) {
    (void) env; (void) clazz;
    AudioSink *sink = from_handle(handle);
    if (!sink) return;
    pthread_mutex_lock(&sink->lock);
    sink->paused = pause ? 1 : 0;
    if (!pause) pthread_cond_signal(&sink->dataReady);
    pthread_mutex_unlock(&sink->lock);

#if LBAO_HAVE_AAUDIO
    if (sink->backend == BACKEND_AAUDIO && sink->aaudio) {
        if (aaudio_api()) {
            if (pause) g_aaudio.requestPause(sink->aaudio);
            else g_aaudio.requestStart(sink->aaudio);
        }
    }
#endif
}

JNIEXPORT jint JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_android_NativeAudioOutput_nativePlayedFrames(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    AudioSink *sink = from_handle(handle);
    if (!sink) return 0;
    pthread_mutex_lock(&sink->lock);
    long frames = (long) sink->playedFrames;
    pthread_mutex_unlock(&sink->lock);
    return (jint) (frames & 0x7FFFFFFF);
}

JNIEXPORT jint JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_android_NativeAudioOutput_nativeDrain(
        JNIEnv *env, jclass clazz, jlong handle, jint timeoutMs) {
    (void) env; (void) clazz;
    AudioSink *sink = from_handle(handle);
    if (!sink) return -1;

    struct timespec deadline;
    now_plus_ms(&deadline, timeoutMs > 0 ? timeoutMs : 5000);
    for (;;) {
        pthread_mutex_lock(&sink->lock);
        int pending = (int) (sink->writePos - sink->readPos);
        int paused = sink->paused;
        int failed = sink->failed || sink->closed;
        pthread_mutex_unlock(&sink->lock);
        if (failed) return -1;
        if (pending <= 0) return 1;
        if (paused) return 0;
        struct timespec now;
        clock_gettime(CLOCK_REALTIME, &now);
        if (now.tv_sec > deadline.tv_sec ||
            (now.tv_sec == deadline.tv_sec && now.tv_nsec >= deadline.tv_nsec)) {
            return 0;
        }
        struct timespec nap;
        now_plus_ms(&nap, 10);
        pthread_mutex_lock(&sink->lock);
        pthread_cond_timedwait(&sink->spaceReady, &sink->lock, &nap);
        pthread_mutex_unlock(&sink->lock);
    }
}

JNIEXPORT void JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_android_NativeAudioOutput_nativeFlush(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    AudioSink *sink = from_handle(handle);
    if (!sink) return;
    pthread_mutex_lock(&sink->lock);
    sink->readPos = sink->writePos;
    pthread_cond_signal(&sink->spaceReady);
    pthread_mutex_unlock(&sink->lock);
}

JNIEXPORT jstring JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_android_NativeAudioOutput_nativeLastError(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    AudioSink *sink = from_handle(handle);
    if (!sink || sink->lastError[0] == '\0') return NULL;
    return (*env)->NewStringUTF(env, sink->lastError);
}

JNIEXPORT void JNICALL
Java_net_ccbluex_liquidbounce_utils_audio_android_NativeAudioOutput_nativeClose(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    AudioSink *sink = from_handle(handle);
    if (!sink) return;

    pthread_mutex_lock(&sink->lock);
    sink->closed = 1;
    pthread_cond_broadcast(&sink->dataReady);
    pthread_cond_broadcast(&sink->spaceReady);
    pthread_mutex_unlock(&sink->lock);

#if LBAO_HAVE_AAUDIO
    if (sink->backend == BACKEND_AAUDIO && sink->aaudio) {
        if (aaudio_api()) {
            g_aaudio.requestStop(sink->aaudio);
            g_aaudio.closeStream(sink->aaudio);
        }
        sink->aaudio = NULL;
    }
#endif
#if LBAO_HAVE_OPENSL
    if (sink->backend == BACKEND_OPENSL) {
        if (sink->feederStarted) {
            pthread_join(sink->feeder, NULL);
            sink->feederStarted = 0;
        }
        if (sink->slPlayer) {
            SLObjectItf player = (SLObjectItf) sink->slPlayer;
            SLPlayItf play = NULL;
            if ((*player)->GetInterface(player, SL_IID_PLAY, &play) == SL_RESULT_SUCCESS && play) {
                (*play)->SetPlayState(play, SL_PLAYSTATE_STOPPED);
            }
            (*player)->Destroy(player);
        }
        if (sink->slMix) {
            SLObjectItf mix = (SLObjectItf) sink->slMix;
            (*mix)->Destroy(mix);
        }
        if (sink->slEngine) {
            SLObjectItf engineObject = (SLObjectItf) sink->slEngine;
            (*engineObject)->Destroy(engineObject);
        }
    }
#endif

    pthread_cond_destroy(&sink->spaceReady);
    pthread_cond_destroy(&sink->dataReady);
    pthread_mutex_destroy(&sink->lock);
    free(sink->ring);
    free(sink);
}
