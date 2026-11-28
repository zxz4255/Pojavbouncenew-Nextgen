package net.ccbluex.liquidbounce.config.types
import net.ccbluex.liquidbounce.config.types.group.Value
import org.joml.Vector2f
class CurveValue(override val name:String): Value<MutableList<Vector2f>>(mutableListOf(Vector2f(),Vector2f(1f,1f)),name) {
 class Axis {val label="axis"; val range=0f..1f}
 val xAxis=Axis(); val yAxis=Axis()
 fun transform(x:Float)=x
 fun restore() {}
}
