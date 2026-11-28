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
@file:Suppress("LongMethod", "CognitiveComplexMethod", "LongParameterList", "MagicNumber")

package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.render.CachedMeshStorage
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.setPipeline
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.begin
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.LivingEntity
import org.joml.Matrix4f
import org.joml.Vector4f
import java.util.Optional
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Shared GPU Screen-Space Planar Mirror Reflection (`平滑光面无起伏 + 周围实体与方块隐约倒像`) engine.
 *
 * - Zero wave undulations (`N = (0, 1, 0)` strictly flat mirror plane).
 * - Renders ground-block top faces into an off-screen RGBA buffer while reading `mainTarget`'s
 *   rendered color & depth textures (zero feedback loop), marching exact planar mirror rays through
 *   3D depth to capture real inverted reflections (`倒像`) of surrounding entities and blocks.
 */
object MirrorGroundRenderer {

    private val CLEAR_ZERO = Vector4f(0f, 0f, 0f, 0f)

    private val surfaceMeshStorage = CachedMeshStorage("LB MirrorGround")
    private val overlayTarget = LazyRenderTargetHolder("${LiquidBounce.CLIENT_NAME} MirrorGround Overlay", useDepth = false)
    private val uboRing = ClientUniformDefine.MIRROR_GROUND.createRingBuffer()

