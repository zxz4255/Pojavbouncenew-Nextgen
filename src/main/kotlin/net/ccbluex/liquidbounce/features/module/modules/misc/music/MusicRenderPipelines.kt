package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientShaders
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.gpuDevice
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.kotlin.optional
import net.ccbluex.liquidbounce.utils.render.createUbo
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/** Music-owned SDF world-text pipeline, ordinary alpha compositing in a single pass.
 * The font core is rendered even at zero glow intensity. Missing shader => no fake layered fallback.
 */
internal object MusicRenderPipelines {

    /** Uniform block name, shared with `lyric_glow.fsh`. */
    const val GLOW_UNIFORM = "MonetLyricGlowData"

    private const val SHADER_NAMESPACE = "liquidbounce_music"
    private const val SHADER_PATH = "lyric_glow"

    private val shaderId: Identifier = Identifier.fromNamespaceAndPath(SHADER_NAMESPACE, SHADER_PATH)

    private val glowBindGroup: BindGroupLayout = BindGroupLayout.builder()
        .withUniform(GLOW_UNIFORM, UniformType.UNIFORM_BUFFER)
        .build()

    private fun register(name: String, factory: () -> RenderPipeline.Builder): RenderPipeline? = runCatching {
        val pipeline = factory().build()
        RenderPipelines.register(pipeline)
        pipeline
    }.onFailure {
        logger.warn("[Music] Could not register pipeline $name; a fallback will be used.", it)
    }.getOrNull()

    private fun glyphPipeline(name: String, depthTested: Boolean): RenderPipeline? = register(
        name,
    ) {
        RenderPipeline.Builder()
            .withLocation(LiquidBounce.identifier("pipeline/music/$name"))
            .withSnippet(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withFragmentShader(ClientShaders.Fragment.FontMask)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withCull(false)
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(
                if (depthTested) optional(DepthStencilState.DEFAULT) else optional<DepthStencilState>(),
            )
    }

    private fun glowPipeline(name: String, depthTested: Boolean): RenderPipeline? = register(
        name,
    ) {
        RenderPipeline.Builder()
            .withLocation(LiquidBounce.identifier("pipeline/music/$name"))
            .withSnippet(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withVertexShader(shaderId)
            .withFragmentShader(shaderId)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withCull(false)
            .withBindGroupLayout(glowBindGroup)
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(
                if (depthTested) optional(DepthStencilState.DEFAULT) else optional<DepthStencilState>(),
            )
    }

    private val fontMaskOccluded = glyphPipeline("font_mask_quads_depth_tested", depthTested = true)
    private val glowOccluded = glowPipeline("lyric_glow_depth_tested", depthTested = true)
    private val glowSeeThrough = glowPipeline("lyric_glow_no_depth_test", depthTested = false)

    /** One small uniform buffer: params (intensity, softness, pulse, unused) + tint. */
    private val glowBuffer: GpuBufferSlice by lazy {
        gpuDevice.createUbo({ "Monet Lyric Glow" }) { vec4 + vec4 }.slice()
    }

    private var glowChecked = false
    private var glowAvailable = false

    /**
     * @param noDepthTest true draws the lyric plane through walls (ESP style).
     */
    fun fontMaskQuads(noDepthTest: Boolean): RenderPipeline =
        if (noDepthTest) ClientRenderPipelines.FontMaskQuads else fontMaskOccluded ?: ClientRenderPipelines.FontMaskQuads

    fun glowQuads(noDepthTest: Boolean): RenderPipeline? {
        val candidate = if (noDepthTest) glowSeeThrough else glowOccluded
        if (candidate == null) return null
        if (!glowChecked) {
            glowChecked = true
            // Compiles once, on demand. A missing shader file must not throw on the render thread.
            glowAvailable = runCatching { RenderSystem.getCompiledPipelineNullable(candidate) != null }
                .onFailure { logger.warn("[Music] Lyric glow shader unavailable, world lyric SDF disabled.", it) }
                .getOrDefault(false)
        }
        return if (glowAvailable) candidate else null
    }

    /** Writes the glow parameters for this frame and returns the bound slice. */
    fun glowUniforms(intensity: Float, softness: Float, pulse: Float, color: Color4b, radius: Float = 3f): Map<String, GpuBufferSlice> {
        glowBuffer.writeStd140 {
            putVec4(intensity.coerceIn(0f, 2f), softness.coerceIn(0.05f, 8f), pulse.coerceIn(0f, 1f), radius.coerceIn(0.5f, 5f))
            putVec4(color.toVector4f())
        }
        return mapOf(GLOW_UNIFORM to glowBuffer)
    }

    /** True when real world occlusion is available. */
    val supportsOcclusion: Boolean get() = fontMaskOccluded != null

    /** Forces pipeline registration during client feature setup instead of the first world draw. */
    fun prewarm() {
        supportsOcclusion
    }
}
