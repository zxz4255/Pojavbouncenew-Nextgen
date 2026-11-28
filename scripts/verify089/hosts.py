#!/usr/bin/env python3
"""Additional explicit project hosts; exact new HUD scope/interceptor methods and routing excerpt.
Complements verify086/hosts.py. Does NOT compile whole HUD manager/interceptor/TargetHud modules.
"""
from pathlib import Path
import sys
W=Path(sys.argv[1]);ROOT=Path(sys.argv[2]);K=ROOT/'src/main/kotlin/net/ccbluex/liquidbounce'
def block(src,needle):
 a=src.index(needle);start=src.index('{',a);level=1;i=start+1
 while level:
  if src[i]=='{':level+=1
  elif src[i]=='}':level-=1
  i+=1
 return src[a:i]
s=(K/'features/module/modules/render/moderngui/ModernGuiInterceptor.kt').read_text()
(W/'ScopeHook.kt').write_text('''package net.ccbluex.liquidbounce.features.module.modules.render.moderngui
import net.ccbluex.liquidbounce.ui.clickgui.glass.LightPollutionGlassSurface
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassSurface
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Material
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import kotlin.math.max
import kotlin.math.min
object ModernGuiInterceptor {
 private var inClientHudScope: Boolean = false
 private var clientHudProfile = LightPollutionGlassSurface.Profile.SOFT
 private val reentrancyGuard = ThreadLocal.withInitial { false }
 private val frameGlassSetup:TextureSetup?=null
 private fun ensureTheme():ResolvedGuiTheme = error("Explicit project-only theme host; not runtime tested")
 fun observedScope()=inClientHudScope
 fun observedProfile()=clientHudProfile
'''+block(s,'    fun <T> withClientHudScope(')+'\n'+block(s,'    fun tryUpgradeClientHudBox(')+'\n}\n')
p=W/'ModernModule.kt';s=p.read_text().replace('val running=true','var running=true').replace('val clientHudCards=true','var clientHudCards=true').replace('object CardDesign {','object CardDesign { val hudCardRadius=12f;');p.write_text(s)
s=(K/'ui/hud/HudComponentManager.kt').read_text();selection=block(s,'                val profile = when (comp)')
# Compile exact new selection + nested-scope call. Component services are explicit project hosts.
a=s.index('                val measured = ModernGuiInterceptor.withClientHudScope(profile)');b=s.index('\n                comp.lastWidth',a)
render=s[a:b]
(W/'RoutingHook.kt').write_text('''package verify089
import net.ccbluex.liquidbounce.ui.clickgui.glass.LightPollutionGlassSurface
import net.ccbluex.liquidbounce.features.module.modules.render.moderngui.ModernGuiInterceptor
import net.minecraft.client.gui.GuiGraphicsExtractor
open class HudComponent { fun renderHud(ctx:GuiGraphicsExtractor,tickDelta:Float,dt:Float,hudW:Float,hudH:Float,inEditor:Boolean)=1 }
class TargetHudComponent:HudComponent()
class BlockCounterHudComponent:HudComponent()
fun select(comp:HudComponent):LightPollutionGlassSurface.Profile {
'''+selection+'\nreturn profile\n}\n'+'''fun compileRenderCall(comp:HudComponent,ctx:GuiGraphicsExtractor,tickDelta:Float,dt:Float,hudW:Float,hudH:Float,inEditor:Boolean):Int {
'''+selection+'\n'+render+'\nreturn measured\n}\n')
s=(K/'features/module/modules/render/targethud/TargetHud.kt').read_text();a=s.index('ui.lightPollutionCard(');i=a+len('ui.lightPollutionCard(');level=1
while level:
 if s[i]=='(':level+=1
 elif s[i]==')':level-=1
 i+=1
call=s[a:i]
(W/'TargetCall.kt').write_text('''package verify089
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.UI
fun compileTargetCall(ui:UI,width:Float,HEIGHT:Float,RADIUS:Float):Boolean = '''+call+'\n')
print('089 exact production HUD scope, full HUD-upgrade method, selection/render-call excerpt and TargetHud call extracted; enclosing services remain explicit hosts')
