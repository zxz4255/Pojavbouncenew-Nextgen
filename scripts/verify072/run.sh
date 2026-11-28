#!/usr/bin/env bash
# Dependency-fixture compile/headless checks, NOT a Gradle build or GPU/game validation.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
: "${JAVA_HOME:?JDK25 required}" "${MC_JAR:?Official MC26.3 client jar}" "${VERIFY_LIBS:?Dependency jars}"
KOTLINC="${KOTLINC:-kotlinc}"
export JAVA_OPTS="${JAVA_OPTS:--Xmx512m}"
CP="$MC_JAR:$(printf '%s:' "$VERIFY_LIBS"/*.jar)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
R="$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/fpsknife"
"$KOTLINC" -jvm-target 25 -cp "$CP" -d "$WORK/classes" "$ROOT/scripts/verify070/stubs/"*.kt "$R/"*.kt
"$KOTLINC" -jvm-target 25 -cp "$WORK/classes:$CP" -include-runtime -d "$WORK/tests.jar" "$HERE/RigRegression.kt"
"$JAVA_HOME/bin/java" -Xmx256m -cp "$WORK/tests.jar:$WORK/classes:$CP" RigRegressionKt
bash "$ROOT/scripts/verify070/run.sh"
