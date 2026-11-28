#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Compiles modified 099 LightPollutionGlass Kotlin surfaces + verbatim extracted UI control blocks
# against typed Minecraft/Blaze3D stubs and runs WhiteBloomRegression on the JVM.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KOTLINC_BIN="${KOTLINC_BIN:-$(command -v kotlinc || echo /tmp/kt/kotlinc/bin/kotlinc)}"
JAVA_BIN="${JAVA_BIN:-$(command -v java)}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/src"
python3 "$ROOT/scripts/verify099/glass-hosts.py" "$WORK/src" "$ROOT"

"$KOTLINC_BIN" \
  "$WORK/src"/*.kt \
  "$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/glass/LightPollutionGlassSurface.kt" \
  "$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/glass/ClickGuiGlassSurface.kt" \
  "$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/ui/common/UiPalette.kt" \
  "$ROOT/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/moderngui/ModernGuiSurface.kt" \
  "$ROOT/scripts/verify099/WhiteBloomRegression.kt" \
  -include-runtime -d "$WORK/test099.jar"

"$JAVA_BIN" -jar "$WORK/test099.jar" | tee "$ROOT/scripts/verify099/glass-results.txt"
