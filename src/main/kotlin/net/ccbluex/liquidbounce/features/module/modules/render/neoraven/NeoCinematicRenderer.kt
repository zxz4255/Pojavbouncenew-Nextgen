/* GPL-3.0-only. Port of Matr1ze/NeoRaven b483ffc; see licenses/neoraven/NOTICE.md. */
package net.ccbluex.liquidbounce.features.module.modules.render.neoraven

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleCinematic
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.setPipeline
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.client.renderer.MappableRingBuffer
import net.minecraft.core.BlockPos
import org.joml.Matrix4f

/** World-only post chain, executed after world overlays but BEFORE the first-person hand and GUI. */
object NeoCinematicRenderer {
    private val output = LazyRenderTargetHolder("NeoRaven Cinematic", false)
    private val mips = Array(5) { LazyRenderTargetHolder("NeoRaven Cinematic Mip ${it + 1}", false) }
    private var ring: MappableRingBuffer? = null
    private val adaptation = NeoEyeAdaptation()
    private var lastQuality: ModuleCinematic.Quality? = null
    private var failed = false
    private val linear get() = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
    private val nearest get() = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)

    fun reset() { dispose(); failed = false }
    fun dispose() {
        output.close(); mips.forEach { it.close() }; ring?.close(); ring = null
        adaptation.reset(); lastQuality = null
    }

    fun render(event: WorldRenderEvent) {
        if (!ModuleCinematic.running || failed) return
        val world = mc.level ?: return
        val player = mc.player ?: return
        val target = event.renderTarget
        val width = target.width; val height = target.height
        if (width < 2 || height < 2) return
        val color = target.colorTextureView ?: return
        val depth = target.depthTextureView ?: return
        try {
            val high = ModuleCinematic.quality == ModuleCinematic.Quality.HIGH
            if (lastQuality != ModuleCinematic.quality) {
                adaptation.reset(); mips.forEach { it.close() }; lastQuality = ModuleCinematic.quality
            }
            val light = world.getLightLevelDependentMagicValue(BlockPos.containing(player.x, player.eyeY, player.z))
            val exposure = adaptation.update(world, light, System.nanoTime())
            val projection = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix
            val inverse = Matrix4f(projection).invert()
            if (!inverse.isFinite) return
            val convention01 = NeoDepthConvention.zeroToOne(projection)
            val mipViews = ArrayList<GpuTextureView>(5)
            var previous = color
            var w = width; var h = height
            if (high && ModuleCinematic.bloom > 0f) {
                for (i in mips.indices) {
                    w = (w / 2).coerceAtLeast(1); h = (h / 2).coerceAtLeast(1)
                    val mip = mips[i].initAndGet(w, h)
                    mip.createRenderPass({ "NeoRaven Cinematic Mip ${i + 1}" }, useDepthAttachment = false).use { pass ->
                        pass.setPipeline(ClientRenderPipelines.NeoRaven.CinematicDownsample)
                        pass.setUniform("texture0", previous, linear)
                        pass.draw(3, 1, 0, 0)
                    }
                    previous = requireNotNull(mip.colorTextureView)
                    mipViews.add(previous)
                }
            }
            val ubo = ring ?: ClientUniformDefine.CINEMATIC.createRingBuffer().also { ring = it }
            ubo.rotate()
            val slice = ubo.currentBuffer().slice()
            slice.writeStd140 {
                for (col in 0..3) putVec4(inverse.get(col,0), inverse.get(col,1), inverse.get(col,2), inverse.get(col,3))
                putVec4(projection.m00(), projection.m11(), projection.m20(), projection.m21())
                putVec4(if (convention01) 1f else 2f, if (convention01) 0f else -1f,
                    if (NeoDepthConvention.reversed(projection)) 1f else 0f, 5f)
                putVec4(1f / width, 1f / height,
                    mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.depthFar.coerceAtLeast(1f), 0f)
                putVec4(ModuleCinematic.ambientOcclusion, ModuleCinematic.bloom, ModuleCinematic.lensDistortion,
                    1f + (exposure - 1f) * ModuleCinematic.eyeAdaptation)
            }
            val result = output.initAndGet(width, height)
            result.createRenderPass({ "NeoRaven Cinematic ${ModuleCinematic.quality}" }, useDepthAttachment = false).use { pass ->
                pass.setPipeline(if (high) ClientRenderPipelines.NeoRaven.CinematicHigh else ClientRenderPipelines.NeoRaven.CinematicLight)
                pass.setUniform("scene", color, linear)
                pass.setUniform("geometry", depth, nearest)
                if (high) for (i in 1..5) pass.setUniform("Mip$i", mipViews.getOrNull(i - 1) ?: color, linear)
                pass.setUniform(ClientUniformDefine.CINEMATIC.uboName, slice)
                pass.draw(3, 1, 0, 0)
            }
            target.createRenderPass({ "NeoRaven Cinematic Blit" }, useDepthAttachment = false).use { pass ->
                pass.setPipeline(ClientRenderPipelines.AtmosphereBlit)
                pass.setUniform("texture0", requireNotNull(result.colorTextureView), nearest)
                pass.draw(3, 1, 0, 0)
            }
        } catch (error: Exception) {
            failed = true
            dispose()
            logger.warn("[Cinematic] Post-processing failed; disabling module.", error)
            ModuleCinematic.enabled = false
        }
    }
}
