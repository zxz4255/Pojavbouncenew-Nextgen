#!/usr/bin/env bash
#
# Rebuilds libliquidbounce_audioout.so for Android (arm64 + x64) into src/main/resources/natives/.
#
# This library is tiny and has no third-party dependency at all: it only uses the NDK's own
# <aaudio/AAudio.h> and <SLES/OpenSLES.h>, both of which come from the system the app already runs
# on. It is what actually produces sound for the Music module, because `android.media.AudioTrack`
# is a *Java framework* class that a Pojav/Zalith launcher (which runs the game on its own OpenJDK
# bootclasspath) can never see through Class.forName.
#
# Requirements:
#   * Android NDK r26+  ($ANDROID_NDK_HOME or $ANDROID_NDK_ROOT)
#
# Usage:
#   ./build-android.sh            # both ABIs
#   ./build-android.sh arm64      # only aarch64
#
# API notes:
#   * API 26 is the minimum here because that is where AAudio appears. The library still builds a
#     working OpenSL ES sink when it is loaded on an older device, because both paths are compiled
#     in and picked at runtime (AAudio first, OpenSL second).
#   * `-z max-page-size=16384` keeps the .so loadable on 16 KB page devices (Android 15+).
set -euo pipefail

API="${API:-26}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$ROOT/src/audio_output_jni.c"
OUT_ROOT="$ROOT/../../src/main/resources/natives"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "ANDROID_NDK_HOME is not set (needed for the Android clang wrappers)" >&2
    exit 1
fi

HOST_TAG="${ANDROID_NDK_HOST_TAG:-}"
if [ -z "$HOST_TAG" ]; then
    case "$(uname -s)" in
        Linux)  HOST_TAG=linux-x86_64 ;;
        Darwin) HOST_TAG=darwin-x86_64 ;;
        *) echo "Unsupported host: $(uname -s)" >&2; exit 1 ;;
    esac
fi
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"

build_one() {
    local abi="$1" triple="$2"
    local out="$OUT_ROOT/android-$abi"
    mkdir -p "$out"
    echo "==> $abi ($triple$API)"
    "$TOOLCHAIN/bin/${triple}${API}-clang" \
        -shared -fPIC -O2 -fvisibility=hidden \
        -ffunction-sections -fdata-sections \
        -Wall -Wextra -Wno-unused-parameter \
        -Wl,--gc-sections -Wl,-Bsymbolic -Wl,-z,max-page-size=16384 \
        --sysroot="$TOOLCHAIN/sysroot" \
        -o "$out/libliquidbounce_audioout.so" "$SRC" \
        -llog -landroid -lOpenSLES
    "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$out/libliquidbounce_audioout.so"
    ls -l "$out/libliquidbounce_audioout.so"
}

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
    targets=(arm64 x64)
fi

for target in "${targets[@]}"; do
    case "$target" in
        arm64|arm64-v8a) build_one arm64 aarch64-linux-android ;;
        x64|x86_64)      build_one x64 x86_64-linux-android ;;
        *) echo "Unknown target: $target" >&2; exit 1 ;;
    esac
done
