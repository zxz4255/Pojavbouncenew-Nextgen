from pathlib import Path
import hashlib,json,sys,zipfile
R=Path(__file__).resolve().parents[2];baseline=Path(sys.argv[1]);digest='5f4bd421ea3a9688b67ea348ec265401ab36565cfad535d74aa7c55c90527e14'
assert hashlib.sha256(baseline.read_bytes()).hexdigest()==digest
with zipfile.ZipFile(baseline) as z:before={n:z.read(n) for n in z.namelist() if not n.endswith('/')}
K='src/main/kotlin/net/ccbluex/liquidbounce/';M=K+'features/module/modules/misc/music/';N=K+'features/module/modules/player/neoraven/'
allowed={M+n for n in ['ModuleMusic.kt','ScreenLyrics.kt','worldlyrics/WorldLyrics.kt']}|{N+n for n in ['ModuleNeoInvManager.kt','NeoInvHotkeys.kt']}|{K+n for n in ['features/module/modules/render/ModuleOpaiDymisisland.kt','render/ClientRenderPipelines.kt','render/ClientShaders.kt']}
newprod={M+'LyricLayout.kt','src/main/resources/resources/liquidbounce/shaders/gui/opai_island_shadow.fsh'}
p=R/'scripts/verify095/scope-audit.json'
if not p.exists():p.write_text('{}\n')
now={str(p.relative_to(R)):p.read_bytes() for p in R.rglob('*') if p.is_file()}
modified=sorted(n for n in before.keys()&now.keys() if before[n]!=now[n]);added=sorted(now.keys()-before.keys());deleted=sorted(before.keys()-now.keys())
assert set(modified)==allowed,modified;assert not deleted,deleted
assert all(n in newprod or n=='095-SHADOW-LYRICS-TAP-TOGGLE.md' or n.startswith('scripts/verify095/') for n in added),added
# Main panel / Music HUD / all prior unrelated fixes must still match the delivered 094 bytes.
for n in ['MusicScreen.kt','MusicPanelLayout.kt','LyricPanel.kt','MusicIsland.kt','onyx/hud/Island.kt','onyx/render/UI.kt','MusicManager.kt','KugouPlayback.kt']:
 assert now[M+n]==before[M+n],n
for n in ['utils/input/InputBind.kt','features/module/ClientModule.kt']:
 assert now[K+n]==before[K+n],n
# Existing pipelines and shader registrations are untouched: only an additive dedicated lane.
s=now[K+'render/ClientRenderPipelines.kt'].decode();a=s.index('    /** Opai-only exterior shadow');b=s.index('    /**\n     * Music panel shadow.',a)
assert s[:a]+s[b:]==before[K+'render/ClientRenderPipelines.kt'].decode()
s=now[K+'render/ClientShaders.kt'].decode();line='        val OpaiIslandShadow = "opai_island_shadow"("shaders/gui/opai_island_shadow.fsh")\n'
assert s.replace(line,'')==before[K+'render/ClientShaders.kt'].decode()
# Opai content, icons and motion remain identical outside old-shadow helper removal / new emitter.
def strip_shadow(s):
 a=s.index('    override fun shadow(');b=s.index('    override fun clip(',a);s=s[:a]+s[b:]
 s=s.replace('    const val SHADOW_EXTENT = 8f\n    const val SHADOW_STEP = .25f\n','')
 if '    fun shadowCoverage(' in s:
  a=s.index('    fun shadowCoverage(');b=s.index('    private fun mix(',a);s=s[:a]+s[b:]
 return s
opai=K+'features/module/modules/render/ModuleOpaiDymisisland.kt'
assert strip_shadow(now[opai].decode())==strip_shadow(before[opai].decode())
world=now[M+'worldlyrics/WorldLyrics.kt'].decode();module=now[M+'ModuleMusic.kt'].decode()
assert all(t not in world for t in ['options.glow','WorldLyricsState','val offsets =','BlendFunction.ADDITIVE'])
assert 'WorldLyricsGroup.glow' not in module and 'GlowStrength' not in module
assert 'rotate(rotation)' in world and 'forward.x()*target.depth' in world and 'scene.frame(' in world
assert 'pose.last().pose().translate(dx,dy,dz)' in world
inv=now[N+'ModuleNeoInvManager.kt'].decode()
for token in ['TapToggleMigration095','if (tapToggleMigrated.get()) return','old.copy(action =','BindAction.HOLD','BindAction.TOGGLE','ConfigSystem.store']:
 assert token in inv,token
assert 'override val externalBind: Boolean get() = true' in inv
assert now[N+'NeoInvBindLatch.kt']==before[N+'NeoInvBindLatch.kt']
result={'baseline_sha256':digest,'modified':modified,'added':added,'deleted':deleted,'unchanged_entries':len(before)-len(modified),'scope':'PASS: 095 explicit Opai shadow, independent 2D/3D lyric overlays, approved Neo HOLD→TOGGLE migration only; 094 main-panel/HUD and unrelated baseline bytes preserved.'}
p.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print('PASS: strict 094→095 audit:',len(modified),'modified,',len(added),'added,',len(deleted),'deleted,',result['unchanged_entries'],'unchanged')
if len(sys.argv)>2:
 dest=Path(sys.argv[2])
 with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
  for p in sorted(p for p in R.rglob('*') if p.is_file()):z.write(p,p.relative_to(R))
 with zipfile.ZipFile(dest) as z:
  assert z.testzip() is None;assert set(z.namelist())==set(now)
  for n in z.namelist():assert z.read(n)==(R/n).read_bytes(),n
 sha=hashlib.sha256(dest.read_bytes()).hexdigest();Path(str(dest)+'.sha256').write_text(sha+'  '+dest.name+'\n')
 print('ZIP',dest,'entries',len(now),'bytes',dest.stat().st_size,'SHA256',sha)
