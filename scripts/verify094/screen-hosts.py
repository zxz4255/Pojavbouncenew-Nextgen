from pathlib import Path
import sys
r,w=map(Path,sys.argv[1:]);w.mkdir(parents=True,exist_ok=True)
base='net.ccbluex.liquidbounce.features.module.modules.misc.music'
for n in ['ConfigSystem.kt','UiLocalization.kt','AudioEngine.kt']:
 (w/n).write_text((r/'scripts/verify078/stubs'/n).read_text())
p=w/'AudioEngine.kt';s=p.read_text().replace('    fun stop() {}','    fun stop() {}\n    fun isBuffering()=false');p.write_text(s)
(w/'Client.kt').write_text('''package net.ccbluex.liquidbounce.utils.client
val mc get()=net.minecraft.client.Minecraft.getInstance()
val inGame get()=mc.player!=null && mc.level!=null
val logger=org.slf4j.LoggerFactory.getLogger("Music094HostedCompile")
''')
(w/'UI.kt').write_text('''package '''+base+'''.onyx.render
import net.minecraft.client.gui.GuiGraphicsExtractor
object Typeface {class Style(val lineHeight:Float=18f){fun width(s:String?)=(s?.length?:0)*7f;fun supports(s:String)=true};val LABEL_TIGHT=Style()}
class UI(ctx:GuiGraphicsExtractor){
 companion object {const val ALIGN_LEFT=0;const val ALIGN_RIGHT=1;const val ALIGN_CENTER=2}
 fun begin(){};fun end(){};fun push(){};fun pop(){};fun alphaPush(v:Float){};fun alphaPop(){}
 fun translate(vararg v:Float){};fun scale(vararg v:Float){};fun scissorPush(vararg v:Float){};fun scissorPop(){}
 fun icon(vararg v:Any?){};fun text(vararg v:Any?){};fun rounded(vararg v:Any?){};fun circle(vararg v:Any?){}
 fun shadow(vararg v:Any?){};fun smoothShadow(vararg v:Any?){};fun frost(vararg v:Any?){};fun outline(vararg v:Any?){}
 fun rect(vararg v:Any?){};fun gradient(vararg v:Any?){};fun gradient4(vararg v:Any?){};fun imageRounded(vararg v:Any?){}
 fun lightPollutionCard(vararg v:Float):Boolean=false
 fun musicPanelCard(x:Float,y:Float,w:Float,h:Float,opacity:Float=1f){}
 fun truncate(style:Typeface.Style,text:String?,w:Float)=text.orEmpty()
}
object WavyProgressIndicator {class Style(vararg v:Any);fun drawLinear(vararg v:Any){};fun drawCircularIndeterminate(vararg v:Any){}}
object Blur {fun request(){};fun ready()=false;fun prepare(){}}
''')
(w/'UiHost.kt').write_text('''package '''+base+'''
import net.minecraft.resources.Identifier
import '''+base+'''.onyx.render.Typeface
object ModuleMusic {fun islandPillActive()=true;fun setIslandVisible(v:Boolean){};fun islandScale()=1f;fun islandSize()=1f;fun islandPositionX()=50f;fun islandPositionY()=0f;fun islandShowsLyric()=true}
object MusicTypeface {
'''+''.join(' val '+n+'=Typeface.Style()\n' for n in ['BODY','CAPTION','CARD_TITLE','DISPLAY','HEADLINE','HEADLINE_LARGE','LABEL','LABEL_TIGHT','ROW','ROW_SUB','SEARCH','CARD_ARTIST','CARD_CLOCK'])+'''
 fun style(a:Float,b:Float,c:Boolean)=Typeface.Style(b)
}
object CoverCache {fun tick(){};fun get(a:String,b:Int,c:Int):Identifier?=null;fun dominant(s:String)=0}
object MusicSpectrumRenderer {fun draw(vararg v:Any){}}
object MusicSpectrumAnalyzer {fun suspend(){}}
''')
(w/'Backdrop.kt').write_text('''package net.ccbluex.liquidbounce.ui.clickgui.glass
object ClickGuiGlassBackdrop {fun retryOnOpen(){}}
''')
s=(r/'src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/misc/music/MusicViper.kt').read_text();s=s[s.index('internal data class ViperSettings('):s.index('/** PCM bridge')];(w/'ViperSettings.kt').write_text('package '+base+'\n'+s)
