package net.ccbluex.liquidbounce.utils.audio.parser;

import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;
import net.sourceforge.jaad.mp4.MP4Container;
import net.sourceforge.jaad.mp4.api.AudioTrack;
import net.sourceforge.jaad.mp4.api.Frame;
import net.sourceforge.jaad.mp4.api.Movie;
import net.sourceforge.jaad.mp4.api.Track;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * MP4/M4A AAC decoder. Only the AAC samples of the selected file are decoded; video tracks and
 * Minecraft's global mixer are never read as PCM input.
 *
 * <p>JAAD is a pure-Java decoder with no native audio device. This parser is intended for regular
 * MP4/M4A files (including Bilibili's progressive MP4 response), not standalone DASH .m4s
 * fragments.</p>
 */
public final class M4aAudioParser implements PcmAudioParser {
    private static final int MAX_PCM_FRAME_BYTES = 128 * 1024;

    private final RandomAccessFile input;
    private final BooleanSupplier cancelled;
    private final AudioTrack track;
    private final Decoder decoder;
    private final SampleBuffer sampleBuffer = new SampleBuffer();
    private final int sampleRate;
    private final int channels;

    private long positionSamples;
    private boolean closed;

    public M4aAudioParser(File file, long startAtMs, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!file.isFile()) throw new IOException("MP4/M4A 文件不存在");
        if (file.length() > MusicAudioParserFactory.MAX_FILE_BYTES) {
            throw new IOException("音频文件超过 100 MiB 安全上限");
        }

        this.cancelled = cancelled == null ? () -> false : cancelled;
        RandomAccessFile opened = new RandomAccessFile(file, "r");
        AudioTrack selected = null;
        Decoder configured = null;
        int rate = 0;
        int channelCount = 0;
        long seekPositionSamples = 0L;
        try {
            checkCancelled();
            MP4Container container = new MP4Container(opened);
            Movie movie = container.getMovie();
            if (movie == null) throw new IOException("MP4 文件缺少 movie 音轨信息");

            List<Track> tracks = movie.getTracks(AudioTrack.AudioCodec.AAC);
            for (Track candidate : tracks) {
                if (!(candidate instanceof AudioTrack)) continue;
                AudioTrack audio = (AudioTrack) candidate;
                if (!audio.isEnabled() || !audio.isInFile() || audio.getProtection() != null) continue;
                selected = audio;
                break;
            }
            if (selected == null) throw new IOException("MP4 文件没有可用的内嵌 AAC 音轨");

            rate = selected.getSampleRate();
            channelCount = selected.getChannelCount();
            if (rate < 8_000 || rate > 192_000 || channelCount < 1 || channelCount > 2) {
                throw new IOException("不支持的 AAC 音频参数：" + rate + " Hz / " + channelCount + " 声道");
            }

            byte[] decoderInfo;
            try {
                decoderInfo = selected.getDecoderSpecificInfo();
            } catch (RuntimeException missingConfig) {
                throw new IOException("AAC 音轨缺少解码配置", missingConfig);
            }
            if (decoderInfo == null || decoderInfo.length == 0) {
                throw new IOException("AAC 音轨缺少解码配置");
            }
            configured = new Decoder(decoderInfo);

            if (startAtMs > 0L) {
                // For zero we leave the track untouched so decoding begins at its first AAC frame.
                // JAAD's seek returns a nearby frame timestamp (not the exact requested position);
                // reject a result before the target, which indicates its frame scan reached EOF.
                double targetSeconds = startAtMs / 1_000.0;
                double actualSeconds = selected.seek(targetSeconds);
                if (actualSeconds < 0.0 || actualSeconds < targetSeconds) {
                    throw new IOException("seek beyond available MP4 audio");
                }
                seekPositionSamples = Math.max(0L, Math.round(actualSeconds * rate));
            }
        } catch (IOException e) {
            try { opened.close(); } catch (IOException ignored) { }
            throw e;
        } catch (Exception e) {
            try { opened.close(); } catch (IOException ignored) { }
            throw new IOException("MP4/AAC 解码器初始化失败", e);
        }

        this.input = opened;
        this.track = selected;
        this.decoder = configured;
        this.sampleRate = rate;
        this.channels = channelCount;
        this.positionSamples = seekPositionSamples;
    }

    @Override
    public int getSampleRate() {
        return sampleRate;
    }

    @Override
    public int getChannels() {
        return channels;
    }

    /** The MP4 API used here does not expose a reliable AAC sample count for every container. */
    @Override
    public long getTotalSamples() {
        return 0L;
    }

    @Override
    public long getPositionSamples() {
        return positionSamples;
    }

    @Override
    public String getCodec() {
        return "AAC/MP4";
    }

    @Override
    public short[] readFrame() throws IOException {
        ensureOpen();
        checkCancelled();

        final Frame frame;
        try {
            frame = track.readNextFrame();
        } catch (IOException e) {
            throw new IOException("读取 AAC/MP4 音频帧失败", e);
        }
        if (frame == null) return null;
        if (frame.getData() == null || frame.getData().length == 0) {
            throw new IOException("MP4 AAC 音频帧为空");
        }

        try {
            decoder.decodeFrame(frame.getData(), sampleBuffer);
        } catch (Exception e) {
            throw new IOException("AAC 音频帧解码失败", e);
        }
        checkCancelled();

        byte[] pcm = sampleBuffer.getData();
        int outputRate = sampleBuffer.getSampleRate();
        int outputChannels = sampleBuffer.getChannels();
        int bitsPerSample = sampleBuffer.getBitsPerSample();
        if (pcm == null || pcm.length == 0 || (pcm.length & 1) != 0 || pcm.length > MAX_PCM_FRAME_BYTES ||
                outputRate != sampleRate || outputChannels != channels || bitsPerSample != 16) {
            throw new IOException("AAC 解码器输出了不支持的 PCM 格式");
        }

        short[] samples = new short[pcm.length / 2];
        if (sampleBuffer.isBigEndian()) {
            for (int i = 0, j = 0; i < pcm.length; i += 2, j++) {
                samples[j] = (short) (((pcm[i] & 0xff) << 8) | (pcm[i + 1] & 0xff));
            }
        } else {
            for (int i = 0, j = 0; i < pcm.length; i += 2, j++) {
                samples[j] = (short) ((pcm[i] & 0xff) | ((pcm[i + 1] & 0xff) << 8));
            }
        }
        positionSamples += samples.length / channels;
        return samples;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("AAC/MP4 parser is closed");
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("AAC/MP4 decoding cancelled");
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        input.close();
    }
}
