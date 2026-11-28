#!/bin/bash
set -euo pipefail
R="$(cd "$(dirname "$0")/../.." && pwd)"; W=$(mktemp -d);trap 'rm -rf "$W"' EXIT
python3 "$R/scripts/verify091/hosts.py" "$W/hosts"
CP="$MC_JAR:$(find "$MC_LIBS" "$VERIFY_LIBS" -name '*.jar' -printf '%p:' | sort):$KOTLIN_LIBS/kotlin-stdlib.jar"
K="$R/src/main/kotlin/net/ccbluex/liquidbounce"
"$JAVA_HOME/bin/java" -Xmx512m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath "$CP" -d "$W/classes" "$W/hosts/"*.kt \
 "$K/utils/inventory/"{InventorySafetyPolicy,SafeInventorySession}.kt \
 "$K/features/module/modules/player/ModuleInvManager.kt" \
 "$K/features/module/modules/player/neoraven/"{ModuleNeoInvManager,NeoInvBindLatch,NeoInvHotkeys,NeoInventoryPolicy,NeoChestPolicy,NeoChestItems}.kt
echo 'PASS: complete Neo module/hotkeys/latch + TS + native safety transport compile against actual MC26.3; config/events/bind hosts are fixtures'
python3 "$R/scripts/verify076/make_oracle.py" "$W/NeoUpstreamInventoryOracle.java"
"$JAVA_HOME/bin/javac" -proc:none --release 25 -d "$W/classes" "$W/NeoUpstreamInventoryOracle.java"
"$JAVA_HOME/bin/java" -Xmx512m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath "$W/classes:$CP" -d "$W/tests" "$R/scripts/verify076/InventoryRegression.kt"
"$JAVA_HOME/bin/java" -Xmx256m -cp "$W/tests:$W/classes:$CP" InventoryRegressionKt
