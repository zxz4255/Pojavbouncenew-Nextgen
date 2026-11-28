#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${JAVA_HOME:?JDK25}" "${VERIFY_LIBS:?Dependency jars}"
KOTLINC="${KOTLINC:-kotlinc}"
export JAVA_OPTS="${JAVA_OPTS:--Xmx320m}"
CP="$(printf '%s:' "$VERIFY_LIBS"/*.jar)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
R="$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/misc/music"
J="$ROOT/src/main/java/net/ccbluex/liquidbounce/utils/audio/parser"
S="$ROOT/scripts/verify079"
STUBS="$ROOT/scripts/verify078/stubs"
node "$ROOT/scripts/verify081/reference-request.cjs"
python3 "$ROOT/scripts/verify081/source-check.py"
"$JAVA_HOME/bin/javac" -d "$WORK/classes" "$J/PcmAudioParser.java" "$J/MusicAudioParserFactory.java" "$J/KggDecryptor.java" "$STUBS/"*.java
"$JAVA_HOME/bin/javac" -cp "$WORK/classes:$CP" -d "$WORK/classes" "$S/KggCipherRegression.java"
"$JAVA_HOME/bin/java" -Xmx256m -Dmusic.test.root="$WORK/cipher-root" -cp "$WORK/classes:$CP" net.ccbluex.liquidbounce.utils.audio.parser.KggCipherRegression "$ROOT"
python3 - "$R" "$WORK/ViperSettings.kt" <<'PY'
import pathlib,sys
s=(pathlib.Path(sys.argv[1])/'MusicViper.kt').read_text()
s=s[s.index('internal data class ViperSettings('):s.index('/** PCM bridge')]
pathlib.Path(sys.argv[2]).write_text('package net.ccbluex.liquidbounce.features.module.modules.misc.music\n'+s)
PY
FILES=()
for f in MusicManager KugouClient KugouPlayback MusicModels LocalMusicLibrary NeteaseClient NeteaseHttp NeteaseCrypto BilibiliClient MusicCrossSource LyricsParser MusicEqualizer MusicQr QrEncoder MusicIsland KugouLocalFiles KugouOnlineKeys; do FILES+=("$R/$f.kt"); done
"$KOTLINC" -jvm-target 25 -cp "$WORK/classes:$CP" -include-runtime -d "$WORK/tests.jar" "${FILES[@]}" "$WORK/ViperSettings.kt" "$R/onyx/hud/Island.kt" "$R/onyx/render/Anim.kt" "$R/onyx/render/Ease.kt" "$R/onyx/material/Material.kt" "$R/onyx/material/Scheme.kt" "$STUBS/"*.kt "$S/ProtocolRegression.kt" "$S/ManagerRegression.kt" "$S/IslandRegression.kt" "$S/KggIntegrationRegression.kt" "$ROOT/scripts/verify081/OnlineKeyRegression.kt"
"$JAVA_HOME/bin/java" -Xmx256m -Dmusic.test.root="$WORK/music-root" -cp "$WORK/tests.jar:$WORK/classes:$CP" net.ccbluex.liquidbounce.features.module.modules.misc.music.ProtocolRegressionKt
"$JAVA_HOME/bin/java" -Xmx256m -Dmusic.test.root="$WORK/music-root" -cp "$WORK/tests.jar:$WORK/classes:$CP" net.ccbluex.liquidbounce.features.module.modules.misc.music.ManagerRegressionKt
"$JAVA_HOME/bin/java" -Xmx256m -Dmusic.test.root="$WORK/island-root" -cp "$WORK/tests.jar:$WORK/classes:$CP" net.ccbluex.liquidbounce.features.module.modules.misc.music.IslandRegressionKt
"$JAVA_HOME/bin/java" -Xmx256m -Dmusic.test.root="$WORK/kgg-root" -Dmusic.test.project="$ROOT" -cp "$WORK/tests.jar:$WORK/classes:$CP" net.ccbluex.liquidbounce.features.module.modules.misc.music.KggIntegrationRegressionKt
python3 "$S/check-ui-wiring.py"

"$JAVA_HOME/bin/java" -Xmx256m -Dmusic.test.root="$WORK/online-key-root" -Dmusic.test.project="$ROOT" -cp "$WORK/tests.jar:$WORK/classes:$CP" net.ccbluex.liquidbounce.features.module.modules.misc.music.OnlineKeyRegressionKt
