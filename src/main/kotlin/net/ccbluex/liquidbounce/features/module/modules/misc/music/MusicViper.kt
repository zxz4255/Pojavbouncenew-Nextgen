package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.muoniumplayer.core.viper.dsp.ViPER
import com.muoniumplayer.core.viper.dsp.ViPERParams
import kotlin.math.roundToInt

/** UI publishes immutable snapshots; the decoder worker alone owns the ViPER instance. */
internal data class ViperSettings(
    val enabled: Boolean = false,
    val bass: Boolean = false,
    val bassMode: Int = 0,
    val bassFrequency: Int = 60,
    val bassGain: Float = 0.5f,
    val clarity: Boolean = false,
    val clarityMode: Int = 0,
    val clarityGain: Float = 0.5f,
    val eq: Boolean = false,
    val bands: List<Float> = List(10) { 0f },
    val reverb: Boolean = false,
    val reverbWet: Float = 0.2f,
    val cure: Boolean = false,
    val curePreset: Int = 0,
    val tube: Boolean = false,
    val agc: Boolean = false,
    val agcStrength: Float = 1f,
    val lufs: Boolean = false,
    val lufsTarget: Float = -14f,
    val spectrum: Boolean = false,
    val spectrumFrequency: Int = 7600,
    val spectrumExciter: Float = 0f,
    val field: Boolean = false,
    val fieldWidth: Float = 0f,
    val diff: Boolean = false,
    val diffDelay: Float = 5f,
    val stereo: Boolean = false,
    val stereoWidth: Float = 100f,
    val dynamic: Boolean = false,
    val dynamicStrength: Float = 1f,
    val analog: Boolean = false,
    val analogMode: Int = 0,
    val speaker: Boolean = false,
    val headphone: Boolean = false,
    val headphoneQuality: Int = 0,
    val jdsp: Boolean = false,
    val jdspPreset: Int = 0,
    val limiterThreshold: Float = 1f,
    val channelPan: Float = 0f,
)

/** PCM bridge for Muonium's Java ViPER engine, no JSyn and no extra audio device. */
internal class MusicViper(private val rate: Int, private val channels: Int) {
    private val engine = ViPER()
    private var last: ViperSettings? = null
    private var floatSamples = FloatArray(2304)
    private var shortSamples = ShortArray(2304)

    init {
        engine.setSampleRate(rate)
        engine.resetAllEffects()
        engine.applyMasterLimiter(ViPERParams.MasterLimiterParams())
    }

