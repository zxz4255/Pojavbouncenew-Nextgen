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
    vec2 axisStep = vec2(0.0, texel.y * max(uBlurParams.x, 0.05));
    vec4 blurred = sampleGaussian15Bilinear(texture0, texCoord, axisStep);
    if (uBlurParams.y > 1.001) {
        float luma = dot(blurred.rgb, vec3(0.2126, 0.7152, 0.0722));
        float maxC = max(blurred.r, max(blurred.g, blurred.b));
        float minC = min(blurred.r, min(blurred.g, blurred.b));
        float sat = maxC - minC;
        blurred.rgb = clamp(mix(vec3(luma), blurred.rgb, 1.0 + (1.0 - sat) * (uBlurParams.y - 1.0)), vec3(0.0), vec3(1.0));
    }
    fragColor = vec4(blurred.rgb, 1.0);
}
