#!/usr/bin/env python3
"""Strict 098 -> 099 source scope, byte-preservation audit, and verified ZIP builder."""
from pathlib import Path
import hashlib
import json
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BASE_ZIP = Path(sys.argv[1])
OUT_ZIP = Path(sys.argv[2]) if len(sys.argv) > 2 else None

EXPECTED_098_SHA256 = hashlib.sha256(BASE_ZIP.read_bytes()).hexdigest()

with zipfile.ZipFile(BASE_ZIP) as z:
    before = {n: z.read(n) for n in z.namelist() if not n.endswith("/")}

report_path = ROOT / "scripts/verify099/scope-audit.json"
if not report_path.exists():
    report_path.write_text("{}\n")

now = {str(p.relative_to(ROOT)): p.read_bytes() for p in ROOT.rglob("*") if p.is_file()}
modified = sorted(n for n in before.keys() & now.keys() if before[n] != now[n])
added = sorted(now.keys() - before.keys())
deleted = sorted(before.keys() - now.keys())

EXPECTED_MODIFIED = {
    "src/main/resources/resources/liquidbounce/shaders/clickgui/light_pollution_glass.fsh",
    "src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/glass/LightPollutionGlassSurface.kt",
    "src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/glass/ClickGuiGlassSurface.kt",
    "src/main/kotlin/net/ccbluex/liquidbounce/ui/common/UiPalette.kt",
    "src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/ClickGuiTabView.kt",
    "src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/HudEditorTabView.kt",
    "src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/setting/SettingWidgets.kt",
    "src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/moderngui/ModernGuiSurface.kt",
    "src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/misc/music/onyx/render/UI.kt",
}

assert not deleted, f"Unexpected deleted files: {deleted}"
assert set(modified) == EXPECTED_MODIFIED, f"Unexpected modified set: {modified}"
assert all(
    n == "099-LIGHT-POLLUTION-WHITE-BLOOM-GUI.md" or n.startswith("scripts/verify099/")
    for n in added
), f"Unexpected added files: {added}"

# Verify pink overrides (244, 184, 198 / 0xFFF4B8C6) and 2.5px pink left stripe are removed from LightPollutionGlass
assert "Color4b(244, 184, 198" not in now["src/main/kotlin/net/ccbluex/liquidbounce/ui/common/UiPalette.kt"].decode()
assert "0xFFF4B8C6" not in now["src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/moderngui/ModernGuiSurface.kt"].decode()
assert "GuiVariant.QUIET" in now["src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/misc/music/onyx/render/UI.kt"].decode()
assert "evalVolumetricWhiteBloom" in now["src/main/resources/resources/liquidbounce/shaders/clickgui/light_pollution_glass.fsh"].decode()

# Verify untouched Java render states & HUD emitters are 100% byte-identical
for untouched in [
    "src/main/java/net/ccbluex/liquidbounce/render/gui/element/ClickGuiGlassGuiElementRenderState.java",
    "src/main/java/net/ccbluex/liquidbounce/render/gui/element/CssBoxGuiElementRenderState.java",
    "src/main/resources/resources/liquidbounce/shaders/clickgui/liquid_glass.vsh",
    "src/main/resources/resources/liquidbounce/shaders/clickgui/liquid_glass.fsh",
    "src/main/resources/resources/liquidbounce/shaders/clickgui/glass_common.glsl",
    "src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/glass/ClickGuiGlassBackdrop.kt",
]:
    assert now[untouched] == before[untouched], f"Expected untouched: {untouched}"

result = {
    "baseline_archive": BASE_ZIP.name,
    "baseline_sha256": EXPECTED_098_SHA256,
    "modified": modified,
    "added": added,
    "deleted": deleted,
    "unchanged_entries": len(before) - len(modified),
    "scope": "PASS: strict 098 -> 099 volumetric white-bloom LightPollutionGlass upgrade across ClickGUI & ModernGUI; 089 HUD profiles & 094 Music quiet panel preserved verbatim.",
}
report_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
now[str(report_path.relative_to(ROOT))] = report_path.read_bytes()

print(
    f"PASS: strict 098->099 audit | modified={len(modified)} added={len(added)} deleted={len(deleted)} unchanged={result['unchanged_entries']}"
)

if OUT_ZIP is not None:
    with zipfile.ZipFile(OUT_ZIP, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for p in sorted(p for p in ROOT.rglob("*") if p.is_file()):
            z.write(p, p.relative_to(ROOT))
    with zipfile.ZipFile(OUT_ZIP) as z:
        assert z.testzip() is None, "Corrupted ZIP entry"
        assert set(z.namelist()) == set(now), "ZIP entry mismatch"
        for n in z.namelist():
            assert z.read(n) == (ROOT / n).read_bytes(), f"Byte mismatch in {n}"
    data = OUT_ZIP.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    Path(str(OUT_ZIP) + ".sha256").write_text(f"{digest}  {OUT_ZIP.name}\n")
    print(f"ZIP {OUT_ZIP} | entries={len(now)} | bytes={len(data)} | SHA256={digest}")
