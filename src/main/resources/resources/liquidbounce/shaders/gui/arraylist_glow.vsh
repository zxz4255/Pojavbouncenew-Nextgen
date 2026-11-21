#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

/*
 * Arraylist glow quad.
 *
 * The quad is the row rect expanded by roughly 3 sigma on every side. Every attribute carries the
 * information the fragment shader needs to evaluate the glow analytically, so a single quad is
 * enough - no stacked shadow layers.
 *
 *  - UV0: offset of this vertex from the centre of the row rect, in GUI units.
 *  - UV1: half extents of the row rect, in GUI units (rounded to int).
 *  - LineWidth: gaussian sigma, in GUI units.
 */
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in float LineWidth;

layout(location = 0) out vec2 vOffset;
layout(location = 1) out vec4 vColor;
layout(location = 2) flat out ivec2 vHalfSize;
layout(location = 3) out float vSigma;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vOffset = UV0;
    vColor = Color;
    vHalfSize = UV1;
    vSigma = max(LineWidth, 0.0);
}
