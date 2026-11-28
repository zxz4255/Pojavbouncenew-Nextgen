# NeoRaven combat port — attribution and modifications (085)

Repository: https://github.com/Matr1ze/NeoRaven

Pinned revision: `b483ffc576e335e01f2914c920e88289ab090e66`.

Original project/authorship: NeoRaven contributors, published by Matr1ze. `KillAura2Module.java` identifies its Expo behaviour as derived from OpenExpo and its Leader surface as Leader-Lite; `KillAura2Flux.java` identifies the Flux branch. Those original descriptions remain in the archived source. No endorsement is implied.

License: GNU General Public License version 3. The upstream license is retained at `licenses/neoraven/LICENSE`. Retain these notices, the archived original notices, and the destination LiquidBounce GPL notices when redistributing.

## Derived production files

`src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/neoraven/`:

- `ModuleNeoKillAura.kt`: native module/event/packet integration for the original aura.
- `ModuleNeoKillAura2.kt`: independent Leader/Expo/Flux selection, rotation, cadence, filtering and target rendering.
- `NeoAuraSettings.kt`: functional settings/defaults/ranges/steps and independent persisted branch groups.
- `NeoAuraLogic.kt`: timestamp/index selection, angle/smoothing/sensitivity helpers, lag accumulator and bounded queue.
- `NeoBlockMachine.kt`: explicit original block-mode phase transitions, separated for deterministic testing.
- `NeoAuraSupport.kt`: target/visibility/aim helpers, local friend/enemy/team/bot adapters, native rotation/attack/mutual-exclusion integration.

Existing ModuleManager, SwordBlock, CombatManager and two target-HUD sources contain small registration/read-path extensions. No existing aura implementation is replaced.

## Original reference source

`combat-upstream/` contains the original three combat files and four helper files as `.java.txt`, plus the original KillAura's linked setting data. The Java texts are byte copies of the pinned revision, not modern build inputs. SHA256 hashes and artifact locks are recorded in `scripts/verify085/source-lock.json`.

Original source paths, relative to the repository root:

- `src/main/java/xyz/daikirai/module/combat/KillAuraModule.java`
- `src/main/java/xyz/daikirai/module/combat/KillAura2Module.java`
- `src/main/java/xyz/daikirai/module/combat/KillAura2Flux.java`
- `src/main/java/xyz/daikirai/util/{RotationUtils,PlayerUtils,PacketUtils,RotationManager}.java`

Raw source prefix: `https://raw.githubusercontent.com/Matr1ze/NeoRaven/b483ffc576e335e01f2914c920e88289ab090e66/`.

## Compatibility boundaries

This is a source adaptation from Forge 1.8.9 to MC26.3, not a binary or packet-for-packet clone. It uses this project's attack/swing/rotation/render APIs and existing VFP bridge. Legacy block packets require an active legacy PLAY connection. Old inventory-update suppression is intentionally not copied. Several upstream-specific module handshakes are not reproduced; a bounded connection-owned queue and host safety/priority checks replace only part of that coordination. Modern event ordering, visual pipelines, branch-reset behaviour and the Damage timer differ as documented.

See **`085-NEORAVEN-COMBAT-PORT.md`** for the full implemented/missing mapping and validation limits. In particular, the candidate has not passed full Gradle, full Mixin, a live game session, real protocol translation, Android hardware or server testing; it is not certified as a complete 1:1 port. The archived upstream files are evidence of the source used, not evidence that all their dependencies have been transplanted.
