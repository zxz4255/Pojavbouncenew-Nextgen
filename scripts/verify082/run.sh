#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$ROOT"
: "${JAVA_HOME:?JDK25}" "${VERIFY_LIBS:?Gson and fastutil jars}" "${BASELINE081:?Immutable delivered081 ZIP}"
KOTLINC="${KOTLINC:-kotlinc}"
export JAVA_OPTS="${JAVA_OPTS:--Xmx384m}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
CP="$(printf '%s:' "$VERIFY_LIBS"/*.jar)$(dirname "$(dirname "$KOTLINC")")/lib/kotlinx-coroutines-core-jvm.jar"
python3 scripts/verify082/prepare.py "$WORK" "$BASELINE081"
python3 scripts/verify082/fixtures.py "$WORK"
R="$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/setting"
for MODE in old fixed; do
    "$KOTLINC" -nowarn -jvm-target 25 -cp "$CP" -include-runtime -d "$WORK/$MODE.jar" \
        "$WORK/common/"*.kt "$WORK/fixtures/"*.kt "$WORK/$MODE/"*.kt \
        "$R/TextSettingCodec.kt" "$ROOT/scripts/verify082/TextSettingRegression.kt"
    "$JAVA_HOME/bin/java" -Xmx256m -cp "$WORK/$MODE.jar:$CP" \
        net.ccbluex.liquidbounce.ui.clickgui.setting.TextSettingRegressionKt "$MODE"
done
