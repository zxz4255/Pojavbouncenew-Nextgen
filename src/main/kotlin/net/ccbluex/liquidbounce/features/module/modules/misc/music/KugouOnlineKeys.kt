/* SPDX-License-Identifier: GPL-3.0-or-later
 * Wire reference: MakcRe/KuGouMusicApi (MIT), pinned in references/kgg081.
 * Uses account-authorized /v6/priv_url en_ekey, NOT a guessed get_ekey protocol.
 */
package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Base64
import java.util.function.BooleanSupplier

/** No key cache, persistence, anonymous requests, VIP claims or third-party key service. */
internal class KugouOnlineKeys(
    private val transport: KugouPlayback.Transport = KugouPlayback.HttpTransport,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun fetch(hash: String, session: KugouPlayback.Session, concept: Boolean, cancel: BooleanSupplier,
              isCurrent: () -> Boolean): String {
        require(hash.matches(Regex("[a-fA-F0-9]{32}"))) { "在线取钥需要有效的资源 hash" }
        fun guard() {
            if (Thread.currentThread().isInterrupted || cancel.asBoolean) throw InterruptedIOException("在线取钥已取消")
            if (!isCurrent()) throw KugouPlayback.Failure(KugouPlayback.FailureKind.LOGIN, "在线取钥", detail = "账号或设备已切换，请重试")
        }
        guard()
        val request = request(hash.lowercase(), session, concept, clock())
        val response = try { transport.exchange(request) }
        catch (e: InterruptedIOException) { throw e }
        catch (e: KugouPlayback.Failure) { throw e }
        catch (_: IOException) { throw KugouPlayback.Failure(KugouPlayback.FailureKind.NETWORK, "在线取钥", detail = "酷狗网络请求失败，请重试") }
        catch (_: Exception) { throw KugouPlayback.Failure(KugouPlayback.FailureKind.PROTOCOL, "在线取钥", detail = "酷狗响应格式无效") }
        guard() // A late response may never supply a key to another account/job.
        return parse(response, hash.lowercase()).also { guard() }
    }

    companion object {
        private const val STANDARD_SALT = "OIlwieks28dk2k092lksi2UIkp"
        private val qualities = listOf("128", "320", "flac", "high", "multitrack", "viper_atmos", "viper_tape", "viper_clear", "super")
        internal fun request(hash: String, session: KugouPlayback.Session, concept: Boolean, now: Long): KugouPlayback.Request {
            val appid = if (concept) "3116" else "1005"
            val time = (now / 1000).toString()
            val body = JsonObject().apply {
                addProperty("area_code", "1"); addProperty("behavior", "play")
                add("qualities", JsonArray().apply { qualities.forEach { add(it) } })
                add("resource", JsonObject().apply {
                    // Header-only imports have no album_audio_id. Upstream omits undefined IDs.
                    addProperty("collect_list_id", "3"); addProperty("collect_time", now)
                    addProperty("hash", hash); addProperty("id", 0); addProperty("page_id", 1); addProperty("type", "audio")
                })
                addProperty("token", session.token)
                add("tracker_param", JsonObject().apply {
                    addProperty("all_m", 1); addProperty("auth", ""); addProperty("is_free_part", 0)
                    // song_url_new uses this salt and pid for BOTH platform modes.
                    addProperty("key", KugouPlayback.md5(hash + "185672dd44712f60bb1736df5a377e82" + appid + session.mid + session.userId))
                    addProperty("module_id", 0); addProperty("need_climax", 1); addProperty("need_xcdn", 1)
                    addProperty("open_time", ""); addProperty("pid", "411"); addProperty("pidversion", "3001")
                    addProperty("priv_vip_type", "6"); addProperty("viptoken", "")
                })
                addProperty("userid", session.userId.toString()); addProperty("vip", 0)
            }.toString()
            val params = linkedMapOf("dfid" to session.dfid, "mid" to session.mid, "uuid" to "-", "appid" to appid,
                "clientver" to (if (concept) "11440" else "20489"), "clienttime" to time,
                "token" to session.token, "userid" to session.userId.toString())
            params["signature"] = KugouPlayback.signature(params, body, if (concept) KugouPlayback.LITE_SALT else STANDARD_SALT)
            val headers = linkedMapOf("dfid" to session.dfid, "mid" to session.mid, "clienttime" to time,
                "kg-rc" to "1", "kg-thash" to "5d816a0", "kg-rec" to "1", "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F",
                "User-Agent" to (if (concept) KugouPlayback.LITE_UA else "Android-AndroidPhone-11430-0-0-NetMusic-wifi"),
                "x-router" to "tracker.kugou.com",
                "Cookie" to "token=${session.token}; userid=${session.userId}; mid=${session.mid}; dfid=${session.dfid}")
            return KugouPlayback.Request("https://gateway.kugou.com", "/v6/priv_url", params, headers, body)
        }

        private fun str(o: JsonObject, key: String) = runCatching { o.get(key)?.asString.orEmpty() }.getOrDefault("")
        private fun obj(o: JsonObject, key: String) = o.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
        internal fun parse(root: JsonObject, hash: String): String {
            fun fail(kind: KugouPlayback.FailureKind, detail: String, code: Long = 0): Nothing =
                throw KugouPlayback.Failure(kind, "在线取钥", code, detail)
            val code = listOf("error_code", "errcode").mapNotNull { str(root, it).toLongOrNull() }.firstOrNull { it != 0L } ?: 0L
            if (code == 35005L) fail(KugouPlayback.FailureKind.LOGIN, "酷狗登录态失效，请重新扫码", code)
            // Never include raw upstream text: it may echo tokens, keys or signed URLs.
            if (code != 0L || str(root, "status") != "1") fail(KugouPlayback.FailureKind.API, "平台未批准取钥（code=$code），不会回退匿名请求", code)
            val rows = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: fail(KugouPlayback.FailureKind.PROTOCOL, "平台未返回可核验的密钥条目")
            if (rows.size() > 256) fail(KugouPlayback.FailureKind.PROTOCOL, "平台密钥条目超过安全上限")
            val matching = ArrayList<JsonObject>()
            for (element in rows) {
                if (!element.isJsonObject) continue
                val row = element.asJsonObject
                if (str(row, "hash").equals(hash, true)) matching.add(row)
                val related = row.get("relate_goods")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                if (related.size() > 256) fail(KugouPlayback.FailureKind.PROTOCOL, "关联条目超过安全上限")
                related.forEach { item -> if (item.isJsonObject && str(item.asJsonObject, "hash").equals(hash, true)) matching.add(item.asJsonObject) }
            }
            // Do not use en_hash as an alias or take another quality's key.
            if (matching.isEmpty()) fail(KugouPlayback.FailureKind.PROTOCOL, "未返回与文件头 hash 匹配的 eKey")
            val keys = matching.map { row ->
                val info = obj(row, "info") ?: fail(KugouPlayback.FailureKind.PROTOCOL, "密钥条目缺少资源信息")
                if (str(row, "status") != "1" || str(row, "_errno") != "0" || str(row, "fail_process") != "0" ||
                    str(info, "tracker_status") != "1" || str(info, "tracker_type") != "full")
                    fail(KugouPlayback.FailureKind.API, "平台未授权该资源的完整播放，不能使用其密钥")
                if (listOf(root, row, info).any { part -> listOf("is_free_part", "IsFreePart", "is_trial", "preview").any { str(part, it) in listOf("1", "true") } })
                    fail(KugouPlayback.FailureKind.API, "平台仅提供试听，不能作为完整曲目解密")
                val key = str(info, "en_ekey").trim()
                if (key.length !in 24..16384 || !key.matches(Regex("[A-Za-z0-9+/]+={0,2}")) ||
                    runCatching { Base64.getDecoder().decode(key).size >= 24 }.getOrDefault(false).not())
                    fail(KugouPlayback.FailureKind.PROTOCOL, "平台未返回有效 eKey；可能需额外账号授权")
                key
            }.distinct()
            if (keys.size != 1) fail(KugouPlayback.FailureKind.PROTOCOL, "同一 hash 返回了冲突密钥，拒绝解密")
            return keys.single()
        }
    }
}
