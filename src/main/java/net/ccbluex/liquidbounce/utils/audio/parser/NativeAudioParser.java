package net.ccbluex.liquidbounce.utils.audio.parser;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * {@link PcmAudioParser} on top of {@link NativeAudioCodec}: the bundled .so demuxes and decodes
 * Ogg/Vorbis, Ogg/Opus, fragmented MP4 (.m4s), M4A and everything else FFmpeg's demuxers accept.
 *
 * <p>PCM is interleaved signed 16-bit, matching every other parser in this package, so
 * {@code AudioEngine} can swap decoders without touching its mixing code.</p>
 */
public final class NativeAudioParser implements PcmAudioParser {
    /** Matches AudioEngine's chunking (1152 frames) but rounds up to a whole stereo frame. */
    private static final int CHUNK_SAMPLES = 1152 * 2;
    private static final int MAX_CHUNK_SAMPLES = 65_536 * 2;

    private final String name;
    private final BooleanSupplier cancelled;
    private final short[] buffer = new short[CHUNK_SAMPLES];

    private long handle;
    private int sampleRate;
    private int channels;
    private long totalSamples;
    private long positionSamples;
    private boolean closed;
    private boolean endOfStream;

    public NativeAudioParser(File file, long startAtMs, BooleanSupplier cancelled) throws IOException {
        this(file, startAtMs, 0, 0, cancelled);
    }

    public NativeAudioParser(File file, long startAtMs, int targetRate, int targetChannels,
                             BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!file.isFile()) throw new IOException("音频文件不存在");
        if (file.length() > MusicAudioParserFactory.MAX_FILE_BYTES) {
            throw new IOException("音频文件超过 100 MiB 安全上限");
        }
        this.name = file.getName();
        this.cancelled = cancelled == null ? () -> false : cancelled;

        if (!NativeAudioCodec.isLoaded()) {
            throw new IOException("原生音频解码库未加载" +
                    (NativeAudioCodec.failure() == null ? "" : ": " + NativeAudioCodec.failure()));
        }

        long opened = NativeAudioCodec.nativeOpen(file.getAbsolutePath(), Math.max(0L, startAtMs),
                Math.max(0, targetRate), Math.max(0, targetChannels));
        if (opened == 0L) {
            throw new IOException("原生解码器无法打开 " + name);
        }
        this.handle = opened;
        this.sampleRate = NativeAudioCodec.nativeSampleRate(opened);
        this.channels = NativeAudioCodec.nativeChannels(opened);
        this.totalSamples = NativeAudioCodec.nativeTotalSamples(opened);
        if (sampleRate <= 0 || channels <= 0) {
            close();
            throw new IOException("原生解码器报告了无效的音频参数");
        }
    }

    @Override
    public int getSampleRate() {
        return sampleRate;
    }

    @Override
    public int getChannels() {
        return channels;
    }

    @Override
    public long getTotalSamples() {
        return totalSamples;
    }

    @Override
    public synchronized long getPositionSamples() {
        if (handle != 0L && !closed) {
            positionSamples = NativeAudioCodec.nativePositionSamples(handle);
        }
        return positionSamples;
    }

    @Override
    public String getCodec() {
        return "Native" + (NativeAudioCodec.version().isEmpty() ? "" : " (" + NativeAudioCodec.version() + ")");
    }

    @Override
    public synchronized short[] readFrame() throws IOException {
        if (closed) throw new IOException("解码器已关闭");
        if (endOfStream) return null;
        if (cancelled.getAsBoolean()) {
            throw new InterruptedIOException("音频已取消");
        }

        NativeAudioCodec.nativeSetStop(handle, false);
        int capacity = Math.min(buffer.length, MAX_CHUNK_SAMPLES);
        int samples = NativeAudioCodec.nativeReadFrame(handle, buffer, capacity);
        if (samples < 0) {
            String error = NativeAudioCodec.nativeLastError(handle);
            throw new IOException(error == null ? "原生解码失败" : error);
        }
        if (samples == 0) {
            endOfStream = true;
            return null;
        }

        positionSamples += samples / channels;
        short[] frame = new short[samples];
        System.arraycopy(buffer, 0, frame, 0, samples);
        return frame;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (handle != 0L) {
            NativeAudioCodec.nativeSetStop(handle, true);
            NativeAudioCodec.nativeClose(handle);
            handle = 0L;
        }
    }

    /** Human-readable identification for the log line printed when playback starts. */
    public String describe() {
        return "native " + sampleRate + " Hz / " + channels + " ch";
    }
}
