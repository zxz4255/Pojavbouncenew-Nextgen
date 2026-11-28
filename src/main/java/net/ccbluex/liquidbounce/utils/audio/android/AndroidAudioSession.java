/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce).
 * Copyright (C) 2015-2026 CCBlueX and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.utils.audio.android;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime facts the music player needs on Android, and the one place that decides whether the
 * platform {@code AudioTrack} path may be used.
 *
 * <p>{@link #generation()} is a monotonically increasing counter owned by the sink. It is bumped
 * whenever the module has to drop its device on purpose (client shutdown, output switch). Unlike the
 * old OpenAL/Minecraft-channel approach, a world change does <b>not</b> bump it: the AudioTrack device
 * outlives the level, so the track keeps playing straight through a dimension change or a long
 * teleport.</p>
 */
public final class AndroidAudioSession {

    private static final AtomicLong GENERATION = new AtomicLong();
    private static final AtomicReference<AndroidAudioTrackOutput> ACTIVE = new AtomicReference<>();
    private static final Boolean ANDROID = detectAndroid();

    private AndroidAudioSession() {
    }

    /**
     * Android launchers export {@code ANDROID_ROOT} and report an {@code android} OS version even for
     * a desktop OpenJDK running inside Pojav, so both signals are accepted.
     */
    public static boolean isAndroidRuntime() {
        return ANDROID;
    }

    private static boolean detectAndroid() {
        String root = System.getenv("ANDROID_ROOT");
        if (root != null && !root.isBlank()) {
            return true;
        }
        String version = System.getProperty("os.version", "");
        if (version.toLowerCase().contains("android")) {
            return true;
        }
        // Android's own vendor property; present even when the launcher scrubs the environment.
        String vendor = System.getProperty("java.vendor", "");
        String runtime = System.getProperty("java.runtime.name", "");
        return vendor.toLowerCase().contains("android") || runtime.toLowerCase().contains("android");
    }

    public static long generation() {
        return GENERATION.get();
    }

    public static long bumpGeneration() {
        return GENERATION.incrementAndGet();
    }

    /** Registers the sink currently producing sound, so shutdown can silence it synchronously. */
    public static void setActive(AndroidAudioTrackOutput output) {
        ACTIVE.set(output);
    }

    public static void clearActive(AndroidAudioTrackOutput output) {
        ACTIVE.compareAndSet(output, null);
    }

    /** Immediately silences whatever sink is registered. Safe from any thread. */
    public static void silenceActive() {
        AndroidAudioTrackOutput output = ACTIVE.getAndSet(null);
        if (output != null) {
            try {
                output.close();
            } catch (RuntimeException ignored) {
                // Shutdown must never throw back into the game's teardown.
            }
        }
        bumpGeneration();
    }

    /** A one-line description for the music window's status row. */
    public static String describe() {
        if (!isAndroidRuntime()) {
            return "Android AudioTrack（当前非 Android 运行时，不可用）";
        }
        return "Android AudioTrack（平台原生输出，绑定到媒体音量）";
    }
}
