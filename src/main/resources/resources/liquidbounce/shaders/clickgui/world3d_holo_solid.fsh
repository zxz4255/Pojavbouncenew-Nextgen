#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec2 vUvOverW;
layout(location = 1) in vec4 vColor;
layout(location = 2) flat in vec2 vHalfSize;
layout(location = 3) flat in ivec2 vParams;
layout(location = 4) in vec2 vScreenUv;
layout(location = 5) in float vInvW;

layout(location = 0) out vec4 fragColor;

float sdRoundBox(vec2 p, vec2 halfSize, float radius) {
    float r = clamp(radius, 0.0, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    float invW = max(vInvW, 1e-6);
    vec2 p = vUvOverW / invW;
    vec2 h = vHalfSize;
    float radius = clamp(float(vParams.x) * 0.0625, 0.0, min(h.x, h.y));

    int flags = vParams.y;
    int kind = flags & 7;
    float hover = float((flags >> 3) & 15) / 15.0;
    float selected = float((flags >> 7) & 15) / 15.0;
    float tiltNorm = (float((flags >> 11) & 15) / 15.0) * 2.0 - 1.0;

    float d = sdRoundBox(p, h, radius);
    float aa = max(fwidth(d), 1e-4);
    float inside = 1.0 - smoothstep(-aa * 0.5, aa * 0.5, d);

    vec3 accent = vColor.rgb * ColorModulator.rgb;
    float masterAlpha = clamp(vColor.a * ColorModulator.a, 0.0, 1.0);

    float shadowSigma = (kind == 2) ? 10.5 : ((kind == 3) ? 4.5 : 7.5 + hover * 3.0);
    float dShift = max(sdRoundBox(p - vec2(tiltNorm * 2.2, 3.0 + hover * 1.8), h, radius), 0.0);
    float shadow = exp(-0.5 * (dShift * dShift) / max(shadowSigma * shadowSigma, 1.0));
    float shadowAlpha = shadow * ((kind == 3) ? 0.24 : 0.42) * masterAlpha;

    if (inside <= 0.0005) {
        if (shadowAlpha <= 0.001) discard;
        fragColor = vec4(0.0, 0.0, 0.0, shadowAlpha);
        return;
    }

    vec2 normCoord = p / max(h, vec2(1.0));
    vec3 obsidianBase = (kind == 2) ? vec3(0.060, 0.078, 0.122) : vec3(0.074, 0.096, 0.148);
    float activeBoost = (kind == 1) ? 1.0 : 0.0;
    vec3 tintedGlass = mix(obsidianBase, accent * 0.48 + obsidianBase * 0.52, activeBoost * 0.42 + hover * 0.16 + selected * 0.14);

    vec2 gridCell = abs(fract((p + h) / 18.0 - 0.5) - 0.5) / max(fwidth((p + h) / 18.0), vec2(1e-3));
    float gridLine = 1.0 - clamp(min(gridCell.x, gridCell.y), 0.0, 1.0);
    float gridMask = gridLine * (0.028 + 0.022 * hover) * (1.0 - smoothstep(0.55, 1.0, length(normCoord) * 0.72));
    tintedGlass += mix(vec3(0.55, 0.72, 0.95), accent, 0.5) * gridMask;

    float sweepCoord = normCoord.x * 0.78 + normCoord.y * 0.42 - tiltNorm * 0.55;
    float sheen = exp(-6.5 * sweepCoord * sweepCoord) * (0.055 + 0.055 * hover + 0.04 * abs(tiltNorm));
    tintedGlass += vec3(0.88, 0.94, 1.0) * sheen;

    if (kind == 1) {
        float leftBar = (1.0 - smoothstep(0.0, 3.4, p.x + h.x)) * smoothstep(0.0, 3.0, h.y - abs(p.y));
        tintedGlass = mix(tintedGlass, mix(accent, vec3(1.0), 0.28), leftBar * 0.92);
    }

    float rimBand = (1.0 - smoothstep(-aa, aa * 0.5, d)) * smoothstep(-1.35 - aa, -0.35, d);
    float directionalRim = clamp(0.58 - 0.28 * normCoord.y - 0.20 * normCoord.x * sign(tiltNorm + 0.01), 0.18, 1.0);
    vec3 rimCol = mix(vec3(0.76, 0.85, 0.98), accent, clamp(activeBoost * 0.72 + selected * 0.68 + hover * 0.35, 0.0, 0.92));
    float rimStrength = rimBand * directionalRim * (0.34 + 0.36 * activeBoost + 0.34 * selected + 0.24 * hover);
    vec3 panelRgb = mix(tintedGlass, rimCol, clamp(rimStrength, 0.0, 0.95));

    float bodyOpacity = ((kind == 2) ? 0.90 : ((kind == 3) ? 0.84 : 0.86))
        + activeBoost * 0.06 + hover * 0.04 + selected * 0.03;
    float panelAlpha = clamp(bodyOpacity, 0.0, 0.97) * masterAlpha;

    float finalAlpha = mix(shadowAlpha, panelAlpha, inside);
    if (finalAlpha <= 0.001) discard;
    vec3 finalRgb = panelRgb * clamp((panelAlpha * inside) / max(finalAlpha, 1e-5), 0.0, 1.0);
    fragColor = vec4(finalRgb, finalAlpha);
}
