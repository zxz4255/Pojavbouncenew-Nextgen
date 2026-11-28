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
package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.addVertex
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.drawCustomMesh
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironment
import net.ccbluex.liquidbounce.render.setPipeline
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.level.material.FogType
import org.joml.Matrix4f
import org.joml.Vector4f
import java.util.Optional
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Floating Fog (`漂泛雾`) — ported with 100% visual fidelity from NeoRaven (`FloatingFogModule` + `FloatingFogRenderer`).
 *
 * Half-resolution 20-step 3-octave volumetric raymarched fog banks with silver edge scattering,
 * bilateral depth-aware upsample compositing, and deterministic world-anchored drifting motes.
 */
object ModuleFloatingFog : ClientModule("FloatingFog", ModuleCategories.RENDER) {

    private val fogColor by color("Color", Color4b(0x82, 0x9C, 0xAD, 0xFF))
    private val fogDensity by int("FogDensity", 58, 0..100, "%")
    private val fogRange by int("FogRange", 56, 16..96, "m")
    private val driftSpeed by int("DriftSpeed", 100, 0..200, "%")
    private val floatingParticles by boolean("FloatingParticles", true)

    private const val PARTICLE_CELL = 4.0

    private val fogTargetHolder = LazyRenderTargetHolder("FloatingFog HalfRes", useDepth = false)
    private val nearestSampler by lazy(LazyThreadSafetyMode.NONE) {
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
    }
    private val fogUboRing by lazy(LazyThreadSafetyMode.NONE) {
        ClientUniformDefine.FLOATING_FOG.createRingBuffer()
    }

    private val invViewProjMat = Matrix4f()
    private val clearZero = Vector4f(0f, 0f, 0f, 0f)
    private var startNano = 0L

    override fun onEnabled() {
        startNano = System.nanoTime()
        super.onEnabled()
    }

    override fun onDisabled() {
        fogTargetHolder.close()
        super.onDisabled()
    }

    @Suppress("unused")
    private val worldRenderHandler = handler<WorldRenderEvent> { event ->
        val camera = event.camera
        if (camera.fluidInCamera != FogType.NONE) {
            return@handler
        }

        val density = (fogDensity / 100f).coerceIn(0f, 1f)
        val range = fogRange.coerceIn(16, 96).toFloat()
        if (density <= 0.001f || range <= 0f) {
            return@handler
        }

        val mainTarget = event.renderTarget
        val sceneDepth = mainTarget.depthTextureView ?: return@handler
        val fullW = mainTarget.width
        val fullH = mainTarget.height
        if (fullW <= 0 || fullH <= 0) {
            return@handler
        }

        val drift = (driftSpeed / 100f).coerceIn(0f, 2f)
        val maxDistance = range * 1.35f
        if (startNano == 0L) {
            startNano = System.nanoTime()
        }
        val time = ((System.nanoTime() - startNano) * 1.0e-9).toFloat()

        val pt = event.partialTicks
        val px = Mth.lerp(pt.toDouble(), player.xo, player.x)
        val py = Mth.lerp(pt.toDouble(), player.yo, player.y)
        val pz = Mth.lerp(pt.toDouble(), player.zo, player.z)

        val camPos = camera.position()
        val cameraState = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState
        val projMat = cameraState.projectionMatrix
        invViewProjMat.set(projMat).mul(cameraState.viewRotationMatrix).invert()

        val zeroToOne = abs(projMat.m22()) < 0.25f
        val clipZScale = if (zeroToOne) 1f else 2f
        val clipZBias = if (zeroToOne) 0f else -1f
        val reversedZ = if (projMat.m32() > 0f) 1f else 0f

        val cr = (fogColor.r and 255) / 255f
        val cg = (fogColor.g and 255) / 255f
        val cb = (fogColor.b and 255) / 255f

        fogUboRing.rotate()
        val uboSlice = fogUboRing.currentBuffer().slice()
        uboSlice.writeStd140 {
            putVec4(invViewProjMat.m00(), invViewProjMat.m01(), invViewProjMat.m02(), invViewProjMat.m03())
            putVec4(invViewProjMat.m10(), invViewProjMat.m11(), invViewProjMat.m12(), invViewProjMat.m13())
            putVec4(invViewProjMat.m20(), invViewProjMat.m21(), invViewProjMat.m22(), invViewProjMat.m23())
            putVec4(invViewProjMat.m30(), invViewProjMat.m31(), invViewProjMat.m32(), invViewProjMat.m33())
            putVec4(camPos.x.toFloat(), camPos.y.toFloat(), camPos.z.toFloat(), time)
            putVec4((camPos.x - px).toFloat(), (camPos.y - py).toFloat(), (camPos.z - pz).toFloat(), range)
            putVec4(cr, cg, cb, density)
            putVec4(maxDistance, drift, clipZScale, clipZBias)
            putVec4(reversedZ, 1f / fullW.toFloat(), 1f / fullH.toFloat(), py.toFloat())
        }

        val halfW = max(1, (fullW + 1) / 2)
        val halfH = max(1, (fullH + 1) / 2)
        val fogTarget = fogTargetHolder.initAndGet(halfW, halfH)
        val fogColorView = fogTarget.colorTextureView ?: return@handler

        // Pass 1: Half-resolution 20-step volumetric raymarch
        fogTarget.createRenderPass(
            { "FloatingFog volume" },
            clearColor = Optional.of(clearZero),
            useDepthAttachment = false,
        ).use { pass ->
            pass.setPipeline(ClientRenderPipelines.NeoRaven.FloatingFogVolume)
            pass.setUniform("DepthSampler", sceneDepth, nearestSampler)
            pass.setUniform(ClientUniformDefine.FLOATING_FOG.uboName, uboSlice)
            pass.draw(3, 1, 0, 0)
        }

        // Pass 2: Full-resolution bilateral depth-aware upsample composite (premultiplied ONE, ONE_MINUS_SRC_ALPHA)
        mainTarget.createRenderPass(
            { "FloatingFog composite" },
            useDepthAttachment = false,
        ).use { pass ->
            pass.setPipeline(ClientRenderPipelines.NeoRaven.FloatingFogComposite)
            pass.setUniform("FogSampler", fogColorView, nearestSampler)
            pass.setUniform("DepthSampler", sceneDepth, nearestSampler)
            pass.setUniform(ClientUniformDefine.FLOATING_FOG.uboName, uboSlice)
            pass.draw(3, 1, 0, 0)
        }

        // Pass 3: Deterministic world-anchored luminous floating motes
        if (floatingParticles) {
            renderParticles(event, px, py, pz, camPos.x, camPos.y, camPos.z, density, range, drift, time)
        }
    }

