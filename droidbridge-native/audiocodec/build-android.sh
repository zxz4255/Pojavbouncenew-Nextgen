#!/usr/bin/env bash
#
# Rebuilds libliquidbounce_audiocodec.so for Android (arm64 + x64) and copies the result into
# src/main/resources/natives/. Nothing in Gradle compiles C: the .so is shipped as a resource and
# unpacked at runtime by NativeAudioCodec.java, exactly like libliquidbounce_droidbridge.so.
#
# Requirements:
#   * Android NDK r26+  ($ANDROID_NDK_HOME or $ANDROID_NDK_ROOT)
#   * curl, tar, make, a host C compiler with nasm for the x86_64 FFmpeg (or FFMPEG_HOST_SRC)
#
# Usage:
#   ./build-android.sh                 # build both ABIs
#   ./build-android.sh arm64           # only aarch64
#   FFMPEG_VERSION=7.1.1 ./build-android.sh
#
# The FFmpeg build is deliberately tiny: audio decoders only, file protocol, static, no programs,
# no network, no encoders. That keeps the shipped library around 1.7 MB while covering
# Ogg/Vorbis, Ogg/Opus, MP4/M4A/DASH (.m4s) AAC, MP3, FLAC and the other audio containers below.
#
# The demuxer list is not optional. Building only `--enable-demuxer=ogg,mov` ships a library that
# decodes mp3/flac bitstreams but cannot open an .mp3 or .flac *container* at all, which surfaces as
# "every song failed to parse" in the music player. Keep mp3/flac/aac in the list.
set -euo pipefail

FFMPEG_VERSION="${FFMPEG_VERSION:-7.1.1}"
API="${API:-24}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$ROOT/src/audio_codec_jni.c"
OUT_ROOT="$ROOT/../../src/main/resources/natives"
WORK="${WORK:-${TMPDIR:-/tmp}/liquidbounce-audiocodec-build}"
LIBS=(libavformat libavcodec libavutil libswresample)

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "ANDROID_NDK_HOME is not set (needed for the Android clang wrappers)" >&2
    exit 1
fi

if [ -z "${ANDROID_NDK_HOST_TAG:-}" ]; then
    case "$(uname -s)-$(uname -m)" in
        Linux-x86_64)  ANDROID_NDK_HOST_TAG=linux-x86_64 ;;
        Linux-aarch64) ANDROID_NDK_HOST_TAG=linux-x86_64 ;;  # NDK ships an x86_64 prebuilt only
        Darwin-arm64)  ANDROID_NDK_HOST_TAG=darwin-x86_64 ;;
        Darwin-x86_64) ANDROID_NDK_HOST_TAG=darwin-x86_64 ;;
        *) echo "Unsupported host: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
    esac
    if [ -d "$NDK/toolchains/llvm/prebuilt/darwin-x86_64" ] && [ "$(uname -s)" = "Darwin" ]; then
        ANDROID_NDK_HOST_TAG=darwin-x86_64
    fi
fi
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$ANDROID_NDK_HOST_TAG"

fetch_ffmpeg() {
    local tarball="$WORK/ffmpeg-$FFMPEG_VERSION.tar.xz"
    if [ ! -f "$tarball" ]; then
        mkdir -p "$WORK"
        curl -fsSL -o "$tarball" "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
    fi
}

# configure_ffmpeg <arch-name> <ffmpeg-arch> <clang-triple> <out-dir>
configure_ffmpeg() {
    local name="$1" ffarch="$2" triple="$3" out="$4"
    local src="$WORK/ffmpeg-$name"
    if [ -d "$src" ]; then
        return 0
    fi
    fetch_ffmpeg
    mkdir -p "$WORK"
    tar xf "$WORK/ffmpeg-$FFMPEG_VERSION.tar.xz" -C "$WORK"
    mv "$WORK/ffmpeg-$FFMPEG_VERSION" "$src"

    local cpu_flag=()
    [ "$ffarch" = "x86_64" ] && cpu_flag=(--cpu=x86-64 --disable-x86asm)

    ( cd "$src" && ./configure \
        --prefix="$out" \
        --target-os=android \
        --arch="$ffarch" \
        "${cpu_flag[@]}" \
        --enable-cross-compile \
        --cc="$TOOLCHAIN/bin/$triple${API}-clang" \
        --cxx="$TOOLCHAIN/bin/$triple${API}-clang++" \
        --ar="$TOOLCHAIN/bin/llvm-ar" \
        --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
        --strip="$TOOLCHAIN/bin/llvm-strip" \
        --nm="$TOOLCHAIN/bin/llvm-nm" \
        --sysroot="$TOOLCHAIN/sysroot" \
        --enable-static --disable-shared --disable-programs --disable-doc \
        --disable-avdevice --disable-swscale --disable-postproc --disable-avfilter \
        --disable-network --disable-everything --disable-autodetect --disable-debug \
        --enable-small --disable-symver \
        --enable-protocol=file \
        --enable-demuxer=ogg,mov,mp3,flac,aac,wav,aiff,caf,asf,matroska \
        --enable-decoder=vorbis,opus,aac,alac,mp3,mp3float,flac,pcm_s16le \
        --enable-parser=vorbis,opus,aac,flac,mpegaudio \
        --enable-avcodec --enable-avformat --enable-avutil \
        --extra-cflags="-fPIC -O2 -ffunction-sections -fdata-sections" \
        --extra-ldflags="-Wl,--gc-sections" ) > "$WORK/configure-$name.log" 2>&1

    make -C "$src" -j"$(nproc 2>/dev/null || echo 4)" > "$WORK/make-$name.log" 2>&1
}

# link_so <arch-dir> <ffmpeg-dir> <clang-triple>
link_so() {
    local archdir="$1" ff="$2" triple="$3"
    local out="$OUT_ROOT/android-$archdir"
    local archives=()
    local lib
    for lib in "${LIBS[@]}"; do
        archives+=("$ff/$lib/$lib.a")
    done
    mkdir -p "$out"
    "$TOOLCHAIN/bin/$triple${API}-clang" -shared -fPIC -O2 \
        -ffunction-sections -fdata-sections -Wl,--gc-sections -Wl,-Bsymbolic \
        -Wl,-z,max-page-size=16384 \
        -I"$ff" -o "$out/libliquidbounce_audiocodec.so" "$SRC" \
        -Wl,--whole-archive "${archives[@]}" -Wl,--no-whole-archive \
        -lm -lz -llog -landroid
    "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$out/libliquidbounce_audiocodec.so"
    echo "built $out/libliquidbounce_audiocodec.so"
}

TARGETS=("$@")
if [ ${#TARGETS[@]} -eq 0 ]; then
    TARGETS=(arm64 x64)
fi

for target in "${TARGETS[@]}"; do
    case "$target" in
        arm64|arm64-v8a)
            configure_ffmpeg arm64 aarch64 aarch64-linux-android "$WORK/out-arm64"
            link_so arm64 "$WORK/ffmpeg-arm64" aarch64-linux-android
            ;;
        x64|x86_64)
            configure_ffmpeg x64 x86_64 x86_64-linux-android "$WORK/out-x64"
            link_so x64 "$WORK/ffmpeg-x64" x86_64-linux-android
            ;;
        armeabi-v7a|armv7)
            # 32-bit ARM is intentionally not shipped: Pojav's 1.8 target is 64-bit only.
            echo "skipping $target (not shipped)" >&2
            ;;
        *)
            echo "unknown target: $target" >&2
            exit 1
            ;;
    esac
done
