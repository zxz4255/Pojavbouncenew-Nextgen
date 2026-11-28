#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 delta = vUv * 2.0 - 1.0;
    float r2 = dot(delta, delta);
    if (r2 >= 1.0) {
        discard;
    }
    float glow = max(0.0, 1.0 - r2);
    float core = exp(-r2 * 6.2);
    float alpha = vColor.a * (0.36 * glow * glow + 0.64 * core);
    if (alpha <= 0.001) {
        discard;
    }
    vec3 rgb = mix(vColor.rgb, vec3(0.98, 0.99, 1.0), 0.72);
    fragColor = vec4(rgb, alpha);
}
