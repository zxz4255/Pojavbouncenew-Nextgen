#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec2 vOffset;
layout(location = 1) in vec4 vColor;
layout(location = 2) flat in ivec2 vHalfSize;
layout(location = 3) flat in ivec2 vParams;
layout(location = 4) in float vSigma;
layout(location = 0) out vec4 fragColor;

const float INV_SQRT2 = 0.70710678118;
const float INV_SQRT_2PI = 0.39894228040;

// Exact signed distance to a rounded rectangle in GUI coordinates.
float roundedDistance(vec2 p, vec2 halfSize, float radius) {
    float r = clamp(radius, 0.0, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// Fast odd-symmetric error function approximation (max error < 1.5e-4).
float erfApprox(float x) {
    float s = sign(x);
    float a = abs(x);
    float t = 1.0 / (1.0 + 0.3275911 * a);
    float poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))));
    return s * (1.0 - poly * exp(-a * a));
}

// 1D Gaussian integral of segment [-halfW, +halfW] evaluated at x.
float boxGaussian1D(float x, float halfW, float sigma) {
    if (halfW <= 0.0) return 0.0;
    float k = INV_SQRT2 / max(sigma, 1e-4);
    return 0.5 * (erfApprox((halfW - x) * k) + erfApprox((halfW + x) * k));
}

// Exact horizontal half-extent of the rounded silhouette at vertical slice y.
float sliceHalfWidth(float y, vec2 halfSize, float radius) {
    float ay = abs(y);
    if (ay >= halfSize.y) return 0.0;
    float r = clamp(radius, 0.0, min(halfSize.x, halfSize.y));
    float straightY = halfSize.y - r;
    if (ay <= straightY) return halfSize.x;
    float dy = ay - straightY;
    return max(0.0, halfSize.x - r + sqrt(max(0.0, r * r - dy * dy)));
}

// Exact 2D Gaussian area-convolution of the rounded pill/card via 6-point Gauss-Legendre quadrature
// restricted to the active vertical support [max(-hy, py - 4*sigma), min(+hy, py + 4*sigma)].
float roundedBoxShadow2D(vec2 p, vec2 halfSize, float radius, float sigma) {
    float s = max(sigma, 0.25);
    if (abs(p.x) > halfSize.x + 4.2 * s) return 0.0;
    float ya = max(-halfSize.y, p.y - 4.2 * s);
    float yb = min(halfSize.y, p.y + 4.2 * s);
    if (ya >= yb) return 0.0;

    float mid = 0.5 * (ya + yb);
    float halfSpan = 0.5 * (yb - ya);
    float invSigma = 1.0 / s;

    // Symmetrical 6-point Gauss-Legendre abscissae & weights on [-1, 1].
    const vec3 GL_X = vec3(0.2386191861, 0.6612093865, 0.9324695142);
    const vec3 GL_W = vec3(0.4679139346, 0.3607615730, 0.1713244924);

    float accum = 0.0;
    for (int i = 0; i < 3; ++i) {
        float dx = halfSpan * GL_X[i];
        float w = GL_W[i];

        float yPos = mid + dx;
        float hwPos = sliceHalfWidth(yPos, halfSize, radius);
        float zPos = (yPos - p.y) * invSigma;
        accum += w * exp(-0.5 * zPos * zPos) * boxGaussian1D(p.x, hwPos, s);

        float yNeg = mid - dx;
        float hwNeg = sliceHalfWidth(yNeg, halfSize, radius);
        float zNeg = (yNeg - p.y) * invSigma;
        accum += w * exp(-0.5 * zNeg * zNeg) * boxGaussian1D(p.x, hwNeg, s);
    }

    return clamp(accum * halfSpan * INV_SQRT_2PI * invSigma, 0.0, 1.0);
}

void main() {
    // 1/16 px sub-pixel geometry eliminates quantization mismatch during spring morphing.
    vec2 h = max(vec2(vHalfSize) * 0.0625, vec2(0.0625));
    float r = clamp(float(vParams.x) * 0.0625, 0.0, min(h.x, h.y));

    float body = roundedDistance(vOffset, h, r);
    float aa = max(fwidth(body), 1e-4);
    float shellMask = 1.0 - smoothstep(-aa * 0.5, aa * 0.5, body);

    int encodedBodyAlpha = clamp(vParams.y, 0, 255);
    float bodyAlpha = (float(encodedBodyAlpha) / 255.0) * ColorModulator.a;

    // Fast path strictly inside the fused shell: pure clean translucent glass body, zero shadow bleed.
    if (encodedBodyAlpha > 0 && shellMask >= 0.9999) {
        if (bodyAlpha <= 0.0005) discard;
        fragColor = vec4(vColor.rgb * ColorModulator.rgb, bodyAlpha);
        return;
    }
    if (encodedBodyAlpha == 0 && shellMask >= 0.9999) {
        discard;
    }

    // Two-layer physical 2D area-convolved drop shadow:
    // 1) Centered ambient contact penumbra hugs all 4 edges & corners evenly with zero gap.
    // 2) Downward-offset wide key penumbra adds natural floating elevation without detaching the top edge.
    float baseSigma = max(vSigma, 0.5);
    float ambient = roundedBoxShadow2D(vOffset, h, r, baseSigma * 0.88);
    float key = roundedBoxShadow2D(vOffset - vec2(0.0, baseSigma * 0.62), h, r, baseSigma * 2.35);

    // Normalize against center occlusion of small pills so narrow islands keep natural presence.
    float rawShadow = clamp(ambient * 0.58 + key * 0.78, 0.0, 1.0);
    float shadowAlpha = vColor.a * ColorModulator.a * rawShadow;

    // Subtle interleaved gradient noise dither in the exterior shadow tail prevents 8-bit sky banding.
    float ign = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    shadowAlpha = max(0.0, shadowAlpha + (ign - 0.5) * (0.85 / 255.0) * smoothstep(0.0, 0.04, shadowAlpha));

    float finalAlpha = (encodedBodyAlpha > 0)
        ? mix(shadowAlpha, bodyAlpha, shellMask)
        : shadowAlpha * (1.0 - shellMask);

    if (finalAlpha <= 0.0004) discard;
    vec3 finalRgb = (encodedBodyAlpha > 0)
        ? mix(vec3(0.0), vColor.rgb * ColorModulator.rgb, shellMask)
        : vec3(0.0);
    fragColor = vec4(finalRgb, finalAlpha);
}
