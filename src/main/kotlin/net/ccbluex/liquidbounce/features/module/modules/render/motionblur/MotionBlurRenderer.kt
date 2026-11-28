/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.render.motionblur

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.setPipeline
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.client.renderer.MappableRingBuffer

/** Ping-pong accumulation: never clear/read the same history attachment while writing it. */
object MotionBlurRenderer {
    private val targets = Array(2) { LazyRenderTargetHolder("MotionBlur History $it", false) }
    private val history = MotionBlurHistory()
    private var readIndex = 0
    private var ring: MappableRingBuffer? = null
    @Volatile private var resetRequested = true
    @Volatile var failureReason: String? = null
        private set

    fun requestReset() { resetRequested = true }
    /** Called only on render thread. */
    fun close() {
        targets.forEach { closeResource(it) }; closeResource(ring); ring = null
        history.reset(); readIndex = 0
    }
    private fun closeResource(resource: AutoCloseable?) {
        try { resource?.close() }
        catch (error: Exception) { logger.warn("[MotionBlur] Resource cleanup failed", error) }
        catch (error: LinkageError) { logger.warn("[MotionBlur] Resource cleanup API unavailable", error) }
    }
    @JvmStatic fun beforeGui() {
        if (resetRequested) { close(); failureReason = null; resetRequested = false }
        if (!ModuleMotionBlur.running || mc.level == null || mc.player == null || mc.isPaused ||
            (!ModuleMotionBlur.blurInGui && mc.gui.screen() != null) || ModuleMotionBlur.strength <= 0f) {
            if (targets.any { it.get() != null }) close()
            return
        }
        if (failureReason != null) return
        try {
            val target = mc.gameRenderer.mainRenderTarget()
            val scene = target.colorTextureView ?: run { history.reset(); return }
            val width = target.width; val height = target.height
            if (width < 1 || height < 1) { history.reset(); return }
            val player = mc.player ?: return
            val camera = mc.gameRenderer.mainCamera()
            val position = camera.position()
            val weight = history.begin(requireNotNull(mc.level), player, mc.options.cameraType, width, height,
                System.nanoTime(), position.x, position.y, position.z, player.yRot, player.xRot, ModuleMotionBlur.strength)
            // Read existing history without initAndGet: that method CLEARS even same-sized targets.
            val previous = targets[readIndex].get()
            val usable = weight > 0f && previous?.width == width && previous.height == height
            val destination = 1 - readIndex
            val output = targets[destination].initAndGet(width, height)
            val ubo = ring ?: ClientUniformDefine.MOTION_BLUR.createRingBuffer().also { ring = it }
            ubo.rotate()
            val slice = ubo.currentBuffer().slice()
            slice.writeStd140 { putVec4(if (usable) weight else 0f, 0f, 0f, 0f) }
            val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
            output.createRenderPass({ "MotionBlur Accumulate" }, useDepthAttachment = false).use { pass ->
                pass.setPipeline(ClientRenderPipelines.MotionBlur)
                pass.setUniform("texture0", scene, sampler)
                pass.setUniform("historyTexture", if (usable) requireNotNull(previous?.colorTextureView) else scene, sampler)
                pass.setUniform(ClientUniformDefine.MOTION_BLUR.uboName, slice)
                pass.draw(3, 1, 0, 0)
            }
            target.createRenderPass({ "MotionBlur Present" }, useDepthAttachment = false).use { pass ->
                pass.setPipeline(ClientRenderPipelines.AtmosphereBlit)
                pass.setUniform("texture0", requireNotNull(output.colorTextureView), sampler)
                pass.draw(3, 1, 0, 0)
            }
            readIndex = destination
        } catch (error: Exception) {
            failureReason = error.javaClass.simpleName
            close()
            logger.error("[MotionBlur] GPU post pass paused; toggle off/on to retry", error)
        } catch (error: LinkageError) {
            failureReason = error.javaClass.simpleName
            close()
            logger.error("[MotionBlur] GPU API unavailable; toggle off/on to retry", error)
        }
    }
}
