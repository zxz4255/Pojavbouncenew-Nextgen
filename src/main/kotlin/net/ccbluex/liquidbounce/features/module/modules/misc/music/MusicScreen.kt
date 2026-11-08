package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.hud.Island
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Material
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Scheme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Theme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Anim
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Blur
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Ease
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Spring
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Typeface
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.UI
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.WavyProgressIndicator
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.PreeditEvent
import net.minecraft.network.chat.Component
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The music player window: a Material 3 shell with a nav rail, a content area, a mini player bar and
 * the lyric panel.
 *
 * <p>Ported 1:1 from NeoRaven's `MusicScreen`, keeping its proportions (840x520 design canvas, 28 px
 * corner radius, 42 px nav rail, 84 px player bar, cover-tinted palette) and its whole interaction
 * model: a hit list rebuilt every frame, the window growing out of — and collapsing back into — the
 * island, the spring-scrolled content area, the lyric view and the QR login overlay.</p>
 *
 * <p>Two host differences, neither of them visual. Screens on 26.3 receive immutable input events
 * instead of the old `mouseClicked(int, int, int)` family, so the same numbers are unpacked from
 * [MouseButtonEvent]/[KeyEvent]/[CharacterEvent]; and the morph's endpoint comes from this module's
 * own island (see [Island]) rather than a client watermark module.</p>
 */
class MusicScreen : Screen(Component.literal("Music")) {

    private val manager = MusicManager
    private val lyricPanel = LyricPanel(this)
    private val hits = ArrayList<Hit>()
    private val open = Anim(0.0F)
    private val scrim = Anim(0.0F)
    private val scroll = Spring(0.0F)
    private val hover = Spring(0.0F)

    private var page = Page.HOME
    private var scheme: Scheme? = null
    private var coverSeed = 0
    private var scale = 1.0F
    private var gx = 0.0F
    private var gy = 0.0F
    private var mouseX = 0.0F
    private var mouseY = 0.0F
    private var delta = 16.0F
    private var lastFrame = 0L
    private var initialized = false

    /** Diagnostics: the window logs its geometry once, and a failing frame only once. */
    private var loggedOpen = false
    private val renderFailed = java.util.concurrent.atomic.AtomicBoolean(false)
    private var closing = false
    private var closeCommitted = false
    private var returnMini = false
    private var endpointMini = false
    private var islandBounds = floatArrayOf(0.0F, 0.0F, 0.0F, 0.0F)
    private var closingBounds = floatArrayOf(0.0F, 0.0F, 0.0F, 0.0F)
    private var drawX = 0.0F
    private var drawY = 0.0F
    private var drawScale = 1.0F
    private var shellX = 0.0F
    private var shellY = 0.0F
    private var shellW = 0.0F
    private var shellH = 0.0F
    private var contentHeight = 0.0F
    private var contentScrollable = false
    private var lastMouseX = 0.0F
    private var lastMouseY = 0.0F

    private var searchText = ""
    private var searchFocused = false
    private var searchTypedAt = 0L
    private var caretAt = 0L

    private var draggingProgress = false
    private var progressPreview = -1.0F
    private var draggingVolume = false
    private var draggingSlider: SliderHit? = null
    private var searchRectX = 0.0F
    private var searchRectY = 0.0F
    private var searchRectW = 0.0F
    private var searchRectH = 0.0F

    private var playlistOpen = false
    private var lyricOpen = false
    private var qrOpen = false

    init {
        manager.boot()
    }

    fun scheme(): Scheme = scheme ?: Theme.scheme()

    fun delta(): Float = delta

    fun lyricTranslation(): Boolean = true

    /** Whether the pointer is inside the given local rectangle (used by the lyric panel). */
    internal fun mouseIn(x: Float, y: Float, w: Float, h: Float): Boolean =
        mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h

    internal fun setLyricOpen(value: Boolean) {
        lyricOpen = value
        if (value) lyricPanel.reset()
    }

    // -------------------------------------------------------------- lifecycle

    override fun init() {
        scale = fitScale()
        lastFrame = System.nanoTime()
        if (!initialized) {
            islandBounds = Island.measure(width.toFloat(), height.toFloat())
            endpointMini = islandBounds[3] / islandBounds[2] > 0.3F && MusicIsland.hasTrack()
            open.set(0.0F)
            open.to(1.0F, OPEN_MS, Ease.LINEAR)
            scrim.set(0.0F)
            scrim.to(1.0F, 320.0F, Ease.STANDARD)
            initialized = true
        } else if (closing) {
            islandBounds = Island.measure(width.toFloat(), height.toFloat())
        }
        Blur.request()
        layout()
        if (!loggedOpen) {
            loggedOpen = true
            logger.info(
                "[Music] window init: {}x{} scale={} offset=({},{}) island={} mini={}",
                width, height, scale, gx, gy, islandBounds.toList(), endpointMini,
            )
        }
    }

    private fun fitScale(): Float {
        val fit = min(width * 0.92F / W, height * 0.92F / H)
        return max(0.1F, min(Theme.scale(), fit))
    }

    private fun layout() {
        val virtualWidth = width / scale
        val virtualHeight = height / scale
        gx = ((virtualWidth - W) / 2.0F).roundToInt().toFloat()
        gy = ((virtualHeight - H) / 2.0F).roundToInt().toFloat()
    }

    private fun frameStep() {
        val now = System.nanoTime()
        delta = min((now - lastFrame) / 1_000_000.0F, 100.0F)
        lastFrame = now
        open.update(delta)
        scrim.update(delta)
        hover.update(delta)
        MusicIsland.tick(delta)
        lyricPanel.tick(delta)
        tickSearch()
        tickScheme()
        scroll.update(delta)
        if (contentScrollable) {
            val max = max(0.0F, contentHeight - CT_H)
            if (scroll.target() > max) scroll.target(max)
            if (scroll.value() > max) scroll.set(max)
        } else if (scroll.value() != 0.0F) {
            scroll.set(0.0F)
        }
    }

    private fun tickSearch() {
        if (!searchFocused || searchText.isEmpty()) return
        if (System.currentTimeMillis() - searchTypedAt > 320L) {
            if (searchText != manager.searchQuery) {
                manager.search(searchText)
                page = Page.SEARCH
            }
        }
    }

    /** Rebuilds the palette from the current artwork; falls back to the client accent. */
    private fun tickScheme() {
        if (!manager.coverTint()) {
            if (scheme !== Theme.scheme()) scheme = Theme.scheme()
            return
        }
        val song = manager.current
        var seed = 0
        if (song != null) seed = CoverCache.dominant(song.cover(300))
        if (seed == 0) seed = Theme.accent()
        if (scheme == null || seed != coverSeed) {
            coverSeed = seed
            scheme = Scheme.of(seed, 0.35F)
        }
    }

    // -------------------------------------------------------------- rendering

    override fun extractRenderState(ctx: GuiGraphicsExtractor, rawMouseX: Int, rawMouseY: Int, partialTick: Float) {
        val renderer = UI(ctx)
        renderer.begin()
        try {
            drawFrame(renderer, rawMouseX, rawMouseY)
        } catch (t: Throwable) {
            // A blank panel is impossible to diagnose from the outside; the first failure is logged
            // once with its stack trace, later frames stay quiet instead of spamming the log.
            if (renderFailed.compareAndSet(false, true)) {
                logger.warn("[Music] window render failed — the panel will stay empty", t)
            }
        } finally {
            renderer.end()
        }
    }

    private fun drawFrame(renderer: UI, rawMouseX: Int, rawMouseY: Int) {
        frameStep()
        if (closing && open.isDone()) {
            closeCommitted = true
            MusicIsland.returned()
            Island.returnFromMusic(returnMini)
            mc.gui.setScreen(null)
            return
        }
        updateMouse(rawMouseX, rawMouseY)
        hits.clear()
        contentHeight = 0.0F

        drawBackdrop(renderer)
        drawMorph(renderer)
    }

    // ---------------------------------------------------------- island morph

