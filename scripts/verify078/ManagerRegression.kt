package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.utils.audio.parser.MusicAudioParserFactory
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.lang.reflect.InvocationTargetException
import java.io.IOException

private fun call(name:String,vararg args:Any?):Any? {
    val method=MusicManager::class.java.declaredMethods.single { it.name==name && it.parameterCount==args.size }
    method.isAccessible=true
    return try { method.invoke(MusicManager,*args) } catch(e:InvocationTargetException){throw e.targetException}
}
fun main() {
    val root=File(System.getProperty("music.test.root"));root.mkdirs()
    val base=Song(17,"Fixture","Artist","","",180000,MusicProvider.KUGOU,"1".repeat(32),"4","5")
    var checks=0
    fun session(uid:Long,concept:Boolean) { KugouClient.acceptLogin(KugouClient.QrResult(4,"fake-offline-token",uid,"Fixture","a".repeat(32)),concept) }
    session(42,true)
    val first=KugouClient.cacheName(base,"exhigh")
    check(first.endsWith(".audio") && !first.contains("fake-offline-token"));checks++
    check(first!=KugouClient.cacheName(base,"lossless"));checks++
    session(43,true);check(first!=KugouClient.cacheName(base,"exhigh"));checks++
    session(42,false);check(first!=KugouClient.cacheName(base,"exhigh"));checks++
    session(42,true)
    val rehash=Song(17,"Fixture","Artist","","",180000,MusicProvider.KUGOU,"2".repeat(32),"4","5")
    check(first!=KugouClient.cacheName(rehash,"exhigh"));checks++
    val patternField=MusicManager::class.java.getDeclaredField("cacheName").apply{isAccessible=true}
    val partField=MusicManager::class.java.getDeclaredField("partialCacheName").apply{isAccessible=true}
    val pattern=patternField.get(MusicManager) as Regex;val partPattern=partField.get(MusicManager) as Regex
    check(pattern.matches(first) && partPattern.matches("$first.123.part") && partPattern.matches("$first.123.proof.part"));checks++
    for(legacy in listOf("17.mp3","kugou-17.mp3","kugou-concept-42-17.mp3","bilibili-17.m4s"))check(pattern.matches(legacy))
    checks++
    check(call("isNetworkFailure",KugouPlayback.Failure(KugouPlayback.FailureKind.LOGIN,"fixture",detail="expired"))==false)
    check(call("isNetworkFailure",KugouPlayback.Failure(KugouPlayback.FailureKind.FORMAT,"fixture",detail="encrypted"))==false)
    check(call("isNetworkFailure",KugouPlayback.Failure(KugouPlayback.FailureKind.NETWORK,"fixture",detail="offline"))==true)
    check(call("isNetworkFailure",IOException("network"))==true);checks++
    KugouClient.deviceDfid="fixture-dfid"
    val decimalMid="123456789012345678901234567890123456789"
    KugouClient.deviceMid=decimalMid
    MusicManager.saveSettings()
    KugouClient.deviceDfid="-";KugouClient.deviceMid="a".repeat(32)
    call("loadSettings")
    check(KugouClient.deviceDfid=="fixture-dfid" && KugouClient.deviceMid==decimalMid && KugouClient.userId==42L && KugouClient.conceptSession);checks++

    val prefix=java.util.HexFormat.of().parseHex("7cd532eb86027f4ba8afa68e0fff9914")
    for((label,magic) in listOf("KGG/KGM" to prefix,"VPR" to java.util.HexFormat.of().parseHex("0528bc96e9e45a4391aabdd07af53631"))) {
        val file=File(root,"${checks}-misnamed.flac");file.writeBytes(magic+ByteArray(66000))
        check(MusicAudioParserFactory.isKugouEncrypted(file))
        check(MusicAudioParserFactory.detect(file)==MusicAudioParserFactory.Codec.UNKNOWN)
        try { MusicAudioParserFactory.open(file,0,null);error("Encrypted audio accepted") } catch(e:IOException){check(e.message!!.contains("加密容器"))}
        checks++
    }
    val clear=File(root,"clear.kgg");clear.writeBytes("fLaC".toByteArray()+ByteArray(66000))
    check(!MusicAudioParserFactory.isKugouEncrypted(clear) && MusicAudioParserFactory.detect(clear)==MusicAudioParserFactory.Codec.FLAC);checks++
    val tiny=File(root,"tiny.kgg");tiny.writeBytes(prefix.copyOf(8));check(!MusicAudioParserFactory.isKugouEncrypted(tiny));checks++

    val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    var payload=prefix+ByteArray(66000)
    server.createContext("/audio") { exchange -> val body=payload;exchange.sendResponseHeaders(200,body.size.toLong());exchange.responseBody.use{it.write(body)};exchange.close() }
    server.createContext("/json") { exchange ->
        check(exchange.requestMethod=="POST" && exchange.requestHeaders.getFirst("x-router")=="fixture-router")
        check(exchange.requestBody.readBytes().toString(Charsets.UTF_8)=="{\"body\":1}")
        val body="{\"status\":1}".toByteArray();exchange.sendResponseHeaders(200,body.size.toLong());exchange.responseBody.use{it.write(body)};exchange.close()
    }
    server.start()
    try {
        val url="http://127.0.0.1:${server.address.port}/audio"
        val target=File(root,"download.audio")
        try { call("download",url,target,0L,base);error("Encrypted download accepted") } catch(e:KugouPlayback.Failure){check(e.kind==KugouPlayback.FailureKind.FORMAT)}
        check(!target.exists() && !File(target.path+".verified").exists() && !File(target.path+".0.part").exists());checks++
        payload="fLaC".toByteArray()+ByteArray(66000)
        call("download",url,target,0L,base)
        check(target.readBytes().contentEquals(payload) && call("isUsable",target)==true);checks++
        target.appendBytes(byteArrayOf(1));check(call("isUsable",target)==false);checks++
        payload="<html>not audio</html>".toByteArray()+ByteArray(66000)
        try { call("download",url,File(root,"bad.audio"),0L,base);error("Error body accepted") } catch(_:IOException){}
        check(!File(root,"bad.audio").exists());checks++
        val request=KugouPlayback.Request("http://127.0.0.1:${server.address.port}","/json",mapOf("test" to "a b/+"),mapOf("x-router" to "fixture-router"),"{\"body\":1}")
        check(KugouPlayback.HttpTransport.exchange(request)["status"].asInt==1);checks++
    } finally { server.stop(0);MusicManager.shutdown() }
    println("PASS: $checks manager/parser scenarios: account/mode/quality/hash cache isolation, proof integrity, credential persistence, error classification, encrypted-magic vs real content, native preflight rejection, real loopback HTTP POST and download cleanup")
}
