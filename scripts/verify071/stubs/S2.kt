package net.ccbluex.liquidbounce.utils.client
val mc: net.minecraft.client.Minecraft get() = net.minecraft.client.Minecraft.getInstance()
object SilentHotbar {
    val clientsideSlot: Int = 0
    fun isSlotModified(): Boolean = false
}
