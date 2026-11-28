#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;       // Center-relative local position in GUI px
layout(location = 2) in vec4 Color;     // Glass tint / shadow color / specular rim color
layout(location = 3) in ivec2 UV1;      // (round(width * 8), round(height * 8))
layout(location = 4) in ivec2 UV2;      // x: (rTL_7b << 7) | rTR_7b, y: (rBR_7b << 7) | rBL_7b
layout(location = 5) in float LineWidth;// < 0: fused shadow (-spread), 1..65: glass body (opacityBucket + refractFrac), >= 100: directional Fresnel rim

layout(location = 0) out vec2 vLocalPos;
layout(location = 1) out vec4 vColor;
layout(location = 2) flat out vec2 vHalfSize;
layout(location = 3) flat out vec4 vRadii;
layout(location = 4) out vec2 vScreen;
layout(location = 5) flat out float vMode;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vec2 size = vec2(UV1) * 0.125;
    vec2 halfSize = size * 0.5;
    float maxR = min(halfSize.x, halfSize.y);

    float rTL = min(float((UV2.x >> 7) & 127) * 0.5, maxR);
    float rTR = min(float(UV2.x & 127) * 0.5, maxR);
    float rBR = min(float((UV2.y >> 7) & 127) * 0.5, maxR);
    float rBL = min(float(UV2.y & 127) * 0.5, maxR);

    vLocalPos = UV0;
    vColor = Color;
    vHalfSize = halfSize;
    vRadii = vec4(rTL, rTR, rBR, rBL);
    vMode = LineWidth;

    vec2 ndc = gl_Position.xy / max(abs(gl_Position.w), 1e-6);
    vScreen = vec2(ndc.x * 0.5 + 0.5, ndc.y * 0.5 + 0.5);
}
