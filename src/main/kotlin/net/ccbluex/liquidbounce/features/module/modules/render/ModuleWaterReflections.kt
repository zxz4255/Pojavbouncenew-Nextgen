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
@file:Suppress("LongMethod", "CognitiveComplexMethod", "TooManyFunctions", "MagicNumber")

package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.DisconnectEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.CachedMeshStorage
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.bindDynamicTransformsUniform
import net.ccbluex.liquidbounce.render.bindProjectionUniform
import net.ccbluex.liquidbounce.render.buffers.CachedUniform
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getDynamicTransformsUniform
import net.ccbluex.liquidbounce.render.setPipeline
import net.ccbluex.liquidbounce.utils.render.begin
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.LivingEntity
import org.joml.Matrix4f
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ultra-lightweight single-pass smooth glossy highlight & pseudo-reflection ground/water renderer
 * (`ModuleWaterReflections`).
 *
 * Zero off-screen FBOs, zero multi-pass blurs, zero block/entity scene re-rendering, and static
 * VBO reuse (`CachedMeshStorage`) across frames:
 * - Silky-smooth Schlick-Fresnel sky sheen & dual-lobe specular highlight ("平滑亮光").
 * - Per-vertex obstacle/shoreline pseudo-shadow + analytic nearby-entity mirrored capsule
 *   pseudo-reflection & view-parallax shimmer ("伪影").
 */
object ModuleWaterReflections : ClientModule("WaterReflections", ModuleCategories.RENDER) {

    enum class SurfaceMode(override val tag: String) : Tagged {
        WATER_AND_GROUND("WaterAndGround"),
        WATER_AND_PUDDLES("WaterAndPuddles"),
        WATER_ONLY("WaterOnly"),
        WET_GROUND("WetGround"),
        MIRROR_GROUND("MirrorGround"),
    }

    private val surfaceMode by enumChoice("SurfaceMode", SurfaceMode.WATER_AND_GROUND)
    private val radius by int("Radius", 16, 6..32, "blocks")

    private val glossStrength by float("GlossStrength", 0.78f, 0.0f..1.5f)
    private val fresnelStrength by float("Fresnel", 0.80f, 0.0f..1.0f)
    private val smoothness by float("Smoothness", 0.82f, 0.1f..1.0f)
    private val pseudoShadowStrength by float("PseudoReflection", 0.56f, 0.0f..1.0f)
    private val pseudoShimmer by float("PseudoShimmer", 0.35f, 0.0f..1.0f)
        .visibleWhen { surfaceMode != SurfaceMode.MIRROR_GROUND }
    private val entityPseudoShadow by boolean("EntityPseudoReflection", true)
    private val reflectSelf by boolean("SelfPseudoReflection", true)

    private val subtleWave by float("SubtleWave", 0.16f, 0.0f..1.0f)
        .visibleWhen { surfaceMode != SurfaceMode.MIRROR_GROUND }
    private val puddleScale by float("PuddleScale", 0.30f, 0.08f..1.0f)
        .visibleWhen { surfaceMode == SurfaceMode.WATER_AND_PUDDLES }

    private val highlightColor by color("HighlightColor", Color4b(226, 240, 255, 58))
    private val waterTint by color("WaterTint", Color4b(74, 152, 224, 48))

    val isUsingMirrorMode: Boolean
        get() = running && surfaceMode == SurfaceMode.MIRROR_GROUND

    private val surfaceMeshStorage = CachedMeshStorage("LB SmoothWaterGround")

