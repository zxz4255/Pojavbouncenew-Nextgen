package net.ccbluex.liquidbounce.features.module.modules.misc.music.codec

/**
 * 纯 Kotlin FLAC 解码（参考 FLAC 规格 / FFmpeg flacdec 流程）。
 *
 * 支持：STREAMINFO、多帧、CONSTANT/VERBATIM/FIXED/LPC 子帧、Rice/Rice2 残差、
 * Independent / Left-Side / Right-Side / Mid-Side 声道。
 * 不支持：Ogg-FLAC 封装、稀有的非标准块大小（会跳过并报错）。
 */
object FlacDecoder {

    fun decode(data: ByteArray): PcmBuffer {
        require(data.size >= 42) { "FLAC too small" }
        require(data[0] == 'f'.code.toByte() && data[1] == 'L'.code.toByte() &&
            data[2] == 'a'.code.toByte() && data[3] == 'C'.code.toByte()) { "Not FLAC" }

        var pos = 4
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var totalSamples = 0L

        // metadata blocks
        while (pos + 4 <= data.size) {
            val header = data[pos].toInt() and 0xFF
            val last = (header and 0x80) != 0
            val type = header and 0x7F
            val size = ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
            pos += 4
            require(pos + size <= data.size) { "Bad metadata size" }
            if (type == 0) { // STREAMINFO
                require(size >= 34) { "STREAMINFO too small" }
                // min/max block already at 0..3
                sampleRate = ((data[pos + 10].toInt() and 0xFF) shl 12) or
                    ((data[pos + 11].toInt() and 0xFF) shl 4) or
                    ((data[pos + 12].toInt() and 0xFF) ushr 4)
                channels = ((data[pos + 12].toInt() and 0x0E) ushr 1) + 1
                bitsPerSample = (((data[pos + 12].toInt() and 0x01) shl 4) or
                    ((data[pos + 13].toInt() and 0xFF) ushr 4)) + 1
                totalSamples = ((data[pos + 13].toLong() and 0x0F) shl 32) or
                    ((data[pos + 14].toLong() and 0xFF) shl 24) or
                    ((data[pos + 15].toLong() and 0xFF) shl 16) or
                    ((data[pos + 16].toLong() and 0xFF) shl 8) or
                    (data[pos + 17].toLong() and 0xFF)
            }
            pos += size
            if (last) break
        }

        require(sampleRate > 0 && channels in 1..8 && bitsPerSample in 4..32) {
            "Invalid STREAMINFO"
        }

        val out = ArrayList<Int>(if (totalSamples > 0) (totalSamples * channels).toInt().coerceAtMost(50_000_000) else 1 shl 20)
        val br = BitReader(data, pos)

        while (br.peekBytePos() + 4 < data.size) {
            // sync 0xFFF8..
            br.alignByte()
            var syncPos = br.peekBytePos()
            var found = false
            while (syncPos + 2 < data.size) {
                val b0 = data[syncPos].toInt() and 0xFF
                val b1 = data[syncPos + 1].toInt() and 0xFF
                if (b0 == 0xFF && (b1 and 0xFE) == 0xF8) {
                    found = true
                    break
                }
                syncPos++
            }
            if (!found) break
            br.setBytePos(syncPos)

            val frame = decodeFrame(br, channels, bitsPerSample, sampleRate) ?: break
            for (s in frame) out.add(s)
        }

        require(out.isNotEmpty()) { "No FLAC frames decoded" }
        // 统一到 16-bit 容器幅度（若源 >16，右移；<16 左移）
        val shift = bitsPerSample - 16
        val samples = IntArray(out.size) { i ->
            val v = out[i]
            when {
                shift > 0 -> v shr shift
                shift < 0 -> v shl (-shift)
                else -> v
            }
        }
        return PcmBuffer(PcmFormat(sampleRate, channels, 16), samples)
    }

