# 086 reproducible verification (bounded, not full acceptance)

## Actual API isolated compilation

Use JDK 25, Kotlin compiler 2.4.20 and actual MC 26.3 manifest libraries as described in `../verify085/README.md`.

```sh
export JAVA_HOME=/path/to/jdk25
export MC_JAR=/path/to/official/mc26.3.jar
export MC_LIBS=/path/to/manifest-libraries
export VERIFY_LIBS=/path/to/additional-jars  # include asm.jar, asm-tree.jar
export KOTLIN_LIBS=/path/to/kotlin-compiler-jars
bash scripts/verify086/compile.sh
```

`hosts.py` supplies only explicitly listed project services: config value facades, palette inputs, draw-call wrappers, the pooled-pose facade, backdrop request facade, pipeline factory return values and the sealed resource-owner interface. Minecraft/RenderPearl/JOML/VertexConsumer/GuiElementRenderState are actual jars. The two production Java records and three production Kotlin surface emitters compile in full. Only the exact new Onyx adapter method and enum bodies are extracted; their entire containing modules/UI files and the full pipeline registration DSL are NOT certified by this isolated compile.

Three already-existing GUI access-widener declarations are verified from the production AW file and applied to real MC classes in a temporary compilation overlay, never shipped. `VertexRegression` executes the actual production record's vertex-emission code with a dynamic-proxy consumer implementing the real MC interface. It does not start a renderer.

## GLSL

```sh
python3 -m pip install moderngl numpy pillow
python3 scripts/verify086/shader-test.py
```

Requires an EGL provider (the recorded run used Mesa llvmpipe). Expands the shared production SDF include; substitutes explicit uniform declarations only for the Minecraft transform include blocks. Compiles and links both actual production vertex/fragment shaders, then runs 653 numeric assertions on synthetic background samples. No Android GPU, game framebuffer scheduling, same-scene image comparison or live optical fidelity claim follows from this.

`synthetic-shader-test.png` is an INTERNAL synthetic test artifact, NOT a Minecraft screenshot, user deliverable preview or visual-parity proof.

## Preserved regressions / packaging

`neo-results.txt` and `cursor-results.txt` are reruns of the existing bounded 085/084 verifiers on this source tree, with their original limitations retained. Do not modify historical verification records to pretend they were full-build/live tests.

```sh
python3 scripts/verify086/audit-package.py /path/to/immutable085.zip /path/to/086.zip
```

Packages the full source tree by immutable baseline plus explicit allowlists, checks every archive byte against working sources and all unlisted baseline bytes, excludes generated directories, checks CRC and records SHA-256/MD5/Dropbox content hash outside the archive. Existing generic all-language verification still fails on inherited missing translations; only the scoped JSON/term changes are certified here.
