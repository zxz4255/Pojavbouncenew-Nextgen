#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D SceneColorSampler;
uniform sampler2D SceneDepthSampler;

layout(std140) uniform MirrorGroundData {
    vec4 uViewProj0;
    vec4 uViewProj1;
    vec4 uViewProj2;
    vec4 uViewProj3;
    vec4 uInvViewProj0;
    vec4 uInvViewProj1;
    vec4 uInvViewProj2;
    vec4 uInvViewProj3;
    vec4 uCameraPosAndTime;    // xyz = cameraPos, w = timeSeconds
    vec4 uMeshOriginRelCam;    // xyz = meshOrigin - cameraPos, w = maxRadius
    vec4 uDepthClipParams;     // x = clipZScale, y = clipZBias, z = reversedZ, w = maxRayDist
    vec4 uMirrorParams;        // x = mirrorOpacity, y = glossStrength, z = fresnelStrength, w = mirrorSoftness
    vec4 uGlazeTint;           // rgb = glazeHighlightColor, a = basePolishAlpha
    vec4 uScreenAndCounts;     // x = invScreenW, y = invScreenH, z = entityCount, w = skyBrightness
    vec4 uEntity0;             // xyz = relFootPos, w = height
    vec4 uEntity1;
    vec4 uEntity2;
    vec4 uEntity3;
    vec4 uEntityRadii;         // xyzw = r0..r3
    vec4 uSunDirAndFallback;   // xyz = sunDir, w = offscreenFallbackStrength
};

layout(location = 0) in vec3 vRelPos;
layout(location = 1) in vec4 vClipPos;
layout(location = 2) in vec2 vSurfaceInfo;
layout(location = 3) in vec4 vColor;
layout(location = 4) in float vEdgeFeather;

layout(location = 0) out vec4 fragColor;

bool isSkyDepth(float rawD) {
    return (uDepthClipParams.z > 0.5) ? (rawD <= 1e-6) : (rawD >= 0.999999);
}

vec3 reconstructRelPos(vec2 uv, float rawD, mat4 ivp) {
    vec2 ndcXY = uv * 2.0 - 1.0;
    float zNdc = rawD * uDepthClipParams.x + uDepthClipParams.y;
    vec4 h = ivp * vec4(ndcXY, zNdc, 1.0);
    return h.xyz / max(abs(h.w), 1e-7);
}

// Contact-hardening 5-tap glaze sampler: sharp right at entity feet / block base, softly polished further out
vec3 samplePolishedReflection(vec2 uv, float blurPx) {
    if (blurPx <= 0.15) {
        return textureLod(SceneColorSampler, uv, 0.0).rgb;
    }
    vec2 stepUv = vec2(uScreenAndCounts.x, uScreenAndCounts.y) * blurPx;
    vec3 c0 = textureLod(SceneColorSampler, uv, 0.0).rgb * 0.36;
    vec3 c1 = textureLod(SceneColorSampler, clamp(uv + vec2( stepUv.x,  stepUv.y * 0.6), vec2(0.002), vec2(0.998)), 0.0).rgb * 0.16;
    vec3 c2 = textureLod(SceneColorSampler, clamp(uv + vec2(-stepUv.x, -stepUv.y * 0.6), vec2(0.002), vec2(0.998)), 0.0).rgb * 0.16;
    vec3 c3 = textureLod(SceneColorSampler, clamp(uv + vec2(-stepUv.x * 0.6,  stepUv.y), vec2(0.002), vec2(0.998)), 0.0).rgb * 0.16;
    vec3 c4 = textureLod(SceneColorSampler, clamp(uv + vec2( stepUv.x * 0.6, -stepUv.y), vec2(0.002), vec2(0.998)), 0.0).rgb * 0.16;
    return c0 + c1 + c2 + c3 + c4;
}

