/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
@file:Suppress(
    "TooManyFunctions",
    "LongParameterList",
    "CognitiveComplexMethod",
    "ReturnCount",
    "ComplexCondition",
    "NamedArguments",
    "NestedBlockDepth",
    "MaxLineLength",
)

package net.ccbluex.liquidbounce.ui.clickgui.glass

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.buffers.CachedUniform
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.setPipeline
import net.ccbluex.liquidbounce.utils.client.clientLogger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.clearColorAndDepth
import net.minecraft.client.gui.render.TextureSetup
import kotlin.math.roundToInt

/**
 * Captures and progressively blurs the live 3D world framebuffer immediately before queued GUI rendering
 * for Native ClickGUI Glass Mode (`glass_blur_h.fsh` + `glass_blur_v.fsh` + `glass_blur.glsl`).
 */
object ClickGuiGlassBackdrop {

    private val logger = clientLogger("ClickGui/GlassBackdrop")
    private val horizontal = LazyRenderTargetHolder("${LiquidBounce.CLIENT_NAME} ClickGUI Glass Blur H", useDepth = false)
    private val vertical = LazyRenderTargetHolder("${LiquidBounce.CLIENT_NAME} ClickGUI Glass Blur V", useDepth = false)
    private val sampler by lazy { RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR) }

    private data class BlurPassParams(val stepScaleQuantized: Int, val vibranceQuantized: Int)

    private val nearBlurUniform = CachedUniform<BlurPassParams>(ClientUniformDefine.CLICKGUI_GLASS_BLUR) { p ->
        putVec4(p.stepScaleQuantized / 100f, p.vibranceQuantized / 100f, 0f, 0f)
    }

    private val wideBlurUniform = CachedUniform<BlurPassParams>(ClientUniformDefine.CLICKGUI_GLASS_BLUR) { p ->
        putVec4(p.stepScaleQuantized / 100f, p.vibranceQuantized / 100f, 1f, 0f)
    }

    private var pending = false
    private var disabled = false
    private var expectedWidth = 0
    private var expectedHeight = 0
    private var requestedBlurScale = 1.15f
    private var requestedVibrance = 1.08f

    fun retryOnOpen() {
        disabled = false
        pending = false
    }

    /**
     * Called during GUI/HUD state extraction (before this frame's 3D world renders).
     * Multiple callers in the same frame share the same blurred FBO texture setup.
     */
    fun request(blurStrength: Float, vibrance: Float = 1.08f): TextureSetup? {
        if (disabled) return null
        val w = mc.window.width
        val h = mc.window.height
        if (w <= 0 || h <= 0) return null
        return try {
            val tw = (w / 2).coerceAtLeast(1)
            val th = (h / 2).coerceAtLeast(1)
            horizontal.initAndGet(tw, th)
            val result = vertical.initAndGet(tw, th).colorTextureView ?: return null
            expectedWidth = w
            expectedHeight = h
            val clampedBlur = blurStrength.coerceIn(0.2f, 3.0f)
            val clampedVibrance = vibrance.coerceIn(0.70f, 1.60f)
            if (pending) {
                requestedBlurScale = kotlin.math.max(requestedBlurScale, clampedBlur)
                requestedVibrance = kotlin.math.max(requestedVibrance, clampedVibrance)
            } else {
                requestedBlurScale = clampedBlur
                requestedVibrance = clampedVibrance
                pending = true
            }
            TextureSetup.singleTexture(result, sampler)
        } catch (e: Exception) {
            disabled = true
            logger.warn("ClickGUI glass backdrop allocation failed; using translucent fallback.", e)
            null
        }
    }

    /**
     * Called from `MixinGameRenderer.render` after world + post effects and right before `GuiRenderer.render()`.
     */
    fun prepareForRender() {
        if (!pending || disabled) return
        pending = false
        try {
            val output = vertical.get() ?: return
            output.clearColorAndDepth()
            val source = mc.gameRenderer.mainRenderTarget()
            if (source.width != expectedWidth || source.height != expectedHeight) return
            val sourceView = source.colorTextureView ?: return
            val targetA = horizontal.get() ?: return
            val targetB = vertical.get() ?: return

            val nearScaleQ = (requestedBlurScale * 100f).roundToInt().coerceIn(20, 350)
            val wideScaleQ = (requestedBlurScale * 185f).roundToInt().coerceIn(35, 600)
            val vibQ = (requestedVibrance * 100f).roundToInt().coerceIn(70, 160)
            val nearSlice = nearBlurUniform.get(BlurPassParams(nearScaleQ, 100))
            val wideSlice = wideBlurUniform.get(BlurPassParams(wideScaleQ, vibQ))

            fun runPass(input: GpuTextureView, target: RenderTarget, isHorizontal: Boolean, uboSlice: GpuBufferSlice) {
                target.createRenderPass({ "ClickGUI Liquid Glass Gaussian Blur" }, useDepthAttachment = false).use { p ->
                    p.setPipeline(if (isHorizontal) ClientRenderPipelines.ClickGuiGlassBlurH else ClientRenderPipelines.ClickGuiGlassBlurV)
                    p.setUniform("texture0", input, sampler)
                    p.setUniform(ClientUniformDefine.CLICKGUI_GLASS_BLUR.uboName, uboSlice)
                    p.draw(3, 1, 0, 0)
                }
            }

            // Dual-scale 4-pass 15-tap bilinear Gaussian blur for wide, silky bokeh frost
            runPass(sourceView, targetA, true, nearSlice)
            runPass(targetA.colorTextureView ?: return, targetB, false, nearSlice)
            runPass(targetB.colorTextureView ?: return, targetA, true, wideSlice)
            runPass(targetA.colorTextureView ?: return, targetB, false, wideSlice)
        } catch (e: Exception) {
            disabled = true
            runCatching { vertical.get()?.clearColorAndDepth() }
            logger.warn("ClickGUI glass backdrop blur failed; falling back on subsequent frames.", e)
        }
    }

    fun close() {
        pending = false
        horizontal.close()
        vertical.close()
        nearBlurUniform.close()
        wideBlurUniform.close()
    }
}