    private fun apply(s: ViperSettings) {
        val p = ViPERParams()
        p.bass.enable = s.bass; p.bass.mode = s.bassMode; p.bass.frequency = s.bassFrequency
        p.bass.gain = s.bassGain; p.bass.antiPop = false
        p.clarity.enable = s.clarity; p.clarity.mode = s.clarityMode; p.clarity.gain = s.clarityGain
        p.equalizer.enable = s.eq && rate >= 44100; p.equalizer.bandCount = 10
        for (i in 0 until 10) p.equalizer.bandLevels[i] = s.bands[i]
        p.reverb.enable = s.reverb; p.reverb.roomSize = 0.45f; p.reverb.width = 0.7f
        p.reverb.damp = 0.35f; p.reverb.wet = s.reverbWet; p.reverb.dry = 1f
        p.cure.enable = s.cure; p.cure.crossfeedPreset = s.curePreset
        p.tubeSimulator.enable = s.tube
        // Only touch the supported groups: applySnapshot would also apply unrelated all-zero defaults.
        engine.applyBass(p.bass); engine.applyClarity(p.clarity); engine.applyEqualizer(p.equalizer)
        engine.applyReverb(p.reverb); engine.applyCure(p.cure); engine.applyTubeSimulator(p.tubeSimulator)
        p.masterLimiter.threshold = s.limiterThreshold; p.masterLimiter.channelPan = s.channelPan
        p.playbackGainControl.enable = s.agc; p.playbackGainControl.strength = s.agcStrength
        p.playbackGainControl.maxGain = 2f; p.playbackGainControl.outputThreshold = 0.9f
        p.lufs.enable = s.lufs; p.lufs.target = s.lufsTarget; p.lufs.maxGain = 6f; p.lufs.speed = 1
        p.spectrumExtension.enable = s.spectrum && s.spectrumFrequency < rate / 2
        p.spectrumExtension.strength = s.spectrumFrequency; p.spectrumExtension.exciter = s.spectrumExciter
        p.fieldSurround.enable = s.field; p.fieldSurround.widening = s.fieldWidth
        p.fieldSurround.midImage = 1.5f; p.fieldSurround.depth = 200.toShort()
        p.diffSurround.enable = s.diff; p.diffSurround.delay = s.diffDelay
        p.diffSurround.wetDryMix = 1f; p.diffSurround.lpCutoff = 0f
        p.stereoImager.enable = s.stereo
        p.stereoImager.lowWidth = s.stereoWidth; p.stereoImager.midWidth = s.stereoWidth
        p.stereoImager.highWidth = s.stereoWidth; p.stereoImager.lowCrossover = 200f
        p.stereoImager.highCrossover = minOf(4000f, rate * 0.2f)
        p.dynamicSystem.enable = s.dynamic; p.dynamicSystem.strength = s.dynamicStrength
        p.dynamicSystem.xCoeffLow = 100; p.dynamicSystem.xCoeffHigh = 5600
        p.dynamicSystem.yCoeffLow = 40; p.dynamicSystem.yCoeffHigh = 80
        p.dynamicSystem.sideGainLow = 0.5f; p.dynamicSystem.sideGainHigh = 0.5f
        p.analogX.enable = s.analog; p.analogX.mode = s.analogMode
        p.speakerCorrection.enable = s.speaker
        p.headphoneSurround.enable = s.headphone && (rate == 44100 || rate == 48000)
        p.headphoneSurround.quality = s.headphoneQuality
        p.jdspReverb.enable = s.jdsp; p.jdspReverb.preset = s.jdspPreset
        engine.applyMasterLimiter(p.masterLimiter); engine.applyPlaybackGainControl(p.playbackGainControl)
        engine.applyLufs(p.lufs); engine.applySpectrumExtension(p.spectrumExtension)
        engine.applyFieldSurround(p.fieldSurround); engine.applyDiffSurround(p.diffSurround)
        engine.applyStereoImager(p.stereoImager); engine.applyDynamicSystem(p.dynamicSystem)
        engine.applyAnalogX(p.analogX); engine.applySpeakerCorrection(p.speakerCorrection)
        if (last == null || last?.headphone != s.headphone || last?.headphoneQuality != s.headphoneQuality)
            engine.applyHeadphoneSurround(p.headphoneSurround)
        // JDSP initialization clears a large delay line: rebuild only when its own settings change.
        if (last == null || last?.jdsp != s.jdsp || last?.jdspPreset != s.jdspPreset)
            engine.applyJdspReverb(p.jdspReverb)
        last = s
    }

    fun process(input: ShortArray, count: Int, s: ViperSettings): ShortArray {
        if (last != s) apply(s)
        val frames = minOf(count, input.size).coerceAtLeast(0) / channels
        val stereoCount = frames * 2
        if (floatSamples.size < stereoCount) floatSamples = FloatArray(stereoCount)
        if (shortSamples.size < frames * channels) shortSamples = ShortArray(frames * channels)
        for (frame in 0 until frames) {
            val i = frame * channels
            floatSamples[frame * 2] = input[i] / 32768f
            floatSamples[frame * 2 + 1] = input[i + if (channels > 1) 1 else 0] / 32768f
        }
        engine.process(floatSamples, frames)
        for (frame in 0 until frames) {
            val left = floatSamples[frame * 2]
            val right = floatSamples[frame * 2 + 1]
            fun quantize(value: Float): Short =
                (if (value.isFinite()) (value * 32768f).roundToInt() else throw ArithmeticException("non-finite ViPER sample"))
                    .coerceIn(-32768, 32767).toShort()
            if (channels == 1) shortSamples[frame] = quantize((left + right) * 0.5f)
            else {
                shortSamples[frame * 2] = quantize(left)
                shortSamples[frame * 2 + 1] = quantize(right)
            }
        }
        return shortSamples
    }

    fun reset() { engine.resetAllEffects() }
}
