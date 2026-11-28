# 075 focused verification

Run from the source root with a JDK25, Kotlin compiler supporting JVM25, official unobfuscated MC26.3 client jar and its dependency jars:

```sh
JAVA_HOME=/path/to/jdk25 \
KOTLINC=/path/to/kotlinc \
MC_JAR=/path/to/client.jar \
VERIFY_LIBS=/path/to/dependency-jars \
JAVA_OPTS=-Xmx320m \
bash scripts/verify075/run.sh
```

The default also executes verify074 and its 070–073 chain. That chain needs the existing documented shader/Mesa dependencies, including glslangValidator, Python numpy/moderngl and EGL. Set `SKIP_PRIOR=1` only to run the 075-focused portion. Tools/dependency binaries are not included in the source ZIP.

## What each check does

- `source_audit.py`: SHA256 of twelve untouched pinned upstream Java files and their settings, twenty protected 074 sources, native registration and callbacks, actual packet handler names/descriptors (when JDK/client supplied), defaults/step normalization, JSON duplicate keys and EN/ZH UI keys. These are STATIC assertions, not an applied-Mixin test.
- `stubs/` and `hud-stubs/`: deliberate no-op fixtures for host event/config/module/rotation/chunk-scanner services and HUD painters. The runner compiles ALL five actual new Kotlin files and actual modified OpaiChestLoot against the real client jar, then javac compiles both real Java Mixins. This catches MC signatures and local integration types, not full host linkage or runtime behavior.
- `make_oracle.py`: extracts the actual upstream `slotsToSteal`, category/trash and best-item method bodies with only obsolete fully-qualified aliases replaced; surrounding Minecraft items, slots, inventory and score calculations are toy adapters. Source provenance in `upstream/manifest.json`.
- `ChestRegression.kt`: compares 16,384 seeded randomized 27/54-slot inventories across every one of the 1024 filter toggle combinations against that oracle; explicit equal-score inventory/chest ties, food/rod, score formula, jitter/title/lobby projections; pure Aura and Stealer state-machine boundaries/session/Instant cases.
- `results.txt`: captured successful final runner output, including the 070–074 chain. Earlier-chain malformed-entry test intentionally logs a warning/NumberFormatException and then passes isolation checks.

## Honest limits

No full Gradle/game run, actual Mixin transformation, native item-component extraction/reload execution, server acceptance/rejection/acknowledgement, cancellation-order, live rotations/move-fix, GPU game render or Android/Pojav proof is provided. `accepted` in the observer means the original Connection.send returned; it is NOT a server acknowledgement. The RETURN-injection cancellation premise remains a runtime acceptance item.

An attempted standalone modern ItemStack bootstrap smoke test failed at ItemStack construction (`Components not bound yet`); it was removed instead of recording a false adapter pass. Score/oracle comparisons therefore use modeled items, while the modern adapter receives API-compilation coverage only.

Rotation expiry source review: host `update()` applies the plan, stores it in `previousRotationTarget`, then advances RequestHandler; `activeRotationTarget` falls back to the previous plan after the one-tick request expires. The POST identity guard uses that public active target, not the expired private request. This is source review, not live timing validation.

See root `075-NEORAVEN-CHESTS.md` for feature mapping, explicit cross-version deviations and game acceptance checklist; `licenses/neoraven/CHESTS.md` for attribution.
