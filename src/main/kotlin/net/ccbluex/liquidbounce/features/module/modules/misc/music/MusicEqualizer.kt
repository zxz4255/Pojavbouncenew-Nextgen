package net.ccbluex.liquidbounce.features.module.modules.misc.music

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/** A small independent three-band PCM tone control, not Muonium/ViPER DSP.
 * One instance belongs to one decoder worker. Controls are immutable snapshots.
 */
internal data class ToneSettings(val enabled: Boolean = false, val low: Float = 0f,
                                 val mid: Float = 0f, val high: Float = 0f)

internal class MusicEqualizer(private val rate: Int, private val channels: Int) {
    private val lowState = FloatArray(channels)
    private val highState = FloatArray(channels)
    // Two stable one-pole low passes form a complementary three-band crossover.
    private val lowAlpha = (1.0 - exp(-2.0 * Math.PI * 250.0 / rate)).toFloat()
    private val highAlpha = (1.0 - exp(-2.0 * Math.PI * 4000.0 / rate)).toFloat()
    private val smooth = (1.0 - exp(-1.0 / (rate * 0.025))).toFloat()
    private var lowGain = 1f
    private var midGain = 1f
    private var highGain = 1f

    fun process(source: ShortArray, count: Int, volume: Float, out: ByteArray, settings: ToneSettings): Int {
        val size = minOf(count, source.size, out.size / 2).coerceAtLeast(0)
        val lowTarget = db(if (settings.enabled) settings.low else 0f)
        val midTarget = db(if (settings.enabled) settings.mid else 0f)
        val highTarget = db(if (settings.enabled) settings.high else 0f)
        for (i in 0 until size) {
            val channel = i % channels
            if (channel == 0) {
                lowGain += (lowTarget - lowGain) * smooth
                midGain += (midTarget - midGain) * smooth
                highGain += (highTarget - highGain) * smooth
                if (abs(lowTarget - lowGain) < 0.00001f) lowGain = lowTarget
                if (abs(midTarget - midGain) < 0.00001f) midGain = midTarget
                if (abs(highTarget - highGain) < 0.00001f) highGain = highTarget
            }
            val x = source[i].toFloat()
            lowState[channel] += lowAlpha * (x - lowState[channel])
            highState[channel] += highAlpha * (x - highState[channel])
            val mixed = if (lowGain == 1f && midGain == 1f && highGain == 1f) x else
                lowState[channel] * lowGain + (highState[channel] - lowState[channel]) * midGain +
                    (x - highState[channel]) * highGain
            val value = (mixed * volume).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (value and 0xff).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        return size * 2
    }

    fun reset() {
        lowState.fill(0f)
        highState.fill(0f)
    }

    private fun db(value: Float): Float = 10f.pow(value.coerceIn(-9f, 9f) / 20f)
}
