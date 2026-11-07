#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

/*
 * Monet Music - world lyric glow, vertex stage.
 *
 * Plain POSITION_TEX_COLOR passthrough: the geometry is already placed by the host pose (camera
 * relative glyph quads), so only the projection and dynamic transforms are needed here.
 */

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    vertexColor = Color;
}
