package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render

import kotlin.math.max
import kotlin.math.min

/** Float animation with a duration and easing, ported from Onyx's `render.misc.Cls`. */
class Anim(initial: Float) {

    private var current: Float = initial
    private var goal: Float = initial
    private var start: Float = initial
    private var duration: Float = 0.0F
    private var elapsed: Float = 0.0F
    private var ease: Ease = Ease.DECELERATE

    fun to(newTarget: Float, durationMs: Float, easing: Ease?): Anim {
        if (newTarget == goal) return this
        start = value()
        goal = newTarget
        duration = max(durationMs, 0.0F)
        ease = easing ?: Ease.LINEAR
        elapsed = 0.0F
        return this
    }

    fun target(): Float = goal

    fun set(v: Float) {
        current = v
        goal = v
        duration = 0.0F
        elapsed = 0.0F
    }

    fun update(deltaMs: Float) {
        if (!isDone()) {
            elapsed = min(elapsed + deltaMs, duration)
        }
    }

    fun isDone(): Boolean = duration <= 0.0F || elapsed >= duration

    fun value(): Float {
        if (duration <= 0.0F) return goal
        return start + (goal - start) * ease.get(elapsed / duration)
    }

    /** The animation's raw progress in 0..1 (used for things like drag fades). */
    fun progress(): Float = if (duration <= 0.0F) 1.0F else min(elapsed / duration, 1.0F)
}
