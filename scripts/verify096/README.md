# 096 NeoInvManager execution regression

**Source candidate; no whole mod build, Minecraft/Mixin boot, actual native input/network or Pojav acceptance.** See root 096-NEOINV-EXECUTION-FIX.md.

## Run

Use the same JDK25 / Kotlin2.4.20 / actual MC26.3 + dependency environment as the preceding verification harnesses:

```bash
source /path/to/verify-env/env.sh
bash scripts/verify096/reproduce095.sh /path/to/Pojavbouncenew-Nextgen-095-SHADOW-LYRICS-TAP-TOGGLE.zip
bash scripts/verify096/compile-api.sh
bash scripts/verify096/runtime.sh
python3 scripts/verify096/audit.py /path/to/Pojavbouncenew-Nextgen-095-SHADOW-LYRICS-TAP-TOGGLE.zip
```

Environment: JAVA_HOME, KOTLIN_LIBS, MC_JAR, MC_LIBS, VERIFY_LIBS. `runtime.sh` itself requires only JDK/Kotlin; game fixture sources are generated into a temporary directory. `compile-api.sh` uses the real MC jars. Scripts use set -euo pipefail; failed assertions are not converted to successful runs.

## Separation of evidence

### Baseline reproduction: reproduce095.sh

SHA256-checks the delivered 095 ZIP and extracts its exact production ModuleNeoInvManager, NeoChestItems, NeoInventoryPolicy, NeoChestPolicy, SafeInventorySession, InventorySafetyPolicy. Nothing is patched in those sources. Uses the SAME runtime hosts as 096.

- Closed player inventory + misplaced sword: zero native calls.
- Open player inventory + helmet in storage, empty head slot and empty scratch: zero native calls after repeated full tick dispatch, sword never reaches hotbar.
- Control: same fixtures with no armor blocker submits the sword swap immediately.

This confirms defects in those production paths in a modeled runtime, not the user's actual device trace.

### Real MC API compilation: compile-api.sh

Adapts verify095/compile-inv.sh. Compiles complete actual Neo module/hotkeys/latch/diagnostics, complete safety transport and policies, real NeoChestItems, real Value/ValueWriteTrace/ToggleableValueGroup/ClientModule/MinecraftShortcuts against MC26.3. Config DSL/event delivery/input/config storage are explicit hosts, not actual game services.

Runs 6,148 historical lifecycle and diagnostic assertions plus 1,410 migration/latch assertions. Only historical module-description marker expectation changes to 096; 092 diagnostic-writer marker remains unchanged. Static historical output text may still mention 092/095. This is not an entire Gradle build.

### Runtime: runtime.sh

Compiles WHOLE UNMODIFIED current production ModuleNeoInvManager, NeoChestItems, NeoChestPolicy, NeoInventoryPolicy, SafeInventorySession and InventorySafetyPolicy. `runtime-hosts.py` supplies explicit game/component/DSL/event services. The independent native-operation model uses plain slot exchange / whole-stack removal; it NEVER calls production predict(). This ensures a broken planner cannot validate itself by applying its own prediction as the native result.

`RuntimeRegression.kt` dispatches modeled ticks through the real registered module handler. It checks observed native calls, resulting slots, conservation, cursor state and diagnostic status, not just chosen plans. 112 assertions cover the full path plus pauses, rejection, explicit recovery and settings. Fast tests expire only the private wall-clock settlement deadline via reflection; expected snapshots, fault state, native operations and two distinct tick checks stay active. One test also waits real 270ms intervals and confirms the production 250ms floor is not skipped.

`ArmorRegression.kt` invokes the actual new pure armor plan (42,783 assertions, 5,184 geometry/state combinations). Empty/full matrix extends beyond the historical full-inventory-only tests that missed the redundant empty tail. The runtime transport retains the original narrow predict() refusal of no-progress clicks.

`runtime.sh` also reruns the existing verify076 InventoryRegression with the pinned upstream Java scoring oracle: 240,000 category/score comparisons, 12,000 randomized inventory convergence cases / 60,111 swaps, 1,296 full-inventory armor exchanges, 2,016 partial-stack merges and safety-gate/discard regressions.

### Preservation: audit.py

Checks exact immutable 095 hash, exactly 3 modified production files, no deletions, only 096 docs/tests added. InventorySafetyPolicy with the additive armorSwapPlan stripped must be byte-identical to 095; predict(), gate, target allocator and discardAllowed() remain untouched. Bind migration/hotkeys, music renderers, main panel/HUD, manual sword, MotionBlur and Android cursor are among all remaining baseline entries required byte-identical.

Optional final argument to audit.py packages a root-relative ZIP, verifies its CRCs and every entry against workspace source bytes, and writes a SHA256 sidecar. Packaged result logs are from this candidate, not inherited 095 results relabeled as 096.

## Important limits

Runtime fixture ItemStacks, components, Player, inventory slots, GUI, events, ClientModule DSL and GameMode are modeled. Real-game item-tag sync, vanilla callbacks, applied Mixins, legacy Via inventory-open requirements, server correction latency, native Android input and on-screen appearance remain unverified. The script's real-API compiler is a separate check, not evidence that these fixtures are a running Minecraft client.

The successful-pass boundary releases an already settled snapshot for subsequent legitimate pickups. It does not fabricate a server ACK; a server correction arriving after a completed pass is not distinguishable from a new external update solely from a local snapshot. Pending divergences still latch and never auto-retry. No claim of exhaustive remote correction detection is made.
