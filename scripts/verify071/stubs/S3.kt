package net.ccbluex.liquidbounce.config.types.group
import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.list.Tagged
import kotlin.reflect.KProperty

open class Value<T>(var inner: T, open val name: String = "leaf") {
    var valueType=net.ccbluex.liquidbounce.config.types.ValueType.CONFIGURABLE
    var key: String? = null
    val description = net.ccbluex.liquidbounce.features.module.DescHolder()
    var notAnOption = false
    var visibleCondition = java.util.function.BooleanSupplier { true }
    fun get(): T = inner
    fun set(v: T) { inner = v }
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = inner
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { inner = value }
}

open class ValueGroup(override val name: String, val aliases: List<String> = emptyList()) : Value<MutableList<Value<*>>>(mutableListOf(), name) {
    fun <T : ValueGroup> tree(group: T): T = group
    fun boolean(name: String, default: Boolean) = Value(default)
    fun float(name: String, default: Float, range: ClosedFloatingPointRange<Float>, suffix: String = "") = Value(default)
    fun int(name: String, default: Int, range: IntRange, suffix: String = "") = Value(default)
    fun key(name: String, default: Int) = Value(InputConstants.Type.KEYBOARD.getOrCreate(default))
    fun key(name: String, default: InputConstants.Key = InputConstants.UNKNOWN) = Value(default)
    inline fun <reified T> enumChoice(name: String, default: T): Value<T> where T : Enum<T>, T : Tagged = Value(default)
}

open class Mode(name: String) : ValueGroup(name) { val tag=name }
class ModeValueGroup<T:Mode>(name:String, val modes: List<T>) : ValueGroup(name) {
 var activeMode = modes.first()
 fun setByString(tag:String) { activeMode=modes.first { it.tag==tag } }
}
class ToggleableValueGroup(name:String) : ValueGroup(name) {
 var enabled=false
 val enabledValue=Value(false)
}
