package net.ccbluex.liquidbounce.config.types.group
import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.list.Tagged
import kotlin.reflect.KProperty

open class Value<T>(var inner: T) {
    val name: String = ""
    fun onChange(fn: (T) -> T) = this
    fun onChanged(fn: (T) -> Unit) = this
    fun visibleWhen(fn: () -> Boolean) = this
    fun get(): T = inner
    fun set(v: T) { inner = v }
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = inner
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { inner = value }
}

open class ValueGroup(val name: String, val aliases: List<String> = emptyList()) {
    val inner = mutableListOf<Value<*>>()
    fun <T : ValueGroup> tree(group: T): T = group
    fun boolean(name: String, default: Boolean) = Value(default)
    fun float(name: String, default: Float, range: ClosedFloatingPointRange<Float>, suffix: String = "") = Value(default)
    fun int(name: String, default: Int, range: IntRange, suffix: String = "") = Value(default)
    fun intRange(name: String, default: IntRange, range: IntRange, suffix: String = "") = Value(default)
    fun color(name: String, default: net.ccbluex.liquidbounce.render.engine.type.Color4b) = Value(default)
    fun items(name: String, default: MutableSet<net.minecraft.world.item.Item>) = Value(default)
    fun key(name: String, default: Int) = Value(InputConstants.Type.KEYBOARD.getOrCreate(default))
    fun key(name: String, default: InputConstants.Key = InputConstants.UNKNOWN) = Value(default)
    inline fun <reified T> enumChoice(name: String, default: T): Value<T> where T : Enum<T>, T : Tagged = Value(default)
}

open class ToggleableValueGroup(parent: Any, name: String, enabled: Boolean) : ValueGroup(name) {
 val enabledValue = Value(enabled)
}
