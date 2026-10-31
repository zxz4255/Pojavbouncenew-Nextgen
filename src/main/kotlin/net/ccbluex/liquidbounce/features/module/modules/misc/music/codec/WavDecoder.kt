package net.ccbluex.liquidbounce.features.module.modules.misc.music.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavDecoder {
    fun decode(data: ByteArray): PcmBuffer {
        require(data.size > 44) { "WAV too small" }
        val le = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(le.int == 0x46464952) { "Not RIFF" } // RIFF
        le.int
        require(le.int == 0x45564157) { "Not WAVE" }
        var format = 1
        var channels = 1
        var sampleRate = 44100
        var bits = 16
        var dataPos = -1
        var dataSize = 0
        while (le.remaining() >= 8) {
            val id = le.int
            val size = le.int
            val start = le.position()
            when (id) {
                0x20746d66 -> { // fmt
                    format = le.short.toInt() and 0xFFFF
                    channels = le.short.toInt() and 0xFFFF
                    sampleRate = le.int
                    le.int; le.short
                    bits = le.short.toInt() and 0xFFFF
                }
                0x61746164 -> { // data
                    dataPos = start
                    dataSize = size
                    break
                }
            }
            le.position(start + size + (size and 1))
        }
        require(format == 1 || format == 3) { "Only PCM/float WAV" }
        require(dataPos >= 0) { "No data chunk" }
        val pcm = data.copyOfRange(dataPos, (dataPos + dataSize).coerceAtMost(data.size))
        val samples = when {
            format == 3 && bits == 32 -> {
                val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
                IntArray(pcm.size / 4) { (bb.float * 32767f).toInt().coerceIn(-32768, 32767) }
            }
            bits == 16 -> {
                val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
                IntArray(pcm.size / 2) { bb.short.toInt() }
            }
            bits == 8 -> IntArray(pcm.size) { ((pcm[it].toInt() and 0xFF) - 128) shl 8 }
            bits == 24 -> {
                val n = pcm.size / 3
                IntArray(n) { i ->
                    val o = i * 3
                    val v = (pcm[o].toInt() and 0xFF) or
                        ((pcm[o + 1].toInt() and 0xFF) shl 8) or
                        ((pcm[o + 2].toInt()) shl 16)
                    v shr 8
                }
            }
            else -> error("Unsupported WAV bits=$bits")
        }
        return PcmBuffer(PcmFormat(sampleRate, channels, 16), samples)
    }
}
