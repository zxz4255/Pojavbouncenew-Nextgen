#!/usr/bin/env bash
# Focused compile + headless regression checks; NOT a replacement for Gradle or in-game testing.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 25}"
: "${MC_JAR:?Set MC_JAR to the official Minecraft 26.3 client jar}"
: "${VERIFY_LIBS:?Set VERIFY_LIBS to a directory of dependency jars (see README.md)}"
KOTLINC="${KOTLINC:-kotlinc}"
export JAVA_OPTS="${JAVA_OPTS:--Xmx512m}"
CP="$MC_JAR:$(printf '%s:' "$VERIFY_LIBS"/*.jar)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
R="$ROOT/src/main/kotlin/net/ccbluex/liquidbounce"
J="$ROOT/src/main/java/net/ccbluex/liquidbounce"
"$KOTLINC" -jvm-target 25 -cp "$CP" -d "$WORK/knife" "$HERE"/stubs/*.kt "$R"/features/module/modules/render/fpsknife/*.kt
"$JAVA_HOME/bin/javac" -J-Xmx256m -proc:none --release 25 -cp "$WORK/knife:$CP" -d "$WORK/knife" "$J/interfaces/EntityRenderStateAddition.java" "$J"/injection/mixins/minecraft/item/MixinFirstPersonHandsAndItems*.java
echo 'PASS: FPS knife Kotlin sources and both Java rendering mixins (dependency fixtures).'
"$KOTLINC" -jvm-target 25 -cp "$WORK/knife:$CP" -include-runtime -d "$WORK/knife-tests.jar" "$HERE/KnifeRegression.kt"
"$JAVA_HOME/bin/java" -Xmx256m -cp "$WORK/knife-tests.jar:$WORK/knife:$CP" KnifeRegressionKt
# 071 adds persistent widget state; its harness covers the current UI sources and reuses UiRegression.kt.
bash "$ROOT/scripts/verify071/run.sh"
