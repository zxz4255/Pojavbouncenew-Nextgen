#!/bin/bash
set -euo pipefail
R="$(cd "$(dirname "$0")/../.." && pwd)";W=$(mktemp -d);trap 'rm -rf "$W"' EXIT
python3 "$R/scripts/verify096/runtime-hosts.py" "$W/hosts"
python3 - "$1" "$W/old" <<'PY'
import zipfile,hashlib,sys
from pathlib import Path
p=Path(sys.argv[1]);assert hashlib.sha256(p.read_bytes()).hexdigest()=='bb2f76de4842d59dc9f40db7c72cd4cb848df9602a2ee7b691b0105d054aeaef'
w=Path(sys.argv[2]);w.mkdir()
with zipfile.ZipFile(p) as z:
 for base in ['InventorySafetyPolicy','SafeInventorySession','ModuleNeoInvManager','NeoInventoryPolicy','NeoChestItems','NeoChestPolicy']:
  paths=[n for n in z.namelist() if n.endswith('/'+base+'.kt')];assert len(paths)==1
  (w/(base+'.kt')).write_bytes(z.read(paths[0]))
PY
CP="$KOTLIN_LIBS/kotlin-stdlib.jar"
"$JAVA_HOME/bin/java" -Xmx512m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath "$CP" -d "$W/classes" "$W/hosts/"*.kt "$W/old/"*.kt "$R/scripts/verify096/Reproduce095.kt"
(cd "$W"; "$JAVA_HOME/bin/java" -Xmx256m -cp "$W/classes:$CP" verify096baseline.Reproduce095Kt)
