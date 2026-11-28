#!/usr/bin/env python3
"""Real offscreen GLSL execution on EGL (normally Mesa llvmpipe), NOT Minecraft/Pojav validation.
Requires numpy + moderngl. Synthetic plane/wall, modern and conventional depth; compares retained
kernels to pinned upstream shaders, checks exposure/rain/height gates and destination alpha.
"""
from pathlib import Path
import math, re
import numpy as np
import moderngl as gl
ROOT = Path(__file__).resolve().parents[2]
SH = ROOT/'src/main/resources/resources/liquidbounce/shaders/neoraven'
UP = Path(__file__).parent/'upstream'
ctx = gl.create_standalone_context(backend='egl', require=330)
print('GPU fixture:', ctx.info['GL_RENDERER'], '|', ctx.info['GL_VERSION'])
VERT = '''#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location=0) out vec2 texCoord;
void main(){ vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2)); texCoord=p; gl_Position=vec4(p*2.-1.,0.,1.); }
'''

def program(name, upstream=False):
    text = (UP/name if upstream else SH/name).read_text()
    if upstream:
        text = text.replace('#version 120','#version 330\n#extension GL_ARB_separate_shader_objects : require\nlayout(location=0) out vec4 fragColor;')
        text = text.replace('varying vec2 uv;', 'layout(location=0) in vec2 texCoord;\n#define uv texCoord')
        text = text.replace('varying vec4 projection;', 'uniform vec4 projection;')
        text = text.replace('gl_FragColor','fragColor').replace('texture2D','texture')
    return ctx.program(vertex_shader=VERT,fragment_shader=text)

def tex(data, linear=False):
    data = np.ascontiguousarray(data, dtype='f4')
    t=ctx.texture((data.shape[1],data.shape[0]),data.shape[2],data.tobytes(),dtype='f4')
    t.filter = (gl.LINEAR,gl.LINEAR) if linear else (gl.NEAREST,gl.NEAREST)
    t.repeat_x=t.repeat_y=False
    return t

def block(p, name, data):
    binding=0
    b=ctx.buffer(np.array(data,dtype='f4').tobytes())
    p[name].binding=binding;b.bind_to_uniform_block(binding)
    return b

def mat(p): return p.T.flatten().tolist()
def draw(p, size, textures, data=None, blockname=None, background=None):
    target=ctx.texture(size,4,dtype='f4'); fb=ctx.framebuffer([target]); fb.use();ctx.viewport=(0,0,*size)
    if background is not None:
        target.write(np.broadcast_to(np.array(background,dtype='f4'),(size[1],size[0],4)).copy().tobytes())
        ctx.enable(gl.BLEND); ctx.blend_func=(gl.ONE,gl.ONE_MINUS_SRC_ALPHA,gl.ZERO,gl.ONE)
    else: ctx.disable(gl.BLEND)
    for i,(name,t) in enumerate(textures.items()):
        if name in p: p[name].value=i;t.use(i)
    b=block(p,blockname,data) if data is not None else None
    vao=ctx.vertex_array(p,[]);vao.render(vertices=3)
    pixels=np.frombuffer(fb.read(components=4,dtype='f4'),dtype='f4').reshape(size[1],size[0],4).copy()
    assert np.isfinite(pixels).all(), 'NaN/Inf '+str(p)
    if b:b.release()
    vao.release();fb.release();target.release();ctx.disable(gl.BLEND)
    return pixels

def projection(w,h,modern=False):
    f=1/math.tan(math.radians(70)/2);n=.05;far=256.
    p=np.zeros((4,4),dtype='f4');p[0,0]=f*h/w;p[1,1]=f;p[3,2]=-1
    p[2,2]=0 if modern else -(far+n)/(far-n)
    p[2,3]=n if modern else -2*far*n/(far-n)
    return p

def fixture(w,h,modern=False,eyeY=64):
    p=projection(w,h,modern); y,x=np.mgrid[0:h,0:w]
    ray=np.stack((((x+.5)/w*2-1)/p[0,0],((y+.5)/h*2-1)/p[1,1],-np.ones((h,w))),axis=-1)
    c=math.sqrt(.5);up=np.array([0,c,c]); wz=np.array([0,-c,c])
    yp=ray@up;zp=ray@wz
    ground=np.divide(-1,yp,out=np.full_like(yp,np.inf),where=yp<0)
    wall=np.divide(-5,zp,out=np.full_like(zp,np.inf),where=zp<0)
    wall=np.where((wall*yp>-1)&(wall*yp<3),wall,np.inf)
    d=np.minimum(ground,wall); valid=np.isfinite(d)&(d<200)
    d=np.where(valid,d,200)
    clipz=-p[2,2]+p[2,3]/d
    raw=clipz if modern else clipz*.5+.5
    raw=np.where(valid,raw,0 if modern else 1)
    scene=np.zeros((h,w,4),dtype='f4');scene[:,:,0]=(x/w)*.7+.1;scene[:,:,1]=(y/h)*.7+.1
    scene[:,:,2]=np.where(wall<ground,.95,.35);scene[:,:,3]=1
    scene[(x-w*.6)**2+(y-h*.7)**2 < (h*.1)**2,:3]=1
    return p,tex(raw[:,:,None]),tex(scene,True),up,wz

