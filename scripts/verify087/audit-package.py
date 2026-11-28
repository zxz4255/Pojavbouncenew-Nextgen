#!/usr/bin/env python3
"""Complete086 plus exactly two event-field renames and explicit087 evidence. No build outputs."""
from pathlib import Path,PurePosixPath
import sys,zipfile,hashlib,json
root=Path(__file__).resolve().parents[2]
baseline=Path(sys.argv[1]);output=Path(sys.argv[2])
assert hashlib.sha256(baseline.read_bytes()).hexdigest()=='d0e16fdba47a49976a79f9c17ed98cb6b4eb9d5fd7084a9864ab78bacb5c85fc'
expected={f'src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/neoraven/{n}.kt' for n in ['ModuleNeoKillAura','ModuleNeoKillAura2']}
with zipfile.ZipFile(baseline) as z:original={n:z.read(n) for n in z.namelist() if not n.endswith('/')}
assert len(original)==2679
assert all((root/n).is_file() for n in original)
modified={n for n,v in original.items() if(root/n).read_bytes()!=v};assert modified==expected,modified
for n in expected:
 old=original[n];assert old.count(b'private val world')==1
 assert (root/n).read_bytes()==old.replace(b'private val world',b'private val worldChangeHandler'),n
additions={'087-NEOAURA-WORLD-HANDLER-FIX.md','scripts/verify087/scope-audit.json'}
additions.update(p.relative_to(root).as_posix() for p in(root/'scripts/verify087').rglob('*') if p.is_file() and '__pycache__' not in p.parts)
assert not(additions&set(original))
actualsrc={p.relative_to(root).as_posix() for p in(root/'src').rglob('*') if p.is_file()};assert actualsrc<=set(original)
names=sorted(set(original)|additions)
report={
 'baseline_sha256':hashlib.sha256(baseline.read_bytes()).hexdigest(),'baseline_files':len(original),
 'modified_files':sorted(modified),'modified_count':len(modified),
 'scope':'Only two production field names world -> worldChangeHandler; all callbacks/logic and every other baseline file byte-identical.',
 'identical_baseline_files':len(original)-len(modified),'deleted_count':0,
 'added_files':sorted(additions),'added_count':len(additions),'total_files':len(names),
 'negative_control':'Immutable086 reproduces exactly both reported inherited-world errors with actual MinecraftShortcuts included.',
 'positive_control':'Fixed six production NeoAura files compile with actual MinecraftShortcuts and AddonApi; 104settings/368API/67460assertions pass. Explicit project hosts remain; not fullGradle/game/Android.',
 'archive_validation':'Flat root, all bytes compared, all untouched baseline entries checked and ZIP CRC validated by this script.'
}
(root/'scripts/verify087/scope-audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for n in names:
  p=PurePosixPath(n)
  assert not p.is_absolute() and '..' not in p.parts
  assert not any(x in p.parts for x in ['build','.gradle','__pycache__','node_modules','.cache'])
  info=zipfile.ZipInfo(n,(2026,11,22,12,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
  z.writestr(info,(root/n).read_bytes())
with zipfile.ZipFile(output) as z:
 assert z.testzip() is None and sorted(z.namelist())==names
 for n in names:assert z.read(n)==(root/n).read_bytes(),n
 for n in set(original)-expected:assert z.read(n)==original[n],n
b=output.read_bytes();sha=hashlib.sha256(b).hexdigest()
meta={
 'file':str(output),'status':'087 two inherited-world compiler errors fixed; source package, not a built JAR',
 'files':len(names),'size':len(b),'sha256':sha,'md5':hashlib.md5(b).hexdigest(),
 'dropbox_content_hash':hashlib.sha256(b''.join(hashlib.sha256(b[i:i+4194304]).digest() for i in range(0,len(b),4194304))).hexdigest(),
 'modified_count':2,'identical_baseline_files':len(original)-2,'deleted_count':0,'added_count':len(additions),
 'zip_integrity':'PASS','flat_root':True,
 'tests':'Original086 exactly reproduces 2 errors; fixed6NeoAura+actualMinecraftShortcuts+actualAddonApi isolated compilePASS;104settings/368API/67460logicassertionsPASS. Not fullGradle/Mixin/game/Android acceptance.',
 'cloud_delivery':{'dropbox':'NOT ATTEMPTED','google_drive':'NOT ATTEMPTED'},
}
output.with_suffix(output.suffix+'.sha256').write_text(sha+'  '+output.name+'\n')
Path('/home/user/087-delivery.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(meta,ensure_ascii=False,indent=2))