    private data class WaterReflectionUniformKey(
        val camX: Float,
        val camY: Float,
        val camZ: Float,
        val timeSeconds: Float,
        val originRelX: Float,
        val originRelY: Float,
        val originRelZ: Float,
        val maxRadius: Float,
        val glossStrength: Float,
        val fresnelStrength: Float,
        val smoothness: Float,
        val pseudoShadowStrength: Float,
        val subtleWave: Float,
        val puddleMode: Float,
        val puddleScale: Float,
        val entityCount: Float,
        val hiR: Float,
        val hiG: Float,
        val hiB: Float,
        val filmAlpha: Float,
        val tintR: Float,
        val tintG: Float,
        val tintB: Float,
        val pseudoShimmer: Float,
        val sunX: Float,
        val sunY: Float,
        val sunZ: Float,
        val skyBrightness: Float,
        val e0x: Float, val e0y: Float, val e0z: Float, val e0h: Float,
        val e1x: Float, val e1y: Float, val e1z: Float, val e1h: Float,
        val e2x: Float, val e2y: Float, val e2z: Float, val e2h: Float,
        val e3x: Float, val e3y: Float, val e3z: Float, val e3h: Float,
        val e4x: Float, val e4y: Float, val e4z: Float, val e4h: Float,
        val e5x: Float, val e5y: Float, val e5z: Float, val e5h: Float,
        val r0: Float, val r1: Float, val r2: Float, val r3: Float,
        val r4: Float, val r5: Float,
    )

    private val reflectionUniform = CachedUniform<WaterReflectionUniformKey>(ClientUniformDefine.WATER_REFLECTION) { u ->
        putVec4(u.camX, u.camY, u.camZ, u.timeSeconds)
        putVec4(u.originRelX, u.originRelY, u.originRelZ, u.maxRadius)
        putVec4(u.glossStrength, u.fresnelStrength, u.smoothness, u.pseudoShadowStrength)
        putVec4(u.subtleWave, u.puddleMode, u.puddleScale, u.entityCount)
        putVec4(u.hiR, u.hiG, u.hiB, u.filmAlpha)
        putVec4(u.tintR, u.tintG, u.tintB, u.pseudoShimmer)
        putVec4(u.sunX, u.sunY, u.sunZ, u.skyBrightness)
        putVec4(u.e0x, u.e0y, u.e0z, u.e0h)
        putVec4(u.e1x, u.e1y, u.e1z, u.e1h)
        putVec4(u.e2x, u.e2y, u.e2z, u.e2h)
        putVec4(u.e3x, u.e3y, u.e3z, u.e3h)
        putVec4(u.e4x, u.e4y, u.e4z, u.e4h)
        putVec4(u.e5x, u.e5y, u.e5z, u.e5h)
        putVec4(u.r0, u.r1, u.r2, u.r3)
        putVec4(u.r4, u.r5, 0f, 0f)
    }

    private var meshOriginX = 0.0
    private var meshOriginY = 0.0
    private var meshOriginZ = 0.0
    private var hasCachedTiles = false

    private var lastScanCamX = Double.NaN
    private var lastScanCamY = Double.NaN
    private var lastScanCamZ = Double.NaN
    private var lastScanTimeMs = 0L
    private var lastScanMode: SurfaceMode? = null
    private var lastScanRadius = -1
    private val startNano = System.nanoTime()

    // Scratch arrays for up to 6 nearest entity pseudo-reflections (zero per-frame list allocations)
    private val entX = FloatArray(6)
    private val entY = FloatArray(6)
    private val entZ = FloatArray(6)
    private val entH = FloatArray(6)
    private val entR = FloatArray(6)
    private val entDistSq = FloatArray(6)

    private fun clearCache() {
        surfaceMeshStorage.clearBuffers()
        hasCachedTiles = false
        lastScanCamX = Double.NaN
        if (!ModuleMirrorGround.running) {
            MirrorGroundRenderer.clearCache()
        }
    }

    override fun onEnabled() {
        MirrorGroundRenderer.resetState()
    }

    override fun onDisabled() {
        clearCache()
    }

    @Suppress("unused")
    private val disconnectHandler = handler<DisconnectEvent> {
        clearCache()
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        clearCache()
    }

