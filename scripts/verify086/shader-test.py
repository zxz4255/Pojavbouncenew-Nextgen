#!/usr/bin/env python3
"""Actual production GLSL compile/link + offscreen render on EGL (not Minecraft validation).
Requires: pip install moderngl numpy pillow
"""
from pathlib import Path
import struct, math
import moderngl
import numpy as np
ROOT = Path(__file__).resolve().parents[2]
S = ROOT / 'src/main/resources/resources/liquidbounce/shaders/clickgui'
ctx = moderngl.create_standalone_context(backend='egl', require=330)
def expand(s):
    return s.replace('#include <minecraft:dynamictransforms.glsl>',
        'uniform mat4 ModelViewMat;\nuniform vec4 ColorModulator;').replace(
        '#include <minecraft:projection.glsl>', 'uniform mat4 ProjMat;').replace(
        '#include <liquidbounce:clickgui/glass_common.glsl>', (S/'glass_common.glsl').read_text())
v = expand((S/'liquid_glass.vsh').read_text())
progs = [ctx.program(vertex_shader=v, fragment_shader=expand((S/f).read_text()))
         for f in ['liquid_glass.fsh', 'light_pollution_glass.fsh']]
W,H=512,384
Y,X=np.mgrid[:H,:W]
scene=np.stack([0.12+X/W*.5, 0.2+Y/H*.5, 0.15+((X//32+Y//32)%2)*.45],axis=-1).astype('f4')
tex=ctx.texture((W,H),3,scene.tobytes(),dtype='f4');tex.filter=(moderngl.LINEAR,moderngl.LINEAR);tex.use(0)
fbo=ctx.simple_framebuffer((W,H), components=4, dtype='f4');fbo.use()
identity=np.eye(4,dtype='f4')
proj=np.array([[2/W,0,0,-1],[0,-2/H,0,1],[0,0,1,0],[0,0,0,1]],dtype='f4')
for p in progs:
    p['ModelViewMat'].write(identity.T.tobytes());p['ProjMat'].write(proj.T.tobytes())
    p['ColorModulator'].value=(1,1,1,1);p['Sampler0'].value=0

def draw(p,w,h,r,mode,color=(.60,.60,.60,.25),pad=0):
    x,y=(W-w)/2,(H-h)/2
    rr=[min(a,min(w,h)/2,63.5) for a in r]
    enc=[round(a*2) for a in rr]
    uv2=((enc[0]<<7)|enc[1],(enc[2]<<7)|enc[3])
    vertices=[]
    for a,b in [(0,0),(0,1),(1,1),(0,0),(1,1),(1,0)]:
        vx=x-pad+a*(w+2*pad);vy=y-pad+b*(h+2*pad)
        lx=-w/2-pad+a*(w+2*pad);ly=-h/2-pad+b*(h+2*pad)
        vertices.append(struct.pack('=3f2f4f2i2if',vx,vy,0,lx,ly,*color,round(w*8),round(h*8),*uv2,mode))
    b=ctx.buffer(b''.join(vertices));vao=ctx.vertex_array(p,[(b,'3f 2f 4f 2i 2i 1f','Position','UV0','Color','UV1','UV2','LineWidth')])
    vao.render(moderngl.TRIANGLES);vao.release();b.release()
def pixels():return np.frombuffer(fbo.read(components=4,dtype='f4'),dtype='f4').reshape(H,W,4)
checks=0
for w,h in [(240,64),(145,280),(300,40),(64,64),(8,8),(400,320)]:
 for r in [(0,0,0,0),(10,10,10,10),(30,2,17,0)]:
  for opacity in [1,16,64]:
   for refract in [0,.25,.75]:
    fbo.clear(0,0,0,0);draw(progs[1],w,h,r,opacity+refract)
    a=pixels();assert np.isfinite(a).all();assert a.min()>=0 and a.max()<=1.00001
    assert np.count_nonzero(a[:,:,3])>0
    assert abs(a[H//2,W//2,3]-opacity/64)<1e-6
    checks+=4
# Rounded silhouette and external-only elevation/halo.
fbo.clear(0,0,0,0);draw(progs[1],240,64,(20,)*4,64.25)
a=pixels();assert a[H//2,W//2,3]==1 and a[0,0,3]==0;checks+=2
fbo.clear(0,0,0,0);draw(progs[1],240,64,(12,)*4,-14,(0,0,0,.5),39)
a=pixels();assert a[H//2,W//2,3]==0 and a[:,:,3].max()>0;checks+=2
# Refraction=0 has no spectral separation, and neutral scene is not tinted blue.
gray=np.full((H,W,3),.5,dtype='f4');tex.write(gray.tobytes())
fbo.clear(0,0,0,0);draw(progs[1],240,64,(12,)*4,64)
a=pixels()[H//2,W//2,:3];assert np.max(a)-np.min(a)<1e-6;checks+=1
# Save an internal synthetic test frame, NEVER label it a game screenshot or visual acceptance.
tex.write(scene.tobytes());fbo.clear(.14,.17,.20,1)
ctx.enable(moderngl.BLEND);ctx.blend_func=(moderngl.SRC_ALPHA,moderngl.ONE_MINUS_SRC_ALPHA)
for mode,color,pad in [(-14,(0,0,0,.5),39),(64.25,(.62,.62,.62,.24),0),(100.85,(1,1,1,.66),0)]:
 draw(progs[1],240,64,(12,)*4,mode,color,pad)
from PIL import Image
Image.fromarray((np.clip(pixels()[:,:,:3],0,1)[::-1]*255).astype('uint8')).save(ROOT/'scripts/verify086/synthetic-shader-test.png')
print('PASS: old and new production GLSL compile/link on',ctx.info['GL_RENDERER'])
print('PASS:',checks,'offscreen numeric assertions (finite RGB/alpha, opacity, silhouettes, shadow, neutral tint)')
print('NOT a Minecraft/Android/screenshot-parity acceptance test')
