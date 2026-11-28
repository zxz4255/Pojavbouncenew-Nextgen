#!/usr/bin/env python3
from pathlib import Path
import hashlib,json,re,sys,zipfile
R=Path(__file__).resolve().parents[2]
base=Path(sys.argv[1]);assert hashlib.sha256(base.read_bytes()).hexdigest()=='0931259e4085161b3874c52ade58de1b09bf9fc2b7a282771902750069055f89'
with zipfile.ZipFile(base) as z: before={n:z.read(n) for n in z.namelist() if not n.endswith('/')}
K='src/main/kotlin/net/ccbluex/liquidbounce/';J='src/main/java/net/ccbluex/liquidbounce/'
removed={K+s for s in [
 'features/module/modules/combat/ModuleTs2KillAura.kt',
 'features/module/modules/misc/ModulePointPearl.kt',
 'features/module/modules/misc/pointpearl/PearlMapScreen.kt',
 'features/module/modules/player/ModuleInvManager.kt',
 'features/module/modules/player/cheststealer/ModuleChestStealer.kt',
 'features/module/modules/player/cheststealer/features/FeatureChestAura.kt',
 'features/module/modules/player/cheststealer/features/FeatureSilentScreen.kt',
 'features/module/modules/world/ModuleChestAura.kt']}
allowed={K+s for s in [
 'features/module/ModuleManager.kt',
 'features/module/modules/combat/ModuleSwordBlock.kt',
 'features/module/modules/combat/neoraven/NeoAuraSupport.kt',
 'features/module/modules/misc/music/KugouPlayback.kt',
 'features/module/modules/player/ModuleNoGui.kt',
 'features/module/modules/player/neoraven/ModuleNeoChestAura.kt',
 'features/module/modules/player/neoraven/ModuleNeoChestStealer.kt',
 'features/module/modules/player/neoraven/ModuleNeoInvManager.kt',
 'features/module/modules/render/ModuleChestView.kt',
 'features/module/modules/render/ModuleStorageESP.kt',
 'features/module/modules/render/OpaiChestLoot.kt',
 'features/module/modules/render/fpsknife/ModuleFpsKnife.kt',
 'render/engine/BlurEffectRenderer.kt',
 'ui/hud/components/CombatAndNotificationHudComponents.kt']}
allowed|={J+s for s in [
 'injection/mixins/minecraft/client/MixinGui.java',
 'injection/mixins/minecraft/client/MixinMinecraft.java',
 'injection/mixins/minecraft/entity/MixinLocalPlayer.java',
 'injection/mixins/minecraft/gui/MixinAbstractContainerScreen.java',
 'injection/mixins/minecraft/gui/MixinScreen.java',
 'injection/mixins/minecraft/network/MixinMultiPlayerGameMode.java',
 'utils/input/AndroidGuiCursor.java']}
allowed|={'scripts/verify078/ProtocolRegression.kt','scripts/verify079/ProtocolRegression.kt'}
langprefix='src/main/resources/resources/liquidbounce/lang/'
for n,b in before.items():
 if n.startswith(langprefix) and n.endswith('.json'):
  old=json.loads(b);new=json.loads((R/n).read_bytes())
  if n.endswith('/zh_cn_ui.json'):
   def clean(x):
    if isinstance(x,dict):return {k:clean(v) for k,v in x.items() if k not in {'Aura','PointPearl','InvManager','ChestAura','ChestStealer'}}
    if isinstance(x,list):return list(map(clean,x))
    return x
   expect=clean(old)
  else:
   expect={k:v for k,v in old.items() if not re.match(r'liquidbounce\.module\.(aura|pointPearl|invManager|chestAura|chestStealer)\.',k)}
   if n.endswith(('/en_us.json','/zh_cn.json')):
    for key in ['liquidbounce.module.neoChestAura.description','liquidbounce.module.neoChestStealer.description']:
     expect[key]=expect[key].replace('; yields to an active old aura.','.').replace(' Does not replace the existing ChestStealer.','').replace('；旧开箱模块工作时让行。','。').replace('，不覆盖已有拿箱模块。','。')
  assert new==expect,('unrelated locale edit',n)
  allowed.add(n)
