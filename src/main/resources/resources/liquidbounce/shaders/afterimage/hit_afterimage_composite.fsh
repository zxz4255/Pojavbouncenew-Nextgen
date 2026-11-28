#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

uniform sampler2D coreTex;
uniform sampler2D coreDepth;
uniform sampler2D shellTex;
uniform sampler2D shellDepth;
uniform sampler2D sceneDepth;

layout(std140) uniform BlendData {
    vec4 uColor;
};

void main() {
    float sceneD = textureLod(sceneDepth, texCoord, 0.0).r;
    float coreAlpha = textureLod(coreTex, texCoord, 0.0).a;

    float alpha = 0.0;
    if (coreAlpha > 0.004) {
        float coreD = textureLod(coreDepth, texCoord, 0.0).r;
        // Reversed-Z (1.0 = near, 0.0 = far): small bias avoids self-occlusion against the victim's
        // live model/armor on the hit frame while still occluding behind solid walls/blocks.
        if (coreD + 0.00025 >= sceneD) {
            alpha = coreAlpha;
        }
    } else {
        float shellAlpha = textureLod(shellTex, texCoord, 0.0).a;
        if (shellAlpha > 0.002) {
            float shellD = textureLod(shellDepth, texCoord, 0.0).r;
            if (shellD + 0.00025 >= sceneD) {
                alpha = shellAlpha;
            }
        }
    }

    if (alpha <= 0.002) {
        discard;
    }
    fragColor = vec4(uColor.rgb, clamp(alpha * uColor.a, 0.0, 1.0));
}