// Flat-mirror analytic entity capsule fallback when camera looks steeply down and entity extends above top of screen
float flatMirrorEntitySilhouette(vec3 surfRelPos, vec3 reflRay, vec4 entPosH, float radius) {
    float h = entPosH.w;
    if (h <= 0.01) return 0.0;
    vec2 toEnt = entPosH.xz - surfRelPos.xz;
    vec2 rXz = reflRay.xz;
    float rXzLenSq = max(dot(rXz, rXz), 1e-5);
    float s = max(0.0, dot(toEnt, rXz) / rXzLenSq);
    vec3 hit = surfRelPos + reflRay * s;
    float horizDist = length(hit.xz - entPosH.xz);
    float botY = entPosH.y;
    float topY = entPosH.y + h;
    float vertDist = max(max(botY - hit.y, hit.y - topY), 0.0);
    float softR = max(radius * 1.15, 0.24);
    float normD = length(vec2(horizDist / softR, vertDist / (softR * 1.1)));
    float mask = 1.0 - smoothstep(0.35, 1.0, normD);
    float distFade = 1.0 - clamp(s / max(h * 3.5, 2.5), 0.0, 1.0) * 0.65;
    return mask * distFade;
}

void main() {
    float edgeFade = vEdgeFeather * vColor.a;
    if (edgeFade <= 0.003 || vClipPos.w <= 0.05) {
        discard;
    }

    vec2 screenUv = (vClipPos.xy / vClipPos.w) * 0.5 + 0.5;
    if (screenUv.x < 0.001 || screenUv.x > 0.999 || screenUv.y < 0.001 || screenUv.y > 0.999) {
        discard;
    }

    float rawFloorDepth = textureLod(SceneDepthSampler, screenUv, 0.0).r;
    if (isSkyDepth(rawFloorDepth)) {
        discard;
    }

    mat4 vp  = mat4(uViewProj0, uViewProj1, uViewProj2, uViewProj3);
    mat4 ivp = mat4(uInvViewProj0, uInvViewProj1, uInvViewProj2, uInvViewProj3);

    float surfDist = length(vRelPos);
    vec3 visibleSceneRel = reconstructRelPos(screenUv, rawFloorDepth, ivp);
    float visibleDist = length(visibleSceneRel);

    // Hardware-accurate occlusion: discard if an entity, fence, grass blade, wall, or nearer block is in front
    float occlTol = 0.06 + surfDist * 0.010;
    if (surfDist > visibleDist + occlTol) {
        discard;
    }

    // Strictly flat zero-undulation normal: smooth polished mirror plane (N = +Y, zero waves!)
    vec3 I = vRelPos / max(surfDist, 1e-4);
    vec3 V = -I;
    vec3 R = vec3(I.x, abs(I.y), I.z); // Exact planar mirror reflection upward into surrounding blocks & entities

    // Schlick Fresnel on optically flat surface
    float ndotv = clamp(V.y, 0.0, 1.0);
    float schlick = 0.14 + 0.86 * pow(1.0 - ndotv, 3.4);
    float fresnel = mix(0.56, schlick, uMirrorParams.z);

    // Progressive 3D Screen-Space Raymarch + 4-step binary refinement for crisp inverted entity & block reflections
    float maxRayDist = max(uDepthClipParams.w, 4.0);
    float jitter = fract(52.9829189 * fract(dot(floor(gl_FragCoord.xy), vec2(0.06711056, 0.00583715))));

    bool hitFound = false;
    vec2 hitUv = screenUv;
    float hitTravel = maxRayDist;
    float screenBorderFade = 0.0;

    float sPrev = 0.035;
    const int STEPS = 28;
    for (int i = 1; i <= STEPS; i++) {
        float fi = (float(i) - 0.60 + jitter * 0.50) / float(STEPS);
        float s = 0.045 + (fi * fi * 0.76 + fi * 0.24) * maxRayDist;
        vec3 Q = vRelPos + R * s;

        vec4 clipQ = vp * vec4(Q, 1.0);
        if (clipQ.w <= 0.05) {
            break;
        }
        vec2 uvQ = (clipQ.xy / clipQ.w) * 0.5 + 0.5;
        if (uvQ.x < 0.002 || uvQ.x > 0.998 || uvQ.y < 0.002 || uvQ.y > 0.998) {
            break;
        }

        float dRaw = textureLod(SceneDepthSampler, uvQ, 0.0).r;
        if (!isSkyDepth(dRaw)) {
            vec3 sceneP = reconstructRelPos(uvQ, dRaw, ivp);
            // Only reflect objects above this flat block surface so the floor never reflects itself
            if (sceneP.y > vRelPos.y + 0.03) {
                float qDist = length(Q);
                float pDist = length(sceneP);
                float pen = qDist - pDist;
                float thickness = 1.20 + s * 0.09;
                if (pen >= -0.015 && pen <= thickness) {
                    float lo = sPrev;
                    float hi = s;
                    vec2 bestUv = uvQ;
                    for (int b = 0; b < 4; b++) {
                        float mid = 0.5 * (lo + hi);
                        vec3 Qm = vRelPos + R * mid;
                        vec4 cm = vp * vec4(Qm, 1.0);
                        if (cm.w > 0.05) {
                            vec2 uvm = (cm.xy / cm.w) * 0.5 + 0.5;
                            if (uvm.x >= 0.001 && uvm.x <= 0.999 && uvm.y >= 0.001 && uvm.y <= 0.999) {
                                float dm = textureLod(SceneDepthSampler, uvm, 0.0).r;
                                if (!isSkyDepth(dm)) {
                                    vec3 spm = reconstructRelPos(uvm, dm, ivp);
                                    if (spm.y > vRelPos.y + 0.025 && length(Qm) >= length(spm) - 0.015) {
                                        hi = mid;
                                        bestUv = uvm;
                                    } else {
                                        lo = mid;
                                    }
                                } else {
                                    lo = mid;
                                }
                            }
                        }
                    }
                    hitFound = true;
                    hitUv = bestUv;
                    hitTravel = hi;
                    vec2 bDist = min(bestUv, 1.0 - bestUv);
                    screenBorderFade = smoothstep(0.003, 0.08, min(bDist.x, bDist.y));
                    break;
                }
            }
        }
        sPrev = s;
    }

    float mirrorStrength = uMirrorParams.x; // User reflection opacity ("隐约显倒像")
    float glossStrength  = uMirrorParams.y; // Smooth polished surface sheen ("平滑光面")
    float softness       = uMirrorParams.w;
    float skyBright      = clamp(uScreenAndCounts.w, 0.30, 1.0);

    // Smooth flat-plane sun + sky polish glaze (zero ripples, zero wave bands)
    vec3 sunDir = normalize(uSunDirAndFallback.xyz);
    vec3 H = normalize(sunDir + V);
    float ndoth = max(H.y, 0.0);
    float broadPolish = pow(ndoth, 24.0) * 0.30;
    float crispPolish = pow(ndoth, 96.0) * 0.42;
    vec3 fwdLight = normalize(vec3(V.x, 0.58, V.z));
    vec3 Hfwd = normalize(fwdLight + V);
    float forwardGlaze = pow(max(Hfwd.y, 0.0), 22.0) * 0.22;
    float horizonSheen = exp(-abs(R.y - 0.10) * 4.8) * 0.24;

    float polishSpec = (broadPolish + crispPolish + forwardGlaze + horizonSheen) * glossStrength;
    vec3 glazeRgb = uGlazeTint.rgb * skyBright;

    // Sample sky color along reflected direction if visible on screen
    vec4 skyClip = vp * vec4(vRelPos + R * (maxRayDist * 1.5), 1.0);
    if (skyClip.w > 0.05) {
        vec2 skyUv = (skyClip.xy / skyClip.w) * 0.5 + 0.5;
        if (skyUv.x > 0.01 && skyUv.x < 0.99 && skyUv.y > 0.01 && skyUv.y < 0.99) {
            float dSky = textureLod(SceneDepthSampler, skyUv, 0.0).r;
            if (isSkyDepth(dSky)) {
                vec2 sb = min(skyUv, 1.0 - skyUv);
                float skyEdge = smoothstep(0.01, 0.10, min(sb.x, sb.y));
                vec3 realSky = textureLod(SceneColorSampler, skyUv, 0.0).rgb;
                glazeRgb = mix(glazeRgb, realSky, skyEdge * 0.55);
            }
        }
    }

    // Evaluate inverted mirror reflection of surrounding entities & blocks
    vec3 reflRgb = glazeRgb;
    float reflAlpha = 0.0;

    if (hitFound && screenBorderFade > 0.002) {
        float contactBlur = clamp(0.18 + hitTravel * 0.38, 0.18, 2.8) * softness;
        vec3 ssrColor = samplePolishedReflection(hitUv, contactBlur);
        float travelFade = 1.0 - smoothstep(maxRayDist * 0.42, maxRayDist, hitTravel);
        float ssrWeight = screenBorderFade * travelFade;
        reflRgb = ssrColor;
        reflAlpha = mirrorStrength * (0.58 + 0.42 * fresnel) * ssrWeight;
    }

    // Smooth off-screen fallback when looking straight down near an entity or wall
    float fallbackNeed = 1.0 - (hitFound ? screenBorderFade : 0.0);
    if (fallbackNeed > 0.02 && uSunDirAndFallback.w > 0.01) {
        int entCount = int(uScreenAndCounts.z + 0.5);
        float entMask = 0.0;
        if (entCount > 0) entMask = max(entMask, flatMirrorEntitySilhouette(vRelPos, R, uEntity0, uEntityRadii.x));
        if (entCount > 1) entMask = max(entMask, flatMirrorEntitySilhouette(vRelPos, R, uEntity1, uEntityRadii.y));
        if (entCount > 2) entMask = max(entMask, flatMirrorEntitySilhouette(vRelPos, R, uEntity2, uEntityRadii.z));
        if (entCount > 3) entMask = max(entMask, flatMirrorEntitySilhouette(vRelPos, R, uEntity3, uEntityRadii.w));

        float wallMask = clamp(vSurfaceInfo.y * 0.72, 0.0, 0.85);
        float fbMask = max(entMask, wallMask) * fallbackNeed * uSunDirAndFallback.w;
        if (fbMask > 0.005) {
            vec3 fbColor = mix(vColor.rgb * 0.52, glazeRgb * 0.45, 0.35);
            float fbAlpha = mirrorStrength * 0.65 * fbMask * (0.55 + 0.45 * fresnel);
            if (fbAlpha > reflAlpha) {
                reflRgb = mix(fbColor, reflRgb, clamp(reflAlpha / max(fbAlpha, 1e-4), 0.0, 1.0));
                reflAlpha = max(reflAlpha, fbAlpha);
            }
        }
    }

    // Combine faint inverted reflection ("隐约倒像") + smooth flat glossy glaze ("平滑光面")
    bool isWater = vSurfaceInfo.x > 1.0;
    float baseFilm = uGlazeTint.a * (0.30 + 0.70 * fresnel) * (isWater ? 1.25 : 1.0);
    float specAlpha = clamp(polishSpec * 0.42, 0.0, 0.45);
    float glazeAlpha = clamp(baseFilm + specAlpha, 0.0, 0.55);

    // Composite reflection layer & surface specular glaze in premultiplied alpha space
    vec3 premul = reflRgb * reflAlpha
        + glazeRgb * (baseFilm * (1.0 - reflAlpha * 0.55))
        + uGlazeTint.rgb * (polishSpec * 0.48);
    float outAlpha = clamp(reflAlpha + glazeAlpha * (1.0 - reflAlpha * 0.65), 0.0, 0.86) * edgeFade;
    premul *= edgeFade;

    if (outAlpha <= 0.002) {
        discard;
    }

    fragColor = vec4(clamp(premul, vec3(0.0), vec3(outAlpha + 0.25)), outAlpha);
}
