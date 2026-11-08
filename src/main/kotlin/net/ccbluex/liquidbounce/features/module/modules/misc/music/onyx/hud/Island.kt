package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.hud

import net.ccbluex.liquidbounce.features.module.modules.misc.music.ModuleMusic
import net.ccbluex.liquidbounce.features.module.modules.misc.music.MusicIsland
import net.ccbluex.liquidbounce.features.module.modules.misc.music.MusicManager
import net.ccbluex.liquidbounce.features.module.modules.misc.music.MusicTypeface
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Material
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Scheme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Theme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Anim
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Blur
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Ease
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Typeface
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.UI
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.WavyProgressIndicator
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The HUD surface the player lives in: a small pill that carries the now-playing row and expands
 * into the 296x130 mini card.
 *
 * <p>Port of the music subset of Onyx's `hud.Island`. Onyx's island is also the client's watermark,
 * its tab-list, its chest-loot panel, its scaffold-gauge and its toggle announcer; none of those
 * exist in a single-purpose module, so the port keeps the parts the music player needs and the exact
 * geometry maths around them — the 36.7 px fixed edge, 22 px pill and 30 px gauge heights, the
 * `MARGIN`/`EDGE` insets, the `EMPHASIZED_DECELERATE` 460/420 ms mini expansion, the animated
 * content width that makes the pill breathe instead of snapping, the 2.5 px progress ring clipped to
 * the played fraction, and the morphed lyric row with its three equaliser bars.</p>
 *
 * <p>Everything the original read from `WatermarkModule` (position, scale, "show lyric instead of the
 * title") is read from this module's own settings instead, and the raven logo bitmap is replaced by
 * the Material music glyph, tinted like the rest of the island — the deliverable has no raven PNG and
 * no second HUD to share.</p>
 */
object Island {

    // ------------------------------------------------------------- geometry

    private const val FIXED_WIDTH_D = 36.695652F
    private const val MIN_CONTENT_D = 22.0F
    private const val MAX_TEXT_D = 90.0F
    private const val HEIGHT_D = 22.0F
    private const val GAUGE_HEIGHT_D = 30.0F

    /** Where the lyric text starts inside the pill (logo edge + logo gap). */
    private const val TEXT_X_D = 29.695652F

    /** How long a paused track keeps the island before the idle pill comes back. */
    private const val MUSIC_PAUSE_TIMEOUT_MS = 10000L

    private const val MARGIN = 10.0F
    private const val EDGE = 20.0F

    /** Same frosted recipe as the keybind card: the surface tint covers 58% of the blurred scene. */
    private const val FROST_COVERAGE = 0.58F

    private const val IDLE = 0
    private const val MUSIC = 3

    private const val CLIENT_NAME = "LiquidBounce"
    private const val MUSIC_ICON = "\ue405"

    // ---------------------------------------------------------------- state

    private val visible = Anim(0.0F)
    private val contentWidth = Anim(MIN_CONTENT_D)
    private val contentHeight = Anim(HEIGHT_D)
    private val morph = Anim(1.0F)
    private val flash = Anim(0.0F)
    private val musicProgressAnim = Anim(0.0F)
    private val miniOpen = Anim(0.0F)

    private var lastFrame = 0L
    private var phase = 0.0F

    private var kind = IDLE
    private var previousKind = IDLE
    private var text = CLIENT_NAME
    private var previousText = ""

    private var musicActive = false
    private var musicTitle = ""
    private var musicPlaying = false

    /** Last GUI size the island rendered at, so [measure] works before the first HUD frame. */
    private var guiWidth = 320.0F
    private var guiHeight = 240.0F

    private var x = 0.0F
    private var y = 0.0F
    private var w = 0.0F
    private var h = 0.0F

    // ------------------------------------------------------------- lifecycle

    /** Clears per-session state so re-enabling starts from a clean idle island. */
    fun reset() {
        visible.set(0.0F)
        contentWidth.set(MIN_CONTENT_D)
        contentHeight.set(HEIGHT_D)
        morph.set(1.0F)
        flash.set(0.0F)
        musicProgressAnim.set(0.0F)
        miniOpen.set(0.0F)
        kind = IDLE
        previousKind = IDLE
        text = CLIENT_NAME
        previousText = ""
        musicActive = false
        musicPlaying = false
        musicTitle = ""
        lastFrame = 0L
    }

    // -------------------------------------------------------------- geometry

    fun left(): Float = x

    fun top(): Float = y

    fun width(): Float = w

    fun height(): Float = h

    fun inside(mouseX: Float, mouseY: Float): Boolean =
        mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h

    /** Design-space size of the surface (before the HUD scale is applied). */
    fun designWidth(): Float {
        val pill = FIXED_WIDTH_D + contentWidth.value()
        val expansion = Material.clamp(miniOpen.value(), 0.0F, 1.0F)
        return pill + (MusicIsland.miniWidth() - pill) * expansion
    }

    fun designHeight(): Float {
        val pill = contentHeight.value()
        val expansion = Material.clamp(miniOpen.value(), 0.0F, 1.0F)
        return pill + (MusicIsland.miniHeight() - pill) * expansion
    }

    /**
     * Physical-pixel rectangle the island occupies for its current state, using the same maths as
     * [render]. The player window starts and ends its morph on exactly this rect.
     */
    fun measure(guiWidth: Float = this.guiWidth, guiHeight: Float = this.guiHeight): FloatArray {
        var scale = ModuleMusic.islandScale()
        val designWidth = designWidth()
        val designHeight = designHeight()
        scale = min(scale, min((guiWidth - EDGE) / designWidth, (guiHeight - EDGE) / designHeight))
        val width = designWidth * scale
        val height = designHeight * scale
        val maxX = max(0.0F, guiWidth - width - EDGE)
        val maxY = max(0.0F, guiHeight - height - EDGE)
        val px = Material.clamp(ModuleMusic.islandPositionX(), 0.0F, 100.0F)
        val py = Material.clamp(ModuleMusic.islandPositionY(), 0.0F, 100.0F)
        return floatArrayOf(MARGIN + maxX * px / 100.0F, MARGIN + maxY * py / 100.0F, width, height)
    }

    /** Primes the exact return endpoint before the GUI gives rendering back to the HUD. */
    fun returnFromMusic(mini: Boolean) {
        refreshMusicSource()
        kind = if (musicActive) MUSIC else IDLE
        previousKind = kind
        text = if (musicActive) musicTitle else CLIENT_NAME
        previousText = text
        visible.set(1.0F)
        morph.set(1.0F)
        contentWidth.set(targetContentWidth())
        contentHeight.set(HEIGHT_D)
        miniOpen.set(if (mini && musicActive) 1.0F else 0.0F)
        lastFrame = 0L
    }

    /**
     * Draws the island's current content into another UI so the player window can start its morph
     * from the exact HUD artwork that was on screen a frame earlier.
     */
    fun snapshot(target: UI, scheme: Scheme, logo: Boolean) {
        val designWidth = designWidth()
        val designHeight = designHeight()
        if (logo) {
            target.icon(MUSIC_ICON, 7.0F + (16.695652F - 14.0F) / 2.0F + 7.0F, designHeight / 2.0F, 14.0F, scheme.primary)
        }
        drawContent(target, scheme, kind, text, designWidth, designHeight, 0.0F)
        if (kind == MUSIC) drawMusicProgress(target, scheme, designWidth, designHeight, designHeight / 2.0F)
    }

    // ------------------------------------------------------------------ tick

    private fun tick() {
        val now = System.nanoTime()
        val delta = if (lastFrame == 0L) 16.0F else min((now - lastFrame) / 1_000_000.0F, 100.0F)
        lastFrame = now
        update(delta)
        MusicIsland.tick(delta)
        val mini = MusicIsland.miniVisible()
        miniOpen.to(if (mini) 1.0F else 0.0F, if (mini) 460.0F else 420.0F, Ease.EMPHASIZED_DECELERATE)
        miniOpen.update(delta)
    }

    private fun update(delta: Float) {
        phase = (phase + delta) % 100000.0F
        val active = ModuleMusic.islandPillActive() || MusicIsland.hasTrack()
        visible.to(if (active) 1.0F else 0.0F, if (active) 300.0F else 200.0F, if (active) Ease.EMPHASIZED else Ease.DECELERATE)
        visible.update(delta)
        flash.update(delta)

        if (active) {
            refreshMusicSource()
            chooseContent()
        } else {
            musicActive = false
        }

        contentWidth.to(max(MIN_CONTENT_D, targetContentWidth()), 200.0F, Ease.EMPHASIZED_DECELERATE)
        contentWidth.update(delta)
        contentHeight.to(if (kind == 2) GAUGE_HEIGHT_D else HEIGHT_D, 200.0F, Ease.EMPHASIZED_DECELERATE)
        contentHeight.update(delta)
        morph.to(1.0F, 250.0F, Ease.EMPHASIZED_DECELERATE)
        morph.update(delta)
        musicProgressAnim.to(if (musicActive) MusicIsland.progress() else 0.0F, 250.0F, Ease.DECELERATE)
        musicProgressAnim.update(delta)
    }

    private fun refreshMusicSource() {
        musicActive = false
        val manager = MusicManager
        manager.observePlayback()
        val song = manager.current ?: return
        // A song left paused for a while gives the island back to the client name: an advert for a
        // track nobody is listening to is worse than the product's own name.
        if (manager.pausedFor(MUSIC_PAUSE_TIMEOUT_MS)) return
        musicPlaying = manager.isPlaying()
        // "Island lyric" chooses between the synced lyric line and the plain song title.
        val lyric = if (ModuleMusic.islandShowsLyric()) MusicIsland.currentLyric() else ""
        musicTitle = if (lyric.isBlank()) musicLabel(song.name, song.artist) else musicLabel(lyric.trim(), song.artist)
        musicActive = true
    }

    private fun chooseContent() {
        val nextKind: Int
        val nextText: String
        val musicCard = musicActive && miniOpen.value() > 0.5F
        if (musicCard || musicActive) {
            nextKind = MUSIC
            nextText = musicTitle
        } else {
            nextKind = IDLE
            nextText = CLIENT_NAME
        }

        val changed = when {
            nextKind != kind -> true
            nextKind == MUSIC -> nextText != text
            else -> false
        }
        if (changed) {
            previousKind = kind
            previousText = text
            kind = nextKind
            text = nextText
            morph.set(0.0F)
            morph.to(1.0F, 250.0F, Ease.EMPHASIZED_DECELERATE)
        }
    }

    private fun targetContentWidth(): Float = when (kind) {
        MUSIC -> min(musicStyle().width(text), musicTextMax()) + 6.0F + 10.0F
        else -> Typeface.LABEL_TIGHT.width(text)
    }

    /** A lyric line needs more room than a short song title before it gets truncated. */
    private fun musicTextMax(): Float = if (ModuleMusic.islandShowsLyric()) 175.0F else 130.0F

    /**
     * Falls back to the artist, then to a generic label, when the face cannot render a title.
     */
    private fun musicLabel(title: String, artist: String): String {
        if (title.isNotEmpty() && musicStyle().supports(title)) return title
        if (artist.isNotEmpty() && musicStyle().supports(artist)) return artist
        return "Now playing"
    }

    /**
     * The music row is part of the player, so it uses the player's own face; `ISLAND_ROW` is the same
     * MiSans Medium one step larger, because the face runs small at pill size.
     */
    private fun musicStyle(): Typeface.Style = MusicTypeface.ISLAND_ROW

    // --------------------------------------------------------------- drawing

    /** Draws (and ticks) the island into the HUD. */
    fun render(ctx: GuiGraphicsExtractor) {
        guiWidth = ctx.guiWidth().toFloat()
        guiHeight = ctx.guiHeight().toFloat()
        tick()

        val fade = visible.value()
        if (fade <= 0.002F) return
        val scheme = Theme.scheme()

        val musicExpansion = miniOpen.value()
        val expansion = musicExpansion
        val scale = ModuleMusic.islandScale() * (0.94F + 0.06F * fade)
        val content = contentWidth.value()
        val pillWidth = FIXED_WIDTH_D + content
        val pillHeight = contentHeight.value()
        val islandWidth = pillWidth + (MusicIsland.miniWidth() - pillWidth) * musicExpansion
        val islandHeight = pillHeight + (MusicIsland.miniHeight() - pillHeight) * musicExpansion
        val fitted = min(scale, min((guiWidth - EDGE) / islandWidth, (guiHeight - EDGE) / islandHeight))
        w = islandWidth * fitted
        h = islandHeight * fitted

        val maxX = max(0.0F, guiWidth - w - EDGE)
        val maxY = max(0.0F, guiHeight - h - EDGE)
        x = MARGIN + maxX * (Material.clamp(ModuleMusic.islandPositionX(), 0.0F, 100.0F) / 100.0F)
        y = MARGIN + maxY * (Material.clamp(ModuleMusic.islandPositionY(), 0.0F, 100.0F) / 100.0F)

        val ui = UI(ctx)
        ui.begin()
        ui.push()
        try {
            ui.alphaPush(fade)
            ui.translate(x, y + (1.0F - fade) * 5.0F)
            ui.scale(fitted, 0.0F, 0.0F)

            val radius = pillHeight / 2.0F + (24.0F - pillHeight / 2.0F) * expansion
            ui.shadow(0.0F, 0.0F, islandWidth, islandHeight, radius, 3)
            ui.rounded(0.0F, 0.0F, islandWidth, islandHeight, radius, scheme.surfaceContainerHigh)
            if (Theme.frost() && Blur.ready()) {
                ui.frost(0.0F, 0.0F, islandWidth, islandHeight, radius, scheme.surfaceContainerHigh, FROST_COVERAGE)
            }
            // No resting border: the outline is only the state-change flash ring.
            val border = Material.clamp(flash.value(), 0.0F, 1.0F)
            if (border > 0.01F && expansion < 0.01F) {
                ui.outline(0.0F, 0.0F, islandWidth, islandHeight, radius, 1.0F, Material.withAlpha(scheme.outlineVariant, border))
            }

            ui.alphaPush(1.0F - Material.clamp(expansion * 3.0F, 0.0F, 1.0F))
            ui.scissorPush(0.0F, 0.0F, islandWidth, islandHeight)
            val t = morph.value()
            if (t < 0.999F) {
                ui.alphaPush(1.0F - t)
                drawContent(ui, scheme, previousKind, previousText, islandWidth, islandHeight, -6.0F * t)
                ui.alphaPop()
            }
            if (t > 0.001F) {
                ui.alphaPush(t)
                drawContent(ui, scheme, kind, text, islandWidth, islandHeight, 6.0F * (1.0F - t))
                ui.alphaPop()
            }
            if (kind == MUSIC) drawMusicProgress(ui, scheme, islandWidth, islandHeight, radius)
            ui.scissorPop()
            ui.alphaPop()

            if (musicExpansion > 0.001F) {
                ui.scissorPush(3.0F, 3.0F, islandWidth - 6.0F, islandHeight - 6.0F)
                MusicIsland.draw(ui, scheme, islandWidth, islandHeight, musicExpansion)
                ui.scissorPop()
            }
            ui.alphaPop()
        } finally {
            ui.pop()
            ui.end()
        }
    }

    private fun drawMusicProgress(ui: UI, scheme: Scheme, islandWidth: Float, islandHeight: Float, radius: Float) {
        val progress = Material.clamp(musicProgressAnim.value(), 0.0F, 1.0F)
        val stripY = islandHeight - 6.0F
        ui.scissorPush(0.0F, stripY, islandWidth, 6.0F)
        ui.outline(0.0F, 0.0F, islandWidth, islandHeight, radius, 2.5F, scheme.surfaceContainerHighest)
        if (progress > 0.01F) {
            ui.scissorPush(0.0F, stripY, islandWidth * progress, 6.0F)
            ui.outline(0.0F, 0.0F, islandWidth, islandHeight, radius, 2.5F, scheme.primary)
            ui.scissorPop()
        }
        ui.scissorPop()
    }

    private fun drawContent(
        ui: UI, scheme: Scheme, contentKind: Int, contentText: String,
        islandWidth: Float, islandHeight: Float, offsetX: Float,
    ) {
        when (contentKind) {
            MUSIC -> {
                val style = musicStyle()
                val maxText = musicTextMax()
                ui.icon(MUSIC_ICON, 15.35F + offsetX, islandHeight / 2.0F, 14.0F, scheme.primary)
                val label = ui.truncate(style, contentText, maxText)
                ui.text(style, label, TEXT_X_D + offsetX, islandHeight / 2.0F, scheme.onSurface, UI.ALIGN_LEFT)
                val barsX = TEXT_X_D + offsetX + style.width(label) + 6.0F
                for (i in 0..2) {
                    val level = if (musicPlaying) 0.5F + 0.5F * sin(phase * 0.007F + i * 2.1F) else 0.1F
                    val barHeight = 3.0F + 6.0F * level
                    ui.rounded(
                        barsX + i * 4.0F, islandHeight / 2.0F - barHeight / 2.0F, 2.0F, barHeight,
                        1.0F, scheme.primary,
                    )
                }
            }
            else -> {
                ui.icon(MUSIC_ICON, 15.35F + offsetX, islandHeight / 2.0F, 14.0F, scheme.primary)
                ui.text(
                    Typeface.LABEL_TIGHT, contentText, TEXT_X_D + offsetX, islandHeight / 2.0F,
                    scheme.onSurface, UI.ALIGN_LEFT,
                )
            }
        }
    }

    // ----------------------------------------------------------------- input

    /** Handles transport clicks before chat receives them. */
    fun musicClick(mouseX: Float, mouseY: Float): Boolean {
        if (!MusicIsland.hasTrack()) return false
        if (miniOpen.value() < 0.98F) return false
        if (!inside(mouseX, mouseY)) return false
        // The card is drawn in its own 296x130 design space, so the click map stays in that space.
        val localX = (mouseX - x) * MusicIsland.MINI_WIDTH / w
        val localY = (mouseY - y) * MusicIsland.MINI_HEIGHT / h
        return MusicIsland.click(localX, localY)
    }

    /** True while the mini card owns the island (used by the screen's morph endpoint). */
    fun musicCardOpen(): Boolean = MusicIsland.hasTrack() && miniOpen.value() > 0.98F

    /** The pill's current scale factor, so the screen can match the morph's endpoint size. */
    fun renderScale(): Float = ModuleMusic.islandScale()
}
