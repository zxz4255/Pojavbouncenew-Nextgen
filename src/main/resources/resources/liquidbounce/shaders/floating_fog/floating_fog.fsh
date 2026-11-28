#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

uniform sampler2D DepthSampler;

layout(std140) uniform FloatingFogData {
    vec4 uInvViewProj0;      // col 0 of inverse(Proj * ViewRot)
    vec4 uInvViewProj1;      // col 1
    vec4 uInvViewProj2;      // col 2
    vec4 uInvViewProj3;      // col 3
    vec4 uCameraPosAndTime;  // xyz = cameraPos (world), w = uTime (seconds)
    vec4 uCamMinusPlayer;    // xyz = cameraPos - playerPos, w = uRange
    vec4 uColorAndDensity;   // rgb = uColor, a = uDensity
    vec4 uParams0;           // x = uMaxDistance, y = uDrift, z = uClipZScale, w = uClipZBias
    vec4 uParams1;           // x = uReversedZ, y = invFullW, z = invFullH, w = playerWorldY
};

const int PERIOD = 128;
const int STEPS = 20;
const vec3 CELL = vec3(8.0, 4.5, 8.0);

mat4 invViewProj() {
    return mat4(uInvViewProj0, uInvViewProj1, uInvViewProj2, uInvViewProj3);
}

float hash(ivec3 c) {
    ivec3 w = ((c % PERIOD) + PERIOD) % PERIOD;
    int n = w.x * 15731 + w.y * 789221 + w.z * 1376312589;
    n = (n << 13) ^ n;
    int m = n * (n * n * 15731 + 789221) + 1376312589;
    return float(m & 0x7fffffff) / 2147483647.0;
}

float noise(vec3 p) {
    ivec3 i = ivec3(floor(p));
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);

    float n000 = hash(i);
    float n100 = hash(i + ivec3(1, 0, 0));
    float n010 = hash(i + ivec3(0, 1, 0));
    float n110 = hash(i + ivec3(1, 1, 0));
    float n001 = hash(i + ivec3(0, 0, 1));
    float n101 = hash(i + ivec3(1, 0, 1));
    float n011 = hash(i + ivec3(0, 1, 1));
    float n111 = hash(i + ivec3(1, 1, 1));

    float nx00 = mix(n000, n100, u.x);
    float nx10 = mix(n010, n110, u.x);
    float nx01 = mix(n001, n101, u.x);
    float nx11 = mix(n011, n111, u.x);
    return mix(mix(nx00, nx10, u.y), mix(nx01, nx11, u.y), u.z);
}

float fogField(vec3 relFromCam, vec3 worldPos) {
    float uRange = uCamMinusPlayer.w;
    vec3 relFromPlayer = uCamMinusPlayer.xyz + relFromCam;
    float radial = 1.0 - smoothstep(uRange * 0.70, uRange, length(relFromPlayer.xz));
    if (radial <= 0.0) {
        return 0.0;
    }
    float vertical = 1.0 - smoothstep(5.0, 20.0, abs(relFromPlayer.y + 0.5));
    if (vertical <= 0.0) {
        return 0.0;
    }

    float uTime = uCameraPosAndTime.w;
    float uDrift = uParams0.y;
    vec3 wind = vec3(-0.78, -0.16, 0.36) * uDrift * uTime;
    vec3 p = (worldPos + wind) / CELL;
    float broad = noise(p);
    float billow = 1.0 - abs(noise(p * 2.35 + vec3(19.3, -7.1, 31.7) + wind * 0.14) * 2.0 - 1.0);
    float detail = noise(p * 5.05 + vec3(-43.0, 13.0, 9.0) - wind * 0.22);
    float combined = broad * 0.62 + billow * 0.28 + detail * 0.10;
    float banks = smoothstep(0.46, 0.80, combined);
    return banks * banks * radial * vertical;
}

float sphereExit(vec3 camMinusSphereCenter, vec3 rayDir, float radius) {
    float b = dot(camMinusSphereCenter, rayDir);
    float c = dot(camMinusSphereCenter, camMinusSphereCenter) - radius * radius;
    float h = b * b - c;
    if (h <= 0.0) {
        return 0.0;
    }
    return max(0.0, -b + sqrt(h));
}

float interleavedGradientNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

void main() {
    float uDensity = uColorAndDensity.a;
    float uRange = uCamMinusPlayer.w;
    float uMaxDistance = uParams0.x;
    if (uDensity <= 0.001 || uRange <= 0.0) {
        fragColor = vec4(0.0);
        return;
    }

    mat4 ivp = invViewProj();
    vec2 ndcXY = texCoord * 2.0 - 1.0;

    // Well-conditioned mid-depth point along this pixel's ray (never divides by 0 at sky depth)
    vec4 refH = ivp * vec4(ndcXY, 0.5 * uParams0.z + uParams0.w, 1.0);
    vec3 rayDir = normalize(refH.xyz / refH.w);

    float rawDepth = textureLod(DepthSampler, texCoord, 0.0).r;
    bool isSky = (uParams1.x > 0.5) ? (rawDepth <= 1e-6) : (rawDepth >= 0.999999);
    float rayLength = uMaxDistance;
    if (!isSky) {
        float zNdc = rawDepth * uParams0.z + uParams0.w;
        vec4 hitH = ivp * vec4(ndcXY, zNdc, 1.0);
        vec3 hitRel = hitH.xyz / max(abs(hitH.w), 1e-7);
        rayLength = min(length(hitRel), uMaxDistance);
    }

    float maxDistance = min(rayLength, sphereExit(uCamMinusPlayer.xyz, rayDir, uRange));
    if (maxDistance <= 0.65) {
        fragColor = vec4(0.0);
        return;
    }

    float start = min(0.55, maxDistance * 0.2);
    float span = maxDistance - start;
    float stepSize = span / float(STEPS);
    float jitter = interleavedGradientNoise(floor(gl_FragCoord.xy));
    float t = start + stepSize * jitter;

    vec3 uColor = uColorAndDensity.rgb;
    vec3 uCameraPos = uCameraPosAndTime.xyz;
    vec3 light = vec3(0.0);
    float transmission = 1.0;
    for (int i = 0; i < STEPS; i++) {
        vec3 relPos = rayDir * t;
        vec3 worldPos = uCameraPos + relPos;
        float density = fogField(relPos, worldPos);
        if (density > 0.001) {
            float extinction = density * (0.048 + 0.25 * uDensity) * stepSize;
            float sampleAlpha = 1.0 - exp(-extinction);
            float edge = 1.0 - smoothstep(0.06, 0.48, density);
            vec3 shaded = uColor * 0.35;
            vec3 lit = mix(uColor * 0.95, vec3(0.91, 0.96, 1.0), 0.68);
            vec3 fogColor = mix(shaded, lit, 0.24 + 0.76 * edge);
            light += transmission * sampleAlpha * fogColor;
            transmission *= 1.0 - sampleAlpha;
            if (transmission < 0.03) {
                break;
            }
        }
        t += stepSize;
    }

    float nearReach = min(maxDistance, 13.0);
    float nearMist = smoothstep(2.0, 13.0, nearReach) * (0.015 + 0.060 * uDensity);
    vec3 nearColor = mix(uColor * 0.72, vec3(0.88, 0.93, 0.98), 0.45);
    light += transmission * nearMist * nearColor;
    transmission *= 1.0 - nearMist;

    fragColor = vec4(light, 1.0 - transmission);
}