    /** 0 = the island endpoint, 1 = the full window; eased in both directions. */
    private fun morph(): Float {
        val progress = open.progress()
        val eased = (if (closing) CLOSE_CURVE else OPEN_CURVE).get(Material.clamp(progress, 0.0F, 1.0F))
        return if (closing) 1.0F - eased else eased
    }

    private fun drawMorph(renderer: UI) {
        val k = Material.clamp(morph(), 0.0F, 1.0F)
        // Close from wherever the window actually is, so closing mid-open never jumps.
        val targetX = if (closing) closingBounds[0] else gx * scale
        val targetY = if (closing) closingBounds[1] else gy * scale
        val targetW = if (closing) closingBounds[2] else W * scale
        val targetH = if (closing) closingBounds[3] else H * scale
        shellX = islandBounds[0] + (targetX - islandBounds[0]) * k
        shellY = islandBounds[1] + (targetY - islandBounds[1]) * k
        shellW = islandBounds[2] + (targetW - islandBounds[2]) * k
        shellH = islandBounds[3] + (targetH - islandBounds[3]) * k
        val smallRadius = if (endpointMini) 24.0F * islandBounds[2] / MusicIsland.miniWidth() else islandBounds[3] / 2.0F
        val radius = smallRadius + (RADIUS * scale - smallRadius) * k
        val current = scheme()
        val color = Material.mix(Theme.scheme().surfaceContainerHigh, current.surfaceContainer, k)
        renderer.shadow(shellX, shellY, shellW, shellH, radius, (3.0F + k).roundToInt())
        renderer.rounded(shellX, shellY, shellW, shellH, radius, color)
        if (Theme.frost() && Blur.ready()) renderer.frost(shellX, shellY, shellW, shellH, radius, color, 0.58F + 0.35F * k)

        drawMorphArtwork(renderer, k)
        drawMorphWindow(renderer, k, radius)
    }

    /**
     * The island artwork the morph starts from. It rides inside the growing surface, is clipped by it
     * and never magnified past its own size, so the same continuous shape is shown in both directions
     * instead of a layer that pops in at a fixed size halfway through.
     */
    private fun drawMorphArtwork(renderer: UI, k: Float) {
        // Tablist's pill fade: the old island content is gone once the surface is a third open.
        val alpha = 1.0F - Material.clamp(k * 3.0F, 0.0F, 1.0F)
        if (alpha <= 0.003F) return
        // A 1.5 px bleed keeps the island's own outline ring identical to the HUD on the first and
        // last frame of the morph; the artwork is bounded to its own box anyway.
        renderer.scissorPush(shellX - 1.5F, shellY - 1.5F, max(0.0F, shellW + 3.0F), max(0.0F, shellH + 3.0F))
        if (endpointMini && MusicIsland.hasTrack()) {
            drawMorphArtworkBox(renderer, MusicIsland.miniWidth(), MusicIsland.miniHeight(), alpha, false)
        } else {
            drawMorphArtworkBox(renderer, Island.designWidth(), Island.designHeight(), alpha, true)
        }
        renderer.scissorPop()
    }

    private fun drawMorphArtworkBox(renderer: UI, designWidth: Float, designHeight: Float, alpha: Float, islandRow: Boolean) {
        if (designWidth < 1.0F || designHeight < 1.0F) return
        // Endpoint scale: never magnify the source artwork, so text stays crisp and the pill does not
        // blow up while the surface grows around it. Shrinking is allowed, so a closing window carries
        // the card into the island instead of cropping it.
        val endpoint = min(islandBounds[2] / designWidth, islandBounds[3] / designHeight)
        val fit = min(shellW / designWidth, shellH / designHeight)
        val factor = min(endpoint, fit)
        renderer.push()
        renderer.translate(
            shellX + (shellW - designWidth * factor) / 2.0F,
            shellY + (shellH - designHeight * factor) / 2.0F,
        )
        renderer.scale(factor, 0.0F, 0.0F)
        renderer.alphaPush(alpha)
        if (islandRow) {
            Island.snapshot(renderer, Theme.scheme(), true)
        } else {
            MusicIsland.draw(renderer, Theme.scheme(), designWidth, designHeight, 1.0F)
        }
        renderer.alphaPop()
        renderer.pop()
    }

    /**
     * The window content fades in on Tablist's own curve — a plain `(k - 0.15) / 0.65` ramp plus an
     * 8 px upward slide — and is read at its final layout size, so nothing is squashed while the
     * surface grows around it. Like the tab list, the content rides the surface: it is anchored to the
     * card's origin and clipped by it, then lands exactly on the final rect when k reaches 1.
     */
    private fun drawMorphWindow(renderer: UI, k: Float, radius: Float) {
        val alpha = Material.clamp((k - 0.15F) / 0.65F, 0.0F, 1.0F)
        if (alpha <= 0.003F) return
        val inset = radius * (1.0F - k) * 0.3F + 1.0F
        drawScale = scale
        drawX = gx * scale
        drawY = gy * scale
        renderer.scissorPush(
            shellX + inset, shellY + inset, max(0.0F, shellW - inset * 2.0F),
            max(0.0F, shellH - inset * 2.0F),
        )
        renderer.push()
        renderer.translate(shellX, shellY + (1.0F - k) * -8.0F)
        renderer.scale(drawScale, 0.0F, 0.0F)
        renderer.alphaPush(alpha)
        drawWindow(renderer)
        renderer.alphaPop()
        renderer.pop()
        renderer.scissorPop()
    }

    /** Called by the module's Escape hook, so Escape and programmatic closes share the morph. */
    fun deferClose(): Boolean {
        if (closeCommitted) return false
        if (!closing) {
            closingBounds = if (shellW > 0.0F) floatArrayOf(shellX, shellY, shellW, shellH) else islandBounds.copyOf()
            closing = true
            returnMini = MusicIsland.hasTrack()
            endpointMini = returnMini
            // Prime the HUD endpoint now, then measure it: the morph must land on the exact rect the
            // island will occupy on the next HUD frame.
            Island.returnFromMusic(returnMini)
            islandBounds = Island.measure(width.toFloat(), height.toFloat())
            open.to(0.0F, CLOSE_MS, Ease.LINEAR)
            scrim.to(0.0F, 220.0F, Ease.DECELERATE)
            draggingProgress = false
            draggingVolume = false
            draggingSlider = null
        }
        return true
    }

    private fun interactive(): Boolean = !closing && open.isDone()

    private fun drawBackdrop(renderer: UI) {
        val current = scheme()
        if (!inGame) {
            renderer.rect(0.0F, 0.0F, width.toFloat(), height.toFloat(), current.surface)
            renderer.gradient(0.0F, 0.0F, width.toFloat(), height.toFloat(), current.surfaceContainerLow, current.surface)
            return
        }
        if (Theme.frost()) Blur.prepare()
        renderer.rect(0.0F, 0.0F, width.toFloat(), height.toFloat(), Theme.scrim(scrim.value()))
    }

    private fun drawWindow(renderer: UI) {
        val current = scheme()
        renderer.scissorPush(1.0F, 1.0F, W - 2.0F, H - 2.0F)
        drawRail(renderer, current)
        drawHeader(renderer, current)
        if (lyricOpen) {
            lyricPanel.draw(renderer, current, CT_X, CT_Y, CT_W, CT_H)
        } else if (playlistOpen) {
            drawPlaylist(renderer, current)
        } else {
            drawContent(renderer, current)
        }
        drawMiniBar(renderer, current)
        if (qrOpen) drawQrOverlay(renderer, current)
        renderer.scissorPop()
    }

