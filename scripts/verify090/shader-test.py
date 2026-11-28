#!/usr/bin/env python3
"""Quiet GUI production GLSL/blur tests, with immutable089 HUD numeric negative comparison.
Uses verify089's raw EGL transport harness ONLY, not its old acceptance assertions/output writes.
"""
from pathlib import Path
import zipfile,hashlib,json,struct
R=Path(__file__).resolve().parents[2];OUT=R/'scripts/verify090'
code=(R/'scripts/verify089/shader-test.py').read_text().split('checks=0\n')[0]
exec(compile(code,str(R/'scripts/verify089/shader-test.py'),'exec'))
OUT=R/'scripts/verify090'
zpath=R.parent/'Pojavbouncenew-Nextgen-089-REFERENCE-GLASS-CANDIDATE.zip'
assert hashlib.sha256(zpath.read_bytes()).hexdigest()=='7944467014ce6c1086e42c62f48047032e7adde86a9fa36d485276668ea85686'
with zipfile.ZipFile(zpath) as z:
 baseline=ctx.program(vertex_shader=expand((S/'liquid_glass.vsh').read_text()),fragment_shader=expand(z.read('src/main/resources/resources/liquidbounce/shaders/clickgui/light_pollution_glass.fsh').decode()))
for prog in [baseline]:
 prog['ModelViewMat'].write(np.eye(4,dtype='f4').T.tobytes());prog['ProjMat'].write(np.array([[2/W,0,0,-1],[0,-2/H,0,1],[0,0,1,0],[0,0,0,1]],dtype='f4').T.tobytes());prog['ColorModulator'].value=(1,1,1,1);prog['Sampler0'].value=0
new=p;checks=0;hudmax=0
background(synthetic)
# Body, shadow, rim and exterior pass for all three HUD profiles; same inputs on both versions.
for profile in [1,2,3]:
 for w,h in [(368,106),(331,96),(220,680),(64,64)]:
  for local in [16.0,64.0,64.75,-6,-14,100.45,192]:
   mode=-(profile*256+abs(local)) if local<0 else profile*256+local
   col=flat((1,1,1,.3)) if local<0 or local>=100 else colors(profile)
   def frame(prog):
    global p
    p=prog;fbo.clear(0,0,0,0);draw(w,h,(12,)*4,mode,col,39 if local<0 or local==192 else 0);return read()
   a=frame(new);b=frame(baseline);error=float(np.max(np.abs(a-b)));hudmax=max(hudmax,error)
   assert error<=2e-6,(profile,local,error);checks+=1
p=new
# Quiet panel is spatially uniform on a flat background: zero top/corner highlights.
background(sky)
quiet=flat((0,96/255,96/255,.2))
fbo.clear(0,0,0,0);draw(250,560,(3,)*4,64,quiet);a=read()
x0,y0=(W-250)//2,(H-560)//2;inside=a[y0+5:y0+555,x0+5:x0+245,:3]
assert np.max(np.ptp(inside,axis=(0,1)))<1e-6;checks+=1
# Even malformed/legacy positive reflection/rim settings cannot reintroduce a GUI highlight.
fbo.clear(0,0,0,0);draw(250,560,(3,)*4,64.75,flat((1,96/255,96/255,.2)));b=read()
assert np.max(np.abs(a-b))<1e-6;checks+=1
for mode in [100.45,192]:
 fbo.clear(0,0,0,0);draw(250,560,(3,)*4,mode,flat((1,1,1,1)),30)
 assert read().max()==0;checks+=1
