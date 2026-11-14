package net.ccbluex.liquidbounce.utils.audio.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MusicAudioParserFactoryTest {
    @TempDir
    Path tempDir;

    @Test
    void detectsFlacAndOggFromContainerSignatures() throws IOException {
        Path flac = tempDir.resolve("misnamed.bin");
        Files.write(flac, new byte[]{'f', 'L', 'a', 'C', 0, 0, 0, 0});
        assertEquals(MusicAudioParserFactory.Codec.FLAC, MusicAudioParserFactory.detect(flac.toFile()));

        Path ogg = tempDir.resolve("also-misnamed.bin");
        Files.write(ogg, new byte[]{'O', 'g', 'g', 'S', 0, 0, 0, 0});
        assertEquals(MusicAudioParserFactory.Codec.OGG_VORBIS, MusicAudioParserFactory.detect(ogg.toFile()));
    }

    @Test
    void doesNotMistakeAnArbitraryMpegSyncByteForAnAudioStream() throws IOException {
        Path response = tempDir.resolve("error.html");
        byte[] body = new byte[64];
        body[0] = (byte) 0xff;
        body[1] = (byte) 0xfb;
        body[2] = (byte) 0x90;
        body[3] = 0x64;
        Files.write(response, body);

        assertEquals(MusicAudioParserFactory.Codec.UNKNOWN, MusicAudioParserFactory.detect(response.toFile()));
    }

    @Test
    void recognizesId3TaggedMp3BeforeItsFirstFrame() throws IOException {
        Path mp3 = tempDir.resolve("track.dat");
        // MPEG-1 Layer III, 128 kbps / 44.1 kHz => 417-byte frame.
        byte[] bytes = new byte[10 + 417 + 4];
        bytes[0] = 'I';
        bytes[1] = 'D';
        bytes[2] = '3';
        bytes[3] = 4;
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xfb;
        bytes[12] = (byte) 0x90;
        bytes[13] = 0x64;
        bytes[10 + 417] = (byte) 0xff;
        bytes[11 + 417] = (byte) 0xfb;
        bytes[12 + 417] = (byte) 0x90;
        bytes[13 + 417] = 0x64;
        Files.write(mp3, bytes);

        assertEquals(MusicAudioParserFactory.Codec.MP3, MusicAudioParserFactory.detect(mp3.toFile()));
    }
}
