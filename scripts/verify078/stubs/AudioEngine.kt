package net.ccbluex.liquidbounce.features.module.modules.misc.music
import java.io.File
class AudioEngine {
    companion object { fun androidRuntime()=false }
    fun setListener(listener:()->Unit) {}
    fun stop() {}
    fun setDurationMs(ms:Long) {}
    fun durationMs()=0L
    fun positionMs()=0L
    fun hasTrack()=false
    fun isPlaying()=false
    fun error():String?=null
    fun play(file:File, ms:Long) {}
    fun pause() {}
    fun resume() {}
    fun seek(ms:Long) {}
    fun volume()=.8f
    fun setVolume(value:Float) {}
    fun outputDescription()="fixture"
    fun minecraftOutput()=false
    fun setMinecraftOutput(value:Boolean) {}
    internal fun viperSettings()=ViperSettings()
    internal fun setViperSettings(value:ViperSettings) {}
    internal fun toneSettings()=ToneSettings()
    internal fun setToneSettings(value:ToneSettings) {}
    fun viperStatus()="fixture"
    fun cachedFileInUse():File?=null
    fun handleWorldReload() {}
    fun handleClientShutdown() {}
    fun dispose() {}
}
