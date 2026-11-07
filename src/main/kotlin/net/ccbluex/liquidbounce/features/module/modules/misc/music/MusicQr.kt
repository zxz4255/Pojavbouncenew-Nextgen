package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.mojang.blaze3d.platform.NativeImage
import net.ccbluex.liquidbounce.render.drawBlitOnCurrentLayer
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.textureSetup
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import kotlin.math.abs

/**
 * Dependency-free QR Code encoder (byte mode, ISO/IEC 18004) used for the login hand-off.
 *
 * LiquidBounce ships no QR library and adding one for a single 60 byte URL is not worth a
 * dependency, so the encoder lives here. It is deliberately small: byte mode only, error correction
 * levels L/M/Q/H, versions 1..40, all eight mask patterns with the standard penalty scoring.
 *
 * This revision is source-only; no decoder or real-device scan validation was run.
 */
object QrEncoder {

    enum class Ecc(val formatBits: Int) { LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2) }

    class QrCode internal constructor(
        val version: Int,
        val size: Int,
        val mask: Int,
        private val modules: ByteArray,
    ) {
        fun isDark(x: Int, y: Int): Boolean = modules[y * size + x].toInt() != 0
        internal fun set(x: Int, y: Int, dark: Boolean) {
            modules[y * size + x] = if (dark) 1 else 0
        }
    }

    private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
        intArrayOf(
            -1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28,
            28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
        ),
        intArrayOf(
            -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26,
            26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
        ),
        intArrayOf(
            -1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30,
            28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
        ),
        intArrayOf(
            -1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28,
            30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
        ),
    )

    private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
        intArrayOf(
            -1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8,
            8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25,
        ),
        intArrayOf(
            -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16,
            17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
        ),
        intArrayOf(
            -1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20,
            23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68,
        ),
        intArrayOf(
            -1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25,
            25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81,
        ),
    )

    fun encode(text: String, ecc: Ecc = Ecc.MEDIUM): QrCode {
        val data = text.toByteArray(Charsets.UTF_8)
        var version = 1
        while (true) {
            if (version > 40) throw IllegalArgumentException("二维码内容过长")
            val capacityBits = getNumRawDataModules(version) -
                ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][version] * NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal][version] * 8
            if (4 + charCountBits(version) + data.size * 8 <= capacityBits) break
            version++
        }
        return build(data, version, ecc)
    }

    private fun build(data: ByteArray, version: Int, ecc: Ecc): QrCode {
        val size = version * 4 + 17
        val working = QrCode(version, size, -1, ByteArray(size * size))
        drawFunctionPatterns(working, ecc)
        drawCodewords(working, addEccAndInterleave(buildBitStream(data, version, ecc), version, ecc))

        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            applyMask(working, mask)
            drawFormatBits(working, ecc, mask)
            val penalty = penaltyScore(working)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
            }
            applyMask(working, mask)
        }

        // Rebuild from a clean matrix so the chosen mask is only applied once.
        val result = QrCode(version, size, bestMask, ByteArray(size * size))
        drawFunctionPatterns(result, ecc)
        drawCodewords(result, addEccAndInterleave(buildBitStream(data, version, ecc), version, ecc))
        applyMask(result, bestMask)
        drawFormatBits(result, ecc, bestMask)
        if (version >= 7) drawVersion(result)
        return result
    }

    private fun charCountBits(version: Int): Int = if (version <= 9) 8 else 16

    // --- bit stream ------------------------------------------------------------------------

    private fun buildBitStream(data: ByteArray, version: Int, ecc: Ecc): ByteArray {
        val capacityBits = getNumRawDataModules(version) -
            ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][version] * NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal][version] * 8
        val bits = ArrayList<Boolean>(capacityBits)
        fun append(value: Int, length: Int) {
            for (i in length - 1 downTo 0) bits.add(((value ushr i) and 1) != 0)
        }
        append(0b0100, 4)
        append(data.size, charCountBits(version))
        data.forEach { append(it.toInt() and 0xFF, 8) }
        append(0, minOf(4, capacityBits - bits.size))
        append(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < capacityBits) {
            append(pad, 8)
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }
        val bytes = ByteArray(bits.size / 8)
        bits.forEachIndexed { index, bit ->
            if (bit) bytes[index ushr 3] = (bytes[index ushr 3].toInt() or (1 shl (7 - (index and 7)))).toByte()
        }
        return bytes
    }

    private fun getNumRawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val numAlign = version / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    // --- Reed-Solomon ----------------------------------------------------------------------

    private fun rsComputeDivisor(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root: Byte = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMultiply(result[j].toInt() and 0xFF, root.toInt() and 0xFF).toByte()
                if (j + 1 < degree) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
            }
            root = gfMultiply(root.toInt() and 0xFF, 0x02).toByte()
        }
        return result
    }

    private fun rsComputeRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
        val result = ByteArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor result[0].toInt()
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in divisor.indices) {
                result[i] = (result[i].toInt() xor gfMultiply(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
        }
        return result
    }

    private fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xFF
    }

    private fun addEccAndInterleave(data: ByteArray, version: Int, ecc: Ecc): ByteArray {
        val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal][version]
        val blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][version]
        val rawCodewords = getNumRawDataModules(version) / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockLen = rawCodewords / numBlocks

        val blocks = ArrayList<ByteArray>(numBlocks)
        val divisor = rsComputeDivisor(blockEccLen)
        var offset = 0
        for (i in 0 until numBlocks) {
            val dataLen = shortBlockLen - blockEccLen + (if (i < numShortBlocks) 0 else 1)
            val dataBlock = data.copyOfRange(offset, offset + dataLen)
            offset += dataLen
            val eccBlock = rsComputeRemainder(dataBlock, divisor)
            // The trailing slot of every block is the interleave hole for short blocks.
            val block = ByteArray(shortBlockLen + 1)
            System.arraycopy(dataBlock, 0, block, 0, dataBlock.size)
            System.arraycopy(eccBlock, 0, block, block.size - blockEccLen, eccBlock.size)
            blocks += block
        }

        val result = ByteArray(rawCodewords)
        var index = 0
        for (i in 0 until shortBlockLen + 1) {
            for (j in blocks.indices) {
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                    result[index] = blocks[j][i]
                    index++
                }
            }
        }
        return result
    }

    // --- function patterns -----------------------------------------------------------------

    private fun drawFunctionPatterns(qr: QrCode, ecc: Ecc) {
        val size = qr.size
        for (i in 0 until size) {
            qr.set(6, i, i % 2 == 0)
            qr.set(i, 6, i % 2 == 0)
        }
        drawFinderPattern(qr, 3, 3)
        drawFinderPattern(qr, size - 4, 3)
        drawFinderPattern(qr, 3, size - 4)

        val align = alignmentPatternPositions(qr.version)
        val numAlign = align.size
        for (i in 0 until numAlign) {
            for (j in 0 until numAlign) {
                if ((i == 0 && j == 0) || (i == 0 && j == numAlign - 1) || (i == numAlign - 1 && j == 0)) continue
                drawAlignmentPattern(qr, align[i], align[j])
            }
        }
        drawFormatBits(qr, ecc, 0)
        if (qr.version >= 7) drawVersion(qr)
    }

    private fun drawFormatBits(qr: QrCode, ecc: Ecc, mask: Int) {
        val data = ecc.formatBits shl 3 or mask
        var rem = data
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        val bits = (data shl 10 or rem) xor 0x5412
        fun bit(i: Int) = (bits ushr i) and 1 != 0
        val size = qr.size
        for (i in 0..5) qr.set(8, i, bit(i))
        qr.set(8, 7, bit(6))
        qr.set(8, 8, bit(7))
        qr.set(7, 8, bit(8))
        for (i in 9..14) qr.set(14 - i, 8, bit(i))
        for (i in 0..7) qr.set(size - 1 - i, 8, bit(i))
        for (i in 8..14) qr.set(8, size - 15 + i, bit(i))
        qr.set(8, size - 8, true)
    }

    private fun drawVersion(qr: QrCode) {
        var rem = qr.version
        for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        val bits = qr.version shl 12 or rem
        for (i in 0 until 18) {
            val dark = (bits ushr i) and 1 != 0
            val a = qr.size - 11 + i % 3
            val b = i / 3
            qr.set(a, b, dark)
            qr.set(b, a, dark)
        }
    }

    private fun drawFinderPattern(qr: QrCode, x: Int, y: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val dist = maxOf(abs(dx), abs(dy))
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until qr.size && yy in 0 until qr.size) qr.set(xx, yy, dist != 2 && dist != 4)
            }
        }
    }

    private fun drawAlignmentPattern(qr: QrCode, x: Int, y: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                qr.set(x + dx, y + dy, maxOf(abs(dx), abs(dy)) != 1)
            }
        }
    }

    private fun alignmentPatternPositions(version: Int): IntArray {
        if (version == 1) return IntArray(0)
        val numAlign = version / 7 + 2
        val step = if (version == 32) 26 else (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
        val result = IntArray(numAlign)
        result[0] = 6
        var pos = version * 4 + 10
        for (i in numAlign - 1 downTo 1) {
            result[i] = pos
            pos -= step
        }
        return result
    }

    // --- placement ------------------------------------------------------------------------

    private fun drawCodewords(qr: QrCode, data: ByteArray) {
        var i = 0
        val size = qr.size
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction(qr, x, y) && i < data.size * 8) {
                        qr.set(x, y, (data[i ushr 3].toInt() ushr (7 - (i and 7)) and 1) != 0)
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    private fun isFunction(qr: QrCode, x: Int, y: Int): Boolean {
        val version = qr.version
        val size = qr.size
        if (x < 9 && y < 9) return true
        if (x >= size - 8 && y < 9) return true
        if (x < 9 && y >= size - 8) return true
        if (x == 6 || y == 6) return true
        val align = alignmentPatternPositions(version)
        for (i in align.indices) {
            for (j in align.indices) {
                val cx = align[i]
                val cy = align[j]
                if ((i == 0 && j == 0) || (i == 0 && j == align.size - 1) || (i == align.size - 1 && j == 0)) continue
                if (x in cx - 2..cx + 2 && y in cy - 2..cy + 2) return true
            }
        }
        if (version >= 7) {
            if (x < 6 && y >= size - 11 && y < size - 8) return true
            if (y < 6 && x >= size - 11 && x < size - 8) return true
        }
        return false
    }

    private fun applyMask(qr: QrCode, mask: Int) {
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (isFunction(qr, x, y)) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    7 -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    else -> false
                }
                if (invert) qr.set(x, y, !qr.isDark(x, y))
            }
        }
    }

    private fun penaltyScore(qr: QrCode): Int {
        val size = qr.size
        var penalty = 0
        for (y in 0 until size) {
            var run = 1
            for (x in 1 until size) {
                if (qr.isDark(x, y) == qr.isDark(x - 1, y)) run++ else {
                    if (run >= 5) penalty += run - 2
                    run = 1
                }
            }
            if (run >= 5) penalty += run - 2
        }
        for (x in 0 until size) {
            var run = 1
            for (y in 1 until size) {
                if (qr.isDark(x, y) == qr.isDark(x, y - 1)) run++ else {
                    if (run >= 5) penalty += run - 2
                    run = 1
                }
            }
            if (run >= 5) penalty += run - 2
        }
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = qr.isDark(x, y)
                if (c == qr.isDark(x + 1, y) && c == qr.isDark(x, y + 1) && c == qr.isDark(x + 1, y + 1)) penalty += 3
            }
        }
        val patternA = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        val patternB = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)
        for (y in 0 until size) {
            for (x in 0..size - 11) {
                var matchA = true
                var matchB = true
                for (k in 0..10) {
                    val dark = qr.isDark(x + k, y)
                    if (dark != patternA[k]) matchA = false
                    if (dark != patternB[k]) matchB = false
                }
                if (matchA) penalty += 40
                if (matchB) penalty += 40
            }
        }
        for (x in 0 until size) {
            for (y in 0..size - 11) {
                var matchA = true
                var matchB = true
                for (k in 0..10) {
                    val dark = qr.isDark(x, y + k)
                    if (dark != patternA[k]) matchA = false
                    if (dark != patternB[k]) matchB = false
                }
                if (matchA) penalty += 40
                if (matchB) penalty += 40
            }
        }
        var darkCount = 0
        for (y in 0 until size) for (x in 0 until size) if (qr.isDark(x, y)) darkCount++
        val total = size * size
        penalty += ((abs(darkCount * 20 - total * 10) + total - 1) / total - 1) * 10
        return penalty
    }
}

