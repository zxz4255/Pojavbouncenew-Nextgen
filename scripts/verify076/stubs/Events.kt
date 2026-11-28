package net.ccbluex.liquidbounce.event.events
import net.ccbluex.liquidbounce.event.Event
class WorldChangeEvent : Event
class DisconnectEvent : Event
class RotationUpdateEvent : Event
class PlayerTickEvent : Event
enum class EventState { PRE, POST }
class PlayerNetworkMovementTickEvent(val state: EventState) : Event
class GameTickEvent : Event
class PacketEvent(val packet: net.minecraft.network.protocol.Packet<*>) : Event
class ClickGuiScaleChangeEvent(val scale: Float) : Event
class ClickGuiValueChangeEvent(val module: Any) : Event
