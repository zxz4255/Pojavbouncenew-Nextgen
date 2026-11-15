package net.ccbluex.liquidbounce.utils.audio.parser;

import java.io.Closeable;
import java.io.IOException;

/**
 * A single-track, single-owner parser that emits interleaved signed PCM16 samples.
 * Implementations own all codec state and input buffers; no Minecraft mixer or other
 * sound source is ever used as parser input.
 */
public interface PcmAudioParser extends Closeable {
    int getSampleRate();

    int getChannels();

    /** Total decoded samples per channel, or 0 when the codec does not advertise a count. */
    long getTotalSamples();

    /** Decoded/consumed samples per channel. */
    long getPositionSamples();

    String getCodec();

    /**
     * Returns one interleaved PCM16 frame, or {@code null} at end of stream.
     * The returned array belongs to the caller and will not be mutated by this parser.
     */
    short[] readFrame() throws IOException;

    @Override
    void close() throws IOException;
}