    @Suppress("unused")
    private val worldRenderHandler = handler<WorldRenderEvent> { event ->
        val level = mc.level ?: return@handler
        val mainTarget = event.renderTarget
        if (mainTarget.width <= 2 || mainTarget.height <= 2) return@handler

        val levelState = mc.gameRenderer.gameRenderState().levelRenderState
        val cameraState = levelState.cameraRenderState
        val camPos = cameraState.pos
        val camX = camPos.x
        val camY = camPos.y
        val camZ = camPos.z

        // Skip surface sheen when camera eye itself is submerged underwater
        val eyePos = BlockPos.containing(camX, camY, camZ)
        if (level.getFluidState(eyePos).`is`(FluidTags.WATER)) return@handler

        val nowMs = System.currentTimeMillis()
        val movedSq = (camX - lastScanCamX) * (camX - lastScanCamX) +
            (camY - lastScanCamY) * (camY - lastScanCamY) +
            (camZ - lastScanCamZ) * (camZ - lastScanCamZ)
        val mode = surfaceMode
        val rad = radius

        if (mode == SurfaceMode.MIRROR_GROUND) {
            if (!ModuleMirrorGround.running) {
                MirrorGroundRenderer.render(
                    event = event,
                    radius = rad,
                    mirrorOpacity = (pseudoShadowStrength * 0.65f).coerceIn(0.08f, 0.85f),
                    glossStrength = glossStrength,
                    fresnelStrength = fresnelStrength,
                    mirrorSoftness = (1.15f - smoothness).coerceIn(0.15f, 1.5f),
                    maxRayDistance = max(14f, rad * 1.35f),
                    includeWater = true,
                    includeSlabs = true,
                    glazeColor = highlightColor,
                )
            }
            return@handler
        }

        if (movedSq.isNaN() || movedSq > 6.25 || nowMs - lastScanTimeMs > 450L ||
            mode != lastScanMode || rad != lastScanRadius
        ) {
            rebuildSurfaceMesh(level, camX, camY, camZ, mode, rad)
            lastScanCamX = camX
            lastScanCamY = camY
            lastScanCamZ = camZ
            lastScanTimeMs = nowMs
            lastScanMode = mode
            lastScanRadius = rad
        }

        if (!hasCachedTiles || !surfaceMeshStorage.isReady) return@handler

        val entityCount = if (entityPseudoShadow && pseudoShadowStrength > 0.01f) {
            gatherNearestEntities(level, camX, camY, camZ, rad.toDouble(), cameraState.isFirstPerson)
        } else {
            0
        }

        // Smooth sun direction & sky brightness from world time
        val dayAngle = ((level.defaultClockTime % 24000L).toFloat() / 24000f) * (Math.PI.toFloat() * 2f)
        val sunElev = cos(dayAngle).coerceAtLeast(0.28f)
        val sunAzim = sin(dayAngle)
        val skyBright = (0.48f + 0.52f * ((cos(dayAngle) + 1f) * 0.5f)).coerceIn(0.42f, 1.0f)

        val originRelX = (meshOriginX - camX).toFloat()
        val originRelY = (meshOriginY - camY).toFloat()
        val originRelZ = (meshOriginZ - camZ).toFloat()
        val timeSec = ((System.nanoTime() - startNano) * 1e-9).toFloat()

        val uboSlice = reflectionUniform.get(
            WaterReflectionUniformKey(
                camX = camX.toFloat(),
                camY = camY.toFloat(),
                camZ = camZ.toFloat(),
                timeSeconds = timeSec,
                originRelX = originRelX,
                originRelY = originRelY,
                originRelZ = originRelZ,
                maxRadius = rad.toFloat(),
                glossStrength = glossStrength,
                fresnelStrength = fresnelStrength,
                smoothness = smoothness,
                pseudoShadowStrength = pseudoShadowStrength,
                subtleWave = subtleWave,
                puddleMode = if (mode == SurfaceMode.WATER_AND_PUDDLES) 1f else 0f,
                puddleScale = puddleScale,
                entityCount = entityCount.toFloat(),
                hiR = highlightColor.r / 255f,
                hiG = highlightColor.g / 255f,
                hiB = highlightColor.b / 255f,
                filmAlpha = highlightColor.a / 255f,
                tintR = waterTint.r / 255f,
                tintG = waterTint.g / 255f,
                tintB = waterTint.b / 255f,
                pseudoShimmer = pseudoShimmer,
                sunX = sunAzim * 0.65f + 0.25f,
                sunY = sunElev,
                sunZ = 0.38f,
                skyBrightness = skyBright,
                e0x = entX[0], e0y = entY[0], e0z = entZ[0], e0h = entH[0],
                e1x = entX[1], e1y = entY[1], e1z = entZ[1], e1h = entH[1],
                e2x = entX[2], e2y = entY[2], e2z = entZ[2], e2h = entH[2],
                e3x = entX[3], e3y = entY[3], e3z = entZ[3], e3h = entH[3],
                e4x = entX[4], e4y = entY[4], e4z = entZ[4], e4h = entH[4],
                e5x = entX[5], e5y = entY[5], e5z = entZ[5], e5h = entH[5],
                r0 = entR[0], r1 = entR[1], r2 = entR[2], r3 = entR[3],
                r4 = entR[4], r5 = entR[5],
            )
        )

        val modelView = Matrix4f(cameraState.viewRotationMatrix).translate(originRelX, originRelY, originRelZ)
        val worldViewUniform = getDynamicTransformsUniform(modelView = modelView)
        val pipeline = ClientRenderPipelines.Reflection.WaterGroundPass

        mainTarget.createRenderPass({ "LB SmoothWaterGround Pass" }, useDepthAttachment = true).use { pass ->
            pass.setPipeline(pipeline)
            pass.bindProjectionUniform()
            pass.bindDynamicTransformsUniform(worldViewUniform)
            ClientUniformDefine.WATER_REFLECTION.setTo(pass, uboSlice)
            surfaceMeshStorage.bindAndDraw(pass)
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
        for (i in 0 until 6) {
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
            if (entity === self && (!reflectSelf || !isFirstPerson)) continue

            val rx = (entity.x - camX).toFloat()
            val ry = (entity.y - camY).toFloat()
            val rz = (entity.z - camZ).toFloat()
            val dSq = rx * rx + rz * rz
            if (dSq > maxDistSq) continue

            // Insert into sorted top-6 nearest slots
            var insertIdx = -1
            if (count < 6) {
                insertIdx = count
                count++
            } else if (dSq < entDistSq[5]) {
                insertIdx = 5
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

    private fun rebuildSurfaceMesh(
        level: ClientLevel,
        camX: Double,
        camY: Double,
        camZ: Double,
        mode: SurfaceMode,
        rad: Int,
    ) {
        val cx = floor(camX).toInt()
        val cy = floor(camY).toInt()
        val cz = czFloor(camZ)
        val gridDim = rad * 2 + 3 // +1 border ring so neighbor obstacle height lookups stay O(1) array accesses!
        val half = rad + 1

        val surfHeights = FloatArray(gridDim * gridDim) { Float.NaN }
        val isWaterGrid = BooleanArray(gridDim * gridDim)
        val waterColorGrid = IntArray(gridDim * gridDim) { -1 }
        val columnTopSolidY = IntArray(gridDim * gridDim) { Int.MIN_VALUE }
        val columnMapRgb = IntArray(gridDim * gridDim) { 0x6A7A8C }

        val includeWater = mode != SurfaceMode.WET_GROUND
        val includeGround = mode != SurfaceMode.WATER_ONLY

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
                            if (surfY < camY - 0.06) {
                                surfHeights[idx] = surfY
                                isWaterGrid[idx] = true
                                val biomeCol = BiomeColors.getAverageWaterColor(level, mPos)
                                waterColorGrid[idx] = (0xFF shl 24) or (biomeCol and 0x00FFFFFF)
                                break
                            }
                        }
                        continue
                    }

                    val state = level.getBlockState(mPos)
                    if (state.isAir) continue

                    if (!recordedTopSolid && !state.getCollisionShape(level, mPos).isEmpty) {
                        recordedTopSolid = true
                        columnTopSolidY[idx] = y + 1
                        val mapCol = state.getMapColor(level, mPos).col
                        if (mapCol != 0) {
                            columnMapRgb[idx] = mapCol and 0x00FFFFFF
                        }
                    }

                    if (includeGround && state.isFaceSturdy(level, mPos, Direction.UP)) {
                        abovePos.set(wx, y + 1, wz)
                        val upState = level.getBlockState(abovePos)
                        if (!upState.isSolidRender && upState.fluidState.isEmpty) {
                            val surfY = (y + 1).toFloat()
                            if (surfY < camY - 0.06) {
                                surfHeights[idx] = surfY
                                isWaterGrid[idx] = false
                                val gCol = columnMapRgb[idx]
                                waterColorGrid[idx] = (0xFF shl 24) or gCol
                                break
                            }
                        }
                    }

                    if (state.isSolidRender) {
                        break
                    }
                }
            }
        }

