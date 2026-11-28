// Shared 7-tap hardware-bilinear (= 15-tap analytical Gaussian) separable blur kernel
// for LiquidBounce Native ClickGUI Liquid Glass backdrop.

vec4 sampleGaussian15Bilinear(sampler2D tex, vec2 uv, vec2 axisStep) {
    // Hardware bilinear-paired offsets & weights for a true 15-tap Gaussian (sigma ~ 3.2):
    // Center + 3 symmetric bilinear tap pairs = 7 texture fetches total.
    vec4 acc = texture(tex, uv) * 0.14107424;

    vec2 off1 = axisStep * 1.41176471;
    acc += (texture(tex, uv + off1) + texture(tex, uv - off1)) * 0.24690695;

    vec2 off2 = axisStep * 3.29411765;
    acc += (texture(tex, uv + off2) + texture(tex, uv - off2)) * 0.13265107;

    vec2 off3 = axisStep * 5.17647059;
    acc += (texture(tex, uv + off3) + texture(tex, uv - off3)) * 0.04990486;

    return vec4(acc.rgb, 1.0);
}
