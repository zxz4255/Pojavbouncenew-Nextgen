# Focused 078 regression harness

Run from the project root with JDK 25, Kotlin compiler 2.4.20 and local dependency jars:

```sh
JAVA_HOME=/path/to/jdk25 \
KOTLINC=/path/to/kotlinc/bin/kotlinc \
VERIFY_LIBS=/path/to/dependency-jars \
bash scripts/verify078/run.sh > scripts/verify078/results.txt 2>&1
```

The compiler's classpath is all `.jar` files in VERIFY_LIBS. The focused source set uses Gson (tested 2.14.0); this run supplied the broader local Minecraft dependency jar set. UI/native host APIs are provided by the included stubs, not the Minecraft binary. Harness uses a temporary directory and deletes it on exit, and Java heap is bounded. Kotlin heap defaults to 320 MB. It does not download dependencies or contact KuGou.

`ProtocolRegression.kt`: 24 scenarios for signatures, bodies, routing, identity, quality selection, typed response errors and QR builder consistency.
`ManagerRegression.kt`: 18 scenarios for cache identity, persistence, classification, file signatures, parser preflight and loopback HTTP transport/download cleanup. Native decoder and AudioEngine are stubs; FLAC header fixture is not decoded music.
`IslandRegression.kt`: 8 track/chat/return combinations, 80 disabled cycles and hidden input/snapshot; preserves current track and permits explicit re-enable. UI/Minecraft are stubs; actual Island state logic is compiled.
`check-ui-wiring.py`: source assertions for real ModuleMusic/MusicScreen. This is not a compiler or visual regression test; these two full production classes are outside the focused compilation.

Stubs are test-only and are never included in production sources. The result is NOT full Gradle success, device codec validation, Pojav/GPU behavior or real VIP playback.

`audit-package.py` requires the immutable delivered 077 ZIP path and output ZIP path as positional arguments. It permits exactly seven existing music-related production-file changes, one new production helper, and additions only under the 078 documentation/reference/test paths. Every other baseline file must be byte-identical; no deletions are allowed. It excludes generated workspace caches by selecting only approved additions and the baseline manifest. It writes `scope-audit.json` and packages a flat source root, then checks archive integrity and records hashes beside the ZIP.