    private fun drawRail(renderer: UI, current: Scheme) {
        val pages = arrayOf(Page.HOME, Page.SEARCH, Page.MINE, Page.SETTINGS)
        val icons = arrayOf(I_HOME, I_SEARCH, I_PERSON, I_SETTINGS)
        val labels = arrayOf("推荐", "搜索", "我的", "设置")
        val cx = RAIL_X + RAIL_W / 2.0F
        for (i in pages.indices) {
            val target = pages[i]
            val selected = page == target && !playlistOpen && !lyricOpen
            val top = RAIL_Y + 10.0F + i * 56.0F
            val iconY = top + 16.0F
            val hovered = mouseX >= RAIL_X && mouseX < RAIL_X + RAIL_W && mouseY >= top && mouseY < top + 56.0F
            if (selected) {
                // The indicator is centred on the icon, not on the icon+label pair: a pill that
                // spanned both read as a checkbox once the label sat under it.
                renderer.rounded(cx - 19.0F, iconY - 14.0F, 38.0F, 28.0F, 14.0F, Material.withAlpha(current.secondaryContainer, 0.95F))
            } else if (hovered) {
                renderer.rounded(cx - 19.0F, iconY - 14.0F, 38.0F, 28.0F, 14.0F, Material.withAlpha(current.onSurface, 0.08F))
            }
            val iconColor = if (selected) current.onSecondaryContainer else current.onSurfaceVariant
            renderer.icon(icons[i], cx, iconY, 19.0F, iconColor)
            renderer.text(
                MusicTypeface.MICRO, labels[i], cx, top + 40.0F,
                if (selected) current.onSurface else Material.withAlpha(current.onSurfaceVariant, 0.85F),
                UI.ALIGN_CENTER,
            )
            hit(RAIL_X, top, RAIL_W, 56.0F) {
                page = target
                playlistOpen = false
                lyricOpen = false
                qrOpen = false
                scroll.set(0.0F)
            }
        }
        renderer.icon("\ue5cf", cx, H - 38.0F, 22.0F, current.onSurfaceVariant)
        renderer.text(MusicTypeface.MICRO, "收起", cx, H - 17.0F, current.onSurfaceVariant, UI.ALIGN_CENTER)
        hit(RAIL_X, H - 58.0F, RAIL_W, 48.0F) { mc.gui.setScreen(null) }
    }

    private fun drawHeader(renderer: UI, current: Scheme) {
        val title = when {
            playlistOpen -> manager.playlistTitle
            lyricOpen -> "歌词"
            page == Page.HOME -> "推荐"
            page == Page.SEARCH -> "搜索"
            page == Page.MINE -> "我的"
            else -> "设置"
        }
        val showSearch = !playlistOpen && !lyricOpen
        val titleWidth = if (showSearch) 96.0F else 300.0F
        renderer.text(DISPLAY, renderer.truncate(DISPLAY, title, titleWidth), CT_X + 16.0F, 28.0F, current.onSurface, UI.ALIGN_LEFT)

        val avatarSize = 40.0F
        val avatarX = W - 24.0F - avatarSize
        val searchX = CT_X + 128.0F
        val searchW = min(400.0F, avatarX - 24.0F - searchX)
        val searchY = 8.0F
        searchRectX = searchX
        searchRectY = searchY
        searchRectW = if (showSearch) searchW else 0.0F
        searchRectH = 40.0F
        val focused = searchFocused
        if (showSearch) {
            renderer.rounded(
                searchX, searchY, searchW, 40.0F, 20.0F,
                if (focused) current.surfaceContainerHighest else current.surfaceContainerHigh,
            )
            if (focused) {
                renderer.outline(searchX, searchY, searchW, 40.0F, 20.0F, 2.0F, Material.withAlpha(current.primary, 0.9F))
            }
            renderer.icon(I_SEARCH, searchX + 20.0F, searchY + 20.0F, 17.0F, current.onSurfaceVariant)
            val displayed = if (searchText.isEmpty()) "搜索歌曲、歌手" else searchText
            val textColor = if (searchText.isEmpty()) Material.withAlpha(current.onSurfaceVariant, 0.75F) else current.onSurface
            renderer.text(SEARCH, renderer.truncate(SEARCH, displayed, searchW - 52.0F), searchX + 38.0F, searchY + 20.0F, textColor, UI.ALIGN_LEFT)
            if (focused && (System.currentTimeMillis() / 530L) % 2L == 0L) {
                val caretX = searchX + 38.0F + SEARCH.width(searchText) + 1.0F
                renderer.rect(caretX, searchY + 12.0F, 1.6F, 16.0F, current.primary)
            }
            hit(searchX, searchY, searchW, 40.0F) {
                searchFocused = true
                caretAt = System.currentTimeMillis()
            }
        }

        val profile = manager.profile
        renderer.rounded(avatarX, searchY, avatarSize, avatarSize, avatarSize / 2.0F, Material.withAlpha(current.surfaceContainerHighest, 1.0F))
        if (profile != null) {
            val avatar = CoverCache.get(profile.avatar(80), texRadius(80, avatarSize, avatarSize / 2.0F))
            if (avatar != null) {
                renderer.imageRounded(avatar, avatarX, searchY, avatarSize, avatarSize, avatarSize / 2.0F, -1)
            } else {
                renderer.icon(I_PERSON, avatarX + avatarSize / 2.0F, searchY + avatarSize / 2.0F, 20.0F, current.onSurfaceVariant)
            }
        } else {
            renderer.icon(I_PERSON, avatarX + avatarSize / 2.0F, searchY + avatarSize / 2.0F, 20.0F, current.onSurfaceVariant)
        }
        hit(avatarX, searchY, avatarSize, avatarSize) {
            page = Page.MINE
            playlistOpen = false
            lyricOpen = false
            qrOpen = manager.profile == null
            scroll.set(0.0F)
        }
    }

    // ------------------------------------------------------------------ pages

    private fun drawContent(renderer: UI, current: Scheme) {
        renderer.scissorPush(CT_X, CT_Y, CT_W, CT_H)
        val start = CT_Y - scroll.value()
        var y = start
        y = when (page) {
            Page.HOME -> drawHome(renderer, current, y)
            Page.SEARCH -> drawSearchPage(renderer, current, y)
            Page.MINE -> drawMine(renderer, current, y)
            Page.SETTINGS -> drawSettings(renderer, current, y)
        }
        contentHeight = y - start + 12.0F
        contentScrollable = contentHeight > CT_H
        renderer.scissorPop()
    }

    private fun drawHome(renderer: UI, current: Scheme, startY: Float): Float {
        var y = startY
        renderer.text(DISPLAY, greeting(), CT_X + 8.0F, y + 18.0F, current.onSurface, UI.ALIGN_LEFT)
        y += 44.0F
        y = section(renderer, current, "推荐歌单", y)
        val home = manager.home
        if (home.isEmpty()) {
            renderer.text(
                MusicTypeface.BODY,
                if (manager.homeLoading()) "正在加载…" else "暂无推荐（登录后可看到每日推荐）",
                CT_X + 8.0F, y + 12.0F, current.onSurfaceVariant, UI.ALIGN_LEFT,
            )
            y += 40.0F
        } else {
            y = grid(renderer, current, home, y, 5, true)
        }
        y += 8.0F
        val mine = manager.myPlaylists
        if (mine.isNotEmpty()) {
            y = section(renderer, current, "我的歌单", y)
            y = grid(renderer, current, mine, y, 5, true)
        }
        return y
    }

