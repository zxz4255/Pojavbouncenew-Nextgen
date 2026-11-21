package net.ccbluex.liquidbounce.features.module.modules.misc.music

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal HTTP + cookie plumbing for the NetEase web API.
 *
 * <p>Ported verbatim from NeoRaven's `music.NeteaseHttp`. The cookie jar is shared and thread safe:
 * the QR login flow writes into it from the polling thread while the UI reads the csrf token from the
 * render thread. Requests go straight through [HttpURLConnection] — the module owns its own transport
 * rather than routing through a client helper, which is also why the NetEase hosts need no allowlist
 * entry anywhere.</p>
 */
object NeteaseHttp {

    private val UTF8 = StandardCharsets.UTF_8

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"

    private val COOKIES = ConcurrentHashMap<String, String>()

    @Volatile
    private var anonymousCookie = ""

    class Response(val status: Int, body: String?) {
        val body: String = body ?: ""

        fun ok(): Boolean = status in 200..299
    }

    /** POSTs a url-encoded form to the weapi endpoint for `path`. */
    fun weapi(path: String, json: String, capture: Boolean = true): Response {
        val form = NeteaseCrypto.weapi(json)
        val suffix = if (path.startsWith("/")) path else "/$path"
        return request("POST", "https://music.163.com/weapi$suffix", encode(form), capture)
    }

    /** POSTs a url-encoded form to an arbitrary endpoint (the plaintext API family). */
    fun post(url: String, form: String?): Response = request("POST", url, form)

    fun get(url: String): Response = request("GET", url, null)

    @Volatile private var sessionGeneration = 0L

    private fun request(method: String, url: String, form: String?, capture: Boolean = true): Response {
        val generation = sessionGeneration
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.instanceFollowRedirects = true
            connection.requestMethod = method
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Referer", "https://music.163.com")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            // The web client sends a mainland IP hint; the CDN uses it to pick a region edge.
            connection.setRequestProperty("X-Real-IP", "116.25.146.177")
            connection.setRequestProperty("X-Forwarded-For", "116.25.146.177")
            val cookie = cookieHeader()
            if (cookie.isNotEmpty()) {
                connection.setRequestProperty("Cookie", cookie)
            }
            if (form != null) {
                val bytes = form.toByteArray(UTF8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setRequestProperty("Content-Length", bytes.size.toString())
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            synchronized(this) {
                if (capture && generation == sessionGeneration) captureCookies(connection.headerFields)
            }
            val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
            val body = read(stream)
            return Response(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun read(stream: InputStream?): String {
        if (stream == null) return ""
        return try {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val read = stream.read(chunk)
                if (read <= 0) break
                buffer.write(chunk, 0, read)
            }
            String(buffer.toByteArray(), UTF8)
        } finally {
            try {
                stream.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun captureCookies(headers: Map<String, List<String>>?) {
        if (headers == null) return
        for ((name, values) in headers) {
            if (name == null || !name.equals("Set-Cookie", ignoreCase = true) || values == null) continue
            for (header in values) store(header)
        }
    }

    /** Parses one `Set-Cookie` header into the jar. */
    fun store(header: String?) {
        if (header == null) return
        val semicolon = header.indexOf(';')
        val pair = if (semicolon >= 0) header.substring(0, semicolon) else header
        val equals = pair.indexOf('=')
        if (equals <= 0) return
        val name = pair.substring(0, equals).trim()
        val value = pair.substring(equals + 1).trim()
        if (name.isEmpty()) return
        if (value.isEmpty() || value == "deleted") {
            COOKIES.remove(name)
            return
        }
        COOKIES[name] = value
    }

    /** Feeds a raw `name=value; name2=value2` cookie string into the jar. */
    fun storeAll(raw: String?) {
        if (raw.isNullOrEmpty()) return
        for (part in raw.split(";")) {
            val piece = part.trim()
            val equals = piece.indexOf('=')
            if (equals > 0) {
                COOKIES[piece.substring(0, equals).trim()] = piece.substring(equals + 1).trim()
            }
        }
    }

    fun hasCookie(name: String): Boolean = COOKIES.containsKey(name)

    /** Serialises the jar into a `Cookie` header, adding the fields the web client sends. */
    fun cookieHeader(): String {
        val out = StringBuilder()
        if (anonymousCookie.isNotEmpty()) out.append(anonymousCookie)
        for ((name, value) in COOKIES) {
            if (out.isNotEmpty()) out.append("; ")
            out.append(name).append('=').append(value)
        }
        if (out.isNotEmpty()) out.append("; ")
        out.append("os=pc; appver=8.9.70; channel=netease;")
        return out.toString()
    }

    /** The csrf token is the `__csrf` cookie (or an empty string when logged out). */
    fun csrf(): String = COOKIES["__csrf"] ?: ""

    fun musicU(): String? = COOKIES["MUSIC_U"]

    fun loggedIn(): Boolean = musicU()?.isNotEmpty() == true

    fun setAnonymousCookie(value: String?) {
        anonymousCookie = value ?: ""
    }

    /** Drops the login cookies but keeps the anonymous device cookie. */
    @Synchronized fun logout() {
        sessionGeneration++
        COOKIES.remove("MUSIC_U")
        COOKIES.remove("__csrf")
        COOKIES.remove("NMTID")
        COOKIES.remove("MUSIC_A_T")
        COOKIES.remove("MUSIC_R_T")
    }

    fun clear() {
        COOKIES.clear()
        anonymousCookie = ""
    }

    /** Everything the jar holds, so a session can be persisted between launches. */
    fun snapshot(): String = COOKIES.entries.joinToString(";") { (k, v) -> "$k=$v" }

    internal fun encode(form: Map<String, String>): String {
        val out = StringBuilder()
        for ((key, value) in form) {
            if (out.isNotEmpty()) out.append('&')
            out.append(percent(key)).append('=').append(percent(value))
        }
        return out.toString()
    }

    private fun percent(value: String?): String = try {
        URLEncoder.encode(value ?: "", "UTF-8")
    } catch (t: Throwable) {
        ""
    }
}
