#!/usr/bin/env python3
"""Strict immutable088 scope/preservation and source wiring audit, not visual/game acceptance."""
from pathlib import Path
import zipfile,hashlib,json,sys
R=Path(__file__).resolve().parents[2]
P=Path(sys.argv[1]) if len(sys.argv)>1 else R.parent/'Pojavbouncenew-Nextgen-088-CS2-MVP-BANNER-CANDIDATE.zip'
assert hashlib.sha256(P.read_bytes()).hexdigest()=='97d930419c4b1cdb0c5278dba0f58cc14a012299443bd58e2dd6e6ea4454115c'
with zipfile.ZipFile(P) as z:old={n:z.read(n) for n in z.namelist()}
k='src/main/kotlin/net/ccbluex/liquidbounce/'
modified={
 'src/main/java/net/ccbluex/liquidbounce/render/gui/element/ClickGuiGlassGuiElementRenderState.java',
 k+'features/module/modules/misc/music/onyx/render/UI.kt',
 k+'features/module/modules/render/moderngui/ModernGuiInterceptor.kt',
 k+'features/module/modules/render/moderngui/ModernGuiSurface.kt',
 k+'features/module/modules/render/targethud/TargetHud.kt',
 k+'ui/clickgui/glass/ClickGuiGlassSurface.kt',k+'ui/clickgui/glass/LightPollutionGlassSurface.kt',
 k+'ui/hud/HudComponentManager.kt',
 'src/main/resources/resources/liquidbounce/shaders/clickgui/light_pollution_glass.fsh'}
assert all((R/n).is_file() for n in old)
actual={n for n,b in old.items() if (R/n).read_bytes()!=b};assert actual==modified,actual^modified
# Everything else, including MVP files/locales/registration, both087 aura fixes,
# knives, native cursor/Mixins/backdrop/pipelines and historical test evidence must be identical.
assert len(old)==2700
for n in old.keys()-modified:assert (R/n).read_bytes()==old[n],n
assert not (R/(k+'features/module/modules/combat/ModuleMoonlightKillAura.kt')).exists()
assert not (R/(k+'ui/clickgui/neumorph/ClickGuiNeumorphSurface.kt')).exists()
java=(R/'src/main/java/net/ccbluex/liquidbounce/render/gui/element/ClickGuiGlassGuiElementRenderState.java').read_text()
assert 'lightPollutionGlass ? Math.clamp(referenceProfile, 0, 3) : 0' in java
assert 'profile == 1 || profile == 2' in java and 'lane + 192.0F' in java
surf=(R/(k+'ui/clickgui/glass/LightPollutionGlassSurface.kt')).read_text()
assert 'val pad = max(shadowPad, arcPad)' in surf and 'if (setup != null)' in surf
assert 'true, profile.lane, setup' in surf and 'fallbackTint' in surf
for guard in ['!x.isFinite()','!y.isFinite()','!w.isFinite()','!h.isFinite()','!opacity.isFinite()']:assert guard in surf
mgr=(R/(k+'ui/hud/HudComponentManager.kt')).read_text()
for name,mode in [('TargetHudComponent','TARGET'),('BlockCounterHudComponent','COUNTER')]:assert f'is {name} -> LightPollutionGlassSurface.Profile.{mode}' in mgr
assert 'else -> LightPollutionGlassSurface.Profile.SOFT' in mgr
assert 'withClientHudScope(profile)' in mgr
hook=(R/(k+'features/module/modules/render/moderngui/ModernGuiInterceptor.kt')).read_text()
assert 'referenceProfile = clientHudProfile' in hook
assert 'val prevProfile = clientHudProfile' in hook and 'clientHudProfile = prevProfile' in hook
for n in ['en_us','zh_cn','zh_cn_ui']:json.loads((R/f'src/main/resources/resources/liquidbounce/lang/{n}.json').read_text())
spec=json.loads((R/'licenses/glass089/reference-spec.json').read_text());assert len(spec['source_images'])==6
shader=(R/'src/main/resources/resources/liquidbounce/shaders/clickgui/light_pollution_glass.fsh').read_text()
assert '192.0' not in shader # arc threshold deliberately 191.5, body/rim ranges separate
assert 'mode >= 191.5' in shader and 'if (vMode < -0.001)' in shader
assert 'float strength = clamp(base.r * 2.0' in shader
print('PASS: immutable088 baseline SHA256; exactly9 scoped production modifications /2691 baseline files byte-identical /0 deleted')
print('PASS: MVP+locales+module registration,087 aura fixes, native-only cursor/Mixins, knives, old shaders/backdrop/pipelines and historical evidence preserved byte-for-byte')
print('PASS: explicit per-component reference profiles, padded arc bounds, nested-scope restoration, non-optical fallback, finite emitter guards and6-image source lock')
print('NOT a full Gradle build, game/Android rendering or whole-image pixel acceptance')
