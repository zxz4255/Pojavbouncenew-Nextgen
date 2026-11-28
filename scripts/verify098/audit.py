from pathlib import Path
import sys,hashlib,zipfile,json,re,difflib
R=Path(__file__).resolve().parents[2]
baseline=Path(sys.argv[1]);baseline_sha='a82137f4177fcb2426f0bf7e5d0869e3d3be7fdea454bb714640b34bd14794a8'
assert hashlib.sha256(baseline.read_bytes()).hexdigest()==baseline_sha
with zipfile.ZipFile(baseline) as z:before={n:z.read(n) for n in z.namelist() if not n.endswith('/')}
K='src/main/kotlin/net/ccbluex/liquidbounce/';J='src/main/java/net/ccbluex/liquidbounce/';L='src/main/resources/resources/liquidbounce/lang/'
modified={J+'injection/mixins/minecraft/'+p+'.java' for p in ['client/MixinGui','client/MixinMinecraft','client/MixinMouseHandler','gui/MixinAbstractContainerScreen','gui/MixinScreen']}
modified|={J+'utils/input/AndroidGuiCursor.java',K+'features/module/ModuleManager.kt',K+'features/module/modules/combat/ModuleSwordBlock.kt',K+'features/module/modules/render/ModuleStorageESP.kt',K+'features/module/modules/render/OpaiChestLoot.kt',K+'features/module/modules/render/targethud/TargetHudLayer.kt',K+'ui/hud/components/CombatAndNotificationHudComponents.kt',K+'utils/combat/CombatManager.kt','src/main/resources/liquidbounce.mixins.json',L+'en_us.json',L+'zh_cn.json',L+'zh_cn_ui.json'}
deleted={K+'features/module/modules/combat/neoraven/'+p+'.kt' for p in ['ModuleNeoKillAura','ModuleNeoKillAura2','NeoAuraLogic','NeoAuraSettings','NeoAuraSupport','NeoBlockMachine']}
deleted|={K+'features/module/modules/combat/moonlight2/'+p+'.kt' for p in ['Moonlight1_8Packets','MoonlightRotation']}
deleted|={K+'features/module/modules/player/neoraven/'+p+'.kt' for p in ['ModuleNeoChestAura','ModuleNeoChestStealer','NeoChestEnvironment']}
deleted|={J+'injection/mixins/minecraft/network/'+p+'.java' for p in ['MixinNeoChestPackets','MixinNeoChestAcceptedSend']}
chest=K+'features/module/modules/player/cheststealer/'
restored={chest+'ModuleChestStealer.kt',chest+'features/FeatureChestAura.kt',chest+'features/FeatureSilentScreen.kt'}
out=R/'scripts/verify098/scope-audit.json'
if not out.exists():out.write_text('{}\n')
after={p.relative_to(R).as_posix():p.read_bytes() for p in R.rglob('*') if p.is_file()}
changed={n for n in before.keys()&after.keys() if before[n]!=after[n]};gone=before.keys()-after.keys();added=after.keys()-before.keys()
assert changed==modified,('unexpected modifications',changed^modified)
assert gone==deleted,('unexpected deletions',gone^deleted)
assert restored<=added
assert all(n in restored or n=='098-ORIGINAL-AURA-CHESTS.md' or n.startswith('scripts/verify098/') or n.startswith('references/liquidbounce098/') for n in added),sorted(added-restored)
# Every retained source not explicitly allowed above is protected, including ALL original KillAura,
# TpAura/CrystalAura, NeoInvManager and its shared classifiers, 095 music/shadow, 096 safety, 097 MVP.
assert all(before[n]==after[n] for n in before.keys()&after.keys()-modified)
lock=json.loads(after['scripts/verify098/source-lock.json'])
for n,sha in lock['files'].items():
 rel=n[len(K):];assert hashlib.sha256(after['references/liquidbounce098/'+rel]).hexdigest()==sha,n
observer='''        // Read-only observer for the existing island; never schedules or performs inventory actions.
        net.ccbluex.liquidbounce.features.module.modules.render.OpaiChestLoot.onCandidates(
            screen, cleanupPlan.usefulItems.filterIsInstance<ContainerItemSlot>().map { it.slotInContainer }
        )
'''
for n in restored:
 ref=after['references/liquidbounce098/'+n[len(K):]]
 actual=after[n]
 if n.endswith('/ModuleChestStealer.kt'):
  assert actual.decode().count(observer)==1
  actual=actual.decode().replace(observer,'').encode()
 assert actual==ref,('not original upstream',n)
