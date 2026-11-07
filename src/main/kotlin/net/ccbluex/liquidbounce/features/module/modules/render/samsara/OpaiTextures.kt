package net.ccbluex.liquidbounce.features.module.modules.render.samsara

import com.mojang.blaze3d.platform.NativeImage
import net.ccbluex.liquidbounce.utils.client.clientLogger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * Register packaged or embedded images directly, rather than assuming the hosting mod contributes
 * an assets/samsara resource pack. Must be called from GUI extraction on the render thread.
 */
internal object OpaiTextures {
    private val logger = clientLogger("SamsaraOpaiTextures")
    private val loaded = mutableMapOf<String, Identifier>()
    private val failed = mutableSetOf<String>()

    fun resolve(path: String): Identifier? {
        loaded[path]?.let { return it }
        if (path in failed) return null
        val resource = "/assets/samsara/textures/$path"
        val identifier = Identifier.fromNamespaceAndPath("liquidbounce", "textures/samsara-opai/$path")
        try {
            val fromResource = try {
                OpaiTextures::class.java.getResourceAsStream(resource)?.use { NativeImage.read(it) }
            } catch (e: Exception) {
                logger.warn("Packaged Samsara Opai texture could not be decoded; using embedded copy: {}", resource, e)
                null
            }
            val image = fromResource ?: OpaiEmbeddedTextures.base64(path)?.let { encoded ->
                    ByteArrayInputStream(Base64.getDecoder().decode(encoded)).use { NativeImage.read(it) }
                }
            if (image == null) {
                failed.add(path)
                logger.error("Samsara Opai texture unavailable: {} (no packaged or embedded image)", resource)
                return null
            }
            val texture = try {
                DynamicTexture({ identifier.toString() }, image)
            } catch (e: Exception) {
                image.close()
                throw e
            }
            try {
                mc.textureManager.register(identifier, texture)
            } catch (e: Exception) {
                texture.close()
                throw e
            }
            loaded[path] = identifier
            return identifier
        } catch (e: Exception) {
            failed.add(path)
            logger.error("Failed to register Samsara Opai texture {}", resource, e)
            return null
        }
    }
}
