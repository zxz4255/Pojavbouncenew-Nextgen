# 085 combat verification — scope and reproduction

Run from the source root:

```bash
JAVA_HOME=/path/to/jdk25 \
MC_JAR=/path/to/official26.3-client.jar \
MC_LIBS=/path/to/26.3-manifest-libraries \
VERIFY_LIBS=/path/to/verified-vfp5.1.1-and-extracted-nested-jars-plus-asm \
KOTLIN_LIBS=/path/to/kotlin-compiler-jars \
bash scripts/verify085/run.sh
```

`KOTLIN_LIBS` must include Kotlin 2.4.20 compiler-embeddable, stdlib and script-runtime; kotlin-reflect, kotlinx-coroutines-core-jvm and JetBrains annotations must also be on the compiler launcher classpath. This run used reflect 2.2.0, coroutines 1.11.0 and annotations 26.0.2. Artifacts are not redistributed in the source ZIP.

`VERIFY_LIBS` contains the official Modrinth VFP5.1.1 JAR and its `META-INF/jars/*.jar` entries, plus ASM and ASM-tree 9.9. Minecraft58 non-native compile libraries come from the official manifest. Artifact hashes are checked before compilation.

## What runs

- `settings-audit.py`: pinned upstream SHA256 and all 104 functional settings, registration and local public-API/name wiring checks. Original descriptive UI headers are not executable settings.
- `fixtures.py`: explicit **project-host** signatures/behaviour for isolated Kotlin compilation. It does not replace any Minecraft class in the compilation. It does not assert serialization, module initialization or host feature correctness.
- `integration-hosts.py`: extract the exact modified SwordBlock predicates, HUD resolvers and CombatManager target getter, with explicit containing-class hosts. These excerpts are not full containing-class compilation.
- `run.sh`: compile the actual existing Java VFP bridge against the real VFP and MC artifacts (only its outer protocol-selector facade is a stub); compile all six new Kotlin production files and integration excerpts against the actual MC jar and actual bridge; compile/run the audit and pure logic tests.
- `ApiAudit.java`: resolve all actual MC and bridge member owners/descriptors referenced by the compiled new port. No actual network connection is made.
- `Regression.kt`: 67,460 assertions on actual production `NeoBlockMachine`, signed/index switch algorithms, queue, lag accumulator and maths, including an independently transcribed pinned timestamp-switch oracle. The pure-logic runtime has neither MC nor the project hosts on its classpath.

`results.txt` is the actual stdout/stderr of that run. Counts may change when production code changes; rerun instead of editing results manually.

`cursor084-regression.txt` records a separate rerun of `scripts/verify084/run.sh` against the unchanged native-only cursor code. Its Android/desktop cases are explicit runtime hosts, not hardware.

`gradle-attempt.txt` records the failed full-project Gradle attempt. `scope-audit.json` is produced by `audit-package.py` when the source archive is assembled.

## Not established

These tests do not establish a complete Gradle build, a Mixin transformation/bootstrap, a real MC game session, ViaFabricPlus wire correctness, Minecraft rendering fidelity, actual client settings serialization, true mouse/Android behaviour, server hit registration, or anti-cheat compatibility. They also do not prove equivalence of the upstream-specific module dependencies that were not transplanted. See the root085 port status/mapping document; do not describe this test set as complete 1:1 port acceptance.
