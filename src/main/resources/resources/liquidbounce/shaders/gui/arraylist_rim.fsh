#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

/*
 * Arraylist rim light.
 *
 * An SDF stroke that follows the rounded row exactly - an inner ring rather than an inset rect, so
 * the corners stay round and the line keeps a constant width at every GUI scale. The stroke colour
 * is the palette colour of the row (per vertex, so it carries the same vertical gradient as the
 * surface) and it fades out towards the left edge, which makes the list look lit from the right -
 * the side the accent bar sits on.
 *
 * LineWidth is the stroke thickness in GUI units.
 */
layout(location = 0) in vec2 vUv;
layout(location = 1) in vec4 vColor;
layout(location = 2) flat in ivec2 vSize;
layout(location = 3) flat in ivec2 vParams;
layout(location = 4) in float vStyle;

layout(location = 0) out vec4 fragColor;

float sdRoundBox(vec2 p, vec2 halfSize, float radius) {
    float r = clamp(radius, 0.0, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + vec2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - r;
}

void main() {
    vec2 size = max(vec2(vSize) / 16.0, vec2(1.0));
    vec2 halfSize = size * 0.5;
    vec2 p = (vUv - vec2(0.5)) * size;

    float radius = float(vParams.x) / 16.0;
    float dist = sdRoundBox(p, halfSize, radius);

    float thickness = max(vStyle, 0.4);
    // Inner ring of the given thickness, measured from the shape edge inwards.
    float ring = abs(dist + thickness) - thickness;

    float edge = max(fwidth(dist) * 1.25, 1e-4) * 0.5;
    float alpha = 1.0 - smoothstep(-edge, edge, ring);
    if (alpha <= 0.002) {
        discard;
    }

    // Lit from the accent side: full strength at the right edge, fading towards the left.
    float horizontal = smoothstep(0.0, 0.85, vUv.x);
    float vertical = 1.0 - 0.25 * abs(vUv.y - 0.5) * 2.0;

    vec4 color = vColor * ColorModulator;
    color.a *= alpha * horizontal * vertical;

    fragColor = color;
}
