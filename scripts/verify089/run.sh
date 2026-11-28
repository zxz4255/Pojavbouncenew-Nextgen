#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)";cd "$ROOT"
: "${BASELINE088:?Immutable088 source ZIP}" "${MC_JAR:?MC26.3}" "${VERIFY_LIBS:?Real VFP/nested/ASM jars}"
python3 - "$ROOT/scripts/verify085/source-lock.json" "$MC_JAR" "$VERIFY_LIBS/vfp.jar" <<'PY'
from pathlib import Path
import json,hashlib,sys
lock=json.loads(Path(sys.argv[1]).read_text())
for key,path in [('minecraftSha256',sys.argv[2]),('vfpSha256',sys.argv[3])]:assert hashlib.sha256(Path(path).read_bytes()).hexdigest()==lock[key]
print('PASS: MC26.3/VFP5.1.1 actual artifact SHA256 locks')
PY
bash scripts/verify089/compile.sh 2>&1 | tee scripts/verify089/compile-results.txt
python3 scripts/verify089/shader-test.py 2>&1 | tee scripts/verify089/shader-results.txt
bash scripts/verify088/run.sh 2>&1 | tee scripts/verify089/mvp-regression.txt
python3 scripts/verify089/check-wiring.py "$BASELINE088" | tee scripts/verify089/preservation-results.txt
if [ -n "${SOURCE_IMAGES:-}" ]; then
 python3 scripts/verify089/measure-references.py "$SOURCE_IMAGES" | tee scripts/verify089/source-measurements.txt
 python3 scripts/verify089/build-review.py "$SOURCE_IMAGES"
fi
echo '089 bounded checks PASS; NOT full Gradle/game/Android/same-scene pixel acceptance'
