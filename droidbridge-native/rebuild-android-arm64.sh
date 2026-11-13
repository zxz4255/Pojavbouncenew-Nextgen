#!/usr/bin/env bash
# Rebuild the Android bridge dex and the aarch64 .so on a Linux x86_64 host.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to an installed Android SDK}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed Android NDK}"
API="${ANDROID_API:-35}"
ANDROID_JAR="$ANDROID_SDK_ROOT/platforms/android-$API/android.jar"
D8="${D8:-$(find "$ANDROID_SDK_ROOT/build-tools" -mindepth 2 -maxdepth 2 -type f -name d8 | sort -V | tail -1)}"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -f "$ANDROID_JAR" && test -x "$D8" && test -x "$TOOLCHAIN/aarch64-linux-android21-clang"
command -v cargo >/dev/null
rustup target add aarch64-linux-android
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/classes" "$TMP/dex"
mapfile -d '' JAVA_FILES < <(find "$ROOT/droidbridge-android" -name '*.java' -print0)
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$TMP/classes" "${JAVA_FILES[@]}"
mapfile -d '' CLASS_FILES < <(find "$TMP/classes" -name '*.class' -print0)
"$D8" --min-api 21 --lib "$ANDROID_JAR" --output "$TMP/dex" "${CLASS_FILES[@]}"
cp "$TMP/dex/classes.dex" "$ROOT/droidbridge-native/android-bridge.dex"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/aarch64-linux-android21-clang"
export CC_aarch64_linux_android="$TOOLCHAIN/aarch64-linux-android21-clang"
export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
cargo build --locked --manifest-path "$ROOT/droidbridge-native/Cargo.toml" --release --lib --target aarch64-linux-android
mkdir -p "$ROOT/src/main/resources/natives/android-arm64"
cp "$ROOT/droidbridge-native/target/aarch64-linux-android/release/libliquidbounce_droidbridge.so" "$ROOT/src/main/resources/natives/android-arm64/"
echo 'Rebuilt Android ARM64 native resource.'
