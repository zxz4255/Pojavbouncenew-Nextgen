package net.ccbluex.liquidbounce.features.module.modules.misc.music
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Typeface
object ModuleMusic {
 var showIsland=true
 fun islandPillActive()=showIsland
 fun islandScale()=1f
 fun islandSize()=1f
 fun islandPositionX()=50f
 fun islandPositionY()=0f
 fun islandShowsLyric()=true
}
object MusicTypeface {
 val LABEL_TIGHT=Typeface.Style()
 val CARD_ARTIST=Typeface.Style()
 val CARD_CLOCK=Typeface.Style()
 fun style(a:Float,b:Float,c:Boolean)=Typeface.Style()
}
object CoverCache { fun tick(){};fun get(a:String,b:Int,c:Int):Any?=null }
class MusicScreen
object MusicSpectrumRenderer {fun draw(vararg v:Any){}}
