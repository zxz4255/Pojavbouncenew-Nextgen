/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.features.module.modules.render.fpsknife

import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.min

/** Shared render/collision rig. All positions are palm centres, not independently guessed fist tips. */
object FpsKnifeRig {
    private fun rad(degrees: Float) = Math.toRadians(degrees.toDouble()).toFloat()

    fun armRotation(yaw: Float, pitch: Float, roll: Float): Quaternionf = Quaternionf()
        .rotateY(rad(yaw)).rotateX(rad(pitch - 52f)).rotateZ(rad(-roll))

    private fun bladeRotation(yaw: Float, pitch: Float, roll: Float): Quaternionf = Quaternionf()
        .rotateZ(rad(roll)).rotateY(rad(yaw)).rotateX(rad(pitch))

    data class Frames(val hand: Quaternionf, val blade: Quaternionf)

    /**
     * Ordinary grip, wrist showcases and attacks share an invariant B^-1 * H.
     * Held inspections deliberately change attachment at the palm tip or safe handle;
     * their independent blade motion does not rotate the hand with the moving part.
     */
    fun frames(p: FpsKnifePose): Frames {
        val arm = armRotation(p.mainArmYawY, p.mainArmPitchX, p.mainArmRollZ)
        val baseArm = armRotation(p.gripArmYawY, p.gripArmPitchX, p.gripArmRollZ)
        val referenceBlade = bladeRotation(168f, 38f, -16f)
        val referenceHand = armRotation(-10f, -6f, -28f)
        val animatedBlade = bladeRotation(p.knifeYawY, p.knifeRollX, p.knifeViewRollZ)
        val armDelta = Quaternionf(arm).mul(Quaternionf(baseArm).invert())
        val blade = armDelta.mul(animatedBlade)
        // The same grip calibration in idle, guards and every attack: not a new fit per animation.
        val hand = Quaternionf(blade).mul(Quaternionf(referenceBlade).invert()).mul(referenceHand)
        if (p.heldBalisongBlend > 0f) {
            // Turn to a safe-handle grip: handle length crosses the fingers, while the moving
            // bite handle is wholly on the fingertip side of the hand's end face.
            val safeHand = Quaternionf(blade).rotateX(rad(180f)).rotateZ(rad(180f))
            hand.slerp(safeHand, p.heldBalisongBlend)
            blade.rotateX(rad(180f * p.heldBalisongBlend))
        }
        if (p.palmFrontSpinBlend > 0f) {
            // Spin plane parallel to the fingertip face, never through the forearm.
            val palmPlane = Quaternionf(hand).rotateX(rad(90f))
            blade.slerp(palmPlane, p.palmFrontSpinBlend)
            blade.rotateZ(rad(p.ringSpinDeg))
        }
        if (p.knifeRelease > 0f) {
            // Release first, THEN rotate in the clear plane in front of the fists.
            val free = bladeRotation(180f, 0f, p.gripKnifeViewRollZ)
            blade.slerp(free, p.knifeFreePoseBlend.coerceIn(0f, 1f))
            blade.rotateZ(rad(p.ringSpinDeg))
            hand.slerp(arm, p.knifeRelease.coerceIn(0f, 1f))
        }
        return Frames(hand.normalize(), blade.normalize())
    }

    fun thickness(requested: Float, knifeScale: Float, knifeVisible: Boolean): Float =
        if (knifeVisible) min(requested.coerceIn(0.48f, 0.85f), knifeScale * 0.70f)
        else requested.coerceIn(0.48f, 0.85f)

    /** Canonical Mojang arm space: x cross-section, y length, z thickness; includes slim skin centre. */
    fun canonicalArmMatrix(palm: Vector3f, rotation: Quaternionf, side: Float, slim: Boolean, thickness: Float): Matrix4f {
        val centreX = -side * (if (slim) 0.5f else 1f) / 16f
        return Matrix4f().translate(palm).rotate(rotation).scale(thickness, 0.90f, thickness)
            .translate(-centreX, -9.5f / 16f, 0f)
    }

