# 093 focused verification

Source release; NOT a full Gradle build or device/server/VIP-account acceptance test.

Environment: JDK25, Kotlin2.4.20 compiler jars (`KOTLIN_LIBS`), official MC26.3 client (`MC_JAR`) and manifest libraries (`MC_LIBS`), VFP5.1.1 plus extracted Via libraries/ASM/Sponge Mixin (`VERIFY_LIBS`). `compiler.sh` invokes the supplied embeddable compiler; does not download dependencies. See 085 source locks for MC/VFP hashes.

Run from repository root:

```sh
bash scripts/verify093/manual.sh
bash scripts/verify093/aura.sh
bash scripts/verify093/chest.sh
bash scripts/verify093/music.sh
bash scripts/verify093/lifecycle.sh
bash scripts/verify091/compile-render.sh
python3 scripts/verify093/audit.py /path/to/Pojavbouncenew-Nextgen-092-NEOINV-DIAGNOSTIC.zip
```

- `manual.sh`: compiles the **complete production** manual adapter/state/VFP wire bridge against actual Minecraft/VFP APIs. Only project bootstrap/module/protocol-selector facades are hosts. Runs pure production session state, exact extracted input-hook body, and forced-false negative control. Exact HEAD/RETURN item-use callback bodies compile too; this is NOT Mixin bootstrap, real connection/translation or renderer validation.
- `aura.sh`: reuses explicit 085 fixtures; compiles complete current Neo aura code, exact current SwordBlock gates/manual ownership predicate and HUD/CombatManager excerpts, plus API/logic checks. ManualSwordBlocking is a host only in this aura harness; the actual adapter is compiled separately in `manual.sh`. Old TS-preservation assertions are intentionally not reused.
- `chest.sh`: complete current Neo chest modules/adapters/policy and shared Opai HUD compile against real MC APIs, plus two unchanged packet mixins. Uses explicit 075 peripheral hosts and upstream Java toy-item filter oracle; no server acknowledgements. Corrects the **host** EventState namespace to production's actual namespace.
- `music.sh`: actual music protocol/client/manager/key parsers, test transports and loopback fixture downloads; existing UI host gains current glass painter signature only. 079 protocol tests updated for native asset request + request-hash checks. Does not send a VIP request, exercise the native audio engine, or prove OGG caused the user's failures.
- `lifecycle.sh`: full actual 092 setter/module chain without deleted TS source; preserves diagnostics and never forces activation. The old device failure remains unreproduced.
- `audit.py`: verifies 092 baseline SHA, exact eight deletions, approved source/localization scope, zero remaining production references to deleted implementations, Neo registrations and unrelated baseline file equality. ZIP validation checks every archived byte and CRC.

The `093-*.txt` files are actual focused test outputs, not device logs. Historical verify070–092 assertions describe their respective releases and some TS-preservation expectations no longer apply; they are retained as history, not represented as passing wholesale on 093.
