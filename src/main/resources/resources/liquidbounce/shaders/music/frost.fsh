#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
layout(location = 0) in vec2 vLocal;
layout(location = 1) in vec4 vColor;
layout(location = 2) flat in ivec2 vSize;
layout(location = 3) flat in ivec2 vParameters;
layout(location = 4) in vec2 vScreen;
layout(location = 0) out vec4 fragColor;
uniform sampler2D Sampler0;
void main() {
    vec2 size = max(vec2(vSize), vec2(1.0));
    vec2 halfSize = size * 0.5;
    float r = clamp(float(vParameters.x), 0.0, min(halfSize.x, halfSize.y));
    vec2 q = abs((vLocal - 0.5) * size) - halfSize + vec2(r);
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    float aa = max(fwidth(d), 1e-4);
    float mask = 1.0 - smoothstep(-aa, 0.0, d);
    // A small edge displacement approximates refraction, not physical liquid optics.
    vec2 screenSize = vec2(textureSize(Sampler0, 0));
    vec2 normal = normalize((vLocal - 0.5) * size + vec2(1e-4));
    float edge = 1.0 - smoothstep(0.0, 18.0, max(0.0, -d));
    vec2 uv = clamp(vScreen + normal * edge * 1.2 / screenSize, vec2(0.0), vec2(1.0));
    vec4 backdrop = texture(Sampler0, uv);
    vec4 tint = vColor * ColorModulator;
    fragColor = vec4(mix(backdrop.rgb, tint.rgb, 0.48), tint.a * mask * backdrop.a);
    if (fragColor.a <= 0.001) discard;
}
