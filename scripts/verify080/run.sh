#!/usr/bin/env bash
# Verify exact host namespace/inheritance regressions with real MC26.3 and MinecraftShortcuts.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"; cd "$ROOT"
: "${JAVA_HOME:?JDK25}" "${MC_JAR:?MC26.3 client jar}" "${VERIFY_LIBS:?jar directory}" "${BASELINE079:?delivered079 ZIP}"
KOTLINC="${KOTLINC:-kotlinc}"
export JAVA_OPTS="${JAVA_OPTS:--Xmx320m}"
CP="$MC_JAR:$(printf '%s:' "$VERIFY_LIBS"/*.jar)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
K="$ROOT/src/main/kotlin/net/ccbluex/liquidbounce"
N="$K/features/module/modules/player/neoraven"
python3 "$HERE/source-check.py" "$WORK" "$BASELINE079"
COMMON=("$K/features/module/MinecraftShortcuts.kt" "$K/features/addon/AddonApi.kt" "$WORK/EventState.kt")
compile_chest() {
    local dest="$1" source="$2"
    "$KOTLINC" -nowarn -jvm-target 25 -cp "$CP" -d "$dest" \
        "$WORK/chest-stubs/"*.kt "$ROOT/scripts/verify075/hud-stubs/"*.kt "${COMMON[@]}" \
        "$source/ModuleNeoChestAura.kt" "$source/ModuleNeoChestStealer.kt" \
        "$N/"{NeoChestEnvironment,NeoChestItems,NeoChestPolicy}.kt \
        "$K/features/module/modules/render/OpaiChestLoot.kt"
}
compile_inventory() {
    local dest="$1" source="$2"
    "$KOTLINC" -nowarn -jvm-target 25 -cp "$CP" -d "$dest" \
        "$WORK/inv-stubs/"*.kt "${COMMON[@]}" \
        "$K/utils/inventory/"{InventorySafetyPolicy,SafeInventorySession}.kt \
        "$K/features/module/modules/player/ModuleInvManager.kt" \
        "$source/ModuleNeoInvManager.kt" "$N/"{NeoInventoryPolicy,NeoChestPolicy,NeoChestItems}.kt
}
if compile_chest "$WORK/old-chest" "$WORK/original" > "$WORK/old-chest.txt" 2>&1; then
    echo 'FAIL: baseline chest sources unexpectedly compiled'; exit 1
fi
if compile_inventory "$WORK/old-inv" "$WORK/original" > "$WORK/old-inv.txt" 2>&1; then
    echo 'FAIL: baseline inventory source unexpectedly compiled'; exit 1
fi
python3 - "$WORK" <<'PY'
import pathlib,sys
w=pathlib.Path(sys.argv[1]);a=(w/'old-chest.txt').read_text();b=(w/'old-inv.txt').read_text()
assert "unresolved reference 'eventstate'" in a.lower(),a
assert "'world' hides member of supertype 'MinecraftShortcuts'" in a,a
assert "'world' hides member of supertype 'MinecraftShortcuts'" in b,b
print('PASS: original079 sources reproduce all three reported compiler errors with corrected host fixtures.')
PY
compile_chest "$WORK/chest" "$N"
compile_inventory "$WORK/inv" "$N"
echo 'PASS: all three actual patched modules compile against MC26.3, actual MinecraftShortcuts and EventState enum extracted from the real event package (other LB/HUD hosts are fixtures).'
# Existing pure/model regressions, with unchanged upstream Java method-body oracles.
python3 "$ROOT/scripts/verify075/make_oracle.py" "$WORK/NeoUpstreamLootOracle.java"
"$JAVA_HOME/bin/javac" -J-Xmx128m -proc:none --release 25 -d "$WORK/chest" "$WORK/NeoUpstreamLootOracle.java"
"$KOTLINC" -jvm-target 25 -cp "$WORK/chest:$CP" -include-runtime -d "$WORK/chest-tests.jar" "$ROOT/scripts/verify075/ChestRegression.kt"
"$JAVA_HOME/bin/java" -Xmx256m -cp "$WORK/chest-tests.jar:$WORK/chest:$CP" ChestRegressionKt
python3 "$ROOT/scripts/verify076/make_oracle.py" "$WORK/NeoUpstreamInventoryOracle.java"
"$JAVA_HOME/bin/javac" -J-Xmx128m -proc:none --release 25 -d "$WORK/inv" "$WORK/NeoUpstreamInventoryOracle.java"
"$KOTLINC" -jvm-target 25 -cp "$WORK/inv:$CP" -include-runtime -d "$WORK/inv-tests.jar" "$ROOT/scripts/verify076/InventoryRegression.kt"
"$JAVA_HOME/bin/java" -Xmx256m -cp "$WORK/inv-tests.jar:$WORK/inv:$CP" InventoryRegressionKt
echo 'PASS: 080 focused regression complete. No full Gradle, game/server, applied Mixin or Pojav claim.'
