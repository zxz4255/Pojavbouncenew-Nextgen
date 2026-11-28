package net.ccbluex.liquidbounce.common;

import java.util.Collections;
import java.util.List;
import java.util.function.IntToDoubleFunction;

/** Render-thread adapter populated by the player before each state update. */
public final class LyricsInput {
    public static List<LyricLine> lyrics = Collections.emptyList();
    public static String lyricsTrackKey;
    public static long lyricsRevision;
    private static IntToDoubleFunction advance;
    private LyricsInput() {}
    public static boolean lyricsMatchCurrentTrack() { return lyricsTrackKey != null; }
    public static void setAdvance(IntToDoubleFunction provider) { advance = provider; }
    public static float designAdvance(char c) {
        if (advance == null) throw new IllegalStateException("World lyrics font provider is not configured");
        return (float) advance.applyAsDouble(c);
    }
}
