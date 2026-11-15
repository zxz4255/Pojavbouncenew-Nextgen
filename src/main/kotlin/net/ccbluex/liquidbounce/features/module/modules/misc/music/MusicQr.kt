package net.ccbluex.liquidbounce.features.module.modules.misc.music

/**
 * QR encoding for the NetEase login flow.
 *
 * <p>NetEase has no endpoint that renders the login QR; the web client builds the
 * `https://music.163.com/login?codekey=<unikey>` URL locally and draws the code itself, so the port
 * has to encode it too. The encoder is the module's own [QrEncoder] (a byte-exact port of the
 * reference ZXing algorithm the original client embedded, verified by an OpenCV round-trip), which
 * keeps the module dependency-free.</p>
 */
object MusicQr {

    /**
     * Encodes `content` into a module matrix with a `quietZone`-module border, indexed `[y][x]`.
     *
     * @return the matrix, or null when the content cannot be encoded
     */
    fun encode(content: String, quietZone: Int): Array<BooleanArray>? = runCatching {
        val code = QrEncoder.encode(content)
        val total = code.size + quietZone * 2
        Array(total) { y ->
            BooleanArray(total) { x ->
                val gx = x - quietZone
                val gy = y - quietZone
                gx >= 0 && gy >= 0 && gx < code.size && gy < code.size && code.isDark(gx, gy)
            }
        }
    }.getOrNull()
}
