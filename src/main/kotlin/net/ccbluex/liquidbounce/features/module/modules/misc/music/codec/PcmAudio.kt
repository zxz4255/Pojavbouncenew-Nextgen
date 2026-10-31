package net.ccbluex.liquidbounce.features.module.modules.misc.music.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

data class PcmFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int = 16,
) {
    val frameSize: Int get() = channels * (bitsPerSample / 8)
}

data class PcmBuffer(
    val format: PcmFormat,
    /** 交错 sample：ch0, ch1, ch0, ch1... 有符号 32bit 容器（有效位在低 bitsPerSample）。 */
    val samples: IntArray,
) {
    val frames: Int get() = samples.size / format.channels
    val durationMs: Long get() =
        if (format.sampleRate <= 0) 0 else frames.toLong() * 1000L / format.sampleRate

    /** 导出 s16le 字节（裁剪到 16bit）。 */
    fun toS16le(): ByteArray {
        val out = ByteArray(samples.size * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val v = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bb.putShort(v.toShort())
        }
        return out
    }
}

object AudioCodec {
    fun decode(path: Path): PcmBuffer {
        val bytes = Files.readAllBytes(path)
        return decode(bytes, path.extension.lowercase())
    }

    fun decode(data: ByteArray, ext: String): PcmBuffer = when (ext.lowercase()) {
        "flac" -> FlacDecoder.decode(data)
        "mp3" -> Mp3Decoder.decode(data)
        "wav" -> WavDecoder.decode(data)
        else -> error("纯 Kotlin 解码器支持 flac/mp3/wav，不支持 .$ext")
    }
}
