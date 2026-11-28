/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
@file:Suppress("MagicNumber", "LongMethod", "CognitiveComplexMethod")

package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Axis
import com.mojang.renderpearl.api.textures.FilterMode
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.drawGradientCircle
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironment
import net.ccbluex.liquidbounce.render.setPipeline
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.client.Camera
import net.minecraft.client.renderer.fog.FogData
import net.minecraft.world.level.material.FogType
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ambient Atmosphere (`环境氛围`) — full-world & full-block artistic stylization engine:
 * - [AtmosphereStyle.FUTURE] (`未来样式`): Cyberpunk Electric Blue, Neon Purple & Matrix Green over a dark desaturated gunmetal base across blocks, sky, and fog.
 * - [AtmosphereStyle.MILLENNIUM] (`千禧年样式`): High-key luminous pure white primary + soft pale cream-yellow secondary with dreamy Y2K halation/bloom.
 * - [AtmosphereStyle.SKETCH] (`素描样式`): Monochrome black-and-white hand-drawn pencil/ink outlines + multi-angle cross-hatching over all blocks and terrain.
 */
object ModuleAmbientAtmosphere : ClientModule(
    name = "AmbientAtmosphere",
    category = ModuleCategories.RENDER,
    aliases = listOf("Atmosphere", "Cyberpunk", "Millennium", "Sketch", "环境氛围"),
) {

    enum class AtmosphereStyle(override val tag: String, val shaderIndex: Float) : Tagged {
        FUTURE("Future", 0f),
        MILLENNIUM("Millennium", 1f),
        SKETCH("Sketch", 2f),
    }

    private val style by enumChoice("Style", AtmosphereStyle.FUTURE)
    private val intensity by int("Intensity", 100, 15..100, "%")
    private val edgeStrength by float("EdgeDetail", 1.15f, 0.2f..2.5f)
    private val glowStrength by float("BloomGlow", 1.10f, 0.0f..2.5f)
        .visibleWhen { style != AtmosphereStyle.SKETCH }
    private val cyberDarkness by int("BaseDarkness", 65, 15..95, "%")
        .visibleWhen { style == AtmosphereStyle.FUTURE }
    private val sketchHatchDensity by int("HatchDensity", 55, 10..100, "%")
        .visibleWhen { style == AtmosphereStyle.SKETCH }
    private val worldFog by boolean("WorldFog", true)
    private val fogDistance by float("FogDistance", 68f, 16f..220f)
        .visibleWhen { worldFog }
    private val floatingMotes by boolean("AmbientMotes", true)
        .visibleWhen { style != AtmosphereStyle.SKETCH }

    // Customizable palette overrides per style
    private val futureBlue by color("CyberBlue", Color4b(0x00, 0xD8, 0xFF, 0xFF))
        .visibleWhen { style == AtmosphereStyle.FUTURE }
    private val futurePurple by color("CyberPurple", Color4b(0xA8, 0x38, 0xFF, 0xFF))
        .visibleWhen { style == AtmosphereStyle.FUTURE }
    private val futureGreen by color("CyberGreen", Color4b(0x00, 0xFF, 0x9C, 0xFF))
        .visibleWhen { style == AtmosphereStyle.FUTURE }

    private val millenniumWhite by color("DreamWhite", Color4b(0xFF, 0xFF, 0xFF, 0xFF))
        .visibleWhen { style == AtmosphereStyle.MILLENNIUM }
    private val millenniumYellow by color("DreamPaleYellow", Color4b(0xFF, 0xF3, 0xB2, 0xFF))
        .visibleWhen { style == AtmosphereStyle.MILLENNIUM }

    private val sketchPaper by color("PaperColor", Color4b(0xF6, 0xF5, 0xEF, 0xFF))
        .visibleWhen { style == AtmosphereStyle.SKETCH }
    private val sketchInk by color("InkColor", Color4b(0x14, 0x15, 0x19, 0xFF))
        .visibleWhen { style == AtmosphereStyle.SKETCH }

    private val outputTarget = LazyRenderTargetHolder("${LiquidBounce.CLIENT_NAME} Atmosphere Post", useDepth = false)
    private val uboRing = ClientUniformDefine.ATMOSPHERE.createRingBuffer()
    private val linearSampler by lazy { RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR) }
    private val nearestSampler by lazy { RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST) }

    private var startNano = 0L
    private var shaderDisabled = false

    override val tag: String
        get() = style.tag

    override fun onEnabled() {
        shaderDisabled = false
        startNano = System.nanoTime()
    }

    fun appliesToWorld(camera: Camera): Boolean =
        running && mc.level != null && camera.fluidInCamera == FogType.NONE

    /**
     * Harmonizes distant terrain/sky fog with the selected atmosphere style.
     */
    fun modifyFog(fog: FogData, camera: Camera) {
        if (!worldFog || !appliesToWorld(camera)) return
        val blend = (intensity / 100f).coerceIn(0f, 1f)
        val viewFar = fog.renderDistanceEnd.coerceAtLeast(8f)
        val far = min(fogDistance, viewFar).coerceAtLeast(8f)
        val near = (far * when (style) {
            AtmosphereStyle.FUTURE -> 0.22f
            AtmosphereStyle.MILLENNIUM -> 0.16f
            AtmosphereStyle.SKETCH -> 0.35f
        }).coerceIn(1f, far - 2f)

        val targetR: Float
        val targetG: Float
        val targetB: Float
        when (style) {
            AtmosphereStyle.FUTURE -> {
                targetR = 0.08f
                targetG = 0.07f
                targetB = 0.16f
            }
            AtmosphereStyle.MILLENNIUM -> {
                targetR = 1.00f
                targetG = 0.99f
                targetB = 0.93f
            }
            AtmosphereStyle.SKETCH -> {
                targetR = (sketchPaper.r and 255) / 255f
                targetG = (sketchPaper.g and 255) / 255f
                targetB = (sketchPaper.b and 255) / 255f
            }
        }

        fog.color.set(
            fog.color.x + (targetR - fog.color.x) * blend,
            fog.color.y + (targetG - fog.color.y) * blend,
            fog.color.z + (targetB - fog.color.z) * blend,
            1f,
        )
        fog.environmentalStart = near
        fog.environmentalEnd = far
        fog.renderDistanceStart = near
        fog.renderDistanceEnd = far
        fog.skyEnd = far
        fog.cloudEnd = far
    }

    /**
     * Full-screen GPU post-processing pass invoked from `MixinGameRenderer` right after the 3D world
     * (blocks, terrain, sky, entities, first-person hand) finishes rendering and right before GUI/HUD.
     */
    fun applyPostEffect() {
        if (!running || shaderDisabled || mc.level == null || mc.player == null) return
        val mainTarget = mc.gameRenderer.mainRenderTarget()
        val w = mainTarget.width
        val h = mainTarget.height
        if (w <= 0 || h <= 0) return

        val colorView = mainTarget.colorTextureView ?: return
        val depthView = mainTarget.depthTextureView ?: return

        try {
            if (startNano == 0L) startNano = System.nanoTime()
            val timeSec = ((System.nanoTime() - startNano) * 1.0e-9).toFloat()

            val colA: Color4b
            val colB: Color4b
            val colC: Color4b
            val subVal: Float
            when (style) {
                AtmosphereStyle.FUTURE -> {
                    colA = futureBlue
                    colB = futurePurple
                    colC = futureGreen
                    subVal = cyberDarkness / 100f
                }
                AtmosphereStyle.MILLENNIUM -> {
                    colA = millenniumWhite
                    colB = millenniumYellow
                    colC = millenniumWhite
                    subVal = 0.5f
                }
                AtmosphereStyle.SKETCH -> {
                    colA = sketchPaper
                    colB = sketchInk
                    colC = sketchInk
                    subVal = sketchHatchDensity / 100f
                }
            }

            uboRing.rotate()
            val uboSlice = uboRing.currentBuffer().slice()
            uboSlice.writeStd140 {
                putVec4(style.shaderIndex, intensity / 100f, timeSec, edgeStrength)
                putVec4(1f / w.toFloat(), 1f / h.toFloat(), glowStrength, subVal)
                putVec4((colA.r and 255) / 255f, (colA.g and 255) / 255f, (colA.b and 255) / 255f, 1f)
                putVec4((colB.r and 255) / 255f, (colB.g and 255) / 255f, (colB.b and 255) / 255f, 1f)
                putVec4((colC.r and 255) / 255f, (colC.g and 255) / 255f, (colC.b and 255) / 255f, 1f)
            }

            val outFbo = outputTarget.initAndGet(w, h)
            val outView = outFbo.colorTextureView ?: return

            // Pass 1: Stylize entire 3D world & blocks into outputTarget
            outFbo.createRenderPass({ "AmbientAtmosphere Stylize" }, useDepthAttachment = false).use { pass ->
                pass.setPipeline(ClientRenderPipelines.AtmospherePost)
                pass.setUniform("texture0", colorView, linearSampler)
                pass.setUniform("DepthSampler", depthView, nearestSampler)
                pass.setUniform(ClientUniformDefine.ATMOSPHERE.uboName, uboSlice)
                pass.draw(3, 1, 0, 0)
            }

            // Pass 2: Copy stylized frame back to mainRenderTarget (preserving depth attachment untouched)
            mainTarget.createRenderPass({ "AmbientAtmosphere Blit" }, useDepthAttachment = false).use { pass ->
                pass.setPipeline(ClientRenderPipelines.AtmosphereBlit)
                pass.setUniform("texture0", outView, nearestSampler)
                pass.draw(3, 1, 0, 0)
            }
        } catch (t: Throwable) {
            shaderDisabled = true
            logger.warn("[AmbientAtmosphere] Post shader pass failed; disabling until re-enabled.", t)
        }
    }

    fun closeResources() {
        outputTarget.close()
        uboRing.close()
    }

    private fun cellHash(x: Int, y: Int, z: Int, salt: Int): Float {
        var v = x * 73428767 xor y * 912931 xor z * 4382891 xor salt * 19349663
        v = (v xor (v ushr 16)) * 0x45d9f3b
        v = (v xor (v ushr 16)) * 0x45d9f3b
        v = v xor (v ushr 16)
        return (v ushr 8) / 16777215f
    }

    @Suppress("unused")
    private val worldMotesHandler = handler<WorldRenderEvent> { event ->
        if (!floatingMotes || style == AtmosphereStyle.SKETCH || !appliesToWorld(event.camera)) return@handler
        if (startNano == 0L) startNano = System.nanoTime()
        val time = ((System.nanoTime() - startNano) * 1.0e-9).toFloat()

        val pos = event.camera.position()
        val cellSize = 6.0
        val cx = floor(pos.x / cellSize).toInt()
        val cy = floor(pos.y / cellSize).toInt()
        val cz = floor(pos.z / cellSize).toInt()
        val blend = (intensity / 100f).coerceIn(0f, 1f)

        event.renderEnvironment {
            for (ix in cx - 4..cx + 4) {
                for (iy in cy - 3..cy + 3) {
                    for (iz in cz - 4..cz + 4) {
                        if (cellHash(ix, iy, iz, 0) > 0.36f) continue
                        val phase = cellHash(ix, iy, iz, 4) * 6.283f
                        val px = (ix + cellHash(ix, iy, iz, 1)) * cellSize + sin(time * 0.7f + phase) * 0.45
                        val py = (iy + cellHash(ix, iy, iz, 2)) * cellSize + ((time * 0.35 + phase) % cellSize) - cellSize * 0.5
                        val pz = (iz + cellHash(ix, iy, iz, 3)) * cellSize + sin(time * 0.6f + phase * 1.3f) * 0.45

                        val dx = px - pos.x
                        val dy = py - pos.y
                        val dz = pz - pos.z
                        val dist = sqrt(dx * dx + dy * dy + dz * dz).toFloat()
                        if (dist < 1.8f || dist > 26f) continue

                        val fadeIn = ((dist - 1.8f) / 3.5f).coerceIn(0f, 1f)
                        val fadeOut = ((26f - dist) / 7f).coerceIn(0f, 1f)
                        val pulse = 0.65f + 0.35f * sin(time * 2.4f + phase)
                        val alpha = (145f * blend * fadeIn * fadeOut * pulse).roundToInt().coerceIn(0, 220)
                        if (alpha <= 2) continue

                        val baseCol = if (style == AtmosphereStyle.FUTURE) {
                            when (((ix + iy + iz) and 3)) {
                                0 -> futureBlue
                                1 -> futurePurple
                                2 -> futureGreen
                                else -> futureBlue
                            }
                        } else {
                            if (((ix + iz) and 1) == 0) millenniumWhite else millenniumYellow
                        }
                        val radius = if (style == AtmosphereStyle.FUTURE) 0.16f else 0.24f

                        withPositionRelativeToCamera(px, py, pz) {
                            poseStack.withPush {
                                rotate(event.camera.rotation())
                                rotate(Axis.XP.rotationDegrees(90f))
                                drawGradientCircle(
                                    radius,
                                    0f,
                                    Color4b(baseCol.r, baseCol.g, baseCol.b, 0),
                                    Color4b(baseCol.r, baseCol.g, baseCol.b, alpha),
                                    noDepthTest = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
