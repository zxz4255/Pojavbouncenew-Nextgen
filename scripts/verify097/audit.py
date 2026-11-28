from pathlib import Path
import sys,zipfile,hashlib,json
R=Path(__file__).resolve().parents[2];base=Path(sys.argv[1]);expected='94c44fb2ab2cb598d29f854dfa951767a6c5339ca67ff2c96bd1ffc20afff3c3'
assert hashlib.sha256(base.read_bytes()).hexdigest()==expected
with zipfile.ZipFile(base) as z:old={n:z.read(n) for n in z.namelist() if not n.endswith('/')}
out=R/'scripts/verify097/scope-audit.json'
if not out.exists():out.write_text('{}\n')
now={str(p.relative_to(R)):p.read_bytes() for p in R.rglob('*') if p.is_file()}
k='src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/cs2mvp/'
lang='src/main/resources/resources/liquidbounce/lang/'
allowed={k+'ModuleCs2Mvp.kt',lang+'en_us.json',lang+'zh_cn.json'}
modified=sorted(n for n in old.keys()&now.keys() if old[n]!=now[n]);added=sorted(now.keys()-old.keys());deleted=sorted(old.keys()-now.keys())
assert set(modified)==allowed,modified;assert not deleted,deleted
assert all(n=='097-MVP-ENTITY-KILLS.md' or n.startswith('scripts/verify097/') for n in added),added
for language in ['en_us','zh_cn']:
 n=lang+language+'.json';a=json.loads(old[n]);b=json.loads(now[n]);key='liquidbounce.module.cS2MVP.description'
 assert a[key]!=b[key];a.pop(key);b.pop(key);assert a==b
for n in old:
 if n not in allowed:assert now[n]==old[n],n
# The module change is restricted to wider target typing and inclusive eligibility; no evidence/timeline rewrite.
s=old[k+'ModuleCs2Mvp.kt'].decode().replace('import net.ccbluex.liquidbounce.features.misc.FriendManager\n','').replace('import net.minecraft.world.entity.player.Player','import net.minecraft.world.entity.LivingEntity').replace('linkedMapOf<MvpLife, Player>()','linkedMapOf<MvpLife, LivingEntity>()').replace('entity: Player','entity: LivingEntity').replace('as? Player','as? LivingEntity').replace('private fun enemy(', 'private fun eligible(').replace('enemy(entity)','eligible(entity)').replace('enemy(target)','eligible(target)').replace('return entity !== local && !entity.isSpectator && !FriendManager.isFriend(entity) && !local.isAlliedTo(entity)','// 097: every living target counts, not just enemy players; never award our own death.\n        return entity !== local && !entity.isSpectator')
assert s.encode()==now[k+'ModuleCs2Mvp.kt']
r={'baseline_sha256':expected,'modified':modified,'added':added,'deleted':deleted,'unchanged_entries':len(old)-len(modified),'scope':'PASS: MVP living-target eligibility only, plus two language description lines. Complete MVP state/renderer and all unrelated096 bytes unchanged.'}
out.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n');print('PASS: immutable 096→097 scope:',len(modified),'modified,',len(added),'added,',len(deleted),'deleted,',r['unchanged_entries'],'unchanged')
if len(sys.argv)>2:
 dst=Path(sys.argv[2])
 with zipfile.ZipFile(dst,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
  for p in sorted(p for p in R.rglob('*') if p.is_file()):z.write(p,p.relative_to(R))
 with zipfile.ZipFile(dst) as z:
  assert z.testzip() is None and set(z.namelist())==set(now)
  for n in z.namelist():assert z.read(n)==(R/n).read_bytes()
 sha=hashlib.sha256(dst.read_bytes()).hexdigest();Path(str(dst)+'.sha256').write_text(sha+'  '+dst.name+'\n');print(dst,'bytes',dst.stat().st_size,'SHA256',sha)
