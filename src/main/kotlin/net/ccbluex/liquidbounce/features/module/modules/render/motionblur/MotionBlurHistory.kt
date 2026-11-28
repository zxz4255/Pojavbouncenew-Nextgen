/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.render.motionblur

import kotlin.math.abs
import kotlin.math.pow

/** Frame-rate-independent temporal history policy. No game/GPU dependencies. */
class MotionBlurHistory {
    private var valid = false
    private var world: Any? = null
    private var owner: Any? = null
    private var view: Any? = null
    private var width = 0
    private var height = 0
    private var time = 0L
    private var x = 0.0
    private var y = 0.0
    private var z = 0.0
    private var yaw = 0f
    private var pitch = 0f

    fun reset() { valid = false; world = null; owner = null; view = null }
    @Suppress("LongParameterList")
    fun begin(world: Any, owner: Any, view: Any, width: Int, height: Int, now: Long,
              x: Double, y: Double, z: Double, yaw: Float, pitch: Float, strength: Float): Float {
        if (width <= 0 || height <= 0 || !x.isFinite() || !y.isFinite() || !z.isFinite() ||
            !yaw.isFinite() || !pitch.isFinite() || !strength.isFinite()) { reset(); return 0f }
        val delta = (now - time) / 1_000_000_000.0
        val angle = abs(((yaw.toDouble() - this.yaw + 540.0) % 360.0 + 360.0) % 360.0 - 180.0)
        val distance = (x-this.x).pow(2) + (y-this.y).pow(2) + (z-this.z).pow(2)
        val reuse = valid && this.world === world && this.owner === owner && this.view === view &&
            this.width == width && this.height == height && delta > 0.0 && delta <= 0.25 &&
            distance <= 64.0 && angle <= 90.0 && abs(pitch-this.pitch) <= 90f
        val weight = if (reuse) strength.coerceIn(0f, .95f).toDouble().pow(delta * 60.0).toFloat() else 0f
        this.world=world; this.owner=owner; this.view=view; this.width=width; this.height=height
        this.time=now; this.x=x; this.y=y; this.z=z; this.yaw=yaw; this.pitch=pitch; valid=true
        return weight.coerceIn(0f, 1f)
    }
}
