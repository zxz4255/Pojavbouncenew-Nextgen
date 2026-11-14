#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in float LineWidth;
layout(location = 0) out vec2 vOffset;
layout(location = 1) out vec4 vColor;
layout(location = 2) flat out ivec2 vHalfSize;
layout(location = 3) flat out ivec2 vParams;
layout(location = 4) out float vSigma;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vOffset = UV0;
    vColor = Color;
    vHalfSize = UV1;
    vParams = UV2;
    vSigma = max(LineWidth, 0.001);
}
