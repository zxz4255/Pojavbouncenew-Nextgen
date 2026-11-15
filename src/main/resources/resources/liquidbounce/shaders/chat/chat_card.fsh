#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec2 vUv;
layout(location = 1) in vec4 vColor;
layout(location = 2) flat in ivec2 vSize;
layout(location = 3) flat in ivec2 vParameters;
layout(location = 4) in float vStrokeWidth;

layout(location = 0) out vec4 fragColor;

float sdRoundBox(vec2 p, vec2 halfSize, float radius) {
    float r = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2 size = max(vec2(vSize), vec2(1.0));
    vec2 halfSize = size * 0.5;
    vec2 p = (vUv - vec2(0.5)) * size;
    float d = sdRoundBox(p, halfSize, max(float(vParameters.x), 0.0));
    float aa = max(fwidth(d), 1e-4);
    float coverage = 1.0 - smoothstep(-aa, 0.0, d);

    vec4 color = vColor * ColorModulator;
    int surface = vParameters.y;
    if (surface == 1 || surface == 2) {
        float exponent = surface == 1 ? 2.6 : 4.0;
        float amount = surface == 1 ? 0.10 : 0.055;
        float topSheen = pow(clamp(1.0 - vUv.y, 0.0, 1.0), exponent);
        color.rgb = mix(color.rgb, vec3(0.92, 0.96, 1.0), topSheen * amount);
    }

    if (vStrokeWidth > 0.0) {
        float innerCoverage = 1.0 - smoothstep(-aa, 0.0, d + vStrokeWidth);
        float stroke = max(coverage - innerCoverage, 0.0);
        vec3 edgeColor = surface == 0 ? vec3(0.63, 0.70, 0.82) : vec3(0.86, 0.92, 1.0);
        color.rgb = mix(color.rgb, edgeColor, clamp(stroke * 0.72, 0.0, 0.72));
    }

    color.a *= coverage;
    if (color.a <= 0.001) discard;
    fragColor = color;
}
