#version 330
#extension GL_ARB_separate_shader_objects : require
// Ported from Matr1ze/NeoRaven b483ffc; GPL-3.0. See licenses/neoraven/NOTICE.md.
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
#define uv texCoord
uniform sampler2D DepthSampler, ReflectionSampler;
layout(std140) uniform NeoRainData {
    mat4 uProjection;
    mat4 uInvProjection;
    vec4 uWX;
    vec4 uWY;
    vec4 uWZ;
    vec4 uExposure;
    vec4 uNoiseRain;
    vec4 uSkyColor;
    vec4 uPixelDepth;
    vec4 uSizes;
};
#define uWorldX uWX.xyz
#define uWorldY uWY.xyz
#define uWorldZ uWZ.xyz
#define uNoiseOrigin uNoiseRain.xy
#define uRain uNoiseRain.zw
#define uSky uSkyColor.rgb
#define uPixel uPixelDepth.xy
#define uReflectionSize uSizes.xy
bool skyDepth(float raw) { return uSizes.z > 0.5 ? raw <= 0.000001 : raw >= 0.999999; }
float eyeDepth(float raw) {
    vec4 v=uInvProjection*vec4(0.0,0.0,raw*uPixelDepth.z+uPixelDepth.w,1.0);
    return -v.z/(abs(v.w)<1e-7 ? 1e-7 : v.w);
}
void accumulate(vec2 pixel, float bilinear, float reference, inout vec4 reflection, inout float total) {
    vec2 coord = (clamp(pixel, vec2(0.0), uReflectionSize - 1.0) + 0.5) / uReflectionSize;
    vec2 depthCoord = (floor(coord / uPixel + 0.001) + 0.5) * uPixel;
    float delta = abs(eyeDepth(texture(DepthSampler,depthCoord).r) - reference);
    float weight = bilinear * (1.0 - smoothstep(0.025, max(0.15, reference * 0.023), delta));
    reflection += texture(ReflectionSampler, coord) * weight;
    total += weight;
}

void main() {
    float reference = eyeDepth(texture(DepthSampler,uv).r);
    if (reference > 48.0) discard;
    vec2 pixel = uv * uReflectionSize - 0.5;
    vec2 base = floor(pixel);
    vec2 f = fract(pixel);
    vec4 reflection = vec4(0.0);
    float total = 0.0;
    accumulate(base, (1.0 - f.x) * (1.0 - f.y), reference, reflection, total);
    accumulate(base + vec2(1.0, 0.0), f.x * (1.0 - f.y), reference, reflection, total);
    accumulate(base + vec2(0.0, 1.0), (1.0 - f.x) * f.y, reference, reflection, total);
    accumulate(base + 1.0, f.x * f.y, reference, reflection, total);
    fragColor = reflection / max(0.25, total);
}