# One black shadow lobe only, outside body, with capped emitter opacity.
fbo.clear(0,0,0,0);draw(250,560,(3,)*4,-6,flat((0,0,0,28/255)),19)
s=read();assert s[:,:,:3].max()==0 and 0<s[:,:,3].max()<=28/255+1e-6;checks+=1
assert s[H//2,W//2,3]==0;checks+=1
# Compile and execute BOTH actual Gaussian blur fragment shaders (uniform declarations intact).
vs='''#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location=0) out vec2 texCoord;
void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);texCoord=p;gl_Position=vec4(p*2.0-1.0,0,1);}
'''
blurprogs=[]
for name in ['glass_blur_h.fsh','glass_blur_v.fsh']:
 src=(S/name).read_text().replace('#include <liquidbounce:clickgui/glass_blur.glsl>',(S/'glass_blur.glsl').read_text())
 prog=ctx.program(vertex_shader=vs,fragment_shader=src);prog['texture0'].value=0;prog['ClickGuiGlassBlurData'].binding=0;blurprogs.append(prog)
ub=ctx.buffer(reserve=16);ub.bind_to_uniform_block(0)
def gaussian(data):
 source=ctx.texture((W,H),3,data.tobytes(),dtype='f4');source.filter=(moderngl.LINEAR,moderngl.LINEAR);source.repeat_x=False;source.repeat_y=False
 a=ctx.texture((W//2,H//2),4,dtype='f4');b=ctx.texture((W//2,H//2),4,dtype='f4')
 for t in [a,b]:t.filter=(moderngl.LINEAR,moderngl.LINEAR);t.repeat_x=False;t.repeat_y=False
 fa=ctx.framebuffer([a]);fb=ctx.framebuffer([b]);current=source
 for n,(axis,scale) in enumerate([(0,1.15),(1,1.15),(0,2.13),(1,2.13)]):
  target=fa if axis==0 else fb;target.use();ctx.viewport=(0,0,W//2,H//2)
  current.use(0);ub.write(struct.pack('4f',scale,1,0 if n<2 else 1,0));vao=ctx.vertex_array(blurprogs[axis],[]);vao.render(vertices=3);vao.release();current=a if axis==0 else b
 # Read a full-size bilinear upsample, matching the card sampler of the half-size FBO.
 copy=ctx.program(vertex_shader=vs,fragment_shader='''#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location=0) in vec2 texCoord;layout(location=0) out vec4 color;uniform sampler2D inputTex;
void main(){color=texture(inputTex,texCoord);}''');copy['inputTex'].value=0
 fbo.use();ctx.viewport=(0,0,W,H);b.use(0);vao=ctx.vertex_array(copy,[]);vao.render(vertices=3);vao.release();result=read()
 for ob in [fa,fb,a,b,source,copy]:ob.release()
 return result
ctx.disable(moderngl.BLEND)
constant=np.full((H,W,3),.4,dtype='f4');c=gaussian(constant)
assert np.max(np.abs(c[:,:,:3]-.4))<2e-6;checks+=1
checker=np.repeat((((X//8+Y//8)%2)*.8+.1)[:,:,None],3,axis=2).astype('f4')
blurred=gaussian(checker)
assert np.isfinite(blurred).all() and blurred[:,:,:3].min()>=0 and blurred[:,:,:3].max()<=1.00001;checks+=1
checker_ratio=float(blurred[60:-60,60:-60,:3].std()/checker[60:-60,60:-60].std())
assert checker_ratio<.15;checks+=1
# Outside coverage is empty: blurred scene is composited only inside the rounded card.
fbo.use();tex.use(0);background(blurred[::-1,:,:3].copy());fbo.clear(0,0,0,0);draw(250,560,(3,)*4,64,quiet);a=read()
assert a[0,0,3]==0 and a[H//2,W//2,3]==1;checks+=1
assert a[y0,x0,3]<1 and a[y0+4,x0+4,3]==1;checks+=1
# Labelled specimen is an offscreen shader output, not an in-game screenshot.
# Copy unblurred synthetic backdrop, then overlay the shader's clipped blurred card + black shadow.
back=synthetic.copy();blurred=gaussian(back)
# fbo image is built on CPU only for the OUTSIDE unmodified synthetic scenery; card is actual shader output.
fbo.use();tex.use(0);background(blurred[::-1,:,:3].copy());fbo.clear(0,0,0,0)
ctx.disable(moderngl.BLEND)
draw(250,560,(3,)*4,-6,flat((0,0,0,28/255)),19)
# Capture straight-alpha shadow separately for the labelled CPU composite.
shadow=read();fbo.clear(0,0,0,0);ctx.disable(moderngl.BLEND);draw(250,560,(3,)*4,64,quiet);card=read()
scene=back[::-1].copy();scene*=1-shadow[:,:,3:4];scene=scene*(1-card[:,:,3:4])+card[:,:,:3]*card[:,:,3:4]
pad=22;crop=scene[y0-pad:y0+560+pad,x0-pad:x0+250+pad]
Image.fromarray((np.clip(crop,0,1)*255).astype('uint8')).save(OUT/'offscreen-quiet-gui.png')
report={'assertions':checks,'hud_max_rgba_difference_vs089':hudmax,'renderer':ctx.info['GL_RENDERER'],'gaussian_checker_std_ratio':checker_ratio,'scope':'Production GLSL/EGL numeric tests + actual4 blur fragment passes with fullscreen harness. No Minecraft/Android/full-build verification.'}
(OUT/'shader-report.json').write_text(json.dumps(report,indent=2)+'\n')
print('PASS:',checks,'quiet-panel/blur/HUD-preservation shader assertions on',ctx.info['GL_RENDERER'])
print('HUD max RGBA difference vs immutable089:',hudmax)
print('GUI: no highlight/rim/arc/lens, constant interior flat-scene response; single black shadow; actual H/V Gaussian4-pass lowpass and clipped card coverage')
print('NOT a Minecraft/Android screenshot, full Gradle build or game framebuffer-scheduling acceptance')
