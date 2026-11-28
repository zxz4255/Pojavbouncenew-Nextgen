# 094 focused verification

Source candidate only. No full Gradle build, game bootstrap/Mixin acceptance, on-device UI capture, real VIP account or Android audio/input acceptance is claimed.

Run from the project root after setting JDK25/Kotlin/MC26.3 manifest dependency paths as in the preceding harnesses:

```bash
source /path/to/verify-env/env.sh
bash scripts/verify094/compile-screen.sh
bash scripts/verify094/compile-glass.sh
python3 -m pip install moderngl numpy pillow
python3 scripts/verify094/shader-test.py
bash scripts/verify093/music.sh
python3 scripts/verify094/audit.py /path/to/Pojavbouncenew-Nextgen-093-MANUAL-BLOCK-MUSIC-CLEANUP.zip
# Optional final positional argument writes a CRC/byte-verified, root-relative ZIP.
```

The immutable 093 archive must be beside the project directory for shader baseline comparison. SHA256 is verified before use.

## What is actually exercised

- Complete production MusicScreen, MusicPanelLayout, SearchTextBuffer, LyricPanel, music backend and unchanged HUD sources compile against real MC26.3 classes. `screen-hosts.py` explicitly substitutes UI/font rendering, audio engine, cover cache, config, client/module facade and blur backdrop services. MC input/event/screen APIs are real. This catches screen/backend/API typing but does not exercise graphics, game scheduling, native IME or audio.
- PanelRegression runs production geometry/Hit/animation/search primitives. Transform and modal dispatch cases are mathematical/dispatch models, not native input delivery. 18,346 assertions.
- `compile-glass.sh` extends the prior real-API surface tests with the exact dedicated `musicPanelCard` body extracted from production. It does not compile the entire shared UI class. Explicit project hosts are in the generator scripts. 177,024 vertex and 27,006 HUD scope assertions.
- `shader-test.py` reuses only the EGL transport setup from verify089 and tests actual production GLSL/blur fragments. It compares HUD samples against immutable093, not a game capture. 95 assertions; 84 HUD samples, zero maximum RGBA error. `offscreen-quiet-gui.png` is a generic material swatch, not the new panel layout.
- The unchanged music backend is rechecked with the 093 music harness. Its local HTTP/decrypt fixtures are synthetic, not live service/VIP authorization proof.
- `audit.py` allows exactly four modified production files and one new layout source, plus 094 docs/tests. All other baseline bytes must remain equal, including HUD, shaders, shared material/render pipeline, Neo/manual sword/MotionBlur/native cursor work. The original UI painter bodies must be identical after removing the dedicated additive painter; ModuleMusic must differ only by removing panel style/layout config. Selected input/lifecycle method bodies must be identical.

Current result logs live alongside this README. Historical 078–093 scripts are preserved, not rewritten to hide new UI expectations; some historical baseline-specific audits are not applicable to 094.
