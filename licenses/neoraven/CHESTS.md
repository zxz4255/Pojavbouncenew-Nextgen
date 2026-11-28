# Additional NeoRaven attribution — release 075

Repository: https://github.com/Matr1ze/NeoRaven
Pinned upstream: `b483ffc576e335e01f2914c920e88289ab090e66`
Authorship: NeoRaven contributors; repository published by Matr1ze. Upstream describes its chest modules as OpenExpo adaptations; their original source comments have been preserved in the pinned copies.
License: GNU GPL version 3, original text in this directory's `LICENSE`. Destination LiquidBounce notices and earlier NeoRaven Cinematic/Rain notices remain intact.

## Derived code

- New package `features/module/modules/player/neoraven/`: `ModuleNeoChestAura`, `ModuleNeoChestStealer`, `NeoChestPolicy` (filter/scoring and state machines), `NeoChestItems` (modern item-component adapter), `NeoChestEnvironment` (modern protocol/sidebar adapter).
- New `MixinNeoChestPackets` and `MixinNeoChestAcceptedSend`: accepted-use and post-vanilla-content callback order.
- Additive observer bridge in `render/OpaiChestLoot.kt`: permits the new stealer to drive the existing NeoRaven-style loot panel without borrowing any old stealer's queue/filter/click logic or changing its settings. The existing Opai island remains an optional, separately enabled visual host, not a newly transplanted full Watermark module.

Twelve untouched upstream Java files are retained under `scripts/verify075/upstream/` with paths and SHA-256 in `manifest.json`: the two modules, ItemUtils, BlockUtils, PacketUtils, RotationUtils, RotationManager, RotationSource, Hypixel, ChestLoot, the packet mixin and WatermarkModule. `settings.json` records the original two modules' settings/defaults.

## Changes for the target engine

The original module algorithms are expressed in Kotlin with explicit pure state machines. Removed 1.8 item subclasses are mapped using modern item tags/components; original comparator/tie/food/rod rules and the legacy generic-protection/sword/bow/tool scores are retained. Modern-only human armor, enchantment/data-pack attributes, offhand and newer potion items necessarily follow the documented adapter rather than a nonexistent 1.8 class.

Legacy C08/S30/S2F/S32 handling is replaced by native MC 26.3 block-use and container quick-move APIs. The Aura confirms before the Stealer runs Instant from the full-content handler's TAIL. The old five-second exact-old-S2F mirror rewrite is **not** applied to modern authoritative updates: MC 26.3 has state-id synchronization and no matching S32 transaction packet. Slot/full resyncs remain server-authoritative. No fake inventory changes or forged acceptance packets are introduced. Via/1.8 server behavior still requires live testing.

New modules yield while corresponding old modules are active, avoiding two independent engines clicking the same menu. Old module source/config namespaces are untouched. Rotation requests use the native arbitration engine at low priority and map Silent/Strict/None movement correction. Chest tracking uses its own loaded-chunk scanner subscriber. Lobby detection projects the two states used by this module from the upstream sidebar rules; it does not transplant unrelated Hypixel locraw or combat features.

See `075-NEORAVEN-CHESTS.md` and `scripts/verify075/README.md` for exact feature mapping, test scope and outstanding live-runtime limitations. Neither a full build nor game/server/Mixin/Pojav/visual parity is implied by source and fixture checks.
