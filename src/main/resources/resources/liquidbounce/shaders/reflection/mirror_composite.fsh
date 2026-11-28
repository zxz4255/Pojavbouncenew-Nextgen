#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

uniform sampler2D MirrorSampler;

void main() {
    vec4 m = textureLod(MirrorSampler, texCoord, 0.0);
    if (m.a <= 0.001) {
        discard;
    }
    fragColor = m;
}
