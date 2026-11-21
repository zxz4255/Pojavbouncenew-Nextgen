/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce).
 * Copyright (C) 2015-2026 CCBlueX and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.utils.audio.android;

import java.io.IOException;

/**
 * PCM sink used by the music player, on top of {@link NativeAudioOutput}.
 *
 * <p><b>This class used to reflect on {@code android.media.AudioTrack}, and that could never work
 * here.</b> {@code android.media.AudioTrack} is a class in the Android <i>platform</i> boot image; a
 * launcher such as Pojav/Zalith runs the game on its own OpenJDK bootclasspath, which contains no
 * {@code android.*} class at all. {@code Class.forName("android.media.AudioTrack")} therefore threw
 * {@code ClassNotFoundException}, got wrapped into {@code IOException("Android AudioTrack API 不可用")},
 * and the engine retried it forever — the log shows exactly that:
 *
 * <pre>
 * native decoder loaded: FFmpeg 7.1.1 ...
 * stage=打开 Android AudioTrack
 * output interrupted, reopening Android sink: Android 音频输出不可用：Android AudioTrack API 不可用
 * </pre>
 *
 * <p>The decode half was fine all along; only the output half was unreachable. The sink now lives in
 * {@code libliquidbounce_audioout.so}, which talks to AAudio (API 26+) or OpenSL ES directly from
 * native code, where no Java framework class is involved.
 *
 * <p>The old class name is kept on purpose: {@code AudioEngine} and {@code AndroidAudioSession}
 * already reference it, and renaming it would only spread the change without adding anything.
 */
public final class AndroidAudioTrackOutput implements AutoCloseable {

    private final NativeAudioOutput sink;

    private AndroidAudioTrackOutput(NativeAudioOutput sink) {
        this.sink = sink;
    }

    /**
     * Opens a streaming sink for the given PCM format.
     *
     * @throws IOException when this is not an Android runtime or the device could not be opened. The
     *                     message carries the real reason (missing .so, no AAudio, no OpenSL ES).
     */
    public static AndroidAudioTrackOutput open(int sampleRate, int channels) throws IOException {
        if (!AndroidAudioSession.isAndroidRuntime()) {
            throw new IOException("原生音频输出只能在 Android 运行时使用");
        }
        return new AndroidAudioTrackOutput(NativeAudioOutput.open(sampleRate, channels));
    }

    public boolean isAndroidRuntime() {
        return AndroidAudioSession.isAndroidRuntime();
    }

    public int sampleRate() {
        return sink.sampleRate();
    }

    public int channels() {
        return sink.channels();
    }

    /** Which native backend is producing the sound, e.g. {@code AAudio} or {@code OpenSL ES}. */
    public String backend() {
        return sink.backend();
    }

    /**
     * True until this instance is closed or the device reported a hard failure. A closed sink is
     * discarded by the engine, never re-used, so a stale channel can never be written to.
     */
    public boolean isAlive() {
        return sink.isAlive();
    }

    public boolean isPaused() {
        return sink.isPaused();
    }

    public boolean isStarted() {
        return sink.isStarted();
    }

    /** Starts device playback on the first byte. Repeated calls are harmless. */
    public void start() throws IOException {
        sink.start();
    }

    /**
     * Queues interleaved PCM16. Blocks while the device queue is at its latency budget, which is the
     * back-pressure the decoder worker needs; it is never called from the client thread.
     *
     * @return false when the sink died and the engine has to rebuild it.
     */
    public boolean write(byte[] data, int offset, int length) {
        return sink.write(data, offset, length);
    }

    /** Stops writing ahead; the device keeps whatever is already queued until {@link #pause(boolean)}. */
    public void pause(boolean value) {
        sink.pause(value);
    }

    /** Drops everything already queued in the device, for a seek. */
    public void flushDevice() {
        sink.flushDevice();
    }

    /** Frames the device has actually played, used for the progress clock. */
    public long playedFrames() {
        return sink.playedFrames();
    }

    /** Blocking drain used by the engine before it retires a finished track. */
    public boolean drain(int timeoutMs) {
        return sink.drain(timeoutMs);
    }

    /** Kept for the engine's interface; the native sink reports failures through {@link #write}. */
    public boolean deviceStalled() {
        return false;
    }

    public void setGain(float gain) {
        sink.setGain(gain);
    }

    /** One line for the music window's status row. */
    public static String describe() {
        if (!AndroidAudioSession.isAndroidRuntime()) {
            return "原生音频输出（当前非 Android 运行时，不可用）";
        }
        if (!NativeAudioOutputLoader.isLoaded()) {
            return "原生音频输出（未加载：" + NativeAudioOutputLoader.describeFailure() + "）";
        }
        return "原生音频输出（" + NativeAudioOutputLoader.backend() + "，绑定到媒体音量）";
    }

    /** PLAYSTATE_* helper kept so older diagnostics code still compiles. */
    public static String describePlayState(int state) {
        return "state=" + state;
    }

    @Override
    public void close() {
        sink.close();
    }
}
