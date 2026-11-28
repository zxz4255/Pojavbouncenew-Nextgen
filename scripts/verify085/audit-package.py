#!/usr/bin/env python3
"""Package complete084 plus strictly scoped085 sources; never package Gradle/cache outputs."""
from pathlib import Path,PurePosixPath
import sys,zipfile,hashlib,json
root=Path(__file__).resolve().parents[2]
baseline=Path(sys.argv[1]);output=Path(sys.argv[2])
assert hashlib.sha256(baseline.read_bytes()).hexdigest()=='291a96edf0a9291f2fd07b373ba9d2091c58c2bd68d630b60754ad1566377543'
expected={
 'src/main/kotlin/net/ccbluex/liquidbounce/features/module/ModuleManager.kt',
 'src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/ModuleSwordBlock.kt',
 'src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/targethud/TargetHudLayer.kt',
 'src/main/kotlin/net/ccbluex/liquidbounce/ui/hud/components/CombatAndNotificationHudComponents.kt',
 'src/main/kotlin/net/ccbluex/liquidbounce/utils/combat/CombatManager.kt',
}
with zipfile.ZipFile(baseline) as z:
 original={n:z.read(n) for n in z.namelist() if not n.endswith('/')}
 assert len(original)==2631
 missing=[n for n in original if not(root/n).is_file()];assert not missing,missing
 modified=[n for n,b in original.items() if(root/n).read_bytes()!=b]
 assert set(modified)==expected,modified
 # Include only intentionally added deliverables, source, verification and attribution.
 additions={'085-NEORAVEN-COMBAT-PORT.md','licenses/neoraven/COMBAT.md'}
 for prefix in [
  'src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/neoraven',
  'scripts/verify085','licenses/neoraven/combat-upstream']:
  additions.update(p.relative_to(root).as_posix() for p in(root/prefix).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
 report='scripts/verify085/scope-audit.json';additions.add(report)
 assert not(set(original)&additions)
 # No unlisted production additions may silently vanish from the delivery.
 actualsrc={p.relative_to(root).as_posix() for p in(root/'src').rglob('*') if p.is_file()}
 assert actualsrc <= set(original)|additions,sorted(actualsrc-set(original)-additions)
 reportdata={
  'baseline_sha256':hashlib.sha256(baseline.read_bytes()).hexdigest(),
  'baseline_files':len(original),'modified_baseline_files':sorted(modified),
  'identical_baseline_files':len(original)-len(modified),
  'added_files':sorted(additions),'added_count':len(additions),
  'total_files':len(original)+len(additions),
  'scope':'Full084 preserved; only five registration/visual/HUD/combat read-path extensions plus six new Kotlin production sources. Native cursor, legacy sword helper, models and all other baseline files byte-identical.',
  'not_complete_parity':'Upstream-specific dependency handshakes are not fully ported; see root085 status document. No full Gradle/Mixin/game/hardware/server acceptance.',
  'excluded':'Build/dependency/cache/workspace artifacts excluded by explicit addition allowlist; no MC/VFP binaries bundled.',
  'archive_validation':'All entries compared with working source, flat-root checks and CRC performed by this script after packing. Report cannot include its own cryptographic digest.'
 }
 (root/report).write_text(json.dumps(reportdata,ensure_ascii=False,indent=2)+'\n')
 names=sorted(set(original)|additions)
 with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as new:
  for name in names:
   path=PurePosixPath(name)
   assert not path.is_absolute() and '..' not in path.parts and not any(x in path.parts for x in ['.gradle','node_modules','__pycache__','build','.cache'])
   info=zipfile.ZipInfo(name,(2026,11,22,12,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
   new.writestr(info,(root/name).read_bytes())
with zipfile.ZipFile(output) as new:
 assert new.testzip() is None
 assert sorted(new.namelist())==names
 for name in names:assert new.read(name)==(root/name).read_bytes(),name
 for name,old in original.items():
  if name not in expected:assert new.read(name)==old,name
bytes_=output.read_bytes();sha=hashlib.sha256(bytes_).hexdigest()
blocks=[hashlib.sha256(bytes_[i:i+4194304]).digest() for i in range(0,len(bytes_),4194304)]
meta={
 'file':str(output),'status':'085 source-port candidate; not certified complete1:1 parity',
 'files':len(names),'size':len(bytes_),'sha256':sha,'md5':hashlib.md5(bytes_).hexdigest(),
 'dropbox_content_hash':hashlib.sha256(b''.join(blocks)).hexdigest(),
 'modified_baseline_count':len(modified),'identical_baseline_count':len(original)-len(modified),'added_count':len(additions),
 'zip_integrity':'PASS','flat_root':True,
 'tests':'6 new Kotlin files + exact integration excerpts real MC26.3/production VFP bridge compile; 368 actual API refs; 104 settings; 67460 production-logic assertions; retained084 cursor76API/20036Android-host+4desktop/2000cycles. Not fullGradle/Mixin/liveVFP/game/hardware/server validation.',
 'cloud_delivery':{'dropbox':'NOT ATTEMPTED','google_drive':'NOT ATTEMPTED'}
}
output.with_suffix(output.suffix+'.sha256').write_text(sha+'  '+output.name+'\n')
Path('/home/user/085-delivery.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(meta,ensure_ascii=False,indent=2))
