#!/usr/bin/env python3
"""Strict immutable089 source scope + full source package; no inventory or unrelated edits."""
from pathlib import Path,PurePosixPath
import sys,zipfile,hashlib,json
R=Path(__file__).resolve().parents[2];baseline=Path(sys.argv[1]);output=Path(sys.argv[2]);script=R/'scripts/verify090'
assert hashlib.sha256(baseline.read_bytes()).hexdigest()=='7944467014ce6c1086e42c62f48047032e7adde86a9fa36d485276668ea85686'
with zipfile.ZipFile(baseline) as z:old={n:z.read(n) for n in z.namelist()}
k='src/main/kotlin/net/ccbluex/liquidbounce/'
expected={k+'features/module/modules/misc/music/onyx/render/UI.kt',k+'ui/clickgui/ClickGuiTabView.kt',k+'ui/clickgui/GlobalSettingsTabView.kt',k+'ui/clickgui/glass/ClickGuiGlassSurface.kt',k+'ui/clickgui/glass/LightPollutionGlassSurface.kt','src/main/resources/resources/liquidbounce/shaders/clickgui/light_pollution_glass.fsh'}
assert len(old)==2726 and all((R/n).is_file() for n in old)
modified={n for n,v in old.items() if(R/n).read_bytes()!=v};assert modified==expected,modified^expected
surface=(R/(k+'ui/clickgui/glass/LightPollutionGlassSurface.kt')).read_text()
for text in ['if (panel) 3f else Float.MAX_VALUE','val strength = if (panel) 0f','val lens = if (panel) 0f','val rim = if (panel) 0','if (panel) 28 else 255','if (panel) 6f else 40f']:
 assert text in surface,text
for name in ['ClickGuiTabView','GlobalSettingsTabView']:
 s=(R/(k+f'ui/clickgui/{name}.kt')).read_text();assert 'if (!ModuleClickGui.isLightPollutionGlass) {' in s
ui=(R/(k+'features/module/modules/misc/music/onyx/render/UI.kt')).read_text();assert 'if (hudCard) referenceProfile' in ui and 'LightPollutionGlassSurface.Profile.PANEL' in ui
shader_report=json.loads((script/'shader-report.json').read_text());assert shader_report['assertions']==95 and shader_report['hud_max_rgba_difference_vs089']==0
additions={'090-QUIET-GUI-GLASS.md','090-QUIET-GUI-REVIEW.html','scripts/verify090/scope-audit.json'}
additions.update(p.relative_to(R).as_posix() for p in script.rglob('*') if p.is_file() and '__pycache__' not in p.parts)
assert not(set(old)&additions)
actual={p.relative_to(R).as_posix() for p in R.rglob('*') if p.is_file() and '__pycache__' not in p.parts}
assert actual<=set(old)|additions,actual-set(old)-additions
names=sorted(set(old)|additions)
report={'baseline_sha256':hashlib.sha256(baseline.read_bytes()).hexdigest(),'baseline_files':len(old),'modified_count':len(modified),'modified_files':sorted(modified),'identical_baseline_files':len(old)-len(modified),'deleted_count':0,'added_count':len(additions),'added_files':sorted(additions),'total_files':len(names),
 'scope':'Quiet GUI/PANEL material only: no highlights/lens/rim/arc/glaze, radius<=3GUI units, one capped black shadow, existing Gaussian framebuffer sampling inside silhouette. Music GUI hudCard=false routes PANEL; true HUD profiles retained.',
 'checks':{'compile':'2entireJavaRecords+3entireKotlinSurfaceEmitters against actual MC26.3; exact Onyx/scope/upgrade/glaze excerpts with explicit project hosts, not full GUI files','vertex_assertions':177024,'scope_assertions':27006,'shader_assertions':95,'hud_samples_vs089':84,'hud_max_rgba_difference':0,'blur':'Existing actualH/V Gaussian fragments executed4passes at half resolution using fullscreen harness; shared production blur files byte-identical'},
 'preservation':'All other baseline bytes preserved, including inventory/Neo module, MVP,087 fixes, native cursor/Mixins, knives, HUD components and historical evidence. NeoInvManager revert NOT fixed; awaiting device log.',
 'limitations':'No full Gradle/Mixin/Minecraft/Android/game-framebuffer/screenshot acceptance. No-FBO fallback remains non-optical.',
 'archive_validation':'Flat root, excluded build/cache/dependency dirs, allCRC/allbytes verified, unmodified baseline entries rechecked after packing'}
(script/'scope-audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for n in names:
  path=PurePosixPath(n);assert not path.is_absolute() and '..' not in path.parts
  assert not any(x in path.parts for x in ['.gradle','build','.cache','node_modules','__pycache__','.git'])
  info=zipfile.ZipInfo(n,(2026,11,22,12,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;z.writestr(info,(R/n).read_bytes())
with zipfile.ZipFile(output) as z:
 assert z.testzip() is None and sorted(z.namelist())==names
 for n in names:assert z.read(n)==(R/n).read_bytes(),n
 for n in set(old)-expected:assert z.read(n)==old[n],n
b=output.read_bytes();sha=hashlib.sha256(b).hexdigest()
meta={'file':str(output),'status':'090 quietGUI glass SOURCE; not a compiled JAR / not game-verified / NeoInvManager revert not fixed','files':len(names),'size':len(b),'sha256':sha,'md5':hashlib.md5(b).hexdigest(),'dropbox_content_hash':hashlib.sha256(b''.join(hashlib.sha256(b[i:i+4194304]).digest() for i in range(0,len(b),4194304))).hexdigest(),'baseline':'089','modified_count':len(modified),'identical_baseline_files':len(old)-len(modified),'added_count':len(additions),'deleted_count':0,'zip_integrity':'PASS','flat_root':True,'verification':report['checks'],'limitations':report['limitations'],'cloud_delivery':{'dropbox':'NOT ATTEMPTED','google_drive':'NOT ATTEMPTED'}}
Path('/home/user/090-delivery.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n');output.with_suffix(output.suffix+'.sha256').write_text(sha+'  '+output.name+'\n')
print(json.dumps(meta,ensure_ascii=False,indent=2))
