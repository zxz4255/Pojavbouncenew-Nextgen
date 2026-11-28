package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.security.MessageDigest

private const val HASH128 = "11111111111111111111111111111111"
private const val HASH320 = "22222222222222222222222222222222"
private const val HASHFLAC = "33333333333333333333333333333333"
private val session = KugouPlayback.Session(42, "test-token-not-a-real-account", "12345678901234567890123456789012")
private val song = Song(17,"Fixture","Artist","Album","",180000,MusicProvider.KUGOU,HASH128,"4","999")
private fun json(text:String)=JsonParser.parseString(text).asJsonObject
private fun md5(text:String)=MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}

private class Scenario : KugouPlayback.Transport {
    val seen=mutableListOf<KugouPlayback.Request>()
    var verify:JsonObject=json("""{"status":1,"data":{"auth":"user-auth"}}""")
    var handler:(KugouPlayback.Request)->JsonObject = { r ->
        json("""{"status":1,"hash":"${r.params["hash"]}","timelength":180000,"url":["https://cdn.example.test/file.ogg"]}""")
    }
    override fun exchange(request:KugouPlayback.Request):JsonObject {
        seen+=request
        val p=request.params
        check(p["appid"]=="3116" && p["token"]==session.token && p["userid"]=="42" && p["mid"]==session.mid)
        check(request.headers["Cookie"]!!.contains("token=${session.token}"))
        val unsigned=p.filterKeys { it!="signature" }.toSortedMap().entries.joinToString(""){"${it.key}=${it.value}"}
        check(p["signature"]==md5(KugouPlayback.LITE_SALT+unsigned+request.body.orEmpty()+KugouPlayback.LITE_SALT))
        check(!request.toString().contains(session.token))
        return when(request.path) {
            "/v1/user_verify" -> { check(request.headers["x-router"]=="trackercdngz.kugou.com"); verify.deepCopy() }
            "/v2/get_res_privilege/lite" -> {
                check(request.headers["x-router"]=="media.store.kugou.com" && request.body!=null)
                val body=json(request.body!!)
                check(body.getAsJsonArray("resource")[0].asJsonObject["hash"].asString==HASH128)
                json("""{"status":1,"data":[{"album_audio_id":778,"privilege":10,"pay_type":3,"relate_goods":[{"quality":"128","hash":"$HASH128"},{"quality":"320","hash":"$HASH320"},{"quality":"flac","hash":"$HASHFLAC"}]}]}""")
            }
            "/v1/authorization" -> {
                check(request.headers["x-router"]=="trackercdngz.kugou.com")
                check(p["authorization"]=="user-auth" && p["album_audio_id"]=="778" && p["clientver"]=="11561")
                json("""{"status":1,"data":{"auth":"song-auth","open_time":1700000000}}""")
            }
            "/tracker/v5/url" -> {
                check(request.headers["x-router"]==null)
                check(p["auth"]=="song-auth" && p["need_ogg"]=="0" && p["need_m"]=="0" && p["album_audio_id"]=="778")
                check(p["key"]==md5(p["hash"]+"185672dd44712f60bb1736df5a377e82"+"3116"+session.mid+"42"))
                handler(request)
            }
            else -> error("Unexpected endpoint ${request.path}")
        }
    }
    fun resolve(level:String="exhigh")=KugouPlayback(this){1_700_000_000_000L}.resolve(song,level,session)
}
private fun failure(block:()->Unit):KugouPlayback.Failure = try { block(); error("Expected protocol failure") } catch(e:KugouPlayback.Failure){e}

