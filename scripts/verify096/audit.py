from pathlib import Path
import sys,hashlib,zipfile,json
R=Path(__file__).resolve().parents[2];p=Path(sys.argv[1]);sha='bb2f76de4842d59dc9f40db7c72cd4cb848df9602a2ee7b691b0105d054aeaef'
assert hashlib.sha256(p.read_bytes()).hexdigest()==sha
with zipfile.ZipFile(p) as z:before={n:z.read(n) for n in z.namelist() if not n.endswith('/')}
out=R/'scripts/verify096/scope-audit.json'
if not out.exists():out.write_text('{}\n')
after={str(p.relative_to(R)):p.read_bytes() for p in R.rglob('*') if p.is_file()}
K='src/main/kotlin/net/ccbluex/liquidbounce/'
allowed={K+'features/module/modules/player/neoraven/ModuleNeoInvManager.kt',K+'utils/inventory/SafeInventorySession.kt',K+'utils/inventory/InventorySafetyPolicy.kt'}
changed=sorted(n for n in before.keys()&after.keys() if before[n]!=after[n]);added=sorted(after.keys()-before.keys());deleted=sorted(before.keys()-after.keys())
assert set(changed)==allowed,changed
assert not deleted,deleted
assert all(n=='096-NEOINV-EXECUTION-FIX.md' or n.startswith('scripts/verify096/') for n in added),added
n=K+'utils/inventory/InventorySafetyPolicy.kt';s=after[n].decode();a=s.index('    /** Armor exchange with');b=s.index('    fun discardAllowed(',a)
assert s[:a]+s[b:]==before[n].decode(),'Existing predict/gate/discard/allocator changed'
s=after[K+'utils/inventory/SafeInventorySession.kt'].decode()
assert 'allowClosedInventory: Boolean = false' in s
assert 'current !is InventoryScreen || current.menu !== player.inventoryMenu' in s
assert 'player.containerMenu !== player.inventoryMenu' in s
assert 'gameMode.handleContainerInput(current.containerId, click.slot, click.button, nativeMode, player)' in s
assert 'if (busy || !ready()) return false\n        gate.reset()' in s
# No direct native menu/ItemStack mutation or raw click-packet forge in changed production files.
for n in allowed:
 s=after[n].decode()
 for token in ['.setItem(','.setCount(','.item =','.carried =','ServerboundContainerClickPacket','Thread.sleep(']:assert token not in s,(n,token)
assert all(before[n]==after[n] for n in before if n not in allowed)
r={'baseline_sha256':sha,'modified':changed,'added':added,'deleted':deleted,'unchanged_baseline_entries':len(before)-len(changed),'scope':'PASS: only three Neo inventory execution/transport/planning production files changed; 095 music/lyrics/shadow, binds and unrelated baseline bytes preserved. Existing pure predict/gate/discard rules unchanged.'}
out.write_text(json.dumps(r,indent=2,ensure_ascii=False)+'\n')
print('PASS: immutable 095→096 audit:',len(changed),'modified,',len(added),'added,',len(deleted),'deleted,',r['unchanged_baseline_entries'],'unchanged baseline entries')
if len(sys.argv)>2:
 dst=Path(sys.argv[2])
 with zipfile.ZipFile(dst,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
  for p in sorted(p for p in R.rglob('*') if p.is_file()):z.write(p,p.relative_to(R))
 with zipfile.ZipFile(dst) as z:
  assert z.testzip() is None;assert set(z.namelist())==set(after)
  for n in z.namelist():assert z.read(n)==(R/n).read_bytes(),n
 digest=hashlib.sha256(dst.read_bytes()).hexdigest();Path(str(dst)+'.sha256').write_text(digest+'  '+dst.name+'\n')
 print('ZIP',dst,'entries',len(after),'bytes',dst.stat().st_size,'SHA256',digest)
