#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
uniform sampler2D texture0;
void main() {
    vec2 stepSize = vec2(1.0, 0.0) / vec2(textureSize(texture0, 0));
    vec4 sum = vec4(0.0);
    for (int i = -8; i <= 8; ++i) sum += texture(texture0, texCoord + stepSize * float(i));
    fragColor = sum / 17.0;
}
