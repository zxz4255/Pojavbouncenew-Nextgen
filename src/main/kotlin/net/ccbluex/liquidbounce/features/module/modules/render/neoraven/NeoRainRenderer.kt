/* GPL-3.0-only. Port of Matr1ze/NeoRaven b483ffc; see licenses/neoraven/NOTICE.md. */
package net.ccbluex.liquidbounce.features.module.modules.render.neoraven

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleRainReflections
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.setPipeline
import net.ccbluex.liquidbounce.utils.client.gpuDevice
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.write
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MappableRingBuffer
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.FogType
import org.joml.Matrix4f
import org.joml.Vector4f
import java.util.Optional

/** Original two-pass half-resolution SSR + depth-matched wet-ground composite. */
object NeoRainRenderer {
    private val reflection = LazyRenderTargetHolder("NeoRaven Rain Reflections Half", false)
    private val exposure = NeoRainExposure()
    private var pixels: NativeImage? = null
    private var wetTexture: GpuTexture? = null
    private var wetView: GpuTextureView? = null
    private var ring: MappableRingBuffer? = null
    private var hasWetColumns = false
    private var failed = false
    private val clear = Vector4f(0f)
    private val nearest get() = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
    private val linear get() = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)

    fun reset() { dispose(); failed = false }
    fun dispose() {
        reflection.close(); ring?.close(); ring = null
        wetView?.close(); wetView = null; wetTexture?.close(); wetTexture = null
        pixels?.close(); pixels = null
        hasWetColumns = false; exposure.invalidate()
    }

    private fun shadersActive(): Boolean {
        if (!FabricLoader.getInstance().isModLoaded("iris")) return false
        return try {
            val type = Class.forName("net.irisshaders.iris.api.v0.IrisApi")
            val api = type.getMethod("getInstance").invoke(null)
            type.getMethod("isShaderPackInUse").invoke(api) as Boolean
        } catch (_: ReflectiveOperationException) {
            // Unknown shader bridge: conservatively avoid sampling a foreign depth/color convention.
            true
        }
    }

    fun render(event: WorldRenderEvent) {
        if (!ModuleRainReflections.running || failed) return
        val level = mc.level ?: return
        if (!level.dimensionType().hasSkyLight() || shadersActive()) return
        val rain = level.getRainLevel(event.partialTicks)
        if (rain <= 0.01f) return
        if (event.camera.fluidInCamera == FogType.WATER || event.camera.fluidInCamera == FogType.LAVA) return
        val target = event.renderTarget
        if (target.width < 2 || target.height < 2) return
        val scene = target.colorTextureView ?: return
        val depth = target.depthTextureView ?: return
        try {
            val state = mc.gameRenderer.gameRenderState().levelRenderState
            val camera = state.cameraRenderState
            val inverseView = Matrix4f(camera.viewRotationMatrix).invert()
            val projection = camera.projectionMatrix
            val inverseProjection = Matrix4f(projection).invert()
            if (!inverseView.isFinite || !inverseProjection.isFinite) return
            val eyeX = camera.pos.x + inverseView.m30()
            val eyeY = camera.pos.y + inverseView.m31()
            val eyeZ = camera.pos.z + inverseView.m32()
            updateExposure(level, eyeX, eyeZ)
            if (!hasWetColumns) return
            val w = target.width; val h = target.height
            val halfW = (w + 1) / 2; val halfH = (h + 1) / 2
            val half = reflection.initAndGet(halfW, halfH)
            val ubo = ring ?: ClientUniformDefine.NEO_RAIN.createRingBuffer().also { ring = it }
            ubo.rotate()
            val slice = ubo.currentBuffer().slice()
            val sky = state.skyRenderState.skyColor
            val clip01 = NeoDepthConvention.zeroToOne(projection)
            slice.writeStd140 {
                for (col in 0..3) putVec4(projection.get(col,0), projection.get(col,1), projection.get(col,2), projection.get(col,3))
                for (col in 0..3) putVec4(inverseProjection.get(col,0), inverseProjection.get(col,1), inverseProjection.get(col,2), inverseProjection.get(col,3))
                putVec4(inverseView.m00(), inverseView.m10(), inverseView.m20(), 0f)
                putVec4(inverseView.m01(), inverseView.m11(), inverseView.m21(), 0f)
                putVec4(inverseView.m02(), inverseView.m12(), inverseView.m22(), 0f)
                putVec4((eyeX - exposure.x).toFloat(), eyeY.toFloat(), (eyeZ - exposure.z).toFloat(), NeoRainExposure.SIZE.toFloat())
                putVec4(NeoRainExposure.wrap(eyeX), NeoRainExposure.wrap(eyeZ), rain,
                    ((level.gameTime % 1200L) + event.partialTicks) / 1200f)
                putVec4(sky.x(), sky.y(), sky.z(), 0f)
                putVec4(1f / w, 1f / h, if (clip01) 1f else 2f, if (clip01) 0f else -1f)
                putVec4(halfW.toFloat(), halfH.toFloat(), if (NeoDepthConvention.reversed(projection)) 1f else 0f, level.minY.toFloat())
            }
            half.createRenderPass({ "NeoRaven Rain Trace" }, clearColor = Optional.of(clear), useDepthAttachment = false).use { pass ->
                pass.setPipeline(ClientRenderPipelines.NeoRaven.RainTrace)
                pass.setUniform("DepthSampler", depth, nearest)
                pass.setUniform("SceneSampler", scene, linear)
                pass.setUniform("ExposureSampler", requireNotNull(wetView), nearest)
                pass.setUniform(ClientUniformDefine.NEO_RAIN.uboName, slice)
                pass.draw(3, 1, 0, 0)
            }
            // Scene color is no longer sampled here: no read/write feedback. Depth is read-only.
            target.createRenderPass({ "NeoRaven Rain Composite" }, useDepthAttachment = false).use { pass ->
                pass.setPipeline(ClientRenderPipelines.NeoRaven.RainComposite)
                pass.setUniform("DepthSampler", depth, nearest)
                pass.setUniform("ReflectionSampler", requireNotNull(half.colorTextureView), nearest)
                pass.setUniform(ClientUniformDefine.NEO_RAIN.uboName, slice)
                pass.draw(3, 1, 0, 0)
            }
        } catch (error: Exception) {
            failed = true
            dispose()
            logger.warn("[RainReflections] Render failed; suspended until re-enabled.", error)
        }
    }

    private fun updateExposure(level: ClientLevel, eyeX: Double, eyeZ: Double) {
        if (!exposure.needsRefresh(level, eyeX, eyeZ, level.gameTime)) return
        exposure.refreshed(level, eyeX, eyeZ, level.gameTime)
        val size = NeoRainExposure.SIZE
        val image = pixels ?: NativeImage(size, size, false).also { pixels = it }
        val column = BlockPos.MutableBlockPos()
        hasWetColumns = false
        for (cz in 0 until size step 16) for (cx in 0 until size step 16) {
            // Never request/load missing chunks from a render callback.
            val chunk = level.chunkSource.getChunk((exposure.x + cx) shr 4, (exposure.z + cz) shr 4, false)
            for (z in cz until cz + 16) for (x in cx until cx + 16) {
                var wet = false
                var encoded = 0
                if (chunk != null && !chunk.isEmpty) {
                    val worldX = exposure.x + x; val worldZ = exposure.z + z
                    val top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX and 15, worldZ and 15) + 1
                    if (top > level.minY && top <= level.maxY + 1) {
                        column.set(worldX, top - 1, worldZ)
                        val state = chunk.getBlockState(column)
                        wet = state.isSolid && state.fluidState.isEmpty && !state.`is`(BlockTags.LEAVES) &&
                            level.getPrecipitationAt(column) == Biome.Precipitation.RAIN
                        if (wet) encoded = exposure.encodeHeight(top - 1, level.minY)
                    }
                }
                // NativeImage ABGR -> RGBA8, preserving exact nearest-sampled channel values.
                image.setPixelABGR(x, z, if (wet) (0xFF000000.toInt() or encoded) else 0)
                hasWetColumns = hasWetColumns || wet
            }
        }
        if (!hasWetColumns) return
        val texture = wetTexture ?: gpuDevice.createTexture(
            "NeoRaven Rain Exposure", GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.RGBA8_UNORM, size, size, 1, 1,
        ).also { wetTexture = it; wetView = gpuDevice.createTextureView(it) }
        texture.write(image)
    }
}
