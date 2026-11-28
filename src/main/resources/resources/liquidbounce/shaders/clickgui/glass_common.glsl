// Shared optical & analytical SDF library for LiquidBounce Native ClickGUI Liquid Glass.

float selectCornerRadius(vec2 p, float rTL, float rTR, float rBR, float rBL) {
    if (p.x < 0.0) {
        return (p.y < 0.0) ? rTL : rBL;
    } else {
        return (p.y < 0.0) ? rTR : rBR;
    }
}

float sdCssRoundedBox(vec2 p, vec2 halfSize, float rTL, float rTR, float rBR, float rBL) {
    float r = selectCornerRadius(p, rTL, rTR, rBR, rBL);
    vec2 q = abs(p) - halfSize + vec2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

// Analytical C1-continuous SDF + 2D outward unit gradient (x = sdf, yz = outward normal in GUI coords).
vec3 sdCssRoundedBoxGrad(vec2 p, vec2 halfSize, float rTL, float rTR, float rBR, float rBL) {
    float r = selectCornerRadius(p, rTL, rTR, rBR, rBL);
    vec2 b = max(halfSize - vec2(r), vec2(0.0));
    vec2 s = vec2((p.x >= 0.0) ? 1.0 : -1.0, (p.y >= 0.0) ? 1.0 : -1.0);
    vec2 q = abs(p) - b;
    if (q.x > 0.0 && q.y > 0.0) {
        float lenQ = length(q);
        vec2 n = (lenQ > 1e-4) ? (s * (q / lenQ)) : normalize(s);
        return vec3(lenQ - r, n);
    } else if (q.x > q.y) {
        return vec3(q.x - r, s.x, 0.0);
    } else {
        return vec3(q.y - r, 0.0, s.y);
    }
}

// Dual-layer physical elevation shadow (tight top-lit contact shadow + wide ambient halo),
// strictly masked outside the unshifted glass body so translucent interiors stay crystal clear.
float evalGlassElevationShadow(float dShifted, float dBody, float spread) {
    float safeSpread = max(spread, 0.5);
    float contact = exp(-pow(max(dShifted, 0.0) / max(safeSpread * 0.38, 0.45), 1.65)) * 0.56;
    float ambient = exp(-pow(max(dBody, 0.0) / max(safeSpread * 0.92, 0.65), 1.42)) * 0.48;
    float outsideMask = smoothstep(-0.55, 0.45, dBody);
    return clamp((contact + ambient) * outsideMask, 0.0, 1.0);
}

// Preserves rich world colors behind frosted dark/tinted glass instead of muddying midtones.
vec3 boostFrostVibrance(vec3 rgb, float vibrance) {
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    float maxC = max(rgb.r, max(rgb.g, rgb.b));
    float minC = min(rgb.r, min(rgb.g, rgb.b));
    float sat = maxC - minC;
    float weight = (1.0 - sat) * (vibrance - 1.0);
    return clamp(mix(vec3(luma), rgb, 1.0 + weight), vec3(0.0), vec3(1.0));
}

// 4x4 Bayer ordered dither in [-0.5, +0.5] to eliminate 8-bit banding on ultra-soft shadows & sheens.
float bayerDither4x4(vec2 fragCoord) {
    ivec2 ip = ivec2(mod(floor(fragCoord), 4.0));
    int idx = ip.y * 4 + ip.x;
    float m = 0.0;
    if (idx == 0) m = 0.0;
    else if (idx == 1) m = 8.0;
    else if (idx == 2) m = 2.0;
    else if (idx == 3) m = 10.0;
    else if (idx == 4) m = 12.0;
    else if (idx == 5) m = 4.0;
    else if (idx == 6) m = 14.0;
    else if (idx == 7) m = 6.0;
    else if (idx == 8) m = 3.0;
    else if (idx == 9) m = 11.0;
    else if (idx == 10) m = 1.0;
    else if (idx == 11) m = 9.0;
    else if (idx == 12) m = 15.0;
    else if (idx == 13) m = 7.0;
    else if (idx == 14) m = 13.0;
    else m = 5.0;
    return (m + 0.5) / 16.0 - 0.5;
}

// Subtle high-frequency frost micro-grain in [-0.5, +0.5].
float frostMicroGrain(vec2 fragCoord) {
    vec3 p3 = fract(vec3(fragCoord.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z) - 0.5;
}
