package net.ccbluex.liquidbounce.utils.audio.parser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Loads {@code libliquidbounce_audiocodec.so}, the bundled Ogg/Vorbis + Opus + MP4/AAC decoder.
 *
 * <p>The library is shipped inside the mod jar under {@code /natives/android-<arch>/} and is
 * unpacked into a content-addressed folder before {@link System#load(String)}, exactly the way
 * {@code liquidbounce.DroidBridge.Native} loads the WebView bridge. Nothing here depends on
 * Gradle/NDK at runtime: if no library matches the CPU the loader reports why, and the music player
 * surfaces that message instead of silently changing decoder.</p>
 *
 * <p>This is the only decoder the module has: platform {@code MediaCodec} and the former Java
 * decoders (JLayer, jFLAC, JAAD) were removed, so every format behaves the same on every device and
 * a bare ISO-BMFF .m4s segment can be opened.</p>
 */
public final class NativeAudioCodec {
    private static final String LIBRARY_NAME = "liquidbounce_audiocodec";

    private static volatile boolean loaded;
    private static volatile Throwable failure;
    private static volatile String version = "unavailable";
    private static volatile File directory;
    private static volatile String libraryPath;

    private NativeAudioCodec() {
    }

    /** Maps {@code os.arch} to the resource folder, or {@code null} when unsupported. */
    private static String platform() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) return "android-arm64";
        if (arch.equals("amd64") || arch.equals("x86_64")) return "android-x64";
        return null;
    }

    public static synchronized boolean load(File folder) {
        if (loaded) return true;
        if (failure != null) return false;

        try {
            String platform = platform();
            if (platform == null) {
                failure = new IOException("没有适用于 " + System.getProperty("os.arch") + " 的原生解码库");
                return false;
            }

            String name = System.mapLibraryName(LIBRARY_NAME);
            String resource = "/natives/" + platform + "/" + name;
            try (InputStream input = NativeAudioCodec.class.getResourceAsStream(resource)) {
                if (input == null) {
                    failure = new IOException("jar 内缺少 " + resource);
                    return false;
                }
                byte[] bytes = readAll(input);
                String hash = sha256(bytes);
                File base = new File(nativeFolder(folder), "liquidbounce-audiocodec");
                File target = new File(base, hash.substring(0, 16));
                File library = new File(target, name);
                if (!library.isFile() || library.length() != bytes.length) {
                    if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
                        failure = new IOException("无法创建原生库目录 " + target);
                        return false;
                    }
                    File temporary = new File(target, name + ".tmp");
                    Files.write(temporary.toPath(), bytes);
                    try {
                        Files.move(temporary.toPath(), library.toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        temporary.delete();
                        throw e;
                    }
                }
                System.load(library.getAbsolutePath());
                directory = target;
                libraryPath = library.getAbsolutePath();
            }

            loaded = true;
            version = nativeVersion();
            System.out.println("[Music audio] native decoder loaded: " + version +
                    " (" + platform + " <- " + libraryPath + ")");
            return true;
        } catch (Throwable e) {
            failure = e;
            System.err.println("[Music audio] native decoder unavailable: " + e);
            return false;
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /** Why loading failed, or {@code null} when it worked (used in error messages). */
    static Throwable failure() {
        return failure;
    }

    /** One-line reason to show the player, never {@code null}. */
    public static String describeFailure() {
        Throwable cause = failure;
        if (cause == null) {
            return loaded ? "已加载" : "尚未加载";
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    public static String version() {
        return version;
    }

    public static File directory() {
        return directory;
    }

    private static File nativeFolder(File fallback) {
        String tmp = System.getenv("TMPDIR");
        if (tmp == null || tmp.trim().isEmpty()) tmp = System.getProperty("java.io.tmpdir");
        File base = tmp == null || tmp.trim().isEmpty() ? fallback : new File(tmp);
        if (base == null) base = new File(".");
        return base;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 20);
        byte[] chunk = new byte[8192];
        int count;
        while ((count = input.read(chunk)) > 0) {
            out.write(chunk, 0, count);
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    /**
     * Reports which containers the loaded library can actually demux.
     *
     * <p>Bumped once per session so a log says exactly what the shipped .so supports. The music
     * player feeds this list to the player UI and to the error message, instead of leaving the user
     * with a bare "解析失败" when one demuxer is missing from the build.</p>
     */
    public static String[] formats() {
        if (!loaded) return new String[0];
        try {
            String reported = nativeFormats();
            if (reported == null || reported.isBlank()) return new String[0];
            return reported.split(",");
        } catch (Throwable e) {
            return new String[0];
        }
    }

    /** One line describing everything the player needs to know about the decoder. */
    public static String describe() {
        if (!loaded) return "原生解码库未加载：" + describeFailure();
        String[] formats = formats();
        return version + (formats.length == 0 ? "" : " [" + String.join(", ", formats) + "]");
    }

    // ---------------------------------------------------------------- JNI ----

    static native String nativeVersion();

    /** Comma separated demuxer names the library was built with. */
    static native String nativeFormats();

    static native long nativeOpen(String path, long startAtMs, int targetRate, int targetChannels);

    static native int nativeReadFrame(long handle, short[] out, int capacity);

    static native int nativeSampleRate(long handle);

    static native int nativeChannels(long handle);

    static native long nativeTotalSamples(long handle);

    static native long nativePositionSamples(long handle);

    static native void nativeSetStop(long handle, boolean stop);

    static native String nativeLastError(long handle);

    static native void nativeClose(long handle);
}
