#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;  // Projected 3D vertex in GUI coordinates (sx, sy, 0)
layout(location = 1) in vec2 UV0;       // Perspective-predivided (localU / depthW, localV / depthW)
layout(location = 2) in vec4 Color;     // Accent RGB + master opacity
layout(location = 3) in ivec2 UV1;      // (round(width * 8), round(height * 8)) -> halfSize * 16
layout(location = 4) in ivec2 UV2;      // x: round(radius * 16), y: packed 3D style/hover/select/normal/tex flags
layout(location = 5) in float LineWidth;// 1.0 / depthW for hardware perspective-correct local coordinates

layout(location = 0) out vec2 vUvOverW;
layout(location = 1) out vec4 vColor;
layout(location = 2) flat out vec2 vHalfSize;
layout(location = 3) flat out ivec2 vParams;
layout(location = 4) out vec2 vScreenUv;
layout(location = 5) out float vInvW;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vUvOverW = UV0;
    vColor = Color;
    vHalfSize = max(vec2(UV1) * 0.0625, vec2(0.5));
    vParams = UV2;
    vInvW = LineWidth;

    vec2 ndc = gl_Position.xy / max(abs(gl_Position.w), 1e-6);
    vScreenUv = vec2(ndc.x * 0.5 + 0.5, ndc.y * 0.5 + 0.5);
}