    /** Cancels exactly the offset/z-rotation installed by AvatarRenderer.renderHand (MC 26.3). */
    fun avatarArmMatrix(palm: Vector3f, rotation: Quaternionf, side: Float, slim: Boolean, thickness: Float): Matrix4f =
        canonicalArmMatrix(palm, rotation, side, slim, thickness)
            .rotateZ(-side * 0.1f).translate(side * 5f / 16f, -2f / 16f, 0f)

    class Bounds {
        val min = Vector3f(Float.POSITIVE_INFINITY)
        val max = Vector3f(Float.NEGATIVE_INFINITY)
        fun add(v: Vector3f) { min.min(v); max.max(v) }
        fun overlaps(other: Bounds, margin: Float = 0f): Boolean =
            min.x < other.max.x + margin && max.x > other.min.x - margin &&
                min.y < other.max.y + margin && max.y > other.min.y - margin &&
                min.z < other.max.z + margin && max.z > other.min.z - margin
    }

    fun armBounds(matrix: Matrix4f, side: Float, slim: Boolean): Bounds {
        val loX = if (side > 0f) (if (slim) -2f else -3f) else -1f
        val hiX = if (side > 0f) 1f else (if (slim) 2f else 3f)
        val b = Bounds()
        // Include the 0.25px outer sleeve shell too.
        for (x in listOf(loX - 0.25f, hiX + 0.25f))
            for (y in listOf(-2.25f, 10.25f)) for (z in listOf(-2.25f, 2.25f)) {
                b.add(matrix.transformPosition(Vector3f(x / 16f, y / 16f, z / 16f)))
            }
        return b
    }

    private val meshHalfDepth = java.util.IdentityHashMap<FpsKnifeMeshes.KnifeMeshData, Float>()
    private fun halfDepth(mesh: FpsKnifeMeshes.KnifeMeshData): Float = meshHalfDepth.getOrPut(mesh) {
        (mesh.mainQuads + (mesh.safeHandle?.quads ?: emptyList()) + (mesh.biteHandle?.quads ?: emptyList()) + (mesh.foldingBlade?.quads ?: emptyList()))
            .maxOf { q -> maxOf(kotlin.math.abs(q.v0.z), kotlin.math.abs(q.v1.z), kotlin.math.abs(q.v2.z), kotlin.math.abs(q.v3.z)) }
    }

    /** The socket remains on the hand for held inspections; only explicit toss offsets leave it. */
    fun bladeMatrix(p: FpsKnifePose, mesh: FpsKnifeMeshes.KnifeMeshData, rotation: Quaternionf, scale: Float): Matrix4f {
        val ring = p.palmFrontSpinBlend
        val fan = p.heldBalisongBlend
        val hand = if (ring > 0f || fan > 0f) frames(p).hand else null
        val fingertip = if (hand != null) {
            // Sleeve ends at model Y=10.25, ordinary palm socket is Y=9.5.
            val forward = ring * (0.75f * 0.90f / 16f + halfDepth(mesh) * scale + 0.001f) +
                fan * (0.68f * 0.90f / 16f)
            hand.transform(Vector3f(0f, forward, 0f))
        } else Vector3f()
        val gripX = mesh.gripPivotX + (mesh.ringPivotX - mesh.gripPivotX) * ring
        val gripY = mesh.gripPivotY + (mesh.ringPivotY - mesh.gripPivotY) * ring +
            (0.010f - mesh.gripPivotY) * fan
        return Matrix4f().translate(p.mainArmX + p.knifePalmOffsetX + fingertip.x,
            p.mainArmY + p.knifePalmOffsetY + fingertip.y, p.mainArmZ + p.knifePalmOffsetZ + fingertip.z)
            .rotate(rotation).scale(scale).translate(-gripX, -gripY, 0f)
    }

