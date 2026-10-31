package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.nio.file.Files

object ModuleMusicPlayer : ClientModule("MusicPlayer", ModuleCategories.MISC) {

    private val engine = MusicPlayerEngine()

    private val showHud by boolean("ShowHud", true)
    private val volume by float("Volume", 0.8f, 0f..1f).onChanged { engine.setVolume(it) }

    override fun onEnabled() {
        Files.createDirectories(localMusicDir())
        Files.createDirectories(cacheDir())
        engine.setVolume(volume)
        openScreen()
        super.onEnabled()
    }

    override fun onDisabled() {
        super.onDisabled()
    }

    private fun openScreen() {
        mc.setScreen(
            MusicPlayerScreen(engine) {
                mc.setScreen(null)
            },
        )
    }

    @Suppress("unused")
    private val hudHandler = handler<OverlayRenderEvent> { event ->
        if (!showHud || !engine.isPlaying) return@handler
        drawLyricHud(event.context)
    }

    private fun drawLyricHud(g: GuiGraphicsExtractor) {
        val track = engine.current ?: return
        val line = engine.lyrics.lineAt(engine.positionMs())?.text ?: track.title
        val w = mc.window.guiScaledWidth
        val textW = mc.font.width(line)
        val x = (w - textW) / 2
        val y = mc.window.guiScaledHeight - 48
        Md3Theme.fillRound(
            g,
            (x - 12).toFloat(),
            (y - 6).toFloat(),
            (textW + 24).toFloat(),
            20f,
            10f,
            Md3Theme.withAlpha(Md3Theme.SURFACE, 180),
        )
        g.text(mc.font, line, x, y, Md3Theme.ON_SURFACE, false)
    }

    private fun localMusicDir() = mc.gameDirectory.toPath().resolve("LiquidBounce/music")
    private fun cacheDir() = mc.gameDirectory.toPath().resolve("LiquidBounce/music-cache")
}
