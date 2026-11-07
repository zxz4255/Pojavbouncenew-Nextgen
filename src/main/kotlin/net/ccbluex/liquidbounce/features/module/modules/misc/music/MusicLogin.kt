package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.utils.client.clientLogger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Account handling for 网易云 and 酷狗.
 *
 * Design rules:
 *  - Every method here is *user initiated*. Nothing logs in silently, nothing scrapes cookies.
 *  - QR (the default), phone + SMS code, e-mail + password and a manual cookie/token import are
 *    offered side by side, so a platform changing one endpoint never locks the user out.
 *  - Credentials never leave the machine: the only persisted state is the session cookie the
 *    platform returned plus the display name, stored in `liquidbounce/music/session.json`.
 *  - Playback rights are never escalated. A session only changes *which* public endpoint answers.
 */
object MusicLogin {
    private val logger = clientLogger("MonetMusic/Auth")
    private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "MonetMusic-Auth").apply { isDaemon = true } }

    data class QrState(
        val phase: QrPhase = QrPhase.IDLE,
        val key: String = "",
        val payload: String = "",
        val message: String = "点「刷新二维码」开始",
        val issuedAt: Long = 0L,
    )

    private val sessions = ConcurrentHashMap<MusicSource, MusicSession>()
    private val qrStates = ConcurrentHashMap<MusicSource, QrState>()
    private val inFlight = AtomicInteger()

    @Volatile
    var notice: String = ""

    @Volatile
    private var lastPollAt = 0L
    private val polling = ConcurrentHashMap.newKeySet<MusicSource>()
    private val revisions = ConcurrentHashMap<MusicSource, Int>()

    private val file: Path get() = ConfigSystem.rootFolder.toPath().resolve("music").resolve("session.json")

    val busy: Boolean get() = inFlight.get() > 0

    fun session(source: MusicSource): MusicSession? = sessions[source]

    fun qrState(source: MusicSource): QrState =
        qrStates[source] ?: QrState(message = if (source == MusicSource.LOCAL) "本地无需登录" else "未登录")

    fun isLoggedIn(source: MusicSource): Boolean = sessions[source] != null

    private fun submit(action: () -> Unit) {
        inFlight.incrementAndGet()
        worker.execute {
            try {
                action()
            } catch (e: Exception) {
                notice = e.message?.take(160) ?: "网络错误"
                logger.warn("Music account action failed: ${e.message}")
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    // --- QR ---------------------------------------------------------------------------------

    fun refreshQr(source: MusicSource) {
        if (source == MusicSource.LOCAL) return
        val revision = revisions.merge(source, 1) { a, b -> a + b } ?: 1
        qrStates[source] = QrState(QrPhase.IDLE, message = "正在获取二维码…")
        submit {
            try {
                val challenge = when (source) {
                    MusicSource.NETEASE -> neteaseQrChallenge()
                    MusicSource.KUGOU -> KugouApi.qrChallenge()
                    MusicSource.LOCAL -> return@submit
                }
                if (revisions[source] != revision) return@submit
                qrStates[source] = QrState(QrPhase.WAITING, challenge.key, challenge.payload,
                    "打开${source.label} App 扫一扫", System.currentTimeMillis())
            } catch (e: Exception) {
                if (revisions[source] == revision) qrStates[source] = QrState(QrPhase.FAILED,
                    message = e.message?.take(180) ?: "平台接口不可用")
            }
        }
    }

    /** Called from the screen; throttles itself so a render loop never spams the endpoint. */
    fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastPollAt < 1200L) return
        lastPollAt = now
        for (source in MusicSource.entries) {
            if (!source.supportsAccount) continue
            val state = qrStates[source] ?: continue
            if (state.phase != QrPhase.WAITING && state.phase != QrPhase.SCANNED) continue
            if (now - state.issuedAt > 180_000L) {
                qrStates[source] = state.copy(phase = QrPhase.EXPIRED, message = "二维码已过期，请刷新")
                continue
            }
            if (!polling.add(source)) continue
            submit {
                try { poll(source, state.key) }
                catch (e: Exception) {
                    if (qrStates[source]?.key == state.key) qrStates[source] = state.copy(
                        phase = QrPhase.FAILED, message = e.message?.take(160) ?: "轮询失败")
                } finally { polling.remove(source) }
            }
        }
    }

    private fun poll(source: MusicSource, key: String) {
        if (key.isBlank()) return
        val result = when (source) {
            MusicSource.NETEASE -> neteasePoll(key)
            MusicSource.KUGOU -> KugouApi.pollQr(key)
            MusicSource.LOCAL -> return
        }
        val current = qrStates[source] ?: return
        if (current.key != key) return
        when (result) {
            is QrPoll.Waiting -> qrStates[source] = current.copy(phase = QrPhase.WAITING, message = "等待扫码…")
            is QrPoll.Scanned -> qrStates[source] = current.copy(phase = QrPhase.SCANNED, message = "已扫码，请在手机上确认")
            is QrPoll.Expired -> qrStates[source] = current.copy(phase = QrPhase.EXPIRED, message = "二维码已过期，请刷新")
            is QrPoll.Failed -> qrStates[source] = current.copy(phase = QrPhase.FAILED, message = result.message)
            is QrPoll.Success -> {
                qrStates[source] = current.copy(phase = QrPhase.CONFIRMED, message = "登录成功")
                applySession(result.session)
            }
        }
    }

    // --- NetEase endpoints ------------------------------------------------------------------

    private fun neteaseQrChallenge(): QrChallenge {
        val root = MusicHttp.post("https://music.163.com/api/login/qrcode/unikey", mapOf("type" to "1"))
        val key = root.string("unikey").ifBlank { root.getAsJsonObject("data")?.string("unikey").orEmpty() }
        if (key.isBlank()) throw IOException("网易云二维码接口没有返回 key")
        return QrChallenge(key, "https://music.163.com/login?codekey=$key")
    }

    private fun neteasePoll(key: String): QrPoll {
        val root = MusicHttp.post("https://music.163.com/api/login/qrcode/client/login",
            mapOf("key" to key, "type" to "1"))
        return when (root.int("code")) {
            800 -> QrPoll.Expired
            801 -> QrPoll.Waiting
            802 -> QrPoll.Scanned
            803 -> {
                val profile = MusicHttp.json("https://music.163.com/api/nuser/account/get")
                val body = profile.getAsJsonObject("profile")
                    ?: return QrPoll.Failed("扫码完成但账号校验失败，请刷新后重试")
                if (body.string("userId").isBlank()) return QrPoll.Failed("账号缺少用户 ID")
                QrPoll.Success(
                    MusicSession(
                        MusicSource.NETEASE,
                        body?.string("userId") ?: "",
                        body?.string("nickname") ?: root.string("nickname").ifBlank { "网易云用户" },
                        vip = (body?.int("vipType") ?: 0) > 0,
                        detail = "扫码登录",
                    ),
                )
            }
            else -> QrPoll.Failed("网易云登录响应 ${root.int("code")}：${root.string("message")}")
        }
    }

    fun sendCode(source: MusicSource, phone: String) {
        if (phone.isBlank()) {
            notice = "请先输入手机号"
            return
        }
        submit {
            notice = when (source) {
                MusicSource.NETEASE -> {
                    val root = MusicHttp.post(
                        "https://music.163.com/api/sms/captcha/sent",
                        mapOf("cellphone" to phone, "ctcode" to "86"),
                    )
                    if (root.int("code") == 200) "验证码已发送" else "发送失败：${root.string("message")}"
                }
                MusicSource.KUGOU -> KugouApi.sendCode(phone).ifBlank { "验证码已发送" }
                MusicSource.LOCAL -> "本地无需登录"
            }
        }
    }

    fun loginPhone(source: MusicSource, phone: String, code: String) {
        if (phone.isBlank() || code.isBlank()) {
            notice = "请输入手机号与验证码"
            return
        }
        submit {
            notice = "登录中…"
            val session = when (source) {
                MusicSource.NETEASE -> {
                    val root = MusicHttp.post(
                        "https://music.163.com/api/login/cellphone",
                        mapOf("phone" to phone, "countrycode" to "86", "captcha" to code, "rememberLogin" to "true"),
                    )
                    if (root.int("code") != 200) throw IOException(root.string("message").ifBlank { "手机号登录失败" })
                    val body = runCatching { MusicHttp.json("https://music.163.com/api/nuser/account/get") }
                        .getOrNull()?.getAsJsonObject("profile")
                    MusicSession(
                        MusicSource.NETEASE,
                        body?.string("userId") ?: "",
                        body?.string("nickname") ?: "网易云用户",
                        vip = (body?.int("vipType") ?: 0) > 0,
                        detail = "手机号登录",
                    )
                }
                MusicSource.KUGOU -> KugouApi.loginByPhone(phone, code)
                MusicSource.LOCAL -> throw IOException("本地无需登录")
            }
            notice = "登录成功：${session.nickname}"
            applySession(session)
        }
    }

    fun loginEmail(source: MusicSource, email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            notice = "请输入账号与密码"
            return
        }
        submit {
            notice = "登录中…"
            if (source == MusicSource.KUGOU) {
                // Kugou has no public password endpoint for the web player; guide instead of guessing.
                notice = "酷狗请使用二维码或手机验证码；当前版本不伪造 Cookie 校验"
                return@submit
            }
            val root = MusicHttp.post(
                "https://music.163.com/api/login",
                mapOf("username" to email, "password" to MusicHttp.md5(password), "rememberLogin" to "true"),
            )
            if (root.int("code") != 200) throw IOException(root.string("message").ifBlank { "邮箱登录失败" })
            val body = runCatching { MusicHttp.json("https://music.163.com/api/nuser/account/get") }
                .getOrNull()?.getAsJsonObject("profile")
            val session = MusicSession(
                MusicSource.NETEASE,
                body?.string("userId") ?: "",
                body?.string("nickname") ?: "网易云用户",
                vip = (body?.int("vipType") ?: 0) > 0,
                detail = "邮箱登录",
            )
            notice = "登录成功：${session.nickname}"
            applySession(session)
        }
    }

    /** Manual fallback for both platforms: paste `name=value; name=value`. */
    fun loginToken(source: MusicSource, raw: String) {
        if (raw.isBlank()) {
            notice = "请粘贴 Cookie 或 Token"
            return
        }
        submit {
            notice = "校验中…"
            val session = when (source) {
                MusicSource.NETEASE -> {
                    raw.split(';', '&', '\n').mapNotNull { part ->
                        val index = part.indexOf('=')
                        if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
                    }.forEach { (name, value) -> MusicCookies.put("music.163.com", name, value) }
                    val body = runCatching { MusicHttp.json("https://music.163.com/api/nuser/account/get") }
                        .getOrNull()?.getAsJsonObject("profile")
                        ?: throw IOException("Cookie 无效或已过期")
                    MusicSession(
                        MusicSource.NETEASE,
                        body.string("userId"),
                        body.string("nickname").ifBlank { "网易云用户" },
                        vip = body.int("vipType") > 0,
                        detail = "Cookie 导入",
                    )
                }
                MusicSource.KUGOU -> KugouApi.loginByToken(raw)
                MusicSource.LOCAL -> throw IOException("本地无需登录")
            }
            notice = "登录成功：${session.nickname}"
            applySession(session)
        }
    }

    @Synchronized
    fun logout(source: MusicSource) {
        revisions.merge(source, 1) { a, b -> a + b }
        sessions.remove(source)
        qrStates.remove(source)
        MusicCookies.clear(if (source == MusicSource.NETEASE) "music.163.com" else "kugou.com")
        notice = "已退出${source.label}账号"
        persist()
    }

    @Synchronized
    private fun applySession(session: MusicSession) {
        if (session.userId.isBlank()) throw IOException("平台未返回有效用户 ID，不能视作登录成功")
        sessions[session.source] = session
        qrStates[session.source] = qrState(session.source).copy(
            phase = QrPhase.CONFIRMED,
            message = "已登录：${session.nickname}",
        )
        persist()
    }

    // --- persistence ------------------------------------------------------------------------

    fun restore() {
        // Restored cookies are candidates only; a saved nickname is not proof of authentication.
        val path = file
        if (!Files.isRegularFile(path)) return
        runCatching {
            val root = JsonParser.parseString(Files.readString(path)).asJsonObject
            root.getAsJsonArray("sessions")?.forEach { element ->
                val entry = element.asJsonObject
                val source = runCatching { MusicSource.valueOf(entry.string("source")) }.getOrNull() ?: return@forEach
                val cookies = entry.getAsJsonArray("cookies")
                cookies?.forEach { cookie ->
                    val item = cookie.asJsonObject
                    MusicCookies.put(item.string("domain"), item.string("name"), item.string("value"))
                }
                if (source == MusicSource.NETEASE) submit {
                    runCatching {
                        val profile = MusicHttp.json("https://music.163.com/api/nuser/account/get")
                            .getAsJsonObject("profile") ?: throw IOException("会话已过期")
                        applySession(MusicSession(source, profile.string("userId"), profile.string("nickname"),
                            profile.int("vipType") > 0, "已校验的本地会话"))
                    }.onFailure { notice = "${source.label}会话需重新认证" }
                }
                // r5 has no independent Kugou session validation API. Never auto-label it logged in.

            }
        }.onFailure { logger.warn("Could not restore music session: ${it.message}") }
    }

    @Synchronized
    private fun persist() {
        val root = JsonObject()
        val array = com.google.gson.JsonArray()
        sessions.values.forEach { session ->
            val entry = JsonObject()
            entry.addProperty("source", session.source.name)
            entry.addProperty("userId", session.userId)
            entry.addProperty("nickname", session.nickname)
            entry.addProperty("vip", session.vip)
            entry.addProperty("detail", session.detail)
            val cookies = com.google.gson.JsonArray()
            MusicCookies.snapshotPlatform(if (session.source == MusicSource.NETEASE) "163.com" else "kugou.com")
                .forEach { (domain, name, value) ->
                    val item = JsonObject()
                    item.addProperty("domain", domain)
                    item.addProperty("name", name)
                    item.addProperty("value", value)
                    cookies.add(item)
                }
            entry.add("cookies", cookies)
            array.add(entry)
        }
        root.add("sessions", array)
        runCatching {
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling("session.json.tmp")
            Files.writeString(temp, root.toString())
            try { Files.move(temp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { logger.warn("Could not persist music session: ${it.message}") }
    }
}

private fun JsonObject.string(name: String): String =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()

private fun JsonObject.int(name: String): Int =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrDefault(0) } ?: 0

private fun com.google.gson.JsonElement.string(name: String): String =
    if (isJsonObject) asJsonObject.string(name) else ""
