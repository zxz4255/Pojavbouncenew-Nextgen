# 087 inherited-member compile regression

This supplements, and does not silently rewrite, the historical 085 verifier.

## Run

Use the same JDK25/Kotlin2.4.20/MC26.3/VFP5.1.1 toolchain and environment described in `../verify085/README.md`:

```sh
export JAVA_HOME=/path/to/jdk25
export MC_JAR=/path/to/mc26.3.jar
export MC_LIBS=/path/to/manifest-libraries
export VERIFY_LIBS=/path/to/vfp-and-asm-libraries
export KOTLIN_LIBS=/path/to/kotlin-compiler-jars
export BASELINE086=/path/to/Pojavbouncenew-Nextgen-086-LIGHT-POLLUTION-GLASS-CANDIDATE.zip
bash scripts/verify087/run.sh
```

`inherit-shortcuts.py` patches only temporary generated hosts so `ClientModule` implements the real production `MinecraftShortcuts` interface. Real `MinecraftShortcuts.kt` and `AddonApi.kt` are compiled alongside six complete production NeoAura Kotlin sources and the original exact integration excerpts. Project config/event/aiming/attack facades remain the explicit compile hosts documented by 085. This is not compilation of the entire real `ClientModule` implementation or the whole application.

The immutable086 negative control must fail with exactly two compiler errors, one per reported file, identifying `world` hiding `MinecraftShortcuts.world`. The current sources must then compile in that same environment. This prevents the original missing-inheritance host from producing another false-green result for this particular bug.

The actual API reference audit and production pure-logic regression are rerun after positive compilation. Neither test starts Minecraft, applies Mixins, connects to a server, or tests Android hardware.

## Package

```sh
python3 scripts/verify087/audit-package.py /path/to/immutable086.zip /path/to/087.zip
```

Explicitly verifies the only baseline source modifications are the two field renames, excludes build/cache outputs, includes all other baseline bytes unchanged, and validates ZIP CRC and every archive entry. New content is limited to the 087 status document and this verification directory.
