#version 330
#extension GL_ARB_separate_shader_objects : require
// Ported from Matr1ze/NeoRaven b483ffc; GPL-3.0. See licenses/neoraven/NOTICE.md.
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
#define uv texCoord
uniform sampler2D texture0;
void main() {
    // Bilinear downsampling is the same four-texel box footprint for even mip dimensions.
    fragColor=vec4(texture(texture0,texCoord).rgb,1.0);
}
