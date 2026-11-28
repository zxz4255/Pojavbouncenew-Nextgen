#!/usr/bin/env bash
# Focused MC26.3 API + modeled state/algorithm verification; NOT a full build/game test.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"
: "${JAVA_HOME:?JDK25 required}" "${MC_JAR:?Official MC26.3 client jar}" "${VERIFY_LIBS:?Dependency jar directory}"
KOTLINC="${KOTLINC:-kotlinc}"
export JAVA_OPTS="${JAVA_OPTS:--Xmx320m}"
CP="$MC_JAR:$(printf '%s:' "$VERIFY_LIBS"/*.jar)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
K="$ROOT/src/main/kotlin/net/ccbluex/liquidbounce"
N="$K/features/module/modules/player/neoraven"
python3 "$HERE/source_audit.py"
"$KOTLINC" -nowarn -jvm-target 25 -cp "$CP" -d "$WORK/classes" \
    "$HERE/stubs/"*.kt "$K/utils/inventory/"{InventorySafetyPolicy,SafeInventorySession}.kt \
    "$K/features/module/modules/player/ModuleInvManager.kt" \
    "$N/"{ModuleNeoInvManager,NeoInventoryPolicy,NeoChestPolicy,NeoChestItems}.kt
echo 'PASS: actual repaired TS module, new Neo module, shared safety policy/transport and modern adapters compile against MC26.3 APIs (host config/events/old scoring/slot helpers are fixtures).'
python3 "$HERE/make_oracle.py" "$WORK/NeoUpstreamInventoryOracle.java"
"$JAVA_HOME/bin/javac" -J-Xmx128m -proc:none --release 25 -d "$WORK/classes" "$WORK/NeoUpstreamInventoryOracle.java"
"$KOTLINC" -jvm-target 25 -cp "$WORK/classes:$CP" -include-runtime -d "$WORK/tests.jar" "$HERE/InventoryRegression.kt"
"$JAVA_HOME/bin/java" -Xmx256m -cp "$WORK/tests.jar:$WORK/classes:$CP" InventoryRegressionKt
"$KOTLINC" -nowarn -jvm-target 25 -cp "$CP" -d "$WORK/uiclasses" \
    "$HERE/stubs/"{S1,S3,S4,S6,Client,Events,Color}.kt "$HERE/ui-stubs/"*.kt \
    "$K/features/module/modules/render/ModuleClickGui.kt" "$K/ui/common/UiPalette.kt"
"$KOTLINC" -jvm-target 25 -cp "$WORK/uiclasses:$CP" -include-runtime -d "$WORK/style.jar" "$HERE/StyleRegression.kt"
"$JAVA_HOME/bin/java" -Xmx128m -cp "$WORK/style.jar:$WORK/uiclasses:$CP" StyleRegressionKt
if [[ "${SKIP_PRIOR:-0}" != 1 ]]; then
    bash "$ROOT/scripts/verify075/run.sh"
fi
echo 'PASS: 076 focused verification completed. Full Gradle/game/component runtime/server/Via/Mixin/Pojav acceptance remains outstanding.'
