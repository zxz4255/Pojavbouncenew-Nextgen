package net.ccbluex.liquidbounce.features.module.modules.misc.music

import kotlin.math.ln

import net.ccbluex.liquidbounce.utils.client.logger
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * 简易播放引擎：优先 JavaSound Clip。
 * 支持 wav；mp3 在 classpath 存在 MP3SPI/JLayer 时也可播。
 * 安卓/Pojav 若无 JavaSound 实现会优雅失败并给出状态。
 */
class MusicPlayerEngine {

    @Volatile var current: MusicTrack? = null
        private set
    @Volatile var lyrics: LyricSheet = LyricSheet(emptyList())
        private set
    @Volatile var status: String = "空闲"
        private set
    @Volatile var error: String? = null
        private set

    private var clip: Clip? = null
    private val playing = AtomicBoolean(false)
    private var worker: Thread? = null

    val isPlaying: Boolean get() = playing.get() && (clip?.isRunning == true)

    fun positionMs(): Long {
        val c = clip ?: return 0L
        val fmt = c.format ?: return 0L
        val frames = c.framePosition.toLong().coerceAtLeast(0)
        return (frames * 1000L / fmt.frameRate.toLong().coerceAtLeast(1))
    }

    fun durationMs(): Long {
        val c = clip ?: return current?.durationMs ?: 0L
        val fmt = c.format ?: return current?.durationMs ?: 0L
        return (c.frameLength.toLong() * 1000L / fmt.frameRate.toLong().coerceAtLeast(1))
    }

    fun playRemote(track: MusicTrack, cacheDir: Path) {
        stop()
        current = track
        status = "解析中…"
        error = null
        worker = Thread({
            try {
                val url = NeteaseMusicApi.songUrl(track.id)
                    ?: throw IllegalStateException("无法获取播放地址（网关无登录接口不可用）")
                status = "下载中…"
                Files.createDirectories(cacheDir)
                val file = cacheDir.resolve("netease_${track.id}.audio")
                download(url, file)
                lyrics = NeteaseMusicApi.lyric(track.id)
                status = "解码中…"
                openAndStart(file)
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                status = "失败"
                logger.warn("Music play failed", t)
                playing.set(false)
            }
        }, "lb-music-play").apply { isDaemon = true; start() }
    }

    fun playLocal(file: Path) {
        stop()
        current = MusicTrack(
            id = file.fileName.toString(),
            title = file.name.substringBeforeLast('.'),
            artist = "本地",
            source = MusicTrack.Source.LOCAL,
        )
        error = null
        worker = Thread({
            try {
                val lrc = file.resolveSibling(file.name.substringBeforeLast('.') + ".lrc")
                lyrics = if (lrc.exists()) LyricParser.parse(Files.readString(lrc)) else LyricSheet(emptyList())
                status = "解码中…"
                openAndStart(file)
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                status = "失败"
                playing.set(false)
            }
        }, "lb-music-local").apply { isDaemon = true; start() }
    }

    fun togglePause() {
        val c = clip ?: return
        if (c.isRunning) {
            c.stop()
            status = "暂停"
        } else {
            c.start()
            status = "播放中"
            playing.set(true)
        }
    }

    fun stop() {
        playing.set(false)
        worker?.interrupt()
        worker = null
        runCatching {
            clip?.stop()
            clip?.close()
        }
        clip = null
        status = "空闲"
    }

    fun setVolume(percent: Float) {
        val c = clip ?: return
        if (!c.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val ctrl = c.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        val p = percent.coerceIn(0f, 1f)
        val min = ctrl.minimum
        val max = ctrl.maximum
        // 近似线性响度映射到 dB
        val gain = if (p <= 0f) min else (min + (max - min) * (ln(1.0 + p * 9.0) / ln(10.0)).toFloat())
        ctrl.value = gain.coerceIn(min, max)
    }

    private fun download(url: String, target: Path) {
        URI.create(url).toURL().openStream().use { input ->
            Files.newOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    private fun openAndStart(file: Path) {
        val stream = openAudioStream(Files.newInputStream(file))
        val clip = AudioSystem.getClip()
        clip.open(stream)
        this.clip = clip
        clip.start()
        playing.set(true)
        status = "播放中"
    }

    private fun openAudioStream(input: InputStream): AudioInputStream {
        val buffered = BufferedInputStream(input)
        val base = AudioSystem.getAudioInputStream(buffered)
        val baseFormat = base.format
        val decoded = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            baseFormat.sampleRate,
            16,
            baseFormat.channels,
            baseFormat.channels * 2,
            baseFormat.sampleRate,
            false,
        )
        return if (AudioSystem.isConversionSupported(decoded, baseFormat)) {
            AudioSystem.getAudioInputStream(decoded, base)
        } else {
            base
        }
    }

    companion object {
        fun listLocal(dir: Path): List<Path> {
            if (!Files.isDirectory(dir)) return emptyList()
            return Files.list(dir).use { stream ->
                stream.filter { it.isRegularFile() }
                    .filter {
                        val ext = it.extension.lowercase()
                        ext in setOf("mp3", "wav", "ogg", "flac")
                    }
                    .sorted()
                    .toList()
            }
        }
    }
}
