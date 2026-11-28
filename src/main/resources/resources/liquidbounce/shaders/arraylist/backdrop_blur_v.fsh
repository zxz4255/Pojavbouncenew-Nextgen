#version 330
#extension GL_ARB_separate_shader_objects : require

/*
 * Vertical half of the separable gaussian used by the arraylist backdrop blur.
 *
 * The horizontal half reuses the HUD blur pass (shaders/blur/ui_blur_h.frag), this is its vertical
 * counterpart. Weights come packed as vec4[23] from the GUI_BLUR_KERNEL uniform, exactly like the
 * vanilla blur pass, so the kernel is precomputed and normalized on the CPU.
 */
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

uniform sampler2D texture0;
layout(std140) uniform BlurKernelData {
    vec4 weightVecs[23];
    int kernelRadius;
};

float getWeight(int idx) {
    vec4 v = weightVecs[idx / 4];
    int comp = idx - (idx / 4) * 4;
    if (comp == 0) return v.x;
    if (comp == 1) return v.y;
    if (comp == 2) return v.z;
    return v.w;
}

void main() {
    vec2 texelSize = vec2(1.0) / textureSize(texture0, 0).xy;

    vec4 result = vec4(0.0);

    for (int i = 0; i < kernelRadius * 2 + 1; i++) {
        float offset = float(i - kernelRadius);
        result += texture(texture0, texCoord + vec2(0.0, texelSize.y * offset)) * getWeight(i);
    }

    fragColor = result;
}