    fun meshBounds(mesh: FpsKnifeMeshes.KnifeMeshData, matrix: Matrix4f, safe: Float, bite: Float, fold: Float = 0f): Bounds {
        val b = Bounds()
        fun append(quads: List<FpsKnifeMeshes.KnifeQuad>, m: Matrix4f) {
            for (q in quads) for (v in arrayOf(q.v0, q.v1, q.v2, q.v3)) {
                b.add(m.transformPosition(Vector3f(v.x, v.y, v.z)))
            }
        }
        append(mesh.mainQuads, matrix)
        fun part(part: FpsKnifeMeshes.ArticulatedPart?, angle: Float) {
            if (part == null) return
            val m = Matrix4f(matrix).translate(part.pivotX, part.pivotY, 0f)
                .rotateZ(rad(angle)).translate(-part.pivotX, -part.pivotY, 0f)
            append(part.quads, m)
        }
        part(mesh.safeHandle, safe); part(mesh.biteHandle, bite); part(mesh.foldingBlade, fold)
        return b
    }

    /**
     * Shared render/test support-hand solver. Bounds include the sleeve and all moving parts.
     * Move a render-local support palm, never the persistent animator or its grip socket.
     * A camera-depth correction is capped; use lateral separation if it would reach the near plane.
     */
    fun supportCorrection(main: Bounds, off: Bounds, blade: Bounds): Vector3f {
        val correction = Vector3f()
        if (off.overlaps(main, .025f) || off.overlaps(blade, .025f)) {
            val requiredZ = maxOf(main.max.z, blade.max.z) + .045f - off.min.z
            if (off.max.z + requiredZ < -.18f) correction.z = requiredZ.coerceAtLeast(0f)
            else correction.x = (minOf(main.min.x, blade.min.x) - .045f - off.max.x).coerceAtMost(0f)
        }
        return correction
    }

    /**
     * During the fully released part, require ALL blade/handle vertices in front of BOTH arm/sleeve
     * boxes. Depth separation is sufficient even while articulated handles or the whole knife spin.
     * Run on a render-local pose copy: corrections never accumulate in the animator.
     */
    fun clearFlight(p: FpsKnifePose, mesh: FpsKnifeMeshes.KnifeMeshData, frames: Frames, scale: Float,
                    main: Bounds, off: Bounds?) {
        if (p.knifeRelease < 0.999f) return
        val bounds = meshBounds(mesh, bladeMatrix(p, mesh, frames.blade, scale), p.safeHandleDeg, p.biteHandleDeg, p.bladeFoldDeg)
        val front = min(main.min.z, off?.min?.z ?: main.min.z) - 0.045f
        if (bounds.max.z > front) p.knifePalmOffsetZ -= bounds.max.z - front
    }
}

/** Source-aware de-duplication; a raw click followed by its entity event must play just one strike. */
class FpsKnifeAttackInput {
    private var lastPhysicalMs = Long.MIN_VALUE / 2
    private var lastEmissionMs = Long.MIN_VALUE / 2
    private var down = false
    fun reset() { lastPhysicalMs = Long.MIN_VALUE / 2; lastEmissionMs = Long.MIN_VALUE / 2; down = false }
    fun physical(now: Long): Boolean {
        lastPhysicalMs = now
        // No damage/hit-result gate, no tick-rate-dependent rising-edge requirement.
        lastEmissionMs = now
        return true
    }
    fun entity(now: Long): Boolean {
        if (now - lastPhysicalMs < 140L || now - lastEmissionMs < 100L) return false
        lastEmissionMs = now
        return true
    }
    fun held(now: Long, pressed: Boolean): Boolean {
        val rising = pressed && !down
        down = pressed
        if (!pressed || now - lastPhysicalMs < 140L) return false
        if (rising || now - lastEmissionMs >= 440L) {
            lastEmissionMs = now
            return true
        }
        return false
    }
}
