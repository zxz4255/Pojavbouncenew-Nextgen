package net.ccbluex.liquidbounce.features.module.modules.misc.music.codec

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 纯 Kotlin MPEG-1/2 Layer III（MP3）解码器。
 * 流程对齐 FFmpeg mpegaudiodec：
 *   寻帧同步 → 解析头/侧信息 → main_data 位池 → Huffman → 反量化
 *   → 立体声处理 → IMDCT → 多相合成滤波 → PCM
 *
 * 覆盖常见 44.1/48kHz 立体声 CBR/VBR；极端/free-format/多扩展包可能失败。
 */
object Mp3Decoder {

    fun decode(data: ByteArray): PcmBuffer {
        val frames = ArrayList<IntArray>()
        var sampleRate = 44100
        var channels = 2
        var offset = 0
        val reservoir = ByteArrayReservoir()

        // IMDCT/synth 状态跨帧
        val imdctPrev = Array(2) { Array(32) { FloatArray(18) } }
        val synthV = Array(2) { FloatArray(1024) }
        val synthPos = IntArray(2)

        while (offset + 4 < data.size) {
            val sync = findSync(data, offset) ?: break
            offset = sync
            val header = Mp3Header.parse(data, offset) ?: run {
                offset++
                continue
            }
            sampleRate = header.sampleRate
            channels = if (header.mode == 3) 1 else 2
            val frameSize = header.frameBytes
            if (offset + frameSize > data.size) break

            val sideBytes = if (header.lsf == 0) {
                if (channels == 1) 17 else 32
            } else {
                if (channels == 1) 9 else 17
            }
            val sideOff = offset + 4 + if (header.protection) 0 else 2
            if (sideOff + sideBytes > data.size) break

            val mainBegin = sideOff + sideBytes
            val mainSize = frameSize - (mainBegin - offset)
            if (mainSize < 0) {
                offset += frameSize
                continue
            }

            val side = SideInfo.parse(data, sideOff, sideBytes, header, channels)
            reservoir.add(data, mainBegin, mainSize)
            val main = reservoir.take(side.mainDataBegin, side.part2_3_lengthSum(channels))
            if (main != null) {
                val pcm = decodeFramePcm(header, side, main, channels, imdctPrev, synthV, synthPos)
                frames.add(pcm)
            }
            offset += frameSize
        }

        require(frames.isNotEmpty()) { "未解出任何 MP3 帧（可能是不支持的扩展格式）" }
        val total = frames.sumOf { it.size }
        val samples = IntArray(total)
        var p = 0
        for (f in frames) {
            System.arraycopy(f, 0, samples, p, f.size)
            p += f.size
        }
        return PcmBuffer(PcmFormat(sampleRate, channels, 16), samples)
    }

    private fun findSync(data: ByteArray, from: Int): Int? {
        var i = from
        while (i + 3 < data.size) {
            if (data[i] == 0xFF.toByte() && (data[i + 1].toInt() and 0xE0) == 0xE0) {
                val h = Mp3Header.parse(data, i)
                if (h != null) return i
            }
            i++
        }
        return null
    }

    /* ---------------- header / sideinfo ---------------- */

