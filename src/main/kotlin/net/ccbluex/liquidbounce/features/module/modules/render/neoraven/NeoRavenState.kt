/* GPL-3.0-only. Port of Matr1ze/NeoRaven b483ffc; see licenses/neoraven/NOTICE.md. */
package net.ccbluex.liquidbounce.features.module.modules.render.neoraven

import org.joml.Matrix4fc
import kotlin.math.exp
import kotlin.math.floor

/** Original asymmetric eye-adaptation timing, independent of render FPS. */
class NeoEyeAdaptation {
    private var world: Any? = null
    private var previous = 0L
    var exposure = 1f
        private set

    fun reset() { world = null; previous = 0L; exposure = 1f }

    fun update(currentWorld: Any, light: Float, now: Long): Float {
        val target = 1.30f - 0.42f * light.coerceIn(0f, 1f)
        if (world !== currentWorld) exposure = target else {
            val dt = ((now - previous) * 1e-9).coerceIn(0.0, 0.2)
            exposure += ((target - exposure) * (1.0 - exp(-dt * if (target < exposure) 3.0 else 1.1))).toFloat()
        }
        world = currentWorld
        previous = now
        return exposure
    }
}

/** 112x112 rain-exposure map, same chunk anchoring and 10-tick refresh as upstream. */
class NeoRainExposure {
    companion object {
        const val SIZE = 112
        fun origin(coordinate: Double) = (floor(coordinate).toInt() and -16) - 48
        fun wrap(coordinate: Double): Float = (coordinate - floor(coordinate / 1024.0) * 1024.0).toFloat()
    }
    var x = 0; private set
    var z = 0; private set
    private var world: Any? = null
    private var tick = Long.MIN_VALUE
    fun invalidate() { world = null }
    fun needsRefresh(level: Any, eyeX: Double, eyeZ: Double, gameTick: Long): Boolean =
        world !== level || origin(eyeX) != x || origin(eyeZ) != z || gameTick < tick || gameTick - tick >= 10L

    fun refreshed(level: Any, eyeX: Double, eyeZ: Double, gameTick: Long) {
        world = level; x = origin(eyeX); z = origin(eyeZ); tick = gameTick
    }

    /** RG encodes 16-bit height relative to dimension minimum; A is the original wet mask. */
    fun encodeHeight(y: Int, minimum: Int): Int = (y - minimum).coerceIn(0, 65535)
}

object NeoDepthConvention {
    /** Vanilla perspective: conventional [-1,1] or modern reversed [0,1]. */
    fun zeroToOne(projection: Matrix4fc) = kotlin.math.abs(projection.m22()) < 0.25f
    fun reversed(projection: Matrix4fc) = projection.m32() > 0f
}
