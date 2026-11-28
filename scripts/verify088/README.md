# 088 bounded verification

## Run

Use the actual JDK25 / Kotlin2.4.20 / Minecraft26.3 / VFP5.1.1 setup from `../verify085/README.md`:

```sh
export JAVA_HOME=/path/to/jdk25
export MC_JAR=/path/to/official-mc26.3.jar
export MC_LIBS=/path/to/manifest-libraries
export VERIFY_LIBS=/path/to/vfp-and-asm-jars
export KOTLIN_LIBS=/path/to/kotlin-compiler-jars
bash scripts/verify088/run.sh
```

The three complete production MVP sources compile with real production `MinecraftShortcuts.kt`, real `AddonApi.kt`, the production VFP1.8 bridge compiled against real VFP/ViaVersion and actual Minecraft/RenderPearl/JOML types. The VFP protocol-selector facade, project module/config/event/friend services and UiDraw bodies are explicit compile hosts in `hosts.py`; they are NOT a build of those real enclosing project implementations. UiDraw parameter lists are extracted from current source so mismatched public calls fail this check. No Minecraft API type is substituted.

The pure runtime regression has only the compiled production classes and Kotlin stdlib on its runtime classpath; it never starts Minecraft or exercises the network adapter/rendering. It tests the production evidence tracker, deduplication/expiry/capacity/reset, timer/replacement/CSS cubic curve and Unicode ellipsis logic (51,039 assertions).

The 085 actual-member auditor is reused without modifying its historical file, scoped to the new production package (58 actual member references). Structural checks validate the module registration, nine settings, packet cancellation/direction and main-thread/epoch guards, player/friend/team/HUD conditions, JSON parsing/unique keys and recorded reference constants. Structural checks are not live event-dispatch tests.

`licenses/cs2mvp088/source-spec.json` contains factual reference measurements and hashes, not proprietary game assets. Matching those constants does not establish screenshot or frame-perfect parity. The original mask/font/shadow/optional audio are not certified or bundled.

## Scope/package

```sh
python3 scripts/verify088/audit-package.py /path/to/immutable087.zip /path/to/088.zip
```

Checks all original087 bytes, allows only registration and three language-file extensions, adds the three standalone production files and explicit088 documentation/tests/reference metadata, excludes generated directories, verifies CRC and compares every archive entry. The old native cursor, NeoAura collision fix, glass shaders, models and unrelated modules remain byte-identical.

There is no complete Gradle/Mixin/Minecraft/Android/server or image-difference acceptance test here. Read the root088 status document before reporting this as complete visual reproduction.
