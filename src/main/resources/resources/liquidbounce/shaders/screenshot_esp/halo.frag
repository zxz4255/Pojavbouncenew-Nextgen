#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
uniform sampler2D outlineTex;
uniform sampler2D depthTex;
layout(std140) uniform ScreenshotEspData {
    vec4 effect; // radius in pixels, strength, shadow mode, hand mask
    vec4 handColor;
};

vec4 maskAt(vec2 uv) {
    vec4 color = textureLod(outlineTex, uv, 0.0);
    if (effect.w > 0.5) {
        float depth = textureLod(depthTex, uv, 0.0).r;
        float coverage = max(color.a, step(1e-8, depth));
        return vec4(handColor.rgb, coverage * handColor.a);
    }
    return color;
}

void main() {
    vec4 center = maskAt(texCoord);
    // Never paint the solid silhouette; vanilla's sharp edge is blitted over this pass.
    if (center.a > 0.01 || effect.y <= 0.0) discard;
    vec2 pixel = 1.0 / vec2(textureSize(outlineTex, 0));
    float best = 0.0;
    vec3 selectedColor = vec3(0.0);
    // 48 samples, fixed bound for mobile drivers. Nearest weighted sample defines a smooth
    // outward falloff without layering many translucent shells.
    for (int ring = 1; ring <= 6; ++ring) {
        float t = float(ring) / 6.0;
        float r = max(1.0, effect.x * t);
        float weight = exp(-3.2 * t * t);
        for (int direction = 0; direction < 8; ++direction) {
            float angle = float(direction) * 0.7853981634;
            vec4 sampleColor = maskAt(texCoord + vec2(cos(angle), sin(angle)) * r * pixel);
            float contribution = sampleColor.a * weight;
            if (contribution > best) {
                best = contribution;
                selectedColor = sampleColor.rgb;
            }
        }
    }
    float amount = clamp(best * effect.y, 0.0, 1.0);
    if (amount < 0.001) discard;
    if (effect.z > 0.5) {
        fragColor = vec4(0.0, 0.0, 0.0, amount * 0.65);
    } else {
        fragColor = vec4(selectedColor * amount, 0.0);
    }
}