progs={n:program(n) for n in ['cinematic_light.fsh','cinematic_high.fsh','rain_trace.fsh','rain_composite.fsh','downsample.fsh']}
oldlight=program('cinematic.frag',True);oldhigh=program('cinematic-high.frag',True)
oldrain=program('onyx_rain_reflection.fsh',True);oldcomposite=program('onyx_rain_reflection_composite.fsh',True)
print('PASS: five port shaders and four pinned upstream shader fixtures compiled and linked on OpenGL.')

for w,h in [(128,96),(129,97)]:
    p,depth,scene,up,wz=fixture(w,h)
    miptex=[];prev=scene;mw=w;mh=h
    for i in range(5):
        mw=max(1,mw//2);mh=max(1,mh//2)
        miptex.append(tex(draw(progs['downsample.fsh'],(mw,mh),{'texture0':prev}),True));prev=miptex[-1]
    scene.build_mipmaps(0,5);scene.filter=(gl.LINEAR_MIPMAP_LINEAR,gl.LINEAR)
    for name,old in [('cinematic_light.fsh',oldlight),('cinematic_high.fsh',oldhigh)]:
        errors=[]
        for bloom in [0.,.75]:
            controls=[.75,bloom,.75,1.12]
            data=mat(np.linalg.inv(p))+[p[0,0],p[1,1],p[0,2],p[1,2]]+[2,-1,0,5]+[1/w,1/h,256,0]+controls
            textures={'scene':scene,'geometry':depth,**{f'Mip{i+1}':t for i,t in enumerate(miptex)}}
            result=draw(progs[name],(w,h),textures,data,'CinematicData')
            old['pixel'].value=(1/w,1/h);old['controls'].value=controls
            if 'projection' in old: old['projection'].value=(p[0,0],p[1,1],p[2,2],p[2,3])
            reference=draw(old,(w,h),{'scene':scene,'geometry':depth})
            error=float(np.max(abs(result-reference)));errors.append(error)
            # Explicit mip chain is not the driver's hardware mip generator for odd dimensions.
            tolerance=.015 if bloom and name.endswith('high.fsh') else .002
            assert error<tolerance,(name,w,h,bloom,error)
        print(f'PASS: {name} {w}x{h} upstream max error (bloom off/on) {errors}')
    scene.filter=(gl.LINEAR,gl.LINEAR)
    # Both depth encodings must reconstruct the same actual world and wetness.
    def rainData(p,modern,eyeY=64,time=.2,rain=1):
        return mat(p)+mat(np.linalg.inv(p))+[1,0,0,0]+[*up,0]+[*wz,0]+[56,eyeY,56,112]+[0,0,rain,time]+[.4,.6,.9,0]+[1/w,1/h,1 if modern else 2,0 if modern else -1]+[(w+1)//2,(h+1)//2,1 if modern else 0,-64]
    wet=np.zeros((112,112,4),dtype='f4'); wet[:,:,0]=126/255;wet[:,:,3]=1
    wetOld=wet.copy();wetOld[:,:,0]=62/255
    wt=tex(wet);wo=tex(wetOld)
    data=rainData(p,False)
    result=draw(progs['rain_trace.fsh'],((w+1)//2,(h+1)//2),{'DepthSampler':depth,'SceneSampler':scene,'ExposureSampler':wt},data,'NeoRainData')
    for key,value in {'uPixel':(1/w,1/h),'uDepthProjection':(p[2,2],p[2,3]),'uNoiseOrigin':(0,0),'uRain':(1,.2),'uFrustum':(p[0,0],p[1,1],0,0),'uExposure':(56,64,56,112),'uWorldX':(1,0,0),'uWorldY':tuple(up),'uWorldZ':tuple(wz),'uSky':(.4,.6,.9)}.items():
        if key in oldrain:oldrain[key].value=value
    reference=draw(oldrain,((w+1)//2,(h+1)//2),{'DepthSampler':depth,'SceneSampler':scene,'ExposureSampler':wo})
    err=float(np.max(abs(result-reference)));assert err<.003,(w,h,'rain parity',err)
    assert result[:,:,3].max()>.03,'missing wet reflections'
    pm,dm,sm,_,_=fixture(w,h,True)
    modern=draw(progs['rain_trace.fsh'],((w+1)//2,(h+1)//2),{'DepthSampler':dm,'SceneSampler':sm,'ExposureSampler':wt},rainData(pm,True),'NeoRainData')
    errModern=float(np.max(abs(result-modern))); assert errModern<.005,(w,h,'modern depth',errModern)
    # Dry exposure and no rain must have no contribution.
    dry=tex(np.zeros_like(wet))
    for label,ex,r in [('dry map',dry,1),('no rain',wt,0)]:
        off=draw(progs['rain_trace.fsh'],((w+1)//2,(h+1)//2),{'DepthSampler':depth,'SceneSampler':scene,'ExposureSampler':ex},rainData(p,False,rain=r),'NeoRainData')
        assert np.max(abs(off))==0,label
    # Same physical top surface at a NEGATIVE world height, unavailable in 1.8's LA8 encoding.
    low=wet.copy();low[:,:,0]=42/255
    below=draw(progs['rain_trace.fsh'],((w+1)//2,(h+1)//2),{'DepthSampler':depth,'SceneSampler':scene,'ExposureSampler':tex(low)},rainData(p,False,eyeY=-20),'NeoRainData')
    assert np.max(abs(below-result))<.0001,'negative-height encoding'
    reflected=tex(result)
    composite=draw(progs['rain_composite.fsh'],(w,h),{'DepthSampler':depth,'ReflectionSampler':reflected},data,'NeoRainData',background=[.2,.3,.4,.37])
    assert np.max(abs(composite[:,:,3]-.37))<.000001,'destination alpha changed'
    oldcomposite['uPixel'].value=(1/w,1/h);oldcomposite['uDepthProjection'].value=(p[2,2],p[2,3]);oldcomposite['uReflectionSize'].value=((w+1)//2,(h+1)//2)
    oldComp=draw(oldcomposite,(w,h),{'DepthSampler':depth,'ReflectionSampler':reflected},background=[.2,.3,.4,.37])
    compErr=float(np.max(abs(composite-oldComp)));assert compErr<.002,compErr
    # Time changes must preserve finite output; animation kernel equivalence is separately source-audited.
    changed=draw(progs['rain_trace.fsh'],((w+1)//2,(h+1)//2),{'DepthSampler':depth,'SceneSampler':scene,'ExposureSampler':wt},rainData(p,False,time=.7),'NeoRainData')
    print(f'PASS: Rain {w}x{h}: upstream error={err:.6f}, reversed-depth={errModern:.6f}, composite={compErr:.6f}; dry/rain/negative-Y/alpha gates. Time delta={np.max(abs(changed-result)):.6f}')
print('PASS: EGL synthetic-scene smoke/parity checks; NOT target-game or device visual parity.')

# Isolate ripple normals: the plane/wall fixture above does not guarantee an on-screen SSR hit,
# so its zero time delta is NOT evidence of animated reflected objects.
def function(text,name):
    start=re.search(r'(?:float|vec[234]|void)\s+'+re.escape(name)+r'\s*\(',text).start()
    end=text.index('{',start);depth=1;end+=1
    while depth:
        if text[end]=='{':depth+=1
        elif text[end]=='}':depth-=1
        end+=1
    return text[start:end]
ripplePrograms=[]
for src in [(SH/'rain_trace.fsh').read_text(),(UP/'onyx_rain_reflection.fsh').read_text()]:
    body='\n'.join(function(src,f) for f in ['hash','ripple'])
    frag='''#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
uniform vec2 uRain;
'''+body+'\nvoid main(){fragColor=vec4(ripple(texCoord*16.0),0.,1.);}'
    ripplePrograms.append(ctx.program(vertex_shader=VERT,fragment_shader=frag))
frames=[]
for time in [.2,.2001,.7]:
    images=[]
    for p in ripplePrograms:
        p['uRain'].value=(1,time);images.append(draw(p,(128,128),{}))
    assert np.max(abs(images[0]-images[1]))==0,'ripple kernel diverged'
    frames.append(images[0])
assert np.max(abs(frames[0]-frames[1]))>1e-6,'ripples not moving'
assert np.max(abs(frames[0]-frames[2]))>.001,'no animated wavefront'
print('PASS: animated ripple normals at three times are bit-identical to upstream and change with time (isolated kernel, not an in-game SSR-animation test).')
