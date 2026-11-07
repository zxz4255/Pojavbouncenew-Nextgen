package net.ccbluex.liquidbounce.features.module.modules.render.shadersky

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.ClientRenderPipelines.withUniformBuffer
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.utils.client.clientStartDurationMs
import net.ccbluex.liquidbounce.utils.kotlin.optional
import net.ccbluex.liquidbounce.utils.render.writeStd140
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.renderer.fog.FogData
import net.minecraft.client.renderer.state.level.SkyRenderState
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.material.FogType
import org.joml.Vector3f
import java.nio.file.Files
import kotlin.math.min
import kotlin.math.tan

/** Drawn in the vanilla world sky pass, before terrain, never as a HUD overlay. */
object ModuleShaderSky : ClientModule("ShaderSky", ModuleCategories.RENDER) {
    private val shaderName by text("Shader", "black_aurora")
    private val autoReload by boolean("AutoReload", true)
    private val reloadKey by key("ReloadKey", InputConstants.KEY_F8)
    private val aurora by float("Aurora", 0.8f, 0f..2f)
    private val speed by float("Speed", 1f, 0f..3f)
    private val haze by float("HorizonHaze", 0.85f, 0f..2f)
    private val exposure by float("Exposure", 1f, 0.1f..2f)
    private val worldFog by boolean("WorldFog", true)
    private val fogStart by float("FogStart", 24f, 0f..256f)
    private val fogEnd by float("FogEnd", 144f, 16f..512f)
    private val fogGray by float("FogGray", 0.075f, 0.01f..0.3f)
    private val directory get() = ConfigSystem.rootFolder.toPath().resolve("shaders/sky")
    private val loader by lazy {
        ExternalShaderLoader(directory) { location, vertex, fragment ->
            RenderPipeline.Builder()
                .withLocation(location)
                .withVertexShader(vertex)
                .withFragmentShader(fragment)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withUniformBuffer(ClientUniformDefine.SHADER_SKY)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(optional())
                .withCull(false)
                .build()
        }
    }
    private val uniforms by lazy {
        ClientUniformDefine.SHADER_SKY.createRingBuffer { "External sky uniforms" }
    }
    private var failedDraw = false

    override fun onEnabled() {
        runCatching {
            Files.createDirectories(directory)
            for (extension in listOf("vsh", "fsh")) {
                val name = "black_aurora.$extension"
                val target = directory.resolve(name)
                if (!Files.exists(target)) {
                    requireNotNull(javaClass.getResourceAsStream("/resources/liquidbounce/shaders/sky/$name"))
                        .use { Files.copy(it, target) }
                }
            }
            failedDraw = false
            loader.poll(shaderName, true)
        }.onFailure { logger.error("Cannot install default external sky shaders", it) }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (autoReload) loader.poll(shaderName)
    }
    @Suppress("unused")
    private val reloadHandler = handler<KeyboardKeyEvent> { event ->
        if (event.isPressed && event.key == reloadKey && mc.gui.screen() == null) {
            failedDraw = false
            loader.poll(shaderName, true)
        }
    }

    private fun usable(): Boolean {
        if (!running || failedDraw || loader.pipeline == null) return false
        val level = mc.level ?: return false
        if (level.dimensionType().skybox() != DimensionType.Skybox.OVERWORLD) return false
        val camera = mc.gameRenderer.mainCamera()
        if (camera.fluidInCamera != FogType.NONE) return false
        val player = mc.player ?: return false
        return !player.hasEffect(MobEffects.BLINDNESS) && !player.hasEffect(MobEffects.DARKNESS)
    }

    /** Return true only after a successful draw; otherwise let vanilla render its sky. */
    fun drawSky(target: RenderTarget, state: SkyRenderState): Boolean {
        if (!usable() || state.skybox != DimensionType.Skybox.OVERWORLD) return false
        val pipeline = loader.pipeline ?: return false
        val view = target.colorTextureView ?: return false
        if (target.width <= 0 || target.height <= 0) return false
        return runCatching {
            val camera = mc.gameRenderer.mainCamera()
            val orientation = camera.rotation()
            val right = Vector3f(1f, 0f, 0f).rotate(orientation)
            val up = Vector3f(0f, 1f, 0f).rotate(orientation)
            val forward = Vector3f(0f, 0f, -1f).rotate(orientation)
            val tangent = tan(Math.toRadians(camera.fov.toDouble().coerceIn(1.0, 179.0)) * 0.5).toFloat()
            uniforms.rotate()
            val slice = uniforms.currentBuffer().slice()
            slice.writeStd140 {
                putVec4(right.x, right.y, right.z, clientStartDurationMs / 1000f)
                putVec4(up.x, up.y, up.z, target.width.toFloat() / target.height)
                putVec4(forward.x, forward.y, forward.z, tangent)
                putVec4(fogGray, fogGray, fogGray, 1f)
                putVec4(aurora, speed, haze, exposure)
            }
            view.createRenderPass({ "External black aurora sky" }).use { pass ->
                pass.setPipeline(pipeline)
                pass.setUniform(ClientUniformDefine.SHADER_SKY.uboName, slice)
                pass.draw(3, 1, 0, 0)
            }
            true
        }.getOrElse {
            failedDraw = true
            logger.error("Sky draw failed; reverting to vanilla. Press reload key after correcting the shader.", it)
            false
        }
    }

    /** Actual terrain fog, in addition to the horizon mist drawn by the fragment program. */
    fun modifyFog(fog: FogData) {
        if (!worldFog || !usable()) return
        val end = min(fogEnd, fog.renderDistanceEnd.coerceAtLeast(16f)).coerceAtLeast(1f)
        val start = fogStart.coerceIn(0f, end - 0.5f)
        fog.color.set(fogGray, fogGray, fogGray, 1f)
        fog.renderDistanceStart = start
        fog.renderDistanceEnd = end
        fog.environmentalStart = start
        fog.environmentalEnd = end
        fog.skyEnd = end
        fog.cloudEnd = end
    }
}
