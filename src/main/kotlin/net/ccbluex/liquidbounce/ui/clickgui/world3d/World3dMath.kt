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
package net.ccbluex.liquidbounce.ui.clickgui.world3d

import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassSurface
import net.ccbluex.liquidbounce.utils.math.ceilToInt
import net.ccbluex.liquidbounce.utils.math.floorToInt
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import org.joml.Matrix3x2f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Perspective 3D camera, oriented 3D surface plane with exact ray-plane picking,
 * differential tangent Jacobian (`Matrix3x2f`), and perspective-correct `u/w` holo quad emitter.
 */
class World3dCamera(
    val viewportW: Float,
    val viewportH: Float,
    fovDegrees: Float = 53f,
    camYawDeg: Float = 0f,
    camPitchDeg: Float = 0f,
) {
    val halfW: Float = viewportW * 0.5f
    val halfH: Float = viewportH * 0.5f
    val focal: Float = halfH / max(0.15f, tan(Math.toRadians((fovDegrees * 0.5f).toDouble())).toFloat())
    val viewRotation: Quaternionf = Quaternionf().rotationYXZ(
        Math.toRadians((-camYawDeg).toDouble()).toFloat(),
        Math.toRadians((-camPitchDeg).toDouble()).toFloat(),
        0f,
    )
}

data class ProjectedPoint(val sx: Float, val sy: Float, val depthW: Float)

data class LocalHit(val u: Float, val v: Float, val distance: Float)

/**
 * A rectangular 2D UI plane `[0..width] x [0..height]` embedded in 3D camera space.
 * Positive Z points forward into the screen; X points right; Y points down (matching GUI orientation).
 */
class Surface3D(
    worldCenterX: Float,
    worldCenterY: Float,
    worldCenterZ: Float,
    val width: Float,
    val height: Float,
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float = 0f,
    val camera: World3dCamera,
) {
    val center: Vector3f
    val axisU: Vector3f
    val axisV: Vector3f
    val normal: Vector3f
    val normalTiltX: Float

    init {
        val localRot = Quaternionf(camera.viewRotation).mul(
            Quaternionf().rotationYXZ(
                Math.toRadians(yawDeg.toDouble()).toFloat(),
                Math.toRadians(pitchDeg.toDouble()).toFloat(),
                Math.toRadians(rollDeg.toDouble()).toFloat(),
            )
        )
        center = Vector3f(worldCenterX, worldCenterY, worldCenterZ).rotate(camera.viewRotation)
        // Ensure every panel stays strictly in front of the near plane.
        if (center.z < 120f) center.z = 120f
        axisU = Vector3f(1f, 0f, 0f).rotate(localRot)
        axisV = Vector3f(0f, 1f, 0f).rotate(localRot)
        normal = Vector3f(axisU).cross(axisV).normalize()
        normalTiltX = axisU.z.coerceIn(-1f, 1f)
    }

    val depthKey: Float get() = center.z

    /** Projects local coordinates `u in [0..width], v in [0..height]` to GUI screen coordinates. */
    fun project(u: Float, v: Float): ProjectedPoint {
        val lx = u - width * 0.5f
        val ly = v - height * 0.5f
        val px = center.x + axisU.x * lx + axisV.x * ly
        val py = center.y + axisU.y * lx + axisV.y * ly
        val pz = max(40f, center.z + axisU.z * lx + axisV.z * ly)
        val invZ = camera.focal / pz
        return ProjectedPoint(
            sx = camera.halfW + px * invZ,
            sy = camera.halfH + py * invZ,
            depthW = pz,
        )
    }

    /** Projects centered coordinates `cx in [-width/2..+width/2], cy in [-height/2..+height/2]`. */
    fun projectCentered(cx: Float, cy: Float): ProjectedPoint {
        val px = center.x + axisU.x * cx + axisV.x * cy
        val py = center.y + axisU.y * cx + axisV.y * cy
        val pz = max(40f, center.z + axisU.z * cx + axisV.z * cy)
        val invZ = camera.focal / pz
        return ProjectedPoint(
            sx = camera.halfW + px * invZ,
            sy = camera.halfH + py * invZ,
            depthW = pz,
        )
    }

    /**
     * Exact 3D camera ray-plane intersection from GUI cursor `(screenX, screenY)`.
     * Returns `(u, v)` in `[0..width] x [0..height]` (or outside if `clampToBounds == false`).
     */
    fun raycast(screenX: Float, screenY: Float, requireInside: Boolean = true, pad: Float = 0f): LocalHit? {
        val rayX = (screenX - camera.halfW) / camera.focal
        val rayY = (screenY - camera.halfH) / camera.focal
        val rayZ = 1f

        val denom = rayX * normal.x + rayY * normal.y + rayZ * normal.z
        if (kotlin.math.abs(denom) < 1e-5f) return null

        val numer = center.x * normal.x + center.y * normal.y + center.z * normal.z
        val t = numer / denom
        if (t <= 25f) return null

        val hx = rayX * t - center.x
        val hy = rayY * t - center.y
        val hz = rayZ * t - center.z

        val u = hx * axisU.x + hy * axisU.y + hz * axisU.z + width * 0.5f
        val v = hx * axisV.x + hy * axisV.y + hz * axisV.z + height * 0.5f

        if (requireInside) {
            if (u < -pad || u > width + pad || v < -pad || v > height + pad) return null
        }
        return LocalHit(u, v, t)
    }

    /**
     * First-order 3D perspective Jacobian `Matrix3x2f` at `(u0, v0)` so standard 2D text/icons/widgets
     * map onto this tilted 3D plane with authentic foreshortening, skew, and scale.
     */
    fun tangentPoseAt(u0: Float = width * 0.5f, v0: Float = height * 0.5f): Matrix3x2f {
        val p0 = project(u0, v0)
        val puPos = project(u0 + 0.5f, v0)
        val puNeg = project(u0 - 0.5f, v0)
        val pvPos = project(u0, v0 + 0.5f)
        val pvNeg = project(u0, v0 - 0.5f)

        val dSxDu = puPos.sx - puNeg.sx
        val dSyDu = puPos.sy - puNeg.sy
        val dSxDv = pvPos.sx - pvNeg.sx
        val dSyDv = pvPos.sy - pvNeg.sy

        val tx = p0.sx - dSxDu * u0 - dSxDv * v0
        val ty = p0.sy - dSyDu * u0 - dSyDv * v0

        return Matrix3x2f(
            dSxDu, dSyDu,
            dSxDv, dSyDv,
            tx, ty,
        )
    }

    fun screenBounds(pad: Float = 0f): ScreenRectangle {
        val hw = width * 0.5f + pad
        val hh = height * 0.5f + pad
        val p0 = projectCentered(-hw, -hh)
        val p1 = projectCentered(-hw, hh)
        val p2 = projectCentered(hw, hh)
        val p3 = projectCentered(hw, -hh)
        val minX = min(min(p0.sx, p1.sx), min(p2.sx, p3.sx)).floorToInt()
        val maxX = max(max(p0.sx, p1.sx), max(p2.sx, p3.sx)).ceilToInt()
        val minY = min(min(p0.sy, p1.sy), min(p2.sy, p3.sy)).floorToInt()
        val maxY = max(max(p0.sy, p1.sy), max(p2.sy, p3.sy)).ceilToInt()
        return ScreenRectangle(minX, minY, max(1, maxX - minX), max(1, maxY - minY))
    }
}

