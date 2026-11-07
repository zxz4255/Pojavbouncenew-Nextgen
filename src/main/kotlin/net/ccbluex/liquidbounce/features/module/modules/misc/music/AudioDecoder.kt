package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.utils.audio.codec.jflac.FLACDecoder
import net.ccbluex.liquidbounce.utils.audio.codec.jflac.util.ByteData
import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.Bitstream
import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.Decoder
import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.Header
import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.SampleBuffer
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The only codec entry point used by the module.
 *
 * Frame decoder -> interleaved PCM -> JavaSound. Like FFmpeg's demux/decode/audio separation,
 * networking and rendering never parse codec frames. The numerical kernels are the LGPL Java
 * kernels supplied in the reference, vendored under utils/audio/codec/; this is NOT a claim to reimplement
 * FFmpeg. No native library, service-side transcoding, ScriptEngine or codec Maven artifact is used.
 */
object AudioDecoder {
    const val MAX_FILE_BYTES = 100L * 1024 * 1024
    const val MAX_FRAME_SAMPLES = 65536

    data class Format(val sampleRate: Int, val channels: Int, val totalSamples: Long, val codec: String) {
        val durationMs: Long get() = if (sampleRate > 0) totalSamples * 1000 / sampleRate else 0
        val label: String get() = "$codec · ${sampleRate / 1000.0} kHz · ${if (channels == 1) "单声道" else "立体声"}"
    }

    fun open(path: Path, startMs: Long = 0, cancelled: () -> Boolean = { false }): Session {
        if (!java.nio.file.Files.isRegularFile(path)) throw IOException("音频文件不存在：$path")
        val size = java.nio.file.Files.size(path)
        if (size > MAX_FILE_BYTES) throw IOException("音频文件超过 100 MiB 安全上限")
        return open(path.toFile().inputStream(), size, startMs, cancelled)
    }

