package net.ccbluex.liquidbounce.features.module.modules.render

import kotlin.math.hypot

/** Same sampling contract as Samsara Scaffold.ScaffoldBpsTracker; no reads mutate the sample. */
internal class OpaiScaffoldMotion {
    private var player: Any? = null
    private var world: Any? = null
    private var tick = 0L
    private var x = 0.0
    private var z = 0.0
    private var speed = 0.0

    fun sample(player: Any?, world: Any?, tick: Long, x: Double, z: Double, multiplier: Double) {
        if (player == null || world == null || !x.isFinite() || !z.isFinite() || !multiplier.isFinite() || multiplier < 0) {
            reset(); return
        }
        val same = this.player === player && this.world === world
        if (same && tick == this.tick) return
        speed = if (same && tick - this.tick == 1L) hypot(x - this.x, z - this.z) * 20 * multiplier else 0.0
        this.player = player; this.world = world; this.tick = tick; this.x = x; this.z = z
    }
    fun speed(player: Any?, world: Any?): Double = if (this.player === player && this.world === world) speed else 0.0
    fun reset() { player = null; world = null; speed = 0.0 }
}

/** Interpolate a real target's two successive tick values; never borrow progress from another block. */
internal class OpaiBreakingMotion {
    private var world: Any? = null
    private var pos: Any? = null
    private var tick: Long = Long.MIN_VALUE
    private var previous = 0f
    private var current = 0f

    fun sample(world: Any, pos: Any, tick: Long, raw: Float, partialTick: Float): Float {
        val next = if (raw.isFinite()) raw.coerceIn(0f, 1f) else 0f
        val same = this.world === world && this.pos == pos
        if (!same || tick < this.tick || next < current) {
            previous = 0f
            current = next
            this.world = world; this.pos = pos; this.tick = tick
        } else if (tick != this.tick) {
            previous = if (tick == this.tick + 1L) current else next
            current = next
            this.tick = tick
        } else {
            current = next
        }
        val t = if (partialTick.isFinite()) partialTick.coerceIn(0f, 1f) else 1f
        return (previous + (current - previous) * t).coerceIn(0f, 1f)
    }
    fun reset() { world = null; pos = null; tick = Long.MIN_VALUE; previous = 0f; current = 0f }
}
