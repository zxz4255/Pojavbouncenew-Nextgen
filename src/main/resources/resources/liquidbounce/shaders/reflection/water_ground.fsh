#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

layout(std140) uniform WaterReflectionData {
    vec4 uCameraPosAndTime;    // xyz = cameraPos, w = timeSeconds
    vec4 uMeshOriginRelCam;    // xyz = meshOrigin - cameraPos, w = maxRadius
    vec4 uSurfaceParams;       // x = glossStrength, y = fresnelStrength, z = smoothness, w = pseudoShadowStrength
    vec4 uStyleParams;         // x = subtleWave, y = puddleMode, z = puddleScale, w = entityCount
    vec4 uHighlightColor;      // rgb = smooth highlight color, a = baseFilmAlpha
    vec4 uWaterTint;           // rgb = water/wet tint, a = pseudoShimmer
    vec4 uSunDirAndSky;        // xyz = sunDirection, w = skyBrightness
    vec4 uEntity0;             // xyz = relFootPos, w = height
    vec4 uEntity1;
    vec4 uEntity2;
    vec4 uEntity3;
    vec4 uEntity4;
    vec4 uEntity5;
    vec4 uEntityRadii0;        // xyzw = r0..r3
    vec4 uEntityRadii1;        // xy = r4..r5, zw = unused
};

layout(location = 0) in vec3 vWorldPos;
layout(location = 1) in vec3 vRelPos;
layout(location = 2) in vec2 vSurfaceInfo; // x = waterFlag, y = wallPseudoShadow (0..1)
layout(location = 3) in vec4 vColor;
layout(location = 4) in float vEdgeFeather;

layout(location = 0) out vec4 fragColor;

// Ultra-cheap smooth puddle field (zero hash/valueNoise grain — pure smooth harmonic field)
float smoothPuddleMask(vec2 xz) {
    float a = sin(xz.x * 0.72 + cos(xz.y * 0.58) * 1.35);
    float b = cos(xz.y * 0.81 - sin(xz.x * 0.64) * 1.25);
    float c = sin((xz.x + xz.y) * 0.43 + 1.7);
    return (a + b + c * 0.65) * 0.19 + 0.52;
}

// Analytic mirrored-capsule pseudo-reflection weight along the eye ray `O -> P` (6 float ops, branchless math)
float entityPseudoShadow(vec3 surfRelPos, vec3 rayDir, vec4 entPosH, float radius) {
    float h = entPosH.w;
    if (h <= 0.01) return 0.0;

    // Mirrored virtual segment extends downward below the entity's feet: [footY - h, footY]
    // Find ray height at closest horizontal approach to entity XZ
    vec2 toEnt = entPosH.xz - surfRelPos.xz;
    vec2 dirXz = rayDir.xz;
    float dirXzLenSq = max(dot(dirXz, dirXz), 1e-5);
    float tRay = max(0.0, dot(toEnt, dirXz) / dirXzLenSq);

    vec3 closestPt = surfRelPos + rayDir * tRay;
    float horizDist = length(closestPt.xz - entPosH.xz);

    float topY = entPosH.y + 0.05;
    float botY = entPosH.y - h * 1.15;
    float vertDist = max(max(botY - closestPt.y, closestPt.y - topY), 0.0);

    // Smooth capsule falloff + vertical fade along the stretched reflection
    float softRad = max(radius * 1.45, 0.28);
    float normDist = length(vec2(horizDist / softRad, vertDist / (softRad * 1.25)));
    float mask = 1.0 - smoothstep(0.22, 1.0, normDist);
    float heightFade = 1.0 - clamp((topY - closestPt.y) / max(h * 1.35, 0.4), 0.0, 1.0) * 0.55;
    return mask * heightFade;
}