    private val linearSampler by lazy { RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR) }
    private val nearestSampler by lazy { RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST) }

    private val viewProjMat = Matrix4f()
    private val invViewProjMat = Matrix4f()

    private var meshOriginX = 0.0
    private var meshOriginY = 0.0
    private var meshOriginZ = 0.0
    private var hasCachedTiles = false

    private var lastScanCamX = Double.NaN
    private var lastScanCamY = Double.NaN
    private var lastScanCamZ = Double.NaN
    private var lastScanTimeMs = 0L
    private var lastScanRadius = -1
    private var lastIncludeWater = true
    private var lastIncludeSlabs = true

    private var startNano = 0L
    private var shaderFailed = false

    private val entX = FloatArray(4)
    private val entY = FloatArray(4)
    private val entZ = FloatArray(4)
    private val entH = FloatArray(4)
    private val entR = FloatArray(4)
    private val entDistSq = FloatArray(4)

    fun resetState() {
        shaderFailed = false
        startNano = System.nanoTime()
    }

    fun clearCache() {
        surfaceMeshStorage.clearBuffers()
        overlayTarget.close()
        hasCachedTiles = false
        lastScanCamX = Double.NaN
    }

    fun closeResources() {
        clearCache()
        uboRing.close()
    }

    fun render(
        event: WorldRenderEvent,
        radius: Int,
        mirrorOpacity: Float,
        glossStrength: Float,
        fresnelStrength: Float,
        mirrorSoftness: Float,
        maxRayDistance: Float,
        includeWater: Boolean,
        includeSlabs: Boolean,
        glazeColor: Color4b,
    ) {
        if (shaderFailed) return
        val level = mc.level ?: return
        val mainTarget = event.renderTarget
        val fullW = mainTarget.width
        val fullH = mainTarget.height
        if (fullW <= 2 || fullH <= 2) return

        val sceneColorView = mainTarget.colorTextureView ?: return
        val sceneDepthView = mainTarget.depthTextureView ?: return

        val cameraState = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState
        val camPos = cameraState.pos
        val camX = camPos.x
        val camY = camPos.y
        val camZ = camPos.z

        // Skip surface glaze when the camera eye is submerged underwater
        val eyePos = BlockPos.containing(camX, camY, camZ)
        if (level.getFluidState(eyePos).`is`(FluidTags.WATER)) return

        val nowMs = System.currentTimeMillis()
        val movedSq = (camX - lastScanCamX) * (camX - lastScanCamX) +
            (camY - lastScanCamY) * (camY - lastScanCamY) +
            (camZ - lastScanCamZ) * (camZ - lastScanCamZ)

        if (movedSq.isNaN() || movedSq > 5.76 || nowMs - lastScanTimeMs > 420L ||
            radius != lastScanRadius || includeWater != lastIncludeWater || includeSlabs != lastIncludeSlabs
        ) {
            rebuildMirrorMesh(level, camX, camY, camZ, radius, includeWater, includeSlabs)
            lastScanCamX = camX
            lastScanCamY = camY
            lastScanCamZ = camZ
            lastScanTimeMs = nowMs
            lastScanRadius = radius
            lastIncludeWater = includeWater
            lastIncludeSlabs = includeSlabs
        }

        if (!hasCachedTiles || !surfaceMeshStorage.isReady) return

        try {
            if (startNano == 0L) startNano = System.nanoTime()
            val timeSec = ((System.nanoTime() - startNano) * 1.0e-9).toFloat()

            val projMat = cameraState.projectionMatrix
            viewProjMat.set(projMat).mul(cameraState.viewRotationMatrix)
            invViewProjMat.set(viewProjMat).invert()

            val zeroToOne = abs(projMat.m22()) < 0.25f
            val clipZScale = if (zeroToOne) 1f else 2f
            val clipZBias = if (zeroToOne) 0f else -1f
            val reversedZ = if (projMat.m32() > 0f) 1f else 0f

            val entityCount = gatherNearestEntities(level, camX, camY, camZ, radius.toDouble(), cameraState.isFirstPerson)

            val dayAngle = ((level.defaultClockTime % 24000L).toFloat() / 24000f) * (Math.PI.toFloat() * 2f)
            val sunElev = cos(dayAngle).coerceAtLeast(0.30f)
            val sunAzim = sin(dayAngle)
            val skyBright = (0.52f + 0.48f * ((cos(dayAngle) + 1f) * 0.5f)).coerceIn(0.42f, 1.0f)

            val originRelX = (meshOriginX - camX).toFloat()
            val originRelY = (meshOriginY - camY).toFloat()
            val originRelZ = (meshOriginZ - camZ).toFloat()

            uboRing.rotate()
            val uboSlice = uboRing.currentBuffer().slice()
            uboSlice.writeStd140 {
                putVec4(viewProjMat.m00(), viewProjMat.m01(), viewProjMat.m02(), viewProjMat.m03())
                putVec4(viewProjMat.m10(), viewProjMat.m11(), viewProjMat.m12(), viewProjMat.m13())
                putVec4(viewProjMat.m20(), viewProjMat.m21(), viewProjMat.m22(), viewProjMat.m23())
                putVec4(viewProjMat.m30(), viewProjMat.m31(), viewProjMat.m32(), viewProjMat.m33())
                putVec4(invViewProjMat.m00(), invViewProjMat.m01(), invViewProjMat.m02(), invViewProjMat.m03())
                putVec4(invViewProjMat.m10(), invViewProjMat.m11(), invViewProjMat.m12(), invViewProjMat.m13())
                putVec4(invViewProjMat.m20(), invViewProjMat.m21(), invViewProjMat.m22(), invViewProjMat.m23())
                putVec4(invViewProjMat.m30(), invViewProjMat.m31(), invViewProjMat.m32(), invViewProjMat.m33())
                putVec4(camX.toFloat(), camY.toFloat(), camZ.toFloat(), timeSec)
                putVec4(originRelX, originRelY, originRelZ, radius.toFloat())
                putVec4(clipZScale, clipZBias, reversedZ, maxRayDistance)
                putVec4(mirrorOpacity.coerceIn(0.02f, 0.90f), glossStrength, fresnelStrength, mirrorSoftness)
                putVec4(
                    (glazeColor.r and 255) / 255f,
                    (glazeColor.g and 255) / 255f,
                    (glazeColor.b and 255) / 255f,
                    (glazeColor.a and 255) / 255f,
                )
                putVec4(1f / fullW.toFloat(), 1f / fullH.toFloat(), entityCount.toFloat(), skyBright)
                putVec4(entX[0], entY[0], entZ[0], entH[0])
                putVec4(entX[1], entY[1], entZ[1], entH[1])
                putVec4(entX[2], entY[2], entZ[2], entH[2])
                putVec4(entX[3], entY[3], entZ[3], entH[3])
                putVec4(entR[0], entR[1], entR[2], entR[3])
                putVec4(sunAzim * 0.65f + 0.25f, sunElev, 0.38f, 0.75f)
            }

            val overlayFbo = overlayTarget.initAndGet(fullW, fullH)
            val overlayView = overlayFbo.colorTextureView ?: return

            // Pass 1: Trace exact zero-wave mirror reflections onto off-screen overlay target
            overlayFbo.createRenderPass(
                { "LB MirrorGround SSR" },
                clearColor = Optional.of(CLEAR_ZERO),
                useDepthAttachment = false,
            ).use { pass ->
                pass.setPipeline(ClientRenderPipelines.Reflection.MirrorGroundPass)
                pass.setUniform("SceneColorSampler", sceneColorView, linearSampler)
                pass.setUniform("SceneDepthSampler", sceneDepthView, nearestSampler)
                pass.setUniform(ClientUniformDefine.MIRROR_GROUND.uboName, uboSlice)
                surfaceMeshStorage.bindAndDraw(pass)
            }

            // Pass 2: Premultiplied translucent composite onto mainRenderTarget
            mainTarget.createRenderPass(
                { "LB MirrorGround Composite" },
                useDepthAttachment = false,
            ).use { pass ->
                pass.setPipeline(ClientRenderPipelines.Reflection.MirrorGroundComposite)
                pass.setUniform("MirrorSampler", overlayView, linearSampler)
                pass.draw(3, 1, 0, 0)
            }
        } catch (t: Throwable) {
            shaderFailed = true
            logger.warn("[MirrorGround] Mirror shader pass failed; disabling until re-enabled.", t)
        }
    }

    private fun gatherNearestEntities(
        level: ClientLevel,
        camX: Double,
        camY: Double,
        camZ: Double,
        maxRad: Double,
        isFirstPerson: Boolean,
    ): Int {
        for (i in 0 until 4) {
            entX[i] = 0f
            entY[i] = 0f
            entZ[i] = 0f
            entH[i] = 0f
            entR[i] = 0f
            entDistSq[i] = Float.MAX_VALUE
        }
        var count = 0
        val maxDistSq = ((maxRad + 3.0) * (maxRad + 3.0)).toFloat()
        val self = mc.player

        for (entity in level.entitiesForRendering()) {
            if (entity !is LivingEntity || !entity.isAlive || entity.isInvisible || entity.isSpectator) continue
            if (entity === self && isFirstPerson) continue

            val rx = (entity.x - camX).toFloat()
            val ry = (entity.y - camY).toFloat()
            val rz = (entity.z - camZ).toFloat()
            val dSq = rx * rx + rz * rz
            if (dSq > maxDistSq) continue

            var insertIdx = -1
            if (count < 4) {
                insertIdx = count
                count++
            } else if (dSq < entDistSq[3]) {
                insertIdx = 3
            }

            if (insertIdx >= 0) {
                var pos = insertIdx
                while (pos > 0 && dSq < entDistSq[pos - 1]) {
                    entX[pos] = entX[pos - 1]
                    entY[pos] = entY[pos - 1]
                    entZ[pos] = entZ[pos - 1]
                    entH[pos] = entH[pos - 1]
                    entR[pos] = entR[pos - 1]
                    entDistSq[pos] = entDistSq[pos - 1]
                    pos--
                }
                entX[pos] = rx
                entY[pos] = ry
                entZ[pos] = rz
                entH[pos] = entity.bbHeight.coerceIn(0.4f, 3.2f)
                entR[pos] = (entity.bbWidth * 0.55f).coerceIn(0.22f, 1.2f)
                entDistSq[pos] = dSq
            }
        }
        return count
    }

    private fun rebuildMirrorMesh(
        level: ClientLevel,
        camX: Double,
        camY: Double,
        camZ: Double,
        rad: Int,
        includeWater: Boolean,
        includeSlabs: Boolean,
    ) {
        val cx = floor(camX).toInt()
        val cy = floor(camY).toInt()
        val cz = floor(camZ).toInt()
        val gridDim = rad * 2 + 3
        val half = rad + 1

        val surfHeights = FloatArray(gridDim * gridDim) { Float.NaN }
        val isWaterGrid = BooleanArray(gridDim * gridDim)
        val tintGrid = IntArray(gridDim * gridDim) { -1 }
        val columnTopSolidY = FloatArray(gridDim * gridDim) { Float.NaN }
        val columnMapRgb = IntArray(gridDim * gridDim) { 0x6A7A8C }

        val minScanY = max(level.minY, cy - 12)
        val maxScanY = min(level.maxY - 1, cy + 5)
        val mPos = BlockPos.MutableBlockPos()
        val abovePos = BlockPos.MutableBlockPos()
        val radSq = rad * rad

        for (gx in 0 until gridDim) {
            val dx = gx - half
            val wx = cx + dx
            for (gz in 0 until gridDim) {
                val dz = gz - half
                if (dx * dx + dz * dz > (rad + 2) * (rad + 2)) continue
                val wz = cz + dz
                val idx = gx * gridDim + gz
                var recordedTopSolid = false

                for (y in maxScanY downTo minScanY) {
                    mPos.set(wx, y, wz)
                    val fluid = level.getFluidState(mPos)
                    if (includeWater && fluid.`is`(FluidTags.WATER)) {
                        abovePos.set(wx, y + 1, wz)
                        if (!level.getFluidState(abovePos).`is`(FluidTags.WATER)) {
                            val h = fluid.getHeight(level, mPos).coerceIn(0.1f, 1.0f)
                            val surfY = y + h
                            if (surfY < camY - 0.08) {
                                surfHeights[idx] = surfY
                                isWaterGrid[idx] = true
                                val biomeCol = BiomeColors.getAverageWaterColor(level, mPos)
                                tintGrid[idx] = (0xFF shl 24) or (biomeCol and 0x00FFFFFF)
                                break
                            }
                        }
                        continue
                    }

                    val state = level.getBlockState(mPos)
                    if (state.isAir) continue

                    val shape = state.getCollisionShape(level, mPos)
                    if (!shape.isEmpty) {
                        val topLocalY = shape.max(Direction.Axis.Y)
                        if (!recordedTopSolid) {
                            recordedTopSolid = true
                            columnTopSolidY[idx] = (y + topLocalY).toFloat()
                            val mapCol = state.getMapColor(level, mPos).col
                            if (mapCol != 0) {
                                columnMapRgb[idx] = mapCol and 0x00FFFFFF
                            }
                        }

                        val fullTopFace = if (includeSlabs) {
                            val spanX = shape.max(Direction.Axis.X) - shape.min(Direction.Axis.X)
                            val spanZ = shape.max(Direction.Axis.Z) - shape.min(Direction.Axis.Z)
                            spanX >= 0.98 && spanZ >= 0.98 && topLocalY in 0.05..1.0
                        } else {
                            state.isFaceSturdy(level, mPos, Direction.UP)
                        }

                        if (fullTopFace) {
                            abovePos.set(wx, y + 1, wz)
                            val upState = level.getBlockState(abovePos)
                            val clearAbove = topLocalY < 0.99 || (!upState.isSolidRender && upState.fluidState.isEmpty)
                            if (clearAbove) {
                                val surfY = (y + topLocalY).toFloat()
                                if (surfY < camY - 0.08) {
                                    surfHeights[idx] = surfY
                                    isWaterGrid[idx] = false
                                    tintGrid[idx] = (0xFF shl 24) or columnMapRgb[idx]
                                    break
                                }
                            }
                        }
                    }

                    if (state.isSolidRender) {
                        break
                    }
                }
            }
        }

        val pipeline = ClientRenderPipelines.Reflection.MirrorGroundPass
        surfaceMeshStorage.byteBufferBuilder.clear()
        val builder = surfaceMeshStorage.byteBufferBuilder.begin(pipeline)

        val origX = cx.toDouble()
        val origY = cy.toDouble()
        val origZ = cz.toDouble()
        var emitted = 0

        fun cornerWallInfo(gxCorner: Int, gzCorner: Int, surfY: Float, fallbackArgb: Int): Pair<Float, Int> {
            var wallFactor = 0f
            var accumR = 0
            var accumG = 0
            var accumB = 0
            var samples = 0
            for (ox in -1..1) {
                val nx = gxCorner + ox
                if (nx !in 0 until gridDim) continue
                for (oz in -1..1) {
                    val nz = gzCorner + oz
                    if (nz !in 0 until gridDim) continue
                    val nIdx = nx * gridDim + nz
                    val topY = columnTopSolidY[nIdx]
                    if (!topY.isNaN()) {
                        val rise = topY - surfY
                        if (rise > 0.35f) {
                            val distW = if (ox == 0 || oz == 0) 0.34f else 0.20f
                            wallFactor += (rise / 3.2f).coerceIn(0f, 1f) * distW
                            val rgb = columnMapRgb[nIdx]
                            accumR += (rgb shr 16) and 0xFF
                            accumG += (rgb shr 8) and 0xFF
                            accumB += rgb and 0xFF
                            samples++
                        }
                    }
                }
            }
            val argb = if (samples > 0) {
                (0xFF shl 24) or
                    ((accumR / samples).coerceIn(0, 255) shl 16) or
                    ((accumG / samples).coerceIn(0, 255) shl 8) or
                    (accumB / samples).coerceIn(0, 255)
            } else {
                fallbackArgb
            }
            return wallFactor.coerceIn(0f, 1f) to argb
        }

        for (gx in 1 until gridDim - 1) {
            val dx = gx - half
            for (gz in 1 until gridDim - 1) {
                val dz = gz - half
                if (dx * dx + dz * dz > radSq) continue
                val idx = gx * gridDim + gz
                val surfY = surfHeights[idx]
                if (surfY.isNaN()) continue

                val ry = (surfY + 0.002f - origY.toFloat())
                val x0 = dx.toFloat()
                val x1 = x0 + 1f
                val z0 = dz.toFloat()
                val z1 = z0 + 1f

                val waterFlag = if (isWaterGrid[idx]) 2.0f else 0.0f
                val baseArgb = tintGrid[idx]

                val (w00, c00) = cornerWallInfo(gx, gz, surfY, baseArgb)
                val (w01, c01) = cornerWallInfo(gx, gz + 1, surfY, baseArgb)
                val (w11, c11) = cornerWallInfo(gx + 1, gz + 1, surfY, baseArgb)
                val (w10, c10) = cornerWallInfo(gx + 1, gz, surfY, baseArgb)

                builder.addVertex(x0, ry, z0).setUv(waterFlag, w00).setColor(c00).setUv1(0, 0).setUv2(0, 0).setLineWidth(1f)
                builder.addVertex(x0, ry, z1).setUv(waterFlag, w01).setColor(c01).setUv1(0, 0).setUv2(0, 0).setLineWidth(1f)
                builder.addVertex(x1, ry, z1).setUv(waterFlag, w11).setColor(c11).setUv1(0, 0).setUv2(0, 0).setLineWidth(1f)
                builder.addVertex(x1, ry, z0).setUv(waterFlag, w10).setColor(c10).setUv1(0, 0).setUv2(0, 0).setLineWidth(1f)
                emitted++
            }
        }

        val meshData = builder.build()
        if (emitted == 0 || meshData == null) {
            meshData?.close()
            surfaceMeshStorage.clearStates()
            hasCachedTiles = false
            return
        }

        try {
            surfaceMeshStorage.uploadAndSet(meshData, pipeline)
            meshOriginX = origX
            meshOriginY = origY
            meshOriginZ = origZ
            hasCachedTiles = true
        } finally {
            meshData.close()
        }
    }
}
