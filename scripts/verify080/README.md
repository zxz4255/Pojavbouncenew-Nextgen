# 080 namespace / inherited-member compile regression

```sh
JAVA_HOME=/path/to/jdk25 \
KOTLINC=/path/to/kotlinc/bin/kotlinc \
MC_JAR=/path/to/official-MC26.3-client.jar \
VERIFY_LIBS=/path/to/MC-library-jars \
BASELINE079=/path/to/Pojavbouncenew-Nextgen-079-KGG-DECRYPTION.zip \
bash scripts/verify080/run.sh > scripts/verify080/results.txt 2>&1
```

Tested with JDK25 and Kotlin2.4.20. Dependencies are not bundled. Heaps are bounded and all compiled outputs/adjusted fixtures are temporary. Host helper stubs are copied from historical075/076, but:
- EventState is removed from their incorrect `event.events` namespace. The actual enum body/package is read from real `event/Event.kt`.
- ClientModule fixture inherits the **real production MinecraftShortcuts** interface (compiled with real MC26.3) and its conflicting private/protected mc stand-in is removed.
- Actual AddonApi is compiled too. All other no-op host behavior remains explicitly stubbed.

Runner first compiles original079 modules and REQUIRES the three reported diagnostics. It then compiles actual patched Aura/Stealer/inventory modules, actual associated policies/adapters/shared HUD observer, real MinecraftShortcuts and the extracted EventState enum. It reuses unchanged075 chest and076 inventory model/oracle regressions afterward. Source-check requires exactly the intended import/identifier-only changes.

The old fixtures' incorrect namespace/inheritance explain the historical false-negative. This new negative control would fail if the reported problems became masked again. Historical tests/results are preserved unchanged; their original weak host coverage is not retroactively called full integration.

No full Gradle build, full ClientModule/config/event-dispatch linkage, applied Mixin, native item-component runtime, live game/server or Pojav verification is implied.

Packaging: `python3 scripts/verify080/audit-package.py BASELINE079.zip OUTPUT080.zip` checks exact immutable079 SHA256, three allowed production changes and no deleted/other modified baseline files, creates scope-audit.json, packages a flat source root and verifies all ZIP bytes/CRC.
