#version 330
#extension GL_ARB_separate_shader_objects : require

// SPDX-License-Identifier: GPL-3.0-or-later
// Edge refraction retains the MIT LiquidGlassShader adaptation credited in licenses/glass086/.
// 089 reflection coordinates are traced from the supplied reference images; see licenses/glass089/.
// 099 adds analytical 2D Gaussian volumetric inward-to-outward white bloom for Profile 0 GUI surfaces.
// No screenshot, game scenery, avatar or text is sampled as a baked material.
#include <minecraft:dynamictransforms.glsl>
#include <liquidbounce:clickgui/glass_common.glsl>

layout(location = 0) in vec2 vLocalPos;
layout(location = 1) in vec4 vColor;
layout(location = 2) flat in vec2 vHalfSize;
layout(location = 3) flat in vec4 vRadii;
layout(location = 4) in vec2 vScreen;
layout(location = 5) flat in float vMode;
layout(location = 0) out vec4 fragColor;
uniform sampler2D Sampler0;

float bell(float distance, float sigma) {
    float t = distance / max(sigma, 0.0001);
    return exp(-0.5 * t * t);
}

// Traced broad target-card fold: a curved lobe, NOT a uniform border or straight diagonal.
float targetFold(float y) {
    // +.004 compensates the radiance-peak shift from the adjacent cool lobe.
    return 0.4222908 + 1.0968153 * y - 0.8101146 * y * y;
}

// Closed-form 2D Gaussian area-convolution integral (Abramowitz & Stegun 7.1.26, |err| < 1.5e-7).
// Evaluates a C^infinity volumetric white bloom field continuously from interior (d < 0) to exterior (d > 0).
float erfApprox(float x) {
    float s = x < 0.0 ? -1.0 : 1.0;
    float ax = abs(x);
    float t = 1.0 / (1.0 + 0.3275911 * ax);
    float poly = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t
        + 0.254829592) * t;
    return s * (1.0 - poly * exp(-ax * ax));
}

float axisGaussianIntegral(float p, float halfExtent, float sigma) {
    float inv = 0.70710678 / max(sigma, 0.001);
    return 0.5 * (erfApprox((halfExtent - p) * inv) - erfApprox((-halfExtent - p) * inv));
}

float rectGaussianBloom(vec2 p, vec2 halfExtent, float sigma) {
    return axisGaussianIntegral(p.x, halfExtent.x, sigma) * axisGaussianIntegral(p.y, halfExtent.y, sigma);
}

float evalVolumetricWhiteBloom(vec2 localPos, vec2 halfSize, float spread, float capsule) {
    vec2 insetHalf = max(halfSize - vec2(mix(0.9, 0.35, capsule)), vec2(0.5));
    float sTight = max(spread * mix(0.40, 0.34, capsule), 1.5);
    float sMid   = max(spread * mix(0.96, 0.86, capsule), 3.6);
    float sWide  = max(spread * mix(1.80, 1.58, capsule), 6.8);
    float bTight = rectGaussianBloom(localPos, insetHalf, sTight);
    float bMid   = rectGaussianBloom(localPos, insetHalf, sMid);
    float bWide  = rectGaussianBloom(localPos, insetHalf, sWide);
    return bTight * mix(0.24, 0.45, capsule)
         + bMid   * mix(0.40, 0.35, capsule)
         + bWide  * mix(0.36, 0.20, capsule);
}

