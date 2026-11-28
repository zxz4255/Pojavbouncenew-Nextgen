# verify082

Run from a complete 082 source root:

```sh
JAVA_HOME=/path/to/jdk25 \
KOTLINC=/path/to/kotlinc/bin/kotlinc \
VERIFY_LIBS=/path/to/libs \
BASELINE081=/path/to/Pojavbouncenew-Nextgen-081-KGG-ONLINE-KEYS.zip \
  bash scripts/verify082/run.sh
```

Used: Kotlin 2.4.20 official compiler distribution (including its kotlinx-coroutines-core-jvm.jar); JDK25; Gson 2.14.0; fastutil 8.5.16. `VERIFY_LIBS` contains gson.jar and fastutil.jar; the runner adds the compiler distribution's coroutine jar. The immutable081 ZIP SHA256 is enforced by prepare.py.

- `prepare.py`: extracts original081 and patched production class bodies verbatim; full actual Value, UiTextEditState, SettingRenderer; actual ListValue/MutableListValue bodies and factory functions. Does not rewrite the code under test.
- `fixtures.py`: explicit no-render game/clipboard/draw/layout/window/event/translation services, unrelated widget constructors and non-TEXT ValueType deserializer dependencies. TEXT still uses the actual identity String-deserializer behavior. These are not real Minecraft or GPU implementations.
- `TextSettingRegression.kt`: old negative controls plus patched regression assertions. Reflection accesses actual private input editors and list-add method, avoiding production test-only APIs.
- `results.txt`: exact successful 082 execution output. 10,044 assertions include 2,000 reopen/edit loops (not 10,044 unique features).
- `retained081-results.txt`: this turn's rerun of the unchanged081 runner, including compiler warnings and test outputs. No baseline log overwritten.
- `audit-package.py`: immutable081 scope verification, entire baseline file inclusion, exact three changed paths and one added production file, no removed Neumorph source, flat ZIP / CRC / byte verification; writes scope-audit.json and external delivery metadata.

This suite is not a full Gradle build, real-account service test, actual Minecraft render test or Android-device test.

Official toolchain archive checks at download:
- Kotlin 2.4.20 compiler SHA256: `59e9ca74c7904ef2c122b12114937673ccce68de820a663f0ed66ccf8799e0b7`.
- Gson 2.14.0 SHA256: `2cbd119bf1961c28788310963dc80ba65f58cdeec1dd139c8bdb1240faa2c36f`.
- fastutil 8.5.16 SHA1 (repository-provided checksum): `34d13fa04fc72b5d9674adc6fd765afaaccc551e`.
