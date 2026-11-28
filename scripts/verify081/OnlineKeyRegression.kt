package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.utils.audio.parser.KggDecryptor
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.function.BooleanSupplier

fun main() {
    var checks = 0
    fun ok(b: Boolean) { check(b) { "081 assertion $checks" }; checks++ }
    fun failure(kind: KugouPlayback.FailureKind, action: () -> Unit) {
        try { action(); error("Expected $kind") }
        catch (e: KugouPlayback.Failure) { ok(e.kind == kind); ok(!e.message.orEmpty().contains("syntheticToken081")) }
    }
    fun cancelled(action: () -> Unit) {
        try { action(); error("Expected cancellation") } catch (_: InterruptedIOException) { checks++ }
    }
    val project = File(System.getProperty("music.test.project"))
    val root = File(System.getProperty("music.test.root")).apply { mkdirs() }
    val vectors = File(project, "scripts/verify079/vectors")
    val manifest = JsonParser.parseString(File(vectors, "synthetic.json").readText()).asJsonArray.map { it.asJsonObject }
    val vector = manifest.first()
    val hash = vector["hash"].asString
    val key = vector["ekey"].asString
    val encrypted = File(vectors, vector["name"].asString).readBytes()
    val noCancel = BooleanSupplier { false }
    val session = KugouPlayback.Session(123456789, "syntheticToken081", "12345678901234567890123456789012", "syntheticDfid081")
    val references = JsonParser.parseString(File(project, "scripts/verify081/reference-request.json").readText()).asJsonArray
    for (element in references) {
        val ref = element.asJsonObject
        val request = KugouOnlineKeys.request("0123456789abcdef0123456789abcdef", session, ref["concept"].asBoolean, 1700000000123)
        ok(request.body == ref["body"].asString) // Exact JSON serialization, not just an equivalent object.
        ok(request.params == ref["params"].asJsonObject.entrySet().associate { it.key to it.value.asString })
        ok(request.path == ref["path"].asString && request.headers["x-router"] == ref["router"].asString)
        ok(request.base == "https://gateway.kugou.com")
        ok(!request.toString().contains(session.token) && !session.toString().contains(session.token))
        ok(request.headers.keys.none { it.equals("X-Forwarded-For", true) || it.equals("X-Real-IP", true) })
        val body = JsonParser.parseString(request.body).asJsonObject
        ok(body["vip"].asInt == 0 && body["tracker_param"].asJsonObject["viptoken"].asString.isEmpty())
        ok(!body["resource"].asJsonObject.has("album_audio_id"))
    }
    fun row(h: String = hash, k: String = key) = JsonObject().apply {
        addProperty("hash", h); addProperty("quality", "flac"); addProperty("status", 1)
        addProperty("_errno", 0); addProperty("fail_process", 0)
        add("info", JsonObject().apply {
            addProperty("tracker_status", 1); addProperty("tracker_type", "full"); addProperty("en_ekey", k)
            addProperty("en_hash", "e".repeat(32))
        })
    }
    fun response(r: JsonObject = row()) = JsonObject().apply { addProperty("status", 1); addProperty("error_code", 0); add("data", JsonArray().apply { add(r) }) }
    fun parse(r: JsonObject) = KugouOnlineKeys.parse(r, hash)
    ok(parse(response()) == key)
    ok(parse(response(row(hash.uppercase()))) == key)
    ok(parse(response(row("f".repeat(32)).apply { add("relate_goods", JsonArray().apply { add(row()) }) })) == key)
    failure(KugouPlayback.FailureKind.PROTOCOL) { parse(response(row("f".repeat(32)))) }
    failure(KugouPlayback.FailureKind.PROTOCOL) { parse(response(row("f".repeat(32)).apply { getAsJsonObject("info").addProperty("en_hash", hash) })) }
    failure(KugouPlayback.FailureKind.API) { parse(response().apply { addProperty("status", 0); addProperty("error_msg", session.token) }) }
    failure(KugouPlayback.FailureKind.LOGIN) { parse(response().apply { addProperty("error_code", 35005) }) }
    failure(KugouPlayback.FailureKind.API) { parse(response().apply { addProperty("error_code", 35002) }) }
    for ((field, value) in listOf("status" to 0, "_errno" to 5, "fail_process" to 12)) {
        failure(KugouPlayback.FailureKind.API) { parse(response(row().apply { addProperty(field, value) })) }
        failure(KugouPlayback.FailureKind.API) { parse(response(row().apply { remove(field) })) }
    }
    for ((field, value) in listOf("tracker_status" to "2", "tracker_type" to "preview", "tracker_type" to "none"))
        failure(KugouPlayback.FailureKind.API) { parse(response(row().apply { getAsJsonObject("info").addProperty(field, value) })) }
    for (flag in listOf("is_free_part", "IsFreePart", "is_trial", "preview")) {
        failure(KugouPlayback.FailureKind.API) { parse(response(row().apply { getAsJsonObject("info").addProperty(flag, 1) })) }
        failure(KugouPlayback.FailureKind.API) { parse(response().apply { addProperty(flag, true) }) }
    }
    for (bad in listOf("", "A".repeat(16385), "!".repeat(48), "a".repeat(24), "AAA===", "null"))
        failure(KugouPlayback.FailureKind.PROTOCOL) { parse(response(row(k = bad))) }
    failure(KugouPlayback.FailureKind.PROTOCOL) { parse(response().apply { add("data", JsonObject()) }) }
    failure(KugouPlayback.FailureKind.PROTOCOL) { parse(response().apply { getAsJsonArray("data").add(row(k = manifest.last()["ekey"].asString)) }) }
    failure(KugouPlayback.FailureKind.PROTOCOL) { parse(response().apply { repeat(256) { getAsJsonArray("data").add(row()) } }) }
    // A denied parent must not prevent a separately-authorized exact-match relate_goods row.
    ok(parse(response(row("f".repeat(32)).apply { addProperty("status", 0); add("relate_goods", JsonArray().apply { add(row()) }) })) == key)

    var calls = 0; var stale = false; var stop = false
    val wire = KugouOnlineKeys(KugouPlayback.Transport { request ->
        calls++; ok(request.params["token"] == session.token); response()
    }, { 1700000000123 })
    ok(wire.fetch(hash, session, true, noCancel) { true } == key && calls == 1)
    cancelled { wire.fetch(hash, session, true, BooleanSupplier { true }) { true } }
    ok(calls == 1)
    failure(KugouPlayback.FailureKind.LOGIN) { wire.fetch(hash, session, true, noCancel) { false } }
    ok(calls == 1)
    val late = KugouOnlineKeys(KugouPlayback.Transport { stale = true; response() })
    failure(KugouPlayback.FailureKind.LOGIN) { late.fetch(hash, session, true, noCancel) { !stale } }
    val stopped = KugouOnlineKeys(KugouPlayback.Transport { stop = true; response() })
    cancelled { stopped.fetch(hash, session, true, BooleanSupplier { stop }) { true } }
    failure(KugouPlayback.FailureKind.NETWORK) {
        KugouOnlineKeys(KugouPlayback.Transport { throw IOException("https://example.invalid/?token=${session.token}") }).fetch(hash, session, true, noCancel) { true }
    }
    failure(KugouPlayback.FailureKind.PROTOCOL) {
        KugouOnlineKeys(KugouPlayback.Transport { throw IllegalArgumentException(key) }).fetch(hash, session, true, noCancel) { true }
    }
    // Actual v5 key lookup -> fake authorized API transport -> production decrypt -> byte equality.
    var lookups = 0
    val local = KugouLocalFiles(File(root, "local")) { h, cancel ->
        lookups++; ok(h == hash); wire.fetch(h, session, true, cancel) { true }
    }
    val source = File(local.inputDir, "synthetic.kgg.flac").apply { writeBytes(encrypted) }
    val report = local.importFiles(noCancel)
    ok(report.succeeded == 1 && report.failed == 0 && lookups == 1)
    fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    ok(sha(local.file(local.songs().single())) == vector["plain_sha256"].asString)
    ok(source.readBytes().contentEquals(encrypted))
    ok(!local.keyFile.exists() && !local.mmkvFile.exists())
    ok(root.walkTopDown().filter { it.isFile }.none { it.extension in listOf("log", "json") && it.readText().contains(key) })
    // Local keys win, including with an offline or rejected account. No persistent online cache.
    local.keyFile.writeText("{\"$hash\":\"$key\"}")
    ok(local.importFiles(noCancel).succeeded == 1 && lookups == 1)
    local.keyFile.delete()
    ok(local.importFiles(noCancel).succeeded == 1 && lookups == 2)
    // v3 never looks up a key; cancellation never publishes an output.
    val v3 = File(project, "references/kgg079/python-kgm_v3_sample.kgm")
    check(v3.exists()); local.decrypt(v3, File(root, "v3.audio"), noCancel); ok(lookups == 2)
    val denied = KugouLocalFiles(File(root, "denied")) { _, _ -> throw KugouPlayback.Failure(KugouPlayback.FailureKind.API, "test", detail = "拒绝") }
    File(denied.inputDir, "denied.kgg").writeBytes(encrypted)
    ok(denied.importFiles(noCancel).failed == 1 && denied.songs().isEmpty())
    ok(denied.outputDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    val cancelledLocal = KugouLocalFiles(File(root, "cancel")) { h, _ -> ok(h == hash); stop = true; key }
    stop = false
    val output = File(root, "cancel.audio")
    cancelled { cancelledLocal.decrypt(source, output, BooleanSupplier { stop }) }
    ok(!output.exists())
    val wrong = KugouLocalFiles(File(root, "wrong")) { _, _ -> manifest.last()["ekey"].asString }
    try { wrong.decrypt(source, File(root, "wrong.audio"), noCancel); error("Wrong key accepted") } catch (_: IOException) { checks++ }
    ok(!File(root, "wrong.audio").exists())
    KugouClient.clearLogin()
    ok(KugouClient.onlineKey(hash, noCancel) == null) // No network for a logged-out account.
    val guard = KugouClient.accountGuard(); ok(guard())
    KugouClient.token = "changed"; ok(!guard())
    failure(KugouPlayback.FailureKind.LOGIN) { KugouClient.onlineKey(hash, noCancel) }
    KugouClient.clearLogin()
    val loggedOutGuard = KugouClient.accountGuard(); KugouClient.clearLogin(); ok(!loggedOutGuard())

    // Exercise actual HttpTransport + Client snapshot + Manager download without an external call.
    // HTTPS URL connections are intercepted process-wide in THIS test JVM only.
    var apiCalls = 0
    var apiResponse = response()
    var onResponse: () -> Unit = {}
    val bodies = ArrayList<JsonObject>()
    java.net.URL.setURLStreamHandlerFactory { protocol ->
        if (protocol != "https") null else object : java.net.URLStreamHandler() {
            override fun openConnection(url: java.net.URL): java.net.URLConnection {
                check(url.host == "gateway.kugou.com" && url.path == "/v6/priv_url") { "Unexpected external request: ${url.host}" }
                return object : java.net.HttpURLConnection(url) {
                    private val written = java.io.ByteArrayOutputStream()
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getOutputStream(): java.io.OutputStream = written
                    override fun getResponseCode(): Int {
                        apiCalls++
                        ok(requestMethod == "POST" && !instanceFollowRedirects)
                        ok(getRequestProperty("x-router") == "tracker.kugou.com")
                        bodies.add(JsonParser.parseString(written.toString(Charsets.UTF_8)).asJsonObject)
                        onResponse()
                        return 200
                    }
                    override fun getInputStream(): java.io.InputStream = apiResponse.toString().byteInputStream()
                }
            }
        }
    }
    KugouClient.userId = session.userId; KugouClient.token = session.token
    KugouClient.deviceMid = session.mid; KugouClient.deviceDfid = session.dfid
    for (mode in listOf(false, true)) {
        KugouClient.conceptSession = mode
        ok(KugouClient.onlineKey(hash, noCancel) == key)
        ok(bodies.last()["resource"].asJsonObject["hash"].asString == hash)
    }
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/synthetic.kgg") { ex ->
        ex.sendResponseHeaders(200, encrypted.size.toLong()); ex.responseBody.use { it.write(encrypted) }; ex.close()
    }
    server.start()
    fun manager(name: String, vararg args: Any?): Any? {
        val method = MusicManager::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }
        return try { method.invoke(MusicManager, *args) } catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
    }
    try {
        val song = Song(81, "Synthetic", "Fixture", "", "", 0, MusicProvider.KUGOU, "d".repeat(32))
        val url = "http://127.0.0.1:${server.address.port}/synthetic.kgg"
        MusicManager.kugouLocal.keyFile.delete(); MusicManager.kugouLocal.mmkvFile.delete()
        val target = File(root, "managed.audio")
        val before = apiCalls
        manager("download", url, target, 0L, song)
        ok(apiCalls == before + 1 && sha(target) == vector["plain_sha256"].asString)
        ok(manager("isUsable", target) == true)
        ok(!MusicManager.kugouLocal.keyFile.exists())
        apiResponse = response().apply { addProperty("error_code", 35005) }
        val rejected = File(root, "rejected.audio")
        failure(KugouPlayback.FailureKind.LOGIN) { manager("download", url, rejected, 0L, song) }
        ok(!rejected.exists() && !File(rejected.path + ".verified").exists())
        ok(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
        apiResponse = response(); onResponse = { KugouClient.clearLogin() }
        val switched = File(root, "switched.audio")
        cancelled { manager("download", url, switched, 0L, song) }
        ok(!switched.exists() && !File(switched.path + ".verified").exists())
    } finally { server.stop(0); KugouClient.clearLogin() }
    println("PASS: 081 $checks assertions: exact upstream standard/lite wire vectors, strict authorization/hash/preview/size/key guards, cancellation/account-change, local-first automatic online lookup and synthetic byte-exact decrypt; no live account/network/native audio test")
}
