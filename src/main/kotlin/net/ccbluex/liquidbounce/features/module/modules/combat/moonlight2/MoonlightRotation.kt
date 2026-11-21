/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce).
 * Copyright (C) 2015-2026 CCBlueX and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Rotation maths for the MoonLight-style aura ports. Written for this fork; the rotation *modes*
 * follow MoonLight's KillAura (GPL-3.0) naming so a 1.8 config keeps its meaning.
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.moonlight2

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Which point of the target box the aura aims at.
 *
 * <p>`NEAREST` and `CENTER` are the two MoonLight options. `TOP`/`FEET`/`BODY` are this fork's
 * additions for the 1.8 style "aim at the head but rotate through the body" feel; they pick a fixed
 * height on the box and then, like MoonLight, clamp that point to the visible surface.</p>
 */
enum class AimPoint(override val tag: String) : Tagged {
    CENTER("Center"),
    NEAREST("Nearest"),
    HEAD("Head"),
    BODY("Body"),
    FEET("Feet"),
}

/** Rotation smoothing functions exposed in the menu; all are pure functions of the delta. */
enum class RotationMode(override val tag: String) : Tagged {
    LINEAR("Linear"),
    SINE("Sine"),
    QUADRATIC("Quadratic"),
    CUBIC("Cubic"),
    ADAPTIVE("Adaptive"),
    RANDOM("Random"),
}

/** What the aura does when the target is out of the rotation range but inside the search range. */
enum class RotationSpeedVariance(override val tag: String) : Tagged {
    NONE("None"),
    PER_AURA("PerAura"),
    PER_ROTATION("PerRotation"),
}

object MoonlightRotation {

    /**
     * Point on [entity]'s box that should be aimed at.
     *
     * @param from our eye position; the point is pulled onto the box surface facing us so the aim
     * does not sit deep inside the hitbox when the target is very close.
     */
    fun aimPoint(entity: Entity, from: Vec3, aim: AimPoint): Vec3 {
        val box = entity.boundingBox
        val center = box.center
        return when (aim) {
            AimPoint.CENTER -> center
            AimPoint.HEAD -> surfacePoint(box, from, box.maxY - 0.12)
            AimPoint.BODY -> surfacePoint(box, from, center.y)
            AimPoint.FEET -> surfacePoint(box, from, box.minY + 0.12)
            AimPoint.NEAREST -> {
                // Clamp our own eye position into the box: this is the closest point of the hitbox,
                // and for a normally sized entity it lands on the side that faces us.
                Vec3(
                    from.x.coerceIn(box.minX, box.maxX),
                    from.y.coerceIn(box.minY, box.maxY),
                    from.z.coerceIn(box.minZ, box.maxZ),
                )
            }
        }
    }

    private fun surfacePoint(box: AABB, from: Vec3, y: Double): Vec3 {
        val clampedY = y.coerceIn(box.minY + 0.01, box.maxY - 0.01)
        val center = box.center
        // Step just inside the box along the horizontal direction from us to the target, so the ray
        // hits the near face instead of stopping short of it.
        val dx = center.x - from.x
        val dz = center.z - from.z
        val length = hypot(dx, dz)
        if (length < 1.0E-4) {
            return Vec3(center.x, clampedY, center.z)
        }
        val inset = 1.0E-3
        val halfX = box.xsize / 2 - inset
        val halfZ = box.zsize / 2 - inset
        val x = (center.x - dx / length * halfX).coerceIn(box.minX, box.maxX)
        val z = (center.z - dz / length * halfZ).coerceIn(box.minZ, box.maxZ)
        return Vec3(x, clampedY, z)
    }

    /** Yaw/pitch from [from] to [to]. */
    fun rotationTo(from: Vec3, to: Vec3): Rotation = Rotation.lookingAt(to, from)

    /**
     * Smallest signed step from [current] to [target], wrapped to ±180°.
     */
    fun delta(current: Float, target: Float): Float {
        var difference = (target - current) % 360.0F
        if (difference > 180.0F) difference -= 360.0F
        if (difference < -180.0F) difference += 360.0F
        return difference
    }

    /**
     * Advances one rotation axis by at most [speed] degrees, applying the configured easing to the
     * remaining distance.
     *
     * <p>[speed] is a per-tick budget, exactly like MoonLight's yaw/pitch speed values: the step is
     * `min(distance, speed)` shaped by the curve, never a teleport onto the target.</p>
     */
    fun step(
        current: Float,
        target: Float,
        speed: Float,
        mode: RotationMode,
        remainingTicks: Int,
        randomFactor: Float,
    ): Float {
        val difference = delta(current, target)
        val distance = abs(difference)
        if (distance < 1.0E-4F) return target

        val budget = if (remainingTicks > 0) {
            min(distance, max(speed, distance / remainingTicks))
        } else {
            min(distance, speed)
        }

        val shaped = when (mode) {
            RotationMode.LINEAR -> budget
            RotationMode.SINE -> {
                // Sine easing on the fraction of the remaining distance: slow start, fast middle.
                val fraction = (budget / distance).coerceIn(0F, 1F)
                distance * kotlin.math.sin(fraction * Math.PI / 2).toFloat()
            }
            RotationMode.QUADRATIC -> {
                val fraction = (budget / distance).coerceIn(0F, 1F)
                distance * fraction * fraction
            }
            RotationMode.CUBIC -> {
                val fraction = (budget / distance).coerceIn(0F, 1F)
                distance * fraction * fraction * fraction
            }
            RotationMode.ADAPTIVE -> {
                // Faster when far away, precise when close: a 1/x curve over the remaining angle.
                val adaptive = max(budget, distance * distance / 90.0F)
                min(distance, adaptive)
            }
            RotationMode.RANDOM -> {
                val jitter = if (randomFactor > 0F) (randomFactor.coerceIn(0F, 1F)) else 0F
                val scaled = budget * (1F - jitter + jitter * (0.5F + RANDOM.nextFloat()))
                min(distance, scaled)
            }
        }

        val applied = min(distance, max(0F, shaped))
        return current + if (difference < 0F) -applied else applied
    }

    /** Normalises a yaw into [-180, 180). */
    fun wrapYaw(value: Float): Float {
        var yaw = value % 360.0F
        if (yaw >= 180.0F) yaw -= 360.0F
        if (yaw < -180.0F) yaw += 360.0F
        return yaw
    }

    /** Pitch is clamped to the vanilla look limits. */
    fun clampPitch(value: Float): Float = value.coerceIn(-90F, 90F)

    /** Distance from our eye to the nearest point of the target box, used for reach decisions. */
    fun boxDistance(eye: Vec3, box: AABB): Double {
        val dx = max(max(box.minX - eye.x, 0.0), eye.x - box.maxX)
        val dy = max(max(box.minY - eye.y, 0.0), eye.y - box.maxY)
        val dz = max(max(box.minZ - eye.z, 0.0), eye.z - box.maxZ)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Angle between two rotations, for "is the target inside the FOV cone" checks. */
    fun angleBetween(from: Rotation, to: Rotation): Float {
        val yawDelta = abs(RotationUtil.angleDifference(from.yaw, to.yaw))
        val pitchDelta = abs(from.pitch - to.pitch)
        return sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta)
    }

    /** Yaw that points from [from] towards [to], used by the "look at the block side" helper. */
    fun yawTo(from: Vec3, to: Vec3): Float = (atan2(to.z - from.z, to.x - from.x) * 180.0 / Math.PI).toFloat() - 90F

    private val RANDOM = java.util.concurrent.ThreadLocalRandom.current()
}
