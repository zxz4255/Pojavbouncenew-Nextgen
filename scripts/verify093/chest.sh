#!/usr/bin/env bash
# Focused MC26.3 API compilation + pure-state/upstream-filter regressions, NOT a full mod build.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../verify075" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"
: "${JAVA_HOME:?JDK25 required}" "${MC_JAR:?Official MC26.3 client jar}" "${VERIFY_LIBS:?Dependency jar directory}"
KOTLINC="${KOTLINC:-$ROOT/scripts/verify093/compiler.sh}"
export JAVA_OPTS="${JAVA_OPTS:--Xmx320m}"
CP="$MC_JAR:$(find "$VERIFY_LIBS" "$MC_LIBS" -name '*.jar' -printf '%p:' | sort):$KOTLIN_LIBS/kotlin-stdlib.jar"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
N="$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/player/neoraven"
# Superseded preservation/registration assertions live in verify093/audit.py.
# Compile the ACTUAL new module/engine/adapters plus ACTUAL modified shared HUD observer.
# Surrounding LB module/event/config/rotation/chunk scanner and HUD painters are dependency fixtures.
printf '%s\n' 'package net.ccbluex.liquidbounce.event; enum class EventState { PRE,POST }' > "$WORK/EventState.kt"
mkdir -p "$WORK/stubs"
cp "$HERE/stubs/"*.kt "$WORK/stubs/"
sed -i 's/enum class EventState { PRE, POST }/typealias EventState = net.ccbluex.liquidbounce.event.EventState/' "$WORK/stubs/Events.kt"
mapfile -t STUBS < <(find "$WORK/stubs" -name '*.kt' ! -name Visual.kt | sort)
"$KOTLINC" -nowarn -jvm-target 25 -cp "$CP" -d "$WORK/classes" \
    "${STUBS[@]}" "$WORK/EventState.kt" "$HERE/hud-stubs/"*.kt "$N/"{ModuleNeoChestAura,ModuleNeoChestStealer,NeoChestEnvironment,NeoChestItems,NeoChestPolicy}.kt \
    "$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/OpaiChestLoot.kt"
"$JAVA_HOME/bin/javac" -J-Xmx256m -proc:none --release 25 \
    -cp "$WORK/classes:$CP" -d "$WORK/classes" \
    "$ROOT/src/main/java/net/ccbluex/liquidbounce/injection/mixins/minecraft/network/"MixinNeoChest*.java
echo 'PASS: actual 075 Kotlin modules/policy/item/protocol adapters + shared HUD observer and both Java packet mixins compile against real MC26.3 APIs (LB/HUD dependency fixtures).'
python3 "$HERE/make_oracle.py" "$WORK/NeoUpstreamLootOracle.java"
"$JAVA_HOME/bin/javac" -J-Xmx128m -proc:none --release 25 -d "$WORK/classes" "$WORK/NeoUpstreamLootOracle.java"
"$KOTLINC" -jvm-target 25 -cp "$WORK/classes:$CP" -d "$WORK/tests.jar" "$HERE/ChestRegression.kt"
"$JAVA_HOME/bin/java" -Xmx256m -cp "$WORK/tests.jar:$WORK/classes:$CP" ChestRegressionKt
if [[ "${SKIP_PRIOR:-1}" != 1 ]]; then
    bash "$ROOT/scripts/verify074/run.sh"
fi
echo 'PASS: 075 verification complete. Full build, applied Mixins, server transactions/rotations, native item-component runtime and Android/Pojav acceptance remain outstanding.'
