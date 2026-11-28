package net.ccbluex.liquidbounce.event.events
import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.event.Event
import net.minecraft.network.protocol.Packet
import net.minecraft.world.entity.Entity

class GameTickEvent : Event
class KeyboardKeyEvent(val key: InputConstants.Key, val keyCode: Int, val scanCode: Int, val action: Int) : Event
class AttackEntityEvent(val entity: Entity) : Event
class PacketEvent(val packet: Packet<*>) : Event
class ClientLanguageChangedEvent : Event