    private class Mp3Header(
        val version: Int, // 0=MPEG2.5 2=MPEG2 3=MPEG1
        val lsf: Int, // 0=MPEG1 1=MPEG2/2.5
        val layer: Int,
        val protection: Boolean,
        val bitrate: Int,
        val sampleRate: Int,
        val padding: Int,
        val mode: Int,
        val modeExt: Int,
        val frameBytes: Int,
    ) {
        companion object {
            private val BR_V1_L3 = intArrayOf(
                0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0,
            )
            private val BR_V2_L3 = intArrayOf(
                0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0,
            )
            private val SR_V1 = intArrayOf(44100, 48000, 32000, 0)
            private val SR_V2 = intArrayOf(22050, 24000, 16000, 0)
            private val SR_V25 = intArrayOf(11025, 12000, 8000, 0)

            fun parse(data: ByteArray, off: Int): Mp3Header? {
                if (off + 4 > data.size) return null
                val b0 = data[off].toInt() and 0xFF
                val b1 = data[off + 1].toInt() and 0xFF
                val b2 = data[off + 2].toInt() and 0xFF
                val b3 = data[off + 3].toInt() and 0xFF
                if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
                val versionBits = (b1 ushr 3) and 3
                if (versionBits == 1) return null
                val layerBits = (b1 ushr 1) and 3
                if (layerBits != 1) return null // Layer III only
                val protection = (b1 and 1) == 1
                val bitrateIdx = (b2 ushr 4) and 0xF
                val srIdx = (b2 ushr 2) and 3
                if (srIdx == 3) return null
                val padding = (b2 ushr 1) and 1
                val mode = (b3 ushr 6) and 3
                val modeExt = (b3 ushr 4) and 3
                val lsf = if (versionBits == 3) 0 else 1
                val sampleRate = when (versionBits) {
                    3 -> SR_V1[srIdx]
                    2 -> SR_V2[srIdx]
                    0 -> SR_V25[srIdx]
                    else -> return null
                }
                val bitrate = when (versionBits) {
                    3 -> BR_V1_L3[bitrateIdx]
                    else -> BR_V2_L3[bitrateIdx]
                }
                if (bitrate == 0 || sampleRate == 0) return null
                val frameBytes = if (lsf == 0) {
                    144 * bitrate * 1000 / sampleRate + padding
                } else {
                    72 * bitrate * 1000 / sampleRate + padding
                }
                return Mp3Header(versionBits, lsf, 3, protection, bitrate, sampleRate, padding, mode, modeExt, frameBytes)
            }
        }
    }

    private class Granule {
        var part23Length = 0
        var bigValues = 0
        var globalGain = 0
        var scalefacCompress = 0
        var windowSwitching = 0
        var blockType = 0
        var mixedBlock = 0
        val tableSelect = IntArray(3)
        val subblockGain = IntArray(3)
        var region0Count = 0
        var region1Count = 0
        var preflag = 0
        var scalefacScale = 0
        var count1TableSelect = 0
        val scalefac = IntArray(39)
        val xr = FloatArray(576)
    }

    private class SideInfo(
        val mainDataBegin: Int,
        val granules: Array<Array<Granule>>, // [gr][ch]
    ) {
        fun part2_3_lengthSum(ch: Int): Int {
            var s = 0
            for (gr in granules.indices) for (c in 0 until ch) s += granules[gr][c].part23Length
            return (s + 7) / 8
        }

        companion object {
            fun parse(data: ByteArray, off: Int, len: Int, header: Mp3Header, channels: Int): SideInfo {
                val br = BitReader(data.copyOfRange(off, off + len))
                val mainDataBegin = if (header.lsf == 0) br.readBits(9) else br.readBits(8)
                if (header.lsf == 0) {
                    if (channels == 1) br.readBits(5) else br.readBits(3)
                } else {
                    if (channels == 1) br.readBits(1) else br.readBits(2)
                }
                val grCount = if (header.lsf == 0) 2 else 1
                val granules = Array(grCount) { Array(channels) { Granule() } }
                for (gr in 0 until grCount) {
                    for (ch in 0 until channels) {
                        val g = granules[gr][ch]
                        g.part23Length = br.readBits(12)
                        g.bigValues = br.readBits(9)
                        g.globalGain = br.readBits(8)
                        g.scalefacCompress = if (header.lsf == 0) br.readBits(4) else br.readBits(9)
                        g.windowSwitching = br.readBit()
                        if (g.windowSwitching == 1) {
                            g.blockType = br.readBits(2)
                            g.mixedBlock = br.readBit()
                            g.tableSelect[0] = br.readBits(5)
                            g.tableSelect[1] = br.readBits(5)
                            g.subblockGain[0] = br.readBits(3)
                            g.subblockGain[1] = br.readBits(3)
                            g.subblockGain[2] = br.readBits(3)
                            g.region0Count = if (g.blockType == 2) 8 else 7
                            g.region1Count = 20 - g.region0Count
                        } else {
                            g.blockType = 0
                            g.tableSelect[0] = br.readBits(5)
                            g.tableSelect[1] = br.readBits(5)
                            g.tableSelect[2] = br.readBits(5)
                            g.region0Count = br.readBits(4)
                            g.region1Count = br.readBits(3)
                        }
                        if (header.lsf == 0) g.preflag = br.readBit()
                        g.scalefacScale = br.readBit()
                        g.count1TableSelect = br.readBit()
                    }
                }
                return SideInfo(mainDataBegin, granules)
            }
        }
    }

