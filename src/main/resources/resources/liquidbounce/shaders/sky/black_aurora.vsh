#version 330
#extension GL_ARB_separate_shader_objects : require

// Five std140 vec4s = 80 bytes. Shared contract with ModuleShaderSky.
layout(std140) uniform ShaderSkyData {
    vec4 SkyRightTime;   // camera world-space right.xyz; elapsed seconds.w
    vec4 SkyUpAspect;    // camera world-space up.xyz; framebuffer aspect.w
    vec4 SkyForwardTan;  // camera forward.xyz; tan(vertical FOV / 2).w
    vec4 SkyFogColor;    // horizon / world-fog RGBA
    vec4 SkyControls;    // aurora amount, animation speed, horizon haze, exposure
};

layout(location = 0) out vec3 worldRay;

void main() {
    // Oversized full-screen triangle, no VBO, no seams, exactly one draw call.
    vec2 uv = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vec2 screen = uv * 2.0 - 1.0;
    worldRay = SkyForwardTan.xyz
        + SkyRightTime.xyz * screen.x * SkyUpAspect.w * SkyForwardTan.w
        + SkyUpAspect.xyz * screen.y * SkyForwardTan.w;
    gl_Position = vec4(screen, 1.0, 1.0);
}
