# verify084 — native-only Android GUI cursor

```sh
JAVA_HOME=/path/to/jdk25 \
MC_JAR=/path/to/official26.3-client.jar \
MC_LIBS=/path/to/official26.3-compile-libraries \
VERIFY_LIBS=/path/to/mixin-asm-jars \
BASELINE083=/path/to/Pojavbouncenew-Nextgen-083-ANDROID-CURSOR-LEGACY-BLOCK.zip \
  bash scripts/verify084/run.sh
```

Compile dependencies match083: official MC26.3 manifest libraries, Fabric sponge-mixin0.17.3+mixin.0.8.7 (`mixin.jar`), MixinExtras-common0.5.0, ASM/ASM-tree9.9. MC26.3 main artifact SHA256 is enforced; its source URL/checksums remain in unchanged verify083/source-lock.json. This cursor test requires neither Kotlin nor ViaFabricPlus.

- `prepare.py`: exact083 source ZIP hash; negative source control proves083 contained the removed bitmap/drawing API; current helper must have no software-pointer/rendering/coordinate-synthesis members. Extracts retained production hook bodies verbatim. Generates unrelated project-service fixtures for the real-MC compile phase.
- `run.sh`: compiles the complete production AndroidGuiCursor and exact hook bodies against REAL MC26.3/SDL/Mixin APIs. No game/SDL mocks in this compilation phase.
- `ApiAudit.java`: actual-game bytecode verifies3 retained injection signatures/callbacks and compiled-helper restrictions: no bitmap/extract/draw APIs, no synthetic input/remap/warp, native SDL call set limited toShowCursor/GetWindowRelativeMouseMode/SetWindowRelativeMouseMode. This is not full Mixin engine application.
- Runtime hosts reuse the unchanged083 fixture generator; only the ephemeral SDL host gains a native-request-refusal switch. Production code is NOT recompiled against fixtures. `CursorRegression.java` executes real-API-compiled bytes with real Mixin CallbackInfo. Android20036 assertions/2000 GUI-close-focus cycles and desktop4 assertions PASS. No-GUI/hidden-screen/background paths cannot mutate native state or pointer coordinates. A simulated native ShowCursor failure does not invoke software fallback.
- `audit-package.py`: strict083 baseline inclusion and exact2 modified files; protects every other baseline byte, including1.8 sword fix. Flat ZIP CRC and per-entry byte checks.

`results.txt` is the current run. Historical083 rendering tests/docs are immutable history and are superseded for the removed cursor rendering path; do not run them unchanged and claim they describe084. Existing sword/music/Regex source is untouched; these suites were not rerun this turn.

No full Gradle build, complete Mixin runtime, actual Android OS pointer/keyboard/mouse, real click/drag event delivery, backend native ShowCursor success or real server match verification. Runtime fixtures validate scope/state, not Android hardware.
