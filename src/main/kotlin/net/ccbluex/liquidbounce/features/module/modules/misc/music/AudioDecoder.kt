package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.features.module.modules.misc.music.codec.AudioCodec
import net.ccbluex.liquidbounce.features.module.modules.misc.music.codec.PcmBuffer
import java.nio.file.Path

/**
 * 对外入口：纯 Kotlin 解码，无 FFmpeg / 无第三方 JNI。
 * 实现见 codec/（FlacDecoder / Mp3Decoder / WavDecoder）。
 */
object AudioDecoder {
    fun decode(path: Path): PcmBuffer = AudioCodec.decode(path)
    fun decode(data: ByteArray, ext: String): PcmBuffer = AudioCodec.decode(data, ext)
}
