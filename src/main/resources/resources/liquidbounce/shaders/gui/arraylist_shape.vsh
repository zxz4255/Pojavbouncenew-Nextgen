#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

/*
 * Shared vertex stage of the arraylist surface shaders (row fill, sheen band, rim light).
 *
 * Every one of them draws a single quad in the GUI_ROUNDED_RECT vertex format, which carries all
 * the parameters the fragment stage needs - no uniform buffer, no per-frame CPU upload:
 *
 *   Position     : GUI space quad corner.
 *   UV0          : quad local UV (0..1); outside that range when the quad is expanded for AA.
 *   Color        : per-vertex colour. A row quad gets the palette colour at the top edge on its top
 *                  vertices and the next palette stop on its bottom vertices, so the GPU produces
 *                  the vertical gradient by interpolation and the CPU only ever sends two colours.
 *   UV1 (ivec2)  : shape size in GUI units, encoded * 16.
 *   UV2 (ivec2)  : corner radius pair (top, bottom) in GUI units, encoded * 16.
 *   LineWidth    : style parameter. Row: inner highlight amount (0..1). Rim: stroke thickness.
 *                  Sheen: falloff exponent.
 */
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in float LineWidth;

layout(location = 0) out vec2 vUv;
layout(location = 1) out vec4 vColor;
layout(location = 2) flat out ivec2 vSize;
layout(location = 3) flat out ivec2 vParams;
layout(location = 4) out float vStyle;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vUv = UV0;
    vColor = Color;
    vSize = UV1;
    vParams = UV2;
    vStyle = LineWidth;
}
