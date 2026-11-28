package net.ccbluex.liquidbounce.utils.audio.parser;
public final class NativeAudioParser implements PcmAudioParser {
 public NativeAudioParser(java.io.File f,long ms,java.util.function.BooleanSupplier s) throws java.io.IOException {}
 public int getSampleRate(){return 44100;}
 public int getChannels(){return 2;}
 public long getTotalSamples(){return 0;}
 public long getPositionSamples(){return 0;}
 public String getCodec(){return "fixture";}
 public short[] readFrame(){return null;}
 public void close(){}
}
