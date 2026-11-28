# 074 verification — NeoRaven Cinematic / Rain Reflections

## Run

From the project root, supply JDK 25, Kotlin compiler supporting JVM target 25, the official Minecraft **26.3** client JAR and its dependency JARs (including ASM 9.9, JOML 1.10.9, Fabric Loader 0.19.5, SLF4J, fastutil, Gson and the dependencies needed by verify070–073). Install `glslangValidator`, Python 3, NumPy and ModernGL with an EGL-capable OpenGL implementation. The recorded run used Mesa llvmpipe 25.0.7, OpenGL 4.5. No Minecraft JAR or dependency binaries are redistributed in this source archive.

```bash
JAVA_HOME=/path/to/jdk25 \
KOTLINC=/path/to/kotlinc \
MC_JAR=/path/to/official-26.3-client.jar \
VERIFY_LIBS=/path/to/dependency-jars \
JAVA_OPTS=-Xmx320m bash scripts/verify074/run.sh
```

`SKIP_PRIOR=1` skips only the older 070–073 regression chain. The delivered `results.txt` is from the complete run, exit status 0, including that chain. Compilation/deprecated-Unsafe warnings and the older intentional malformed-config warning are not game crashes.

## What ran successfully

1. SHA-256 check of all seven original source/shader files pinned to `b483ffc576e335e01f2914c920e88289ab090e66`, GPL attribution and EN/ZH JSON duplicate-key checks.
2. Token-level audit of **16 retained shader functions**, including all four effect main functions and the original ripple, puddle-noise, AO, bloom, trace and composite kernels. The test enumerates allowed substitutions: texture API/output names, modern projection/depth access and explicit mip lookup. Modern depth/height reconstruction itself is tested separately.
3. The **actual** two module sources, four render/state/integration sources, entire shader/pipeline/UBO/vertex-format registries and lazy target holder compiled against the real MC 26.3 renderpearl APIs. Application singleton, module/config/event APIs, fastutil iterator bridge and selected project utility functions are dependency fixtures under `stubs/`; this is not a whole-project compile. `ApplyAccess.java` creates a temporary three-class compile overlay using existing access-widener declarations for pipeline and vertex-format access. The MC JAR is unchanged. This does not exercise Loom's access-widener application.
4. CPU assertions: original eye-exposure defaults, asymmetric speeds, world snap, 0.2-second stall cap, 15–240 FPS invariance; 4,099 chunk/noise origin cases; refresh/movement/time rollback/world invalidation; **16,384** negative/custom-height round trips; **108** depth reconstruction cases at three FOVs; actual std140 sizes **128 and 256 bytes**.
5. `glslangValidator` syntax validation of all five GLSL 330 shaders.
6. **Real EGL/OpenGL shader execution** on software Mesa: compile/link all five port shaders and four source-derived upstream fixtures; synthetic 128×96 and 129×97 plane/wall images; conventional and reversed-depth reconstruction; dry-map/no-rain rejection; negative-height map; premultiplied blend preserving destination alpha. The upstream shaders are minimally adapted to the same GLSL test interface, not run inside the original Forge client.
7. Synthetic upstream max absolute floating-point channel differences:
   - Cinematic Lightweight: at most **0.00002134**.
   - Cinematic High, Bloom off: at most **0.00002867**.
   - Cinematic High, Bloom on: at most **0.00123895** (explicit mip chain versus driver-generated mipmaps; not bit-identical).
   - Rain trace: at most **0.000010**; composite: at most **0.000002**.
   These are limited synthetic images, NOT general error bounds or gameplay-quality measurements.
8. Isolated ripple-normal GLSL kernels are bit-identical to the pinned upstream at three time samples and produce changing wavefronts. **The synthetic plane/wall SSR image had zero time delta** because it did not guarantee a visible reflected hit; it is not evidence of animated reflected objects in Minecraft. The animation kernel is retained and independently exercised, but an in-game reflected-motion test is outstanding.
9. World hook source-order and actual target-method descriptor audit: Rain → world event/overlay flush → Cinematic; before hand/GUI. **Not** a compiled/applied GameRenderer Mixin test.
10. Entire existing 070–073 dependency-fixture/headless regression chain passed, including held/palm-front butterfly/inspect geometry, click input and ClickGUI persistence/localization.

## Still NOT verified

- Full Gradle/Loom build or all-project linkage.
- Game launch, Mixin local capture/application, live render graph, resource reload, toggle/disconnect GPU lifecycle, real-world biome/heightmap scan behavior or screenshots in either client.
- Android/Pojav/GL translator/mobile GPU behavior, performance/FPS, shader-pack interoperability or device-specific artifacts.
- All world geometries, surface types, cameras, window sizes, projection conventions and continuous animations/settings.
- Pixel-exact visual parity. Minecraft 1.8.9 and 26.3 have different lighting, materials, camera and render systems; explicit bloom mip generation introduces a documented numerical difference.

Do not report these fixtures as a successful full build, Minecraft test, Pojav test or proof of complete visual restoration. See `../../074-NEORAVEN-CINEMATIC-RAIN.md` for the feature/settings mapping and manual acceptance checklist.
