# 073 held-inspection regression

```sh
JAVA_HOME=/path/to/jdk25 \
KOTLINC=/path/to/kotlinc/bin/kotlinc \
MC_JAR=/path/to/official-mc26.3-client.jar \
VERIFY_LIBS=/path/to/dependency-jars \
bash scripts/verify073/run.sh
```

Requirements and dependency fixtures are as described in `../verify070/README.md`. This script defaults JAVA_OPTS to `-Xmx320m` and delegates to verify072 -> verify070 -> verify071 after its new tests. Third-party/game binaries are not redistributed.

The production FPS sources are compiled against the official Minecraft API. `HeldInspectRegression.kt` invokes inspection timeline samples deterministically and tests:

- Only toss choreography uses release/airborne offsets.
- Palm-front spins keep the entire mesh beyond the fingertip/sleeve end plane.
- The butterfly safe-handle socket lies inside the fist and stays attached to the hand.
- Butterfly moving-handle and main-blade triangles do not intersect the holding arm's sleeve-included cuboid, via triangle/AABB SAT in inverse arm-model space. Held handle overlap is intentional and excluded from collision rejection.
- Blade-body vertices stay outside the holding arm during regrip transitions.

The tests sample 101 phases, both arm models/sides, 3 scales and 3 thickness settings at default 22-degree tilt. Full-contact butterfly tests run after regrip is complete; not all handle transitions and arbitrary settings are proven collision-free. These are headless geometry tests, not a window/GPU/game/Mixin-application/Pojav/Android test or a complete Gradle build.

The verify072 fixture now exempts deliberate 073 palm-front and safe-handle attachment transitions from its old fixed-palm socket invariant; this suite tests their replacement invariants independently. Historical release result files remain unchanged; `results.txt` here is the current complete chain's output.
