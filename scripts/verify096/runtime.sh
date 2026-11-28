#!/bin/bash
set -euo pipefail
R="$(cd "$(dirname "$0")/../.." && pwd)";W="${VERIFY_RUNTIME_WORK:-$(mktemp -d)}";mkdir -p "$W"
if [ -z "${VERIFY_RUNTIME_WORK:-}" ];then trap 'rm -rf "$W"' EXIT;fi
python3 "$R/scripts/verify096/runtime-hosts.py" "$W/hosts"
K="$R/src/main/kotlin/net/ccbluex/liquidbounce"
CP="$KOTLIN_LIBS/kotlin-stdlib.jar"
"$JAVA_HOME/bin/java" -Xmx512m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath "$CP" -d "$W/classes" "$W/hosts/"*.kt \
 "$K/utils/inventory/"{InventorySafetyPolicy,SafeInventorySession}.kt \
 "$K/features/module/modules/player/neoraven/"{ModuleNeoInvManager,NeoInventoryPolicy,NeoChestPolicy,NeoChestItems}.kt \
 "$R/scripts/verify096/RuntimeRegression.kt" "$R/scripts/verify096/ArmorRegression.kt"
(cd "$W"; "$JAVA_HOME/bin/java" -Xmx256m -cp "$W/classes:$CP" verify096.ArmorRegressionKt)
(cd "$W"; "$JAVA_HOME/bin/java" -Xmx256m -cp "$W/classes:$CP" verify096.RuntimeRegressionKt)
python3 "$R/scripts/verify076/make_oracle.py" "$W/NeoUpstreamInventoryOracle.java"
"$JAVA_HOME/bin/javac" -proc:none --release 25 -d "$W/classes" "$W/NeoUpstreamInventoryOracle.java"
"$JAVA_HOME/bin/java" -Xmx384m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath "$W/classes:$CP" -d "$W/regression" "$R/scripts/verify076/InventoryRegression.kt"
(cd "$W"; "$JAVA_HOME/bin/java" -Xmx256m -cp "$W/regression:$W/classes:$CP" InventoryRegressionKt)
