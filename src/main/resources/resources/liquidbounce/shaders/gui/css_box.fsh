#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec2 vLocalPos;
layout(location = 1) in vec4 vColor;
layout(location = 2) flat in ivec2 vEncodedSize;
layout(location = 3) flat in ivec2 vEncodedRadii;
layout(location = 4) in float vMode;

layout(location = 0) out vec4 fragColor;

float selectCornerRadius(vec2 p, float rTL, float rTR, float rBR, float rBL) {
    if (p.x > 0.0) {
        return (p.y > 0.0) ? rBR : rTR;
    } else {
        return (p.y > 0.0) ? rBL : rTL;
    }
}

float sdCssRoundedBox(vec2 p, vec2 halfSize, float rTL, float rTR, float rBR, float rBL) {
    float maxR = min(halfSize.x, halfSize.y);
    float r = clamp(selectCornerRadius(p, rTL, rTR, rBR, rBL), 0.0, maxR);
    vec2 q = abs(p) - halfSize + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2 size = max(vec2(vEncodedSize) * 0.0625, vec2(0.0625));
    vec2 halfSize = size * 0.5;

    float rTL = float((vEncodedRadii.x >> 7) & 127) * 0.5;
    float rTR = float(vEncodedRadii.x & 127) * 0.5;
    float rBR = float((vEncodedRadii.y >> 7) & 127) * 0.5;
    float rBL = float(vEncodedRadii.y & 127) * 0.5;

    float d = sdCssRoundedBox(vLocalPos, halfSize, rTL, rTR, rBR, rBL);
    float aa = max(fwidth(d), 1e-3);
    float alpha;

    if (vMode < -0.001) {
        // Analytical soft CSS box-shadow falloff (sigma = -vMode)
        float sigma = -vMode;
        float t = clamp(1.0 - (d + sigma * 0.25) / max(sigma * 2.1, 0.001), 0.0, 1.0);
        alpha = t * t * (3.0 - 2.0 * t);
    } else if (vMode > 0.001) {
        // Crisp SDF inner border / inset spread stroke
        float outerMask = 1.0 - smoothstep(-aa * 0.5, aa * 0.5, d);
        float innerMask = smoothstep(-vMode - aa * 0.5, -vMode + aa * 0.5, d);
        alpha = outerMask * innerMask;
    } else {
        // Anti-aliased fill
        alpha = 1.0 - smoothstep(-aa * 0.5, aa * 0.5, d);
    }

    vec4 color = vColor * ColorModulator;
    color.a *= alpha;
    if (color.a <= 0.001) {
        discard;
    }

    fragColor = color;
}
