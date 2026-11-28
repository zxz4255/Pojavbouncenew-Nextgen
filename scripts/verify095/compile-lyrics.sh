#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)";W=$(mktemp -d);trap 'rm -rf "$W"' EXIT
R="$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/misc/music"
J="$ROOT/src/main/java/net/ccbluex/liquidbounce/utils/audio/parser"
CP="$MC_JAR:$(find "$MC_LIBS" "$VERIFY_LIBS" -name '*.jar' -printf '%p:' | sort):$KOTLIN_LIBS/kotlin-stdlib.jar:$KOTLIN_LIBS/kotlinx-coroutines-core-jvm.jar"
python3 "$ROOT/scripts/verify094/screen-hosts.py" "$ROOT" "$W/hosts"
python3 "$ROOT/scripts/verify095/lyric-hosts.py" "$ROOT" "$W/hosts"
"$JAVA_HOME/bin/javac" -proc:none -d "$W/classes" "$J/"{PcmAudioParser,MusicAudioParserFactory,KggDecryptor}.java "$ROOT/scripts/verify078/stubs/"*.java "$ROOT/src/main/java/com/muoniumplayer/core/jdsp/JdspReverb.java"
FILES=()
for f in MusicScreen MusicPanelLayout SearchTextBuffer LyricPanel ScreenLyrics LyricLayout MusicManager KugouClient KugouPlayback MusicModels LocalMusicLibrary NeteaseClient NeteaseHttp NeteaseCrypto BilibiliClient MusicCrossSource LyricsParser MusicEqualizer MusicQr QrEncoder MusicIsland KugouLocalFiles KugouOnlineKeys;do FILES+=("$R/$f.kt");done
"$ROOT/scripts/verify093/compiler.sh" -nowarn -jvm-target 25 -cp "$W/classes:$CP" -d "$W/classes" "${FILES[@]}" "$W/hosts/"*.kt \
 "$R/worldlyrics/WorldLyrics.kt" "$R/onyx/render/TtfFont.kt" "$R/onyx/hud/Island.kt" "$R/onyx/render/"{Anim,Spring,Ease}.kt "$R/onyx/material/"{Material,Scheme,Theme}.kt "$ROOT/scripts/verify094/PanelRegression.kt" "$ROOT/scripts/verify095/LyricRegression.kt"
printf '%s\n' 'PASS: complete production MusicScreen, layout, search/IME buffer, in-panel lyric view, music backend and unchanged HUD classes compile against actual MC26.3. Complete rewritten 2D/3D overlays, TtfFont and exact lyric setting/color/Options excerpts also compile. Audio/painter/cache/config/module facades are explicit hosts; not full mod build or UI rendering.'

"$JAVA_HOME/bin/java" -Xmx256m -cp "$W/classes:$CP" net.ccbluex.liquidbounce.features.module.modules.misc.music.PanelRegressionKt

"$JAVA_HOME/bin/java" -Xmx256m -cp "$W/classes:$CP" net.ccbluex.liquidbounce.features.module.modules.misc.music.LyricRegressionKt