/**
 * Rasterises a [QrEncoder.QrCode] into one reusable GPU texture and blits it inside the screen.
 * The texture is rebuilt only when the payload changes, so a login poll never uploads per frame.
 */
object MusicQr {
    private const val MODULE_PIXELS = 4
    private const val QUIET_MODULES = 4

    private var texture: DynamicTexture? = null
    private var registered: Identifier? = null
    private var loadedText = ""

    fun draw(ctx: GuiGraphicsExtractor, x: Float, y: Float, size: Float, text: String) {
        if (text.isBlank()) return
        if (text != loadedText) {
            val code = runCatching { QrEncoder.encode(text) }.getOrNull() ?: return
            upload(code)
            loadedText = text
        }
        val tex = texture ?: return
        ctx.drawBlitOnCurrentLayer(
            tex.textureSetup,
            x.toInt(), y.toInt(), (x + size).toInt(), (y + size).toInt(),
            0f, 1f, 1f, 0f,
        )
    }

    private fun upload(code: QrEncoder.QrCode) {
        val pixels = (code.size + QUIET_MODULES * 2) * MODULE_PIXELS
        val image = NativeImage(pixels, pixels, true)
        // Quiet zone + module plate: white background, dark modules, no anti-aliasing.
        image.fillRect(0, 0, pixels, pixels, Color4b.WHITE.argb)
        val dark = Color4b(16, 21, 26).argb
        for (my in 0 until code.size) {
            for (mx in 0 until code.size) {
                if (!code.isDark(mx, my)) continue
                val px = (mx + QUIET_MODULES) * MODULE_PIXELS
                val py = (my + QUIET_MODULES) * MODULE_PIXELS
                image.fillRect(px, py, MODULE_PIXELS, MODULE_PIXELS, dark)
            }
        }
        val id = Identifier.fromNamespaceAndPath("liquidbounce", "music_login_qr")
        release()
        val uploaded = image.asTexture("Monet Music login QR")
        texture = uploaded
        registered = id
        mc.textureManager.register(id, uploaded)
    }

    private fun release() {
        val id = registered
        if (id != null) mc.textureManager.release(id) else texture?.close()
        texture = null
        registered = null
    }

    fun clear() {
        loadedText = ""
        release()
    }
}