void main() {
    float edgeFade = vEdgeFeather * vColor.a;
    if (edgeFade <= 0.003) {
        discard;
    }

    bool isWater = vSurfaceInfo.x > 1.0;

    // Optional smooth puddle mask on dry ground in WaterAndPuddles mode
    float puddleCoverage = 1.0;
    float puddleRim = 0.0;
    if (uStyleParams.y > 0.5 && !isWater) {
        float field = smoothPuddleMask(vWorldPos.xz * uStyleParams.z * 2.2);
        puddleCoverage = smoothstep(0.46, 0.62, field);
        puddleRim = smoothstep(0.44, 0.51, field) * (1.0 - smoothstep(0.51, 0.60, field));
        if (puddleCoverage <= 0.004) {
            discard;
        }
    }

    // Silky-smooth low-frequency surface normal (zero grainy noise!)
    float waveAmt = uStyleParams.x;
    float t = uCameraPosAndTime.w;
    vec2 wp = vWorldPos.xz;
    float sx = sin(wp.x * 0.85 + wp.y * 0.45 + t * 1.15) + 0.5 * cos(wp.x * 0.42 - wp.y * 0.78 - t * 0.82);
    float sz = cos(wp.y * 0.88 - wp.x * 0.39 + t * 1.05) + 0.5 * sin(wp.x * 0.68 + wp.y * 0.52 - t * 0.74);
    float tilt = waveAmt * 0.045;
    vec3 N = normalize(vec3(-sx * tilt, 1.0, -sz * tilt));

    float dist = max(length(vRelPos), 1e-4);
    vec3 rayDir = vRelPos / dist; // Camera -> surface ray (points forward & downward)
    vec3 V = -rayDir;

    // Smooth Schlick Fresnel grazing sheen ("平滑亮光")
    float ndotv = clamp(dot(N, V), 0.0, 1.0);
    float fresnelRaw = 0.06 + 0.94 * pow(1.0 - ndotv, 3.2);
    float fresnel = mix(0.55, clamp(fresnelRaw * 1.35 + 0.14, 0.0, 1.0), uSurfaceParams.y);

    // Reflected sky/horizon vector for smooth mirror gradient + subtle pseudo-reflection bands ("伪影流光")
    vec3 R = reflect(rayDir, N);
    float horizonBand = exp(-abs(R.y - 0.12) * 5.2);
    float skyDome = smoothstep(-0.08, 0.65, R.y);

    // Smooth pseudo-reflection interference bands (view-parallax fake architectural/cloud streaks, zero noise)
    vec2 projXz = wp + (R.xz / max(R.y + 0.38, 0.22)) * 1.65;
    float pseudoBandA = 0.5 + 0.5 * sin(projXz.x * 0.62 + sin(projXz.y * 0.41) * 1.4);
    float pseudoBandB = 0.5 + 0.5 * cos(projXz.y * 0.53 - cos(projXz.x * 0.37) * 1.3);
    float pseudoArtifact = smoothstep(0.25, 0.88, pseudoBandA * 0.58 + pseudoBandB * 0.42) * uWaterTint.a;

    // Smooth dual-lobe sun + sky specular highlight ("平滑亮光")
    vec3 sunDir = normalize(uSunDirAndSky.xyz);
    vec3 H = normalize(sunDir + V);
    float ndoth = max(dot(N, H), 0.0);
    float smoothness = clamp(uSurfaceParams.z, 0.05, 1.0);
    float broadExp = mix(8.0, 38.0, smoothness);
    float coreExp  = mix(28.0, 130.0, smoothness);
    float broadSpec = pow(ndoth, broadExp) * 0.48;
    float coreSpec  = pow(ndoth, coreExp) * 0.68;
    // Forward view-aligned glossy ribbon so wet ground always has a sleek sheen ahead of the camera
    vec3 forwardLight = normalize(vec3(V.x, 0.52, V.z));
    vec3 Hfwd = normalize(forwardLight + V);
    float ribbonSpec = pow(max(dot(N, Hfwd), 0.0), mix(10.0, 32.0, smoothness)) * 0.36;

    float gloss = (broadSpec + coreSpec + ribbonSpec + horizonBand * 0.32) * uSurfaceParams.x;

    // Obstacle/wall vertex-interpolated pseudo-reflection + entity mirrored-capsule pseudo-reflection ("伪影")
    float wallShadow = clamp(vSurfaceInfo.y, 0.0, 1.0);
    float entShadow = 0.0;
    int entCount = int(uStyleParams.w + 0.5);
    if (entCount > 0) {
        entShadow = max(entShadow, entityPseudoShadow(vRelPos, rayDir, uEntity0, uEntityRadii0.x));
        if (entCount > 1) entShadow = max(entShadow, entityPseudoShadow(vRelPos, rayDir, uEntity1, uEntityRadii0.y));
        if (entCount > 2) entShadow = max(entShadow, entityPseudoShadow(vRelPos, rayDir, uEntity2, uEntityRadii0.z));
        if (entCount > 3) entShadow = max(entShadow, entityPseudoShadow(vRelPos, rayDir, uEntity3, uEntityRadii0.w));
        if (entCount > 4) entShadow = max(entShadow, entityPseudoShadow(vRelPos, rayDir, uEntity4, uEntityRadii1.x));
        if (entCount > 5) entShadow = max(entShadow, entityPseudoShadow(vRelPos, rayDir, uEntity5, uEntityRadii1.y));
    }

    float pseudoStrength = uSurfaceParams.w;
    float combinedPseudoOccl = clamp(max(wallShadow * 0.85, entShadow) * pseudoStrength, 0.0, 0.88);

    // Compose smooth sky sheen + tinted obstacle/entity pseudo-reflection + highlight
    float skyBright = clamp(uSunDirAndSky.w, 0.28, 1.0);
    vec3 baseTint = isWater ? mix(uWaterTint.rgb, vColor.rgb, 0.35) : uWaterTint.rgb;
    vec3 skySheenRgb = mix(baseTint, uHighlightColor.rgb, clamp(0.38 + 0.45 * fresnel + 0.25 * skyDome, 0.0, 1.0)) * skyBright;
    skySheenRgb += uHighlightColor.rgb * pseudoArtifact * 0.24;

    // Darken & tint inside wall/entity pseudo-reflection ("伪影") while keeping subtle specular glaze on top
    vec3 pseudoDarkRgb = mix(vColor.rgb * 0.28, baseTint * 0.22, 0.55);
    vec3 surfaceRgb = mix(skySheenRgb, pseudoDarkRgb, combinedPseudoOccl);
    surfaceRgb += uHighlightColor.rgb * gloss * (1.0 - combinedPseudoOccl * 0.42);
    surfaceRgb += uHighlightColor.rgb * puddleRim * 0.18;

    float sheenAlpha = uHighlightColor.a * (0.35 + 0.65 * fresnel)
        + gloss * 0.46
        + pseudoArtifact * 0.10
        + combinedPseudoOccl * 0.26;
    if (isWater) {
        sheenAlpha = max(sheenAlpha, uHighlightColor.a * 0.55 + gloss * 0.42);
    }

    float finalAlpha = clamp(sheenAlpha * puddleCoverage * edgeFade, 0.0, 0.88);
    if (finalAlpha <= 0.003) {
        discard;
    }

    fragColor = vec4(clamp(surfaceRgb, 0.0, 1.0), finalAlpha) * ColorModulator;
}