    private fun decodeFrame(
        br: BitReader,
        streamChannels: Int,
        streamBps: Int,
        streamRate: Int,
    ): IntArray? {
        // sync already at start
        val sync = br.readBits(15)
        if (sync != 0x7FFC) return null
        br.readBit() // reserved
        val blockStrategy = br.readBit()
        val blockSizeCode = br.readBits(4)
        val sampleRateCode = br.readBits(4)
        val channelCode = br.readBits(4)
        val bpsCode = br.readBits(3)
        br.readBit() // reserved0

        // UTF-8 coded sample/frame number
        readUtf8(br)

        val blockSize = when (blockSizeCode) {
            1 -> 192
            in 2..5 -> 576 shl (blockSizeCode - 2)
            6 -> br.readBits(8) + 1
            7 -> br.readBits(16) + 1
            in 8..15 -> 256 shl (blockSizeCode - 8)
            else -> return null
        }

        when (sampleRateCode) {
            0 -> streamRate
            1 -> 88200
            2 -> 176400
            3 -> 192000
            4 -> 8000
            5 -> 16000
            6 -> 22050
            7 -> 24000
            8 -> 32000
            9 -> 44100
            10 -> 48000
            11 -> 96000
            12 -> {
                br.readBits(8); streamRate
            }
            13 -> {
                br.readBits(16); streamRate
            }
            14 -> {
                br.readBits(16); streamRate
            }
            else -> return null
        }

        val channels = when (channelCode) {
            in 0..7 -> channelCode + 1
            in 8..10 -> 2
            else -> return null
        }
        if (channels != streamChannels && channelCode !in 8..10) {
            // 允许与 streaminfo 不一致的少见情况
        }

        val bps = when (bpsCode) {
            0 -> streamBps
            1 -> 8
            2 -> 12
            4 -> 16
            5 -> 20
            6 -> 24
            7 -> 32
            else -> return null
        }

        br.readBits(8) // frame header CRC-8（不校验以简化）

        val subframes = Array(channels) { IntArray(blockSize) }
        for (ch in 0 until channels) {
            var bpsCh = bps
            if (channelCode == 8 && ch == 1) bpsCh++ // left-side: side extra bit
            if (channelCode == 9 && ch == 0) bpsCh++ // right-side
            if (channelCode == 10 && ch == 1) bpsCh++ // mid-side
            decodeSubframe(br, subframes[ch], bpsCh)
        }

        br.alignByte()
        br.readBits(16) // frame footer CRC-16 skip

        // channel decorrelation
        when (channelCode) {
            8 -> { // left, side(diff)
                for (i in 0 until blockSize) {
                    val side = subframes[1][i]
                    val left = subframes[0][i]
                    subframes[1][i] = left - side
                }
            }
            9 -> { // side, right
                for (i in 0 until blockSize) {
                    val side = subframes[0][i]
                    val right = subframes[1][i]
                    subframes[0][i] = right + side
                }
            }
            10 -> { // mid, side
                for (i in 0 until blockSize) {
                    val mid = subframes[0][i]
                    val side = subframes[1][i]
                    val left = mid + (side shr 1)
                    val right = mid - (side shr 1)
                    subframes[0][i] = left
                    subframes[1][i] = right
                }
            }
        }

        val interleaved = IntArray(blockSize * channels)
        var o = 0
        for (i in 0 until blockSize) {
            for (ch in 0 until channels) interleaved[o++] = subframes[ch][i]
        }
        return interleaved
    }

    private fun decodeSubframe(br: BitReader, out: IntArray, bps: Int) {
        br.readBit() // padding 0
        val type = br.readBits(6)
        val wasted = if (br.readBit() == 1) br.readUnary() + 1 else 0
        val effectiveBps = bps - wasted

        when {
            type == 0 -> { // CONSTANT
                val v = br.readBitsLong(effectiveBps).signExtend(effectiveBps).toInt()
                out.fill(v)
            }
            type == 1 -> { // VERBATIM
                for (i in out.indices) {
                    out[i] = br.readBitsLong(effectiveBps).signExtend(effectiveBps).toInt()
                }
            }
            type in 8..12 -> { // FIXED
                val order = type - 8
                decodeFixed(br, out, order, effectiveBps)
            }
            type in 32..63 -> { // LPC
                val order = (type and 0x1F) + 1
                decodeLpc(br, out, order, effectiveBps)
            }
            else -> error("Unsupported subframe type $type")
        }
        if (wasted > 0) for (i in out.indices) out[i] = out[i] shl wasted
    }

