package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Material
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Scheme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Anim
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Ease
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Typeface
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.UI
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.WavyProgressIndicator
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.screens.ChatScreen
import kotlin.math.abs
import kotlin.math.max

/**
 * Shared island artwork and geometry, including the endpoint of the player-window morph.
 *
 * <p>Ported 1:1 from NeoRaven's `music.MusicIsland`: the card is authored at 296x130 design units and
 * scaled as a whole, so every control keeps its position and the click map can stay in those units.
 * The only substitutions are host-side: the "mini size" setting lives on this module instead of the
 * client's watermark module, and the chat check asks LiquidBounce's screen for `ChatScreen` instead of
 * Forge's `GuiChat`.</p>
 */
object MusicIsland {

    const val MINI_WIDTH = 296.0F
    const val MINI_HEIGHT = 130.0F
    const val PROGRESS_X = 45.0F
    const val PROGRESS_Y = 72.0F
    const val CONTROLS_Y = 104.0F

    private var miniUntil = 0L
    private val lyricFade = Anim(1.0F)
    private var lyric = ""
    private var previousLyric = ""

    fun hasTrack(): Boolean = MusicManager.current != null

    /** User setting: the compact card is authored at 296x130 and scaled as a whole. */
    fun miniSize(): Float = Material.clamp(ModuleMusic.islandSize(), 0.5F, 2.0F)

    fun miniWidth(): Float = MINI_WIDTH * miniSize()

    fun miniHeight(): Float = MINI_HEIGHT * miniSize()

    /** The card takes the island over while chat is open, or for three seconds after the window closes. */
    fun miniVisible(): Boolean =
        hasTrack() && (mc.gui.screen() is ChatScreen || System.nanoTime() < miniUntil)

    /** The three-second hold starts after the returning window has reached the island. */
    fun returned() {
        miniUntil = if (hasTrack()) System.nanoTime() + 3_000_000_000L else 0L
    }

    fun progress(): Float {
        val manager = MusicManager
        return if (manager.durationMs() <= 0L) 0.0F
        else Material.clamp(manager.positionMs() / manager.durationMs().toFloat(), 0.0F, 1.0F)
    }

    fun currentLyric(): String {
        val manager = MusicManager
        val lines = manager.lyrics()
        val position = manager.positionMs()
        // Seek and track switches can move backwards; binary search does not retain a stale index.
        var low = 0
        var high = lines.size - 1
        var index = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= position) {
                index = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (index >= 0 && lines[index].text.trim().isNotEmpty()) return lines[index].text
        return manager.current?.name ?: "Music Player"
    }

    fun tick(delta: Float) {
        CoverCache.tick()
        val next = currentLyric()
        if (next != lyric) {
            previousLyric = lyric
            lyric = next
            lyricFade.set(0.0F)
            lyricFade.to(1.0F, 240.0F, Ease.STANDARD)
        }
        lyricFade.update(delta)
    }

    /**
     * Draws the compact card only; the caller owns the single continuous rounded surface.
     *
     * <p>The card is authored at 296x130 and scaled as a whole by the user's "Island size", so every
     * control stays on the same layout and the click map keeps using those design units.</p>
     */
    fun draw(ui: UI, scheme: Scheme, width: Float, height: Float, expansion: Float) {
        if (!hasTrack()) return
        val manager = MusicManager
        val song = manager.current ?: return
        val mini = Material.clamp(expansion, 0.0F, 1.0F)
        if (mini < 0.01F) return
        val size = miniSize()
        val cardWidth = MINI_WIDTH * size
        val cardHeight = MINI_HEIGHT * size
        ui.alphaPush(Material.clamp((mini - 0.2F) / 0.8F, 0.0F, 1.0F))
        ui.push()
        ui.translate(
            (width - cardWidth) / 2.0F,
            (height - cardHeight) / 2.0F + (1.0F - mini) * -12.0F,
        )
        ui.scale(size, 0.0F, 0.0F)
        drawCover(ui, scheme, song, 14.0F, 14.0F, 44.0F)
        drawLyric(ui, scheme, 68.0F, 25.0F, MINI_WIDTH - 112.0F, MusicTypeface.CARD_HEADLINE)
        val subtitle = if (song.artist.isEmpty()) song.name else song.name + " - " + song.artist
        ui.text(
            MusicTypeface.CARD_ARTIST, ui.truncate(MusicTypeface.CARD_ARTIST, subtitle, MINI_WIDTH - 84.0F),
            68.0F, 47.0F, scheme.onSurfaceVariant, UI.ALIGN_LEFT,
        )
        ui.rounded(MINI_WIDTH - 38.0F, 15.0F, 24.0F, 24.0F, 7.0F, scheme.primaryContainer)
        ui.icon("\ue405", MINI_WIDTH - 26.0F, 27.0F, 18.0F, scheme.onPrimaryContainer)
        WavyProgressIndicator.drawLinear(
            ui, PROGRESS_X, PROGRESS_Y - 4.0F, MINI_WIDTH - PROGRESS_X * 2.0F,
            8.0F, progress(),
            WavyProgressIndicator.Style(22.0F, 0.85F, 22.0F, scheme.primary, scheme.surfaceContainerHighest),
        )
        ui.text(MusicTypeface.CARD_CLOCK, time(manager.positionMs()), 14.0F, PROGRESS_Y, scheme.onSurfaceVariant, UI.ALIGN_LEFT)
        ui.text(MusicTypeface.CARD_CLOCK, time(manager.durationMs()), MINI_WIDTH - 14.0F, PROGRESS_Y, scheme.onSurfaceVariant, UI.ALIGN_RIGHT)
        ui.icon(
            if (manager.repeat() == MusicManager.Repeat.ONE) "\ue041" else "\ue040", 30.0F, CONTROLS_Y, 20.0F,
            if (manager.repeat() == MusicManager.Repeat.OFF) scheme.onSurfaceVariant else scheme.primary,
        )
        ui.icon("\ue045", MINI_WIDTH / 2.0F - 50.0F, CONTROLS_Y, 23.0F, scheme.onSurface)
        ui.rounded(
            MINI_WIDTH / 2.0F - 18.0F, CONTROLS_Y - 18.0F, 36.0F, 36.0F, 18.0F,
            Material.withAlpha(scheme.onSurface, 0.10F),
        )
        ui.outline(
            MINI_WIDTH / 2.0F - 18.0F, CONTROLS_Y - 18.0F, 36.0F, 36.0F, 18.0F, 0.5F,
            Material.withAlpha(scheme.onSurface, 0.12F),
        )
        ui.icon(
            if (manager.isPlaying()) "\ue034" else "\ue037", MINI_WIDTH / 2.0F, CONTROLS_Y,
            28.0F, scheme.onSurface,
        )
        ui.icon("\ue044", MINI_WIDTH / 2.0F + 50.0F, CONTROLS_Y, 23.0F, scheme.onSurface)
        ui.icon("\ue5d0", MINI_WIDTH - 30.0F, CONTROLS_Y, 20.0F, scheme.onSurfaceVariant)
        ui.pop()
        ui.alphaPop()
    }

