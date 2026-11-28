package net.ccbluex.liquidbounce.event.events
import net.ccbluex.liquidbounce.event.Event
class WorldChangeEvent : Event
class DisconnectEvent : Event
class WorldRenderEvent(val camera: net.minecraft.client.Camera, val renderTarget: com.mojang.blaze3d.pipeline.RenderTarget, val partialTicks: Float) : Event
