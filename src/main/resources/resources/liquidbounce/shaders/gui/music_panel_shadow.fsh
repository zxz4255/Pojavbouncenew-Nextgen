#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

/*
 * Music panel shadow, fragment stage.
 *
 * Two things make the halo look soft rather than built out of bands:
 *
 *   1. The distance to the card silhouette is an exact rounded-box SDF, so the falloff follows the
 *      corner radius instead of the axis-aligned quad edges.
 *   2. The falloff itself is a gamma-shaped profile (`t^2 * exp(-t^2)`), which has zero slope at the
 *      silhouette, peaks just outside it and dies out smoothly at the edge of the blur. A plain
 *      gaussian is too hot right at the card edge and reads as a hard line there.
 *
 * On top of that the result is dithered, because a large, very low-alpha halo on an 8 bit target
 * bands visibly even when the maths is continuous.
 */

layout(location = 0) in vec2 vOffset;
layout(location = 1) in vec4 vColor;
layout(location = 2) flat in ivec2 vHalfSize;
layout(location = 3) flat in ivec2 vParams;
layout(location = 4) in float vSigma;

layout(location = 0) out vec4 fragColor;

float sdRoundBox(vec2 p, vec2 halfSize, float radius) {
    float r = clamp(radius, 0.0, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// 4x4 ordered dither. Written out instead of as nested bayer2() calls: the recursion is correct but
// impossible to read, and this is the one place where "did I get the matrix right" decides whether
// the halo bands.
float dither4(vec2 fragCoord) {
    int x = int(mod(fragCoord.x, 4.0));
    int y = int(mod(fragCoord.y, 4.0));
    int index = x + y * 4;
    float matrix[16] = float[16](
         0.0,  8.0,  2.0, 10.0,
        12.0,  4.0, 14.0,  6.0,
         3.0, 11.0,  1.0,  9.0,
        15.0,  7.0, 13.0,  5.0
    );
    return (matrix[index] + 0.5) / 16.0;
}

void main() {
    vec2 halfSize = max(vec2(vHalfSize) * 0.125, vec2(0.125));
    float radius = float(vParams.x) * 0.125;
    int curve = vParams.y;

    // Only the outside of the card emits shadow; the card itself is drawn over this anyway.
    float outside = max(sdRoundBox(vOffset, halfSize, radius), 0.0);
    float sigma = max(vSigma, 0.001);
    float t = outside / sigma;

    float coverage;
    if (curve == 1) {
        // Wide, low-contrast halo (ambient occlusion beneath the panel).
        coverage = exp(-0.5 * t * t);
    } else if (curve == 2) {
        // Tight key shadow: steeper, dies out within roughly one sigma.
        coverage = exp(-1.6 * t * t) * (1.0 - smoothstep(1.0, 2.6, t));
    } else {
        // Default: soft material profile. Starts flat at the silhouette so no ring forms there.
        float soft = t * t * exp(-t * t) * 2.0;
        coverage = soft + 0.12 * exp(-0.35 * t * t);
    }
    coverage = clamp(coverage, 0.0, 1.0);

    vec4 color = vColor * ColorModulator;
    float alpha = color.a * coverage;
    alpha += (dither4(gl_FragCoord.xy) - 0.5) / 255.0;
    if (alpha < 0.002) discard;
    fragColor = vec4(color.rgb, clamp(alpha, 0.0, 1.0));
}
