/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce).
 * Copyright (C) 2015-2026 CCBlueX and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.utils.audio.android;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Loads {@code libliquidbounce_audioout.so}, the PCM sink that actually makes the Music module
 * audible on Android.
 *
 * <p>Why a native sink at all: {@link AndroidAudioTrackOutput} reached
 * {@code android.media.AudioTrack} through {@code Class.forName}. That class lives in the Android
 * <b>platform</b> boot image, and a launcher such as Pojav/Zalith runs Minecraft on its own OpenJDK
 * bootclasspath, where no {@code android.*} class exists. The lookup therefore always threw
 * {@code ClassNotFoundException}, which surfaced as {@code Android AudioTrack API 不可用} right after
 * the native decoder had opened the file successfully — the log literally shows
 * {@code native decoder loaded: FFmpeg 7.1.1} immediately followed by that error.
 *
 * <p>The NDK audio APIs have no such problem: they are plain shared libraries in the same process
 * (AAudio on API 26+, OpenSL ES as the fallback), so they are reachable from any {@code .so} the mod
 * loads. This class only owns the loading; {@link NativeAudioOutput} is the handle wrapper.
 */
public final class NativeAudioOutputLoader {

    private static final String LIBRARY_NAME = "liquidbounce_audioout";

    private static volatile boolean loaded;
    private static volatile Throwable failure;
    private static volatile String backend = "unavailable";
    private static volatile File directory;
    private static volatile String libraryPath;

    private NativeAudioOutputLoader() {
    }

    /** Maps {@code os.arch} to the resource folder, or {@code null} when unsupported. */
    private static String platform() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) return "android-arm64";
        if (arch.equals("amd64") || arch.equals("x86_64")) return "android-x64";
        return null;
    }

    /**
     * Unpacks {@code /natives/android-<arch>/libliquidbounce_audioout.so} into a content-addressed
     * cache directory and loads it. Safe to call from several threads; a failure is cached, so a
     * broken device does not retry the unpack on every track.
     */
    public static synchronized boolean load(File folder) {
        if (loaded) return true;
        if (failure != null) return false;

        try {
            String platform = platform();
            if (platform == null) {
                failure = new IOException("没有适用于 " + System.getProperty("os.arch") + " 的原生音频输出库");
                return false;
            }

            String name = System.mapLibraryName(LIBRARY_NAME);
            String resource = "/natives/" + platform + "/" + name;
            try (InputStream input = NativeAudioOutputLoader.class.getResourceAsStream(resource)) {
                if (input == null) {
                    failure = new IOException("jar 内缺少 " + resource);
                    return false;
                }
                byte[] bytes = readAll(input);
                String hash = sha256(bytes);
                File base = new File(nativeFolder(folder), "liquidbounce-audioout");
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
            try {
                backend = NativeAudioOutput.nativeBackend();
            } catch (Throwable ignored) {
                backend = "unknown";
            }
            System.out.println("[Music audio] native output loaded: " + backend +
                    " (" + platform + " <- " + libraryPath + ")");
            return true;
        } catch (Throwable e) {
            failure = e;
            System.err.println("[Music audio] native output unavailable: " + e);
            return false;
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /** Backend the library reports at runtime ({@code AAudio} / {@code OpenSL ES}). */
    public static String backend() {
        return backend;
    }

    public static String libraryPath() {
        return libraryPath;
    }

    public static File directory() {
        return directory;
    }

    /** One-line reason to show the player, never {@code null}. */
    public static String describeFailure() {
        Throwable cause = failure;
        if (cause == null) {
            return loaded ? "已加载（" + backend + "）" : "尚未加载";
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static File nativeFolder(File fallback) {
        String tmp = System.getenv("TMPDIR");
        if (tmp == null || tmp.trim().isEmpty()) tmp = System.getProperty("java.io.tmpdir");
        File base = tmp == null || tmp.trim().isEmpty() ? fallback : new File(tmp);
        if (base == null) base = new File(".");
        return base;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 16);
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
}
