#!/usr/bin/env python3
"""Actual accumulation GLSL offscreen EGL tests; not a Minecraft/Pojav runtime test."""
from pathlib import Path
import os,zipfile,json,struct
import moderngl
import numpy as np
R=Path(__file__).resolve().parents[2];O=R/'scripts/verify091'
with zipfile.ZipFile(os.environ['MC_JAR']) as z:
    vs=z.read('assets/minecraft/shaders/core/screenquad.vsh').decode()
# RenderPearl's cross-backend built-in vertex index mapped for standalone OpenGL.
vs=vs.replace('gl_VertexIndex','gl_VertexID')
fs=(R/'src/main/resources/resources/liquidbounce/shaders/motionblur/accumulate.fsh').read_text()
c=moderngl.create_standalone_context(backend='egl',require=330)
p=c.program(vertex_shader=vs,fragment_shader=fs)
p['texture0'].value=0;p['historyTexture'].value=1;p['MotionBlurData'].binding=0
ubo=c.buffer(reserve=16);ubo.bind_to_uniform_block(0);vao=c.vertex_array(p,[])
rng=np.random.default_rng(91);checks=0;maxerr=0.0
for w,h in [(1,1),(13,7),(64,48),(257,129)]:
    scene=c.texture((w,h),4,dtype='f4')
    a=c.texture((w,h),4,dtype='f4');b=c.texture((w,h),4,dtype='f4')
    for t in [scene,a,b]:t.filter=(moderngl.NEAREST,moderngl.NEAREST);t.repeat_x=False;t.repeat_y=False
    fa=c.framebuffer([a]);fb=c.framebuffer([b]);old=np.zeros((h,w,4),dtype='f4');a.write(old.tobytes())
    for i in range(60):
        data=rng.random((h,w,4),dtype=np.float32)
        weight=[0,.1,.55,.95,.98725854,1.0][i%6]
        scene.write(data.tobytes());scene.use(0);a.use(1)
        fb.use();c.viewport=(0,0,w,h);ubo.write(struct.pack('4f',weight,0,0,0));vao.render(vertices=3)
        actual=np.frombuffer(fb.read(components=4,dtype='f4'),dtype='f4').reshape(h,w,4)
        expected=data*(1-weight)+old*weight;expected[:,:,3]=1
        error=float(np.max(np.abs(actual-expected)));maxerr=max(maxerr,error)
        assert error<4e-7,(w,h,i,error);checks+=1
        assert np.isfinite(actual).all() and actual.min()>=0 and actual.max()<=1;checks+=1
        # Real ping-pong: output is next frame's read attachment; never clears it.
        old=expected;a,b=b,a;fa,fb=fb,fa
    for x in [fa,fb,scene,a,b]:x.release()
report={'status':'PASS','checks':checks,'maxRGBAError':maxerr,'renderer':c.info['GL_RENDERER'],
        'coverage':'production accumulation fragment + official screenquad vertex (gl_VertexIndex to GL built-in), std140 UBO, 4 dimensions, 60-frame real ping-pong each; no game/HUD/Mixin/Pojav validation'}
(O/'shader-report.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
