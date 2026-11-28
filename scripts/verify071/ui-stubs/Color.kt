package net.ccbluex.liquidbounce.render.engine.type
class Color4b(val r: Int=0, val g: Int=0, val b: Int=0, val a: Int=255) {fun with(a:Int)=Color4b(r,g,b,a); val argb: Int get()=(a shl 24) or (r shl 16) or (g shl 8) or b}
