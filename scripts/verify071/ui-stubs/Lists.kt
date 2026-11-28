package net.ccbluex.liquidbounce.config.types.list
import net.ccbluex.liquidbounce.config.types.group.Value
import java.util.SequencedSet
class Deserializer { fun deserializeThrowing(s: String): Any = s }
class InnerType { val deserializer: Deserializer?=null }
open class MutableListValue<T: MutableCollection<E>,E>(override val name: String, initial: T): Value<T>(initial) {
 val innerValueType=InnerType(); fun restore() {}
}
class ItemListValue<T: MutableSet<E>,E>(name: String,initial: T): MutableListValue<T,E>(name,initial) {
 class NamedItem<E>(val name: String,val value: E)
 val items=emptyList<NamedItem<E>>()
}
class RegistryListValue<T: SequencedSet<E>,E>(name: String,initial: T): MutableListValue<T,E>(name,initial) { val registry="test" }
class RegistryMutableListValue<T: MutableList<E>,E>(name: String,initial: T): MutableListValue<T,E>(name,initial) { val registry="test" }

class MultiChoiceListValue<T:Tagged>(override val name:String,val choices:List<T>): Value<MutableSet<T>>(linkedSetOf(),name) {
 val isOrderSensitive=false; val canBeNone=true
}
