#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

/*
 * Analytic arraylist glow.
 *
 * A gaussian "shadow" of a rectangle is the convolution of the rectangle's indicator function with a
 * separable gaussian. Because the kernel is separable, the two dimensional coverage is the product of
 * the one dimensional coverages, and the one dimensional coverage of an interval [a, b] is exactly
 *
 *     C(x) = PHI((b - x) / sigma) - PHI((a - x) / sigma)      with PHI = standard normal CDF
 *
 * So the glow is evaluated directly in closed form instead of stacking scaled, alpha-faded copies of
 * the rectangle on top of each other. One quad, one pass, and the result is the mathematically exact
 * gaussian blur of the row rect (which is what ImDrawList::AddShadowRect ends up approximating with a
 * blurred texture).
 */
layout(location = 0) in vec2 vOffset;
layout(location = 1) in vec4 vColor;
layout(location = 2) flat in ivec2 vHalfSize;
layout(location = 3) in float vSigma;

layout(location = 0) out vec4 fragColor;

// Abramowitz & Stegun 7.1.26, |error| < 1.5e-7
float erfApprox(float x) {
    // NOTE: `sign`, `half` etc. are reserved/built-in names in GLSL - do not use them as identifiers.
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

// Coverage of the interval [-extent, extent] at position p, blurred by a gaussian of the given sigma.
float axisCoverage(float p, float extent, float sigma) {
    return normalCdf((extent - p) / sigma) - normalCdf((-extent - p) / sigma);
}

void main() {
    float sigma = max(vSigma, 0.001);
    vec2 halfSize = max(vec2(vHalfSize), vec2(0.0));

    float coverage = axisCoverage(vOffset.x, halfSize.x, sigma)
        * axisCoverage(vOffset.y, halfSize.y, sigma);

    vec4 color = vColor * ColorModulator;
    color.a *= coverage;

    if (color.a <= 0.002) {
        discard;
    }

    fragColor = color;
}