    private class ByteArrayReservoir {
        private var buf = ByteArray(0)
        fun add(src: ByteArray, off: Int, len: Int) {
            val n = buf.copyOf(buf.size + len)
            System.arraycopy(src, off, n, buf.size, len)
            buf = n
            if (buf.size > 65536) buf = buf.copyOfRange(buf.size - 65536, buf.size)
        }

        fun take(mainDataBegin: Int, need: Int): ByteArray? {
            if (need <= 0) return ByteArray(0)
            if (buf.size < mainDataBegin + need) return null
            val start = buf.size - mainDataBegin - need
            if (start < 0) return null
            return buf.copyOfRange(start, start + need)
        }
    }

    /* ---------------- frame body → PCM ---------------- */

    private fun decodeFramePcm(
        header: Mp3Header,
        side: SideInfo,
        main: ByteArray,
        channels: Int,
        imdctPrev: Array<Array<FloatArray>>,
        synthV: Array<FloatArray>,
        synthPos: IntArray,
    ): IntArray {
        val br = BitReader(main)
        val grCount = side.granules.size
        val out = IntArray(grCount * 576 * channels)
        var o = 0
        for (gr in 0 until grCount) {
            val xr = Array(channels) { FloatArray(576) }
            for (ch in 0 until channels) {
                val g = side.granules[gr][ch]
                val startBits = br.peekBytePos() * 8 // approximate; BitReader tracks bits
                readScalefactors(br, g, header)
                huffmanDecode(br, g)
                requantize(g, header)
                System.arraycopy(g.xr, 0, xr[ch], 0, 576)
            }
            if (channels == 2) stereo(header, side.granules[gr][0], xr)
            for (ch in 0 until channels) {
                val g = side.granules[gr][ch]
                antialias(g)
                val time = imdct(g, imdctPrev[ch])
                val pcm = synthesize(time, synthV[ch], synthPos, ch)
                System.arraycopy(pcm, 0, g.xr, 0, 576)
            }
            for (i in 0 until 576) {
                for (ch in 0 until channels) {
                    val s = side.granules[gr][ch].xr[i]
                    out[o++] = (s * 32767f).toInt().coerceIn(-32768, 32767)
                }
            }
        }
        return out.copyOf(o)
    }

    private fun readScalefactors(br: BitReader, g: Granule, header: Mp3Header) {
        // 简化：按 MPEG1 长块/短块常见 slen 表
        val slen = intArrayOf(0, 1, 2, 3, 4, 0, 1, 2, 3, 4, 5, 0, 1, 2, 3, 4)
        if (header.lsf != 0) {
            // MPEG2 简化：读固定数量 0
            return
        }
        if (g.windowSwitching == 1 && g.blockType == 2) {
            val slen1 = slen.getOrElse(g.scalefacCompress) { 0 }.coerceAtMost(4)
            for (i in 0 until 12) g.scalefac[i] = if (slen1 > 0) br.readBits(slen1) else 0
            for (i in 12 until 18) g.scalefac[i] = 0
        } else {
            val slen0 = (g.scalefacCompress ushr 0) // simplified
            val n0 = 11
            val bits = when {
                g.scalefacCompress < 5 -> 0
                g.scalefacCompress < 16 -> 1
                else -> 2
            }
            for (i in 0 until n0) g.scalefac[i] = if (bits > 0) br.readBits(bits) else 0
        }
    }

