#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform ShaderSkyData {
    vec4 SkyRightTime;
    vec4 SkyUpAspect;
    vec4 SkyForwardTan;
    vec4 SkyFogColor;
    vec4 SkyControls;
};

layout(location = 0) in vec3 worldRay;
layout(location = 0) out vec4 fragColor;

const float TAU = 6.28318530718;

float hash21(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

float noise2(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(cell), hash21(cell + vec2(1.0, 0.0)), f.x),
               mix(hash21(cell + vec2(0.0, 1.0)), hash21(cell + vec2(1.0)), f.x), f.y);
}

float fbm(vec2 p) {
    float result = 0.0;
    float weight = 0.5;
    const mat2 turn = mat2(0.8, -0.6, 0.6, 0.8);
    for (int i = 0; i < 4; ++i) {
        result += weight * noise2(p);
        p = turn * p * 2.03 + vec2(11.1, 7.9);
        weight *= 0.5;
    }
    return result;
}

// Wrapped dome coordinates avoid a visible atan seam when turning through 360 degrees.
float curtain(vec3 ray, float layer, float time) {
    vec2 dome = ray.xz / max(0.16, ray.y + 0.42);
    float drift = time * (0.025 + layer * 0.007);
    vec2 p = dome * (1.25 + layer * 0.42) + vec2(layer * 9.3, drift);
    float warp = fbm(p * 0.65 + vec2(drift, -drift * 0.3));
    float phase = p.x * 2.1 + p.y * 0.7 + warp * 5.5 + layer * 2.6;
    float ribbonDistance = abs(sin(phase));
    float width = 0.045 + 0.035 * noise2(p * 1.6 + time * 0.04);
    float core = exp(-ribbonDistance * ribbonDistance / (width * width));
    float bloom = exp(-ribbonDistance * ribbonDistance / (width * width * 18.0));
    float strands = 0.55 + 0.45 * noise2(vec2(phase * 30.0, ray.y * 3.0 - time * 0.08));
    float baseHeight = 0.10 + layer * 0.07 + warp * 0.20;
    float aboveBase = ray.y - baseHeight;
    float foot = smoothstep(-0.08, 0.06, aboveBase);
    float veil = exp(-max(aboveBase, 0.0) * (3.2 + layer * 0.65));
    float crestFade = 1.0 - smoothstep(0.78, 0.99, ray.y);
    return (core * 0.16 + bloom * 0.055) * strands * foot * veil * crestFade;
}

void main() {
    vec3 ray = normalize(worldRay);
    float time = SkyRightTime.w * SkyControls.y;
    float elevation = clamp(ray.y, 0.0, 1.0);

    // Neutral black/charcoal atmosphere: no blue, green or purple tint.
    float sky = mix(0.033, 0.0035, pow(elevation, 0.42));
    float lowCloud = fbm(ray.xz / max(0.22, elevation + 0.35) * 2.0
                        + vec2(time * 0.006, -time * 0.004));
    sky += (lowCloud - 0.5) * 0.012 * (1.0 - elevation);

    float aurora = 0.0;
    for (int i = 0; i < 3; ++i) {
        aurora += curtain(ray, float(i), time);
    }
    aurora *= smoothstep(0.015, 0.16, ray.y) * SkyControls.x;
    vec3 color = vec3(max(sky, 0.0) + aurora);

    // Soft moving horizon mist. World terrain fog uses the same SkyFogColor on the CPU side.
    float movingMist = 0.80 + 0.20 * fbm(ray.xz * 4.0 + vec2(time * 0.008, 0.0));
    float horizon = exp(-abs(ray.y) * 10.0) * movingMist * SkyControls.z;
    horizon = clamp(horizon, 0.0, 0.98);
    color = mix(color, SkyFogColor.rgb, horizon);
    if (ray.y < 0.0) {
        color = mix(color, SkyFogColor.rgb * 0.55,
                    smoothstep(0.0, 0.45, -ray.y));
    }

    color *= SkyControls.w;
    // Subtle screen-space dithering reduces banding in the near-black gradients.
    color += (hash21(gl_FragCoord.xy) - 0.5) / 255.0;
    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