fun main() {
    var checks=0
    for((level,hash,quality) in listOf(Triple("standard",HASH128,"128"),Triple("higher",HASH320,"320"),Triple("exhigh",HASH320,"320"),Triple("lossless",HASHFLAC,"flac"))) {
        val mock=Scenario();val stream=mock.resolve(level)
        check(stream.durationMs==180000L && stream.quality==quality)
        check(mock.seen.last().params["hash"]==hash)
        check(mock.seen.map{it.path}==listOf("/v1/user_verify","/v2/get_res_privilege/lite","/v1/authorization","/tracker/v5/url"))
        checks++
    }
    val downgrade=Scenario().apply { handler={r -> if(r.params["quality"]=="flac") json("""{"status":0,"error_code":900,"error_msg":"该档权益不足"}""") else json("""{"status":1,"hash":"${r.params["hash"]}","url":"https://cdn.example.test/audio.ogg"}""")} }
    check(downgrade.resolve("lossless").quality=="320")
    check(downgrade.seen.filter{it.path=="/tracker/v5/url"}.map{it.params["hash"]}==listOf(HASHFLAC,HASH320));checks++
    val denied=Scenario().apply { verify=json("""{"status":0,"error_code":35005,"error_msg":"token invalid"}""") }
    check(failure { denied.resolve() }.kind==KugouPlayback.FailureKind.LOGIN && denied.seen.size==1);checks++
    val optionalValid=Scenario().apply{verify=json("""{"status":1,"data":{"valid":0,"auth":"user-auth"}}""")}
    check(failure { optionalValid.resolve() }.kind==KugouPlayback.FailureKind.LOGIN);checks++
    val noAuth=Scenario().apply{verify=json("""{"status":1,"data":{}}""")}
    check(failure { noAuth.resolve() }.kind==KugouPlayback.FailureKind.PROTOCOL);checks++
    val badHash=Scenario().apply{handler={json("""{"status":1,"hash":"$HASHFLAC","url":"https://cdn.example.test/a.ogg"}""")}}
    check(failure { badHash.resolve() }.kind==KugouPlayback.FailureKind.PROTOCOL);checks++
    val preview=Scenario().apply{handler={json("""{"status":1,"is_free_part":1,"url":"https://cdn.example.test/a.ogg"}""")}}
    check(failure { preview.resolve() }.message!!.contains("试听"));checks++
    val encrypted=Scenario().apply{handler={json("""{"status":1,"url":"https://cdn.example.test/a.kgg"}""")}}
    check(failure { encrypted.resolve() }.kind==KugouPlayback.FailureKind.FORMAT);checks++
    val network=KugouPlayback(KugouPlayback.Transport{throw IOException("secret URL token=${session.token}")})
    val networkError=failure{network.resolve(song,"standard",session)}
    check(networkError.kind==KugouPlayback.FailureKind.NETWORK && !networkError.message!!.contains(session.token));checks++
    val redacted=Scenario().apply {verify=json("""{"status":0,"error_code":35002,"error_msg":"${session.token}"}""")}
    val error=failure{redacted.resolve()};check(!error.message!!.contains(session.token) && error.message!!.contains("不代表账号没有 VIP"));checks++
    val explicitDenial=Scenario().apply{handler={json("""{"status":0,"error_code":901,"error_msg":"当前账号无该曲权益"}""")}}
    check(failure{explicitDenial.resolve()}.message!!.contains("当前账号无该曲权益"));checks++
    val nested=KugouPlayback.parseStream(json("""{"status":1,"data":{"url":[null,"http://cdn.example.test/a.ogg?sig=a%2Fb"],"timelength":185000}}"""),HASH128,"128")
    check(nested.url=="https://cdn.example.test/a.ogg?sig=a%2Fb" && nested.durationMs==185000L);checks++
    val seconds=KugouPlayback.parseStream(json("""{"url":"https://cdn.example.test/a","timeLength":185}"""),HASH128,"128")
    check(seconds.durationMs==185000L);checks++
    for(url in listOf("file:///etc/passwd","javascript:alert(1)","https://u:pw@cdn.example.test/a","https://cdn.example.test/a.vpr","https://cdn.example.test/a.kgm")) {
        check(failure{KugouPlayback.parseStream(json("""{"url":"$url"}"""),HASH128,"128")}.kind==KugouPlayback.FailureKind.FORMAT);checks++
    }
    for(concept in listOf(true,false)) {
        val create=KugouPlayback.loginRequest("/v2/qrcode",concept,session.mid,"a".repeat(32),now=1700000000123)
        val poll=KugouPlayback.loginRequest("/v2/get_userinfo_qrcode",concept,session.mid,"a".repeat(32),"fixture-qr",1700000002123)
        check(create.params["appid"]=="1001" && poll.params["appid"]==if(concept) "3116" else "1005")
        check(create.params["clientver"]==if(concept) "11440" else "20489")
        check(create.params["clienttime"]=="1700000000123" && create.headers["mid"]==poll.headers["mid"])
        check(create.headers["Cookie"]==poll.headers["Cookie"])
        check(create.params["signature"]==KugouPlayback.signature(create.params,"","NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"));checks++
    }
    check(!session.toString().contains(session.token));checks++
    println("PASS: $checks protocol scenarios: exact identity/signatures/body, 4 quality mappings, per-quality hashes/audio-id, routes, optional valid, explicit denial/login/network/format/preview errors, lower-quality retry, TLS upgrade, durations and QR identity consistency")
}
