#!/usr/bin/env python3
"""Production shader offscreen tests. NOT a game capture / whole-image pixel-parity test.
Dependencies: moderngl numpy pillow; EGL required. Source image trace evidence is in reference-spec.json.
"""
from pathlib import Path
import json,struct
import moderngl
import numpy as np
from PIL import Image
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'scripts/verify089';S=ROOT/'src/main/resources/resources/liquidbounce/shaders/clickgui'
ctx=moderngl.create_standalone_context(backend='egl',require=330)
def expand(s):
 return s.replace('#include <minecraft:dynamictransforms.glsl>','uniform mat4 ModelViewMat;\nuniform vec4 ColorModulator;').replace('#include <minecraft:projection.glsl>','uniform mat4 ProjMat;').replace('#include <liquidbounce:clickgui/glass_common.glsl>',(S/'glass_common.glsl').read_text())
p=ctx.program(vertex_shader=expand((S/'liquid_glass.vsh').read_text()),fragment_shader=expand((S/'light_pollution_glass.fsh').read_text()))
# Ensure the unchanged historical shader still links to the shared production vertex shader.
old=ctx.program(vertex_shader=expand((S/'liquid_glass.vsh').read_text()),fragment_shader=expand((S/'liquid_glass.fsh').read_text()))
W,H=768,896
fbo=ctx.simple_framebuffer((W,H),components=4,dtype='f4');fbo.use()
tex=ctx.texture((W,H),3,dtype='f4');tex.filter=(moderngl.LINEAR,moderngl.LINEAR);tex.use(0)
p['ModelViewMat'].write(np.eye(4,dtype='f4').T.tobytes())
p['ProjMat'].write(np.array([[2/W,0,0,-1],[0,-2/H,0,1],[0,0,1,0],[0,0,0,1]],dtype='f4').T.tobytes())
p['ColorModulator'].value=(1,1,1,1);p['Sampler0'].value=0
Y,X=np.mgrid[:H,:W]
synthetic=np.stack([.15+X/W*.46,.22+Y/H*.44,.23+((X//32+Y//32)%2)*.35],axis=-1).astype('f4')
sky=np.zeros((H,W,3),dtype='f4');sky[:]=(.49,.56,.64)
def background(scene):tex.write(scene.tobytes())
def read():return np.frombuffer(fbo.read(components=4,dtype='f4'),dtype='f4').reshape(H,W,4)[::-1].copy()
def draw(w,h,r,mode,colors,pad=0):
 x,y=(W-w)/2,(H-h)/2;rr=[round(min(a,min(w,h)/2,63.5)*2) for a in r]
 packed=((rr[0]<<7)|rr[1],(rr[2]<<7)|rr[3]);data=[]
 for a,b,i in [(0,0,0),(0,1,3),(1,1,2),(0,0,0),(1,1,2),(1,0,1)]:
  vx=x-pad+a*(w+2*pad);vy=y-pad+b*(h+2*pad)
  lx=-w/2-pad+a*(w+2*pad);ly=-h/2-pad+b*(h+2*pad)
  data.append(struct.pack('=3f2f4f2i2if',vx,vy,0,lx,ly,*colors[i],round(w*8),round(h*8),*packed,mode))
 b=ctx.buffer(b''.join(data));vao=ctx.vertex_array(p,[(b,'3f 2f 4f 2i 2i 1f','Position','UV0','Color','UV1','UV2','LineWidth')]);vao.render(moderngl.TRIANGLES);vao.release();b.release()
def flat(color):return [color]*4
def colors(profile,strength=1):
 # Exact 089 emitter transport, default card input alpha=112/255 (Onyx) / .34 (ClickGUI).
 panel=profile==0;alpha=.34 if panel else 112/255
 scales=[.47,.42,.12,.14] if panel else [1.08,.70,.46,1.02]
 tints=[174,174,160,160] if panel else [32]*4
 return [(round(strength*127.5)/255,t/255,t/255,round(alpha*s*255)/255) for s,t in zip(scales,tints)]
def body(profile,w,h,r,opacity=64,lens=.25,strength=1):
 fbo.clear(0,0,0,0);draw(w,h,r,profile*256+opacity+lens,colors(profile,strength));return read()
checks=0
background(synthetic)
for profile in range(4):
 for w,h in [(240,64),(220,680),(300,40),(64,64),(8,8),(400,320)]:
  for r in [(0,0,0,0),(10,10,10,10),(30,2,17,0)]:
   for opacity in [1,16,64]:
    for lens in [0,.25,.75]:
     a=body(profile,w,h,r,opacity,lens)
     assert np.isfinite(a).all();assert a.min()>=0 and a.max()<=1.00001
     assert np.count_nonzero(a[:,:,3])>0
     assert abs(a[H//2,W//2,3]-opacity/64)<1e-6
     checks+=4
# 4 material lanes have identical silhouette/opacity but visibly distinct reflection fields.
background(sky);mats=[body(n,368,106,(20,)*4) for n in range(4)]
for a in mats[1:]:assert np.array_equal(a[:,:,3],mats[0][:,:,3]);checks+=1
for i in range(4):
 for j in range(i+1,4):assert np.abs(mats[i]-mats[j]).max()>.03;checks+=1
# Reflection remains visible at lens strength=0; reflection strength=0 removes the optical folds.
for n in [1,2,3]:
 a=body(n,368,106,(20,)*4,lens=0,strength=1);b=body(n,368,106,(20,)*4,lens=0,strength=0)
 assert (a-b)[:,:,:3].max()>.08;checks+=1
# Narrow seam and broad target ridge against source-image traces, in ORIGINAL screenshot pixels.
# This is only feature-position agreement, not RGB / whole-image parity.
trace=json.loads((ROOT/'licenses/glass089/reference-spec.json').read_text())
geometry=[]
for n,w,h,key,window,tolerance in [(1,368,106,'target_fold_trace',[.64,.84],4.0),(2,331,96,'counter_seam_trace',[.43,.50],3.0)]:
 a=body(n,w,h,(20,)*4,lens=0,strength=1);b=body(n,w,h,(20,)*4,lens=0,strength=0)
 diff=(a-b)[:,:,:3].mean(axis=2);x0,y0=(W-w)/2,(H-h)/2
 for yy,expected in trace[key]:
  y=round(y0+yy*(h-1));lo=int(x0+w*window[0]);hi=int(x0+w*window[1]);found=lo+int(diff[y,lo:hi].argmax())
  err=abs((found-x0)-expected*w)
  geometry.append({'profile':n,'source_y_fraction':yy,'source_x_fraction':expected,'rendered_x_fraction':(found-x0)/w,'error_source_pixels':round(err,4)})
  assert err<=tolerance,(key,geometry[-1]);checks+=1
# Detached arcs: no interior coverage, finite exterior pixels, bottom-right only, fade follows alpha.
for n in [1,2]:
 fbo.clear(0,0,0,0);draw(240,64,(12,)*4,n*256+192,flat((1,1,1,1)),16)
 a=read();yy,xx=np.where(a[:,:,3]>.01)
 assert len(xx)>0 and xx.min()>=W//2 and yy.min()>=H//2;checks+=1
 assert a[H//2,W//2,3]==0;checks+=1
 assert xx.max()>(W+240)/2 and yy.max()>(H+64)/2;checks+=1
 full=a[:,:,3].max();fbo.clear(0,0,0,0);draw(240,64,(12,)*4,n*256+192,flat((1,1,1,.5)),16)
 assert abs(read()[:,:,3].max()-full*.5)<1e-6;checks+=1
# Save true production shader samples, WITHOUT copying game scenery/text to the output material.
# These are explicitly labelled as offscreen specimens in the accompanying document.
ctx.enable(moderngl.BLEND);ctx.blend_func=(moderngl.SRC_ALPHA,moderngl.ONE_MINUS_SRC_ALPHA)
for n,name,w,h,r in [(0,'panel',250,666,20),(1,'target',368,106,20),(2,'counter',331,96,19),(3,'soft',370,140,18)]:
 background(synthetic if n==0 else sky)
 fbo.clear(.20,.25,.30,1)
 draw(w,h,(r,)*4,-(n*256+14),flat((0,0,0,.25)),39)
 draw(w,h,(r,)*4,n*256+64.25,colors(n))
 draw(w,h,(r,)*4,n*256+100.65,flat((1,1,1,1)))
 if n in [1,2]:draw(w,h,(r,)*4,n*256+192,flat((1,1,1,1)),max(h*.2,3))
 a=read();pad=26;x0,y0=round((W-w)/2)-pad,round((H-h)/2)-pad
 Image.fromarray((np.clip(a[y0:y0+h+2*pad,x0:x0+w+2*pad,:3],0,1)*255).astype('uint8')).save(OUT/f'offscreen-{name}.png')
report={'status':'PASS','renderer':ctx.info['GL_RENDERER'],'assertions':checks,'geometry':geometry,
 'limitations':'Offscreen production shader on synthetic/constant background. Feature-position fits only. NOT whole-image RGB pixel parity, Minecraft/Android captures, live framebuffer scheduling or full build validation.'}
(OUT/'shader-report.json').write_text(json.dumps(report,indent=2)+'\n')
print('PASS: old/new production GLSL compile/link on',ctx.info['GL_RENDERER'])
print('PASS:',checks,'offscreen assertions; all 4 material lanes, alpha/finite bounds, silhouettes, independent arcs, zero-lens reflections')
print('Source-trace maximum ridge/seam-position error (not image parity):',max(v['error_source_pixels'] for v in geometry),'original screenshot pixels')
print(report['limitations'])
