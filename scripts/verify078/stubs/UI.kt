package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render
import net.minecraft.client.gui.GuiGraphicsExtractor
object Typeface { class Style { fun supports(text:String)=true;fun width(text:String)=text.length*6f };val LABEL_TIGHT=Style() }
class UI(ctx:GuiGraphicsExtractor) {
 companion object {var paints=0;const val ALIGN_LEFT=0;const val ALIGN_RIGHT=1;const val ALIGN_CENTER=2}
 fun begin(){};fun end(){};fun push(){};fun pop(){};fun alphaPush(v:Float){};fun alphaPop(){}
 fun translate(vararg v:Float){};fun scale(vararg v:Float){};fun scissorPush(vararg v:Float){};fun scissorPop(){}
 fun icon(vararg v:Any){paints++};fun text(vararg v:Any){paints++};fun rounded(vararg v:Any){paints++}
 fun smoothShadow(vararg v:Any){paints++};fun frost(vararg v:Any){paints++};fun outline(vararg v:Any){paints++}
 fun imageRounded(vararg v:Any){paints++}
 fun truncate(style:Typeface.Style,text:String,w:Float)=text
}
object WavyProgressIndicator {class Style(vararg v:Any);fun drawLinear(vararg v:Any){} }
object Blur {fun ready()=false}
