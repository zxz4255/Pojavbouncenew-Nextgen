package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render
class Anim(private var v:Float) {
 fun set(f:Float){v=f};fun value()=v;fun to(f:Float,d:Float,e:Ease){v=f}
 fun update(d:Float){};fun isDone()=true
}
enum class Ease { STANDARD,EMPHASIZED,EMPHASIZED_DECELERATE,DECELERATE; fun get(t:Float)=t }
class UI(ctx:net.minecraft.client.gui.GuiGraphicsExtractor) {
 fun begin(){};fun end(){};fun alphaPush(a:Float){};fun alphaPop(){};fun alpha()=1f
 fun scissorPush(x:Float,y:Float,w:Float,h:Float){};fun scissorPop(){};fun clipVisible()=true
 fun translate(x:Float,y:Float){};fun scale(s:Float,x:Float,y:Float){}
 fun icon(t:String,x:Float,y:Float,s:Float,c:Int){}
 fun rounded(x:Float,y:Float,w:Float,h:Float,r:Float,c:Int){}
 fun outline(x:Float,y:Float,w:Float,h:Float,r:Float,line:Float,c:Int){}
 fun circle(x:Float,y:Float,r:Float,c:Int){}
}
object WavyProgressIndicator {
 class Style(a:Float,b:Float,c:Float,d:Int,e:Int)
 fun drawLinear(ui:UI,x:Float,y:Float,w:Float,h:Float,p:Float,s:Style){}
}
