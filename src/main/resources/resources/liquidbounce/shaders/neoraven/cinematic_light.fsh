#version 330
#extension GL_ARB_separate_shader_objects : require
// Ported from Matr1ze/NeoRaven b483ffc; GPL-3.0. See licenses/neoraven/NOTICE.md.
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
#define uv texCoord
uniform sampler2D scene, geometry;
layout(std140) uniform CinematicData {
    mat4 uInvProjection;
    vec4 uProjection;
    vec4 uDepth;
    vec4 uPixel;
    vec4 controls;
};
#define pixel uPixel.xy
vec3 viewPosition(vec2 coord) {
    float raw = texture(geometry, coord).r;
    vec4 p = uInvProjection * vec4(coord * 2.0 - 1.0, raw * uDepth.x + uDepth.y, 1.0);
    return p.xyz / (abs(p.w) < 1e-7 ? 1e-7 : p.w);
}
// Compare relative view depths. Opposing samples reject planar gradients.
float z(vec2 p) {
    float distance=max(0.0001,-viewPosition(p).z);
    // Recreate conventional 1.8 near/far depth for the original lightweight crease kernel.
    float nearPlane=0.05, farPlane=max(1.0,uPixel.z);
    return 1.0/max(.0001,nearPlane*(farPlane-distance)/(distance*(farPlane-nearPlane)));
}
float crease(vec2 p,vec2 d,float center) {
    vec2 delta=1.0-vec2(z(p+d),z(p-d))/center;
    return smoothstep(.003,.045,delta.x+delta.y)
        *(1.0-smoothstep(.12,.35,max(abs(delta.x),abs(delta.y))));
}
vec3 highlight(vec2 p) {
    vec3 c=texture(scene,p).rgb;
    return c*smoothstep(.63,.98,dot(c,vec3(.2126,.7152,.0722)));
}
void main() {
    vec2 q=uv*2.0-1.0;
    vec2 p=.5+.5*q*(1.0+.026*controls.z*(dot(q,q)-2.0));
    vec3 c=texture(scene,p).rgb;
    if(controls.x>0.0) {
        float center=z(p);
        vec2 r=pixel*clamp(1500.0/center,1.5,6.0);
        float shade=crease(p,vec2(r.x,r.y),center)+crease(p,vec2(r.x,-r.y),center);
        c*=1.0-.14*controls.x*shade;
    }
    if(controls.y>0.0) {
        vec2 r=pixel*clamp(.006/pixel.y,2.0,10.0);
        vec3 halo=highlight(p+r)+highlight(p-r)+highlight(p+vec2(r.x,-r.y))+highlight(p+vec2(-r.x,r.y));
        c+=(1.0-c)*halo*(.095*controls.y);
    }
    c*=controls.w;
    fragColor=vec4(clamp(c,0.0,1.0),1.0);
}