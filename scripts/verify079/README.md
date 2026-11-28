# Focused 079 regression suite

Requires JDK 25, Kotlin compiler (tested 2.4.20), Python 3 and Gson jar (tested 2.14.0). No network/account required to run tests. Set VERIFY_LIBS to the directory containing Gson/dependency jars:

```sh
JAVA_HOME=/path/to/jdk25 \
KOTLINC=/path/to/kotlinc/bin/kotlinc \
VERIFY_LIBS=/path/to/jars \
bash scripts/verify079/run.sh > scripts/verify079/results.txt 2>&1
```

The runner bounds heaps, builds into a temporary directory, tests against temporary config roots and cleans up. Test-only native/UI/Minecraft host stubs are reused unmodified from verify078; no stubs enter production sources.

- `KggCipherRegression.java`: 120 algorithm/container assertions, independent v1/v2 TEA-derived keys, Map/RC4 fixtures, pinned public v3 sample byte equality, known masks, chunk offsets, bounds/cancellation/output cleanup.
- `KggIntegrationRegression.kt`: 35 assertions for explicit JSON/MMKV input, batch import, local identity persistence, no local fallback, actual Manager loopback HTTP encrypted download, plaintext digest/proof and missing/wrong keys. Native audio preflight is a deliberately failing stub when checking file preservation.
- `ProtocolRegression.kt`, `ManagerRegression.kt`, `IslandRegression.kt`: 078 cases copied for 079, 24+18 scenarios and 8 Island combinations/80 disabled cycles. Protocol URL-format expectations now intentionally allow secure encrypted-file suffixes; they are not assumed to be playable until content decryption and codec validation succeed. The historical 078 suite and results remain unmodified and describe 078 behavior; its old encrypted-suffix rejection assertions are superseded by 079.
- `check-ui-wiring.py`: static real MusicScreen/ModuleMusic source wiring only, not their full compilation or a rendered test.

## Reproduce synthetic fixtures

```sh
python3 scripts/verify079/make-vectors.py
```

Uses only Python standard library and selected algorithm definitions from the pinned Python source under references/kgg079. It performs independent encryption and validates key derivation using the Python oracle before writing four deterministic synthetic files. They contain a FLAC marker + pattern bytes, **not valid playable music**. Manifest eKeys and raw keys are synthetic test data, never user credentials. Whole-file plaintext SHA-256 is compared after Java decryption; all keys and bytes are reproducible. Published Map/RC4 golden masks and v3 expected bytes are also checked independently of fixture generation.

No real user KGG, real VIP account, native codec playback, full Gradle build, game/Pojav or GPU behavior was tested. Passing the suite is not universal file compatibility.

## Scope audit / package

`audit-package.py BASELINE078.zip OUTPUT079.zip` validates the immutable delivered078 SHA-256, permits exactly five changed music production files, and additions only for two new production helpers and 079 docs/reference/tests. Every other baseline file (including Island, knives, inventory, chests, ClickGUI and old logs/tests) must remain byte-identical. It writes scope-audit.json, creates a flat source-root archive and checks its CRC/content hashes. It intentionally does not include unrelated generated cache files from the workspace.
