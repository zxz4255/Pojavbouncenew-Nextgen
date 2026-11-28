#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

uniform sampler2D texture0;
uniform sampler2D DepthSampler;

layout(std140) uniform AtmosphereData {
    vec4 uParams0; // x = style (0=Future, 1=Millennium, 2=Sketch), y = intensity, z = timeSec, w = edgeStrength
    vec4 uParams1; // x = invWidth, y = invHeight, z = glowStrength, w = HatchOrDarkness
    vec4 uColorA;  // Primary color (Future=Blue, Millennium=BrightWhite, Sketch=Paper)
    vec4 uColorB;  // Secondary color (Future=Purple, Millennium=PaleYellow, Sketch=Ink)
    vec4 uColorC;  // Tertiary color (Future=NeonGreen)
};

float lumaOf(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec2 t = max(uParams1.xy, vec2(1e-5));
    vec4 baseSample = texture(texture0, texCoord);
    vec3 src = baseSample.rgb;
    float styleId = uParams0.x;
    float intensity = clamp(uParams0.y, 0.0, 1.0);
    float timeSec = uParams0.z;
    float edgeStrength = max(uParams0.w, 0.0);
    float glowStrength = max(uParams1.z, 0.0);
    float subParam = uParams1.w;

    // 3x3 neighborhood samples (used for Sobel block/texture edges AND soft dreamy bloom)
    vec3 cTL = texture(texture0, texCoord + vec2(-t.x, -t.y)).rgb;
    vec3 cTC = texture(texture0, texCoord + vec2( 0.0, -t.y)).rgb;
    vec3 cTR = texture(texture0, texCoord + vec2( t.x, -t.y)).rgb;
    vec3 cML = texture(texture0, texCoord + vec2(-t.x,  0.0)).rgb;
    vec3 cMR = texture(texture0, texCoord + vec2( t.x,  0.0)).rgb;
    vec3 cBL = texture(texture0, texCoord + vec2(-t.x,  t.y)).rgb;
    vec3 cBC = texture(texture0, texCoord + vec2( 0.0,  t.y)).rgb;
    vec3 cBR = texture(texture0, texCoord + vec2( t.x,  t.y)).rgb;

    float lTL = lumaOf(cTL);
    float lTC = lumaOf(cTC);
    float lTR = lumaOf(cTR);
    float lML = lumaOf(cML);
    float lMC = lumaOf(src);
    float lMR = lumaOf(cMR);
    float lBL = lumaOf(cBL);
    float lBC = lumaOf(cBC);
    float lBR = lumaOf(cBR);

    float gxL = (lTR + 2.0 * lMR + lBR) - (lTL + 2.0 * lML + lBL);
    float gyL = (lBL + 2.0 * lBC + lBR) - (lTL + 2.0 * lTC + lTR);
    float texEdge = length(vec2(gxL, gyL));

    // 3D Block Geometry Depth Sobel
    float dC = texture(DepthSampler, texCoord).r;
    float dL = texture(DepthSampler, texCoord - vec2(t.x, 0.0)).r;
    float dR = texture(DepthSampler, texCoord + vec2(t.x, 0.0)).r;
    float dU = texture(DepthSampler, texCoord - vec2(0.0, t.y)).r;
    float dD = texture(DepthSampler, texCoord + vec2(0.0, t.y)).r;
    float depthGrad = (abs(dR - dL) + abs(dD - dU)) * 220.0;
    float geomEdge = clamp(depthGrad, 0.0, 1.0);

    vec3 stylized = src;

    if (styleId < 0.5) {
        // =========================================================================
        // Mode 0: FUTURE / CYBERPUNK (未来样式 — 蓝 / 紫 / 绿 赛博朋克 + 灰暗底色，覆盖方块)
        // =========================================================================
        float darkScale = mix(0.62, 0.28, clamp(subParam, 0.0, 1.0));
        vec3 desat = vec3(lMC);
        vec3 darkSlate = mix(desat, src, 0.22) * darkScale * vec3(0.84, 0.90, 1.12);

        vec3 cyberBlue = uColorA.rgb;   // Electric Cyber Blue
        vec3 cyberPurple = uColorB.rgb; // Neon Violet-Purple
        vec3 cyberGreen = uColorC.rgb;  // Matrix Cyber Green

        // Block surface color remapping: shadows -> Purple, midtones -> Blue, highlights/foliage -> Green
        float greenAffinity = clamp((src.g - max(src.r, src.b) * 0.78) * 3.2, 0.0, 1.0);
        float shadowBand = 1.0 - smoothstep(0.04, 0.48, lMC);
        float midBand = smoothstep(0.14, 0.58, lMC) * (1.0 - smoothstep(0.62, 0.96, lMC));
        float highBand = smoothstep(0.52, 0.94, lMC);

        // Subtle traveling cyberpunk scan wave across 3D depth & screen space
        float scanWave = 0.5 + 0.5 * sin(dC * 95.0 - timeSec * 2.2 + texCoord.y * 18.0);
        vec3 neonTriad = cyberPurple * (shadowBand * 0.52 + 0.14)
                       + cyberBlue * (midBand * 0.68 + scanWave * 0.18)
                       + cyberGreen * (highBand * 0.62 + greenAffinity * 0.48);

        // Tint every block surface into moody dark slate + cyberpunk triad grading
        vec3 gradedBlocks = darkSlate + neonTriad * (0.28 + 0.42 * lMC);

        // Crisp 3D block silhouette & pixel-art neon rim lines (Blue -> Purple -> Green)
        float combinedEdge = clamp((geomEdge * 1.15 + texEdge * 0.68) * edgeStrength, 0.0, 1.0);
        float phase = 0.5 + 0.5 * sin(texCoord.x * 9.0 - texCoord.y * 7.0 + timeSec * 1.6);
        vec3 edgeNeon = mix(mix(cyberBlue, cyberPurple, phase), cyberGreen, clamp(geomEdge * 0.75 + greenAffinity * 0.5, 0.0, 1.0));

        // Wide multi-ring neon bloom around bright block lamps/ores/edges
        vec2 wStep = t * 4.5;
        vec3 wideA = texture(texture0, texCoord + vec2( wStep.x,  wStep.y)).rgb;
        vec3 wideB = texture(texture0, texCoord + vec2(-wStep.x,  wStep.y)).rgb;
        vec3 wideC = texture(texture0, texCoord + vec2( wStep.x, -wStep.y)).rgb;
        vec3 wideD = texture(texture0, texCoord + vec2(-wStep.x, -wStep.y)).rgb;
        vec3 localAvg = (cTL + cTC + cTR + cML + cMR + cBL + cBC + cBR + wideA + wideB + wideC + wideD) / 12.0;
        float bloomMask = smoothstep(0.42, 0.95, lumaOf(localAvg));
        vec3 bloomNeon = mix(cyberBlue, cyberGreen, 0.45) * bloomMask * 0.42 * glowStrength;

        // Subtle dark atmospheric cyber vignette
        vec2 vigUv = texCoord * 2.0 - 1.0;
        float vig = clamp(1.0 - dot(vigUv, vigUv) * 0.22, 0.62, 1.0);

        stylized = (gradedBlocks + edgeNeon * combinedEdge * (0.55 + 0.35 * glowStrength) + bloomNeon) * vig;
    } else if (styleId < 1.5) {
        // =========================================================================
        // Mode 1: MILLENNIUM / Y2K DREAMCORE (千禧年样式 — 亮白为主 + 淡黄，明亮如梦境)
        // =========================================================================
        vec2 b1 = t * 3.2;
        vec2 b2 = t * 7.5;
        vec3 ring1 = (
            texture(texture0, texCoord + vec2( b1.x,  0.0)).rgb +
            texture(texture0, texCoord + vec2(-b1.x,  0.0)).rgb +
            texture(texture0, texCoord + vec2( 0.0,  b1.y)).rgb +
            texture(texture0, texCoord + vec2( 0.0, -b1.y)).rgb
        ) * 0.25;
        vec3 ring2 = (
            texture(texture0, texCoord + vec2( b2.x,  b2.y)).rgb +
            texture(texture0, texCoord + vec2(-b2.x,  b2.y)).rgb +
            texture(texture0, texCoord + vec2( b2.x, -b2.y)).rgb +
            texture(texture0, texCoord + vec2(-b2.x, -b2.y)).rgb +
            texture(texture0, texCoord + vec2( b2.x * 1.4, 0.0)).rgb +
            texture(texture0, texCoord + vec2(-b2.x * 1.4, 0.0)).rgb +
            texture(texture0, texCoord + vec2( 0.0,  b2.y * 1.4)).rgb +
            texture(texture0, texCoord + vec2( 0.0, -b2.y * 1.4)).rgb
        ) * 0.125;

        vec3 softFocus = src * 0.25 + ring1 * 0.40 + ring2 * 0.35;
        float softLuma = lumaOf(softFocus);

        // High-key luminous lift so blocks, shadows & sky glow bright & airy
        float highKey = pow(clamp(mix(lMC, softLuma, 0.45), 0.0, 1.0), 0.56);
        highKey = clamp(0.28 + highKey * 0.80, 0.0, 1.0);

        vec3 pureWhite = uColorA.rgb;  // Dominant radiant bright white
        vec3 paleYellow = uColorB.rgb; // Warm pale cream-yellow secondary

        // Pale yellow kisses warm midtones; luminous pure white dominates highlights & veil
        float yellowBand = smoothstep(0.18, 0.56, highKey) * (1.0 - smoothstep(0.68, 0.96, highKey));
        vec3 dreamPalette = mix(pureWhite, paleYellow, yellowBand * 0.58 + 0.14);

        // Keep delicate pastel block detail so block shapes & textures remain clear and playable
        vec3 pastelBlock = mix(vec3(highKey), clamp(src * 1.32 + 0.18, 0.0, 1.0), 0.28);
        vec3 dreamBase = mix(pastelBlock * dreamPalette, dreamPalette, 0.46);

        // Ethereal Orton dream halation + soft luminous white bloom
        float halo = smoothstep(0.36, 0.92, softLuma) * glowStrength;
        float shimmer = 0.5 + 0.5 * sin(texCoord.x * 6.0 + texCoord.y * 5.0 + timeSec * 1.1);
        vec3 dreamGlow = mix(paleYellow, pureWhite, 0.74 + 0.16 * shimmer) * (halo * 0.44);

        // Soft bright edge halo around sky/horizon & sunlit blocks
        stylized = clamp(dreamBase + dreamGlow + pureWhite * (0.08 * glowStrength), 0.0, 1.0);
    } else {
        // =========================================================================
        // Mode 2: SKETCH (素描样式 — 黑白手绘铅笔线稿 + 交叉排线阴影，覆盖所有方块)
        // =========================================================================
        vec2 pixelPos = texCoord / t;
        float grain = (hash12(floor(pixelPos * 0.75)) - 0.5) * 0.06;

        // Clean perceptual tone of blocks & world
        float tone = clamp(pow(clamp(lMC, 0.0, 1.0), 0.88) + grain, 0.0, 1.0);

        // Combine 3D block silhouette ink lines + block pixel-art graphite strokes
        float outline = clamp((geomEdge * 1.35 + texEdge * 0.92) * edgeStrength, 0.0, 1.0);
        outline = smoothstep(0.10, 0.58, outline);

        // Multi-angle procedural pencil cross-hatching across shaded block faces
        float hatchScale = mix(0.65, 1.45, clamp(subParam, 0.0, 1.0));
        float p1 = sin((pixelPos.x + pixelPos.y) * 0.85 * hatchScale);
        float p2 = sin((pixelPos.x - pixelPos.y) * 0.85 * hatchScale);
        float p3 = sin((pixelPos.x * 0.35 + pixelPos.y * 1.15) * 1.05 * hatchScale);

        float hatchInk = 0.0;
        if (tone < 0.66) {
            float w1 = smoothstep(0.66, 0.34, tone);
            hatchInk = max(hatchInk, smoothstep(0.45, 0.88, p1) * w1 * 0.42);
        }
        if (tone < 0.42) {
            float w2 = smoothstep(0.42, 0.16, tone);
            hatchInk = max(hatchInk, smoothstep(0.40, 0.86, p2) * w2 * 0.62);
        }
        if (tone < 0.22) {
            float w3 = smoothstep(0.22, 0.02, tone);
            hatchInk = max(hatchInk, smoothstep(0.25, 0.80, p3) * w3 * 0.82);
        }

        // Soft graphite tonal wash + cross-hatching + crisp ink/pencil outlines
        float paperShade = mix(0.22, 1.0, smoothstep(0.04, 0.82, tone));
        float inkAmount = clamp(max(outline, hatchInk) + (1.0 - paperShade) * 0.55, 0.0, 1.0);

        vec3 paperCol = uColorA.rgb * (0.97 + grain * 0.45);
        vec3 inkCol = uColorB.rgb;
        stylized = mix(paperCol, inkCol, inkAmount);
    }

    fragColor = vec4(mix(src, clamp(stylized, 0.0, 1.0), intensity), 1.0);
}
