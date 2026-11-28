package net.ccbluex.liquidbounce.event.events
import net.ccbluex.liquidbounce.event.Event
class WorldChangeEvent : Event
class DisconnectEvent : Event
class RotationUpdateEvent : Event
class PlayerTickEvent : Event
enum class EventState { PRE, POST }
class PlayerNetworkMovementTickEvent(val state: EventState) : Event
