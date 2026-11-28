#!/bin/bash
set -euo pipefail
R="$(cd "$(dirname "$0")/../.." && pwd)";W="${VERIFY_WORK:-$(mktemp -d)}";mkdir -p "$W"
if [ -z "${VERIFY_WORK:-}" ];then trap 'rm -rf "$W"' EXIT;fi
python3 "$R/scripts/verify092/lifecycle-hosts.py" "$W/hosts"
python3 "$R/scripts/verify095/inv-hosts.py" "$W/hosts"
sed '/m.description/s/092-DIAGNOSTIC/095 按一下切换/' "$R/scripts/verify092/LifecycleRegression.kt" > "$W/LifecycleRegression.kt"
CP="$MC_JAR:$(find "$MC_LIBS" "$VERIFY_LIBS" -name '*.jar' -printf '%p:' | sort):$KOTLIN_LIBS/kotlin-stdlib.jar:$KOTLIN_LIBS/kotlinx-coroutines-core-jvm.jar"
K="$R/src/main/kotlin/net/ccbluex/liquidbounce"
"$JAVA_HOME/bin/java" -Xmx512m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath "$CP" -d "$W/classes" "$W/hosts/"*.kt \
 "$K/config/types/"{Value,ValueWriteTrace}.kt "$K/config/types/group/ToggleableValueGroup.kt" "$K/features/misc/Toggleable.kt" \
 "$K/features/module/ClientModule.kt" "$K/features/module/MinecraftShortcuts.kt" \
 "$K/utils/inventory/"{InventorySafetyPolicy,SafeInventorySession}.kt \
 "$K/features/module/modules/player/neoraven/"{ModuleNeoInvManager,NeoInvBindLatch,NeoInvHotkeys,NeoInvDiagnostics,NeoInvTraceWriter,NeoInventoryPolicy,NeoChestPolicy,NeoChestItems}.kt \
 "$W/LifecycleRegression.kt" "$R/scripts/verify095/InvRegression.kt"
(cd "$W"; "$JAVA_HOME/bin/java" -Xmx256m -cp "$W/classes:$CP" verify092.LifecycleRegressionKt)

(cd "$W"; "$JAVA_HOME/bin/java" -Xmx256m -cp "$W/classes:$CP" net.ccbluex.liquidbounce.features.module.modules.player.neoraven.InvRegressionKt)
