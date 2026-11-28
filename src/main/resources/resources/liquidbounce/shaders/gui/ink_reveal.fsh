#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec2 vUv;
layout(location = 1) in vec4 vColor;
layout(location = 2) flat in ivec2 vAnimParams;
layout(location = 3) flat in ivec2 vStyleParams;
layout(location = 4) flat in float vNominalRadius;

layout(location = 0) out vec4 fragColor;

vec3 helloSpectrumGradient(float u) {
    float x = clamp(u, 0.0, 1.0);
    const vec3 c0 = vec3( 96.0, 111.0, 250.0) / 255.0;
    const vec3 c1 = vec3(108.0, 101.0, 244.0) / 255.0;
    const vec3 c2 = vec3(116.0,  95.0, 240.0) / 255.0;
    const vec3 c3 = vec3(138.0,  91.0, 231.0) / 255.0;
    const vec3 c4 = vec3(179.0,  91.0, 214.0) / 255.0;
    const vec3 c5 = vec3(213.0,  92.0, 195.0) / 255.0;
    const vec3 c6 = vec3(240.0,  98.0, 161.0) / 255.0;
    const vec3 c7 = vec3(250.0, 116.0, 134.0) / 255.0;
    const vec3 c8 = vec3(252.0, 136.0, 113.0) / 255.0;
    const vec3 c9 = vec3(254.0, 157.0,  92.0) / 255.0;

    if (x < 0.15) return mix(c0, c1, x / 0.15);
    if (x < 0.25) return mix(c1, c2, (x - 0.15) / 0.10);
    if (x < 0.35) return mix(c2, c3, (x - 0.25) / 0.10);
    if (x < 0.45) return mix(c3, c4, (x - 0.35) / 0.10);
    if (x < 0.55) return mix(c4, c5, (x - 0.45) / 0.10);
    if (x < 0.68) return mix(c5, c6, (x - 0.55) / 0.13);
    if (x < 0.80) return mix(c6, c7, (x - 0.68) / 0.12);
    if (x < 0.90) return mix(c7, c8, (x - 0.80) / 0.10);
    return mix(c8, c9, (x - 0.90) / 0.10);
}

vec2 decodeFieldTexel(vec2 baseIj, float yOffset, vec2 invTexSize) {
    vec4 t = texture(Sampler0, (baseIj + vec2(0.5, yOffset + 0.5)) * invTexSize);
    float sd = t.r * (255.0 / 16.0) - 4.0;
    float s = (t.g * (255.0 * 256.0) + t.b * 255.0) * (1.0 / 65535.0);
    return vec2(sd, s);
}

float evalLayerCoverage(
    vec2 ij,
    vec2 f,
    float yOffset,
    vec2 invTexSize,
    float progress,
    float totalArcPx,
    float nomRadius,
    float effRadius,
    float aa
) {
    vec2 c00 = decodeFieldTexel(ij, yOffset, invTexSize);
    vec2 c10 = decodeFieldTexel(ij + vec2(1.0, 0.0), yOffset, invTexSize);
    vec2 c01 = decodeFieldTexel(ij + vec2(0.0, 1.0), yOffset, invTexSize);
    vec2 c11 = decodeFieldTexel(ij + vec2(1.0, 1.0), yOffset, invTexSize);

    float sd = mix(mix(c00.x, c10.x, f.x), mix(c01.x, c11.x, f.x), f.y) + (nomRadius - effRadius);
    if (sd > aa * 0.65 + 0.35) {
        return 0.0;
    }

    float minS = min(min(c00.y, c10.y), min(c01.y, c11.y));
    float maxS = max(max(c00.y, c10.y), max(c01.y, c11.y));
    float s;
    if (maxS - minS > 0.04) {
        vec2 nearCorner = ij + step(vec2(0.5), f);
        s = decodeFieldTexel(nearCorner, yOffset, invTexSize).y;
    } else {
        s = mix(mix(c00.y, c10.y, f.x), mix(c01.y, c11.y, f.x), f.y);
    }

    float dFinal = sd;
    if (progress < 0.9999) {
        float dsPx = (s - progress) * totalArcPx;
        if (dsPx > 0.0) {
            float radial = max(sd + effRadius, 0.0);
            dFinal = max(sd, length(vec2(radial, dsPx)) - effRadius);
        }
    }

    return 1.0 - smoothstep(-aa * 0.5, aa * 0.5, dFinal);
}

void main() {
    vec2 texSize = max(vec2(textureSize(Sampler0, 0)), vec2(2.0, 4.0));
    float halfH = texSize.y * 0.5;
    vec2 invTexSize = vec2(1.0) / texSize;

    vec2 logicalCoord = vUv * vec2(texSize.x, halfH);
    float texelsPerPx = clamp(max(length(dFdx(logicalCoord)), length(dFdy(logicalCoord))), 0.25, 10.0);
    float aa = texelsPerPx * 1.15;

    float progress = clamp(float(vAnimParams.x) / 32767.0, 0.0, 1.0);
    if (progress <= 0.0001) {
        discard;
    }
    float totalArcPx = max(float(vAnimParams.y), 1.0);
    int colorMode = vStyleParams.x;
    float effRadius = (colorMode == 1) ? max(vNominalRadius, texelsPerPx * 0.45) : vNominalRadius;

    vec2 p = clamp(vUv, vec2(0.0), vec2(1.0)) * vec2(texSize.x, halfH) - vec2(0.5);
    vec2 ij = clamp(floor(p), vec2(0.0), vec2(texSize.x - 2.0, halfH - 2.0));
    vec2 f = clamp(p - ij, vec2(0.0), vec2(1.0));

    float cov0 = evalLayerCoverage(ij, f, 0.0, invTexSize, progress, totalArcPx, vNominalRadius, effRadius, aa);
    float cov1 = evalLayerCoverage(ij, f, halfH, invTexSize, progress, totalArcPx, vNominalRadius, effRadius, aa);
    float cov = max(cov0, cov1);

    vec4 base = vColor * ColorModulator;
    float outAlpha = base.a * cov;
    if (outAlpha <= 0.002) {
        discard;
    }

    vec3 outRgb = (colorMode == 0) ? (helloSpectrumGradient(vUv.x) * base.rgb) : base.rgb;
    fragColor = vec4(outRgb, outAlpha);
}