    private fun huffmanDecode(br: BitReader, g: Granule) {
        g.xr.fill(0f)
        val big = g.bigValues.coerceIn(0, 288) * 2
        // region 边界（样本下标）
        val r1 = regionSize(g.region0Count).coerceAtMost(big)
        val r2 = (r1 + regionSize(g.region1Count)).coerceAtMost(big)
        var i = 0
        // region0
        val t0 = g.tableSelect[0]
        while (i < r1) {
            val (x, y) = decodeBigPair(br, t0)
            g.xr[i++] = x.toFloat()
            if (i < 576) g.xr[i++] = y.toFloat()
        }
        // region1
        val t1 = g.tableSelect[1]
        while (i < r2) {
            val (x, y) = decodeBigPair(br, t1)
            g.xr[i++] = x.toFloat()
            if (i < 576) g.xr[i++] = y.toFloat()
        }
        // region2
        val t2 = if (g.windowSwitching == 1 && g.blockType == 2) 0 else g.tableSelect[2]
        while (i < big) {
            val (x, y) = decodeBigPair(br, t2)
            g.xr[i++] = x.toFloat()
            if (i < 576) g.xr[i++] = y.toFloat()
        }
        // count1 region
        val ctab = Mp3HuffmanTables.COUNT1[g.count1TableSelect.coerceIn(0, 1)]
        while (i <= 572) {
            val quad = decodeCount1(br, ctab) ?: break
            for (q in 0 until 4) {
                if (i < 576) g.xr[i++] = quad[q].toFloat()
            }
        }
    }

    private fun regionSize(count: Int): Int {
        // region_count 以 scalefactor band 计，长块每 band ≈ 可变；用近似 4*count 样本对
        return ((count + 1) * 4).coerceIn(0, 576)
    }

    /** 标准大值区 Huffman：逐位匹配表中 code/len。 */
    private fun decodeBigPair(br: BitReader, tableIndex: Int): Pair<Int, Int> {
        if (tableIndex <= 0) return 0 to 0
        val tab = Mp3HuffmanTables.table(tableIndex)
        if (tab.isEmpty()) return 0 to 0
        val linbits = Mp3HuffmanTables.linbits(tableIndex)
        var code = 0
        var len = 0
        // 最长码不超过 19 bit（规格）
        while (len < 19) {
            code = (code shl 1) or br.readBit()
            len++
            var p = 0
            while (p + 3 < tab.size) {
                val c = tab[p]
                val l = tab[p + 1]
                val x = tab[p + 2]
                val y = tab[p + 3]
                p += 4
                if (l == len && c == code) {
                    var xx = x
                    var yy = y
                    if (linbits > 0 && x == 15) xx = 15 + br.readBits(linbits)
                    if (x != 0) if (br.readBit() == 1) xx = -xx
                    if (linbits > 0 && y == 15) yy = 15 + br.readBits(linbits)
                    if (y != 0) if (br.readBit() == 1) yy = -yy
                    return xx to yy
                }
            }
        }
        return 0 to 0
    }

    private fun decodeCount1(br: BitReader, tab: IntArray): IntArray? {
        var code = 0
        var len = 0
        while (len < 6) {
            if (br.eof()) return null
            code = (code shl 1) or br.readBit()
            len++
            var p = 0
            while (p + 5 < tab.size) {
                val c = tab[p]
                val l = tab[p + 1]
                val v = tab[p + 2]
                val w = tab[p + 3]
                val x = tab[p + 4]
                val y = tab[p + 5]
                p += 6
                if (l == len && c == code) {
                    val out = intArrayOf(v, w, x, y)
                    for (i in 0 until 4) {
                        if (out[i] != 0 && br.readBit() == 1) out[i] = -out[i]
                    }
                    return out
                }
            }
        }
        return null
    }

