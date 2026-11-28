package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.function.BooleanSupplier

private fun invokeManager(name: String, vararg args: Any?): Any? {
    val method = MusicManager::class.java.declaredMethods.single { it.name==name && it.parameterCount==args.size }.apply { isAccessible=true }
    return try { method.invoke(MusicManager,*args) } catch(e: InvocationTargetException) {throw e.targetException}
}
private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }
fun main() {
    val root=File(System.getProperty("music.test.root")).apply { mkdirs() }
    val project=File(System.getProperty("music.test.project"))
    val vectors=File(project,"scripts/verify079/vectors")
    val rows=JsonParser.parseString(File(vectors,"synthetic.json").readText()).asJsonArray.map {it.asJsonObject}
    val row=rows.first();val hash=row["hash"].asString;val ekey=row["ekey"].asString
    val encrypted=File(vectors,row["name"].asString).readBytes()
    val local=KugouLocalFiles(File(root,"standalone"))
    var checks=0
    fun ok(condition: Boolean) {check(condition) {"Integration assertion $checks"};checks++}
    fun fail(action: ()->Unit) {try {action();error("Expected failure")} catch(_:IOException){checks++}}
    local.keyFile.parentFile.mkdirs();local.keyFile.writeText("{\"$hash\":\"$ekey\"}")
    val input=File(local.inputDir,"song.kgg.flac").apply { writeBytes(encrypted) }
    val noCancel=BooleanSupplier {false}
    val report=local.importFiles(noCancel)
    ok(report.succeeded==1 && report.failed==0)
    val songs=local.songs();ok(songs.size==1 && songs[0].provider==MusicProvider.LOCAL && songs[0].name=="song")
    val file=local.file(songs[0]);ok(sha(file.readBytes())==row["plain_sha256"].asString)
    ok(input.readBytes().contentEquals(encrypted))
    val repeat=local.importFiles(noCancel);ok(repeat.succeeded==1 && local.songs().size==1)
    val library=LocalMusicLibrary(File(root,"library.json"));library.toggleFavorite(songs[0]);library.rememberSong(songs[0])
    val reload=LocalMusicLibrary(File(root,"library.json")).apply {load()}
    ok(reload.favoriteSongs().single().provider==MusicProvider.LOCAL && reload.favoriteSongs().single().providerHash==songs[0].providerHash)
    ok(MusicCrossSource.candidates(songs[0]).isEmpty())
    try {MusicManager.setProvider(MusicProvider.LOCAL);error("LOCAL became online provider")} catch(_:IllegalArgumentException){checks++}
    fail {local.file(Song(1,"bad","","","",0L,MusicProvider.LOCAL,"../elsewhere"))}
    local.keyFile.writeText("{invalid-secret-key-text}")
    try {local.keys();error("Bad JSON accepted")} catch(e:IOException) {ok(!e.message.orEmpty().contains("secret-key-text"))}
    local.keyFile.writeText("{\"$hash\":\"A\"}");fail {local.keys()}
    local.keyFile.delete()
    val missing=local.importFiles(noCancel);ok(missing.failed==1 && missing.detail.contains("eKey") && input.exists() && file.exists())
    fun varint(value: Int): ByteArray {var v=value;val b=ArrayList<Byte>();do {var c=v and 127;v=v ushr 7;if(v!=0)c=c or 128;b.add(c.toByte())}while(v!=0);return b.toByteArray()}
    val mmkv=ByteArray(13)+hash.uppercase().toByteArray()+varint(ekey.length+2)+varint(ekey.length)+ekey.toByteArray()+ByteArray(8)
    ok(KugouLocalFiles.parseMmkv(mmkv)[hash]==ekey)
    ok(KugouLocalFiles.parseMmkv(mmkv.copyOf(mmkv.size-30)).isEmpty())
    ok(KugouLocalFiles.parseMmkv(hash.toByteArray()+byteArrayOf(-1,-1,-1,-1,-1,-1)).isEmpty())
    local.mmkvFile.writeBytes(mmkv);ok(local.keys()[hash]==ekey)
    local.keyFile.writeText("{\"$hash\":\"${rows[1]["ekey"].asString}\"}");ok(local.keys()[hash]==rows[1]["ekey"].asString)
    local.keyFile.delete();ok(local.importFiles(noCancel).succeeded==1)
    val link=File(local.inputDir,"linked.kgg");Files.createSymbolicLink(link.toPath(),input.toPath())
    ok(local.importFiles(noCancel).succeeded==1) // no double-processing of symlinked inputs
    fail {local.importFiles(BooleanSupplier {true})}
    ok(!File(local.outputDir,".missing").exists())

    // Real production download: encrypted response -> eKey lookup -> plaintext cache -> plaintext proof.
    val managed=MusicManager.kugouLocal
    managed.keyFile.parentFile.mkdirs();managed.keyFile.writeText("{\"$hash\":\"$ekey\"}")
    val song=Song(77,"Synthetic","Fixture","","",1,MusicProvider.KUGOU,"1".repeat(32))
    val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    server.createContext("/audio.kgg") {ex -> ex.sendResponseHeaders(200,encrypted.size.toLong());ex.responseBody.use {it.write(encrypted)};ex.close()}
    server.start()
    try {
        val target=File(root,"online.audio");val url="http://127.0.0.1:${server.address.port}/audio.kgg"
        invokeManager("download",url,target,0L,song)
        ok(sha(target.readBytes())==row["plain_sha256"].asString)
        ok(invokeManager("isUsable",target)==true)
        val proof=File(target.path+".verified").readText().split(" ")
        ok(proof[0].toLong()==target.length() && proof[1]==sha(target.readBytes()))
        ok(proof[1]!=sha(encrypted))
        ok(!File(target.path+".0.part").exists() && !File(target.path+".0.clear.part").exists())
        managed.keyFile.delete()
        val denied=File(root,"missing-key.audio")
        try {invokeManager("download",url,denied,0L,song);error("Missing key accepted")}
        catch(e:KugouPlayback.Failure){ok(e.kind==KugouPlayback.FailureKind.FORMAT && e.message!!.contains("eKey"))}
        ok(!denied.exists() && !File(denied.path+".0.clear.part").exists())
        managed.keyFile.writeText("{\"$hash\":\"${rows.last()["ekey"].asString}\"}")
        val wrong=File(root,"wrong-key.audio")
        try {invokeManager("download",url,wrong,0L,song);error("Wrong matching key accepted")}
        catch(e:KugouPlayback.Failure){ok(e.kind==KugouPlayback.FailureKind.FORMAT)}
        ok(!wrong.exists() && !File(wrong.path+".verified").exists())
        val serial=MusicManager::class.java.getDeclaredField("playSerial").apply {isAccessible=true};serial.setLong(MusicManager,1L)
        fail {invokeManager("download",url,File(root,"cancel.audio"),0L,song)}
        serial.setLong(MusicManager,0L)
        // Local validation errors must not delete the user's imported file.
        val localFile=managed.outputDir.resolve(file.name);file.copyTo(localFile)
        val localSong=Song(1,"local","","","",0,MusicProvider.LOCAL,file.nameWithoutExtension)
        ok(invokeManager("cachedFile",localSong)==localFile)
        try {invokeManager("ensurePlayableFile",localSong,localFile,0L);error("Native stub returned usable PCM")}
        catch (e: IllegalStateException) {ok(e.javaClass.simpleName=="AudioValidationException")} // native stub deliberately returns no PCM
        ok(localFile.exists())
        val pattern=MusicManager::class.java.getDeclaredField("partialCacheName").apply {isAccessible=true}.get(MusicManager) as Regex
        ok(pattern.matches("kugou-v2-concept-42-exhigh-77-${"1".repeat(32)}.audio.12.clear.part"))
    } finally {server.stop(0);MusicManager.shutdown()}
    println("PASS: $checks KGG integration assertions: local JSON/MMKV keys, double-extension import, source preservation, repeat import, local library identity, no cross-provider fallback, real loopback encrypted download/plaintext cache proof, missing/wrong keys, cancellation and local decode-failure preservation")
}
