#!/bin/bash
set -euo pipefail
R="$(cd "$(dirname "$0")/../.." && pwd)";W=$(mktemp -d);trap 'rm -rf "$W"' EXIT
python3 "$R/scripts/verify095/shadow-hosts.py" "$R" "$W"
CP="$MC_JAR:$(find "$MC_LIBS" "$VERIFY_LIBS" -name '*.jar' -printf '%p:' | sort):$KOTLIN_LIBS/kotlin-stdlib.jar"
"$JAVA_HOME/bin/javac" -cp "$VERIFY_LIBS/asm.jar:$VERIFY_LIBS/asm-tree.jar" -d "$W/aw" "$R/scripts/verify095/WidenFormats.java"
"$JAVA_HOME/bin/java" -cp "$W/aw:$VERIFY_LIBS/asm.jar:$VERIFY_LIBS/asm-tree.jar" WidenFormats "$MC_JAR" "$R/src/main/resources/liquidbounce.accesswidener" "$W/aw.jar"
CP="$W/aw.jar:$CP"
"$R/scripts/verify093/compiler.sh" -nowarn -jvm-target 25 -cp "$CP" -d "$W/classes" "$W/"*.kt "$R/src/main/kotlin/net/ccbluex/liquidbounce/render/ClientVertexFormats.kt" "$R/src/main/kotlin/net/ccbluex/liquidbounce/utils/render/VerticesSetupHandler.kt"
echo 'PASS: exact Opai shadow emitter/pipeline/shader registration and complete vertex formats compile against actual MC26.3/RenderPearl. Queue/bounds are explicit hosts; not full Opai module or game rendering.'
