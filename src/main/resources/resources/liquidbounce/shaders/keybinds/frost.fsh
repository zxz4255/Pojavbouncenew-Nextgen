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
    float r = min(float(vParameters.x), min(halfSize.x, halfSize.y));
    vec2 q = abs((vLocal - 0.5) * size) - halfSize + vec2(r);
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    float aa = max(fwidth(d), 1e-4);
    float mask = 1.0 - smoothstep(-aa, 0.0, d);
    vec4 backdrop = texture(Sampler0, vScreen);
    vec4 color = vColor * ColorModulator;
    // Backdrop alpha permits the opaque fallback card to show on a failed/empty capture.
    fragColor = vec4(mix(backdrop.rgb, color.rgb, 0.55), color.a * mask * backdrop.a);
    if (fragColor.a <= 0.001) discard;
}
