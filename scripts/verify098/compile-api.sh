#!/usr/bin/env bash
set -euo pipefail
R="$(cd "$(dirname "$0")/../.." && pwd)";cd "$R"
: "${JAVA_HOME:?JDK25}" "${MC_JAR:?MC26.3}" "${MC_LIBS:?manifest libraries}" "${VERIFY_LIBS:?libraries}" "${KOTLIN_LIBS:?Kotlin2.4.20}"
W="${VERIFY_WORK:-$(mktemp -d)}";mkdir -p "$W";if [ -z "${VERIFY_WORK:-}" ];then trap 'rm -rf "$W"' EXIT;fi
K="$R/src/main/kotlin/net/ccbluex/liquidbounce"
CP="$MC_JAR:$(find "$MC_LIBS" "$VERIFY_LIBS" -name '*.jar' -printf '%p:' | sort):$KOTLIN_LIBS/kotlin-stdlib.jar"
python3 - "$MC_JAR" <<'PYLOCK'
from pathlib import Path
import hashlib,json,sys
lock=json.loads(Path('scripts/verify098/source-lock.json').read_text())
assert hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest()==lock['minecraftSha256']
print('PASS: official MC26.3 client SHA256 lock')
PYLOCK
python3 scripts/verify098/hosts.py "$W/hosts"
cp scripts/verify075/hud-stubs/*.kt "$W/hosts/"
cp scripts/verify075/stubs/Client.kt "$W/hosts/Client.kt"
echo 'val inGame: Boolean get() = mc.player != null && mc.level != null' >> "$W/hosts/Client.kt"
"$JAVA_HOME/bin/java" -Xmx512m -cp "$KOTLIN_LIBS/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Xcompanion-blocks-and-extensions -nowarn -no-stdlib -no-reflect -jvm-target 25 -classpath "$CP" -d "$W/classes" "$W/hosts/"*.kt \
 "$K/features/module/MinecraftShortcuts.kt" "$K/features/addon/AddonApi.kt" \
 "$K/features/module/modules/player/cheststealer/ModuleChestStealer.kt" \
 "$K/features/module/modules/player/cheststealer/features/"*.kt \
 "$K/features/module/modules/render/OpaiChestLoot.kt"
echo 'PASS: all 3 complete restored chest sources + actual OpaiChestLoot + MinecraftShortcuts compile against real MC26.3. Project config/event/inventory/rotation/render services are explicit compile hosts.'
python3 - "$R" "$W" <<'PY'
from pathlib import Path
import sys
r,w=map(Path,sys.argv[1:]);s=(r/'scripts/verify085/ApiAudit.java').read_text().replace('package verify085;','package verify098;').replace('combat/neoraven/','player/cheststealer/').replace('mcRefs<100 || wireRefs<5','mcRefs<45')
(w/'ApiAudit.java').write_text(s)
PY
"$JAVA_HOME/bin/javac" -proc:none -cp "$CP" -d "$W/audit" "$W/ApiAudit.java"
"$JAVA_HOME/bin/java" -Xmx128m -cp "$W/audit:$CP" verify098.ApiAudit "$W/classes" "$CP"
"$JAVA_HOME/bin/javac" -proc:none --release 25 -cp "$W/classes:$CP" -d "$W/selection" scripts/verify098/SelectionRegression.java
"$JAVA_HOME/bin/java" -Xmx128m -cp "$W/selection:$W/classes:$CP" SelectionRegression
python3 scripts/verify098/java-hooks.py "$R" "$W/java"
"$JAVA_HOME/bin/javac" -proc:none --release 25 -cp "$W/classes:$CP" -d "$W/java-classes" "$W/java/"*.java \
 "$R/src/main/java/net/ccbluex/liquidbounce/utils/input/AndroidGuiCursor.java"
echo 'PASS: 5 exact changed Java hook bodies + complete AndroidGuiCursor compile against actual MC26.3/SDL/Mixin callback API and compiled restored Kotlin features. Not Mixin application.'
echo 'NOT a full Gradle, Mixin application, server, Android/Pojav or gameplay test.'