assert after[K+'utils/inventory/InventoryValueGroups.kt']==after['references/liquidbounce098/utils/inventory/InventoryValueGroups.kt']
# Removed implementations cannot survive as a second registration, import, alias or live resource key.
for n,b in after.items():
 if not n.startswith('src/main/'):continue
 if n.endswith(('.kt','.java','.json')):
  s=b.decode()
  assert not re.search(r'ModuleNeoKillAura|ModuleNeoChest(?:Aura|Stealer)|NeoChestEnvironment|NeoAuraLogic|NeoAuraSettings|NeoAuraSupport|NeoBlockMachine|Moonlight1_8Packets|MoonlightRotation|combat\.(?:neoraven|moonlight2)|neo(?:KillAura2?|ChestAura|ChestStealer)',s),n
manager=after[K+'features/module/ModuleManager.kt'].decode()
for name in ['ModuleKillAura','ModuleTpAura','ModuleCrystalAura','ModuleNeoInvManager','ModuleChestStealer']:
 assert manager.count('            '+name+',')==1,name
assert not any(n.endswith('/ModuleChestAura.kt') for n in after if n.startswith('src/main/'))
# Localisation edits must be only removed modules and restored ChestStealer/UI terms.
def unique(pairs):
 d={}
 for k,v in pairs:assert k not in d,('duplicate JSON key',k);d[k]=v
 return d
def flatten(d,p=''):
 o={}
 for k,v in d.items():
  key=p+k
  if isinstance(v,dict):o.update(flatten(v,key+'.'))
  else:o[key]=v
 return o
for n in [L+'en_us.json',L+'zh_cn.json',L+'zh_cn_ui.json']:
 a=flatten(json.loads(before[n],object_pairs_hook=unique));b=flatten(json.loads(after[n],object_pairs_hook=unique))
 assert all(a[k]==b[k] for k in a.keys()&b.keys()),n
 assert all(re.search(r'neo(?:KillAura2?|ChestAura|ChestStealer)|Neo(?:KillAura2?|ChestAura|ChestStealer)',k) for k in a.keys()-b.keys()),n
 assert all(k.startswith('liquidbounce.module.chestStealer.') or k in ['modules.ChestStealer'] or k.startswith('terms.') for k in b.keys()-a.keys()),n
mix=json.loads(after['src/main/resources/liquidbounce.mixins.json'],object_pairs_hook=unique)
# Existing shared chest policy/items are retained solely for NeoInvManager; no Neo chest module remains.
inv=after[K+'features/module/modules/player/neoraven/ModuleNeoInvManager.kt'].decode()
assert 'NeoChestItems.describe(stack)' in inv and 'NeoChestPolicy.jitter(' in inv
for n in ['NeoChestItems','NeoChestPolicy']:
 p=K+'features/module/modules/player/neoraven/'+n+'.kt';assert before[p]==after[p]
report={'baseline_sha256':baseline_sha,'upstream_commit':lock['commit'],'modified':sorted(changed),'deleted':sorted(gone),'added':sorted(added),'unchanged_baseline_entries':len(before)-len(changed)-len(gone),'originals':'Aura and SilentScreen byte-identical upstream; ChestStealer identical after removing one read-only island observer block','scope':'PASS: only explicit removal/integration files changed. All original combat aura sources, NeoInvManager and shared classifiers, music, lyrics, MotionBlur, 096 inventory and 097 MVP protected by byte comparison.'}
out.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
print('PASS: immutable 097 -> 098:',len(changed),'modified,',len(added),'added,',len(gone),'deleted,',report['unchanged_baseline_entries'],'unchanged baseline entries')
print('PASS: upstream hashes/original equivalence; registration, no deleted implementation/import/resource residues; language JSON uniqueness; original KillAura/TpAura/CrystalAura + NeoInv/MVP/music byte preservation')
if len(sys.argv)>2:
 dst=Path(sys.argv[2]);files=sorted(p for p in R.rglob('*') if p.is_file())
 with zipfile.ZipFile(dst,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
  for p in files:z.write(p,p.relative_to(R))
 with zipfile.ZipFile(dst) as z:
  assert z.testzip() is None
  assert set(z.namelist())=={p.relative_to(R).as_posix() for p in files}
  for n in z.namelist():assert z.read(n)==(R/n).read_bytes(),n
 digest=hashlib.sha256(dst.read_bytes()).hexdigest();Path(str(dst)+'.sha256').write_text(digest+'  '+dst.name+'\n')
 print('ZIP',dst,'entries',len(files),'bytes',dst.stat().st_size,'SHA256',digest)
