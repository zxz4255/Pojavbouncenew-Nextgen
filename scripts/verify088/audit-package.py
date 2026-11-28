#!/usr/bin/env python3
"""Full087 + standalone088 feature, with explicit source/registration/localisation allowlists."""
from pathlib import Path,PurePosixPath
import sys,zipfile,json,hashlib
root=Path(__file__).resolve().parents[2];baseline=Path(sys.argv[1]);output=Path(sys.argv[2])
assert hashlib.sha256(baseline.read_bytes()).hexdigest()=='209dd3861e35dbaf73bb248c462223821712149b082ba816382707e6cc47f7cb'
with zipfile.ZipFile(baseline) as z:original={n:z.read(n) for n in z.namelist() if not n.endswith('/')}
assert len(original)==2686
expected={'src/main/kotlin/net/ccbluex/liquidbounce/features/module/ModuleManager.kt',
 *[f'src/main/resources/resources/liquidbounce/lang/{l}.json' for l in ['en_us','zh_cn','zh_cn_ui']]}
assert all((root/n).is_file() for n in original)
modified={n for n,v in original.items() if(root/n).read_bytes()!=v};assert modified==expected,modified
n='src/main/kotlin/net/ccbluex/liquidbounce/features/module/ModuleManager.kt'
old=original[n].decode();new=(root/n).read_text()
needle='import net.ccbluex.liquidbounce.features.module.modules.render.fpsknife.ModuleFpsKnife'
assert new==old.replace(needle,needle+'\nimport net.ccbluex.liquidbounce.features.module.modules.render.cs2mvp.ModuleCs2Mvp').replace('            ModuleFpsKnife,','            ModuleFpsKnife,\n            ModuleCs2Mvp,')
for lang in ['en_us','zh_cn']:
 n=f'src/main/resources/resources/liquidbounce/lang/{lang}.json';old=json.loads(original[n]);new=json.loads((root/n).read_text())
 assert set(new)-set(old)=={'liquidbounce.module.cS2MVP.description'}
 new.pop('liquidbounce.module.cS2MVP.description');assert old==new
n='src/main/resources/resources/liquidbounce/lang/zh_cn_ui.json';old=json.loads(original[n]);new=json.loads((root/n).read_text())
assert new['modules'].pop('CS2MVP')=='CS2 MVP横幅'
for key in ['Attribution','DamageSource','RecentHit','EvidenceWindow','NameFont','ReasonFont','VerticalPosition']:
 assert key not in old['terms'];new['terms'].pop(key)
assert new==old
additions={'088-CS2-MVP-BANNER.md','scripts/verify088/scope-audit.json'}
for prefix in ['scripts/verify088','licenses/cs2mvp088','src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/cs2mvp']:
 additions.update(p.relative_to(root).as_posix() for p in(root/prefix).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
assert not(set(original)&additions)
actualsrc={p.relative_to(root).as_posix() for p in(root/'src').rglob('*') if p.is_file()}
assert actualsrc<=set(original)|additions,actualsrc-set(original)-additions
names=sorted(set(original)|additions)
report={
 'baseline_sha256':hashlib.sha256(baseline.read_bytes()).hexdigest(),'baseline_files':len(original),
 'modified_files':sorted(modified),'modified_count':len(modified),'identical_baseline_files':len(original)-len(modified),
 'added_files':sorted(additions),'added_count':len(additions),'deleted_count':0,'total_files':len(names),
 'scope':'One registration/import extension and three language extensions; three standalone MVP sources. Every other baseline byte preserved, including087 collision fix,086 glass/native cursor and unrelated modules.',
 'checks':'ActualMC26.3/realMinecraftShortcuts/full3MVPfiles isolated compile; 58APIrefs;51039purelogicassertions;9settings/wiring/JSON/spec checks.',
 'limitations':'No full Gradle/game/Android/server/screenshot/animation parity acceptance; no proprietary fonts/music/mask packaged; legacy attribution is a disclosed heuristic.',
 'archive_validation':'Flat root, no generated directories, CRC and every archive byte compared; preserved baseline entries rechecked after packing.'
}
(root/'scripts/verify088/scope-audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for n in names:
  p=PurePosixPath(n);assert not p.is_absolute() and '..' not in p.parts
  assert not any(x in p.parts for x in ['.gradle','build','.cache','node_modules','__pycache__'])
  info=zipfile.ZipInfo(n,(2026,11,22,12,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
  z.writestr(info,(root/n).read_bytes())
with zipfile.ZipFile(output) as z:
 assert z.testzip() is None and sorted(z.namelist())==names
 for n in names:assert z.read(n)==(root/n).read_bytes(),n
 for n in set(original)-expected:assert z.read(n)==original[n],n
b=output.read_bytes();sha=hashlib.sha256(b).hexdigest()
meta={'file':str(output),'status':'088 CS2-measured kill-triggered MVP banner source candidate; not100percent fidelity/live-game certified',
 'files':len(names),'size':len(b),'sha256':sha,'md5':hashlib.md5(b).hexdigest(),
 'dropbox_content_hash':hashlib.sha256(b''.join(hashlib.sha256(b[i:i+4194304]).digest() for i in range(0,len(b),4194304))).hexdigest(),
 'modified_count':len(modified),'identical_baseline_files':len(original)-len(modified),'added_count':len(additions),'deleted_count':0,
 'zip_integrity':'PASS','flat_root':True,
 'tests':'3completeMVPfiles+realMinecraftShortcuts/AddonApi actualMC26.3/VFPbridge isolated compilePASS;58actualAPIrefs;51039productionlogicassertions;9settings/wiring/JSON/specPASS. Not fullGradle/game/Android/visual parity.',
 'cloud_delivery':{'dropbox':'NOT ATTEMPTED','google_drive':'NOT ATTEMPTED'}}
output.with_suffix(output.suffix+'.sha256').write_text(sha+'  '+output.name+'\n')
Path('/home/user/088-delivery.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(meta,ensure_ascii=False,indent=2))
