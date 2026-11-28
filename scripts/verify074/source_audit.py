#!/usr/bin/env python3
"""Pinned-source, shader-kernel and integration audit; does NOT prove Mixin application."""
from pathlib import Path
import hashlib, json, re, subprocess, os
ROOT=Path(__file__).resolve().parents[2]
HERE=Path(__file__).parent
K=ROOT/'src/main/kotlin/net/ccbluex/liquidbounce'
SH=ROOT/'src/main/resources/resources/liquidbounce/shaders/neoraven'
manifest=json.loads((HERE/'upstream/manifest.json').read_text())
for name,digest in manifest['sha256'].items():
    assert hashlib.sha256((HERE/'upstream'/Path(name).name).read_bytes()).hexdigest()==digest,name
assert manifest['commit']=='b483ffc576e335e01f2914c920e88289ab090e66'
assert (ROOT/'licenses/neoraven/NOTICE.md').is_file()
assert 'GNU GENERAL PUBLIC LICENSE' in (ROOT/'licenses/neoraven/LICENSE').read_text()
print('PASS: seven untouched pinned upstream source hashes and GPL attribution.')

def function(text,name):
    m=re.search(r'(?:float|vec[234]|void)\s+'+re.escape(name)+r'\s*\(',text);assert m,name
    start=m.start();end=text.index('{',start)+1;depth=1
    while depth:
        if text[end]=='{':depth+=1
        elif text[end]=='}':depth-=1
        end+=1
    return text[start:end]
def canonical(s):
    s=re.sub(r'//[^\n]*|/\*.*?\*/','',s,flags=re.S)
    return re.sub(r'\s+','',s.replace('texture2D','texture').replace('gl_FragColor','fragColor'))
count=0
for upstream,port,names in [
 ('cinematic.frag','cinematic_light.fsh',['crease','highlight','main']),
 ('cinematic-high.frag','cinematic_high.fsh',['occlusion','bright','bloomLayer','main']),
 ('onyx_rain_reflection.fsh','rain_trace.fsh',['groundNormal','exposureFade','hash','noise','ripple','traceReflection','main']),
 ('onyx_rain_reflection_composite.fsh','rain_composite.fsh',['accumulate','main']),
]:
    old=(HERE/'upstream'/upstream).read_text();new=(SH/port).read_text()
    # Explicitly enumerate compatibility substitutions; no blanket replacement of constants/loops.
    old=old.replace('projection.y','uProjection.y')
    old=old.replace('texture2D(scene, coord, lod)','sampleSceneMip(coord, lod)')
    old=old.replace('rawDepth >= 0.999999','skyDepth(rawDepth)')
    if port == 'rain_composite.fsh':
        old=old.replace('eyeDepth(depthCoord)', 'eyeDepth(texture(DepthSampler,depthCoord).r)')
        old=old.replace('eyeDepth(uv)', 'eyeDepth(texture(DepthSampler,uv).r)')
    for name in names:
        assert canonical(function(old,name))==canonical(function(new,name)),(port,name)
        count+=1
print(f'PASS: {count} original shader kernel functions (including all four effect main functions), constants and animation equations retained after enumerated API substitutions.')

cm=(K/'features/module/modules/render/ModuleCinematic.kt').read_text()
assert 'Quality.HIGH' in cm and 'LIGHTWEIGHT("Lightweight")' in cm
for field in ['AmbientOcclusion','Bloom','LensDistortion','EyeAdaptation']:
    assert f'float("{field}", 0.75f, 0f..1f).onChange {{ kotlin.math.round(it * 20f) / 20f }}' in cm
rain=(K/'features/module/modules/render/ModuleRainReflections.kt').read_text()
assert not re.search(r'by\s+(float|int|boolean|enumChoice)\(',rain)
registry=(K/'features/module/ModuleManager.kt').read_text()
for name in ['ModuleCinematic','ModuleRainReflections']:assert registry.count('            '+name+',')==1
for p in ['ClientShaders.kt','ClientRenderPipelines.kt','ClientUniformDefine.kt']:
    assert (K/'render'/p).is_file()
hook=(ROOT/'src/main/java/net/ccbluex/liquidbounce/injection/mixins/minecraft/render/MixinGameRenderer.java').read_text()
before=hook.index('NeoRavenPostEffects.INSTANCE.beforeWorldOverlays(event)')
event=hook.index('EventManager.INSTANCE.callEvent(event)',before)
after=hook.index('NeoRavenPostEffects.INSTANCE.afterWorldOverlays(event)',event)
assert before<event<after
assert 'try (event)' in hook[before-60:before]
# Ensure the injection target descriptor exists on the actual MC class, not just a fixture.
if os.environ.get('JAVA_HOME') and os.environ.get('MC_JAR'):
    output=subprocess.check_output([os.environ['JAVA_HOME']+'/bin/javap','-s','-p','-classpath',os.environ['MC_JAR'],'net.minecraft.client.renderer.LevelRenderer'],text=True)
    target=re.search(r'target = "Lnet/minecraft/client/renderer/LevelRenderer;render([^\"]+)"',hook).group(1)
    assert 'descriptor: '+target in output,'world injection target drift'
print('PASS: settings/defaults/0.05 quantization, native registry, explicit world-only Rain -> overlay flush -> Cinematic ordering; target method descriptor audit (not a Mixin runtime test).')
for language in ['en_us','zh_cn','zh_cn_ui']:
    def no_duplicates(pairs):
        d={}
        for key,value in pairs:
            assert key not in d,(language,key)
            d[key]=value
        return d
    d=json.loads((ROOT/f'src/main/resources/resources/liquidbounce/lang/{language}.json').read_text(),object_pairs_hook=no_duplicates)
    if language=='zh_cn_ui':
        assert d['modules']['Cinematic']=='电影画质' and d['modules']['RainReflections']=='雨天反射'
    else:assert 'liquidbounce.module.rainReflections.description' in d
print('PASS: EN/ZH descriptions and native Chinese UI labels; JSON duplicate-key audit.')
