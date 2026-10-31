package net.ccbluex.liquidbounce.features.module.modules.misc.music.codec

/** 大端位流读取（FLAC / MP3 侧信息用）。 */
class BitReader(private val data: ByteArray, private var bytePos: Int = 0) {
    private var bitPos = 0 // 当前字节内已读位数 0..7
    val size: Int get() = data.size
    val remainingBytes: Int get() = (data.size - bytePos) - if (bitPos == 0) 0 else 0

    fun alignByte() {
        if (bitPos != 0) {
            bitPos = 0
            bytePos++
        }
    }

    fun eof(): Boolean = bytePos >= data.size || (bytePos == data.size - 1 && bitPos >= 8)

    fun readBit(): Int {
        if (bytePos >= data.size) error("bitstream overflow")
        val v = (data[bytePos].toInt() ushr (7 - bitPos)) and 1
        bitPos++
        if (bitPos == 8) {
            bitPos = 0
            bytePos++
        }
        return v
    }

    fun readBits(n: Int): Int {
        require(n in 0..32)
        var v = 0
        repeat(n) { v = (v shl 1) or readBit() }
        return v
    }

    fun readBitsLong(n: Int): Long {
        require(n in 0..64)
        var v = 0L
        repeat(n) { v = (v shl 1) or readBit().toLong() }
        return v
    }

    fun readUnary(): Int {
        var n = 0
        while (readBit() == 0) n++
        return n
    }

    fun peekBytePos(): Int = bytePos
    fun setBytePos(p: Int) {
        bytePos = p
        bitPos = 0
    }

    fun readRaw(n: Int): ByteArray {
        alignByte()
        require(bytePos + n <= data.size)
        val out = data.copyOfRange(bytePos, bytePos + n)
        bytePos += n
        return out
    }

    fun skipBytes(n: Int) {
        alignByte()
        bytePos += n
    }
}
