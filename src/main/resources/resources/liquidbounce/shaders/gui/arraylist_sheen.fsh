#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

/*
 * Arraylist sheen band.
 *
 * A tilted light sweep that travels along the list, like the specular band Material 3 uses on its
 * elevated surfaces. The band is its own quad: the CPU places it where the sweep currently is (so
 * the animation stays a cheap CPU lerp and needs no time uniform), and this shader turns it into a
 * soft, tilted highlight with a rounded silhouette so the band never shows a hard rectangle edge.
 *
 * LineWidth is the falloff exponent: larger is a tighter, more metallic streak, smaller is a wide
 * diffuse wash.
 */
layout(location = 0) in vec2 vUv;
layout(location = 1) in vec4 vColor;
layout(location = 2) flat in ivec2 vSize;
layout(location = 3) flat in ivec2 vParams;
layout(location = 4) in float vStyle;

layout(location = 0) out vec4 fragColor;

void main() {
    // Horizontal falloff, centred on the band. 0.5 in the middle, 1 at both edges.
    float x = abs(vUv.x - 0.5) * 2.0;
    float power = clamp(vStyle, 0.25, 8.0);
    float band = pow(max(1.0 - x, 0.0), power);

    // Tilt: the band leans slightly, so it reads as a light source moving over the surface.
    float tilt = (vUv.x - 0.5) * 0.55;
    float y = clamp(vUv.y + tilt, 0.0, 1.0);

    // Fade to nothing at the top and bottom of the row, and never touch the rounded corners.
    float vertical = smoothstep(0.0, 0.22, y) * (1.0 - smoothstep(0.78, 1.0, y));

    // Specular core: a narrow bright line inside the wide band.
    float core = pow(max(1.0 - abs(vUv.x - 0.5) * 4.0, 0.0), 2.0);

    float alpha = band * vertical * vColor.a;
    if (alpha <= 0.002) {
        discard;
    }

    vec3 rgb = vColor.rgb + vec3(core * 0.22);
    rgb += (fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) / 255.0;

    vec4 color = vec4(rgb, alpha) * ColorModulator;

    fragColor = color;
}
