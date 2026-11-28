# libliquidbounce_audiocodec

Native audio decoder for the Music module: **Ogg/Vorbis, Ogg/Opus, M4A and fragmented MP4 (.m4s) AAC**.

## Why a `.so` and not MediaCodec / a Java decoder

* `MediaCodec`'s Vorbis support depends on the OEM image. On the Pojav target it is missing or
  broken on several devices, so `OggVorbisAudioParser` style Java fallbacks either did not exist or
  needed a 4000-line JOrbis rewrite.
* A bare DASH `.m4s` segment has no `moov` box, so `MediaExtractor` cannot open it at all. FFmpeg's
  `mov` demuxer reads `styp`/`moof` fragments directly.
* One library covers all four containers, so the player no longer needs per-format special cases.

## Files

| Path | Purpose |
| --- | --- |
| `src/audio_codec_jni.c` | The whole library: FFmpeg setup, seek, resample to interleaved s16, JNI entry points. |
| `build-android.sh` | Downloads FFmpeg `7.1.1`, cross-compiles a minimal static build for each ABI, links and strips the `.so`, copies it into `src/main/resources/natives/android-<abi>/`. |
| `../../src/main/java/.../audio/parser/NativeAudioCodec.java` | Extracts the `.so` from the jar into a SHA-256-addressed folder and `System.load`s it. |
| `../../src/main/java/.../audio/parser/NativeAudioParser.java` | `PcmAudioParser` over the JNI handle, so `AudioEngine` keeps its 1152-frame chunking. |

## Rebuild

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r27c
./droidbridge-native/audiocodec/build-android.sh          # arm64 + x64
```

Nothing in Gradle compiles C. The prebuilt `.so` is committed under
`src/main/resources/natives/android-{arm64,x64}/` and shipped inside the mod jar, exactly like
`libliquidbounce_droidbridge.so`.

## JNI surface

```java
static native String nativeVersion();
static native long   nativeOpen(String path, long startAtMs, int targetRate, int targetChannels);
static native int    nativeReadFrame(long handle, short[] out, int capacity); // 0 = EOF, <0 = error
static native int    nativeSampleRate(long handle);
static native int    nativeChannels(long handle);
static native long   nativeTotalSamples(long handle);
static native long   nativePositionSamples(long handle);
static native void   nativeSetStop(long handle, boolean stop);
static native String nativeLastError(long handle);
static native void   nativeClose(long handle);
```

`targetRate`/`targetChannels` are `0` to keep the file's own parameters; `startAtMs` is honoured by
seeking the container and then dropping the remainder of the first decoded frame, so a seek lands on
the exact sample instead of the previous audio packet.

## Verified

Built with NDK r27c, API 24, 16 KB page alignment (Android 15 requirement). Both `.so` files were
produced from this tree. The C source was additionally compiled for the host (x86_64 glibc) against
the same FFmpeg configuration and driven through the real JNI entry points:

| Input | Result |
| --- | --- |
| Vorbis `.ogg`, 44.1 kHz stereo | 269 188 frames = 6.104 s, full file, no trailer garbage |
| AAC `.m4a`, 44.1 kHz stereo, 105.8 s | 4 665 344 frames, peak 32768 |
| `.m4a` with `startAtMs=60000` | 45.79 s emitted, exactly 105.79 − 60 |
| Fragmented `.m4s` (init + 6 segments) | 649 728 frames = 13.536 s, seeking and 16 KB pages clean |
| Vorbis `.ogg` with `startAtMs=3000` | 3.104 s emitted, position 269 188 |

FFmpeg is LGPL-2.1-or-later; the build disables `--enable-gpl` and all encoders, so the resulting
`.so` stays LGPL and is dynamically linked into the mod.
