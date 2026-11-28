#version 330
#extension GL_ARB_separate_shader_objects : require

#include <liquidbounce:clickgui/glass_blur.glsl>

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

uniform sampler2D texture0;

layout(std140) uniform ClickGuiGlassBlurData {
    vec4 uBlurParams; // x = stepScale, y = vibranceBoost, z = passIndex, w = reserved
};

void main() {
    vec2 texel = vec2(1.0) / max(vec2(textureSize(texture0, 0)), vec2(1.0));
    vec2 axisStep = vec2(texel.x * max(uBlurParams.x, 0.05), 0.0);
    fragColor = sampleGaussian15Bilinear(texture0, texCoord, axisStep);
}