    private fun decodeFixed(br: BitReader, out: IntArray, order: Int, bps: Int) {
        for (i in 0 until order) {
            out[i] = br.readBitsLong(bps).signExtend(bps).toInt()
        }
        decodeResiduals(br, out, order)
        when (order) {
            0 -> Unit
            1 -> for (i in 1 until out.size) out[i] += out[i - 1]
            2 -> for (i in 2 until out.size) out[i] += 2 * out[i - 1] - out[i - 2]
            3 -> for (i in 3 until out.size) out[i] += 3 * out[i - 1] - 3 * out[i - 2] + out[i - 3]
            4 -> for (i in 4 until out.size)
                out[i] += 4 * out[i - 1] - 6 * out[i - 2] + 4 * out[i - 3] - out[i - 4]
        }
    }

    private fun decodeLpc(br: BitReader, out: IntArray, order: Int, bps: Int) {
        for (i in 0 until order) {
            out[i] = br.readBitsLong(bps).signExtend(bps).toInt()
        }
        val coeffPrecision = br.readBits(4) + 1
        val coeffShift = br.readBitsLong(5).signExtend(5).toInt()
        val coeffs = IntArray(order)
        for (i in 0 until order) {
            coeffs[i] = br.readBitsLong(coeffPrecision).signExtend(coeffPrecision).toInt()
        }
        decodeResiduals(br, out, order)
        for (i in order until out.size) {
            var sum = 0L
            for (j in 0 until order) sum += coeffs[j].toLong() * out[i - 1 - j]
            out[i] += (sum shr coeffShift).toInt()
        }
    }

    private fun decodeResiduals(br: BitReader, out: IntArray, order: Int) {
        val method = br.readBits(2)
        require(method <= 1) { "unsupported residual method" }
        val partitionOrder = br.readBits(4)
        val partitions = 1 shl partitionOrder
        val partitionSize = out.size shr partitionOrder
        require(partitionSize >= order) { "bad residual partition" }

        var sample = order
        for (p in 0 until partitions) {
            val riceParam = if (method == 0) br.readBits(4) else br.readBits(5)
            val start = if (p == 0) order else 0
            if (riceParam == (if (method == 0) 15 else 31)) {
                val bits = br.readBits(5)
                for (i in start until partitionSize) {
                    out[sample++] = br.readBitsLong(bits).signExtend(bits).toInt()
                }
            } else {
                for (i in start until partitionSize) {
                    out[sample++] = readRice(br, riceParam)
                }
            }
        }
    }

    private fun readRice(br: BitReader, param: Int): Int {
        val q = br.readUnary()
        val r = if (param > 0) br.readBits(param) else 0
        val v = (q shl param) or r
        // zigzag
        return if ((v and 1) == 0) v ushr 1 else -((v ushr 1) + 1)
    }

    private fun readUtf8(br: BitReader): Long {
        val b0 = br.readBits(8)
        if (b0 < 0x80) return b0.toLong()
        var mask = 0xE0
        var len = 1
        when {
            b0 and 0xE0 == 0xC0 -> { mask = 0x1F; len = 1 }
            b0 and 0xF0 == 0xE0 -> { mask = 0x0F; len = 2 }
            b0 and 0xF8 == 0xF0 -> { mask = 0x07; len = 3 }
            b0 and 0xFC == 0xF8 -> { mask = 0x03; len = 4 }
            b0 and 0xFE == 0xFC -> { mask = 0x01; len = 5 }
            else -> return 0
        }
        var v = (b0 and mask).toLong()
        repeat(len) {
            val c = br.readBits(8)
            v = (v shl 6) or (c and 0x3F).toLong()
        }
        return v
    }

    private fun Long.signExtend(bits: Int): Long {
        if (bits <= 0 || bits >= 64) return this
        val shift = 64 - bits
        return (this shl shift) shr shift
    }
}
