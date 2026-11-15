#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
uniform sampler2D outlineTex;
uniform sampler2D depthTex;
layout(std140) uniform ScreenshotEspData {
    vec4 effect; // radius in pixels, strength, shadow mode, hand mask
    vec4 handColor; // chosen effect color (also used for world silhouettes)
};

float maskAt(vec2 uv) {
    vec4 c = textureLod(outlineTex, uv, 0.0);
    if (effect.w > 0.5) {
        float depth = textureLod(depthTex, uv, 0.0).r;
        return max(c.a, step(1e-8, depth)) * handColor.a;
    }
    vec3 key = vec3(3.0, 249.0, 7.0) / 255.0;
    bool tagged = c.a > 0.001 && all(lessThan(abs(c.rgb - key), vec3(1.1 / 255.0)));
    return tagged ? c.a * handColor.a : 0.0;
}

void main() {
    // No solid silhouette and no sharp edge layer: only the soft outer field.
    if (maskAt(texCoord) > 0.01 || effect.y <= 0.0) discard;
    vec2 pixel = 1.0 / vec2(textureSize(outlineTex, 0));
    float sum = 0.0;
    float weights = 0.0;
    // Smooth weighted coverage rather than a max/dilation, which looked like a hard contour.
    for (int ring = 1; ring <= 6; ++ring) {
        float t = float(ring) / 6.0;
        float radius = max(1.0, effect.x * t);
        float weight = exp(-2.2 * t * t);
        for (int direction = 0; direction < 8; ++direction) {
            float angle = float(direction) * 0.7853981634;
            sum += maskAt(texCoord + vec2(cos(angle), sin(angle)) * radius * pixel) * weight;
            weights += weight;
        }
    }
    float amount = clamp((sum / max(weights, 0.001)) * effect.y, 0.0, 1.0);
    if (amount < 0.001) discard;
    if (effect.z > 0.5) {
        fragColor = vec4(0.0, 0.0, 0.0, amount * 0.65);
    } else {
        fragColor = vec4(handColor.rgb * amount, 0.0);
    }
}
