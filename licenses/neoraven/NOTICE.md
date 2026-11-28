# NeoRaven Cinematic / Rain Reflections attribution

Upstream: https://github.com/Matr1ze/NeoRaven
Pinned revision: `b483ffc576e335e01f2914c920e88289ab090e66`
Original project/authorship: NeoRaven contributors, repository published by Matr1ze.
License: GNU General Public License version 3; original license text is in `LICENSE` alongside this notice. Preserve the upstream notices and the destination LiquidBounce GPL notices on redistribution.

## Derived implementation in this source release (074)

- `features/module/modules/render/ModuleCinematic.kt` and `ModuleRainReflections.kt`: native module lifecycle and settings corresponding to the original two Java modules.
- `features/module/modules/render/neoraven/NeoCinematicRenderer.kt`, `NeoRainRenderer.kt`, `NeoRavenState.kt`, `NeoRavenPostEffects.kt`: MC 26.3 renderpearl integration, ordering, exposure state, rain exposure map and GPU resources, adapted from the upstream Java implementations.
- `resources/liquidbounce/shaders/neoraven/{cinematic_light,cinematic_high,rain_trace,rain_composite}.fsh`: original shader kernels adapted to GLSL 330, uniform blocks and modern depth/height encoding.
- `downsample.fsh`: explicit bloom mip-chain helper replacing legacy OpenGL automatic mip generation, which has no matching exposed command in the target render API.

The archive contains the original seven module/renderer/shader source files under `scripts/verify074/upstream/`, together with source paths, revision and SHA-256 hashes in `manifest.json`. The selected original settings are recorded in `settings.json`. Those original files have NOT been relabeled or modified.

## Changes / compatibility boundaries

This is a source port, not a binary copy of the Forge 1.8.9 renderer. Legacy GL state and framebuffer-copy code are replaced by native render pipelines and render targets. Modern reversed/zero-to-one depth and negative/custom world heights are supported. The rain height map uses nearest-filtered RG16-packed height plus an alpha wet mask instead of the legacy luminance/alpha 8-bit texture. Missing chunks are not loaded from a render callback. Rain skips active Iris shader packs (conservative skip if the bridge cannot be queried). Cinematic's high bloom uses five explicit downsampled levels rather than driver-generated mipmaps, so exact numeric/visual identity is not guaranteed, especially for odd dimensions and different drivers. All screen-space reflection visibility limitations remain.

Verification fixtures compare synthetic frames and retained kernel text; they do not establish target-game, Mixin, Android/Pojav, mobile GPU or pixel-exact scene parity. See `074-NEORAVEN-CINEMATIC-RAIN.md` and `scripts/verify074/README.md`.
