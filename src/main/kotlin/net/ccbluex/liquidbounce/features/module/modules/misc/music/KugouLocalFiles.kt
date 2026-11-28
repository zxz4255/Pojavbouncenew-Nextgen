/* SPDX-License-Identifier: GPL-3.0-or-later
 * Local key-assisted KGG integration; MMKV layout reference: Aaron-gx/KGG-Decryptor (MIT).
 * See references/kgg079 and kgg081. No account database probing or root calls.
 */
package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.utils.audio.parser.KggDecryptor
import net.ccbluex.liquidbounce.utils.audio.parser.MusicAudioParserFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.function.BooleanSupplier

/** All input/key paths are explicit children of the player's music directory. */
internal class KugouLocalFiles(
    private val root: File,
    private val onlineLookup: ((String, BooleanSupplier) -> String?)? = null
) {
    val inputDir get() = File(root, "kgg-import").apply { mkdirs() }
    val outputDir get() = File(root, "local-audio").apply { mkdirs() }
    val keyFile get() = File(root, "kgg-keys.json")
    val mmkvFile get() = File(root, "mggkey_multi_process")
    private fun ordinary(file: File) = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun readBounded(file: File, limit: Int): ByteArray {
        if (!ordinary(file) || file.length() > limit) throw IOException("密钥文件无效或超过大小上限")
        val data=file.inputStream().use { it.readNBytes(limit+1) }
        if (data.size>limit) throw IOException("密钥文件超过大小上限")
        return data
    }

    /** Reload on each job: a newly supplied key works without restart; never print or persist values. */
    internal fun keys(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (mmkvFile.exists()) {
            result.putAll(parseMmkv(readBounded(mmkvFile,16*1024*1024)))
        }
        if (keyFile.exists()) {
            val data=readBounded(keyFile,2*1024*1024)
            try {
                val obj = JsonParser.parseString(String(data,Charsets.UTF_8)).asJsonObject
                if (obj.size() > 4096) throw IOException("密钥条目超过安全上限")
                for ((hash, value) in obj.entrySet()) {
                    if (!hash.matches(Regex("[a-fA-F0-9]{32}")) || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw IOException("密钥应为 32 位 hash 到 eKey 字符串的映射")
                    val key = value.asString.trim()
                    if (key.length !in 24..16384) throw IOException("eKey 长度无效")
                    result[hash.lowercase()] = key
                }
            } catch (e: IOException) { throw e }
            catch (_: Exception) { throw IOException("kgg-keys.json 格式无效（不会显示密钥内容）") }
        }
        return result
    }

    fun decrypt(input: File, output: File, cancel: BooleanSupplier) =
        KggDecryptor.decrypt(input, output, KggDecryptor.KeyLookup { hash ->
            keys()[hash.lowercase()] ?: onlineLookup?.invoke(hash.lowercase(), cancel)
        }, cancel)

    fun file(song: Song): File {
        if (song.provider != MusicProvider.LOCAL || !song.providerHash.matches(Regex("[a-f0-9]{64}"))) throw IOException("本地歌曲标识无效")
        val file = File(outputDir, song.providerHash + ".audio")
        if (!ordinary(file)) throw IOException("本地解密音频不存在，请重新导入")
        return file
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val b = ByteArray(16384); while (true) { val n=stream.read(b); if (n<0) break; hash.update(b,0,n) } }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun songs(): List<Song> = outputDir.listFiles()?.asSequence()?.filter {
        ordinary(it) && it.name.matches(Regex("[a-f0-9]{64}\\.audio"))
    }?.sortedBy { it.name }?.take(512)?.map { file ->
        val hash = file.nameWithoutExtension
        val titleFile = File(file.path + ".title")
        val title = if (ordinary(titleFile) && titleFile.length() <= 4096) titleFile.readText().take(200) else "本地歌曲 ${hash.take(8)}"
        Song(hash.take(15).toLong(16).coerceAtLeast(1L), title, "本地解密", "", "", 0L, MusicProvider.LOCAL, hash)
    }?.toList() ?: emptyList()

    data class Report(val succeeded: Int, val failed: Int, val detail: String)
    /** At most 32 files / 256 MiB per click. Source files and existing outputs are never deleted. */
    fun importFiles(cancel: BooleanSupplier): Report {
        val files = inputDir.listFiles()?.filter { ordinary(it) && MusicAudioParserFactory.isKugouEncrypted(it) }?.sortedBy { it.name } ?: emptyList()
        var succeeded=0; var failed=0; var bytes=0L; var detail=""
        for (source in files.take(32)) {
            if (cancel.asBoolean || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("导入已取消")
            if (source.length() > MusicAudioParserFactory.MAX_FILE_BYTES || source.length() > 256L*1024*1024-bytes) { failed++; detail="文件或本批次总大小超过上限"; continue }
            bytes+=source.length()
            val temp = File(outputDir, ".import-${java.util.UUID.randomUUID()}.audio")
            try {
                decrypt(source,temp,cancel)
                val hash=digest(temp); val output=File(outputDir,"$hash.audio")
                if (cancel.asBoolean || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("导入已取消")
                if (!output.exists()) Files.move(temp.toPath(),output.toPath())
                else if (!ordinary(output) || digest(output)!=hash) throw IOException("现有导入文件校验不一致，拒绝覆盖")
                val title=source.name.replace(Regex("(?i)(\\.(mp3|flac|ogg|m4a|aac|wav))?$"), "").replace(Regex("(?i)\\.(kgg|kgm|kgma|kge|vpr)$"), "").take(200)
                val titleFile = File(output.path+".title")
                if (titleFile.exists() && !ordinary(titleFile)) throw IOException("标题文件不是普通文件")
                titleFile.writeText(title)
                succeeded++
            } catch (e: java.io.InterruptedIOException) { throw e }
            catch (e: Exception) { failed++; detail=e.message?.take(220) ?: "解密失败" }
            finally { temp.delete() }
        }
        return Report(succeeded,failed, if (files.size>32) "本次仅处理前 32 个文件；$detail" else detail)
    }

    companion object {
        /** Bounded best-effort reader of the user's explicitly supplied unencrypted MMKV snapshot. */
        internal fun parseMmkv(data: ByteArray): Map<String,String> {
            fun varint(at: Int): Pair<Int,Int>? {
                var value=0L; var pos=at; var shift=0
                while (pos<data.size && shift<=28) { val b=data[pos++].toInt() and 255; value=value or ((b and 127).toLong() shl shift)
                    if (b and 128 == 0) return if (value<=16386) value.toInt() to pos else null
                    shift+=7
                }; return null
            }
            val result=LinkedHashMap<String,String>(); var i=0
            fun hexByte(b: Byte): Boolean = b.toInt() in 48..57 || b.toInt() in 65..70 || b.toInt() in 97..102
            val keyPattern=Regex("[A-Za-z0-9+/=]{24,16384}")
            while (i<=data.size-32 && result.size<4096) {
                if ((0 until 32).all { hexByte(data[i+it]) }) {
                    val hash=String(data,i,32,Charsets.US_ASCII)
                    val outer=varint(i+32); val value=outer?.let { varint(it.second) }
                    if (outer!=null && value!=null && value.first in 24..16384 && outer.first>=value.first && value.second<=data.size-value.first) {
                        val key=String(data,value.second,value.first,Charsets.US_ASCII).trimEnd('\u0000').trim()
                        if (key.matches(keyPattern)) { result[hash.lowercase()]=key; i=value.second+value.first; continue }
                    }
                };i++
            };return result
        }
    }
}
