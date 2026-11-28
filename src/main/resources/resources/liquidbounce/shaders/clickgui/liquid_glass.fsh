#version 330
#extension GL_ARB_separate_shader_objects : require

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

void main() {
    vec4 baseColor = vColor * ColorModulator;

    // Pass Mode 1: Fused Dual-Layer Elevation Shadow + Tinted Glass Edge Caustic (vMode < 0.0)
    if (vMode < -0.001) {
        float spread = -vMode;
        float dBody = sdCssRoundedBox(vLocalPos, vHalfSize, vRadii.x, vRadii.y, vRadii.z, vRadii.w);
        vec2 shiftedPos = vLocalPos - vec2(0.0, min(spread * 0.22, 4.5));
        float dShifted = sdCssRoundedBox(shiftedPos, vHalfSize, vRadii.x, vRadii.y, vRadii.z, vRadii.w);

        float shadowFactor = evalGlassElevationShadow(dShifted, dBody, spread);
        float dither = bayerDither4x4(gl_FragCoord.xy) * (1.25 / 255.0);
        float outAlpha = clamp(baseColor.a * shadowFactor + dither * step(0.003, shadowFactor), 0.0, 1.0);
        if (outAlpha <= 0.002) {
            discard;
        }

        // Subtle glass caustic transmission right outside the curved glass edge (0..6px)
        float causticBand = exp(-max(dBody, 0.0) * 0.35) * smoothstep(-0.4, 0.6, dBody);
        vec3 backdropSample = texture(Sampler0, clamp(vScreen, vec2(0.002), vec2(0.998))).rgb;
        vec3 shadowRgb = mix(baseColor.rgb, backdropSample * 0.38 + baseColor.rgb * 0.62, causticBand * 0.28);

        fragColor = vec4(shadowRgb, outAlpha);
        return;
    }

    // Analytical SDF + outward unit normal for both Glass Body and Directional Fresnel Rim
    vec3 sdfGrad = sdCssRoundedBoxGrad(vLocalPos, vHalfSize, vRadii.x, vRadii.y, vRadii.z, vRadii.w);
    float d = sdfGrad.x;
    vec2 guiNormal = sdfGrad.yz;

    // Pass Mode 3: Directional Specular Fresnel Rim + Corner Glint (vMode >= 100.0)
    if (vMode >= 99.5) {
        float borderW = max(vMode - 100.0, 0.5);
        float outerMask = 1.0 - smoothstep(-0.55, 0.55, d);
        float innerMask = smoothstep(-borderW - 0.60, -borderW + 0.55, d);
        float ring = outerMask * innerMask;
        if (ring <= 0.002) {
            discard;
        }

        // Top-left crystal key light + bottom-right bounce reflection + grazing Fresnel base
        vec2 keyLight = vec2(-0.60, -0.80);
        vec2 bounceLight = vec2(0.60, 0.80);
        float specKey = pow(max(dot(guiNormal, keyLight), 0.0), 1.75);
        float specBounce = pow(max(dot(guiNormal, bounceLight), 0.0), 2.55) * 0.46;
        float grazing = 0.24;
        float rimLight = clamp(grazing + specKey * 0.96 + specBounce, 0.0, 1.35);

        vec3 rimRgb = mix(baseColor.rgb, vec3(1.0), specKey * 0.42);
        float outAlpha = clamp(baseColor.a * ring * rimLight, 0.0, 1.0);
        if (outAlpha <= 0.002) {
            discard;
        }
        fragColor = vec4(rimRgb, outAlpha);
        return;
    }

    // Pass Mode 2: Fused Blurred Backdrop + Curved-Bezel Lens Refraction + Chromatic Dispersion +
    // Frosted Tint + Diagonal Crystal Sheen + Inner Bevel Depth (0.0 <= vMode < 99.5)
    float edgeAA = 1.0 - smoothstep(-0.55, 0.55, d);
    if (edgeAA <= 0.002) {
        discard;
    }

    float opacityBucket = floor(vMode);
    float layerOpacity = (opacityBucket >= 1.0) ? clamp(opacityBucket / 64.0, 0.0, 1.0) : 1.0;
    float refractStrength = (opacityBucket >= 1.0) ? ((vMode - opacityBucket) * 4.0) : 1.0;

    // Convex rounded bezel profile inside [-bezelWidth .. 0]
    float minHalf = max(min(vHalfSize.x, vHalfSize.y), 1.0);
    float bezelWidth = clamp(minHalf * 0.42, 3.5, 15.0);
    float bezelCurve = pow(smoothstep(-bezelWidth, 0.25, d), 2.05);

    vec2 texel = vec2(1.0) / max(vec2(textureSize(Sampler0, 0)), vec2(1.0));
    // Convert GUI normal (+y down) to FBO UV normal (+v up) and bend rays inward through the convex bezel
    vec2 fboNormal = vec2(guiNormal.x, -guiNormal.y);
    vec2 refractVec = -fboNormal * bezelCurve * refractStrength * texel * 10.5;

    // Subtle chromatic dispersion across the curved glass bevel
    vec2 uvR = clamp(vScreen + refractVec * 0.84, vec2(0.002), vec2(0.998));
    vec2 uvG = clamp(vScreen + refractVec * 1.00, vec2(0.002), vec2(0.998));
    vec2 uvB = clamp(vScreen + refractVec * 1.16, vec2(0.002), vec2(0.998));

    vec3 backdrop;
    if (refractStrength > 0.02 && bezelCurve > 0.01) {
        backdrop = vec3(
            texture(Sampler0, uvR).r,
            texture(Sampler0, uvG).g,
            texture(Sampler0, uvB).b
        );
    } else {
        backdrop = texture(Sampler0, clamp(vScreen, vec2(0.002), vec2(0.998))).rgb;
    }

    backdrop = boostFrostVibrance(backdrop, 1.14);

    // Blend frosted world backdrop with translucent glass body tint
    float tintDensity = clamp(baseColor.a, 0.0, 0.94);
    vec3 glassRgb = mix(backdrop, baseColor.rgb, tintDensity);

    // Subtle diagonal top-left -> bottom-right crystal surface sheen
    vec2 normLocal = vLocalPos / max(vHalfSize, vec2(1.0));
    float diag = clamp((-normLocal.x * 0.48 - normLocal.y * 0.68) * 0.5 + 0.5, 0.0, 1.0);
    float sheen = pow(diag, 2.2) * 0.075;
    glassRgb += vec3(sheen);

    // Inner bevel caustics: luminous top inner highlight + soft bottom inner depth shadow
    float innerBand = exp(-max(-d, 0.0) * 0.42);
    float topCaustic = max(-guiNormal.y, 0.0) * innerBand * 0.11;
    float bottomDepth = max(guiNormal.y, 0.0) * innerBand * 0.10;
    glassRgb = glassRgb * (1.0 - bottomDepth) + vec3(topCaustic);

    // Micro-frost crystal grain + ordered Bayer anti-banding dither
    float grain = frostMicroGrain(gl_FragCoord.xy) * 0.011;
    float dither = bayerDither4x4(gl_FragCoord.xy) * (1.0 / 255.0);
    glassRgb = clamp(glassRgb + vec3(grain + dither), vec3(0.0), vec3(1.0));

    float outAlpha = edgeAA * layerOpacity;
    if (outAlpha <= 0.002) {
        discard;
    }
    fragColor = vec4(glassRgb, outAlpha);
}
