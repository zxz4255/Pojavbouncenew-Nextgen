# 095 targeted verification

Not a complete Gradle build, game/Mixin bootstrap, native IME/touch/audio or multiplayer inventory acceptance test.

## Reproduce

Use JDK25, Kotlin2.4.20 and MC26.3 manifest jars as in prior harnesses. Environment variables: JAVA_HOME, MC_JAR, MC_LIBS, VERIFY_LIBS (ASM jars included), KOTLIN_LIBS. Python shader tests require moderngl, numpy, pillow and EGL.

```bash
source /path/to/verify-env/env.sh
chmod +x scripts/verify093/compiler.sh
bash scripts/verify095/compile-lyrics.sh
bash scripts/verify095/compile-shadow.sh
bash scripts/verify095/compile-inv.sh
python3 scripts/verify095/shadow-test.py
bash scripts/verify093/music.sh
python3 scripts/verify095/analyze-inv-log.py /path/to/user/neo-invmanager-092.log
python3 scripts/verify095/audit.py /path/to/Pojavbouncenew-Nextgen-094-MUSIC-QUIET-PANEL.zip
# Optional final path writes a root-relative ZIP with CRC + all-entry byte verification.
```

## Exact coverage and substituted services

### Lyrics

`compile-lyrics.sh` extends the 094 hosted MusicScreen compile. Complete production rewritten ScreenLyrics, WorldLyrics, new LyricLayout and actual TtfFont are compiled with real Camera/Options/PoseStack/SubmitNodeStorage/VertexConsumer/render-type APIs and the backend, alongside unchanged panel/HUD sources. ModuleMusic's exact lyric enums/config groups/accessors/color helpers and exact Options construction are extracted by `lyric-hosts.py`. The enclosing module, configuration DSL, painter/typeface facade, audio engine, cache and event services are explicit hosts. This is not an entire ModuleMusic/UI compilation or actual render execution.

`LyricRegression.kt` executes real production lyric timing/wrap/fitBox/FrontLyricScene and actual JOML transforms: 75,816 assertions. The camera basis cases are mathematical tests, not game captures. Original panel primitive regression also passes 18,346 assertions. BMP atlas fallback behavior is documented in the release note; Unicode-safe wrapping does not imply complete non-BMP glyph rendering support.

### Opai

`compile-shadow.sh` compiles the exact changed shadow method, pipeline and shader registry entries plus complete actual vertex-format declarations against real MC26.3/RenderPearl/JOML. Queue and bounds extension services are hosts; VerticesSetupHandler is actual production. Temporary ASM overlays apply only fields already declared accessible by the project's existing access-widener. Production AW bytes remain unchanged. Does not compile the whole Opai module.

`shadow-test.py`: actual production shader pair in EGL, 421 assertions over 84 size/radius/scale combinations, fractional geometry included. Shader returns zero inside the shell, bounded black exterior alpha, finite support and monotonically decaying exterior profile. `opai-shadow-offscreen.png` composites this shader with a SOFTWARE shell for inspection; it is not a whole-widget or Minecraft screenshot.

### Neo

`compile-inv.sh` compiles complete real Value setter/trace, ToggleableValueGroup, ClientModule, Neo module/hotkeys/latch/diagnostics and inventory policies. Game/DSL/config/bind fixtures are the explicit 092 hosts extended only for copy/action and persistence counting. Historical lifecycle regression checks are preserved except its description-marker assertion follows the new 095 description; the actual diagnostic writer continues to retain its historical 092 label. 6,148 assertions pass; its historical output strings still say 092 and correctly disclaim device reproduction.

`InvRegression.kt`: 1,410 checks execute the real migration method and real latch. HOLD-release reproduces the captured behavior, TOGGLE survives release, repeats cannot double-toggle, migration preserves key/enabled state, stores once, and respects an explicit later HOLD choice. Bind/config adapters are fixtures, so this does not prove disk permissions or native input on a device.

`inv-log-analysis.json` is derived from the provided user log by `analyze-inv-log.py`. All 23 disable requests originate in NeoInvHotkeys.release under HOLD and commit false. First hold duration: 165ms. Original log is not packaged.

### Preservation

`music-results.txt`: unchanged 093 protocol/manager/Island/KGG/online-key fixture regressions rerun. No live VIP/audio/native decode proof.

`audit.py`: exact immutable 094 baseline hash, no deletions, exactly 8 modified production files, exactly 2 allowed new production files plus 095 docs/tests. Dedicated pipeline/registry additions are stripped and the rest must be byte-identical. Opai changes are restricted to shadow code; prior 094 panel, music HUD, global input and unrelated manual sword/Neo combat/MotionBlur/native Android cursor changes remain baseline bytes.

No benchmark, game-frame scheduling correctness or on-device visual quality guarantee is inferred from assertion counts.