        val pipeline = ClientRenderPipelines.Reflection.WaterGroundPass
        surfaceMeshStorage.byteBufferBuilder.clear()
        val builder = surfaceMeshStorage.byteBufferBuilder.begin(pipeline)

        val origX = cx.toDouble()
        val origY = cy.toDouble()
        val origZ = cz.toDouble()
        var emitted = 0

        // Helper: compute smooth vertex obstacle pseudo-reflection factor [0..1] from 2x2 neighboring columns
        fun cornerObstacleFactorAndColor(gxCorner: Int, gzCorner: Int, surfY: Float, fallbackArgb: Int): Pair<Float, Int> {
            var wallFactor = 0f
            var accumR = 0
            var accumG = 0
            var accumB = 0
            var colSamples = 0
            for (ox in -1..1) {
                val nx = gxCorner + ox
                if (nx !in 0 until gridDim) continue
                for (oz in -1..1) {
                    val nz = gzCorner + oz
                    if (nz !in 0 until gridDim) continue
                    val nIdx = nx * gridDim + nz
                    val topY = columnTopSolidY[nIdx]
                    if (topY != Int.MIN_VALUE) {
                        val rise = topY.toFloat() - surfY
                        if (rise > 0.35f) {
                            val distWeight = if (ox == 0 || oz == 0) 0.34f else 0.20f
                            val hWeight = (rise / 3.2f).coerceIn(0f, 1f)
                            wallFactor += hWeight * distWeight
                            val rgb = columnMapRgb[nIdx]
                            accumR += (rgb shr 16) and 0xFF
                            accumG += (rgb shr 8) and 0xFF
                            accumB += rgb and 0xFF
                            colSamples++
                        }
                    }
                }
            }
            val clampedFactor = wallFactor.coerceIn(0f, 1f)
            val argb = if (colSamples > 0) {
                val r = (accumR / colSamples).coerceIn(0, 255)
                val g = (accumG / colSamples).coerceIn(0, 255)
                val b = (accumB / colSamples).coerceIn(0, 255)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            } else {
                fallbackArgb
            }
            return clampedFactor to argb
        }

        for (gx in 1 until gridDim - 1) {
            val dx = gx - half
            for (gz in 1 until gridDim - 1) {
                val dz = gz - half
                if (dx * dx + dz * dz > radSq) continue
                val idx = gx * gridDim + gz
                val surfY = surfHeights[idx]
                if (surfY.isNaN()) continue

                val ry = (surfY + 0.003f - origY.toFloat())
                val x0 = dx.toFloat()
                val x1 = x0 + 1f
                val z0 = dz.toFloat()
                val z1 = z0 + 1f

                val waterFlag = if (isWaterGrid[idx]) 2.0f else 0.0f
                val baseArgb = waterColorGrid[idx]

                val (w00, c00) = cornerObstacleFactorAndColor(gx, gz, surfY, baseArgb)
                val (w01, c01) = cornerObstacleFactorAndColor(gx, gz + 1, surfY, baseArgb)
                val (w11, c11) = cornerObstacleFactorAndColor(gx + 1, gz + 1, surfY, baseArgb)
                val (w10, c10) = cornerObstacleFactorAndColor(gx + 1, gz, surfY, baseArgb)

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

    private fun czFloor(v: Double): Int = floor(v).toInt()
}
