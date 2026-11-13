package net.ccbluex.liquidbounce.common;
import java.util.ArrayList;
import java.util.List;
/** Minimal input model adapted from the existing player's lyric timeline. */
public final class LyricLine {
    public long timestamp, duration;
    public String lyric, translationText, romanizationText;
    public boolean isBreakLine;
    public final List<Word> words = new ArrayList<>();
    public LyricLine(long timestamp, String text) { this.timestamp = timestamp; this.lyric = text; }
    public boolean hasTimedWords() { return !words.isEmpty(); }
    public void markDirty() { /* no layout cache on this input type */ }
    public static final class Word {
        public final String word;
        public final long timestamp, duration;
        public Word(String word, long timestamp, long duration) {
            this.word = word; this.timestamp = timestamp; this.duration = duration;
        }
    }
}
