# 086 optical material references and attribution

Production `light_pollution_glass.fsh` uses the bounded optical falloff equation and low-frequency diffusion approach adapted from:

- Jack (Jacquesqwq), LiquidGlassShader, MIT license.
- Pinned commit: `4bf62de7c89c285d997cd457daac8ee50837a738`.
- https://github.com/Jacquesqwq/LiquidGlassShader/blob/4bf62de7c89c285d997cd457daac8ee50837a738/liquidGlassV2Tinted.frag
- Exact fetched source preserved as `upstream-liquidGlassV2Tinted.frag`; MIT copyright/permission notice in `LICENSE-MIT.txt`.

The boundary-normal adaptation was also studied in NeoRaven's GPLv3 Onyx frost transport:

- https://github.com/Matr1ze/NeoRaven/blob/b483ffc576e335e01f2914c920e88289ab090e66/src/main/resources/assets/onyx/shaders/core/onyx_frost.fsh
- Exact fetched source preserved as `neoraven-onyx_frost.fsh`, including its upstream MIT attribution.
- NeoRaven GPLv3 license and project attribution are already preserved in `licenses/neoraven/`.

This adaptation targets the existing LiquidBounce GLSL 330 rounded GUI vertex format, retained framebuffer capture and explicit per-element material pipeline. The original GLSL 120 fixed-function transport is not copied into the runtime.

These public implementations are optical implementation references only. They are NOT evidence that the supplied screenshots were produced by these repositories or that 086 already matches those screenshots exactly. No such provenance or pixel-parity claim is made.
