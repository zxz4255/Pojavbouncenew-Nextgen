#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 vUv;
layout(location = 1) in vec4 vColor;
layout(location = 0) out vec4 fragColor;

void main() {
    float edgeDistance = min(min(vUv.x, 1.0 - vUv.x), min(vUv.y, 1.0 - vUv.y));
    float edgeWidth = max(vColor.g, 0.01);
    float edgeMask = 1.0 - smoothstep(0.0, edgeWidth, edgeDistance);
    float softFalloff = pow(clamp(edgeMask, 0.0, 1.0), 1.15);
    float alpha = clamp(vColor.a * softFalloff, 0.0, 1.0);

    // Low-intensity scarlet tint at the borders; the center remains completely clear.
    fragColor = vec4(1.0, 0.012, 0.008, alpha);
}
