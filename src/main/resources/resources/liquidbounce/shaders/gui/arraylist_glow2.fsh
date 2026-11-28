#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

/*
 * Arraylist glow, palette edition.
 *
 * Same closed-form gaussian as the original analytic glow (the convolution of the row rect with a
 * separable gaussian is the product of two normal CDF differences, so one quad is enough), extended
 * with two effects that make the palette drive the glow too:
 *
 *  1. Chromatic dispersion - the red, green and blue channels are evaluated with slightly different
 *     sigmas. On a saturated palette colour this puts a faint prismatic fringe on the falloff
 *     instead of the flat monochrome halo every client has.
 *  2. Rim boost - coverage in the 0.35..0.85 band (i.e. right around the shape edge) is brightened,
 *     which reads as light being emitted by the edge rather than a blurred copy of the rect.
 *
 * The glow colour comes from the per-vertex colour, so a pair of quads (inner tone, outer tone)
 * produces a two-colour bloom that follows the active scheme.
 */
layout(location = 0) in vec2 vOffset;
layout(location = 1) in vec4 vColor;
layout(location = 2) flat in ivec2 vHalfSize;
layout(location = 3) in float vSigma;

layout(location = 0) out vec4 fragColor;

// Abramowitz & Stegun 7.1.26, |error| < 1.5e-7
float erfApprox(float x) {
    float sgn = x < 0.0 ? -1.0 : 1.0;
    float ax = abs(x);

    float t = 1.0 / (1.0 + 0.3275911 * ax);
    float poly = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t
        + 0.254829592) * t;
    float y = 1.0 - poly * exp(-ax * ax);

    return sgn * y;
}

float normalCdf(float x) {
    return 0.5 * (1.0 + erfApprox(x * 0.70710678));
}

float axisCoverage(float p, float extent, float sigma) {
    return normalCdf((extent - p) / sigma) - normalCdf((-extent - p) / sigma);
}

float coverage(vec2 offset, vec2 halfSize, float sigma) {
    return axisCoverage(offset.x, halfSize.x, sigma) * axisCoverage(offset.y, halfSize.y, sigma);
}

void main() {
    float sigma = max(vSigma, 0.001);
    vec2 halfSize = max(vec2(vHalfSize), vec2(0.0));

    float base = coverage(vOffset, halfSize, sigma);

    // Dispersion: +-8% sigma per channel, which is invisible on grey and clearly visible on the
    // saturated scheme colours.
    float chroma = clamp(vColor.a, 0.0, 1.0) * 0.08;
    vec3 dispersed = vec3(
        coverage(vOffset, halfSize, sigma * (1.0 - chroma)),
        base,
        coverage(vOffset, halfSize, sigma * (1.0 + chroma))
    );

    // Rim boost around the shape edge.
    float rim = smoothstep(0.30, 0.62, base) * (1.0 - smoothstep(0.78, 1.0, base));

    vec3 rgb = vColor.rgb * dispersed * (1.0 + rim * 0.45);

    vec4 color = vec4(rgb, 1.0) * ColorModulator;
    color.a = base * vColor.a * ColorModulator.a;

    if (color.a <= 0.002) {
        discard;
    }

    fragColor = color;
}
