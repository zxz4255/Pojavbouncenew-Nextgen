#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec2 vOffset;
layout(location = 1) in vec4 vColor;
layout(location = 2) flat in ivec2 vHalfSize;
layout(location = 3) flat in ivec2 vParams;
layout(location = 4) in float vSigma;
layout(location = 0) out vec4 fragColor;

// Signed distance to the actual rounded silhouette, in transformed GUI coordinates.
float roundedDistance(vec2 p, vec2 halfSize, float radius) {
    float r = clamp(radius, 0.0, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2 halfSize = max(vec2(vHalfSize) * 0.125, vec2(0.125));
    float radius = float(vParams.x) * 0.125;
    float d = max(roundedDistance(vOffset, halfSize, radius), 0.0);
    float t = d / max(vSigma, 0.001);
    // Smooth gaussian-shaped falloff, sampled continuously in one pass, not stacked rings.
    float coverage = exp(-0.5 * t * t);
    vec4 color = vColor * ColorModulator;
    color.a *= coverage;
    if (color.a < 0.00001) discard;
    fragColor = color;
}
