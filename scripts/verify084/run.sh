#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)";cd "$ROOT"
: "${JAVA_HOME:?JDK25}" "${MC_JAR:?Official26.3 client jar}" "${MC_LIBS:?Official manifest compile libs}" "${VERIFY_LIBS:?Mixin/MixinExtras/ASM jars}" "${BASELINE083:?Immutable delivered083 ZIP}"
W="$(mktemp -d)";trap 'rm -rf "$W"' EXIT
J="$JAVA_HOME/bin";S="$ROOT/scripts/verify084";R="$ROOT/src/main/java/net/ccbluex/liquidbounce"
python3 - "$MC_JAR" <<'HASH'
import pathlib,hashlib,sys
assert hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()=='4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d'
print('PASS: pinned official MC26.3 client SHA256')
HASH
CP="$MC_JAR:$(find "$VERIFY_LIBS" "$MC_LIBS" -name '*.jar' -printf '%p:' )"
JOML="$(find "$MC_LIBS" -name 'joml-*.jar' | head -1)"
python3 "$S/prepare.py" "$W" "$BASELINE083"
# Reuse unchanged historical host generator, NOT its superseded software-pointer tests.
python3 "$ROOT/scripts/verify083/fixtures.py" "$W"
python3 - "$W/runtime-hosts/SDLMouse.java" <<'FIXTURE'
from pathlib import Path
import sys
p=Path(sys.argv[1]);s=p.read_text().replace('public static boolean relative=true,visible=false;', 'public static boolean relative=true,visible=false,showResult=true;').replace('visible=true;shows++;return true;', 'if(showResult)visible=true;shows++;return showResult;');p.write_text(s)
FIXTURE
# Only unrelated project services are fixtures. MC/SDL and hook API types are REAL in this phase.
"$J/javac" -proc:none -cp "$CP" -d "$W/real-compile" "$W/project/"*.java "$W/hooks/"*.java \
    "$R/utils/input/AndroidGuiCursor.java"
echo 'PASS: complete native-only helper and verbatim retained cursor hooks compiled against actual MC26.3/SDL/Mixin APIs'
"$J/javac" -proc:none -cp "$CP" -d "$W/audit" "$S/ApiAudit.java"
"$J/java" -cp "$W/audit:$CP" verify084.ApiAudit "$W/real-compile" "$MC_JAR"
# Swap explicit runtime hosts, but never recompile production code against fake API types.
RCP="$W/real-compile:$VERIFY_LIBS/mixin.jar:$JOML"
"$J/javac" -proc:none -cp "$RCP" -d "$W/runtime" "$W/runtime-hosts/"*.java "$W/project/"*.java "$S/CursorRegression.java"
"$J/java" -Dos.version=android -cp "$W/runtime:$RCP" verify084.CursorRegression android
env -u ANDROID_ROOT -u POJAV_NATIVEDIR "$J/java" -Dos.version=linux -cp "$W/runtime:$RCP" verify084.CursorRegression desktop
