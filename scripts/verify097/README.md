# 097 MVP living-entity kills

Source-only candidate. No full Gradle build, game/Mixin boot, real server attribution, native/Pojav or visual acceptance claim.

```bash
source /path/to/verify-env/env.sh
bash scripts/verify097/compile-api.sh
bash scripts/verify097/runtime.sh
python3 scripts/verify097/audit.py /path/to/Pojavbouncenew-Nextgen-096-NEOINV-EXECUTION-FIX.zip
```

`compile-api.sh` reuses 088 project hosts, actual MinecraftShortcuts/AddonApi, pinned MC26.3/VFP5.1.1 SHA locks and actual member-reference auditing, but runs the new 097 living-target wiring checks. All 3 full production MVP sources compile; renderer/state remain byte-identical to 096. 51,039 historical pure-state assertions pass and 57 real member references resolve. DSL, EventListener registration, UiDraw and other app surroundings are hosts, not a booted client.

`runtime.sh` executes the whole production ModuleCs2Mvp and Cs2MvpState with explicitly modeled entities, incoming packets, queue, DSL and renderer. 41 adapter assertions cover all target branches (players and generic LivingEntity standing for animals/mobs), death-only/local attribution, health/status dedup, consecutive-kill refresh, self/spectator/nonliving/other killer exclusions, cancelled/outgoing inputs, legacy modes, stale queue identity checks, HUD/world reset. Tests inspect timeline timestamps to ensure duplicates cannot silently restart the animation. This is separate evidence from real-API compilation, not real packet/Mixin/server execution.

`check-wiring.py` preserves 088 layout/registration/settings/JSON-uniqueness assertions, replaces the now intentionally removed friend/team/player-only checks with all LivingEntity branch/self guards.

`audit.py` verifies the immutable delivered 096 SHA256; exactly 3 changed files (one Kotlin module, two existing language description lines); no deletions; additions only under verify097 or the 097 release note. State/renderer, Neo inventory, music, manual sword, MotionBlur and Android cursor files must match baseline bytes. Optional destination argument writes a root-relative ZIP, checks CRC and all workspace entry bytes, and emits a SHA256 sidecar.
