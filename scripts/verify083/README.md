# verify083

```sh
JAVA_HOME=/path/to/jdk25 \
MC_JAR=/path/to/official26.3-client.jar \
MC_LIBS=/path/to/official-manifest-libraries \
VERIFY_LIBS=/path/to/verification-jars \
BASELINE082=/path/to/Pojavbouncenew-Nextgen-082-CLICKGUI-REGEX-FIX.zip \
  bash scripts/verify083/run.sh
```

`VERIFY_LIBS` contains:
- `vfp.jar`: exact ViaFabricPlus5.1.1 Modrinth main artifact (see source-lock.json). Extract its nested dependency jars into the same directory, especially viafabricplus-api and viaversion-common.
- `mixin.jar`: Fabric sponge-mixin0.17.3+mixin.0.8.7.
- `mixinextras.jar`: mixinextras-common0.5.0.
- `asm.jar` and `asm-tree.jar`: ASM9.9.

`MC_LIBS` contains non-native compile jars from the official26.3 client manifest, in any subdirectory structure. The runner finds them recursively. JOML1.10.9 and SDL3.4.3 come from that manifest. Main MC/VFP artifacts are pinned and hash-enforced; download URLs and hashes are in source-lock.json. No game/VFP jar is redistributed in the source package.

## Test phases

1. `prepare.py` checks the exact delivered082 baseline and extracts the three cursor-GUI hooks plus one mouse-grab hook **verbatim** from the changed production files into test containers with their original annotations. Full production MixinItem and both complete production helpers are compiled directly.
2. `javac` compiles those production sources against real MC26.3, SDL, VFP5.1.1 and Mixin APIs. Only unrelated project services (module visibility/aiming/etc.) use explicit project fixtures. This phase has no mocked MC/SDL/VFP API.
3. `ApiAudit.java` reads the actual official game bytecode and the compiled hooks. It audits all7 injection target signatures/callback types, a unique applyCursor invocation and its live graphics LVT, confirms the actual SDL release gap, and checks no item mutation/raw-send path. It does **not** launch a full Mixin transformation engine.
4. `fixtures.py` produces explicit runtime game/draw/SDL/network hosts. Compile **hosts and tests only**, not production sources. Execute the real-API-compiled production bytecode unchanged against those hosts, with real Mixin CallbackInfo and JOML. `InputRegression.java` calls the hook methods through reflection. 7085 Android/sword +2 desktop assertions, including1000 simulated session/round replacements.
5. Remove the optional Via facade from a separate runtime-host copy and launch another JVM (`NoViaRegression.java`):1000 safe queries plus independent Android cursor recovery without Via.

Runtime hosts are not a real game, OS cursor or Via protocol pipeline. The fake connection versions/types are intentional state-machine fixtures; real API binding was checked separately before execution. No claim of actual server match completion or validated server-side damage reduction.

## Retained regressions

`retained082-results.txt`: rerun unchanged verify082 runner,10044 assertions/2000 GUI open-edit loops PASS, including immutable081 crash negative control. Requires Kotlin2.4.20/Gson/fastutil/coroutines and the immutable081 source ZIP as described in verify082 README.

`retained081-results.txt`: rerun unchanged verify081 runner; crypto, protocol, manager, Island, KGG and online-key offline suites all PASS. Compiler warnings are retained rather than hidden. No real account or native playback test.

`audit-package.py`: exact082 SHA256 baseline, three allowed modified files, two allowed added production helpers, preserve all other files including082/081 tests and configs, no deleted Neumorph source reintroduced, flatZIP CRC/every-byte checks. Writes scope-audit.json and external083-delivery.json.

## Limits

No full Gradle build, complete Mixin runtime application, Android/Pojav/Zalith device test, real SDL cursor painting, real network translation, real server matches or actual server damage verification. The missing-component failure path is covered; the user's exact post-match failure has not been captured on their server.
