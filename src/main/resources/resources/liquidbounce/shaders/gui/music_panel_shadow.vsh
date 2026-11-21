#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

/*
 * Music panel shadow: one quad per light, evaluated as a signed distance field in the fragment stage.
 *
 * The previous implementation stacked up to eight translucent rounded rectangles per shadow, which is
 * why the halo read as concentric rings instead of a gradient. Here the quad is exactly the reach of
 * the blur and the falloff is continuous, so the same look costs one draw call.
 *
 *   Position     : GUI-space quad corner (the padded shadow extents).
 *   UV0          : offset from the shadow centre, in GUI units (carries sign).
 *   Color        : shadow tint, alpha is the peak opacity at the silhouette.
 *   UV1 (ivec2)  : card half size * 8, fixed point.
 *   UV2 (ivec2)  : x = corner radius * 8, y = falloff curve selector (see the fragment stage).
 *   LineWidth    : blur radius in GUI units (sigma).
 */

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