    private fun drawCover(ui: UI, scheme: Scheme, song: Song, x: Float, y: Float, size: Float) {
        // The corner mask is baked into the cached texture, so the radius is asked for in *texture*
        // pixels: a 96 px cover shown at 44 px with a 10.6 px corner needs a 23 px mask.
        val cover = CoverCache.get(song.cover(96), (96.0F * 0.24F).toInt())
        if (cover != null) {
            ui.imageRounded(cover, x, y, size, size, size * 0.24F, -1)
        } else {
            ui.rounded(x, y, size, size, size * 0.24F, scheme.primaryContainer)
            ui.icon("\ue405", x + size / 2.0F, y + size / 2.0F, size * 0.55F, scheme.onPrimaryContainer)
        }
    }

    private fun drawLyric(
        ui: UI, scheme: Scheme, x: Float, y: Float, maxWidth: Float,
        style: Typeface.Style = MusicTypeface.LABEL_TIGHT,
    ) {
        val t = lyricFade.value()
        ui.alphaPush(1.0F - t)
        ui.text(style, ui.truncate(style, previousLyric, maxWidth), x, y - t * 5.0F, scheme.onSurface, UI.ALIGN_LEFT)
        ui.alphaPop()
        ui.alphaPush(t)
        ui.text(style, ui.truncate(style, lyric, maxWidth), x, y + (1.0F - t) * 5.0F, scheme.onSurface, UI.ALIGN_LEFT)
        ui.alphaPop()
    }

    /** Shares the transport layout with drawing, so chat clicks follow the compact card exactly. */
    fun click(x: Float, y: Float): Boolean {
        val manager = MusicManager
        if (x >= 14.0F && x <= 58.0F && y >= 14.0F && y <= 58.0F) {
            openScreen()
        } else if (y >= PROGRESS_Y - 10.0F && y <= PROGRESS_Y + 10.0F && x >= PROGRESS_X && x <= MINI_WIDTH - PROGRESS_X) {
            manager.seek((manager.durationMs() * (x - PROGRESS_X) / (MINI_WIDTH - 2.0F * PROGRESS_X)).toLong())
        } else if (abs(y - CONTROLS_Y) <= 20.0F) {
            if (abs(x - 30.0F) < 18.0F) manager.cycleRepeat()
            else if (abs(x - (MINI_WIDTH / 2.0F - 50.0F)) < 20.0F) manager.previous()
            else if (abs(x - MINI_WIDTH / 2.0F) < 22.0F) manager.togglePlay()
            else if (abs(x - (MINI_WIDTH / 2.0F + 50.0F)) < 20.0F) manager.next()
            else if (abs(x - (MINI_WIDTH - 30.0F)) < 18.0F) openScreen()
            else return false
        } else {
            return false
        }
        return true
    }

    /** Opens the player window through the module, so the same morph the keybind uses is reused. */
    fun openScreen() {
        mc.gui.setScreen(MusicScreen())
    }

    fun time(millis: Long): String {
        val seconds = max(0L, millis) / 1000L
        return (seconds / 60L).toString() + ":" + (if (seconds % 60L < 10) "0" else "") + (seconds % 60L)
    }
}
