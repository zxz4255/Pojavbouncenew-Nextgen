package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material
object Material {
 fun mix(a:Int,b:Int,t:Float)=a
 fun withAlpha(c:Int,a:Float)=c
 fun scaleAlpha(c:Int,a:Float)=c
}
class Scheme {
 val primary=0;val primaryContainer=0;val onSurface=0;val onPrimaryContainer=0
 val surfaceContainerHigh=0;val onSurfaceVariant=0;val surfaceContainerHighest=0
}
object Theme { fun scheme()=Scheme() }
