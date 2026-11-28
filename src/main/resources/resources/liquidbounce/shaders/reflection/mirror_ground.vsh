#version 330
#extension GL_ARB_separate_shader_objects : require

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

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;       // x = isWaterFlag (2.0 = water, 0.0 = ground), y = wallFactor (0..1)
layout(location = 2) in vec4 Color;     // rgb = nearby block/wall tint, a = vertexAlpha
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in float LineWidth;

layout(location = 0) out vec3 vRelPos;
layout(location = 1) out vec4 vClipPos;
layout(location = 2) out vec2 vSurfaceInfo;
layout(location = 3) out vec4 vColor;
layout(location = 4) out float vEdgeFeather;

void main() {
    vec3 relPos = Position + uMeshOriginRelCam.xyz;
    mat4 vp = mat4(uViewProj0, uViewProj1, uViewProj2, uViewProj3);
    vec4 clipPos = vp * vec4(relPos, 1.0);
    gl_Position = clipPos;

    // Unused attributes referenced cleanly to avoid driver optimization warnings
    float dummy = float(UV1.x + UV2.x) * 0.0 + LineWidth * 0.0;
    vRelPos = relPos + vec3(dummy);
    vClipPos = clipPos;
    vSurfaceInfo = UV0;
    vColor = Color;

    float maxRad = max(uMeshOriginRelCam.w, 1.0);
    float horizDist = length(relPos.xz);
    vEdgeFeather = clamp((maxRad - horizDist) / (maxRad * 0.28), 0.0, 1.0);
}
