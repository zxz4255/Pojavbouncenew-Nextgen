package net.ccbluex.liquidbounce.utils.audio.parser;

import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.Bitstream;
import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.BitstreamException;
import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.Decoder;
import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.DecoderException;
import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.Header;
import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.Obuffer;
import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** MP3-only decoder session. A parser instance is private to one track and one audio worker. */
public final class Mp3AudioParser implements PcmAudioParser {
    private static final int INPUT_BUFFER_BYTES = 256 * 1024;

    private final File file;
    private final BooleanSupplier cancelled;
    private final InputStream input;
    private final Bitstream bitstream;
    private final Decoder decoder = new Decoder();
    private final int sampleRate;
    private final int channels;
    private final int version;
    private final int layer;
    private final long totalSamples;

    private Header firstHeader;
    private short[] pending;
    private long positionSamples;
    private boolean closed;

    public Mp3AudioParser(File file, long startAtMs, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!file.isFile()) throw new IOException("MP3 文件不存在");
        if (file.length() > MusicAudioParserFactory.MAX_FILE_BYTES) {
            throw new IOException("音频文件超过 100 MiB 安全上限");
        }
        this.file = file;
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.input = new BufferedInputStream(
                new CancellableInputStream(new FileInputStream(file), this.cancelled), INPUT_BUFFER_BYTES);
        this.bitstream = new Bitstream(input);

        try {
            checkCancelled();
            firstHeader = readHeader();
            if (firstHeader == null) throw new IOException("MP3 中没有可解码的 MPEG 音频帧");
            this.sampleRate = firstHeader.frequency();
            this.channels = firstHeader.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
            this.version = firstHeader.version();
            this.layer = firstHeader.layer();
            if (sampleRate < 8_000 || sampleRate > 192_000 || channels < 1 || channels > 2) {
                throw new IOException("不支持的 MP3 音频参数：" + sampleRate + " Hz / " + channels + " 声道");
            }
            this.totalSamples = estimateSamples(firstHeader, file.length(), sampleRate);
            seekToMs(Math.max(0L, startAtMs));
        } catch (IOException | RuntimeException e) {
            try { bitstream.close(); } catch (Exception ignored) { }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("MP3 解码器初始化失败", e);
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
    public long getPositionSamples() {
        return positionSamples;
    }

    @Override
    public String getCodec() {
        return "MP3";
    }

    @Override
    public short[] readFrame() throws IOException {
        ensureOpen();
        if (pending != null) {
            short[] result = pending;
            pending = null;
            positionSamples += result.length / channels;
            return result;
        }
        short[] result = readDecodedFrame();
        if (result != null) positionSamples += result.length / channels;
        return result;
    }

    private void seekToMs(long startAtMs) throws IOException {
        if (startAtMs <= 0L) return;
        long target;
        try {
            target = Math.addExact(Math.multiplyExact(startAtMs / 1_000L, sampleRate),
                    (startAtMs % 1_000L) * sampleRate / 1_000L);
        } catch (ArithmeticException e) {
            throw new IOException("MP3 seek position is out of range", e);
        }
        if (totalSamples > 0L) target = Math.min(target, totalSamples);

        long decoded = 0L;
        while (true) {
            short[] frame = readDecodedFrame();
            if (frame == null) {
                positionSamples = decoded;
                if (decoded < target) throw new IOException("seek beyond available MP3 audio");
                return;
            }
            int frames = frame.length / channels;
            long end = decoded + frames;
            if (target < end) {
                int skipFrames = (int) Math.max(0L, target - decoded);
                pending = Arrays.copyOfRange(frame, skipFrames * channels, frame.length);
                positionSamples = target;
                return;
            }
            decoded = end;
            positionSamples = decoded;
            if (decoded == target) return;
            checkCancelled();
        }
    }

    private short[] readDecodedFrame() throws IOException {
        while (true) {
            checkCancelled();
            Header header = firstHeader;
            firstHeader = null;
            if (header == null) header = readHeader();
            if (header == null) return null;

            if (header.frequency() != sampleRate || header.version() != version || header.layer() != layer ||
                    (header.mode() == Header.SINGLE_CHANNEL ? 1 : 2) != channels) {
                closeFrameQuietly();
                throw new IOException("MP3 流中途改变采样率、声道或 MPEG 层，已停止以避免错速/错声道输出");
            }

            try {
                Obuffer decoded = decoder.decodeFrame(header, bitstream);
                if (!(decoded instanceof SampleBuffer)) throw new IOException("MP3 解码器未返回 PCM 样本");
                SampleBuffer samples = (SampleBuffer) decoded;
                int count = samples.getBufferLength();
                if (count < 0 || count > samples.getBuffer().length || count % channels != 0) {
                    throw new IOException("MP3 PCM 帧长度异常");
                }
                if (decoder.getOutputFrequency() != sampleRate || decoder.getOutputChannels() != channels) {
                    throw new IOException("MP3 解码器输出格式与帧头不一致");
                }
                if (count == 0) continue;
                // JLayer reuses its SampleBuffer; copy before the next frame is decoded.
                return Arrays.copyOf(samples.getBuffer(), count);
            } catch (DecoderException e) {
                throw new IOException("MP3 帧解码失败", e);
            } finally {
                bitstream.closeFrame();
            }
        }
    }

    private Header readHeader() throws IOException {
        checkCancelled();
        try {
            return bitstream.readFrame();
        } catch (BitstreamException e) {
            throw new IOException("MP3 帧头解析失败", e);
        }
    }

    private static long estimateSamples(Header header, long fileBytes, int sampleRate) {
        if (fileBytes <= 0L || sampleRate <= 0) return 0L;
        try {
            int safeLength = (int) Math.min(Integer.MAX_VALUE, fileBytes);
            double millis = header.totalMs(safeLength);
            if (!Double.isFinite(millis) || millis <= 0d) return 0L;
            double samples = millis * sampleRate / 1_000d;
            return samples >= Long.MAX_VALUE ? 0L : Math.max(0L, Math.round(samples));
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("MP3 解析已取消");
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("MP3 parser is closed");
        checkCancelled();
    }

    private void closeFrameQuietly() {
        try { bitstream.closeFrame(); } catch (RuntimeException ignored) { }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            bitstream.close();
        } catch (BitstreamException e) {
            throw new IOException("关闭 MP3 输入流失败", e);
        }
    }

    private static final class CancellableInputStream extends FilterInputStream {
        private final BooleanSupplier cancelled;

        private CancellableInputStream(InputStream input, BooleanSupplier cancelled) {
            super(input);
            this.cancelled = cancelled;
        }

        private void check() throws InterruptedIOException {
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("MP3 解析已取消");
        }

        @Override
        public int read() throws IOException {
            check();
            return super.read();
        }

        @Override
        public int read(byte[] data, int offset, int length) throws IOException {
            check();
            return super.read(data, offset, length);
        }
    }
}
