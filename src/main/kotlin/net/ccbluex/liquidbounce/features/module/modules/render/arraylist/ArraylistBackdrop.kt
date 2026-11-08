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

package net.ccbluex.liquidbounce.features.module.modules.render.arraylist

import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.buffers.CachedUniform
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.setPipeline
import net.ccbluex.liquidbounce.utils.client.clientLogger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.render.TextureSetup
import kotlin.math.ceil
import kotlin.math.exp

/**
 * Solstice blurs the game behind every row with a Direct2D gaussian blur (`D2D::addBlurOptimized`),
 * clipped to the row rect. This does the same thing with the client's own pipeline: the frame is
 * blurred once per frame with a separable gaussian (H pass reusing the HUD blur shader, V pass using
 * [ClientRenderPipelines.ArrayListBackdropBlurV]) into a pooled render target, and each row then
 * samples that target. Two passes total, independent of the number of rows.
 */
internal object ArraylistBackdrop {

    private val logger = clientLogger("Arraylist/Backdrop")

    /**
     * `vec4[23]` holds up to 91 normalized weights, 3 sigma on each side is plenty.
     */
    private const val MAX_KERNEL_RADIUS = 45

    private const val PREPARE_INTERVAL_NANOS = 4_000_000L

    private val horizontalTarget = LazyRenderTargetHolder(
        "${LiquidBounce.CLIENT_NAME} Arraylist Blur H",
        useDepth = false,
    )

    private val blurredTarget = LazyRenderTargetHolder(
        "${LiquidBounce.CLIENT_NAME} Arraylist Blur",
        useDepth = false,
    )

    private val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)

    private data class Kernel(val sigma: Float, val radius: Int)

    private val kernelUniform = CachedUniform<Kernel>(ClientUniformDefine.GUI_BLUR_KERNEL) { value ->
        writeKernel(value.sigma, value.radius)
    }

    private var texture: TextureSetup? = null

    private var lastPrepare = Long.MIN_VALUE

    private var failures = 0

    private var unavailable = false

    /**
     * Precomputes the normalized gaussian kernel and packs it into the kernel uniform buffer, exactly
     * like the HUD blur does.
     */
    private fun Std140Builder.writeKernel(sigma: Float, kernelRadius: Int) {
        val count = kernelRadius * 2 + 1
        val raw = FloatArray(count)
        var total = 0.0f

        for (i in -kernelRadius..kernelRadius) {
            val weight = exp(-0.5f * i * i / (sigma * sigma))
            raw[i + kernelRadius] = weight
            total += weight
        }

        for (i in raw.indices) {
            raw[i] /= total
        }

        for (vecIndex in 0 until 23) {
            val base = vecIndex * 4
            putVec4(
                raw.getOrElse(base) { 0f },
                raw.getOrElse(base + 1) { 0f },
                raw.getOrElse(base + 2) { 0f },
                raw.getOrElse(base + 3) { 0f },
            )
        }

        putInt(kernelRadius)
    }

    /**
     * Blurs the current frame, at most once per frame (throttled so that a 300 fps overlay does not
     * run the passes more often than the frame itself).
     *
     * @param sigma gaussian sigma in physical pixels
     * @return the texture setup of the blurred frame, or `null` if the blur is not available
     */
    fun prepare(sigma: Float): TextureSetup? {
        if (unavailable || sigma <= 0f) {
            return null
        }

        val now = System.nanoTime()
        if (now - lastPrepare < PREPARE_INTERVAL_NANOS) {
            return texture
        }
        lastPrepare = now

        return runCatching { blurFrame(sigma) }
            .onSuccess { setup ->
                texture = setup
                failures = 0
            }
            .onFailure { throwable ->
                texture = null
                failures++

                if (failures >= 3) {
                    unavailable = true
                    logger.warn("Arraylist backdrop blur is unavailable, falling back to the plain background.", throwable)
                }
            }
            .getOrNull()
    }

    private fun blurFrame(sigma: Float): TextureSetup? {
        val source = mc.gameRenderer.mainRenderTarget()
        val sourceTexture = source.colorTextureView ?: return null

        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) {
            return null
        }

        val kernelRadius = ceil(sigma * 3f).toInt().coerceIn(1, MAX_KERNEL_RADIUS)
        val uniform = kernelUniform.get(Kernel(sigma, kernelRadius))

        val horizontal = horizontalTarget.initAndGet(width, height)
        horizontal.createRenderPass({ "Arraylist backdrop blur H" }, useDepthAttachment = false).use { pass ->
            pass.setPipeline(ClientRenderPipelines.GuiBlurH)
            pass.setUniform("texture0", sourceTexture, sampler)
            pass.setUniform(ClientUniformDefine.GUI_BLUR_KERNEL.uboName, uniform)
            pass.draw(3, 1, 0, 0)
        }

        val vertical = horizontal.colorTextureView ?: return null

        val blurred = blurredTarget.initAndGet(width, height)
        blurred.createRenderPass({ "Arraylist backdrop blur V" }, useDepthAttachment = false).use { pass ->
            pass.setPipeline(ClientRenderPipelines.ArrayListBackdropBlurV)
            pass.setUniform("texture0", vertical, sampler)
            pass.setUniform(ClientUniformDefine.GUI_BLUR_KERNEL.uboName, uniform)
            pass.draw(3, 1, 0, 0)
        }

        return TextureSetup.singleTexture(blurred.colorTextureView ?: return null, sampler)
    }

}
