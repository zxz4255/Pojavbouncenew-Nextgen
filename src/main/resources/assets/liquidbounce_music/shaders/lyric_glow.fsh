#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;
layout(std140) uniform MonetLyricGlowData {
    vec4 GlowParams;
    vec4 GlowColor;
};
layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 0) out vec4 fragColor;

// Positive signed distance is inside the glyph. Core and halo share this exact contour.
void main() {
    float distancePx = (texture(Sampler0, texCoord0).r - 0.5) * 64.0;
    float aa = max(fwidth(distancePx), 0.65);
    float core = smoothstep(-aa, aa, distancePx) * vertexColor.a;
    float radiusPx = max(GlowParams.w * 2.0, 0.5);
    float softness = max(GlowParams.y, 0.15);
    float exterior = max(-distancePx, 0.0);
    float halo = exp(-pow(exterior / radiusPx, 1.0 + softness * 0.35));
    halo *= (1.0 - core) * GlowParams.x * 0.48 * mix(1.0, 1.25, GlowParams.z);
    halo *= 1.0 - smoothstep(24.0, 30.0, exterior);
    halo = clamp(halo * GlowColor.a, 0.0, 0.90);
    float alpha = core + halo * (1.0 - core);
    if (alpha < 0.003) discard;
    vec3 rgb = (vertexColor.rgb * core + GlowColor.rgb * halo * (1.0 - core)) / alpha;
    fragColor = vec4(rgb, alpha) * ColorModulator;
}
