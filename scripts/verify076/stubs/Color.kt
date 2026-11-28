package net.ccbluex.liquidbounce.render.engine.type
data class Color4b(val r: Int,val g: Int,val b: Int,val a: Int) { val argb: Int get() = (a shl 24) or (r shl 16) or (g shl 8) or b }
