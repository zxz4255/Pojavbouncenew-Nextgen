package net.ccbluex.liquidbounce.utils.render
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
fun NativeImage.registerTexture(id: Identifier): DynamicTexture = DynamicTexture({ id.toString() }, this)
