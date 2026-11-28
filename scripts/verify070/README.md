# 070 focused verification harness

This is an isolated compile/regression harness, **not the complete mod build**.
It compiles the changed FPS knife/ClickGUI code against the official Minecraft 26.3
API and uses deliberately small fixtures for unrelated LiquidBounce dependencies.
The fixtures are outside `src` and are never packaged into the mod jar.

- `stubs/`: module/config/event dependencies and unrelated animation integration.
- `ui-stubs/`: unrelated rendering/global/HUD/config dependencies. Public drawing
  signatures match the project API. `UiDraw.drawTextFieldContent` contains the real
  070 method body; the rest of UiDraw is stubbed, so this does NOT compile-verify the
  entire UiDraw implementation.
- `KnifeRegression.kt`: constructs every mesh, checks finite geometry/poses,
  grip-pivot scale/mirror invariance and idle inspect gating/cancellation.
- `UiRegression.kt`: exercises actual search/filter/sort logic with test modules,
  actual bilingual resources, and viewport-height boundaries. This does not open
  a native window, emulate touch, or test an IME service.

## Run

Provide JDK 25, Kotlin compiler 2.4.20, official Minecraft 26.3 `client.jar`, and a
jar directory containing the Minecraft libraries from its version manifest,
Fabric Loader, Sponge Mixin, MixinExtras common and ASM. No game binaries are
redistributed in this source archive.

```sh
JAVA_HOME=/path/to/jdk25 \
KOTLINC=/path/to/kotlinc/bin/kotlinc \
MC_JAR=/path/to/client.jar \
VERIFY_LIBS=/path/to/libs \
bash scripts/verify070/run.sh
```

For release validation, also run `bash gradlew build` using the complete project
classpath and launch Minecraft on the target device. The harness cannot validate
Mixin application, GPU output, native IME focus or Android gesture delivery.

## 071 compatibility

The UI portion now delegates to `scripts/verify071/run.sh` for the current persistent-layout code. The 070 UI fixtures remain as historical reference only.
