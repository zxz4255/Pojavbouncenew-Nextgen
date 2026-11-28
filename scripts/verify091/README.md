# 091 bounded verification (not full Gradle/game acceptance)

Prerequisites: JDK25, Kotlin compiler 2.4.20 jars, official MC26.3 and manifest library jars; environment variables as in scripts/verify090/compile.sh. EGL tests need `moderngl numpy`.

Run from repository root after sourcing the local verification environment:

```sh
bash scripts/verify091/compile-inventory.sh
bash scripts/verify091/compile-render.sh
bash scripts/verify091/compile-activation.sh
bash scripts/verify091/run-pure.sh
python3 scripts/verify091/shader-test.py
```

- Inventory: complete production NeoInvManager, hotkeys/latch, TS organizer, shared native session/safety policy, Neo planners/item adapters compile against real MC26.3. Config/event, bind, old helpers and other module hosts come from 076 plus explicit additions in hosts.py. **This fixture does not instantiate the complete client activation system.** The unchanged 076 regression tests rerun actual pure planner/policy code, with pinned upstream Java score oracle.
- Render: complete new module, renderer/history and complete production uniform enum/target holder compile against real MC26.3. Exact new pipeline, shader ID line, pass extensions, std140 calculator/writer are extracted. The pipeline factory uses public `RenderPipeline.builder()` instead of the access-widened constructor, and registry/shader lookup/config/event/clear-target behavior are hosts. Complete ClientRenderPipelines/ClientShaders/ModuleManager/MixinGameRenderer are **not compiled**. The two added Java→Kotlin hook calls compile independently.
- Activation: extracts exact production ClientModule.onToggled and activationFeedback bodies into a test class; injected event/translation/notification/linkage failures do not veto requested state. Real lifecycle exceptions and fatal VM errors propagate; `disableActivation`, menu and config-loading rules remain. This is not the full ClientModule/Value hierarchy running in Minecraft.
- Pure: actual NeoInvBindLatch + MotionBlurHistory with **no Minecraft or fixture classes on runtime classpath**. Tests duplicate press/repeat/release, toggles both ways, HOLD/SMART, frame-time weighting and history invalidation.
- EGL: actual accumulation fragment and official screenquad vertex (RenderPearl `gl_VertexIndex` mapped to OpenGL `gl_VertexID`), 16-byte std140 UBO, real two-target ping-pong, four dimensions and 60 frames each; 480 assertions, including weights above .95 at high FPS. Float textures are used for a precise oracle; runtime RGBA8 quantization and driver performance are not measured.
- audit-package.py: exact 090 baseline SHA, fixed production-change allowlist, preserved inventory/cursor/GUI/HUD/knife/MVP sources, registration/order/default/safety checks, valid scoped i18n, ZIP CRC and per-entry byte equality.

The results do **not** identify the user's original exception or prove device toggle behavior, Mixin application, full Gradle packaging, multiplayer item acceptance, GUI screenshot parity, Android GPU compatibility or FPS. No same-failure Gradle build was repeated.
