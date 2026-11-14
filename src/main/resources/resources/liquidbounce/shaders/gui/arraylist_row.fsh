#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

/*
 * Arraylist row surface.
 *
 * A signed distance field rounded box instead of a stack of quads, which gives three things the
 * plain rect fill cannot:
 *
 *  1. a real anti-aliased edge at any GUI scale (the SDF gradient is resolution independent),
 *  2. a per-vertex vertical gradient - the palette colour of this row on the top edge, the next
 *     palette stop on the bottom edge, interpolated by the rasterizer,
 *  3. a top inner highlight plus ordered dithering, so large flat translucent areas do not band on
 *     the 8 bit target the HUD renders into.
 *
 * The row is deliberately darker than the palette colour: translucent black glass tinted by the
 * scheme, so text stays readable on top of a bright sky.
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

// Cheap 4x4 ordered dither, enough to break up gradient banding without visible noise.
float bayer2(vec2 a) {
    a = floor(a);
    return fract(a.x / 2.0 + a.y * a.y * 0.75);
}

float bayer4(vec2 a) {
    return bayer2(0.5 * a) * 0.25 + bayer2(a);
}

void main() {
    vec2 size = max(vec2(vSize) / 16.0, vec2(1.0));
    vec2 halfSize = size * 0.5;
    vec2 p = (vUv - vec2(0.5)) * size;

    float radius = float(vParams.x) / 16.0;
    float dist = sdRoundBox(p, halfSize, radius);

    float edge = max(fwidth(dist) * 1.25, 1e-4) * 0.5;
    float coverage = 1.0 - smoothstep(-edge, edge, dist);
    if (coverage <= 0.001) {
        discard;
    }

    // 0 at the top edge, 1 at the bottom edge.
    float v = clamp((p.y + halfSize.y) / max(size.y, 1e-3), 0.0, 1.0);

    vec3 rgb = vColor.rgb;

    // Inner top highlight: a thin brighter band hugging the top edge, which is what makes a flat
    // translucent panel read as a surface instead of a sticker.
    float highlight = vStyle;
    if (highlight > 0.0) {
        float band = exp(-v * 6.0);
        rgb += vec3(band * 0.16 * highlight);
        // Bottom shading keeps the two edges visually distinct.
        rgb -= vec3(smoothstep(0.55, 1.0, v) * 0.06 * highlight);
    }

    // Slight vertical falloff so the surface never looks like a solid fill.
    rgb *= 1.0 - 0.10 * v;

    // Ordered dither on the 8 bit target.
    rgb += (bayer4(gl_FragCoord.xy) - 0.5) / 255.0;

    vec4 color = vec4(rgb, vColor.a) * ColorModulator;
    color.a *= coverage;

    if (color.a <= 0.002) {
        discard;
    }

    fragColor = color;
}
