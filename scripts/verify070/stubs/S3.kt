package net.ccbluex.liquidbounce.config.types.group
import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.list.Tagged
import kotlin.reflect.KProperty

open class Value<T>(var inner: T) {
    fun get(): T = inner
    fun set(v: T) { inner = v }
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = inner
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { inner = value }
}

open class ValueGroup(val name: String, val aliases: List<String> = emptyList()) {
    fun <T : ValueGroup> tree(group: T): T = group
    fun boolean(name: String, default: Boolean) = Value(default)
    fun float(name: String, default: Float, range: ClosedFloatingPointRange<Float>, suffix: String = "") = Value(default)
    fun int(name: String, default: Int, range: IntRange, suffix: String = "") = Value(default)
    fun key(name: String, default: Int) = Value(InputConstants.Type.KEYBOARD.getOrCreate(default))
    fun key(name: String, default: InputConstants.Key = InputConstants.UNKNOWN) = Value(default)
    inline fun <reified T> enumChoice(name: String, default: T): Value<T> where T : Enum<T>, T : Tagged = Value(default)
}
