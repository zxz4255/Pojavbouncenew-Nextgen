#!/usr/bin/env bash
# Focused real-MC-API compile with app/config/event fixtures + headless CPU/EGL tests.
# NOT a Loom/full Gradle build, game launch, Mixin-application or Android/Pojav test.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"
: "${JAVA_HOME:?JDK25 required}" "${MC_JAR:?Official MC26.3 client jar}" "${VERIFY_LIBS:?Dependency jars}"
KOTLINC="${KOTLINC:-kotlinc}"
export JAVA_OPTS="${JAVA_OPTS:--Xmx320m}"
CP="$MC_JAR:$(printf '%s:' "$VERIFY_LIBS"/*.jar)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
R="$ROOT/src/main/kotlin/net/ccbluex/liquidbounce"
python3 "$HERE/source_audit.py"
"$JAVA_HOME/bin/javac" -J-Xmx128m -cp "$CP" -d "$WORK" "$HERE/ApplyAccess.java"
"$JAVA_HOME/bin/java" -Xmx128m -cp "$WORK:$CP" ApplyAccess "$MC_JAR" \
    "$ROOT/src/main/resources/liquidbounce.accesswidener" "$WORK/access.jar"
CP="$WORK/access.jar:$CP"
"$KOTLINC" -nowarn -jvm-target 25 -cp "$CP" -d "$WORK/classes" \
    "$HERE"/stubs/*.kt \
    "$R/utils/kotlin/OptionalExtensions.kt" \
    "$R/render/ClientShaders.kt" "$R/render/ClientUniformDefine.kt" \
    "$R/render/ClientRenderPipelines.kt" "$R/render/ClientVertexFormats.kt" \
    "$R/render/engine/LazyRenderTargetHolder.kt" \
    "$R/features/module/modules/render/ModuleCinematic.kt" \
    "$R/features/module/modules/render/ModuleRainReflections.kt" \
    "$R/features/module/modules/render/neoraven/"*.kt
echo 'PASS: actual new modules/renderers, entire actual shader/pipeline/UBO/vertex-format registries and target holder compile against official MC26.3 APIs (app/event/config dependency fixtures).'
"$KOTLINC" -jvm-target 25 -cp "$WORK/classes:$CP" -include-runtime -d "$WORK/tests.jar" "$HERE/NeoRegression.kt"
"$JAVA_HOME/bin/java" -Xmx256m -cp "$WORK/tests.jar:$WORK/classes:$CP" NeoRegressionKt
for f in "$ROOT/src/main/resources/resources/liquidbounce/shaders/neoraven/"*.fsh; do
    glslangValidator -S frag "$f"
done
echo 'PASS: five GLSL330 shader syntax checks.'
python3 "$HERE/gpu_smoke.py"
if [[ "${SKIP_PRIOR:-0}" != 1 ]]; then
    bash "$ROOT/scripts/verify073/run.sh"
fi
echo 'PASS: 074 verification completed. Full Gradle/game/Mixin/Pojav verification remains outstanding.'