# Include this generated audit receipt in the file inventory even on its first run.
reportpath=R/'scripts/verify093/scope-audit.json'
if not reportpath.exists():reportpath.write_text('{}\n')
now={str(p.relative_to(R)):p.read_bytes() for p in R.rglob('*') if p.is_file()}
modified=sorted(n for n in before.keys()&now.keys() if before[n]!=now[n]);added=sorted(now.keys()-before.keys());deleted=set(before)-set(now)
assert deleted==removed,('deletion scope',deleted^removed)
assert set(modified)<=allowed,('unapproved modifications',set(modified)-allowed)
newprod={J+'utils/client/vfp/'+s+'.java' for s in ['ManualSwordBlocking','LegacySwordUseState']}
assert all(n in newprod or n=='093-MANUAL-BLOCK-MUSIC-CLEANUP.md' or n.startswith('scripts/verify093/') for n in added),added
names=['ModuleTs2KillAura','ModulePointPearl','ModuleChestStealer','ModuleInvManager','ModuleChestAura','FeatureSilentScreen','FeatureChestAura','PearlMapScreen']
for n,b in now.items():
 if n.startswith('src/main/') and n.endswith(('.java','.kt')):
  for name in names:assert name.encode() not in b,(n,name)
registry=now[K+'features/module/ModuleManager.kt'].decode()
for name in ['ModuleNeoKillAura','ModuleNeoKillAura2','ModuleNeoChestAura','ModuleNeoChestStealer','ModuleNeoInvManager','ModuleKillAura','ModuleMotionBlur']:
 assert registry.count('            '+name+',')==1,name
for name in names:assert name not in registry,name
manual=now[J+'utils/client/vfp/ManualSwordBlocking.java'].decode()
for guard in ['original != InteractionResult.PASS','player.isUsingItem()','isOnCooldown','isDestroying','hand != InteractionHand.MAIN_HAND','translateItemTo1_8','is1_8ConnectionActive','sendUseItemInAir','sendReleaseUseItem']:
 assert guard in manual,guard
assert not re.search(r'\.setItem\(|\.set\(DataComponents|\.startUsingItem\(|keyUse\.setDown',manual)
music=now[K+'features/module/modules/misc/music/KugouPlayback.kt'].decode()
for guard in ['"need_ogg" to "0"','"req_hash", "hash", "filehash", "file_hash"','hashes.any','requireSuccess(stream','userAuth(session)','resolveAsset(song, asset']:
 assert guard in music,guard
unchanged=len(set(now)&set(before))-len(modified)
report={'baseline_sha256':hashlib.sha256(base.read_bytes()).hexdigest(),'baseline_entries':len(before),'entries':len(now),'modified':modified,'added':added,'deleted':sorted(deleted),'unchanged_entries':unchanged,'scope':'PASS; focused compile/tests only; no full build/device/server/VIP acceptance'}
reportpath.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
if len(sys.argv)>2:
 dest=Path(sys.argv[2])
 with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
  for p in sorted(p for p in R.rglob('*') if p.is_file()):z.write(p,p.relative_to(R))
 with zipfile.ZipFile(dest) as z:
  assert z.testzip() is None
  assert set(z.namelist())==set(now)
  for n in z.namelist():assert z.read(n)==(R/n).read_bytes(),n
 digest=hashlib.sha256(dest.read_bytes()).hexdigest();Path(str(dest)+'.sha256').write_text(digest+'  '+dest.name+'\n')
 print('ZIP',dest,'entries',len(now),'bytes',dest.stat().st_size,'SHA256',digest)
print('PASS: scope audit; baseline',len(before),'modified',len(modified),'added',len(added),'deleted',len(deleted),'unchanged',unchanged)
