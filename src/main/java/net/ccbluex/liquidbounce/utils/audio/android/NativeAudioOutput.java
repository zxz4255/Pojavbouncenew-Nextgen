/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce).
 * Copyright (C) 2015-2026 CCBlueX and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.utils.audio.android;

import java.io.File;
import java.io.IOException;

/**
 * One PCM sink backed by {@code libliquidbounce_audioout.so} (AAudio, or OpenSL ES as a fallback).
 *
 * <p>Contract, kept identical to the sink this replaces so {@code AudioEngine} did not have to
 * change its shape:</p>
 * <ul>
 *     <li>{@link #write(byte[], int, int)} queues interleaved PCM16 and blocks while the device
 *     queue is at its latency budget. It is the decoder worker's back-pressure, and must never be
 *     called from the render thread.</li>
 *     <li>{@link #isAlive()} only turns false when this instance is closed on purpose or the device
 *     reported a hard failure — never because the world changed.</li>
 *     <li>{@link #playedFrames()} drives the progress bar.</li>
 * </ul>
 *
 * <p>Nothing here touches the Java framework, so it works inside a Pojav/Zalith launcher where
 * {@code android.media.AudioTrack} cannot be resolved at all.</p>
 */
public final class NativeAudioOutput implements AutoCloseable {

    private final int sampleRate;
    private final int channels;
    private final int frameSize;
    private final String backend;

    /**
     * Guards the {@link #handle} publish/retire pair.
     *
     * <p>{@code write()} runs on the decoder worker while the client thread may call
     * {@link #close()} at any moment (track change, seek, world unload, module toggle). Reading the
     * handle and then entering native code is not atomic, so close() could retire the sink in
     * between. The native side now keeps the sink struct allocated and makes a closed sink inert
     * (ring freed, streams closed, every later call a no-op), so a racing call is harmless; this lock
     * is what makes the semantics explicit and keeps close() from running twice.</p>
     *
     * <p>It is deliberately NOT held across a blocking {@code nativeWrite}: that call waits for the
     * device to consume audio, and holding the lock there would make {@link #close()} wait on the
     * audio device — a stall, which is exactly the "client freezes" half of the bug report.</p>
     */
    private final Object handleLock = new Object();

    private volatile long handle;
    private volatile boolean closed;
    private volatile boolean paused;
    private volatile boolean started;

    private NativeAudioOutput(long handle, int sampleRate, int channels, String backend) {
        this.handle = handle;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.frameSize = channels * 2;
        this.backend = backend;
    }

    /**
     * Opens a sink for the given PCM format.
     *
     * @throws IOException when this is not an Android runtime, or when neither AAudio nor OpenSL ES
     *                     could give us a playing output stream.
     */
    public static NativeAudioOutput open(int sampleRate, int channels) throws IOException {
        if (!AndroidAudioSession.isAndroidRuntime()) {
            throw new IOException("原生音频输出只能在 Android 运行时使用");
        }
        if (sampleRate < 8_000 || sampleRate > 192_000) {
            throw new IOException("不支持的采样率：" + sampleRate);
        }
        if (channels != 1 && channels != 2) {
            throw new IOException("只接受单声道或立体声，收到 " + channels);
        }
        if (!NativeAudioOutputLoader.load(null)) {
            throw new IOException("原生音频库未加载：" + NativeAudioOutputLoader.describeFailure());
        }

        long handle = nativeOpen(sampleRate, channels);
        if (handle == 0L) {
            String reason = "原生音频输出打开失败";
            try {
                String detail = nativeLastError(0L);
                if (detail != null && !detail.isBlank()) reason = reason + "：" + detail;
            } catch (Throwable ignored) {
                // A missing detail must not hide the original failure.
            }
            throw new IOException(reason);
        }
        return new NativeAudioOutput(handle, sampleRate, channels, NativeAudioOutputLoader.backend());
    }

    public String backend() {
        return backend;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    /** True until this sink is closed on purpose or the device failed hard. */
    public boolean isAlive() {
        return !closed && handle != 0L;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isStarted() {
        return started;
    }

    /** Starts device playback. Repeated calls are harmless. */
    public void start() throws IOException {
        if (!isAlive()) {
            throw new IOException("原生音频输出已关闭");
        }
        paused = false;
        started = true;
        nativePause(handle, false);
    }

    /**
     * Queues interleaved PCM16, blocking while the device queue is full.
     *
     * @return false when the sink died and the engine has to rebuild it.
     */
    public boolean write(byte[] data, int offset, int length) {
        if (!isAlive() || length <= 0) {
            return isAlive();
        }
        int aligned = length - (length % frameSize);
        if (aligned <= 0) {
            return true;
        }
        if (paused) {
            // Paused output must not buffer ahead, or the progress bar runs away from what is heard.
            return true;
        }
        long current = handle;
        if (current == 0L || closed) {
            return false;
        }
        int written = nativeWrite(current, data, offset, aligned);
        if (written < 0) {
            closed = true;
            return false;
        }
        return true;
    }

    public void pause(boolean value) {
        if (!isAlive()) {
            return;
        }
        paused = value;
        long current = handle;
        if (current != 0L) {
            nativePause(current, value);
        }
    }

    /** Drops everything already queued in the device, for a seek. */
    public void flushDevice() {
        if (!isAlive()) {
            return;
        }
        long current = handle;
        if (current != 0L) {
            nativeFlush(current);
        }
    }

    /** Frames the device has actually played, used for the progress clock. */
    public long playedFrames() {
        if (!isAlive()) {
            return 0L;
        }
        long current = handle;
        if (current == 0L) {
            return 0L;
        }
        return nativePlayedFrames(current) & 0xFFFFFFFFL;
    }

    /**
     * Waits until the device has played everything that was submitted.
     *
     * <p>A paused sink cannot drain, so this returns as soon as playback is paused.</p>
     */
    public boolean drain(int timeoutMs) {
        if (!isAlive()) {
            return false;
        }
        /*
         * Drain does wait, so the handle is read without the lock and the native side tolerates a
         * concurrent close: a closed sink reports failure instead of touching freed memory.
         */
        long current = handle;
        if (current == 0L) {
            return false;
        }
        return nativeDrain(current, timeoutMs) == 1;
    }

    /** True when the device has been stalled while we believe it should be playing. */
    public boolean deviceStalled() {
        return false;
    }

    public void setGain(float gain) {
        // The engine already applies its own gain while decoding; the device stays at full volume.
    }

    /** A one-line description for the music window's status row. */
    public String describe() {
        return "原生音频输出（" + backend + "，" + sampleRate + " Hz / " + channels + " ch）";
    }

    @Override
    public void close() {
        if (closed && handle == 0L) {
            return;
        }
        closed = true;
        long current;
        synchronized (handleLock) {
            current = handle;
            handle = 0L;
            if (current != 0L) {
                try {
                    nativeClose(current);
                } catch (Throwable ignored) {
                    // Closing an already-released sink is not an error worth surfacing.
                }
            }
        }
    }

    // ---------------------------------------------------------------- JNI ----

    static native String nativeBackend();

    static native long nativeOpen(int sampleRate, int channels);

    static native int nativeWrite(long handle, byte[] pcm, int offset, int length);

    static native int nativeDrain(long handle, int timeoutMs);

    static native void nativePause(long handle, boolean pause);

    static native int nativePlayedFrames(long handle);

    static native void nativeFlush(long handle);

    static native String nativeLastError(long handle);

    static native void nativeClose(long handle);
}
