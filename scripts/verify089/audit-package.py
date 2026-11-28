#!/usr/bin/env python3
"""Package full immutable088 baseline + strict glass089 scope; no unrelated source/file replacement."""
from pathlib import Path,PurePosixPath
import subprocess,sys,zipfile,hashlib,json
R=Path(__file__).resolve().parents[2];baseline=Path(sys.argv[1]);output=Path(sys.argv[2]);script=R/'scripts/verify089'
subprocess.run([sys.executable,str(script/'check-wiring.py'),str(baseline)],check=True)
with zipfile.ZipFile(baseline) as z:old={n:z.read(n) for n in z.namelist()}
modified=sorted(n for n,b in old.items() if (R/n).read_bytes()!=b)
additions={'089-GLASS-REFERENCE-REBUILD.md','089-GLASS-REFERENCE-REVIEW.html','scripts/verify089/scope-audit.json'}
for prefix in ['scripts/verify089','licenses/glass089']:
 additions.update(p.relative_to(R).as_posix() for p in (R/prefix).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
assert not (set(old)&additions)
actual={p.relative_to(R).as_posix() for p in R.rglob('*') if p.is_file() and '__pycache__' not in p.parts}
assert actual<=set(old)|additions,actual-set(old)-additions
names=sorted(set(old)|additions)
report={'baseline_sha256':hashlib.sha256(baseline.read_bytes()).hexdigest(),'baseline_files':len(old),
 'modified_count':len(modified),'modified_files':modified,'identical_baseline_files':len(old)-len(modified),
 'added_count':len(additions),'added_files':sorted(additions),'deleted_count':0,'total_files':len(names),
 'scope':'Reference-traced four-profile glass material and bounded transport/routing changes only. All other baseline bytes preserved; no new cursor layer; Moonlight/Neumorphism remain removed.',
 'verification':{'actual_api_isolated_compile':'2 full Java records +3 full Kotlin surface emitters; exact Onyx hook/scope/HUD-upgrade methods +HUD-manager/TargetHud call excerpts. Enclosing project services explicit hosts; not full modules.',
 'production_vertex_assertions':177024,'scope_excerpt_assertions':27006,'offscreen_glsl_assertions':2625,
 'mvp_purelogic_assertions':51039,'mvp_actual_api_refs':58,'source_trace_points':13,'source_trace_max_error_original_jpeg_pixels':2,
 'trace_limit':'Fitted points remeasured, not independent/whole-image RGB or game screenshot parity.'},
 'limitations':'No full Gradle/Mixin/game/Android/same-scene pixel acceptance. No original reference-client source/shader obtained. Optical fallback unavailable without framebuffer.',
 'archive_validation':'Flat root; CRC/every stored byte compared to current files; every unmodified baseline entry rechecked; no build/cache/dependency artifacts.'}
(script/'scope-audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for n in names:
  p=PurePosixPath(n);assert not p.is_absolute() and '..' not in p.parts
  assert not any(v in p.parts for v in ['.gradle','build','.cache','node_modules','__pycache__','.git'])
  info=zipfile.ZipInfo(n,(2026,11,22,12,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
  z.writestr(info,(R/n).read_bytes())
with zipfile.ZipFile(output) as z:
 assert z.testzip() is None and sorted(z.namelist())==names
 for n in names:assert z.read(n)==(R/n).read_bytes(),n
 for n in set(old)-set(modified):assert z.read(n)==old[n],n
b=output.read_bytes();sha=hashlib.sha256(b).hexdigest()
meta={'file':str(output),'status':'089 reference-traced glass SOURCE CANDIDATE; not game-pixel-verified, not a compiled mod JAR',
 'files':len(names),'size':len(b),'sha256':sha,'md5':hashlib.md5(b).hexdigest(),
 'dropbox_content_hash':hashlib.sha256(b''.join(hashlib.sha256(b[i:i+4194304]).digest() for i in range(0,len(b),4194304))).hexdigest(),
 'baseline':'088','modified_count':len(modified),'identical_baseline_files':len(old)-len(modified),'added_count':len(additions),'deleted_count':0,
 'zip_integrity':'PASS','flat_root':True,'verification':report['verification'],'limitations':report['limitations'],
 'review_html':str(R/'089-GLASS-REFERENCE-REVIEW.html'),
 'cloud_delivery':{'dropbox':'NOT ATTEMPTED','google_drive':'NOT ATTEMPTED'}}
output.with_suffix(output.suffix+'.sha256').write_text(sha+'  '+output.name+'\n')
Path('/home/user/089-delivery.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(meta,ensure_ascii=False,indent=2))
