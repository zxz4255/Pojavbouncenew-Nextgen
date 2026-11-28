package net.ccbluex.liquidbounce.event
interface Event
interface Listenable
class EventHook<T : Event>
inline fun <reified T : Event> Listenable.handler(noinline fn: (T) -> Unit): EventHook<T> = EventHook()
object EventManager { fun <T : Event> callEvent(e: T): T = e }
