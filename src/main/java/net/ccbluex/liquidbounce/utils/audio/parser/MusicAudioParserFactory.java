package net.ccbluex.liquidbounce.utils.audio.parser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Signature-based dispatch for music codecs; file extensions and game audio are not trusted. */
public final class MusicAudioParserFactory {
    public static final long MAX_FILE_BYTES = 100L * 1024L * 1024L;
    private static final int PROBE_BYTES = 64 * 1024;
    private static final Object NATIVE_INIT = new Object();

    public enum Codec {
        MP3,
        FLAC,
        OGG_VORBIS,
        MP4_AAC,
        UNKNOWN
    }

    private MusicAudioParserFactory() {
    }

    /**
     * Determines the encoded format from the file header. In particular, an MP3 sync word is
     * validated as an MPEG header (and, when present, followed by a compatible next frame),
     * rather than accepting any coincidental 0xFF byte sequence in a downloaded response.
     */
    public static Codec detect(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() < 4L) {
            return Codec.UNKNOWN;
        }
        if (file.length() > MAX_FILE_BYTES) {
            throw new IOException("音频文件超过 100 MiB 安全上限");
        }

        int length = (int) Math.min(file.length(), PROBE_BYTES);
        byte[] prefix = new byte[length];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < length) {
                int count = input.read(prefix, offset, length - offset);
                if (count < 0) break;
                if (count == 0) continue;
                offset += count;
            }
            if (offset < 4) return Codec.UNKNOWN;
        }

        if (matches(prefix, 0, "fLaC")) return Codec.FLAC;
        if (matches(prefix, 0, "OggS")) return Codec.OGG_VORBIS;
        // ISO-BMFF audio (regular M4A/MP4 or fragmented DASH .m4s).
        if (matches(prefix, 4, "ftyp")) return Codec.MP4_AAC;
        // A downloaded DASH segment starts at a media segment box instead of ftyp; Bilibili ships
        // "styp"/"msdh", other packagers use sidx/moof/emsg first. Only the bundled .so can open
        // these on its own, so route them to MP4_AAC and let the native decoder report the truth.
        if (matches(prefix, 4, "styp") || matches(prefix, 4, "sidx")
                || matches(prefix, 4, "moof") || matches(prefix, 4, "emsg")) {
            return Codec.MP4_AAC;
        }
        if (matches(prefix, 0, "ID3")) {
            long audioOffset = id3AudioOffset(prefix, file.length());
            if (audioOffset < 0L) return Codec.UNKNOWN;
            long remaining = file.length() - audioOffset;
            byte[] audioPrefix = readAt(file, audioOffset, (int) Math.min(remaining, PROBE_BYTES));
            return hasMpegFrames(audioPrefix, remaining) ? Codec.MP3 : Codec.UNKNOWN;
        }
        return hasMpegFrames(prefix, file.length()) ? Codec.MP3 : Codec.UNKNOWN;
    }

    /**
     * KGG/KGM/KGMA share the KGM container magic; VPR has its own signature.
     * These are encrypted containers, NOT another FFmpeg codec. Never strip the header or
     * rename encrypted bytes as MP3/FLAC. Online playback must obtain an authorized clear stream.
     */
    public static boolean isKugouEncrypted(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() < 16L) return false;
        byte[] prefix = readAt(file, 0L, 16);
        byte[] kgm = java.util.HexFormat.of().parseHex("7cd532eb86027f4ba8afa68e0fff9914");
        byte[] vpr = java.util.HexFormat.of().parseHex("0528bc96e9e45a4391aabdd07af53631");
        return java.util.Arrays.equals(prefix, kgm) || java.util.Arrays.equals(prefix, vpr);
    }

    /**
     * Extracts and loads the bundled decoder once, on the thread that first needs it.
     *
     * <p>The output library is loaded here as well, not just on the first playback attempt: doing
     * both up front means the log reports the decoder *and* the audio backend before any track is
     * opened, so "decoder fine, output broken" is one glance instead of a retry loop.</p>
     */
    private static boolean nativeReady() {
        if (!NativeAudioCodec.isLoaded()) {
            synchronized (NATIVE_INIT) {
                if (!NativeAudioCodec.load(null)) return false;
            }
        }
        // Never fatal on its own: open() reports it with the device's own reason.
        net.ccbluex.liquidbounce.utils.audio.android.NativeAudioOutputLoader.load(null);
        return true;
    }

    /** Human-readable state of the native decoder, used by the player's diagnostics. */
    public static String describeNativeDecoder() {
        return NativeAudioCodec.isLoaded() ? NativeAudioCodec.version() : "不可用";
    }

    /**
     * Opens the one decoder this client uses: the bundled native library.
     *
     * <p>The Java decoders that used to sit behind this method (JLayer for MP3, jFLAC, JAAD for
     * M4A/DASH .m4s) and Android's {@code MediaCodec} path were removed on purpose. The .so covers
     * every format the module accepts, so a missing library is now a hard, clearly reported error
     * instead of a silent switch to a decoder whose behaviour differed per format.</p>
     */
    public static PcmAudioParser open(File file, long startAtMs, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(file, "file");
        BooleanSupplier stop = cancelled == null ? () -> false : cancelled;
        Codec codec = detect(file);
        if (codec == Codec.UNKNOWN) {
            if (isKugouEncrypted(file)) throw new IOException("检测到酷狗 KGG/KGM/VPR 加密容器；需要账号授权的标准音频或该文件对应的合法密钥，普通音频解码器不能直接打开");
            throw new IOException("无法识别音频文件头（仅支持 MP3、FLAC、MP4/AAC 与 Ogg/Vorbis）");
        }
        if (!nativeReady()) {
            throw new IOException("原生音频解码库不可用（" + NativeAudioCodec.describeFailure() + "）");
        }
        try {
            return new NativeAudioParser(file, Math.max(0L, startAtMs), stop);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            // Name the container that failed. Without this the player only said "解析失败" and every
            // format looked broken even when a single demuxer was missing from the shipped library.
            throw new IOException("原生解码器无法解析 " + codec + "（" + rootCause(e) + "）", e);
        } catch (RuntimeException | LinkageError e) {
            throw new IOException("原生解码器初始化失败：" + rootCause(e), e);
        }
    }

    /**
     * Flattens a reflective failure into the one line that actually explains it.
     *
     * <p>On Pojav/Zalith the codec classes load but a member is missing, so the interesting message is
     * three levels down (usually {@code NoSuchMethodException} naming the exact member). Printing only
     * the outer "API 不可用" line made that undiagnosable from a log.</p>
     */
    private static String rootCause(Throwable failure) {
        Throwable current = failure;
        String message = String.valueOf(failure.getMessage());
        for (int depth = 0; depth < 6 && current != null; depth++) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message;
    }

    private static boolean matches(byte[] data, int offset, String ascii) {
        if (offset < 0 || offset + ascii.length() > data.length) return false;
        for (int i = 0; i < ascii.length(); i++) {
            if ((data[offset + i] & 0xff) != ascii.charAt(i)) return false;
        }
        return true;
    }

    private static long id3AudioOffset(byte[] header, long fileLength) {
        if (header.length < 10) return -1L;
        long size = 0L;
        for (int i = 6; i <= 9; i++) {
            int value = header[i] & 0xff;
            if ((value & 0x80) != 0) return -1L;
            size = (size << 7) | value;
        }
        long start = 10L + size + (((header[5] & 0x10) != 0) ? 10L : 0L);
        return start <= fileLength ? start : -1L;
    }

    private static byte[] readAt(File file, long offset, int length) throws IOException {
        if (length <= 0) return new byte[0];
        byte[] result = new byte[length];
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(offset);
            int written = 0;
            while (written < length) {
                int count = input.read(result, written, length - written);
                if (count < 0) break;
                if (count == 0) continue;
                written += count;
            }
            return written == length ? result : java.util.Arrays.copyOf(result, written);
        }
    }

    private static boolean hasMpegFrames(byte[] data, long availableBytes) {
        int scanEnd = Math.min(data.length - 4, 16 * 1024);
        for (int offset = 0; offset <= scanEnd; offset++) {
            MpegHeader first = MpegHeader.parse(data, offset);
            if (first == null) continue;
            long nextOffset = (long) offset + first.frameLength;
            if (nextOffset + 4L <= availableBytes && nextOffset + 4L <= data.length) {
                MpegHeader second = MpegHeader.parse(data, (int) nextOffset);
                if (second != null && first.compatibleWith(second)) return true;
            }
            // A complete one-frame MP3 is valid too; otherwise require two matching headers.
            if (nextOffset == availableBytes) return true;
        }
        return false;
    }

    private static final class MpegHeader {
        private static final int[] MPEG1_LAYER1 = {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448};
        private static final int[] MPEG1_LAYER2 = {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384};
        private static final int[] MPEG1_LAYER3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
        private static final int[] MPEG2_LAYER1 = {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256};
        private static final int[] MPEG2_LAYER2_3 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};

        private final int version;
        private final int layer;
        private final int sampleRate;
        private final int frameLength;

        private MpegHeader(int version, int layer, int sampleRate, int frameLength) {
            this.version = version;
            this.layer = layer;
            this.sampleRate = sampleRate;
            this.frameLength = frameLength;
        }

        private static MpegHeader parse(byte[] data, int offset) {
            if (offset < 0 || offset + 4 > data.length) return null;
            int b0 = data[offset] & 0xff;
            int b1 = data[offset + 1] & 0xff;
            int b2 = data[offset + 2] & 0xff;
            int b3 = data[offset + 3] & 0xff;
            if (b0 != 0xff || (b1 & 0xe0) != 0xe0) return null;

            int version = (b1 >>> 3) & 0x03;
            int layer = (b1 >>> 1) & 0x03;
            int bitrateIndex = (b2 >>> 4) & 0x0f;
            int sampleRateIndex = (b2 >>> 2) & 0x03;
            if (version == 1 || layer == 0 || bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null;

            int[] baseRates = {44100, 48000, 32000};
            int sampleRate = baseRates[sampleRateIndex];
            if (version == 2) sampleRate /= 2;
            else if (version == 0) sampleRate /= 4;
            boolean mpeg1 = version == 3;
            int[] bitrates = mpeg1
                    ? (layer == 3 ? MPEG1_LAYER1 : layer == 2 ? MPEG1_LAYER2 : MPEG1_LAYER3)
                    : (layer == 3 ? MPEG2_LAYER1 : MPEG2_LAYER2_3);
            int bitrate = bitrates[bitrateIndex] * 1000;
            int padding = (b2 >>> 1) & 1;
            int frameLength = layer == 3
                    ? ((12 * bitrate / sampleRate) + padding) * 4
                    : (((layer == 1 && !mpeg1) ? 72 : 144) * bitrate / sampleRate) + padding;
            if (frameLength < 4) return null;
            return new MpegHeader(version, layer, sampleRate, frameLength);
        }

        private boolean compatibleWith(MpegHeader other) {
            return version == other.version && layer == other.layer && sampleRate == other.sampleRate;
        }
    }
}