    /** Incremental decoder: bounded prefix sniff, never writes network audio to disk. */
    fun open(source: InputStream, length: Long, startMs: Long = 0,
             cancelled: () -> Boolean = { false }): Session {
        val input = object : BufferedInputStream(source, 64 * 1024) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (cancelled()) throw IOException("解码已取消")
                return super.read(b, off, len)
            }
            override fun read(): Int {
                if (cancelled()) throw IOException("解码已取消")
                return super.read()
            }
        }
        return try {
            input.mark(256 * 1024 + 1)
            // A bounded prefix is replayed by the buffered stream, including ID3 and Xing/LAME.
            val prefix = input.readNBytes(64 * 1024)
            input.reset()
            val magic = prefix.take(4).toByteArray().toString(Charsets.US_ASCII)
            val flac = magic == "fLaC"
            val id3 = magic.startsWith("ID3")
            val mpeg = prefix.size >= 2 && (prefix[0].toInt() and 255) == 255 &&
                (prefix[1].toInt() and 224) == 224
            when {
                flac -> FlacSession(input, length, startMs.coerceAtLeast(0), cancelled)
                id3 || mpeg -> Mp3Session(input, length, readGaplessInfo(prefix), startMs.coerceAtLeast(0), cancelled)
                else -> throw IOException("不是 MP3 或 FLAC（按文件头识别）")
            }
        } catch (e: Exception) {
            runCatching { input.close() }
            throw e
        }
    }

    /** One frame, little-endian signed 16-bit interleaved PCM; ownership stays with the session. */
    class PcmFrame(val data: ByteArray, val validBytes: Int, val validSamples: Int)

    interface Session : Closeable {
        val format: Format
        var positionSamples: Long
        fun nextFrame(): PcmFrame?
    }

    private fun checkFormat(rate: Int, channels: Int) {
        if (rate !in 8000..192000 || channels !in 1..2) throw IOException("不支持的音频参数：$rate Hz / $channels 声道")
    }

    private class Mp3Session(
        private val input: InputStream,
        private val fileBytes: Long,
        private val gapless: Gapless,
        start: Long,
        private val cancelled: () -> Boolean,
    ) : Session {
        private var decodedSamples = 0L
        private var skippedInfoFrame = false
        private val bits = Bitstream(input)
        private val decoder = Decoder()
        private var header: Header? = null
        override var positionSamples = 0L
        override lateinit var format: Format
        private var pending: PcmFrame? = null

        init {
            val first = bits.readFrame() ?: throw IOException("MP3 中没有可解码音频帧")
            header = first
            val channels = if (first.mode() == Header.SINGLE_CHANNEL) 1 else 2
            checkFormat(first.frequency(), channels)
            val frameSamples = if (first.version() == Header.MPEG1) 1152L else 576L
            val encodedSamples: Long = if (gapless.frames > 0) gapless.frames * frameSamples
                else if (fileBytes > 0) (first.totalMs(fileBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).toDouble() * first.frequency() / 1000).toLong() else 0L
            val total = if (gapless.trusted) (encodedSamples - gapless.trimStart - gapless.trimEnd).coerceAtLeast(0L) else encodedSamples
            format = Format(first.frequency(), channels, total, "MP3")
            pending = decode(first, 0)
            seek(start * format.sampleRate / 1000)
        }

        private fun decode(h: Header, offset: Int): PcmFrame {
            val buffer = decoder.decodeFrame(h, bits) as SampleBuffer
            bits.closeFrame()
            val channels = format.channels
            val rawFrames = buffer.bufferLength / channels
            // Xing/Info frame is metadata, not part of the advertised audio-frame count.
            // JLayer decodes it as silence; exclude it before applying LAME delay/padding.
            if (!skippedInfoFrame && gapless.frames > 0) { skippedInfoFrame = true; return PcmFrame(ByteArray(0), 0, 0) }
            val rawStart = decodedSamples
            decodedSamples += rawFrames
            // LAME/Xing stores encoder delay and padding in audio frames. Trim both, otherwise
            // every MP3 adds a silent tail and lyrics drift relative to a gapless reference.
            val delay = if (gapless.trusted) gapless.trimStart else 0L
            val padding = if (gapless.trusted) gapless.trimEnd else 0L
            val begin = max(offset / channels, (delay - rawStart).coerceAtLeast(0L).toInt())
            val samplesPerFrame = if (h.version() == Header.MPEG1) 1152L else 576L
            val end = if (gapless.trusted) minOf(rawFrames, (gapless.frames * samplesPerFrame - padding - rawStart).coerceIn(0L, rawFrames.toLong()).toInt()) else rawFrames
            val valid = (end - begin).coerceAtLeast(0) * channels
            val bytes = ByteArray(valid * 2)
            val samples = buffer.buffer
            for (i in 0 until valid) {
                val sample = samples[begin * channels + i].toInt()
                bytes[i * 2] = (sample and 255).toByte()
                bytes[i * 2 + 1] = ((sample shr 8) and 255).toByte()
            }
            return PcmFrame(bytes, bytes.size, valid / channels)
        }

        private fun seek(target: Long) {
            var frame = pending ?: nextDecoded() ?: return
            pending = null
            while (frame.validSamples == 0 || positionSamples + frame.validSamples <= target) {
                if (cancelled()) throw IOException("解码已取消")
                positionSamples += frame.validSamples
                frame = nextDecoded() ?: return
            }
            if (frame.validSamples == 0) { pending = null; return }
            val skip = (target - positionSamples).toInt() * format.channels
            if (skip > 0) {
                frame = PcmFrame(frame.data.copyOfRange(skip * 2, frame.validBytes), frame.validBytes - skip * 2,
                    frame.validSamples - skip / format.channels)
            }
            positionSamples = target
            pending = frame
        }

        private fun nextDecoded(): PcmFrame? {
            val h = bits.readFrame() ?: return null
            if (h.frequency() != format.sampleRate ||
                (if (h.mode() == Header.SINGLE_CHANNEL) 1 else 2) != format.channels) {
                throw IOException("MP3 流中途改变采样率或声道，已停止而不是输出错速音频")
            }
            header = h
            return decode(h, 0)
        }

        override fun nextFrame(): PcmFrame? {
            if (cancelled()) return null
            while (true) {
                val result = pending ?: nextDecoded() ?: return null
                pending = null
                positionSamples += result.validSamples
                if (result.validSamples > 0) return result
            }
        }

        override fun close() = input.close()
    }

    private data class Gapless(val frames: Long, val delay: Long, val padding: Long, val trusted: Boolean) {
        // JLayer's synthesis filterbank contributes 528 samples of latency on MPEG-1 Layer III.
        val trimStart: Long get() = delay + 528L
        val trimEnd: Long get() = (padding - 528L).coerceAtLeast(0L)
    }

    /** Reads only the first MPEG frame; no whole-file scan and no metadata library. */
    private fun readGaplessInfo(bytes: ByteArray): Gapless {
        var base = 0
        if (bytes.size >= 10 && bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "ID3") {
            val size = ((bytes[6].toInt() and 127) shl 21) or ((bytes[7].toInt() and 127) shl 14) or
                ((bytes[8].toInt() and 127) shl 7) or (bytes[9].toInt() and 127)
            base = size + 10 + if (bytes[5].toInt() and 16 != 0) 10 else 0
        }
        fun u32(i: Int): Long {
            val a = (bytes[i].toInt() and 255).toLong()
            val b = (bytes[i + 1].toInt() and 255).toLong()
            val c = (bytes[i + 2].toInt() and 255).toLong()
            val d = (bytes[i + 3].toInt() and 255).toLong()
            return a * 16777216L + b * 65536L + c * 256L + d
        }
        for (i in base until bytes.size - 180) {
            if (bytes[i].toInt() and 255 != 255 || bytes[i+1].toInt() and 224 != 224) continue
            val version = bytes[i+1].toInt() shr 3 and 3
            if (version == 1 || bytes[i+1].toInt() shr 1 and 3 != 1) continue
            val mono = bytes[i+3].toInt() ushr 6 == 3
            val xing = i + 4 + if (version == 3) (if (mono) 17 else 32) else (if (mono) 9 else 17)
            if (xing + 120 + 24 >= bytes.size) continue
            val tag = bytes.copyOfRange(xing, xing + 4).toString(Charsets.US_ASCII)
            if (tag != "Xing" && tag != "Info") continue
            val flags = u32(xing + 4)
            val frames = if ((flags and 1L) == 1L) u32(xing + 8).takeIf { it in 1..10_000_000 } ?: 0L else 0L
            var lame = xing + 8
            if ((flags and 1L) != 0L) lame += 4
            if ((flags and 2L) != 0L) lame += 4
            if ((flags and 4L) != 0L) lame += 100
            if ((flags and 8L) != 0L) lame += 4
            if (lame + 24 > bytes.size) continue
            val encoder = bytes.copyOfRange(lame, lame + 4).toString(Charsets.US_ASCII)
            // Only trust the 24-bit delay/padding fields for MPEG-1 Layer III Xing tags written
            // by LAME or FFmpeg's Lavc. Other streams fall back to untrimmed, estimated duration.
            val trusted = frames > 0 && version == 3 && (encoder == "LAME" || encoder == "Lavc")
            val a = bytes[lame + 21].toInt() and 255
            val b = bytes[lame + 22].toInt() and 255
            val c = bytes[lame + 23].toInt() and 255
            val delay = (a shl 4 or (b ushr 4)).toLong()
            val padding = ((b and 15) shl 8 or c).toLong()
            return Gapless(frames, delay, padding, trusted && delay in 0..4095 && padding in 0..4095)
        }
        return Gapless(0, 0, 0, false)
    }

    private class FlacSession(
        private val input: InputStream,
        fileBytes: Long,
        start: Long,
        private val cancelled: () -> Boolean,
    ) : Session {
        private val decoder = FLACDecoder(input)
        override var positionSamples = 0L
        override val format: Format
        private var pending: PcmFrame? = null
        private var badFrames = 0
        private val reusablePcm = ByteData(0)

        init {
            decoder.readMetadata()
            val info = decoder.streamInfo ?: throw IOException("FLAC 缺少 STREAMINFO")
            checkFormat(info.sampleRate, info.channels)
            if (info.bitsPerSample !in setOf(8, 16, 24)) throw IOException("此 FLAC 位深暂不支持：${info.bitsPerSample} bit")
            format = Format(info.sampleRate, info.channels, info.totalSamples, "FLAC ${info.bitsPerSample} bit")
            seek(start * format.sampleRate / 1000)
        }

        private fun nextDecoded(): PcmFrame? {
            while (!cancelled()) {
                val frame = try {
                    decoder.readNextFrame() ?: return null
                } catch (e: EOFException) {
                    return null
                } catch (e: IOException) {
                    if (++badFrames > 4) throw IOException("FLAC 帧校验连续失败", e)
                    continue
                }
                if (frame.header.sampleRate != format.sampleRate || frame.header.channels != format.channels || frame.header.blockSize > MAX_FRAME_SAMPLES) {
                    throw IOException("FLAC 帧参数异常")
                }
                badFrames = 0
                val pcm = decoder.decodeFrame(frame, reusablePcm)
                val samples = frame.header.blockSize
                val channels = format.channels
                val bytes = ByteArray(samples * channels * 2)
                val source = pcm.data
                val depth = decoder.streamInfo.bitsPerSample
                val step = (depth + 7) / 8
                for (i in 0 until samples * channels) {
                    val off = i * step
                    val sample = when (depth) {
                        8 -> ((source[off].toInt() and 255) - 128) shl 8
                        16 -> (source[off].toInt() and 255) or (source[off + 1].toInt() shl 8)
                        else -> {
                            val v = (source[off].toInt() and 255) or
                                ((source[off + 1].toInt() and 255) shl 8) or
                                (source[off + 2].toInt() shl 16)
                            // Round 24 -> 16 rather than wrap overflow.
                            ((v + 128) shr 8).coerceIn(-32768, 32767)
                        }
                    }
                    bytes[i * 2] = (sample and 255).toByte()
                    bytes[i * 2 + 1] = ((sample shr 8) and 255).toByte()
                }
                return PcmFrame(bytes, bytes.size, samples)
            }
            return null
        }

        private fun seek(target: Long) {
            while (!cancelled()) {
                val frame = nextDecoded() ?: break
                if (positionSamples + frame.validSamples > target) {
                    val skip = (target - positionSamples).toInt() * format.channels
                    pending = PcmFrame(frame.data.copyOfRange(skip * 2, frame.validBytes),
                        frame.validBytes - skip * 2, frame.validSamples - skip / format.channels)
                    positionSamples = target
                    return
                }
                positionSamples += frame.validSamples
            }
        }

        override fun nextFrame(): PcmFrame? {
            if (cancelled()) return null
            val frame = pending ?: nextDecoded() ?: return null
            pending = null
            positionSamples += frame.validSamples
            return frame
        }

        override fun close() = input.close()
    }
}

