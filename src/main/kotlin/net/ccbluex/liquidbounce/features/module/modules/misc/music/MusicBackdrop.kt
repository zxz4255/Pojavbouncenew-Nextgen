/* Music background capture adapted from this client's Music backdrop; GPL-3.0-or-later. */
package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.render.setPipeline
import net.ccbluex.liquidbounce.utils.client.clientLogger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.clearColorAndDepth
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import kotlin.math.roundToInt

/** Music screen only: extract requests; capture the world before queued GUI rendering. */
object MusicBackdrop {
    private val logger = clientLogger("Music/Backdrop")
    private val horizontal = LazyRenderTargetHolder("${LiquidBounce.CLIENT_NAME} Music Blur H", useDepth = false)
    private val vertical = LazyRenderTargetHolder("${LiquidBounce.CLIENT_NAME} Music Blur V", useDepth = false)
    private val sampler by lazy { RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR) }
    private var pending = false
    private var disabled = false
    private var expectedWidth = 0
    private var expectedHeight = 0

    /** Retry an earlier allocation or shader failure once when the screen is reopened. */
    fun retryOnOpen() { disabled = false; pending = false }

    /** Called once during GUI extraction, before the world for this frame is rendered. */
    fun request(): TextureSetup? {
        pending = false
        if (disabled) return null
        val w = mc.window.width
        val h = mc.window.height
        if (w <= 0 || h <= 0) return null
        return try {
            // NeoRaven uses half-resolution ping-pong targets.
            val tw = (w / 2).coerceAtLeast(1)
            val th = (h / 2).coerceAtLeast(1)
            horizontal.initAndGet(tw, th)
            val result = vertical.initAndGet(tw, th).colorTextureView ?: return null
            expectedWidth = w
            expectedHeight = h
            pending = true
            TextureSetup.singleTexture(result, sampler)
        } catch (e: Exception) {
            disabled = true
            logger.warn("Music blur allocation failed; falling back to an opaque card.", e)
            null
        }
    }

    /** Called from GameRenderer.render after world/post effects and before GuiRenderer.render. */
    fun prepareForRender() {
        if (!pending || disabled) return
        pending = false
        try {
            // A transparent result guarantees the opaque fallback is visible if capture is skipped.
            val output = vertical.get() ?: return
            output.clearColorAndDepth()
            val source = mc.gameRenderer.mainRenderTarget()
            if (source.width != expectedWidth || source.height != expectedHeight) return
            val view = source.colorTextureView ?: return
            val a = horizontal.get() ?: return
            val b = vertical.get() ?: return
            // The first H pass also downsamples. Same four 17-tap box passes as NeoRaven.
            fun pass(input: GpuTextureView, target: com.mojang.blaze3d.pipeline.RenderTarget, h: Boolean) {
                target.createRenderPass({ "Music backdrop box blur" }, useDepthAttachment = false).use { p ->
                    p.setPipeline(if (h) ClientRenderPipelines.MusicBoxH else ClientRenderPipelines.MusicBoxV)
                    p.setUniform("texture0", input, sampler)
                    p.draw(3, 1, 0, 0)
                }
            }
            pass(view, a, true)
            pass(a.colorTextureView ?: return, b, false)
            pass(b.colorTextureView ?: return, a, true)
            pass(a.colorTextureView ?: return, b, false)
        } catch (e: Exception) {
            disabled = true
            try { vertical.get()?.clearColorAndDepth() } catch (_: Exception) { /* device already lost */ }
            logger.warn("Music blur failed; falling back to an opaque card on subsequent frames.", e)
        }
    }

    /** Rounded mask; vertices inherit the GUI pose and scissor. */
    fun draw(ctx: GuiGraphicsExtractor, texture: TextureSetup, x: Float, y: Float, w: Float, h: Float,
             radius: Float, tint: Int, fade: Float) {
        if (w <= 0f || h <= 0f || fade <= 0f) return
        val ew = w.roundToInt().coerceIn(1, 32767)
        val eh = h.roundToInt().coerceIn(1, 32767)
        val er = radius.coerceIn(0f, minOf(w, h) * .5f).roundToInt().coerceIn(0, 32767)
        val alpha = (fade.coerceIn(0f, 1f) * 255f).roundToInt()
        val color = (tint and 0x00ffffff) or (alpha shl 24)
        ctx.drawCustomElement(pipeline = ClientRenderPipelines.MusicFrost, textureSetup = texture,
            bounds = ctx.getBounds(x, y, x + w, y + h)) { pose ->
            addVertexWith2DPose(pose, x, y).setUv(0f, 0f).setColor(color).setUv1(ew, eh).setUv2(er, 0).setLineWidth(0f)
            addVertexWith2DPose(pose, x, y + h).setUv(0f, 1f).setColor(color).setUv1(ew, eh).setUv2(er, 0).setLineWidth(0f)
            addVertexWith2DPose(pose, x + w, y + h).setUv(1f, 1f).setColor(color).setUv1(ew, eh).setUv2(er, 0).setLineWidth(0f)
            addVertexWith2DPose(pose, x + w, y).setUv(1f, 0f).setColor(color).setUv1(ew, eh).setUv2(er, 0).setLineWidth(0f)
        }
    }

    fun close() { pending = false; horizontal.close(); vertical.close() }
}
