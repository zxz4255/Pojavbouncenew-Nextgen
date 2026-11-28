#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$ROOT"
: "${JAVA_HOME:?JDK25}" "${MC_JAR:?Official 26.3 client jar}" "${MC_LIBS:?Manifest libraries directory}" "${VERIFY_LIBS:?VFP5.1.1 + extracted nested jars + ASM}" "${KOTLIN_LIBS:?Kotlin2.4.20 compiler/stdlib/runtime/reflect/coroutines/annotations jars}"
S="$ROOT/scripts/verify085"; J="$JAVA_HOME/bin"; W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
python3 - "$S/source-lock.json" "$MC_JAR" "$VERIFY_LIBS/vfp.jar" <<'PY'
import sys,json,hashlib,pathlib
lock=json.loads(pathlib.Path(sys.argv[1]).read_text())
for key,path in [('minecraftSha256',sys.argv[2]),('vfpSha256',sys.argv[3])]:
 assert hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()==lock[key],key
print('PASS: actual Minecraft26.3 / VFP5.1.1 SHA256 locks')
PY
python3 "$S/settings-audit.py"
CP="$MC_JAR:$(find "$VERIFY_LIBS" "$MC_LIBS" -name '*.jar' -printf '%p:' | sort)"
mkdir -p "$W/wire-host" "$W/wire-classes"
printf '%s\n' 'package net.ccbluex.liquidbounce.utils.client.vfp; public enum VfpCompatibility { INSTANCE; public boolean isOlderThanOrEqual1_8(){return false;} }' > "$W/wire-host/VfpCompatibility.java"
# Production bridge compiled against real ViaFabricPlus and ViaVersion classes, not a mock bridge.
# Only its outer protocol-selector facade is a compile host (never used for network tests).
"$J/javac" -proc:none -cp "$CP" -d "$W/wire-classes" "$W/wire-host/VfpCompatibility.java" \
 "$ROOT/src/main/java/net/ccbluex/liquidbounce/utils/client/vfp/VfpCompatibility1_8.java"
"$J/jar" --create --file "$W/wire.jar" -C "$W/wire-classes" .
python3 "$S/fixtures.py" "$W/fixtures"
python3 "$S/integration-hosts.py" "$W/fixtures"
CP="$W/wire.jar:$CP:$KOTLIN_LIBS/kotlin-stdlib.jar"
"$J/java" -Xmx512m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 -no-stdlib -no-reflect -jvm-target 25 -classpath "$CP" -d "$W/classes" "$W/fixtures/"*.kt \
 "$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/neoraven/"*.kt "$S/Regression.kt"
echo 'PASS: six complete production Kotlin files compile against actual MC26.3 + real production VFP bridge; project-only hosts are explicitly listed in fixtures.py'
"$J/javac" -proc:none -cp "$CP" -d "$W/audit" "$S/ApiAudit.java"
"$J/java" -Xmx128m -cp "$W/audit:$CP" verify085.ApiAudit "$W/classes" "$CP"
# No game APIs or fixtures on runtime classpath: these are real production pure-logic tests.
"$J/java" -Xmx128m -cp "$W/classes:$KOTLIN_LIBS/kotlin-stdlib.jar" \
 net.ccbluex.liquidbounce.features.module.modules.combat.neoraven.RegressionKt
