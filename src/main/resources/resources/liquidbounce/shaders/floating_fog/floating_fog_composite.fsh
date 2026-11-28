#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

uniform sampler2D FogSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform FloatingFogData {
    vec4 uInvViewProj0;
    vec4 uInvViewProj1;
    vec4 uInvViewProj2;
    vec4 uInvViewProj3;
    vec4 uCameraPosAndTime;
    vec4 uCamMinusPlayer;
    vec4 uColorAndDensity;
    vec4 uParams0; // x = uMaxDistance, y = uDrift, z = uClipZScale, w = uClipZBias
    vec4 uParams1; // x = uReversedZ, y = invFullW, z = invFullH, w = playerWorldY
};

mat4 invViewProj() {
    return mat4(uInvViewProj0, uInvViewProj1, uInvViewProj2, uInvViewProj3);
}

float linearDepth(vec2 uv, mat4 ivp) {
    float rawDepth = textureLod(DepthSampler, uv, 0.0).r;
    bool isSky = (uParams1.x > 0.5) ? (rawDepth <= 1e-6) : (rawDepth >= 0.999999);
    float uMaxDistance = uParams0.x;
    if (isSky) {
        return uMaxDistance;
    }
    vec2 ndcXY = uv * 2.0 - 1.0;
    float zNdc = rawDepth * uParams0.z + uParams0.w;
    vec4 hitH = ivp * vec4(ndcXY, zNdc, 1.0);
    vec3 hitRel = hitH.xyz / max(abs(hitH.w), 1e-7);
    return min(length(hitRel), uMaxDistance);
}

vec4 sampleFog(vec2 uv, float referenceDepth, mat4 ivp, out float weight) {
    float depth = linearDepth(uv, ivp);
    float scale = max(0.45, referenceDepth * 0.032);
    float depthWeight = exp(-abs(depth - referenceDepth) / scale);
    float foregroundGuard = depth < referenceDepth ? depth / max(referenceDepth, 0.001) : 1.0;
    weight = depthWeight * foregroundGuard;
    return textureLod(FogSampler, uv, 0.0);
}

void main() {
    mat4 ivp = invViewProj();
    float referenceDepth = linearDepth(texCoord, ivp);
    vec2 fogSize = vec2(textureSize(FogSampler, 0));
    vec2 fogCoord = texCoord * fogSize - 0.5;
    vec2 base = floor(fogCoord);
    vec2 blend = clamp(fogCoord - base, 0.0, 1.0);

    vec2 c00 = clamp((base + vec2(0.5, 0.5)) / fogSize, vec2(0.0), vec2(1.0));
    vec2 c10 = clamp((base + vec2(1.5, 0.5)) / fogSize, vec2(0.0), vec2(1.0));
    vec2 c01 = clamp((base + vec2(0.5, 1.5)) / fogSize, vec2(0.0), vec2(1.0));
    vec2 c11 = clamp((base + vec2(1.5, 1.5)) / fogSize, vec2(0.0), vec2(1.0));

    float w00, w10, w01, w11;
    vec4 f00 = sampleFog(c00, referenceDepth, ivp, w00);
    vec4 f10 = sampleFog(c10, referenceDepth, ivp, w10);
    vec4 f01 = sampleFog(c01, referenceDepth, ivp, w01);
    vec4 f11 = sampleFog(c11, referenceDepth, ivp, w11);

    w00 *= (1.0 - blend.x) * (1.0 - blend.y);
    w10 *= blend.x * (1.0 - blend.y);
    w01 *= (1.0 - blend.x) * blend.y;
    w11 *= blend.x * blend.y;

    float sum = w00 + w10 + w01 + w11;
    if (sum > 0.0005) {
        fragColor = (f00 * w00 + f10 * w10 + f01 * w01 + f11 * w11) / sum;
    } else {
        fragColor = f00 * ((1.0 - blend.x) * (1.0 - blend.y))
            + f10 * (blend.x * (1.0 - blend.y))
            + f01 * ((1.0 - blend.x) * blend.y)
            + f11 * (blend.x * blend.y);
    }
}
