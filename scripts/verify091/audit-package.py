#!/usr/bin/env python3
"""Strict 090->091 scope and byte-preserving source packaging. No game/runtime claims."""
from pathlib import Path
import zipfile,hashlib,json,sys
R=Path(__file__).resolve().parents[2];O=R/'scripts/verify091';K='src/main/kotlin/net/ccbluex/liquidbounce/'
B=Path(sys.argv[1]);Z=Path(sys.argv[2])
assert hashlib.sha256(B.read_bytes()).hexdigest()=='3be501bcca21991b6b6f43b8c90ce4aceafc763d52c7feb06a49dcfdc193a9d5'
allowed={K+p for p in ['render/ClientUniformDefine.kt','render/ClientShaders.kt','render/ClientRenderPipelines.kt','features/module/ClientModule.kt','features/module/ModuleManager.kt','features/module/modules/player/neoraven/ModuleNeoInvManager.kt']}
allowed.add('src/main/java/net/ccbluex/liquidbounce/injection/mixins/minecraft/render/MixinGameRenderer.java')
allowed.update('src/main/resources/resources/liquidbounce/lang/'+s+'.json' for s in ['en_us','zh_cn','zh_cn_ui','zh_tw'])
newproduction={K+p for p in ['features/module/modules/player/neoraven/NeoInvBindLatch.kt','features/module/modules/player/neoraven/NeoInvHotkeys.kt','features/module/modules/render/motionblur/MotionBlurHistory.kt','features/module/modules/render/motionblur/MotionBlurRenderer.kt','features/module/modules/render/motionblur/ModuleMotionBlur.kt']}
newproduction.add('src/main/resources/resources/liquidbounce/shaders/motionblur/accumulate.fsh')
def read(p):return (R/p).read_text()
registry=read(K+'features/module/ModuleManager.kt');neo=read(K+'features/module/modules/player/neoraven/ModuleNeoInvManager.kt');renderer=read(K+'features/module/modules/render/motionblur/MotionBlurRenderer.kt')
assert registry.count('            ModuleNeoInvManager,')==1
assert registry.count('            ModuleMotionBlur,')==1
assert 'modules.filterNot(ClientModule::externalBind)' in registry
assert 'override val externalBind: Boolean get() = true' in neo and 'init { NeoInvHotkeys }' in neo
assert 'private var session: SafeInventorySession? = null' in neo
assert 'override fun onEnabled() { reset(); acquirePending = true }' in neo
assert 'enabled = true' not in neo and 'DiscardItems", itemSortedSetOf()' in neo
assert 'if (acquirePending) acquireInventoryControl()' in neo
assert 'ModuleScaffold.running' in neo and 'SafeInventorySession.mayDiscard(' in neo
assert 'catch (error: LinkageError)' in neo and 'pauseOnError(error)' in neo
hotkeys=read(K+'features/module/modules/player/neoraven/NeoInvHotkeys.kt')
assert hotkeys.count('event.screen != null || mc.gui.screen() != null')==2
assert 'if (bind.matchesKeyRelease(event))' in hotkeys and 'if (bind.matchesMouseRelease(event))' in hotkeys
module=read(K+'features/module/modules/render/motionblur/ModuleMotionBlur.kt')
assert 'state = true' not in module and 'float("Strength", .55f, 0f..0.95f)' in module
assert 'boolean("BlurInGui", false)' in module
assert 'targets[readIndex].get()' in renderer
assert 'val destination = 1 - readIndex' in renderer
assert 'targets[destination].initAndGet(width, height)' in renderer
assert 'targets[readIndex].initAndGet' not in renderer
assert 'ModuleMotionBlur.enabled =' not in renderer
mixin=read('src/main/java/net/ccbluex/liquidbounce/injection/mixins/minecraft/render/MixinGameRenderer.java')
assert mixin.index('MotionBlurRenderer.beforeGui();')<mixin.index('KeybindsBackdrop.INSTANCE.prepareForRender();')
assert 'MotionBlurRenderer.INSTANCE.close();' in mixin
assert 'Lnet/minecraft/client/gui/render/GuiRenderer;render()V' in mixin
for log in ['inventory-results.txt','render-results.txt','activation-results.txt','pure-results.txt','shader-results.txt']:
 s=(O/log).read_text();assert 'PASS' in s and ': error:' not in s,log
with zipfile.ZipFile(B) as b:
 base=set(b.namelist());assert len(base)==2738
 files={p.relative_to(R).as_posix():p for p in R.rglob('*') if p.is_file() and not any(x in p.relative_to(R).parts for x in ['.git','.gradle','build','__pycache__','.cache','node_modules'])}
 deleted=base-files.keys();assert not deleted,deleted
 modified=sorted(p for p in base if files[p].read_bytes()!=b.read(p))
 assert set(modified)==allowed,(set(modified)-allowed,allowed-set(modified))
 added=set(files)-base
 assert {p for p in added if p.startswith('src/')}==newproduction
 assert all(p in newproduction or p.startswith('scripts/verify091/') or p=='091-NEOINV-MOTIONBLUR.md' for p in added),added
 # All existing translations unchanged; only scoped MotionBlur keys added.
 for lang in ['en_us','zh_cn','zh_cn_ui','zh_tw']:
  path='src/main/resources/resources/liquidbounce/lang/'+lang+'.json';old=json.loads(b.read(path));new=json.loads(read(path))
  assert all(new[k]==v for k,v in old.items())
  assert len(new)==len(old)+6
  assert all(k.startswith('liquidbounce.module.motionBlur.') for k in new.keys()-old.keys())
 report={'status':'PASS','baselineSHA256':hashlib.sha256(B.read_bytes()).hexdigest(),'modified':modified,
         'modifiedCount':len(modified),'baselineIdenticalCount':len(base)-len(modified),'deleted':[],
         'newProduction':sorted(newproduction),'checks':'registration/bind/defaults/history routing/safety/logs/localization and exact byte-preserving baseline scope; NOT full build/game'}
 # Report itself is an added entry.
 (O/'scope-audit.json').write_text(json.dumps(report,indent=2)+'\n')
 files['scripts/verify091/scope-audit.json']=O/'scope-audit.json'
 with zipfile.ZipFile(Z,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
  for path,p in sorted(files.items()):z.writestr(path,p.read_bytes())
 with zipfile.ZipFile(Z) as z:
  assert z.testzip() is None
  assert set(z.namelist())==set(files)
  for path,p in files.items():assert z.read(path)==p.read_bytes(),path
  for path in base-allowed:assert z.read(path)==b.read(path),path
 digest=hashlib.sha256(Z.read_bytes()).hexdigest()
 Z.with_suffix(Z.suffix+'.sha256').write_text(digest+'  '+Z.name+'\n')
 print(json.dumps({'status':'PASS','entries':len(files),'bytes':Z.stat().st_size,'modified':len(modified),
  'unchanged':len(base)-len(modified),'added':len(files)-len(base),'deleted':0,'SHA256':digest},indent=2))
