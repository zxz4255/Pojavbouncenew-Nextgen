#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
uniform sampler2D InSampler;

// RGBA8 mask key, shared with ModuleScreenshotESP.maskColor and halo.frag.
bool screenshotMask(vec4 c) {
    vec3 key = vec3(3.0, 249.0, 7.0) / 255.0;
    return c.a > 0.001 && all(lessThan(abs(c.rgb - key), vec3(1.1 / 255.0)));
}

void main() {
    vec4 c = textureLod(InSampler, texCoord, 0.0);
    // Preserve every non-key pixel for vanilla and other outline modules.
    fragColor = screenshotMask(c) ? vec4(0.0) : c;
}
