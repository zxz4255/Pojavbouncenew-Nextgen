#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$ROOT"
: "${JAVA_HOME:?JDK25}" "${MC_JAR:?Official26.3 client jar}" "${MC_LIBS:?Manifest libraries directory}" "${VERIFY_LIBS:?VFP5.1.1 jars, Mixin, MixinExtras and ASM jars}" "${BASELINE082:?Immutable delivered082 source ZIP}"
W="$(mktemp -d)";trap 'rm -rf "$W"' EXIT
J="$JAVA_HOME/bin";S="$ROOT/scripts/verify083";R="$ROOT/src/main/java/net/ccbluex/liquidbounce"
python3 - "$S/source-lock.json" "$MC_JAR" "$VERIFY_LIBS/vfp.jar" <<'HASH'
import hashlib,json,pathlib,sys
lock=json.loads(pathlib.Path(sys.argv[1]).read_text())
for name,path in [('minecraft',sys.argv[2]),('viafabricplus',sys.argv[3])]:
    assert hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()==lock[name]['sha256'],name+' artifact mismatch'
print('PASS: pinned actual Minecraft26.3/ViaFabricPlus5.1.1 artifact SHA256')
HASH
CP="$MC_JAR:$(find "$VERIFY_LIBS" "$MC_LIBS" -name '*.jar' -printf '%p:' )"
JOML="$(find "$MC_LIBS" -name 'joml-*.jar' | head -1)"
python3 "$S/prepare.py" "$W" "$BASELINE082"
python3 "$S/fixtures.py" "$W"
# No game API mocks here: new production code must compile against actual MC26.3/SDL/VFP APIs.
"$J/javac" -proc:none -cp "$CP" -d "$W/real-compile" "$W/project/"*.java "$W/hooks/"*.java \
    "$R/utils/input/AndroidGuiCursor.java" "$R/utils/item/LegacySwordBlocking.java" \
    "$R/injection/mixins/minecraft/item/MixinItem.java"
echo 'PASS: complete AndroidGuiCursor / LegacySwordBlocking / MixinItem and exact added cursor hook bodies javac against actual MC26.3 + VFP5.1.1 + SDL APIs'
"$J/javac" -proc:none -cp "$CP" -d "$W/audit" "$S/ApiAudit.java"
"$J/java" -cp "$W/audit:$CP" verify083.ApiAudit "$W/real-compile" "$MC_JAR"
# Compile only runtime hosts/tests now, NOT the production code again.
RCP="$W/real-compile:$VERIFY_LIBS/mixin.jar:$JOML"
"$J/javac" -proc:none -cp "$RCP" -d "$W/runtime" "$W/runtime-hosts/"*.java "$W/project/"*.java \
    "$S/InputRegression.java" "$S/NoViaRegression.java"
"$J/java" -Djoml.nounsafe=true -Dos.version=android -cp "$W/runtime:$RCP" verify083.InputRegression android
env -u ANDROID_ROOT -u POJAV_NATIVEDIR "$J/java" -Djoml.nounsafe=true -Dos.version=linux \
    -cp "$W/runtime:$RCP" verify083.InputRegression desktop
# Remove optional facade from a dedicated host copy to prove class-loading isolation.
cp -a "$W/runtime" "$W/no-via"
rm "$W/no-via/com/viaversion/viafabricplus/ViaFabricPlus.class"
"$J/java" -Djoml.nounsafe=true -Dos.version=android -cp "$W/no-via:$RCP" verify083.NoViaRegression