void main() {
    vec4 base = vColor * ColorModulator;
    // Four explicitly selected materials. The old Liquid Glass shader/packing is unchanged.
    float profile = floor(abs(vMode) / 256.0);
    float mode = abs(vMode) - profile * 256.0;
    vec2 size = max(vHalfSize * 2.0, vec2(0.001));
    vec2 q = (vLocalPos + vHalfSize) / size;
    vec3 sdf = sdCssRoundedBoxGrad(vLocalPos, vHalfSize, vRadii.x, vRadii.y, vRadii.z, vRadii.w);
    float d = sdf.x;
    vec2 normal = sdf.yz;
    float pixel = max(fwidth(d), 0.0001);
    float mask = 1.0 - smoothstep(-pixel * 0.5, pixel * 0.5, d);
    float key = pow(max(dot(normal, normalize(vec2(-0.55, -0.84))), 0.0), 4.0);
    float bounce = pow(max(dot(normal, normalize(vec2(0.62, 0.78))), 0.0), 6.0);

    // Profile 0: GUI Frosted Glass (ClickGUI & ModernGUI volumetric inward-to-outward white bloom,
    // while preserving 094's standalone quiet Music panel when base.b < 0.5).
    if (profile < 0.5) {
        if (vMode < -0.001) {
            float spread = max(mode, 0.001);
            float outside = smoothstep(-pixel * 0.5, pixel * 0.5, d);
            if (base.b < 0.25) {
                float shifted = sdCssRoundedBox(vLocalPos - vec2(0.0, min(spread * 0.25, 1.5)),
                    vHalfSize, vRadii.x, vRadii.y, vRadii.z, vRadii.w);
                float sigma = max(spread * 0.45, pixel);
                float falloff = exp(-0.5 * pow(max(shifted, 0.0) / sigma, 2.0));
                float alpha = base.a * falloff * outside;
                if (alpha <= 0.002) discard;
                fragColor = vec4(0.0, 0.0, 0.0, alpha);
                return;
            }
            float capsule = clamp((base.b - 0.627) / 0.322, 0.0, 1.0);
            float dist = max(d, 0.0);
            float bloomField = evalVolumetricWhiteBloom(vLocalPos, vHalfSize, spread, capsule);
            float padFade = 1.0 - smoothstep(spread * 1.85, spread * 2.45 + 3.2, dist);
            float bloomAlpha = base.a * bloomField * padFade * outside;

            float shadowSubtle = 0.0;
            if (capsule < 0.25) {
                float shifted = sdCssRoundedBox(vLocalPos - vec2(0.0, min(spread * 0.20, 2.5)),
                    vHalfSize, vRadii.x, vRadii.y, vRadii.z, vRadii.w);
                shadowSubtle = exp(-0.5 * pow(max(shifted, 0.0) / max(spread * 0.55, 1.0), 2.0)) * 0.065 * outside;
            }
            float dither = bayerDither4x4(gl_FragCoord.xy) * (0.85 / 255.0);
            float totalAlpha = clamp(bloomAlpha + shadowSubtle * (1.0 - bloomAlpha)
                + dither * step(0.002, bloomAlpha), 0.0, 1.0);
            if (totalAlpha <= 0.002) discard;
            vec3 outRgb = vec3(0.992, 0.996, 1.0) * (bloomAlpha / max(totalAlpha, 0.0001));
            fragColor = vec4(clamp(outRgb, 0.0, 1.0), totalAlpha);
            return;
        }
        if (mode >= 99.5) {
            if (base.a <= 0.002) discard;
            float width = max(mode - 100.0, 0.45);
            float ring = mask * smoothstep(-width - pixel * 1.2, -pixel * 0.2, d);
            float light = 0.24 + 0.26 * key + 0.12 * bounce;
            float alpha = clamp(ring * base.a * light, 0.0, 1.0);
            if (alpha <= 0.002) discard;
            fragColor = vec4(vec3(1.0), alpha);
            return;
        }
        if (mask <= 0.002) discard;
        vec2 texel = 1.0 / max(vec2(textureSize(Sampler0, 0)), vec2(1.0));
        float bucket = floor(mode);
        float opacity = clamp(bucket / 64.0, 0.0, 1.0);
        if (base.b < 0.5) {
            vec2 uvQuiet = clamp(vScreen, texel * 0.5, vec2(1.0) - texel * 0.5);
            vec3 blurredQuiet = texture(Sampler0, uvQuiet).rgb;
            fragColor = vec4(mix(blurredQuiet, vec3(base.g), base.a), mask * opacity);
            return;
        }

        float capsule = clamp((base.b - 0.627) / 0.322, 0.0, 1.0);
        float glowStrength = clamp(base.r * 2.0, 0.0, 2.2);
        float power = (mode - bucket) * 4.0;
        float minHalf = max(min(vHalfSize.x, vHalfSize.y), 1.0);
        float bezelW = clamp(minHalf * 0.40, 2.5, 10.0);
        float bezel = pow(smoothstep(-bezelW, 0.2, d), 2.0);
        vec2 fboNormal = vec2(normal.x, -normal.y);
        vec2 lo = texel * 0.5;
        vec2 hi = vec2(1.0) - lo;
        vec2 uv = clamp(vScreen - fboNormal * bezel * power * 3.0 * texel, lo, hi);
        vec3 blurred = texture(Sampler0, uv).rgb;
        float luma = dot(blurred, vec3(0.299, 0.587, 0.114));
        blurred = mix(vec3(luma), blurred, 0.78);

        vec3 glass = mix(blurred, vec3(base.g), base.a);

        float refSpread = mix(15.5, 11.5, capsule);
        float bloomField = evalVolumetricWhiteBloom(vLocalPos, vHalfSize, refSpread, capsule);
        float panelEdgeMist = (1.0 - smoothstep(0.0, 24.0, max(-d, 0.0))) * 0.15
            + (1.0 - smoothstep(0.0, 40.0, vLocalPos.y + vHalfSize.y)) * 0.055;
        float capsuleInnerGlow = bloomField * 0.48;

        float whiteGlowIn = clamp(mix(panelEdgeMist, capsuleInnerGlow, capsule) * glowStrength, 0.0, 0.74);
        glass = mix(glass, vec3(0.992, 0.996, 1.0), whiteGlowIn);
        glass += vec3(bayerDither4x4(gl_FragCoord.xy) / 255.0);

        fragColor = vec4(clamp(glass, 0.0, 1.0), mask * opacity);
        return;
    }

    // Separate exterior quarter-arc, visible in both target and block-counter references.
    // This is emitted on its own padded quad, independent of the shadow setting.
    if (vMode >= 0.0 && mode >= 191.5) {
        float radius = max(vRadii.z, size.y * 0.12);
        vec2 corner = vHalfSize - vec2(radius);
        vec2 p = vLocalPos - corner;
        float arcRadius = radius + size.y * 0.12;
        float arcWidth = clamp(size.y * 0.036, 0.55, 4.5);
        float ring = 1.0 - smoothstep(arcWidth * 0.5 - pixel * 0.5,
            arcWidth * 0.5 + pixel * 0.5, abs(length(p) - arcRadius));
        float angle = atan(p.y, p.x);
        float arc = smoothstep(-0.035, 0.035, angle) *
            (1.0 - smoothstep(1.51, 1.61, angle));
        float quadrant = step(0.0, p.x) * step(0.0, p.y);
        float alpha = ring * arc * quadrant * base.a * 0.42;
        if (alpha <= 0.002) discard;
        fragColor = vec4(vec3(0.92, 0.97, 1.0), alpha);
        return;
    }
    if (vMode < -0.001) {
        float spread = mode;
        float shifted = sdCssRoundedBox(vLocalPos - vec2(0.0, min(spread * 0.18, 4.0)),
            vHalfSize, vRadii.x, vRadii.y, vRadii.z, vRadii.w);
        float shade = base.a * evalGlassElevationShadow(shifted, d, spread);
        float outside = smoothstep(-0.5 * pixel, 0.8 * pixel, d);
        float halo = exp(-max(d, 0.0) / max(1.2, min(spread * 0.2, 3.0))) *
            (key * 0.08 + bounce * 0.025) * outside * base.a;
        float alpha = halo + shade * (1.0 - halo);
        if (alpha <= 0.002) discard;
        fragColor = vec4(vec3(halo) / max(alpha, 0.0001), alpha);
        return;
    }
    if (mode >= 99.5) {
        float width = max(mode - 100.0, 0.25);
        float ring = mask * smoothstep(-width - pixel, -width, d);
        // Reference edges are intermittent: no equally bright four-sided white stroke.
        float light = profile < 0.5 ?
            (0.025 + key * 0.38 + bounce * 0.10) : (0.008 + key * 0.06 + bounce * 0.025);
        float alpha = clamp(ring * base.a * light, 0.0, 1.0);
        if (alpha <= 0.002) discard;
        fragColor = vec4(vec3(1.0), alpha);
        return;
    }
    if (mask <= 0.002) discard;
    float bucket = floor(mode);
    float opacity = clamp(bucket / 64.0, 0.0, 1.0);
    float power = (mode - bucket) * 4.0;
    float rimWidth = clamp(min(vHalfSize.x, vHalfSize.y) * 0.5, 0.001, 8.0);
    float depth = clamp(-d / rimWidth, 0.0, 1.0);
    float fresnel = pow(1.0 - depth, 3.0);
    float refraction = pow(max(1.0 - 2.3 * pow(5.2 * 2.718281828459045,
        -6.9 * depth - 0.7), 0.0), 0.75);
    vec2 texel = 1.0 / max(vec2(textureSize(Sampler0, 0)), vec2(1.0));
    float lensPixels = min(30.0, 3.4 * rimWidth / pixel);
    vec2 fboNormal = vec2(normal.x, -normal.y);
    vec2 uv = vScreen - fboNormal * (1.0 - refraction) * lensPixels * power * texel;
    vec2 chroma = fboNormal * fresnel * 0.30 * power * texel;
    vec2 lo = texel * 0.5;
    vec2 hi = vec2(1.0) - lo;
    vec3 scene = vec3(texture(Sampler0, clamp(uv + chroma, lo, hi)).r,
        texture(Sampler0, clamp(uv, lo, hi)).g,
        texture(Sampler0, clamp(uv - chroma, lo, hi)).b);
    float luma = dot(scene, vec3(0.299, 0.587, 0.114));
    // Slightly neutral transmitting body; HUD is smoked glass, ClickGUI remains transparent.
    scene = mix(vec3(luma), scene, profile < 0.5 ? 0.86 : 0.67);
    // Red transports reflection strength; green transports a NEUTRAL tint luminance.
    vec3 color = mix(scene, vec3(base.g), base.a);
    float reflection = 0.0;
    float cool = 0.0;
    float warm = 0.0;
    if (profile < 1.5) {
        // Target: broad concave/convex fold + narrow almost-vertical right specular.
        float fold = targetFold(q.y);
        float broad = bell(q.x - fold, 0.036 + q.y * 0.009);
        float second = bell(q.x - (0.70 + 0.20 * q.y), 0.017) * smoothstep(0.65, 0.95, q.y);
        float edgeX = 0.968 - 0.009 * sin(q.y * 3.14159265);
        float edge = bell(q.x - edgeX, 0.0032);
        float edgeHalo = bell(q.x - edgeX, 0.014);
        reflection = broad * (0.10 + q.y * 0.22) + second * 0.16 + edge * 0.46 + edgeHalo * 0.11;
        cool = bell(q.x - (fold - 0.038), 0.028) * 0.055;
        warm = second * 0.035;
    } else if (profile < 2.5) {
        // Block counter: central thin S-shaped seam, unlike the target's broad right-side fold.
        float seamX = 0.46342175 - 0.01712586 * sin(6.15805702 * q.y + 0.53917966);
        float seam = bell(q.x - seamX, 0.0031);
        float seamHalo = bell(q.x - seamX, 0.014);
        float foldX = 0.20 + 0.25 * q.y;
        float broad = bell(q.x - foldX, 0.033);
        reflection = seam * 0.43 + seamHalo * 0.085 + broad * 0.10;
        reflection += bell(q.x - 0.88, 0.09) * smoothstep(0.10, 0.95, q.y) * 0.065;
        cool = broad * 0.018;
    } else {
        // Music/potions/watermark: low-energy lower fold, not a target-card stripe repeated everywhere.
        float foldX = 0.28 + 0.32 * q.y;
        float bottom = smoothstep(0.48, 1.0, q.y);
        reflection = bell(q.x - foldX, 0.045) * bottom * 0.11;
        reflection += bell(q.x - 0.94, 0.047) * bottom * 0.065;
    }
    // Strength is independent of the lens setting; zero refraction does not erase reflections.
    float strength = clamp(base.r * 2.0, 0.0, 2.0);
    color += vec3(reflection) * strength;
    color += vec3(-cool * 0.35 + warm, cool * 0.28, cool - warm * 0.25) * strength;
    color += vec3(bayerDither4x4(gl_FragCoord.xy) / 255.0);
    fragColor = vec4(clamp(color, 0.0, 1.0), mask * opacity);
}