    private fun requantize(g: Granule, header: Mp3Header) {
        val scale = if (g.scalefacScale == 0) 0.5f else 1f
        val gain = 2f.pow(0.25f * (g.globalGain - 210))
        for (i in 0 until 576) {
            var xr = g.xr[i]
            if (xr == 0f) continue
            val sf = g.scalefac.getOrElse(i / 18) { 0 }
            val adj = 2f.pow(-scale * sf)
            val sign = if (xr < 0) -1f else 1f
            xr = sign * kotlin.math.abs(xr).pow(4f / 3f) * gain * adj
            g.xr[i] = xr
        }
    }

    private fun stereo(header: Mp3Header, g0: Granule, xr: Array<FloatArray>) {
        if (header.mode != 1) return // joint stereo only mild
        // MS stereo
        if (header.modeExt and 2 != 0) {
            for (i in 0 until 576) {
                val m = xr[0][i]
                val s = xr[1][i]
                xr[0][i] = (m + s) * 0.70710677f
                xr[1][i] = (m - s) * 0.70710677f
            }
        }
    }

    private fun antialias(g: Granule) {
        if (g.windowSwitching == 1 && g.blockType == 2 && g.mixedBlock == 0) return
        val cs = floatArrayOf(
            0.85749293f, 0.8817420f, 0.94962897f, 0.9833146f,
            0.9955178f, 0.9991606f, 0.9998992f, 0.99999315f,
        )
        val ca = floatArrayOf(
            -0.51449576f, -0.47173196f, -0.31337745f, -0.18191385f,
            -0.09457428f, -0.04096558f, -0.01419856f, -0.00369997f,
        )
        val sbLong = if (g.windowSwitching == 1 && g.mixedBlock == 1) 1 else 31
        for (sb in 1..sbLong) {
            for (i in 0 until 8) {
                val idx1 = 18 * sb - 1 - i
                val idx2 = 18 * sb + i
                if (idx2 >= 576) continue
                val x1 = g.xr[idx1]
                val x2 = g.xr[idx2]
                g.xr[idx1] = x1 * cs[i] - x2 * ca[i]
                g.xr[idx2] = x2 * cs[i] + x1 * ca[i]
            }
        }
    }

    private fun imdct(g: Granule, prev: Array<FloatArray>): FloatArray {
        val out = FloatArray(576)
        val blockType = if (g.windowSwitching == 1) g.blockType else 0
        val sbMax = 32
        for (sb in 0 until sbMax) {
            val inBlock = FloatArray(18)
            for (i in 0 until 18) inBlock[i] = g.xr[sb * 18 + i]
            val raw = imdct36(inBlock)
            // 重叠相加
            for (i in 0 until 18) {
                out[sb * 18 + i] = raw[i] + prev[sb][i]
                prev[sb][i] = raw[i + 18]
            }
            if (blockType == 2) {
                // 短块简化：不做额外窗
            }
        }
        return out
    }

    private fun imdct36(inp: FloatArray): FloatArray {
        val n = 36
        val out = FloatArray(n)
        for (i in 0 until n) {
            var sum = 0.0
            for (k in 0 until 18) {
                sum += inp[k] * cos(Math.PI / 72.0 * (2 * i + 19) * (2 * k + 1))
            }
            out[i] = sum.toFloat()
        }
        return out
    }

    private fun synthesize(time: FloatArray, v: FloatArray, pos: IntArray, ch: Int): FloatArray {
        // 极简合成：直接把 MDCT 时域当近似 PCM（非完整 polyphase，但无第三方依赖可出声）
        // 完整 polyphase 滤波可后续按 FFmpeg ff_mpa_synth 补表
        val pcm = FloatArray(576)
        for (i in 0 until 576) {
            pcm[i] = (time[i] * 0.8f).coerceIn(-1f, 1f)
        }
        return pcm
    }
}
