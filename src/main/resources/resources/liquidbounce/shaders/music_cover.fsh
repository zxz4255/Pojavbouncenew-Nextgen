#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

// Corner radius as a fraction of the tile edge: 0.26 of a 42px row cover is the 11px
// radius the rest of the list uses.
const float CORNER = 0.26;
// Studio light, brightest at the top edge of the tile.
const float LIGHT = 0.12;
// Hairline just inside the outline, so bright artwork keeps an edge.
const float RIM_WIDTH = 0.035;
const float RIM_STRENGTH = 0.09;

float sdRoundedBox(vec2 point, vec2 halfSize, float radius) {
    vec2 corner = abs(point) - halfSize + radius;
    return length(max(corner, 0.0)) + min(max(corner.x, corner.y), 0.0) - radius;
}

void main() {
    // texCoord0.y runs from 1 at the top edge of the tile down to 0 at the bottom.
    float edge = sdRoundedBox(texCoord0 - 0.5, vec2(0.5), CORNER);
    float feather = max(fwidth(edge), 1e-4);
    float card = 1.0 - smoothstep(-feather, feather, edge);
    if (card <= 0.0) {
        discard;
    }

    vec4 texel = texture(Sampler0, texCoord0);
    float shade = mix(1.0 - LIGHT, 1.0 + LIGHT, smoothstep(0.0, 1.0, texCoord0.y));
    float rim = smoothstep(-RIM_WIDTH - feather, -RIM_WIDTH + feather, edge) * card;
    vec3 rgb = texel.rgb * shade + rim * RIM_STRENGTH * texel.a;

    vec4 color = vec4(rgb, texel.a * card) * vertexColor * ColorModulator;
    if (color.a <= 0.002) {
        discard;
    }
    fragColor = color;
}
