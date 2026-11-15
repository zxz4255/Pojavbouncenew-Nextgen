package net.ccbluex.liquidbounce.utils.audio.parser;

import net.ccbluex.liquidbounce.utils.audio.codec.jflac.FLACDecoder;
import net.ccbluex.liquidbounce.utils.audio.codec.jflac.frame.Frame;
import net.ccbluex.liquidbounce.utils.audio.codec.jflac.metadata.StreamInfo;
import net.ccbluex.liquidbounce.utils.audio.codec.jflac.util.ByteData;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** FLAC-only decoder session. It never shares bit-reader or PCM state with the MP3 parser. */
public final class FlacAudioParser implements PcmAudioParser {
    private static final int MAX_FRAME_SAMPLES = 65_536;
    private static final Set<Integer> SUPPORTED_DEPTHS = Set.of(8, 16, 24);

    private final InputStream input;
    private final BooleanSupplier cancelled;
    private final FLACDecoder decoder;
    private final StreamInfo streamInfo;
    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    private final long totalSamples;
    private final ByteData reusablePcm = new ByteData(0);

    private long positionSamples;
    private short[] pending;
    private boolean closed;

    public FlacAudioParser(File file, long startAtMs, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!file.isFile()) throw new IOException("FLAC 文件不存在");
        if (file.length() > MusicAudioParserFactory.MAX_FILE_BYTES) {
            throw new IOException("音频文件超过 100 MiB 安全上限");
        }
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.input = new CancellableInputStream(new FileInputStream(file), this.cancelled);
        try {
            checkCancelled();
            this.decoder = new FLACDecoder(input);
            decoder.readMetadata();
            this.streamInfo = decoder.getStreamInfo();
            if (streamInfo == null) throw new IOException("FLAC 缺少 STREAMINFO 元数据");

            this.sampleRate = streamInfo.getSampleRate();
            this.channels = streamInfo.getChannels();
            this.bitsPerSample = streamInfo.getBitsPerSample();
            this.totalSamples = Math.max(0L, streamInfo.getTotalSamples());
            if (sampleRate < 8_000 || sampleRate > 192_000 || channels < 1 || channels > 2) {
                throw new IOException("不支持的 FLAC 音频参数：" + sampleRate + " Hz / " + channels + " 声道");
            }
            if (!SUPPORTED_DEPTHS.contains(bitsPerSample)) {
                throw new IOException("此 FLAC 位深暂不支持：" + bitsPerSample + " bit");
            }
            seekToMs(Math.max(0L, startAtMs));
        } catch (IOException | RuntimeException e) {
            try { input.close(); } catch (IOException ignored) { }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("FLAC 解码器初始化失败", e);
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
        return "FLAC " + bitsPerSample + " bit";
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
            throw new IOException("FLAC seek position is out of range", e);
        }
        if (totalSamples > 0L) target = Math.min(target, totalSamples);

        long decoded = 0L;
        while (true) {
            short[] frame = readDecodedFrame();
            if (frame == null) {
                positionSamples = decoded;
                if (decoded < target) throw new IOException("seek beyond available FLAC audio");
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
            final Frame frame;
            try {
                frame = decoder.readNextFrame();
            } catch (EOFException e) {
                return null;
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                throw new IOException("读取 FLAC 帧失败", e);
            } catch (RuntimeException e) {
                throw new IOException("FLAC 帧解析失败", e);
            }
            if (frame == null) return null;

            if (frame.header == null) throw new IOException("FLAC 帧头缺失");
            int samples = frame.header.blockSize;
            int frameRate = frame.header.sampleRate > 0 ? frame.header.sampleRate : sampleRate;
            int frameChannels = frame.header.channels > 0 ? frame.header.channels : channels;
            int frameDepth = frame.header.bitsPerSample > 0 ? frame.header.bitsPerSample : bitsPerSample;
            if (samples <= 0 || samples > MAX_FRAME_SAMPLES || frameRate != sampleRate ||
                    frameChannels != channels || frameDepth != bitsPerSample) {
                throw new IOException("FLAC 帧格式中途改变或超过安全范围");
            }

            final ByteData pcm;
            try {
                pcm = decoder.decodeFrame(frame, reusablePcm);
            } catch (RuntimeException e) {
                throw new IOException("FLAC PCM 转换失败", e);
            }
            int bytesPerSample = (bitsPerSample + 7) / 8;
            long expectedBytesLong = (long) samples * channels * bytesPerSample;
            if (expectedBytesLong > Integer.MAX_VALUE || pcm.getLen() < expectedBytesLong) {
                throw new IOException("FLAC 解码帧 PCM 长度异常");
            }

            byte[] source = pcm.getData();
            short[] result = new short[samples * channels];
            for (int i = 0; i < result.length; i++) {
                int offset = i * bytesPerSample;
                int sample;
                if (bitsPerSample == 8) {
                    sample = ((source[offset] & 0xff) - 128) << 8;
                } else if (bitsPerSample == 16) {
                    sample = (short) ((source[offset] & 0xff) | (source[offset + 1] << 8));
                } else {
                    int signed24 = (source[offset] & 0xff)
                            | ((source[offset + 1] & 0xff) << 8)
                            | (source[offset + 2] << 16);
                    sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (signed24 + 128) >> 8));
                }
                result[i] = (short) sample;
            }
            return result;
        }
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("FLAC 解析已取消");
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("FLAC parser is closed");
        checkCancelled();
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        input.close();
    }

    private static final class CancellableInputStream extends FilterInputStream {
        private final BooleanSupplier cancelled;

        private CancellableInputStream(InputStream input, BooleanSupplier cancelled) {
            super(input);
            this.cancelled = cancelled;
        }

        private void check() throws InterruptedIOException {
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("FLAC 解析已取消");
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
