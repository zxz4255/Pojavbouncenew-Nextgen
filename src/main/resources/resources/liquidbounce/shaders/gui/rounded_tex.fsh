#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec2 vLocalPos;
layout(location = 1) in vec4 vColor;
layout(location = 2) flat in ivec2 vEncodedSize;
layout(location = 3) flat in ivec2 vEncodedUvBounds;
layout(location = 4) in float vRadius;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 size = max(vec2(vEncodedSize) * 0.0625, vec2(0.0625));
    vec2 halfSize = size * 0.5;
    float r = clamp(vRadius, 0.0, min(halfSize.x, halfSize.y));

    vec2 q = abs(vLocalPos) - halfSize + vec2(r);
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    float aa = max(fwidth(d), 1e-3);
    float mask = 1.0 - smoothstep(-aa * 0.5, aa * 0.5, d);
    if (mask <= 0.001) {
        discard;
    }

    vec2 quadUv = clamp(vLocalPos / size + vec2(0.5), 0.0, 1.0);
    float u0 = float((vEncodedUvBounds.x >> 7) & 127) / 64.0;
    float v0 = float(vEncodedUvBounds.x & 127) / 64.0;
    float u1 = float((vEncodedUvBounds.y >> 7) & 127) / 64.0;
    float v1 = float(vEncodedUvBounds.y & 127) / 64.0;
    vec2 texUv = mix(vec2(u0, v0), vec2(u1, v1), quadUv);

    vec4 texel = texture(Sampler0, texUv);
    vec4 color = texel * vColor * ColorModulator;
    color.a *= mask;
    if (color.a <= 0.001) {
        discard;
    }

    fragColor = color;
}
