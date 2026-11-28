#!/usr/bin/env python3
"""Project-only compile hosts. Minecraft/RenderPearl/JOML are actual manifest jars.
Checks transport signatures, not behavior of these explicitly substituted project services.
"""
import sys,re
from pathlib import Path
W=Path(sys.argv[1]);ROOT=Path(sys.argv[2]);K=ROOT/'src/main/kotlin/net/ccbluex/liquidbounce'
def write(n,s):(W/n).write_text(s)
write('ClientRenderPipelines.java','''package net.ccbluex.liquidbounce.render;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
public class ClientRenderPipelines { public static class GUI {
 public static RenderPipeline cssBox(){return null;}
 public static RenderPipeline clickGuiLiquidGlass(){return null;}
 public static RenderPipeline lightPollutionGlass(){return null;}
}}''')
write('PoseReusableGuiElementRenderState.java','''package net.ccbluex.liquidbounce.render.gui.element;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
public interface PoseReusableGuiElementRenderState extends GuiElementRenderState { Matrix3x2f pose(); }
''')
write('Render.kt','''package net.ccbluex.liquidbounce.render
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import org.joml.Matrix3x2f
fun GuiGraphicsExtractor.copyPosePooled(): Matrix3x2f = Matrix3x2f(pose())
fun GuiGraphicsExtractor.getBounds(x0:Float,y0:Float,x1:Float,y1:Float):ScreenRectangle = ScreenRectangle(x0.toInt(),y0.toInt(),(x1-x0).toInt(),(y1-y0).toInt())
''')
write('Color.kt','''package net.ccbluex.liquidbounce.render.engine.type
class Color4b(val r:Int,val g:Int,val b:Int,val a:Int) { val argb:Int get()=(a shl 24) or (r shl 16) or (g shl 8) or b }
''')
write('Tagged.kt','''package net.ccbluex.liquidbounce.config.types.list
interface Tagged { val tag:String; val tagAliases:List<String> get()=emptyList() }
''')
# Compile exact production enum bodies (not handwritten equivalents).
def block(src,needle):
 a=src.index(needle);start=src.index('{',a);level=1;i=start+1
 while level:
  if src[i]=='{':level+=1
  elif src[i]=='}':level-=1
  i+=1
 return src[a:i]
click=(K/'features/module/modules/render/ModuleClickGui.kt').read_text()
modern=(K/'features/module/modules/render/moderngui/ModuleModernGui.kt').read_text()
write('ClickModule.kt','''package net.ccbluex.liquidbounce.features.module.modules.render
import net.ccbluex.liquidbounce.config.types.list.Tagged
object ModuleClickGui {
'''+block(click,'enum class ClickGuiStyle')+'''
val isGlassStyle=true;val isLightPollutionGlass=true
val glassBlurValue=1f;val glassRefractionValue=1f;val glassRimValue=1f;val glassShadowValue=1f;val glassTintOpacityValue=.34f
}
''')
write('ModernModule.kt','''package net.ccbluex.liquidbounce.features.module.modules.render.moderngui
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.render.engine.type.Color4b
'''+block(modern,'enum class ModernGuiStyle')+'''
object ModuleModernGui {
 val running=true;val isLightPollutionGlass=true;val usesGlass=true
 object Targets { val clientHudCards=true;val screensAndMenus=true }
 object GlassOptics { val refraction=1f;val specularRim=1f;val accentRimMix=.34f;val blurStrength=1f }
 object CardDesign { val borderOpacity=.5f;val borderWidth=1f;val cardOpacity=.42f;val panelRadius=14f;val shadowOpacity=.5f;val shadowSpread=13f;val slotOpacity=.34f;val slotRadius=5f;val widgetRadius=8f }
 object CustomColors { val accentColor=Color4b(86,138,255,255);val borderColor=accentColor;val surfaceColor=accentColor }
}
''')
fields=set(re.findall(r'monetPalette\.(\w+)',(K/'features/module/modules/render/moderngui/ModernGuiSurface.kt').read_text()))
write('Monet.kt','package net.ccbluex.liquidbounce.features.module.modules.render.moderngui\nclass MonetPalette {\n'+''.join(' val '+f+'='+('false' if f=='isLight' else '0')+'\n' for f in sorted(fields))+'}\n')
write('Material.kt','''package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material
object Material { fun mix(a:Int,b:Int,f:Float):Int=a;fun lstarFromArgb(a:Int):Double=0.0 }
object Theme { fun radius(r:Float,w:Float,h:Float)=r }
''')
# Exact palette source, with Color4b as the only compile host.
write('UiPalette.kt',(K/'ui/common/UiPalette.kt').read_text())
ui=(K/'ui/common/UiDraw.kt').read_text();methods=[]
for name in ['fillCssBox','fillBox']:
 b=block(ui,'    fun '+name+'(');sig=b[:b.index('{')+1];methods.append(sig+'}\n')
write('UiDraw.kt','package net.ccbluex.liquidbounce.ui.common\nimport net.minecraft.client.gui.GuiGraphicsExtractor\nobject UiDraw {\n'+'\n'.join(methods)+'}\n')
write('Backdrop.kt','''package net.ccbluex.liquidbounce.ui.clickgui.glass
import net.minecraft.client.gui.render.TextureSetup
object ClickGuiGlassBackdrop { fun request(blurStrength:Float,vibrance:Float=1.08f):TextureSetup?=null }
''')
# Exact new Onyx adapter, including real extractor types. Other UI internals are explicit hosts.
onyx=(K/'features/module/modules/misc/music/onyx/render/UI.kt').read_text()
write('OnyxHook.kt','''package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render
import net.ccbluex.liquidbounce.features.module.modules.render.moderngui.ModuleModernGui
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Theme
import net.minecraft.client.gui.GuiGraphicsExtractor
class UI(val ctx:GuiGraphicsExtractor) {
 val scale=1f;val alpha=1f
 fun visibleScissor()=true
 fun screenX(x:Float)=x
 fun screenY(y:Float)=y
'''+block(onyx,'    fun lightPollutionCard(')+'\n}\n')
print('Explicit project-only hosts generated; actual Minecraft/RenderPearl/JOML are not mocked')
