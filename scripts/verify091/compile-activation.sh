#!/bin/bash
set -euo pipefail
R="$(cd "$(dirname "$0")/../.." && pwd)";W=$(mktemp -d);trap 'rm -rf "$W"' EXIT
python3 "$R/scripts/verify091/activation-test.py" "$W/Activation.kt"
"$JAVA_HOME/bin/java" -Xmx384m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath "$KOTLIN_LIBS/kotlin-stdlib.jar" -d "$W/classes" "$W/Activation.kt"
"$JAVA_HOME/bin/java" -Xmx128m -cp "$W/classes:$KOTLIN_LIBS/kotlin-stdlib.jar" verify091.activation.ActivationKt
