#!/usr/bin/env bash
# Isolated headless persistence regression checks (does not launch Minecraft).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 25}"
: "${MC_JAR:?Set MC_JAR to the official Minecraft 26.3 client.jar}"
: "${VERIFY_LIBS:?Set VERIFY_LIBS to the dependency jar directory}"
KOTLINC="${KOTLINC:-kotlinc}"
export JAVA_OPTS="${JAVA_OPTS:--Xmx512m}"
CP="$MC_JAR:$(printf '%s:' "$VERIFY_LIBS"/*.jar)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp -r "$HERE/stubs" "$HERE/ui-stubs" "$WORK/"
python3 "$HERE/prepare.py" "$ROOT" "$WORK"
R="$ROOT/src/main/kotlin/net/ccbluex/liquidbounce"
"$KOTLINC" -jvm-target 25 -cp "$CP" -d "$WORK/classes" \
 "$WORK"/stubs/S{1,2,3,4,5,6}.kt "$WORK"/ui-stubs/*.kt \
 "$R"/ui/common/{UiTextEditState,UiLocalization,UiAnimation,UiTextUtils}.kt \
 "$R"/ui/clickgui/{ClickGuiTabView,TabbedClickGuiScreen,ClickGuiLayoutStore,GlobalSettingsTabView}.kt \
 "$R"/ui/clickgui/setting/{SettingOverlayManager,ListSettingWidgets}.kt
echo 'PASS focused compile: layout store, ClickGUI, global settings, all changed expandable widgets and renderer logic (isolated fixtures)'
"$KOTLINC" -jvm-target 25 -cp "$WORK/classes:$CP" -include-runtime -d "$WORK/tests.jar" \
 "$HERE/PersistenceRegression.kt" "$HERE/ExtraWidgetRegression.kt" "$ROOT/scripts/verify070/UiRegression.kt"
TESTCP="$WORK/tests.jar:$WORK/classes:$ROOT/src/main/resources:$CP"
mkdir "$WORK/layout"
for op in write read; do
 "$JAVA_HOME/bin/java" -Xmx256m -Dverify.layoutDir="$WORK/layout" -cp "$TESTCP" PersistenceRegressionKt "$op"
done
for op in write read; do
 "$JAVA_HOME/bin/java" -Xmx256m -Dverify.layoutDir="$WORK/layout" -cp "$TESTCP" ExtraWidgetRegressionKt "$op"
done
mkdir "$WORK/legacy" "$WORK/bad" "$WORK/search"
echo '{"activeTab":2,"panels":{"Render":{"left":321,"top":123,"expanded":true,"scrollTop":456}}}' > "$WORK/legacy/ui_layout.json"
"$JAVA_HOME/bin/java" -Xmx256m -Dverify.layoutDir="$WORK/legacy" -cp "$TESTCP" PersistenceRegressionKt legacy
echo '{"panels":{"Broken":{"left":"bad"},"Good":{"left":100,"top":20,"scrollTop":-50}},"widgetStates":{"bad":{"expanded":42,"scrollTop":"NaN"}}}' > "$WORK/bad/ui_layout.json"
"$JAVA_HOME/bin/java" -Xmx256m -Dverify.layoutDir="$WORK/bad" -cp "$TESTCP" PersistenceRegressionKt invalid
"$JAVA_HOME/bin/java" -Xmx256m -Dverify.layoutDir="$WORK/search" -cp "$TESTCP" UiRegressionKt
