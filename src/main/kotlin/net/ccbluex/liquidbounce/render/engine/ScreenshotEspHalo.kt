/* GPL-3.0-or-later */
package net.ccbluex.liquidbounce.render.engine

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleScreenshotESP
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.buffers.CachedUniform
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.setPipeline
import net.ccbluex.liquidbounce.utils.render.putVec4

/** Uses a copy of the filled shared outline mask, made before vanilla extracts its thin edge. */
object ScreenshotEspHalo {
    private val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
    private val depthSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
    private val maskTarget = LazyRenderTargetHolder("ScreenshotESP mask", useDepth = false)
    private var maskReady = false

    private data class Data(val radius: Float, val strength: Float, val shadow: Float, val hand: Float, val color: Color4b)
    private val uniform = CachedUniform<Data>(ClientUniformDefine.SCREENSHOT_ESP) { value ->
        putFloat(value.radius)
        putFloat(value.strength)
        putFloat(value.shadow)
        putFloat(value.hand)
        putVec4(value.color)
    }

    @JvmStatic
    fun invalidateMask() { maskReady = false }

    @JvmStatic
    fun captureMask(outline: RenderTarget, frameHasOutline: Boolean) {
        maskReady = false
        if (!ModuleScreenshotESP.running || !ModuleScreenshotESP.halo || !frameHasOutline ||
            outline.width <= 0 || outline.height <= 0 || outline.colorTexture == null) return
        val mask = maskTarget.initAndGet(outline.width, outline.height)
        mask.copyColorFrom(outline)
        maskReady = true
    }

    /** Called immediately before the normal outline blit, so the sharp edge remains on top. */
    fun render(outline: RenderTarget, target: RenderTarget) {
        if (!maskReady || !ModuleScreenshotESP.running || !ModuleScreenshotESP.halo) return
        maskReady = false
        val mask = maskTarget.get()?.colorTextureView ?: return
        draw(mask, mask, target, hand = false)
    }

    fun renderHand(color: GpuTextureView, depth: GpuTextureView, target: RenderTarget) {
        if (!ModuleScreenshotESP.running || !ModuleScreenshotESP.halo) return
        draw(color, depth, target, hand = true)
    }

    private fun draw(color: GpuTextureView, depth: GpuTextureView, target: RenderTarget, hand: Boolean) {
        val shadow = ModuleScreenshotESP.haloStyle == ModuleScreenshotESP.HaloStyle.SHADOW
        val data = Data(ModuleScreenshotESP.haloRadius, ModuleScreenshotESP.haloStrength,
            if (shadow) 1f else 0f, if (hand) 1f else 0f, ModuleScreenshotESP.color)
        val slice = uniform.get(data)
        target.createRenderPass({ "ScreenshotESP outward effect" }, useDepthAttachment = false).use { pass ->
            pass.setPipeline(if (shadow) ClientRenderPipelines.ScreenshotEspShadow else ClientRenderPipelines.ScreenshotEspHalo)
            pass.setUniform("outlineTex", color, sampler)
            pass.setUniform("depthTex", depth, depthSampler)
            pass.setUniform(ClientUniformDefine.SCREENSHOT_ESP.uboName, slice)
            pass.draw(3, 1, 0, 0)
        }
    }
}