/** Bounded, click-free software gain and channel folding. Keeps one code path on all JavaSound devices. */
class PcmProcessor(inputChannels: Int, private val outputChannels: Int = 2) {
    private val channels = inputChannels
    private var gain = 0f
    fun process(input: ByteArray, valid: Int, targetGain: Float): ByteArray {
        val frames = valid / (channels * 2)
        val output = ByteArray(frames * outputChannels * 2)
        val endGain = targetGain.coerceIn(0f, 1f)
        for (i in 0 until frames) {
            gain += (endGain - gain) * 0.002f
            fun sample(channel: Int): Int {
                val at = (i * channels + channel) * 2
                return (input[at].toInt() and 255) or (input[at + 1].toInt() shl 8)
            }
            for (out in 0 until outputChannels) {
                val value = when {
                    channels == 1 -> sample(0)
                    outputChannels == 1 -> (0 until channels).sumOf { sample(it) } / channels
                    channels == 2 -> sample(out)
                    out < 2 -> (sample(out) * 0.70f + sample(2) * 0.30f).roundToInt()
                    else -> 0
                }
                val scaled = max(-32768, kotlin.math.min(32767, (value * gain).roundToInt()))
                val at = (i * outputChannels + out) * 2
                output[at] = (scaled and 255).toByte()
                output[at + 1] = ((scaled shr 8) and 255).toByte()
            }
        }
        return output
    }
}