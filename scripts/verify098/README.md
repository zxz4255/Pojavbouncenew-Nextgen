# 098 verification

Source-only release; no full Gradle build or applied-Mixin/game/server/Pojav claim.

Prerequisites: JDK25, Kotlin2.4.20 compiler distribution, official MC26.3 client jar and manifest libraries, VFP5.1.1 dependencies/ASM for inherited regressions, Fabric sponge-mixin callback classes for Java compile checks. The inherited `prepare089-env.py` was used outside the deliverable to reconstruct the local test environment. Export `JAVA_HOME`, `MC_JAR`, `MC_LIBS`, `VERIFY_LIBS`, `KOTLIN_LIBS` as in previous verifications. Kotlin companion blocks flag matches build.gradle.kts.

Run from project root:

```bash
bash scripts/verify098/compile-api.sh
bash scripts/verify096/compile-api.sh
bash scripts/verify096/runtime.sh
bash scripts/verify097/compile-api.sh
bash scripts/verify097/runtime.sh
bash scripts/verify093/manual.sh
python3 scripts/verify098/audit.py /path/to/Pojavbouncenew-Nextgen-097-MVP-ENTITY-KILLS.zip
# Optional second argument packages and re-verifies all ZIP bytes:
python3 scripts/verify098/audit.py /path/to/097.zip /path/outside/project/098.zip
```

`hosts.py` explicitly supplies project config/event/inventory/rotation/render facades. No Minecraft classes are stubbed for `compile-api.sh`; inherited lifecycle/runtime tests clearly identify their additional models. The Kotlin originals are compiled whole, not translated into an alternate chest engine.

`SelectionRegression.java` reflects original nested Index/Distance/Random process methods without starting the game. Configuration defaults are compile hosts; its slot host mirrors the actual squared row/column distance formula. It checks permutations, default start stability and nearest-next ordering across 6000 generated lists. It does NOT execute inventory actions, components, rotations, timers, packets or server acceptance.

`java-hooks.py` extracts five exact changed Java method bodies; the complete production AndroidGuiCursor is compiled separately. Project bootstrap, NoGui and ModernGUI are explicit hosts; restored FeatureSilentScreen/OpaiChestLoot classes are the compiled production versions. This catches Java/Kotlin member names and real MC/SDL callback types, not Mixin target application.

Logs:
- `api-results.txt`: complete restored chest/observer compile, real member audit, 2,140,019 selection assertions, five changed Java hook bodies + whole Android cursor compile.
- `inv-regression.txt`: 096 actual lifecycle/Neo inventory module/API compile, 6148 lifecycle/trace assertions + 1410 migration assertions.
- `inv-runtime.txt`: 42,783 safety/policy assertions, 112 actual module/transport hosted runtime cases and inherited allocation/conservation regressions.
- `mvp-regression.txt`: 097 real API/state checks, 51,039 state assertions, 57 real MC/VFP member references.
- `mvp-runtime.txt`: 41 complete actual MVP adapter assertions with modeled entities/DSL/render/network.
- `manual-regression.txt`: 77,850 manual blocking state assertions; 24,582 input hook assertions; broken-input negative control.
- `scope-audit.json`: strict modified/deleted/added lists, fixed baseline and upstream commit, unchanged count.
- `source-lock.json`: fixed authentic upstream reference hashes + real MC jar lock.

Older aura/chest test entry points under verify074/075/085/093 target now-deleted variants and are historical, not 098 checks. Original licenses/reference records are retained, not compiled or registered.
