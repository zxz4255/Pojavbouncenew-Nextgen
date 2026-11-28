#!/usr/bin/env python3
"""Immutable085 -> full086 source candidate; explicit modified/deleted/addition allowlists."""
from pathlib import Path, PurePosixPath
import zipfile, hashlib, json, sys
root=Path(__file__).resolve().parents[2]
baseline=Path(sys.argv[1]);output=Path(sys.argv[2])
assert hashlib.sha256(baseline.read_bytes()).hexdigest()=='308008a699a59b0a61441e46b5834e29db95a6f1bd4e905d5a75bd3c66e89a76'
k='src/main/kotlin/net/ccbluex/liquidbounce/'
modified={k+p for p in [
 'features/module/ModuleManager.kt',
 'features/module/modules/combat/neoraven/NeoAuraSupport.kt',
 'features/module/modules/misc/music/MusicScreen.kt',
 'features/module/modules/misc/music/onyx/hud/Island.kt',
 'features/module/modules/misc/music/onyx/render/UI.kt',
 'features/module/modules/render/ModuleClickGui.kt',
 'features/module/modules/render/moderngui/ModernGuiSurface.kt',
 'features/module/modules/render/moderngui/ModuleModernGui.kt',
 'features/module/modules/render/targethud/TargetHud.kt',
 'render/ClientRenderPipelines.kt','render/ClientShaders.kt',
 'ui/clickgui/ClickGuiTabView.kt','ui/clickgui/TabbedClickGuiScreen.kt',
 'ui/clickgui/glass/ClickGuiGlassSurface.kt','ui/common/UiPalette.kt',
]}
modified.update([
 'src/gametest/kotlin/net/ccbluex/liquidbounce/gametest/HelloScreenGameTest.kt',
 'src/main/java/net/ccbluex/liquidbounce/render/gui/element/ClickGuiGlassGuiElementRenderState.java',
 *['src/main/resources/resources/liquidbounce/lang/'+l+'.json' for l in ['en_us','zh_cn','zh_cn_ui']],
])
deleted={k+'features/module/modules/combat/ModuleMoonlightKillAura.kt'}
with zipfile.ZipFile(baseline) as z:original={n:z.read(n) for n in z.namelist() if not n.endswith('/')}
assert len(original)==2660
actualdeleted={n for n in original if not(root/n).is_file()};assert actualdeleted==deleted,actualdeleted
actualmodified={n for n,v in original.items() if n not in deleted and (root/n).read_bytes()!=v}
assert actualmodified==modified,(actualmodified-modified,modified-actualmodified)
additions={
 '086-LIGHT-POLLUTION-GLASS.md',
 k+'ui/clickgui/glass/LightPollutionGlassSurface.kt',
 'src/main/resources/resources/liquidbounce/shaders/clickgui/light_pollution_glass.fsh',
 'scripts/verify086/scope-audit.json',
}
for prefix in ['scripts/verify086','licenses/glass086']:
 additions.update(p.relative_to(root).as_posix() for p in(root/prefix).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
assert not(additions&set(original))
names=sorted((set(original)-deleted)|additions)
# Production sources may not silently be dropped from the archive.
actualsrc={p.relative_to(root).as_posix() for p in(root/'src').rglob('*') if p.is_file()}
assert actualsrc<=set(names),actualsrc-set(names)
for n in actualsrc:
 if n.endswith(('.kt','.java','.json')):
  assert 'ModuleMoonlightKillAura' not in(root/n).read_text(),n
# Localisation edits are scoped, not a general rewrite or false all-language green check.
for lang in ['en_us','zh_cn']:
 n='src/main/resources/resources/liquidbounce/lang/'+lang+'.json'
 old=json.loads(original[n]);new=json.loads((root/n).read_text())
 old={a:b for a,b in old.items() if not a.startswith('liquidbounce.module.moonlightKillAura.')}
 assert new==old,n
n='src/main/resources/resources/liquidbounce/lang/zh_cn_ui.json'
old=json.loads(original[n]);new=json.loads((root/n).read_text())
old['modules'].pop('MoonlightKillAura');old['terms']['LightPollutionGlass']='光污染玻璃'
assert old==new
# Verify existing material and native cursor bytes remain untouched.
for n in original:
 if n.endswith(('liquid_glass.fsh','liquid_glass.vsh','glass_common.glsl','AndroidGuiCursor.java')):
  assert (root/n).read_bytes()==original[n],n
report={
 'baseline_sha256':hashlib.sha256(baseline.read_bytes()).hexdigest(),
 'baseline_files':len(original),'identical_baseline_files':len(original)-len(modified)-len(deleted),
 'modified_files':sorted(modified),'modified_count':len(modified),
 'deleted_files':sorted(deleted),'deleted_count':len(deleted),
 'added_files':sorted(additions),'added_count':len(additions),'total_files':len(names),
 'preserved':'Every unlisted baseline file byte-identical, including old glass GLSL, native hardware cursor and unrelated module implementations.',
 'status':'Source candidate; no complete Gradle/game/Android/screenshot-parity acceptance.',
 'archive_checks':'Flat root, path allowlist, all working-file bytes and baseline preservation, CRC checked after packing. Self-hash not embedded.',
}
(root/'scripts/verify086/scope-audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for n in names:
  p=PurePosixPath(n)
  assert not p.is_absolute() and '..' not in p.parts
  assert not any(x in p.parts for x in ['.gradle','build','node_modules','.cache','__pycache__'])
  info=zipfile.ZipInfo(n,(2026,11,22,12,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
  z.writestr(info,(root/n).read_bytes())
with zipfile.ZipFile(output) as z:
 assert z.testzip() is None and sorted(z.namelist())==names
 for n in names:assert z.read(n)==(root/n).read_bytes(),n
 for n in set(original)-modified-deleted:assert z.read(n)==original[n],n
b=output.read_bytes();sha=hashlib.sha256(b).hexdigest()
meta={
 'file':str(output),'status':'086 source candidate; not certified screenshot1:1 or live-game acceptance',
 'files':len(names),'size':len(b),'sha256':sha,'md5':hashlib.md5(b).hexdigest(),
 'dropbox_content_hash':hashlib.sha256(b''.join(hashlib.sha256(b[i:i+4194304]).digest() for i in range(0,len(b),4194304))).hexdigest(),
 'identical_baseline_files':report['identical_baseline_files'],'modified_count':len(modified),'deleted_count':len(deleted),'added_count':len(additions),
 'flat_root':True,'zip_integrity':'PASS',
 'tests':'Actual-MC26.3 isolated render compile; 14008 production vertex assertions; old/new production GLSL compile/link +653 offscreen numeric assertions; NeoAura104settings/368API/67460assertions; native cursor regression. Not full Gradle/game/Android/screenshot validation.',
 'cloud_delivery':{'dropbox':'NOT ATTEMPTED','google_drive':'NOT ATTEMPTED'},
}
output.with_suffix(output.suffix+'.sha256').write_text(sha+'  '+output.name+'\n')
Path('/home/user/086-delivery.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(meta,ensure_ascii=False,indent=2))
