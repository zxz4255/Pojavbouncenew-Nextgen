#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

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

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;       // x = isWaterFlag (2.0 = water, 0.0 = ground), y = wallPseudoFactor (0..1)
layout(location = 2) in vec4 Color;     // rgb = nearby wall/biome tint, a = vertexAlpha
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in float LineWidth; // unused / reserved

layout(location = 0) out vec3 vWorldPos;
layout(location = 1) out vec3 vRelPos;
layout(location = 2) out vec2 vSurfaceInfo;
layout(location = 3) out vec4 vColor;
layout(location = 4) out float vEdgeFeather;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec3 relPos = Position + uMeshOriginRelCam.xyz;
    vRelPos = relPos;
    vWorldPos = relPos + uCameraPosAndTime.xyz;
    vSurfaceInfo = UV0;
    vColor = Color;
    float maxRad = max(uMeshOriginRelCam.w, 1.0);
    float horizDist = length(relPos.xz);
    vEdgeFeather = clamp((maxRad - horizDist) / (maxRad * 0.30), 0.0, 1.0);
}
