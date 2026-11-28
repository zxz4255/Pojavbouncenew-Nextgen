#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${JAVA_HOME:?JDK25}" "${MC_JAR:?MC26.3 jar}" "${MC_LIBS:?MC libraries}" "${VERIFY_LIBS:?additional libraries}" "${KOTLIN_LIBS:?Kotlin compiler jars}"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
python3 "$ROOT/scripts/verify086/hosts.py" "$W" "$ROOT"
python3 "$ROOT/scripts/verify089/hosts.py" "$W" "$ROOT"
python3 "$ROOT/scripts/verify090/glaze-hosts.py" "$W" "$ROOT"
CP="$MC_JAR:$(find "$MC_LIBS" "$VERIFY_LIBS" -name '*.jar' -printf '%p:' | sort):$KOTLIN_LIBS/kotlin-stdlib.jar"
J="$JAVA_HOME/bin"; K="$ROOT/src/main/kotlin/net/ccbluex/liquidbounce"; R="$ROOT/src/main/java/net/ccbluex/liquidbounce/render/gui/element"
grep -Fq 'accessible class net/minecraft/client/gui/GuiGraphicsExtractor$ScissorStack' "$ROOT/src/main/resources/liquidbounce.accesswidener"
grep -Fq 'accessible field net/minecraft/client/gui/GuiGraphicsExtractor scissorStack ' "$ROOT/src/main/resources/liquidbounce.accesswidener"
grep -Fq 'accessible field net/minecraft/client/gui/GuiGraphicsExtractor guiRenderState ' "$ROOT/src/main/resources/liquidbounce.accesswidener"
"$J/javac" -proc:none -cp "$VERIFY_LIBS/asm.jar:$VERIFY_LIBS/asm-tree.jar" -d "$W/aw" "$ROOT/scripts/verify086/WidenGui.java"
"$J/java" -cp "$W/aw:$VERIFY_LIBS/asm.jar:$VERIFY_LIBS/asm-tree.jar" WidenGui "$MC_JAR" "$W/aw.jar"
CP="$W/aw.jar:$CP"
echo 'Applied the three existing project GUI access-widener declarations to real MC classes in a temporary compile overlay'
"$J/javac" -proc:none -cp "$CP" -d "$W/java" "$W/ClientRenderPipelines.java" "$W/PoseReusableGuiElementRenderState.java" \
 "$R/ClickGuiGlassGuiElementRenderState.java" "$R/CssBoxGuiElementRenderState.java"
"$J/java" -Xmx512m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 -no-stdlib -no-reflect -jvm-target 25 -classpath "$W/java:$CP" -d "$W/classes" "$W/"*.kt \
 "$K/ui/clickgui/glass/LightPollutionGlassSurface.kt" \
 "$K/ui/clickgui/glass/ClickGuiGlassSurface.kt" \
 "$K/features/module/modules/render/moderngui/ModernGuiSurface.kt" \
 "$ROOT/scripts/verify089/ScopeRegression.kt"
echo 'PASS: complete production glass Java records + three complete Kotlin surface emitters compile against actual MC26.3'
echo 'PASS: exact Onyx card hook and Style enum bodies compile; project-only hosts listed in hosts.py'
"$J/javac" -proc:none -cp "$W/java:$CP" -d "$W/java" "$ROOT/scripts/verify089/VertexRegression.java"
"$J/java" -Xmx128m -cp "$W/java:$CP" VertexRegression
"$J/java" -Xmx128m -cp "$W/classes:$W/java:$CP" verify089.ScopeRegressionKt
echo 'NOT a complete Gradle build, Mixin/game/Android validation' 
