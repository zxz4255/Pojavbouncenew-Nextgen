# 099 — Light Pollution Glass Volumetric Inward-to-Outward White Bloom (ClickGUI & Global ModernGUI)

## Summary

Upgrades `LightPollutionGlass` on **GUI surfaces** (`Profile.PANEL`, lane `0`) across both **ClickGUI** (`ModuleClickGui`, `Style = LightPollutionGlass`) and **Global ModernGUI** (`ModuleModernGui`, `Style = LightPollutionGlass`) to deliver an analytical **2D Gaussian volumetric inward-to-outward white bloom** frosted-glass aesthetic aligned with the target reference, while preserving:

- **089 HUD optical profiles** (`Profile.TARGET = 1`, `Profile.COUNTER = 2`, `Profile.SOFT = 3` — `TargetHUD`, `BlockCounter`, `MusicIsland`, Potions, Watermark) with **0.000000 max RGBA pixel diff**.
- **094 standalone Music main panel** (`UI.musicPanelCard` via `GuiVariant.QUIET`) with **0.000000 max RGBA pixel diff**.
- **Legacy `LiquidGlass` and `Standard` styles** completely untouched.

---

## Technical Architecture

### 1. Closed-Form 3-Scale 2D Gaussian Area-Convolution Shader (`light_pollution_glass.fsh`)
Instead of drawing a sharp neon border stroke around dark centers, `Profile.PANEL` (`profile < 0.5`) evaluates a closed-form 2D separable Gaussian convolution integral (`erfApprox` / `rectGaussianBloom`, $|\varepsilon| < 1.5 \times 10^{-7}$) across three spatial scales ($\sigma_{\text{tight}}, \sigma_{\text{mid}}, \sigma_{\text{wide}}$):

- **Interior Pass (`0 <= mode < 99.5`, $d \le 0$)**:
  - Samples the 4-pass separable Gaussian-blurred scene (`Sampler0`) with gentle edge-bezel refraction and creamy frost desaturation.
  - Evaluates `evalVolumetricWhiteBloom` inside the element:
    - `GuiVariant.BLOOM_CAPSULE` (`B = 242`): luminous milky-white glow strongest inside the active row/pill and tapering smoothly toward $d = 0$.
    - `GuiVariant.BLOOM_PANEL` (`B = 160`): smoky-neutral frosted interior for high text legibility with a soft inner-to-edge white frost mist (`24 px` ramp) and subtle top header glow.
- **Exterior Pass (`vMode < -0.001`, $d > 0$)**:
  - Evaluates the **exact same $C^\infty$ `evalVolumetricWhiteBloom` field** outside the rounded rectangle, complemented by `outside = smoothstep(-pixel * 0.5, pixel * 0.5, d)` (`mask + outside == 1.0`) so the white glow extends seamlessly from inside to outside with zero boundary crease, and fades cleanly to `0.0` inside the padded quad (`2.5 * spread + 4.0`).
- **Delicate Rim Whisper (`mode >= 99.5`)**:
  - Ultra-subtle directional frost edge (`0.13` panel / `0.045` capsule) without harsh outlines.
- **Quiet Variant Preservation (`GuiVariant.QUIET`, `B = 96` / shadow `B = 0`)**:
  - Preserves 094's standalone `UI.musicPanelCard` uniform blurred body and subtle black Gaussian drop shadow verbatim.

### 2. Native ClickGUI (`LightPollutionGlass`) Upgrades
- **Palette (`UiPalette.kt`)**: Replaces 090's pink `Color4b(244, 184, 198, 255)` accent override with frost-white `Color4b(244, 247, 255, 255)` and cool slate-white switch tracks + `#FFFFFF` thumbs.
- **Panels & Enabled/Hovered Module Rows (`ClickGuiTabView.kt`, `ClickGuiGlassSurface.kt`)**:
  - Clean `6 px` rounded frosted panels with soft header inner-to-outer white bloom and glowing white header divider.
  - Replaces 090's `2.5 px` pink left stripe on enabled modules and expanded settings wells with full-row `BLOOM_CAPSULE` frosted white bars (`radius = 4.5f`) and clean stripe-free recessed settings wells.
  - Muted smoky-frost disabled module labels (`white(0.54f)`) vs crisp `#FFFFFF` enabled/hovered labels.
  - Horizontal scissor padding (`scissorPadX = 26f`) on panel module lists, expanded settings, search results, and HUD catalog drawer (`18f`) so outward white bloom from active rows and right-edge controls is never vertically sliced while keeping exact vertical scroll clipping.
- **Settings Controls (`SettingWidgets.kt`)**:
  - Right-aligned `SwitchWidget` in `LightPollutionGlass` mode with outward white-glowing circular `#FFFFFF` thumb.
  - `SingleSliderSettingNode` & `RangeSliderSettingNode` upgraded in `LightPollutionGlass` mode to frosted white `3 px` tracks and glowing `#FFFFFF` `6x13 px` vertical rounded-pill handles.

### 3. Global ModernGUI (`ModernGuiSurface.kt`) Upgrades
- Replaces pink `0xFFF4B8C6` theme primary in `resolveTheme` with frost-white `0xFFF4F7FF`.
- Container/screen/menu cards (`drawElevatedCard`) emit `BLOOM_PANEL` volumetric white-bloom glass.
- Interactive buttons/tabs (`drawWidgetPill`) upgrade `selected || hoveredOrFocused` states to `BLOOM_CAPSULE` volumetric inner-to-outer white glow, keep normal buttons on `BLOOM_PANEL`, and set `suppressBloom = true` on `disabled` buttons.
- Hovered item slots (`drawSlotWell`) emit a soft outward white glow (`CssBox` analytical shadow).

---

## Modified Files (9)

1. `src/main/resources/resources/liquidbounce/shaders/clickgui/light_pollution_glass.fsh`
2. `src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/glass/LightPollutionGlassSurface.kt`
3. `src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/glass/ClickGuiGlassSurface.kt`
4. `src/main/kotlin/net/ccbluex/liquidbounce/ui/common/UiPalette.kt`
5. `src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/ClickGuiTabView.kt`
6. `src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/HudEditorTabView.kt`
7. `src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/setting/SettingWidgets.kt`
8. `src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/moderngui/ModernGuiSurface.kt`
9. `src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/misc/music/onyx/render/UI.kt`

## Verification (`scripts/verify099/`)

- `scripts/verify099/compile-glass.sh` — Compiles production `LightPollutionGlassSurface.kt`, `ClickGuiGlassSurface.kt`, `UiPalette.kt`, `ModernGuiSurface.kt`, and verbatim extracted control/panel blocks from `SettingWidgets.kt`, `ClickGuiTabView.kt`, and `onyx/render/UI.kt` with `kotlinc` and executes 44 JVM assertions (`WhiteBloomRegression.kt`).
- `scripts/verify099/shader-test.py` — Compiles and executes the production GLSL 330 shaders on a standalone EGL OpenGL 4.5 context, proving `0.000000` max RGBA diff on `TARGET`, `COUNTER`, `SOFT`, and `QUIET`, monotonic inward-to-outward white bloom decay, frost-white neutrality, and `0.000000` padded-quad seam.
- `scripts/verify099/audit.py` — Verifies baseline 098 SHA-256, strict 9-file modification scope, zero deletions, byte-for-byte preservation of all 2,856 untouched files, and archive integrity.
