#!/bin/bash
set -euo pipefail
R="$(cd "$(dirname "$0")/../.." && pwd)";W=$(mktemp -d);trap 'rm -rf "$W"' EXIT
python3 "$R/scripts/verify097/runtime-hosts.py" "$W/hosts"
K="$R/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/cs2mvp"
"$JAVA_HOME/bin/java" -Xmx384m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath "$KOTLIN_LIBS/kotlin-stdlib.jar" -d "$W/classes" "$W/hosts/"*.kt "$K/ModuleCs2Mvp.kt" "$K/Cs2MvpState.kt" "$R/scripts/verify097/AdapterRegression.kt"
(cd "$W"; "$JAVA_HOME/bin/java" -Xmx128m -cp "$W/classes:$KOTLIN_LIBS/kotlin-stdlib.jar" net.ccbluex.liquidbounce.features.module.modules.render.cs2mvp.AdapterRegressionKt)
