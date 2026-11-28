from pathlib import Path
import moderngl,numpy as np,struct,json
from PIL import Image
R=Path(__file__).resolve().parents[2];S=R/'src/main/resources/resources/liquidbounce/shaders/gui';O=Path(__file__).parent
ctx=moderngl.create_standalone_context(backend='egl',require=330)
def expand(s):return s.replace('#include <minecraft:dynamictransforms.glsl>','uniform mat4 ModelViewMat;uniform vec4 ColorModulator;').replace('#include <minecraft:projection.glsl>','uniform mat4 ProjMat;')
p=ctx.program(vertex_shader=expand((S/'onyx_soft_shadow.vsh').read_text()),fragment_shader=expand((S/'opai_island_shadow.fsh').read_text()))
W,H=512,256;fbo=ctx.simple_framebuffer((W,H),components=4,dtype='f4');fbo.use()
p['ModelViewMat'].write(np.eye(4,dtype='f4').T.tobytes());p['ProjMat'].write(np.array([[2/W,0,0,-1],[0,-2/H,0,1],[0,0,1,0],[0,0,0,1]],dtype='f4').T.tobytes());p['ColorModulator'].value=(1,1,1,1)
checks=0
Y,X=np.mgrid[:H,:W];px=X+.5-W/2;py=Y+.5-H/2
def distance(px,py,w,h,r):
 qx=np.abs(px)-w/2+r;qy=np.abs(py)-h/2+r
 return np.hypot(np.maximum(qx,0),np.maximum(qy,0))+np.minimum(np.maximum(qx,qy),0)-r

def render(w,h,r,scale=1):
 packed=[];hw=round(w*4);hh=round(h*4);rr=round(r*8)
 for x,y in [(0,0),(0,1),(1,1),(0,0),(1,1),(1,0)]:
  ox=-w/2-18+x*(w+36);oy=-h/2-18+y*(h+36)
  packed.append(struct.pack('=9f4if',W/2+ox*scale,H/2+oy*scale,0,ox,oy,0,0,0,80/255,hw,hh,rr,0,3.2))
 vbo=ctx.buffer(b''.join(packed));vao=ctx.vertex_array(p,[(vbo,'3f 2f 4f 2i 2i 1f','Position','UV0','Color','UV1','UV2','LineWidth')]);fbo.clear();vao.render(moderngl.TRIANGLES);a=np.frombuffer(fbo.read(components=4,dtype='f4'),dtype='f4').reshape(H,W,4)[::-1].copy();vao.release();vbo.release();return a

for w,h in [(48,24),(96,28),(220,38),(320,90),(360,170),(219.3,37.7),(83.17,29.63)]:
 for r in [0,3,min(w,h)/4,min(w,h)/2]:
  for scale in [.5,1,1.25]:
   a=render(w,h,r,scale);d=distance(px/scale,py/scale,w,h,r)
   inside=d<=0
   assert a[inside,3].max(initial=0)==0,('inner leak',w,h,r,scale);checks+=1
   assert a[:,:,:3].max()==0;checks+=1
   assert np.isfinite(a).all() and a[:,:,3].min()>=0 and a[:,:,3].max()<=80/255+1e-5;checks+=1
   assert a[d>18,3].max(initial=0)==0;checks+=1
   assert a[(d>1)&(d<5),3].max(initial=0)>0;checks+=1
# An exterior profile must decay continuously, not have an empty annulus followed by a dark ring.
a=render(220,38,19);bottom=a[:,W//2,3];seq=bottom[H//2+21:H//2+35]
assert (np.diff(seq)<=1e-5).all() and seq[0]>.1 and seq[-1]<.01;checks+=1
# Actual analytic shadow, with an explanatory SOFTWARE shell composited above it, not a game capture.
scene=np.empty((H,W,3),dtype='f4');scene[:]=(.74,.78,.81);scene+=(((X//24+Y//24)%2)*.055)[:,:,None]
alpha=a[:,:,3:4];scene=scene*(1-alpha)
d=distance(px,py,220,38,19);shell=(d<0)[:,:,None]*(152/255);scene=scene*(1-shell)
Image.fromarray((np.clip(scene,0,1)*255).astype('uint8')).save(O/'opai-shadow-offscreen.png')
result={'checks':checks,'renderer':ctx.info['GL_RENDERER'],'interior_alpha_max':0,'cases':'84 size/radius/scale combinations including fractional geometry, exact zero interior, bounded black alpha, finite support, monotone exterior profile','scope':'Production GLSL in EGL. Image includes software-composited shell, not full Opai/Minecraft/Android rendering.'}
(O/'shadow-report.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS:',json.dumps(result))
