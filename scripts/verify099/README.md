# Verification 099 — Volumetric White-Bloom LightPollutionGlass

## Contents

- `compile-glass.sh`, `glass-hosts.py`, `WhiteBloomRegression.kt`, `glass-results.txt` — Compiles the modified Kotlin glass/palette/ModernGUI surfaces and verbatim extracted control blocks (`SwitchWidget`, `SingleSliderSettingNode`, `RangeSliderSettingNode`, `ClickGuiTabView`, `onyx/render/UI.kt`) with `kotlinc` and executes 44 JVM assertions.
- `shader-test.py`, `shader-report.json`, `shader-results.txt`, `offscreen-white-bloom-gui.png` — Executes the production GLSL 330 `liquid_glass.vsh`, `light_pollution_glass.fsh`, `glass_blur_h/v.fsh`, and `css_box.vsh/fsh` shaders on an offscreen EGL OpenGL 4.5 context (`moderngl`), verifying:
  - `0.000000` max RGBA pixel diff against 098 across `Profile.TARGET` (`1`), `Profile.COUNTER` (`2`), `Profile.SOFT` (`3`), and `GuiVariant.QUIET` (`094` Music main panel).
  - Smooth $C^\infty$ inward-to-outward luminance decay across capsule/panel boundaries (`0.6242 -> 0.5605 -> 0.3689 -> 0.3115 -> 0.2905 -> 0.2800`), frost-white chromaticity (`chroma_rgb_span = 0.00333`), and zero quad-edge jump (`0.000000`).
- `audit.py`, `scope-audit.json` — Strict 098 -> 099 byte-preservation and ZIP integrity verifier.

## Commands

```bash
./scripts/verify099/compile-glass.sh
python3 ./scripts/verify099/shader-test.py /path/to/Pojavbouncenew-Nextgen-098-ORIGINAL-AURA-CHESTS.zip
python3 ./scripts/verify099/audit.py /path/to/Pojavbouncenew-Nextgen-098-ORIGINAL-AURA-CHESTS.zip /path/to/Pojavbouncenew-Nextgen-099-LIGHT-POLLUTION-WHITE-BLOOM-GUI.zip
```
