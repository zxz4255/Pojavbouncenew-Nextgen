package net.ccbluex.liquidbounce.utils.client
val mc: net.minecraft.client.Minecraft get() = net.minecraft.client.Minecraft.getInstance()
val gpuDevice get() = com.mojang.blaze3d.systems.RenderSystem.getDevice()
val logger = org.slf4j.LoggerFactory.getLogger("verify074")