    @Suppress("LongParameterList")
    private fun renderParticles(
        event: WorldRenderEvent,
        px: Double,
        py: Double,
        pz: Double,
        camX: Double,
        camY: Double,
        camZ: Double,
        density: Float,
        range: Float,
        drift: Float,
        time: Float,
    ) {
        val span = min(range * 0.66, 36.0)
        val maxDistSq = span * span
        val windX = 1.08 * drift
        val windY = 0.24 * drift
        val windZ = -0.50 * drift
        val shiftX = windX * time
        val shiftY = windY * time
        val shiftZ = windZ * time

        val yaw = Math.toRadians(event.camera.yRot().toDouble())
        val pitch = Math.toRadians(event.camera.xRot().toDouble())
        val cosYaw = cos(yaw).toFloat()
        val sinYaw = sin(yaw).toFloat()
        val cosPitch = cos(pitch).toFloat()
        val sinPitch = sin(pitch).toFloat()
        // Camera right and up vectors matching NeoRaven FloatingFogRenderer
        val rightX = -cosYaw
        val rightZ = -sinYaw
        val upX = -sinYaw * sinPitch
        val upY = cosPitch
        val upZ = cosYaw * sinPitch

        val minX = floor((px - span - shiftX) / PARTICLE_CELL).toInt()
        val maxX = floor((px + span - shiftX) / PARTICLE_CELL).toInt()
        val minY = floor((py - 13.0 - shiftY) / PARTICLE_CELL).toInt()
        val maxY = floor((py + 16.0 - shiftY) / PARTICLE_CELL).toInt()
        val minZ = floor((pz - span - shiftZ) / PARTICLE_CELL).toInt()
        val maxZ = floor((pz + span - shiftZ) / PARTICLE_CELL).toInt()

        val rByte = fogColor.r and 255
        val gByte = fogColor.g and 255
        val bByte = fogColor.b and 255

        event.renderEnvironment {
            drawCustomMesh(pipeline = ClientRenderPipelines.NeoRaven.FloatingFogParticles) { pose ->
                for (ix in minX..maxX) {
                    for (iz in minZ..maxZ) {
                        for (iy in minY..maxY) {
                            if (cellHash(ix, iy, iz, 0) > 0.30f + 0.50f * density) {
                                continue
                            }

                            val phase = cellHash(ix, iy, iz, 4) * (PI.toFloat() * 2f)
                            val speed = 0.30f + cellHash(ix, iy, iz, 5) * 0.55f
                            val wx = (ix + cellHash(ix, iy, iz, 1)) * PARTICLE_CELL + shiftX +
                                sin((time * speed + phase).toDouble()) * 0.42
                            val wy = (iy + cellHash(ix, iy, iz, 2)) * PARTICLE_CELL + shiftY +
                                sin((time * speed * 0.72f + phase * 1.6f).toDouble()) * 0.28
                            val wz = (iz + cellHash(ix, iy, iz, 3)) * PARTICLE_CELL + shiftZ +
                                cos((time * speed * 0.86f + phase).toDouble()) * 0.42

                            val dx = wx - px
                            val dz = wz - pz
                            val horizSq = dx * dx + dz * dz
                            if (horizSq >= maxDistSq) {
                                continue
                            }
                            val dy = abs(wy - (py + 1.0))
                            if (dy >= 15.0) {
                                continue
                            }

                            val radial = 1f - smoothstep((span * 0.55).toFloat(), span.toFloat(), sqrt(horizSq).toFloat())
                            val vertical = 1f - smoothstep(3.5f, 15f, dy.toFloat())
                            val camDist = sqrt(
                                (wx - camX) * (wx - camX) +
                                    (wy - camY) * (wy - camY) +
                                    (wz - camZ) * (wz - camZ)
                            ).toFloat()
                            val nearFade = smoothstep(0.65f, 2.2f, camDist)
                            val pulse = 0.76f + 0.24f * sin(
                                (time * (0.45f + speed * 0.35f) + phase * 2.1f).toDouble()
                            ).toFloat()
                            val alpha = radial * vertical * nearFade * pulse * (0.14f + 0.44f * density)
                            if (alpha <= 0.01f) {
                                continue
                            }

                            val s = cellHash(ix, iy, iz, 8)
                            val size = 0.034f + 0.104f * s * s
                            val tilt = (cellHash(ix, iy, iz, 6) - 0.5f) * 2.2f +
                                time * 0.08f * (cellHash(ix, iy, iz, 7) - 0.5f)
                            val cosTilt = cos(tilt.toDouble()).toFloat()
                            val sinTilt = sin(tilt.toDouble()).toFloat()
                            val aspect = 0.60f

                            val ax = (rightX * cosTilt - upX * sinTilt) * size
                            val ay = (-upY * sinTilt) * size
                            val az = (rightZ * cosTilt - upZ * sinTilt) * size
                            val bx = (rightX * sinTilt + upX * cosTilt) * (size * aspect)
                            val by = (upY * cosTilt) * (size * aspect)
                            val bz = (rightZ * sinTilt + upZ * cosTilt) * (size * aspect)

                            val cx = (wx - camX).toFloat()
                            val cy = (wy - camY).toFloat()
                            val cz = (wz - camZ).toFloat()
                            val argb = ARGB.color((alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255), rByte, gByte, bByte)

                            addVertex(pose, cx - ax - bx, cy - ay - by, cz - az - bz).setColor(argb).setUv(0f, 0f)
                            addVertex(pose, cx + ax - bx, cy + ay - by, cz + az - bz).setColor(argb).setUv(1f, 0f)
                            addVertex(pose, cx + ax + bx, cy + ay + by, cz + az + bz).setColor(argb).setUv(1f, 1f)
                            addVertex(pose, cx - ax + bx, cy - ay + by, cz - az + bz).setColor(argb).setUv(0f, 1f)
                        }
                    }
                }
            }
        }
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun cellHash(x: Int, y: Int, z: Int, salt: Int): Float {
        var h = x * -0x61c88647 + y * 0x7f4a7c15 + z * -0x6b2fb645 + salt * -0x7a143595
        h = h xor (h ushr 16)
        h *= -0x7a143595
        h = h xor (h ushr 13)
        h *= -0x3d4d51cb
        h = h xor (h ushr 16)
        return (h and 0x7fffffff).toFloat() / 2147483647.0f
    }
}
