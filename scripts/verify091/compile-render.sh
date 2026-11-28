#!/bin/bash
set -euo pipefail
R="$(cd "$(dirname "$0")/../.." && pwd)"; W=$(mktemp -d);trap 'rm -rf "$W"' EXIT
python3 "$R/scripts/verify091/render-hosts.py" "$W"
CP="$MC_JAR:$(find "$MC_LIBS" "$VERIFY_LIBS" -name '*.jar' -printf '%p:' | sort):$KOTLIN_LIBS/kotlin-stdlib.jar"
K="$R/src/main/kotlin/net/ccbluex/liquidbounce"
"$JAVA_HOME/bin/java" -Xmx512m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath "$CP" -d "$W/classes" "$W/"*.kt \
 "$K/render/ClientUniformDefine.kt" "$K/render/engine/LazyRenderTargetHolder.kt" \
 "$K/features/module/modules/render/motionblur/"*.kt
cat > "$W/JavaHooks.java" <<'JAVA'
class JavaHooks {
 void beforeGui() { net.ccbluex.liquidbounce.features.module.modules.render.motionblur.MotionBlurRenderer.beforeGui(); }
 void close() { net.ccbluex.liquidbounce.features.module.modules.render.motionblur.MotionBlurRenderer.INSTANCE.close(); }
}
JAVA
"$JAVA_HOME/bin/javac" -proc:none -classpath "$W/classes:$CP" -d "$W/classes" "$W/JavaHooks.java"
echo 'PASS: exact two new Java-to-Kotlin render hook calls compile (not the complete Mixin)'
 echo 'PASS: complete MotionBlur module/renderer/history, uniform enum and target holder + exact pipeline/pass/std140 excerpts compile against actual MC26.3'
