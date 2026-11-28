#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
uniform sampler2D texture0;
uniform sampler2D historyTexture;
layout(std140) uniform MotionBlurData { vec4 settings; };
void main() {
    vec3 current = texture(texture0, texCoord).rgb;
    vec3 history = texture(historyTexture, texCoord).rgb;
    fragColor = vec4(mix(current, history, clamp(settings.x, 0.0, 1.0)), 1.0);
}
