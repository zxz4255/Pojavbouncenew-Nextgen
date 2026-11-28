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
python3 "$ROOT/scripts/verify087/inherit-shortcuts.py" "$W/fixtures"
CP="$W/wire.jar:$CP:$KOTLIN_LIBS/kotlin-stdlib.jar"
: "${BASELINE086:?Immutable delivered086 ZIP for negative compile control}"
python3 - "$BASELINE086" "$W/negative" <<'PY'
from pathlib import Path
import sys,zipfile,hashlib
p=Path(sys.argv[1]);assert hashlib.sha256(p.read_bytes()).hexdigest()=='d0e16fdba47a49976a79f9c17ed98cb6b4eb9d5fd7084a9864ab78bacb5c85fc'
out=Path(sys.argv[2]);out.mkdir()
with zipfile.ZipFile(p) as z:
 names=[n for n in z.namelist() if n.startswith('src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/neoraven/') and n.endswith('.kt')]
 assert len(names)==6
 for n in names:(out/Path(n).name).write_bytes(z.read(n))
PY
if "$J/java" -Xmx512m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 -no-stdlib -no-reflect -jvm-target 25 -classpath "$CP" -d "$W/negative-classes" "$W/fixtures/"*.kt \
 "$W/negative/"*.kt "$S/Regression.kt" \
 "$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/features/module/MinecraftShortcuts.kt" \
 "$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/features/addon/AddonApi.kt" > "$W/negative.log" 2>&1; then
 echo 'FAIL: old086 negative compile unexpectedly succeeded'; exit 1
fi
python3 - "$W/negative.log" <<'PY'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text()
errors=[line for line in s.splitlines() if 'error:' in line]
assert len(errors)==2,errors
for name in ['ModuleNeoKillAura.kt','ModuleNeoKillAura2.kt']:
 assert any(name in e and "'world' hides member of supertype 'MinecraftShortcuts'" in e for e in errors),errors
print('PASS: immutable086 negative control reproduces EXACTLY the two reported world/MinecraftShortcuts errors')
for e in errors: print(e)
PY
"$J/java" -Xmx512m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 -no-stdlib -no-reflect -jvm-target 25 -classpath "$CP" -d "$W/classes" "$W/fixtures/"*.kt \
 "$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/neoraven/"*.kt "$S/Regression.kt" \
 "$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/features/module/MinecraftShortcuts.kt" \
 "$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/features/addon/AddonApi.kt"
echo 'PASS: six complete NeoAura production files + actual MinecraftShortcuts + actual AddonApi compile; ClientModule host inherits real shortcuts'
"$J/javac" -proc:none -cp "$CP" -d "$W/audit" "$S/ApiAudit.java"
"$J/java" -Xmx128m -cp "$W/audit:$CP" verify085.ApiAudit "$W/classes" "$CP"
# No game APIs or fixtures on runtime classpath: these are real production pure-logic tests.
"$J/java" -Xmx128m -cp "$W/classes:$KOTLIN_LIBS/kotlin-stdlib.jar" \
 net.ccbluex.liquidbounce.features.module.modules.combat.neoraven.RegressionKt
