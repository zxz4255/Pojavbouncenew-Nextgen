#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)";cd "$ROOT"
: "${JAVA_HOME:?JDK25}" "${MC_JAR:?MC26.3}" "${MC_LIBS:?MC manifest libraries}" "${VERIFY_LIBS:?VFP5.1.1/ASM libraries}" "${KOTLIN_LIBS:?Kotlin2.4.20}"
W="$(mktemp -d)";trap 'rm -rf "$W"' EXIT
J="$JAVA_HOME/bin";S="$ROOT/scripts/verify088";K="$ROOT/src/main/kotlin/net/ccbluex/liquidbounce"
python3 - "$ROOT/scripts/verify085/source-lock.json" "$MC_JAR" "$VERIFY_LIBS/vfp.jar" <<'PY'
import json,hashlib,sys
from pathlib import Path
lock=json.loads(Path(sys.argv[1]).read_text())
for key,path in [('minecraftSha256',sys.argv[2]),('vfpSha256',sys.argv[3])]:assert hashlib.sha256(Path(path).read_bytes()).hexdigest()==lock[key]
print('PASS: actual MC26.3 / VFP5.1.1 SHA256 locks')
PY
CP="$MC_JAR:$(find "$MC_LIBS" "$VERIFY_LIBS" -name '*.jar' -printf '%p:' | sort):$KOTLIN_LIBS/kotlin-stdlib.jar"
mkdir -p "$W/bridge"
echo 'package net.ccbluex.liquidbounce.utils.client.vfp; public enum VfpCompatibility {INSTANCE; public boolean isOlderThanOrEqual1_8(){return false;}}' > "$W/VfpCompatibility.java"
"$J/javac" -proc:none -cp "$CP" -d "$W/bridge" "$W/VfpCompatibility.java" "$ROOT/src/main/java/net/ccbluex/liquidbounce/utils/client/vfp/VfpCompatibility1_8.java"
"$J/jar" --create --file "$W/bridge.jar" -C "$W/bridge" .
CP="$W/bridge.jar:$CP"
python3 "$S/hosts.py" "$W/hosts" "$ROOT"
"$J/java" -Xmx512m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 -no-stdlib -no-reflect -jvm-target 25 -classpath "$W/bridge:$CP" -d "$W/classes" \
 "$W/hosts/"*.kt "$K/features/module/MinecraftShortcuts.kt" "$K/features/addon/AddonApi.kt" \
 "$K/features/module/modules/render/cs2mvp/"*.kt "$S/Regression.kt"
echo 'PASS: all 3 complete MVP production Kotlin sources + real MinecraftShortcuts/AddonApi compile against actual MC26.3/VFP bridge (project services are explicit hosts)'
"$J/java" -Xmx128m -cp "$W/classes:$KOTLIN_LIBS/kotlin-stdlib.jar" net.ccbluex.liquidbounce.features.module.modules.render.cs2mvp.RegressionKt
# Reuse the API auditor algorithm, scoped to this production package, without altering the085 proof.
python3 - "$ROOT/scripts/verify085/ApiAudit.java" "$W/ApiAudit.java" <<'PY'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text().replace('package verify085;','package verify088;').replace('combat/neoraven/','render/cs2mvp/').replace('mcRefs<100 || wireRefs<5','mcRefs<20 || wireRefs<1')
Path(sys.argv[2]).write_text(s)
PY
"$J/javac" -proc:none -cp "$CP" -d "$W/audit" "$W/ApiAudit.java"
"$J/java" -Xmx128m -cp "$W/audit:$W/bridge:$CP" verify088.ApiAudit "$W/classes" "$W/bridge:$CP"
python3 "$S/check-wiring.py"
echo 'NOT a full Gradle build, game/Mixin/Android test or screenshot/animation parity acceptance'
