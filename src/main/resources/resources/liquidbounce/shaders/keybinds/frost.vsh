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
layout(location = 0) out vec2 vLocal;
layout(location = 1) out vec4 vColor;
layout(location = 2) flat out ivec2 vSize;
layout(location = 3) flat out ivec2 vParameters;
layout(location = 4) out vec2 vScreen;
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vLocal = UV0;
    vColor = Color;
    vSize = UV1;
    vParameters = UV2;
    vec2 ndc = gl_Position.xy / gl_Position.w;
    vScreen = vec2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
}