object World3dHoloDraw {

    const val KIND_TILE = 0
    const val KIND_TILE_ACTIVE = 1
    const val KIND_DECK = 2
    const val KIND_CHIP = 3
    const val KIND_SEARCH = 4

    /**
     * Renders a hardware perspective-correct 3D holo-glass slab/card with live world refraction,
     * perspective-locked cyber-grid, grazing directional sheen, Fresnel rim, and 3D drop shadow.
     */
    fun drawSurface(
        ctx: GuiGraphicsExtractor,
        surface: Surface3D,
        radius: Float,
        accentArgb: Int,
        kind: Int,
        hover: Float = 0f,
        selected: Float = 0f,
        shadowMargin: Float = 18f,
    ) {
        if (surface.width <= 0.5f || surface.height <= 0.5f) return

        val hw = surface.width * 0.5f
        val hh = surface.height * 0.5f
        val ex = hw + shadowMargin
        val ey = hh + shadowMargin

        val v0 = surface.projectCentered(-ex, -ey)
        val v1 = surface.projectCentered(-ex, ey)
        val v2 = surface.projectCentered(ex, ey)
        val v3 = surface.projectCentered(ex, -ey)

        val encHalfW = (hw * 16f).roundToInt().coerceIn(1, 32767)
        val encHalfH = (hh * 16f).roundToInt().coerceIn(1, 32767)
        val encRadius = (radius.coerceIn(0f, min(hw, hh)) * 16f).roundToInt().coerceIn(0, 32767)

        val hovBits = (hover.coerceIn(0f, 1f) * 15f).roundToInt().coerceIn(0, 15)
        val selBits = (selected.coerceIn(0f, 1f) * 15f).roundToInt().coerceIn(0, 15)
        val tiltBits = (((surface.normalTiltX + 1f) * 0.5f).coerceIn(0f, 1f) * 15f).roundToInt().coerceIn(0, 15)
        val packedFlags = (kind and 7) or (hovBits shl 3) or (selBits shl 7) or (tiltBits shl 11)

        val rawBounds = surface.screenBounds(shadowMargin)
        val clippedBounds = ctx.scissorStack.peek()?.intersection(rawBounds) ?: rawBounds
        if (clippedBounds.width <= 0 || clippedBounds.height <= 0) return

        val backdropTex = ClickGuiGlassSurface.currentBackdropTexture
        if (backdropTex != null) {
            ctx.drawCustomElement(
                pipeline = ClientRenderPipelines.GUI.world3dHolo(),
                textureSetup = backdropTex,
                bounds = clippedBounds,
            ) { pose ->
                fun emit(pt: ProjectedPoint, lx: Float, ly: Float) {
                    val invW = 1f / max(1f, pt.depthW)
                    addVertexWith2DPose(pose, pt.sx, pt.sy)
                        .setUv(lx * invW, ly * invW)
                        .setColor(accentArgb)
                        .setUv1(encHalfW, encHalfH)
                        .setUv2(encRadius, packedFlags)
                        .setLineWidth(invW)
                }
                emit(v0, -ex, -ey)
                emit(v1, -ex, ey)
                emit(v2, ex, ey)
                emit(v3, ex, -ey)
            }
        } else {
            ctx.drawCustomElement(
                pipeline = ClientRenderPipelines.GUI.world3dHoloSolid(),
                bounds = clippedBounds,
            ) { pose ->
                fun emit(pt: ProjectedPoint, lx: Float, ly: Float) {
                    val invW = 1f / max(1f, pt.depthW)
                    addVertexWith2DPose(pose, pt.sx, pt.sy)
                        .setUv(lx * invW, ly * invW)
                        .setColor(accentArgb)
                        .setUv1(encHalfW, encHalfH)
                        .setUv2(encRadius, packedFlags)
                        .setLineWidth(invW)
                }
                emit(v0, -ex, -ey)
                emit(v1, -ex, ey)
                emit(v2, ex, ey)
                emit(v3, ex, -ey)
            }
        }
    }
}
