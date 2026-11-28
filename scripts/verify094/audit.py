#!/usr/bin/env python3
"""Strict 093 -> 094 source scope and byte-preservation audit; optional verified ZIP."""
from pathlib import Path
import difflib,hashlib,json,sys,zipfile
R=Path(__file__).resolve().parents[2]
base=Path(sys.argv[1]);expected='3fac50d5c2e04d09e33a6bcf9ebaa79b622984586800ba6bad5f1c49a27d8b6c'
assert hashlib.sha256(base.read_bytes()).hexdigest()==expected
with zipfile.ZipFile(base) as z: before={n:z.read(n) for n in z.namelist() if not n.endswith('/')}
M='src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/misc/music/'
report=R/'scripts/verify094/scope-audit.json'
if not report.exists():report.write_text('{}\n')
now={str(p.relative_to(R)):p.read_bytes() for p in R.rglob('*') if p.is_file()}
modified=sorted(n for n in before.keys()&now.keys() if before[n]!=now[n]);added=sorted(now.keys()-before.keys());deleted=sorted(before.keys()-now.keys())
assert not deleted,deleted
assert set(modified)=={M+n for n in ['MusicScreen.kt','ModuleMusic.kt','LyricPanel.kt','onyx/render/UI.kt']},modified
assert all(n==M+'MusicPanelLayout.kt' or n=='094-MUSIC-QUIET-PANEL.md' or n.startswith('scripts/verify094/') for n in added),added
# Dedicated panel painter is the ONLY addition to shared UI: all existing HUD methods byte-identical.
def block(s,needle):
 a=s.index(needle);i=s.index('{',a)+1;d=1
 while d:
  d+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[a:i]
ui=now[M+'onyx/render/UI.kt'].decode();old=before[M+'onyx/render/UI.kt'].decode()
start=ui.index('    /** Dedicated Music main-panel material.')
end=ui.index('    fun rounded(',start)
assert ui[:start]+ui[end:]==old
panel=block(ui,'    fun musicPanelCard(')
assert 'ModuleModernGui' not in panel and 'Profile.PANEL' in panel and 'request(1.35f, 1f)' in panel
assert '0f, 0f, 0x18000000' in panel
# The module change removes only the obsolete main panel enum/settings/accessors.
module=now[M+'ModuleMusic.kt'].decode();oldmodule=before[M+'ModuleMusic.kt'].decode()
a=oldmodule.index('    enum class PanelLayout(');b=oldmodule.index('    private val streamLevelSetting',a)
replacement='    // 094: MusicScreen has one quiet-glass layout. Old PanelLayout/PanelSurface config keys are ignored.\n\n'
assert oldmodule[:a]+replacement+oldmodule[b:]==module
for n in ['MusicIsland.kt','onyx/hud/Island.kt','MusicManager.kt','KugouPlayback.kt','SearchTextBuffer.kt','MusicBackdrop.kt','onyx/material/Theme.kt']:
 assert now[M+n]==before[M+n],n
s=now[M+'MusicScreen.kt'].decode();oldscreen=before[M+'MusicScreen.kt'].decode()
for name in ['focusSearch','charTyped','preeditUpdated','removed','deferClose','interactive','updateMouse','mouseReleased','mouseDragged']:
 needle=('    override fun ' if name in ['charTyped','preeditUpdated','removed','mouseReleased','mouseDragged'] else '    fun ' if name=='deferClose' else '    private fun ')+name+'('
 assert block(s,needle)==block(oldscreen,needle),name
for token in ['ModuleMusic.panelLayout','ModuleMusic.panelSurface','setPanelSurface','setPanelLayout','ModuleMusic.PanelLayout.','ModuleMusic.PanelSurface.']:
 assert token not in s and token not in module,token
for token in ['manager.favoriteSelected(song)','MusicPanelLayout.progressHit','MusicPanelLayout.volumeHit','drawQrOverlay(renderer, current)','if (!qrOpen || qrMotion.value() < .8f)','for (i in hits.indices.reversed())','if (qrOpen || qrMotion.value() > .001f || queueDrag != null)','if (queueDrawerMotion.value() > 0.001f) return true','max(y, contentHitTop)']:
 assert token in s,token
for source in (R/'src/main').rglob('*'):
 if source.suffix in ['.kt','.java'] and source.is_file() and source.name!='ModuleMusic.kt':
  text=source.read_text()
  assert 'ModuleMusic.PanelLayout' not in text and 'ModuleMusic.PanelSurface' not in text,str(source)
result={'baseline_sha256':expected,'modified':modified,'added':added,'deleted':deleted,'unchanged_entries':len(before)-len(modified),'scope':'PASS: only main-panel screen/layout/lyric material, removal of main-panel switches, dedicated additive painter. All other baseline bytes preserved. Not full mod/game validation.'}
report.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print('PASS: strict 093→094 audit; modified',len(modified),'added',len(added),'deleted',len(deleted),'unchanged',result['unchanged_entries'])
if len(sys.argv)>2:
 dest=Path(sys.argv[2])
 with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
  for p in sorted(p for p in R.rglob('*') if p.is_file()):z.write(p,p.relative_to(R))
 with zipfile.ZipFile(dest) as z:
  assert z.testzip() is None;assert set(z.namelist())==set(now)
  for n in z.namelist():assert z.read(n)==(R/n).read_bytes(),n
 data=dest.read_bytes();digest=hashlib.sha256(data).hexdigest()
 Path(str(dest)+'.sha256').write_text(digest+'  '+dest.name+'\n')
 print('ZIP',dest,'entries',len(now),'bytes',len(data),'SHA256',digest)