    private fun greeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 5 -> "夜深了"
            hour < 11 -> "早上好"
            hour < 14 -> "中午好"
            hour < 18 -> "下午好"
            else -> "晚上好"
        }
    }

    private fun section(renderer: UI, current: Scheme, title: String, y: Float): Float {
        renderer.text(MusicTypeface.HEADLINE, title, CT_X + 8.0F, y + 14.0F, current.onSurface, UI.ALIGN_LEFT)
        return y + 32.0F
    }

    private fun grid(renderer: UI, current: Scheme, playlists: List<Playlist>, y: Float, columns: Int, withTitle: Boolean): Float {
        val gap = 10.0F
        val available = CT_W - 16.0F
        val cardW = (available - gap * (columns - 1)) / columns
        val cardH = cardW + (if (withTitle) 38.0F else 0.0F)
        val rows = (playlists.size + columns - 1) / columns
        for (i in playlists.indices) {
            val playlist = playlists[i]
            val column = i % columns
            val row = i / columns
            val x = CT_X + 8.0F + column * (cardW + gap)
            val cy = y + row * (cardH + gap)
            if (inside(x, cy, cardW, cardH)) {
                renderer.rounded(x - 2.0F, cy - 2.0F, cardW + 4.0F, cardH + 4.0F, 18.0F, Material.withAlpha(current.onSurface, 0.06F))
            }
            renderer.rounded(x, cy, cardW, cardH, 16.0F, current.surfaceContainerHigh)
            val cover = CoverCache.get(playlist.cover(220), texRadius(220, cardW - 10.0F, 12.0F))
            if (cover != null) {
                renderer.imageRounded(cover, x + 5.0F, cy + 5.0F, cardW - 10.0F, cardW - 10.0F, 12.0F, -1)
            } else {
                renderer.rounded(x + 5.0F, cy + 5.0F, cardW - 10.0F, cardW - 10.0F, 12.0F, Material.withAlpha(current.primaryContainer, 0.55F))
                renderer.icon(I_MUSIC, x + cardW / 2.0F, cy + cardW / 2.0F, 22.0F, Material.withAlpha(current.onPrimaryContainer, 0.8F))
            }
            renderer.text(CARD_TITLE, renderer.truncate(CARD_TITLE, playlist.name, cardW - 16.0F), x + 8.0F, cy + cardW + 2.0F, current.onSurface, UI.ALIGN_LEFT)
            renderer.text(MusicTypeface.CAPTION, playlist.trackCount.toString() + " 首", x + 8.0F, cy + cardW + 20.0F, Material.withAlpha(current.onSurfaceVariant, 0.9F), UI.ALIGN_LEFT)
            val id = playlist.id
            val name = playlist.name
            hit(x, cy, cardW, cardH) {
                manager.openPlaylist(id, name)
                playlistOpen = true
                lyricOpen = false
                scroll.set(0.0F)
            }
        }
        return y + rows * (cardH + gap)
    }

    private fun drawSearchPage(renderer: UI, current: Scheme, startY: Float): Float {
        var y = startY
        val query = manager.searchQuery
        if (query.isEmpty()) {
            renderer.text(MusicTypeface.BODY, "输入关键字搜索网易云音乐", CT_X + 8.0F, y + 16.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
            return y + 40.0F
        }
        val results = manager.searchResults
        if (manager.searching() && results.isEmpty()) {
            renderer.text(MusicTypeface.BODY, "正在搜索…", CT_X + 8.0F, y + 16.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
            return y + 40.0F
        }
        if (results.isEmpty()) {
            val message = manager.searchError() ?: "没有找到结果"
            renderer.text(MusicTypeface.BODY, message, CT_X + 8.0F, y + 16.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
            return y + 40.0F
        }
        renderer.text(
            MusicTypeface.LABEL, "找到 " + results.size + " 首  ·  " + query,
            CT_X + 8.0F, y + 12.0F, Material.withAlpha(current.onSurfaceVariant, 0.9F), UI.ALIGN_LEFT,
        )
        y += 28.0F
        return songList(renderer, current, results, y)
    }

    private fun drawMine(renderer: UI, current: Scheme, startY: Float): Float {
        var y = startY
        val profile = manager.profile
        if (profile == null) {
            val cardH = 132.0F
            renderer.rounded(CT_X + 8.0F, y, CT_W - 16.0F, cardH, 20.0F, current.surfaceContainerHigh)
            renderer.text(MusicTypeface.HEADLINE_LARGE, "登录网易云音乐", CT_X + 28.0F, y + 34.0F, current.onSurface, UI.ALIGN_LEFT)
            renderer.text(MusicTypeface.BODY, "扫码登录后可同步歌单、查看每日推荐", CT_X + 28.0F, y + 62.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
            button(renderer, current, "扫码登录", CT_X + 28.0F, y + 86.0F, 132.0F, 36.0F, true) {
                qrOpen = true
                manager.startQrLogin()
            }
            y += cardH + 12.0F
            if (manager.myPlaylists.isNotEmpty()) {
                y = section(renderer, current, "我的歌单", y)
                y = grid(renderer, current, manager.myPlaylists, y, 5, true)
            }
            return y
        }

        renderer.rounded(CT_X + 8.0F, y, CT_W - 16.0F, 96.0F, 20.0F, current.surfaceContainerHigh)
        val avatar = CoverCache.get(profile.avatar(160), texRadius(160, 56.0F, 28.0F))
        if (avatar != null) {
            renderer.imageRounded(avatar, CT_X + 24.0F, y + 20.0F, 56.0F, 56.0F, 28.0F, -1)
        } else {
            renderer.circle(CT_X + 52.0F, y + 48.0F, 28.0F, Material.withAlpha(current.primaryContainer, 0.8F))
            renderer.icon(I_PERSON, CT_X + 52.0F, y + 48.0F, 22.0F, current.onPrimaryContainer)
        }
        renderer.text(MusicTypeface.HEADLINE_LARGE, renderer.truncate(MusicTypeface.HEADLINE_LARGE, profile.nickname, 320.0F), CT_X + 96.0F, y + 38.0F, current.onSurface, UI.ALIGN_LEFT)
        renderer.text(
            MusicTypeface.BODY, if (profile.vipType > 0) "VIP 会员" else "普通用户", CT_X + 96.0F, y + 62.0F,
            if (profile.vipType > 0) Material.withAlpha(0xFFF5C542.toInt(), 1.0F) else current.onSurfaceVariant,
            UI.ALIGN_LEFT,
        )
        button(renderer, current, "退出登录", CT_X + CT_W - 130.0F, y + 30.0F, 106.0F, 36.0F, false) {
            manager.logout()
        }
        y += 108.0F
        y = section(renderer, current, "我的歌单", y)
        val mine = manager.myPlaylists
        if (mine.isEmpty()) {
            renderer.text(
                MusicTypeface.BODY, if (manager.myPlaylistsLoading()) "正在加载…" else "暂无歌单",
                CT_X + 8.0F, y + 12.0F, current.onSurfaceVariant, UI.ALIGN_LEFT,
            )
            return y + 40.0F
        }
        return grid(renderer, current, mine, y, 5, true)
    }

    private fun drawSettings(renderer: UI, current: Scheme, startY: Float): Float {
        var y = startY
        y = settingRowHeader(renderer, current, "播放", y)
        y = sliderRow(renderer, current, "音量", (manager.volume() * 100.0F).roundToInt().toString() + "%", y, manager.volume(), 0.0F, 1.0F) {
            manager.setVolume(it)
        }
        y = chipRow(
            renderer, current, "音质", arrayOf("标准", "较高", "极高", "无损"),
            arrayOf("standard", "higher", "exhigh", "lossless"), arrayOf(manager.streamLevel()), y,
        ) { manager.setStreamLevel(it) }
        y = settingRowHeader(renderer, current, "外观", y)
        y = switchRow(renderer, current, "封面取色", "整个界面按专辑封面重新配色", y, manager.coverTint()) { manager.setCoverTint(it) }
        y = sliderRow(renderer, current, "歌词字号", String.format("%.0f%%", manager.lyricScale() * 100.0F), y, (manager.lyricScale() - 0.8F) / 0.6F, 0.0F, 1.0F) {
            manager.setLyricScale(0.8F + it * 0.6F)
        }
        y = switchRow(renderer, current, "歌词动效", "逐字扫光、行滚动与呼吸放大", y, manager.lyricAnimation()) { manager.setLyricAnimation(it) }
        y = settingRowHeader(renderer, current, "账号", y)
        val logged = manager.profile != null
        button(renderer, current, if (logged) "退出登录" else "扫码登录", CT_X + 8.0F, y + 6.0F, 132.0F, 38.0F, !logged) {
            if (manager.profile != null) {
                manager.logout()
            } else {
                qrOpen = true
                manager.startQrLogin()
            }
        }
        renderer.text(
            MusicTypeface.BODY,
            if (logged) "已登录：" + manager.profile!!.nickname else "未登录，仅能播放试听/免费曲目",
            CT_X + 156.0F, y + 25.0F, current.onSurfaceVariant, UI.ALIGN_LEFT,
        )
        y += 56.0F
        renderer.text(
            MusicTypeface.CAPTION, "音频输出：Java Sound（独立于游戏 OpenAL 混音）",
            CT_X + 8.0F, y + 10.0F, Material.withAlpha(current.onSurfaceVariant, 0.7F), UI.ALIGN_LEFT,
        )
        return y + 28.0F
    }

    private fun settingRowHeader(renderer: UI, current: Scheme, title: String, y: Float): Float {
        renderer.text(MusicTypeface.LABEL, title, CT_X + 8.0F, y + 12.0F, Material.withAlpha(current.primary, 0.95F), UI.ALIGN_LEFT)
        return y + 30.0F
    }

    private fun drawPlaylist(renderer: UI, current: Scheme) {
        renderer.scissorPush(CT_X, CT_Y, CT_W, CT_H)
        var y = CT_Y + 6.0F
        renderer.circle(CT_X + 24.0F, y + 14.0F, 16.0F, Material.withAlpha(current.onSurface, 0.08F))
        renderer.icon(I_BACK, CT_X + 24.0F, y + 14.0F, 16.0F, current.onSurface)
        hit(CT_X + 4.0F, y - 2.0F, 40.0F, 32.0F) {
            playlistOpen = false
            scroll.set(0.0F)
        }
        renderer.text(
            MusicTypeface.LABEL, if (manager.playlistLoading()) "加载中…" else manager.playlistTracks.size.toString() + " 首",
            CT_X + 48.0F, y + 14.0F, current.onSurfaceVariant, UI.ALIGN_LEFT,
        )
        y += 38.0F
        // The track list scrolls under a fixed header, so it gets its own clip: without it the first
        // row slid up through the back chip and the count.
        renderer.scissorPush(CT_X, y, CT_W, CT_Y + CT_H - y)
        val start = y - scroll.value()
        val end = songList(renderer, current, manager.playlistTracks, start)
        renderer.scissorPop()
        contentHeight = end - start + 12.0F
        contentScrollable = contentHeight > (CT_Y + CT_H - y)
        renderer.scissorPop()
    }

    private fun songList(renderer: UI, current: Scheme, songs: List<Song>, startY: Float): Float {
        var y = startY
        if (songs.isEmpty()) {
            renderer.text(
                MusicTypeface.BODY, if (manager.playlistLoading()) "正在加载曲目…" else "这里还没有歌曲",
                CT_X + 8.0F, y + 16.0F, current.onSurfaceVariant, UI.ALIGN_LEFT,
            )
            return y + 44.0F
        }
        val playing = manager.current
        for (i in songs.indices) {
            val song = songs[i]
            val rowY = y + i * ROW_PITCH
            // Cull rows outside the list viewport. They were still drawn (and, worse, still asked
            // CoverCache for artwork) for the whole playlist, so opening a 70-track playlist
            // requested 70+ covers in one frame and thrashed the texture cache.
            if (rowY + ROW_PITCH < CT_Y - 8.0F || rowY > CT_Y + CT_H + 8.0F) continue
            val isCurrent = playing != null && playing.id == song.id && manager.isPlaying()
            val hovered = inside(CT_X + 8.0F, rowY, CT_W - 16.0F, ROW_PITCH - 6.0F)
            if (hovered || isCurrent) {
                renderer.rounded(
                    CT_X + 8.0F, rowY, CT_W - 16.0F, ROW_PITCH - 6.0F, 14.0F,
                    Material.withAlpha(
                        if (isCurrent) current.primary else current.onSurface,
                        if (isCurrent) 0.16F else 0.06F,
                    ),
                )
            }
            val cover = CoverCache.get(song.cover(120), texRadius(120, 44.0F, 10.0F))
            val coverX = CT_X + 20.0F
            val coverY = rowY + 6.0F
            if (cover != null) {
                renderer.rounded(coverX, coverY, 44.0F, 44.0F, 10.0F, current.surfaceContainerHighest)
                renderer.imageRounded(cover, coverX, coverY, 44.0F, 44.0F, 10.0F, -1)
            } else {
                renderer.rounded(coverX, coverY, 44.0F, 44.0F, 10.0F, Material.withAlpha(current.primaryContainer, 0.5F))
                renderer.icon(I_MUSIC, coverX + 22.0F, coverY + 22.0F, 18.0F, Material.withAlpha(current.onPrimaryContainer, 0.85F))
            }
            val titleColor = if (isCurrent) current.primary else current.onSurface
            renderer.text(ROW, renderer.truncate(ROW, song.name, CT_W - 220.0F), coverX + 60.0F, rowY + 20.0F, titleColor, UI.ALIGN_LEFT)
            renderer.text(
                ROW_SUB, renderer.truncate(ROW_SUB, song.artist + "  ·  " + song.album, CT_W - 220.0F),
                coverX + 60.0F, rowY + 38.0F, current.onSurfaceVariant, UI.ALIGN_LEFT,
            )
            renderer.text(
                MusicTypeface.CAPTION, duration(song.durationMs), CT_X + CT_W - 24.0F, rowY + 29.0F,
                Material.withAlpha(current.onSurfaceVariant, 0.85F), UI.ALIGN_RIGHT,
            )
            hit(CT_X + 8.0F, rowY, CT_W - 16.0F, ROW_PITCH - 6.0F) {
                manager.play(song, songs)
                lyricPanel.reset()
            }
        }
        y += songs.size * ROW_PITCH
        return y
    }

    // ------------------------------------------------------------ mini player

    private fun drawMiniBar(renderer: UI, current: Scheme) {
        val px = MINI_X + 4.0F
        val py = MINI_Y + 4.0F
        val pw = MINI_W - 8.0F
        val ph = MINI_H - 8.0F
        renderer.shadow(px, py, pw, ph, 18.0F, 2)
        renderer.rounded(px, py, pw, ph, 18.0F, Material.withAlpha(current.surfaceContainerHigh, 0.97F))
        if (Theme.frost() && Blur.ready()) renderer.frost(px, py, pw, ph, 18.0F, current.surfaceContainerHigh, 0.82F)

        val song = manager.current
        val duration = manager.durationMs()
        var progress = if (duration > 0L) min(1.0F, manager.positionMs() / duration.toFloat()) else 0.0F
        if (progressPreview >= 0.0F) progress = progressPreview

        val trackX = px + 14.0F
        val trackW = pw - 28.0F - 104.0F
        val trackY = py + 9.0F
        if (song != null) {
            val style = WavyProgressIndicator.Style(
                40.0F, 0.42F, 28.0F, current.primary,
                Material.withAlpha(current.secondaryContainer, 0.9F),
            )
            WavyProgressIndicator.drawLinear(renderer, trackX, trackY - 4.0F, trackW, 9.0F, progress, style)
            hits.add(ProgressHit(trackX, trackY - 12.0F, trackW, 26.0F, trackX, trackW))

            val shownPosition = if (progressPreview >= 0.0F && duration > 0L) (duration * progressPreview).toLong() else manager.positionMs()
            val time = clock(shownPosition) + " / " + clock(duration)
            renderer.text(MusicTypeface.CAPTION, time, px + pw - 14.0F, trackY, Material.withAlpha(current.onSurfaceVariant, 0.95F), UI.ALIGN_RIGHT)
        }

        val coverSize = 52.0F
        val coverX = px + 14.0F
        val coverY = py + 18.0F
        renderer.rounded(coverX, coverY, coverSize, coverSize, 12.0F, current.surfaceContainerHighest)
        if (song != null) {
            val cover = CoverCache.get(song.cover(160), texRadius(160, coverSize, 12.0F))
            if (cover != null) {
                renderer.imageRounded(cover, coverX, coverY, coverSize, coverSize, 12.0F, -1)
            } else {
                renderer.icon(I_MUSIC, coverX + coverSize / 2.0F, coverY + coverSize / 2.0F, 20.0F, Material.withAlpha(current.onSurfaceVariant, 0.8F))
            }
        }
        hit(coverX, coverY, coverSize, coverSize) {
            if (manager.current != null) {
                lyricOpen = !lyricOpen
                lyricPanel.reset()
            }
        }

        val textX = coverX + coverSize + 14.0F
        val textW = 240.0F
        val title = song?.name ?: "未在播放"
        val artist = song?.artist ?: "网易云音乐"
        renderer.text(ROW, renderer.truncate(ROW, title, textW), textX, py + 30.0F, if (song == null) current.onSurfaceVariant else current.onSurface, UI.ALIGN_LEFT)
        renderer.text(ROW_SUB, renderer.truncate(ROW_SUB, artist, textW), textX, py + 50.0F, Material.withAlpha(current.onSurfaceVariant, 0.9F), UI.ALIGN_LEFT)

        val centerX = px + pw / 2.0F
        val buttonY = py + ph / 2.0F
        transportButton(renderer, current, I_PREV, centerX - 84.0F, buttonY, 34.0F) {
            manager.previous()
            lyricPanel.reset()
        }
        val playing = manager.isPlaying()
        renderer.shadow(centerX - 28.0F, buttonY - 17.0F, 56.0F, 34.0F, 17.0F, 1)
        renderer.rounded(centerX - 28.0F, buttonY - 17.0F, 56.0F, 34.0F, 17.0F, current.primary)
        renderer.icon(if (playing) I_PAUSE else I_PLAY, centerX, buttonY, 20.0F, current.onPrimary)
        hit(centerX - 28.0F, buttonY - 17.0F, 56.0F, 34.0F) { manager.togglePlay() }
        transportButton(renderer, current, I_NEXT, centerX + 84.0F, buttonY, 34.0F) {
            manager.next()
            lyricPanel.reset()
        }

        var rightX = px + pw - 34.0F
        transportButton(renderer, current, I_LYRICS, rightX, buttonY, 30.0F) {
            if (manager.current != null) {
                lyricOpen = !lyricOpen
                lyricPanel.reset()
            }
        }
        rightX -= 42.0F
        val modeIcon = if (manager.repeat() == MusicManager.Repeat.ONE) I_REPEAT_ONE else I_REPEAT
        val modeColor = if (manager.repeat() == MusicManager.Repeat.OFF) Material.withAlpha(current.onSurfaceVariant, 0.75F) else current.primary
        renderer.icon(modeIcon, rightX, buttonY, 19.0F, if (manager.shuffle()) current.primary else modeColor)
        hit(rightX - 16.0F, buttonY - 16.0F, 32.0F, 32.0F) { manager.cycleRepeat() }
        rightX -= 42.0F
        renderer.icon(
            I_SHUFFLE, rightX, buttonY, 19.0F,
            if (manager.shuffle()) current.primary else Material.withAlpha(current.onSurfaceVariant, 0.75F),
        )
        hit(rightX - 16.0F, buttonY - 16.0F, 32.0F, 32.0F) { manager.toggleShuffle() }
        rightX -= 44.0F
        renderer.icon(
            if (manager.volume() <= 0.001F) I_VOLUME_OFF else I_VOLUME, rightX, buttonY, 19.0F,
            Material.withAlpha(current.onSurfaceVariant, 0.9F),
        )
        hits.add(VolumeHit(rightX - 16.0F, buttonY - 16.0F, 32.0F, 32.0F, px + pw - 150.0F))

        if (manager.isLoadingTrack() || manager.engine.isBuffering()) {
            renderer.text(
                MusicTypeface.CAPTION, if (manager.isLoadingTrack()) "缓冲中…" else "解码中…",
                textX, py + 68.0F, Material.withAlpha(current.primary, 0.9F), UI.ALIGN_LEFT,
            )
        } else {
            val error = manager.playbackError()
            if (error != null) {
                renderer.text(
                    MusicTypeface.CAPTION, renderer.truncate(MusicTypeface.CAPTION, error, textW + 40.0F),
                    textX, py + 68.0F, current.error, UI.ALIGN_LEFT,
                )
            }
        }
    }

    private fun transportButton(renderer: UI, current: Scheme, icon: String, x: Float, y: Float, size: Float, action: () -> Unit) {
        if (inside(x - size / 2.0F, y - size / 2.0F, size, size)) {
            renderer.circle(x, y, size / 2.0F + 2.0F, Material.withAlpha(current.onSurface, 0.08F))
        }
        renderer.icon(icon, x, y, size * 0.62F, current.onSurface)
        hit(x - size / 2.0F, y - size / 2.0F, size, size, action)
    }

    // ------------------------------------------------------------ QR overlay

    private fun drawQrOverlay(renderer: UI, current: Scheme) {
        renderer.rect(0.0F, 0.0F, W, H, Material.withAlpha(0x000000, 0.55F))
        val cardW = 340.0F
        val cardH = 420.0F
        val x = (W - cardW) / 2.0F
        val y = (H - cardH) / 2.0F
        renderer.shadow(x, y, cardW, cardH, 24.0F, 3)
        renderer.rounded(x, y, cardW, cardH, 24.0F, current.surfaceContainerHigh)
        renderer.text(MusicTypeface.HEADLINE_LARGE, "扫码登录网易云", W / 2.0F, y + 30.0F, current.onSurface, UI.ALIGN_CENTER)
        renderer.circle(x + cardW - 28.0F, y + 26.0F, 14.0F, Material.withAlpha(current.onSurface, 0.08F))
        renderer.icon(I_CLOSE, x + cardW - 28.0F, y + 26.0F, 15.0F, current.onSurface)
        hit(x + cardW - 42.0F, y + 12.0F, 28.0F, 28.0F) {
            qrOpen = false
            manager.cancelQrLogin()
        }

        val qrSize = 220.0F
        val qrX = W / 2.0F - qrSize / 2.0F
        val qrY = y + 70.0F
        renderer.rounded(qrX - 10.0F, qrY - 10.0F, qrSize + 20.0F, qrSize + 20.0F, 16.0F, 0xFFFFFFFF.toInt())
        val matrix = manager.qrMatrix
        if (matrix != null && matrix.isNotEmpty()) {
            val modules = matrix.size
            val cell = qrSize / modules
            renderer.push()
            renderer.scissorPush(qrX, qrY, qrSize, qrSize)
            for (row in 0 until modules) {
                for (col in 0 until modules) {
                    if (matrix[row][col]) {
                        renderer.rect(qrX + col * cell, qrY + row * cell, cell + 0.4F, cell + 0.4F, 0xFF111111.toInt())
                    }
                }
            }
            renderer.scissorPop()
            renderer.pop()
        } else {
            renderer.text(
                MusicTypeface.BODY, "二维码生成中…", W / 2.0F, qrY + qrSize / 2.0F,
                Material.withAlpha(current.onSurfaceVariant, 0.9F), UI.ALIGN_CENTER,
            )
        }

        val status: String
        var color = current.onSurfaceVariant
        when (manager.qrState) {
            MusicManager.QrState.WAITING -> status = "打开网易云音乐 App 扫描二维码"
            MusicManager.QrState.SCANNED -> {
                status = "已扫码，请在手机上确认登录"
                color = current.primary
            }
            MusicManager.QrState.SUCCESS -> {
                status = "登录成功"
                color = current.primary
            }
            MusicManager.QrState.EXPIRED -> {
                status = "二维码已过期，请刷新"
                color = current.error
            }
            MusicManager.QrState.ERROR -> {
                status = manager.qrError() ?: "登录失败"
                color = current.error
            }
            else -> status = "正在获取二维码…"
        }
        renderer.text(MusicTypeface.BODY, status, W / 2.0F, qrY + qrSize + 30.0F, color, UI.ALIGN_CENTER)
        renderer.text(
            MusicTypeface.CAPTION, "登录信息仅保存在 config/liquidbounce/music/",
            W / 2.0F, qrY + qrSize + 50.0F,
            Material.withAlpha(current.onSurfaceVariant, 0.7F), UI.ALIGN_CENTER,
        )

        if (manager.qrState == MusicManager.QrState.EXPIRED || manager.qrState == MusicManager.QrState.ERROR) {
            button(renderer, current, "刷新二维码", W / 2.0F - 60.0F, y + cardH - 52.0F, 120.0F, 36.0F, true) {
                manager.startQrLogin()
            }
        }
    }

    // ---------------------------------------------------------- small controls

    private fun button(renderer: UI, current: Scheme, label: String, x: Float, y: Float, w: Float, h: Float, filled: Boolean, action: () -> Unit) {
        val hovered = inside(x, y, w, h)
        val background = if (filled) {
            if (hovered) Material.mix(current.primary, 0xFFFFFFFF.toInt(), 0.10F) else current.primary
        } else {
            Material.withAlpha(current.onSurface, if (hovered) 0.10F else 0.04F)
        }
        renderer.rounded(x, y, w, h, h / 2.0F, background)
        renderer.text(MusicTypeface.LABEL_TIGHT, label, x + w / 2.0F, y + h / 2.0F, if (filled) current.onPrimary else current.onSurface, UI.ALIGN_CENTER)
        hit(x, y, w, h, action)
    }

    private fun switchRow(renderer: UI, current: Scheme, title: String, description: String, y: Float, value: Boolean, setter: (Boolean) -> Unit): Float {
        val height = 56.0F
        if (inside(CT_X + 8.0F, y, CT_W - 16.0F, height)) {
            renderer.rounded(CT_X + 8.0F, y, CT_W - 16.0F, height, 12.0F, Material.withAlpha(current.onSurface, 0.05F))
        }
        renderer.text(MusicTypeface.HEADLINE, title, CT_X + 20.0F, y + 20.0F, current.onSurface, UI.ALIGN_LEFT)
        renderer.text(MusicTypeface.BODY, description, CT_X + 20.0F, y + 40.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
        val trackW = 44.0F
        val trackH = 24.0F
        val trackX = CT_X + CT_W - 28.0F - trackW
        val trackY = y + height / 2.0F - trackH / 2.0F
        renderer.rounded(trackX, trackY, trackW, trackH, trackH / 2.0F, if (value) current.primary else current.surfaceContainerHighest)
        if (!value) renderer.outline(trackX, trackY, trackW, trackH, trackH / 2.0F, 1.5F, current.outline)
        val knob = if (value) trackW - trackH + 4.0F else 4.0F
        renderer.circle(trackX + knob + trackH / 2.0F - 7.0F, trackY + trackH / 2.0F, 8.0F, if (value) current.onPrimary else current.outline)
        hit(CT_X + 8.0F, y, CT_W - 16.0F, height) { setter(!value) }
        return y + height
    }

    private fun sliderRow(renderer: UI, current: Scheme, title: String, valueLabel: String, y: Float, value: Float, minValue: Float, maxValue: Float, setter: (Float) -> Unit): Float {
        val height = 52.0F
        renderer.text(MusicTypeface.HEADLINE, title, CT_X + 20.0F, y + 18.0F, current.onSurface, UI.ALIGN_LEFT)
        renderer.text(MusicTypeface.CAPTION, valueLabel, CT_X + CT_W - 28.0F, y + 18.0F, current.onSurfaceVariant, UI.ALIGN_RIGHT)
        val trackX = CT_X + 20.0F
        val trackW = CT_W - 56.0F
        val trackY = y + 36.0F
        renderer.rounded(trackX, trackY, trackW, 4.0F, 2.0F, current.surfaceContainerHighest)
        renderer.rounded(trackX, trackY, max(4.0F, trackW * value), 4.0F, 2.0F, current.primary)
        renderer.circle(trackX + trackW * value, trackY + 2.0F, 7.0F, current.primary)
        hits.add(SliderHit(trackX, y + 24.0F, trackW, 26.0F, minValue, maxValue, setter))
        return y + height
    }

    private fun chipRow(renderer: UI, current: Scheme, title: String, labels: Array<String>, values: Array<String>, selected: Array<String>, y: Float, setter: (String) -> Unit): Float {
        val height = 52.0F
        renderer.text(MusicTypeface.HEADLINE, title, CT_X + 20.0F, y + 18.0F, current.onSurface, UI.ALIGN_LEFT)
        var x = CT_X + 20.0F
        val chipY = y + 30.0F
        for (i in labels.indices) {
            val value = values[i]
            val chipW = MusicTypeface.LABEL.width(labels[i]) + 26.0F
            val active = selected.isNotEmpty() && value == selected[0]
            val hovered = inside(x, chipY, chipW, 26.0F)
            renderer.rounded(
                x, chipY, chipW, 26.0F, 13.0F,
                if (active) current.secondaryContainer else Material.withAlpha(current.onSurface, if (hovered) 0.10F else 0.04F),
            )
            if (!active) renderer.outline(x, chipY, chipW, 26.0F, 13.0F, 1.0F, current.outlineVariant)
            renderer.text(
                MusicTypeface.LABEL_TIGHT, labels[i], x + chipW / 2.0F, chipY + 13.0F,
                if (active) current.onSecondaryContainer else current.onSurfaceVariant, UI.ALIGN_CENTER,
            )
            hit(x, chipY, chipW, 26.0F) { setter(value) }
            x += chipW + 8.0F
        }
        return y + height + 8.0F
    }

    // ------------------------------------------------------------------ input

    private fun inside(x: Float, y: Float, w: Float, h: Float): Boolean =
        mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h

    private fun updateMouse(guiX: Int, guiY: Int) {
        mouseX = (guiX - drawX) / max(0.01F, drawScale)
        mouseY = (guiY - drawY) / max(0.01F, drawScale)
        lastMouseX = mouseX
        lastMouseY = mouseY
    }

    private fun toLocalX(guiX: Float): Float = guiX / scale - gx

    private fun toLocalY(guiY: Float): Float = guiY / scale - gy

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (!interactive()) return false
        updateMouse(click.x.toInt(), click.y.toInt())
        if (click.button() != InputConstants.MOUSE_BUTTON_LEFT) return false
        for (i in hits.indices.reversed()) {
            val hit = hits[i]
            if (!hit.contains(mouseX, mouseY)) continue
            when (hit) {
                is ProgressHit -> {
                    draggingProgress = true
                    progressPreview = hit.fraction(mouseX)
                }
                is VolumeHit -> {
                    draggingVolume = true
                    manager.setVolume(hit.fraction(mouseX))
                }
                is SliderHit -> {
                    draggingSlider = hit
                    hit.apply(mouseX)
                }
                else -> {
                    searchFocused = mouseX >= searchRectX && mouseX < searchRectX + searchRectW &&
                        mouseY >= searchRectY && mouseY < searchRectY + searchRectH
                    hit.action?.invoke()
                }
            }
            return true
        }
        if (lyricOpen && lyricPanel.mouseClicked(mouseX, mouseY)) return true
        searchFocused = false
        return false
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        updateMouse(click.x.toInt(), click.y.toInt())
        if (draggingProgress && progressPreview >= 0.0F) {
            val duration = manager.durationMs()
            if (duration > 0L) manager.seek((duration * progressPreview).toLong())
        }
        draggingProgress = false
        draggingVolume = false
        draggingSlider = null
        progressPreview = -1.0F
        return false
    }

    override fun mouseDragged(click: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        updateMouse(click.x.toInt(), click.y.toInt())
        if (draggingProgress) {
            for (hit in hits) {
                if (hit is ProgressHit) {
                    progressPreview = hit.fraction(mouseX)
                    break
                }
            }
        } else if (draggingVolume) {
            for (hit in hits) {
                if (hit is VolumeHit) {
                    manager.setVolume(hit.fraction(mouseX))
                    break
                }
            }
        } else {
            draggingSlider?.apply(mouseX)
        }
        return false
    }

    override fun mouseScrolled(mx: Double, my: Double, horizontal: Double, vertical: Double): Boolean {
        if (!interactive()) return false
        updateMouse(mx.toInt(), my.toInt())
        if (vertical == 0.0) return false
        if (lyricOpen) {
            // Wheel up scrolls the lyric view back towards the current line.
            if (vertical > 0.0 && mouseX > CT_X && mouseX < CT_X + CT_W) {
                lyricPanel.nudge(-1.0F)
            } else if (vertical < 0.0) {
                lyricPanel.nudge(1.0F)
            }
            return true
        }
        if (mouseX < CT_X || mouseX > CT_X + CT_W || mouseY < CT_Y || mouseY > CT_Y + CT_H) return false
        if (!contentScrollable) return false
        val max = max(0.0F, contentHeight - CT_H)
        val target = scroll.target() - vertical.toFloat() * 48.0F
        scroll.target(Material.clamp(target, 0.0F, max))
        return true
    }

    /**
     * In-window shortcuts.
     *
     * <p>26.3 runs on SDL: `KeyEvent.key` is an **SDL scancode** (`KEY_SPACE` = 44, `KEY_ESCAPE` = 41),
     * while `KeyEvent.keycode` is the SDL keycode (what the old GLFW numbers used to approximate).
     * The vanilla `InputWithModifiers` helpers are used where they exist, so nothing here depends on
     * either numbering by hand.</p>
     */
    override fun keyPressed(event: KeyEvent): Boolean {
        if (closing) return true
        if (event.isEscape()) {
            when {
                searchFocused -> {
                    searchFocused = false
                    return true
                }
                qrOpen -> {
                    qrOpen = false
                    manager.cancelQrLogin()
                    return true
                }
                lyricOpen -> {
                    lyricOpen = false
                    return true
                }
                playlistOpen -> {
                    playlistOpen = false
                    return true
                }
                else -> {
                    deferClose()
                    return true
                }
            }
        }
        if (!interactive()) return false
        if (searchFocused) {
            when {
                event.key == InputConstants.KEY_BACKSPACE -> {
                    if (searchText.isNotEmpty()) {
                        searchText = searchText.substring(0, searchText.length - 1)
                        searchTypedAt = System.currentTimeMillis()
                    }
                    return true
                }
                event.isConfirmation() -> {
                    manager.search(searchText)
                    page = Page.SEARCH
                    return true
                }
                event.isPaste() -> {
                    val clipboard = mc.keyboardHandler.clipboard
                    if (clipboard.isNotEmpty() && searchText.length < 48) {
                        searchText += clipboard.replace('\n', ' ').take(48 - searchText.length)
                        searchTypedAt = System.currentTimeMillis()
                    }
                    return true
                }
            }
        }
        return when {
            event.key == InputConstants.KEY_SPACE || event.key == InputConstants.KEY_END -> {
                manager.togglePlay()
                true
            }
            event.isUp() -> {
                manager.setVolume(min(1.0F, manager.volume() + 0.05F))
                true
            }
            event.isDown() -> {
                manager.setVolume(max(0.0F, manager.volume() - 0.05F))
                true
            }
            event.isLeft() -> {
                manager.previous()
                true
            }
            event.isRight() -> {
                manager.next()
                true
            }
            event.key == InputConstants.KEY_PAGEDOWN -> {
                lyricOpen = !lyricOpen && manager.current != null
                lyricPanel.reset()
                true
            }
            else -> false
        }
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (!interactive() || !searchFocused) return false
        val typed = event.codepoint
        if (typed < 32 || typed == 127) return false
        if (searchText.length >= 48) return true
        searchText += String(Character.toChars(typed))
        searchTypedAt = System.currentTimeMillis()
        page = Page.SEARCH
        return true
    }

    /**
     * IME input (Chinese, Japanese, Korean) never arrives as a character event on 26.3; it is offered
     * as a pre-edit string instead. Committing it appends the whole composition at once, which is what
     * the search field needs to accept CJK queries at all.
     */
    override fun preeditUpdated(event: PreeditEvent?): Boolean {
        if (!interactive() || !searchFocused) return false
        if (event == null) return false
        val text = event.fullText()
        if (text.isEmpty()) return false
        if (searchText.length >= 48) return true
        searchText += text
        searchTypedAt = System.currentTimeMillis()
        page = Page.SEARCH
        return true
    }

    override fun onClose() {
        if (deferClose()) {
            // The module's own close path keeps morphing; the screen is closed when it lands.
            return
        }
        super.onClose()
    }

    override fun removed() {
        Blur.request()
        manager.saveSettings()
    }

    // --------------------------------------------------------------- hit list

    private fun hit(x: Float, y: Float, w: Float, h: Float, action: () -> Unit) {
        hits.add(Hit(x, y, w, h, action))
    }

    internal open class Hit(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val action: (() -> Unit)?,
    ) {
        fun contains(mx: Float, my: Float): Boolean = mx >= x && mx < x + w && my >= y && my < y + h
    }

    private class ProgressHit(x: Float, y: Float, w: Float, h: Float, private val trackX: Float, private val trackW: Float) :
        Hit(x, y, w, h, null) {
        fun fraction(mouseX: Float): Float = Material.clamp((mouseX - trackX) / trackW, 0.0F, 1.0F)
    }

    private class VolumeHit(x: Float, y: Float, w: Float, h: Float, private val startX: Float) :
        Hit(x, y, w, h, null) {
        fun fraction(mouseX: Float): Float = Material.clamp((mouseX - startX) / 120.0F, 0.0F, 1.0F)
    }

    private class SliderHit(x: Float, y: Float, w: Float, h: Float, private val minValue: Float, private val maxValue: Float, private val setter: (Float) -> Unit) :
        Hit(x, y, w, h, null) {
        fun apply(mouseX: Float) {
            val t = Material.clamp((mouseX - x) / w, 0.0F, 1.0F)
            setter(minValue + (maxValue - minValue) * t)
        }
    }

    private enum class Page { HOME, SEARCH, MINE, SETTINGS }

    companion object {
        private const val W = 840.0F
        private const val H = 520.0F
        private const val RADIUS = 28.0F
        private const val RAIL_X = 8.0F
        private const val RAIL_Y = 8.0F
        private const val RAIL_W = 42.0F
        private const val CT_X = 70.0F
        private const val CT_Y = 56.0F
        private const val CT_W = W - 90.0F
        private const val CT_H = H - 140.0F
        private const val MINI_X = 58.0F
        private const val MINI_Y = H - 84.0F
        private const val MINI_W = W - 66.0F
        private const val MINI_H = 84.0F
        private const val ROW_PITCH = 64.0F

        private const val I_HOME = "\ue88a"
        private const val I_SEARCH = "\ue8b6"
        private const val I_PERSON = "\ue7fd"
        private const val I_SETTINGS = "\ue8b8"
        private const val I_PLAY = "\ue037"
        private const val I_PAUSE = "\ue034"
        private const val I_NEXT = "\ue044"
        private const val I_PREV = "\ue045"
        private const val I_REPEAT = "\ue040"
        private const val I_REPEAT_ONE = "\ue041"
        private const val I_SHUFFLE = "\ue043"
        private const val I_VOLUME = "\ue050"
        private const val I_VOLUME_OFF = "\ue04f"
        private const val I_CLOSE = "\ue5cd"
        private const val I_BACK = "\ue5c4"
        private const val I_REFRESH = "\ue5d5"
        private const val I_LYRICS = "\ue8d2"
        private const val I_MUSIC = "\ue405"
        private const val I_CHECK = "\ue5ca"

        private val DISPLAY: Typeface.Style get() = MusicTypeface.DISPLAY
        private val SEARCH: Typeface.Style get() = MusicTypeface.SEARCH
        private val ROW: Typeface.Style get() = MusicTypeface.ROW
        private val ROW_SUB: Typeface.Style get() = MusicTypeface.ROW_SUB
        private val CARD_TITLE: Typeface.Style get() = MusicTypeface.CARD_TITLE

        /**
         * The Tablist expands the island surface with `Ease.EMPHASIZED` over 420 ms and fades its
         * content in with a small upward slide; the player window opens the same way so both gestures
         * feel like one interaction model. The matching collapse is Tablist's 280 ms `STANDARD`.
         */
        private val OPEN_CURVE = Ease.EMPHASIZED
        private val CLOSE_CURVE = Ease.STANDARD
        private const val OPEN_MS = 420.0F
        private const val CLOSE_MS = 280.0F


        /** Corner radius in the cached texture's own pixels, for the baked rounded mask. */
        private fun texRadius(imageSize: Int, displaySize: Float, displayRadius: Float): Int =
            if (displaySize <= 0.0F) 0 else (imageSize * displayRadius / displaySize).roundToInt()

        private fun duration(ms: Long): String {
            if (ms <= 0L) return "--:--"
            val seconds = ms / 1000L
            return String.format("%d:%02d", seconds / 60L, seconds % 60L)
        }

        /** `m:ss` for the transport clock, with a zero state instead of dashes. */
        private fun clock(ms: Long): String {
            val seconds = max(0L, ms) / 1000L
            return String.format("%d:%02d", seconds / 60L, seconds % 60L)
        }
    }
}
