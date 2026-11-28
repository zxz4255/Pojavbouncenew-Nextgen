# 077 focused knife regression

This is a headless API/fixture compile and sampled geometry/state test, not a full Gradle build or game validation.

```bash
JAVA_HOME=/path/to/jdk25 \
KOTLINC=/path/to/kotlinc/bin/kotlinc \
MC_JAR=/path/to/official-minecraft-26.3-client.jar \
VERIFY_LIBS=/path/to/dependency-jars \
JAVA_OPTS=-Xmx320m bash scripts/verify077/run.sh
python3 scripts/verify077/source-audit.py
```

The runner compiles **all current fpsknife/*.kt production files**, including ModuleFpsKnife and skins, against the official client API and dependency jars. Project event/config/host facilities are provided by existing verify070 fixtures. It does not prove full project linkage, runtime resource creation, applied Mixins, renderer submission order or GPU output. Run sequentially on memory-constrained machines.

## Current assertions

- 13 models × 5 normalized action cases × 241 phases × 2 arm sides × 2 skin shapes = 62,660 authored poses.
- 13 models × 3 guard styles × 2 sides × 2 skin shapes × 1,440 successive 120Hz frames = 224,640 state-machine poses.
- Total 287,300 poses, 43,065,648 tested triangle/arm pairs, zero sampled intersections.
- The live streams include draw, inspect/repeated inspect, attack interruption and return, light/heavy/block-hit attacks, walk/sprint, grounded/airborne, busy state and equipment change.
- Exact triangle/AABB separating-axis tests after inverse transforming the knife into the actual canonical holding-arm/sleeve space; checks both triangles of each tagged blade quad. The same render-local flight correction and support-arm solver used in production are applied.
- Moving butterfly bite-handle triangles are additionally tested once the safe-handle regrip is fully established.
- Support hand bounding boxes must be separated from both the entire articulated knife box and holding arm box after the production correction. A depth correction may not move the sleeve beyond the solver's camera limit.
- Closed folder blades with fold >=170 degrees intentionally occupy the handle cavity; they are excluded from exposed-blade vs palm testing. Their maximum axial length is checked against the handle length. This does NOT establish a volumetric fit of every folded blade point inside the handle.
- Handles/collars in an ordinary closed fist deliberately overlap the native solid block hand proxy; this is not a hand with an actual handle-shaped cavity. There is no finger rig and no hand self-collision proof.
- Repeated inspect does not reset phase; interruption queues an attack and enters RECOVER; move/busy/block/air cancellation returns before idle. Switching models with draw disabled clears the old action.
- 30/60/120/240 Hz duration checks cover the Talon inspect timeline only, not every possible input sequence or each animation's frame-by-frame correspondence to CS2.
- 65 complete-action matrix/grip endpoints; two folder blade axial-fit assertions; raw-click/entity deduplication and held-input behavior.

## Not proven

No continuous-time collision/swept-volume proof; no exhaustive interpolation, user/other-module combination, actual skin sleeve deformation, custom shader, engine FOV, GPU clipping, vanilla active offhand item, game reload, desktop/Android/Pojav test or multiplayer test. No recorded CS2 source frames were measured. Track timing and proportions are authored approximations. New geometry refinement has not received visual in-game approval. Geometry tests use fixed module proportions, not the removed arbitrary scale/offset settings.

Historical 070–076 scripts are retained without retroactively rewriting their results. Do not claim that whole historical chain passes on077: 072 expects immediate inspect cancellation and 073 reflects the now-deleted private generic inspect method. Run077 for the new behavior. The early diagnostic logs are not acceptance results; `results.txt` contains the latest successful077 run.

## 076 isolation audit

`baseline076.sha256.json` records every existing file from the immutable delivered076 ZIP. `source-audit.py` permits changes only to Animator, Meshes, Rig and ModuleFpsKnife. It rejects changed/deleted unrelated existing files, and rejects unlisted new files. Skins, inventory, GUI, chest modules, other fixes and historical results must retain their baseline bytes. This is a byte-isolation audit, not a substitute for running their game tests.
