# 089 reproducible, bounded verification

## Prerequisites

Use JDK25, Kotlin2.4.20 and the actual MC26.3 manifest libraries as documented in `../verify085/README.md`. `VERIFY_LIBS` includes real VFP5.1.1/nested JARs plus ASM/ASM-tree; no downloaded dependencies are bundled. MC/VFP hashes are locked by verify085/source-lock.json and rechecked by the runner. Python shader tests require `moderngl numpy pillow` and EGL. Optional source remeasurement additionally requires `scipy` and the user's six ORIGINAL JPEGs with their locked hashes.

```sh
export JAVA_HOME=/path/to/jdk25
export MC_JAR=/path/to/official/mc26.3.jar
export MC_LIBS=/path/to/manifest-libraries
export VERIFY_LIBS=/path/to/vfp-nested-and-asm-jars
export KOTLIN_LIBS=/path/to/kotlin-compiler-jars
export BASELINE088=/path/to/Pojavbouncenew-Nextgen-088-CS2-MVP-BANNER-CANDIDATE.zip
bash scripts/verify089/run.sh
# Optional: independently reproduce the recorded source-coordinate extraction,
# but note these same points were also used in fitting, NOT held out.
python3 scripts/verify089/measure-references.py /path/to/original/uploads
python3 scripts/verify089/build-review.py /path/to/original/uploads
```

The included review HTML is already self-contained. It embeds source crops for reference only, not runtime textures. The four `offscreen-*.png` files are real production-shader EGL samples on synthetic/constant backgrounds, explicitly NOT Minecraft screenshots.

## Exact scope

`compile.sh` first uses the historical verify086 project-host generator, then the additional explicit hosts in `hosts.py`:

- Both entire production Java surface records compile against real MC/RenderPearl/JOML/VertexConsumer types.
- Three entire production Kotlin surface emitters compile: LightPollutionGlassSurface, ClickGuiGlassSurface and ModernGuiSurface.
- Exact production Onyx card-hook and style-enum bodies are compiled; the enclosing UI/module classes are project hosts.
- Exact production `withClientHudScope` and the full `tryUpgradeClientHudBox` method, the new HUD-manager routing/render-call excerpt and the TargetHud call are compiled against these surface APIs. **The enclosing interceptor/HUD manager/TargetHud classes are NOT compiled in full.**
- Themes, configuration, project draw/pooling facades, backdrop requests, pipeline factory return values and unrelated enclosing services are explicitly substituted project hosts. MC and its API classes are not replaced for compilation.
- Three pre-existing GUI access-widener declarations are applied to real MC classes in a temporary compile overlay, as in086. The overlay is never shipped.
- VertexRegression invokes the real production Java emitter with a dynamic proxy implementing real MC VertexConsumer. 177,024 assertions check transport, old-style vertex preservation, four profiles and shadow-independent external arcs. No renderer starts.
- ScopeRegression executes exact production scope-method/routing excerpts with project config/component hosts. 27,006 assertions check selection, nested/exception restoration and disabled paths; no game session starts.

`shader-test.py` expands the actual shared production SDF include and substitutes uniform declarations only for MC transform blocks. Actual old/new GLSL compile/link on EGL. 2,625 assertions exercise the four new profile lanes, finite ranges/opacity/silhouettes, zero-lens reflection and detached arcs. Thirteen source-traced ridge/seam positions are compared in original JPEG pixel coordinates; maximum position error on this run is 2 pixels. Those points participated in fitting; this is NOT an independent test, NOT whole-image RGB pixel parity and NOT a tolerance claim for an entire UI. Body widths/colors and unobserved areas remain approximate.

`mvp-regression.txt` is a real rerun of the existing088 bounded compile/API/state-machine verification on this source tree. Its 51,039 pure-logic assertions and58 real API checks do not demonstrate server kill attribution reliability or CS2 visual parity.

`check-wiring.py` hashes the immutable088 archive and enforces exactly9 production-file modifications with all2691 other baseline files byte-identical and no deletions, including historical verification evidence, native cursor/Mixins, MVP/registration/locales, both087 aura fixes, knives, old shaders/backdrop/pipelines. Moonlight/Neumorphism stay absent.

`audit-package.py` requires that preservation audit, packs the complete immutable baseline plus scoped new docs/evidence, checks every stored byte/CRC/path against working sources, and emits ZIP SHA256/MD5/Dropbox content hash outside the archive.

## Not established

No complete Gradle build, Mixin bootstrap, actual game framebuffer extraction/scheduling, Android GPU/Pojav session, same-scene screenshot comparison, original-client shader/source port, font/layout/animation reproduction or whole-interface pixel-level acceptance. Previous Gradle failures are historical records, not a newly successful build. The no-framebuffer fallback remains intentionally non-optical.

The historical086 compile fixture targets the old record constructor. On the089 source tree use this089 runner, not the historical086 compile script in isolation. Historical logs are preserved, not rewritten as current proof.
