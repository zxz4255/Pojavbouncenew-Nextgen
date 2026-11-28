# 076 inventory safety and style-removal checks

```sh
JAVA_HOME=/path/to/jdk25 \
KOTLINC=/path/to/kotlinc \
MC_JAR=/path/to/official-MC26.3-client.jar \
VERIFY_LIBS=/path/to/dependency-jars \
JAVA_OPTS=-Xmx320m \
bash scripts/verify076/run.sh
```

The default runs 076 and the complete existing 075 → 074 → 073 → 072 → 070/071 focused chain. Existing shader/EGL checks need Python numpy/moderngl, glslangValidator and Mesa/EGL; see older verification READMEs. `SKIP_PRIOR=1` runs only the new focused checks. Compilers/client/dependency jars are not included.

## Actual coverage

- `source_audit.py`: hashes of untouched pinned upstream Java/settings, 28 original defaults, protected 075 chest/knife/effect/layout/inventory-infrastructure files; independent TS/Neo registration/conflict guard; empty explicit discard lists; source-level single native mutation entry, capacity checks, whole component keys, timer/circuit breaker; removal of unsafe TS paths and Neumorphism renderer/settings/UI references; locale duplicate-key checks. STATIC assertions, not runtime proof.
- Compile **actual** complete repaired `ModuleInvManager`, complete `ModuleNeoInvManager`, both shared safety files, `NeoInventoryPolicy`, and the actual 075 modern component/scoring adapter against the official MC26.3 jar.
- `stubs/` supply host module/config/events, old TS scoring extensions, Slots/ArmorEvaluation, potion sets and other modules. They do not instantiate a working game or implement those old services. This is API/type coverage, not full application linkage or a TS scoring/runtime acceptance test.
- `make_oracle.py` embeds unchanged original `sortClick`, `moveBest`, `matchesKind`, `score`, `isSplashPotion` bodies from the pinned InvManager2 Java source. Only surrounding obsolete Minecraft classes are toy adapters. `InventoryRegression.kt` makes 240,000 category/score comparisons; reproduces the upstream tie issue; checks fixed target allocation with 12,000 randomized inventories (60,111 conservative swaps), pinned slots, duplicate categories/targets, full-capacity armor exchanges (1,296), component-key/count-conserving merges (2,016), discard truth table, forbidden slots/buttons, settlement, no-op/rollback and repeated-state latch.
- Compile **actual** `ModuleClickGui.kt` and `UiPalette.kt` against MC APIs with config/UI host fixtures. `StyleRegression.kt` checks two selectable styles, old saved-style tag aliases, and actual Modern/Glass palette outputs. Existing 071 additionally compiles the actual changed major UI screens/setting controls with its own fixtures and tests persisted layout/expansion/search/localized sorting. UiDraw's small branch deletion receives static dangling-reference checks, not a full renderer compile/runtime test.
- `results.txt` contains the successful sequential run. The intentional malformed-GUI-entry fixture logs a NumberFormatException warning before passing error isolation. A preliminary attempt to run compilers concurrently was killed for memory pressure; the final sequential runner passed. Do not run multiple Kotlin verification chains at once in a small sandbox.

## Important limits

These checks cannot prove real MC component binding/reload, actual player/slot/item adapters at runtime, packet cancellation/queue behavior, multiplayer/Via acceptance, full Gradle linkage, applied Mixins, real UI rendering or Android/Pojav support. The pure policy uses modeled items/exact abstract component IDs, not real server stacks. The transport is compiled against actual MC APIs but is not game-driven in this suite.

The 250ms/two-distinct-tick check is a local prediction-stability window, NOT a server acknowledgement. It does not forge transactions or claim every server responds within 250ms. Any observed inventory divergence, even a later/manual change, stops the pass until reopening. Native SWAP permission/capacity preflight is source-audited/API-compiled; game-specific constrained slots still need acceptance testing. Cursor-based TS merges may leave a vanilla cursor stack if the user manually interrupts; no automated outside-window/drop/recovery click is attempted.

The empty DiscardItems setting is a NEW namespace in both modules, so old TS Drop=true profiles do not automatically authorize drops. Selected items still require candidate classification, unmodified components and non-equipment/container protection. Worn armor replacement never calls THROW. Unsafe TS auto-crafting repair, silent simulation, chest-steal side path and 48-action same-tick Instant burst were intentionally retired, not quietly claimed as unchanged features.

See root `076-SAFE-INVENTORY.md` for behavior changes, setup and cautious game acceptance guidance; `licenses/neoraven/INVENTORY.md` for attribution.
