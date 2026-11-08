package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Critically-ish damped spring, the time-based motion model Material 3 Expressive uses for
 * "spatial" transitions (a keycap popping under a finger, a chip settling after a toggle).
 *
 * <p>[Anim] interpolates between two values on a fixed curve, so it can never overshoot and come
 * back. A spring keeps a velocity, which is what gives the small bounce that reads as physical
 * without adding a hand-tuned keyframe list. The integrator is a semi-implicit Euler with a fixed
 * 8 ms sub-step, so a frame spike can never make it explode.</p>
 */
class Spring(initial: Float) {

    private var value: Float = initial
    private var velocity: Float = 0.0F
    private var goal: Float = initial

    fun value(): Float = value

    fun target(): Float = goal

    /** Moves towards `value`; the spring then settles there with a damped bounce. */
    fun target(value: Float): Spring {
        goal = value
        return this
    }

    /** Adds velocity (units per second) for an impulse-style pop without moving the value. */
    fun impulse(velocity: Float): Spring {
        this.velocity += velocity
        return this
    }

    /** Sets both value and target, dropping any velocity. */
    fun set(value: Float) {
        this.value = value
        goal = value
        velocity = 0.0F
    }

    /** Advances the simulation; `deltaMs` is the frame delta in milliseconds. */
    fun update(deltaMs: Float) {
        val seconds = min(max(deltaMs, 0.0F) / 1000.0F, MAX_SECONDS)
        if (seconds <= 0.0F) return
        val steps = max(1, ceil(seconds / STEP_SECONDS).toInt())
        val step = seconds / steps
        repeat(steps) {
            val acceleration = -STIFFNESS * (value - goal) - DAMPING * velocity
            velocity += acceleration * step
            value += velocity * step
        }
    }

    /** Whether the spring is close enough to be considered at rest. */
    fun settled(): Boolean = abs(value - goal) < 0.0005F && abs(velocity) < 0.01F

    companion object {
        private const val STEP_SECONDS = 0.008F
        private const val MAX_SECONDS = 0.05F

        // Under-damped on purpose: this is the "expressive" overshoot, roughly 8% of the distance.
        private const val STIFFNESS = 260.0F
        private const val DAMPING = 18.0F
    }
}
