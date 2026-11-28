#!/bin/bash
set -euo pipefail
R="$(cd "$(dirname "$0")/../.." && pwd)";W=$(mktemp -d);trap 'rm -rf "$W"' EXIT
K="$R/src/main/kotlin/net/ccbluex/liquidbounce"
"$JAVA_HOME/bin/java" -Xmx384m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath "$KOTLIN_LIBS/kotlin-stdlib.jar" -d "$W/classes" \
 "$K/features/module/modules/player/neoraven/NeoInvBindLatch.kt" "$K/features/module/modules/render/motionblur/MotionBlurHistory.kt" "$R/scripts/verify091/Regression.kt"
"$JAVA_HOME/bin/java" -Xmx128m -cp "$W/classes:$KOTLIN_LIBS/kotlin-stdlib.jar" verify091.RegressionKt
