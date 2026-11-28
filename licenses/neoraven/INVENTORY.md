# NeoRaven inventory port attribution — 076

Repository: https://github.com/Matr1ze/NeoRaven
Pinned commit: `b483ffc576e335e01f2914c920e88289ab090e66`.
License: GPL version 3; see this directory's LICENSE and existing NOTICE.md. Original authors are NeoRaven contributors; upstream `InvManager2Module.java` credits OpenExpo's InvManager.

Derived files: `ModuleNeoInvManager.kt` and `NeoInventoryPolicy.kt` in the player/neoraven package. They use the 075 modern component/scoring adapter, with the original InvManager2 stone-efficiency rule (no ChestStealer durability cutoff). The new shared `InventorySafetyPolicy.kt` / `SafeInventorySession.kt` implement target-engine safety and are also used to repair the existing TS `ModuleInvManager`.

Untouched upstream InvManager2Module, ItemUtils and BlockUtils are included in `scripts/verify076/upstream/`, alongside the original 28 settings and a SHA256/path/commit manifest. The repository's older, separate InvManager module is NOT the chosen inventory source: this port uses the OpenExpo-derived InvManager2, matching the previous chest ports' family. It runs only with the physical inventory open, as that upstream module does.

Deliberate safety changes, rather than blind bug-for-bug reproduction:
- Equal-score items already in their configured destination stay there; first configured category owns duplicate destination settings; allocated earlier destinations are not stolen by later categories.
- Replacing worn armor preserves it through capacity-checked native SWAP sequences, including when storage is full. No armor THROW action.
- Cleanup requires a new explicitly selected DiscardItems allowlist, empty by default, in addition to upstream trash classification. Modified-component, named/custom, enchanted, damageable and container stacks are protected. Upstream unsafe default trash classification is not permission to discard anything.
- Symmetric duplicate-shears deletion is fixed to strict-better candidates; MaxTrashThrows is a real per-screen budget instead of an effectively reset local counter.
- Native clicks are serialized with exact component-equivalence/count snapshots, preflight slot permissions/capacities, a minimum 250ms/two-distinct-tick settlement window and a repeated-state/rollback circuit breaker. This is NOT a server acknowledgement implementation.
- Neo takes precedence over TS; both yield to enabled native InventoryCleaner/AutoArmor and defer for combat/Scaffold. Blink/queued packets block new work. No background-click thread, fake confirmations or direct item/menu writes.

The TS repair also retires its unsafe crafting repair, silent OPEN_INVENTORY simulation, chest-steal side path and same-tick Instant burst. Historical settings remain loadable where applicable but retired settings are hidden/non-operative. This is documented compatibility loss chosen to prioritize the user's request not to lose or throw useful items. It is not presented as a fully unchanged 1:1 TS port.

See `076-SAFE-INVENTORY.md` and `scripts/verify076/README.md`. Pure models and focused real-MC API compilations do not prove full Gradle linkage, real component binding, Mixin/game/server/Via correctness or Android/Pojav behavior. No guarantee of loss-free behavior on untested servers is claimed.
