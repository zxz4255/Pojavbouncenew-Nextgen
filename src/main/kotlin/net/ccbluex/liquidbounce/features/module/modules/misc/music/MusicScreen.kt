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
    private var clippingContentHits = false
    private var contentHitTop = 0f
    private val open = Anim(0.0F)
    private val scrim = Anim(0.0F)
    private val scroll = Spring(0.0F)
    private val hover = Spring(0.0F)
    private val railMotion = Spring(0.0F)
    private val pageMotion = Anim(1.0F)
    private var lastAnimatedPage = Page.HOME
    private var backdrop: net.minecraft.client.gui.render.TextureSetup? = null
    private var frameContext: GuiGraphicsExtractor? = null
    private val surfaceMotion = Anim(1.0F)
    private var previousSurface = ModuleMusic.panelSurface()
    private var surface = previousSurface
    private val switchAnimations = HashMap<String, Anim>()
    private var viperExpanded = false
    private var queueDrawerOpen = false
    private var queueDrawerPage = 0
    private val queueDrawerMotion = Anim(0f)
    private var queueExpanded = false
    private var queueDrag: QueueDragHit? = null
    private var queueDragTarget = -1
    private var activeLocalPlaylist: String? = null
    private var pendingDeletePlaylist: String? = null
    private var actionMessage = ""
    private var actionMessageUntil = 0L

    private var page = Page.HOME
    private var scheme: Scheme? = null
    private var coverSeed = 0
    private var paletteKey = ""
    private var paletteWasLight = false
    private var paletteProtectContrast = false
    private var paletteFrom: Scheme? = null
    private var paletteTarget: Scheme? = null
    private val paletteMotion = Anim(1f)
    private val controlAnimations = HashMap<String, Anim>()
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
    private var contentViewportHeight = CT_H
    private var lastMouseX = 0.0F
    private var lastMouseY = 0.0F

    private val searchBuffer = SearchTextBuffer()
    private val searchText: String get() = searchBuffer.text
    private var searchFocused = false
    private var searchTypedAt = 0L

    private var draggingProgress = false
    private var progressPreview = -1.0F
    private var draggingVolume = false
    private var draggingSlider: SliderHit? = null

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
        if (value) {
            focusSearch(false)
            lyricPanel.reset()
        }
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
        MusicBackdrop.retryOnOpen()
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
        railMotion.update(delta)
        pageMotion.update(delta)
        if (page != lastAnimatedPage) {
            lastAnimatedPage = page
            pageMotion.set(0.0F)
            pageMotion.to(1.0F, 180.0F, Ease.STANDARD)
        }
        surfaceMotion.update(delta)
        paletteMotion.update(delta)
        queueDrawerMotion.update(delta)
        controlAnimations.values.forEach { it.update(delta) }
        if (surface != ModuleMusic.panelSurface()) {
            previousSurface = surface
            surface = ModuleMusic.panelSurface()
            surfaceMotion.set(0.0F)
            surfaceMotion.to(1.0F, 260.0F, Ease.STANDARD)
        }
        switchAnimations.values.forEach { it.update(delta) }
        MusicIsland.tick(delta)
        lyricPanel.tick(delta)
        tickSearch()
        tickScheme()
        scroll.update(delta)
        if (contentScrollable) {
            val max = max(0.0F, contentHeight - contentViewportHeight)
            if (scroll.target() > max) scroll.target(max)
            if (scroll.value() > max) scroll.set(max)
        } else if (scroll.value() != 0.0F) {
            scroll.set(0.0F)
        }
    }

    private fun tickSearch() {
        if (!searchFocused || searchBuffer.composition.isNotEmpty()) return
        if (System.currentTimeMillis() - searchTypedAt > 320L) {
            val query = searchText.trim()
            if (query != manager.searchQuery) {
                manager.search(query)
                page = Page.SEARCH
            }
        }
    }

    /** Smooth the complete role palette as the cover or surface style changes. */
    private fun tickScheme() {
        var seed = 0
        if (manager.coverTint()) manager.current?.let { seed = CoverCache.dominant(it.cover(300)) }
        if (seed == 0) seed = Theme.accent()
        coverSeed = seed
        val style = ModuleMusic.panelSurface()
        val key = "$seed:${manager.coverTint()}:${style.name}"
        if (paletteTarget == null || key != paletteKey) {
            paletteKey = key
            val isLight = style == ModuleMusic.PanelSurface.DAYLIGHT
            paletteProtectContrast = isLight || paletteWasLight
            paletteWasLight = isLight
            val next = when {
                style == ModuleMusic.PanelSurface.DAYLIGHT -> Scheme.light(seed)
                !manager.coverTint() -> Theme.scheme()
                else -> Scheme.of(seed, 0.35F)
            }
            paletteFrom = scheme ?: next
            paletteTarget = next
            paletteMotion.set(0f)
            paletteMotion.to(1f, 260f, Ease.STANDARD)
        }
        val start = paletteFrom
        val end = paletteTarget
        if (start != null && end != null) {
            scheme = if (paletteMotion.isDone()) end else Scheme.blend(start, end, paletteMotion.value(), paletteProtectContrast)
        }
    }

    // -------------------------------------------------------------- rendering

    override fun extractRenderState(ctx: GuiGraphicsExtractor, rawMouseX: Int, rawMouseY: Int, partialTick: Float) {
        // The render hook can only capture the world before queued GUI elements are drawn.
        backdrop = if (mc.level != null && (surface == ModuleMusic.PanelSurface.GLASS ||
                (previousSurface == ModuleMusic.PanelSurface.GLASS && !surfaceMotion.isDone()) ||
                ModuleMusic.panelSurface() == ModuleMusic.PanelSurface.GLASS)) MusicBackdrop.request() else null
        frameContext = ctx
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
            frameContext = null
            backdrop = null
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
        fun surfaceColor(style: ModuleMusic.PanelSurface): Int = when (style) {
            ModuleMusic.PanelSurface.MATERIAL -> current.surfaceContainer
            // Opaque fallback underneath the optional captured-world layer.
            ModuleMusic.PanelSurface.GLASS -> 0xff283443.toInt()
            ModuleMusic.PanelSurface.MIDNIGHT -> 0xff11151e.toInt()
            ModuleMusic.PanelSurface.AURORA -> 0xff27213b.toInt()
            ModuleMusic.PanelSurface.DAYLIGHT -> current.surfaceContainer
        }
        val surfaceColor = Material.mix(surfaceColor(previousSurface), surfaceColor(surface), surfaceMotion.value())
        val color = Material.mix(Theme.scheme().surfaceContainerHigh, surfaceColor, k)
        renderer.shadow(shellX, shellY, shellW, shellH, radius, (3.0F + k).roundToInt())
        renderer.rounded(shellX, shellY, shellW, shellH, radius, color)
        val glassMix = (if (previousSurface == ModuleMusic.PanelSurface.GLASS) 1f - surfaceMotion.value() else 0f) +
            (if (surface == ModuleMusic.PanelSurface.GLASS) surfaceMotion.value() else 0f)
        val tex = backdrop
        val ctx = frameContext
        if (tex != null && ctx != null && glassMix > 0.001f) {
            MusicBackdrop.draw(ctx, tex, shellX, shellY, shellW, shellH, radius, color, glassMix * k)
        }
        if (surface == ModuleMusic.PanelSurface.GLASS || surface == ModuleMusic.PanelSurface.AURORA) {
            renderer.outline(shellX, shellY, shellW, shellH, radius, max(0.5F, scale), 0x557ac8df)
        }
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
            focusSearch(false)
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
            queueDrag = null
            queueDragTarget = -1
            setQueueDrawer(false)
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
        if (queueDrawerOpen || queueDrawerMotion.value() > 0.001f) drawQueueDrawer(renderer, current)
        if (qrOpen) drawQrOverlay(renderer, current)
        renderer.scissorPop()
    }

    private fun drawRail(renderer: UI, current: Scheme) {
        if (ModuleMusic.panelLayout() == ModuleMusic.PanelLayout.MUONIUM) {
            drawMuoniumRail(renderer, current)
            return
        }
        if (ModuleMusic.panelLayout() == ModuleMusic.PanelLayout.WIDE) {
            drawWideRail(renderer, current)
            return
        }
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

    /** Wide sidebar is a work-in-progress adaptation, not the original Muonium NCMScreen. */
    private fun drawWideRail(renderer: UI, current: Scheme) {
        val pages = arrayOf(Page.HOME, Page.SEARCH, Page.MINE, Page.SETTINGS)
        val icons = arrayOf(I_HOME, I_SEARCH, I_PERSON, I_SETTINGS)
        val labels = arrayOf("推荐", "搜索", "我的音乐", "设置")
        renderer.rounded(8f, 8f, RAIL_W, H - 16f, 16f, Material.withAlpha(current.surfaceContainerLow, .65f))
        renderer.text(MusicTypeface.HEADLINE, "Music", 22f, 30f, current.onSurface, UI.ALIGN_LEFT)
        val selected = pages.indexOf(page).coerceAtLeast(0)
        val indicatorY = 60f + selected * 48f
        railMotion.target(indicatorY)
        if (!playlistOpen && !lyricOpen) {
            renderer.rounded(14f, railMotion.value(), RAIL_W - 12f, 36f, 12f,
                Material.withAlpha(current.secondaryContainer, .9f))
        }
        for (i in pages.indices) {
            val top = 60f + i * 48f
            val active = page == pages[i] && !playlistOpen && !lyricOpen
            if (!active && inside(14f, top, RAIL_W - 12f, 36f))
                renderer.rounded(14f, top, RAIL_W - 12f, 36f, 12f, Material.withAlpha(current.onSurface, .08f))
            renderer.icon(icons[i], 32f, top + 18f, 18f, if (active) current.onSecondaryContainer else current.onSurfaceVariant)
            renderer.text(MusicTypeface.LABEL, labels[i], 50f, top + 18f,
                if (active) current.onSecondaryContainer else current.onSurfaceVariant, UI.ALIGN_LEFT)
            hit(14f, top, RAIL_W - 12f, 36f) {
                page = pages[i]; playlistOpen = false; lyricOpen = false; qrOpen = false; scroll.set(0f)
            }
        }
        renderer.icon("\ue5cf", 32f, H - 29f, 19f, current.onSurfaceVariant)
        renderer.text(MusicTypeface.LABEL, "收起", 50f, H - 29f, current.onSurfaceVariant, UI.ALIGN_LEFT)
        hit(14f, H - 48f, RAIL_W - 12f, 36f) { mc.gui.setScreen(null) }
    }

    /** Independent Onyx adaptation of Muonium's source/sidebar structure, not its Skija screen. */
    private fun drawMuoniumRail(renderer: UI, current: Scheme) {
        renderer.rounded(8f, 8f, RAIL_W, H - 16f, 16f, Material.withAlpha(current.surfaceContainerLow, .82f))
        renderer.text(MusicTypeface.HEADLINE, "MUSIC", 22f, 28f, current.onSurface, UI.ALIGN_LEFT)
        renderer.text(MusicTypeface.CAPTION, "来源", 22f, 55f, current.onSurfaceVariant, UI.ALIGN_LEFT)
        val providers = arrayOf(MusicProvider.NETEASE, MusicProvider.KUGOU, MusicProvider.BILIBILI)
        val labels = arrayOf("网易云", "酷狗", "B站")
        for (i in providers.indices) {
            val x = 14f + i * 48f
            val chipWidth = 45f
            val selected = manager.provider == providers[i]
            renderer.rounded(x, 66f, chipWidth, 27f, 8f,
                if (selected) current.secondaryContainer else Material.withAlpha(current.onSurface, .06f))
            renderer.text(MusicTypeface.CAPTION, renderer.truncate(MusicTypeface.CAPTION, labels[i], chipWidth - 4f), x + chipWidth / 2f, 79.5f,
                if (selected) current.onSecondaryContainer else current.onSurfaceVariant, UI.ALIGN_CENTER)
            hit(x, 66f, chipWidth, 27f) {
                if (manager.provider != providers[i]) {
                    focusSearch(false)
                    manager.setProvider(providers[i])
                    playlistOpen = false; lyricOpen = false; qrOpen = false
                    if (searchText.isNotBlank()) manager.search(searchText)
                    scroll.set(0f)
                }
            }
        }
        renderer.text(MusicTypeface.CAPTION, "发现音乐", 22f, 115f, current.onSurfaceVariant, UI.ALIGN_LEFT)
        val pages = arrayOf(Page.HOME, Page.SEARCH, Page.MINE, Page.SETTINGS)
        val icons = arrayOf(I_HOME, I_SEARCH, I_PERSON, I_SETTINGS)
        val names = arrayOf("推荐", "搜索", "我的音乐", "设置")
        railMotion.target(128f + pages.indexOf(page).coerceAtLeast(0) * 39f)
        if (!playlistOpen && !lyricOpen) renderer.rounded(15f, railMotion.value(), RAIL_W - 14f, 33f, 9f,
            Material.withAlpha(current.secondaryContainer, .9f))
        for (i in pages.indices) {
            val y = 128f + i * 39f
            val active = page == pages[i] && !playlistOpen && !lyricOpen
            if (!active && inside(15f, y, RAIL_W - 14f, 33f))
                renderer.rounded(15f, y, RAIL_W - 14f, 33f, 9f, Material.withAlpha(current.onSurface, .07f))
            val color = if (active) current.onSecondaryContainer else current.onSurfaceVariant
            renderer.icon(icons[i], 32f, y + 16.5f, 17f, color)
            renderer.text(MusicTypeface.LABEL, names[i], 49f, y + 16.5f, color, UI.ALIGN_LEFT)
            hit(15f, y, RAIL_W - 14f, 33f) {
                page = pages[i]; playlistOpen = false; lyricOpen = false; qrOpen = false; scroll.set(0f)
                if (pages[i] == Page.SEARCH) focusSearch(true)
            }
        }
        renderer.text(MusicTypeface.CAPTION, "资料库", 22f, 305f, current.onSurfaceVariant, UI.ALIGN_LEFT)
        val quickLabels = arrayOf("最近播放", "本地收藏")
        for (i in quickLabels.indices) {
            val y = 316f + i * 36f
            if (inside(15f, y, RAIL_W - 14f, 32f))
                renderer.rounded(15f, y, RAIL_W - 14f, 32f, 9f, Material.withAlpha(current.onSurface, .07f))
            renderer.icon(if (i == 0) "\ue889" else I_FAVORITE_BORDER, 32f, y + 16f, 17f, current.onSurfaceVariant)
            renderer.text(MusicTypeface.LABEL, quickLabels[i], 49f, y + 16f, current.onSurfaceVariant, UI.ALIGN_LEFT)
            hit(15f, y, RAIL_W - 14f, 32f) {
                page = Page.MINE; lyricOpen = false; qrOpen = false
                manager.openLocalPlaylist(quickLabels[i], if (i == 0) manager.library.listeningHistory() else manager.library.favoriteSongs())
                playlistOpen = true; scroll.set(0f)
            }
        }
        renderer.icon("\ue03b", 32f, 406f, 17f, current.onSurfaceVariant)
        renderer.text(MusicTypeface.LABEL, "播放队列", 49f, 406f, current.onSurfaceVariant, UI.ALIGN_LEFT)
        hit(15f, 390f, RAIL_W - 14f, 32f) { setQueueDrawer(true) }
        renderer.icon("\ue5cf", 32f, H - 29f, 19f, current.onSurfaceVariant)
        renderer.text(MusicTypeface.LABEL, "收起", 49f, H - 29f, current.onSurfaceVariant, UI.ALIGN_LEFT)
        hit(15f, H - 48f, RAIL_W - 14f, 36f) { deferClose() }
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
            val composing = searchBuffer.composition
            val fullText = searchText + composing
            val availableWidth = max(0.0F, searchW - if (fullText.isEmpty()) 52.0F else 78.0F)
            var visibleStart = 0
            while (visibleStart < fullText.length && SEARCH.width(fullText.substring(visibleStart)) > availableWidth) {
                visibleStart = fullText.offsetByCodePoints(visibleStart, 1)
            }
            val shown = if (fullText.isEmpty()) "搜索歌曲、歌手" else fullText.substring(visibleStart)
            val textColor = if (fullText.isEmpty()) Material.withAlpha(current.onSurfaceVariant, 0.75F) else current.onSurface
            val textX = searchX + 38.0F
            renderer.text(SEARCH, renderer.truncate(SEARCH, shown, availableWidth), textX, searchY + 20.0F, textColor, UI.ALIGN_LEFT)
            if (focused) {
                // IME caretPosition is a UTF-16 offset in the preedit string, not a commit.
                val caretOffset = (searchText.length + searchBuffer.compositionCaret).coerceAtMost(fullText.length)
                val caretLocal = max(0, caretOffset - visibleStart)
                val caretX = textX + SEARCH.width(shown.take(caretLocal)) + 1.0F
                if (composing.isNotEmpty() || (System.currentTimeMillis() / 530L) % 2L == 0L) {
                    renderer.rect(caretX, searchY + 12.0F, 1.6F, 16.0F, current.primary)
                }
                // Candidate windows need a GUI-space rectangle, not the music window's local coordinates.
                val cx = (drawX + caretX * drawScale).roundToInt()
                val cy = (drawY + (searchY + 12.0F) * drawScale).roundToInt()
                val bottom = (drawY + (searchY + 28.0F) * drawScale).roundToInt()
                mc.textInputManager().setTextInputArea(cx, cy, cx + 1, max(cy + 1, bottom))
            }
            hits.add(SearchHit(searchX, searchY, searchW, 40.0F))
            if (fullText.isNotEmpty()) {
                val clearX = searchX + searchW - 22.0F
                renderer.icon(I_CLOSE, clearX, searchY + 20.0F, 16.0F, current.onSurfaceVariant)
                hit(clearX - 17.0F, searchY + 4.0F, 34.0F, 32.0F) {
                    searchBuffer.replace(""); manager.search(""); page = Page.SEARCH; scroll.set(0.0F)
                    focusSearch(true)
                }
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
            qrOpen = false
            manager.cancelQrLogin()
            scroll.set(0.0F)
        }
    }

    // ------------------------------------------------------------------ pages

    private fun drawContent(renderer: UI, current: Scheme) {
        renderer.scissorPush(CT_X, CT_Y, CT_W, CT_H)
        val start = CT_Y - scroll.value()
        var y = start
        clippingContentHits = true
        contentHitTop = CT_Y
        renderer.alphaPush(pageMotion.value())
        try {
            y = when (page) {
                Page.HOME -> drawHome(renderer, current, y)
                Page.SEARCH -> drawSearchPage(renderer, current, y)
                Page.MINE -> drawMine(renderer, current, y)
                Page.SETTINGS -> drawSettings(renderer, current, y)
            }
        } finally {
            clippingContentHits = false
            renderer.alphaPop()
        }
        contentHeight = y - start + 12.0F
        contentViewportHeight = CT_H
        contentScrollable = contentHeight > contentViewportHeight
        renderer.scissorPop()
    }

    private fun drawHome(renderer: UI, current: Scheme, startY: Float): Float {
        var y = startY
        renderer.text(DISPLAY, greeting(), CT_X + 8.0F, y + 18.0F, current.onSurface, UI.ALIGN_LEFT)
        y += 44.0F
        if (manager.provider == MusicProvider.BILIBILI) {
            y = section(renderer, current, "哔哩哔哩公开视频", y)
            renderer.text(MusicTypeface.BODY,
                renderer.truncate(MusicTypeface.BODY, "搜索 B 站公开视频；只下载 DASH 音频 .m4s，不下载视频。", CT_W - 16.0F),
                CT_X + 8.0F, y + 12.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
            renderer.text(MusicTypeface.CAPTION, "公开内容无需登录；歌词不加载。", CT_X + 8.0F, y + 34.0F,
                Material.withAlpha(current.onSurfaceVariant, 0.78F), UI.ALIGN_LEFT)
            return y + 52.0F
        }
        y = section(renderer, current, if (manager.provider == MusicProvider.KUGOU) "酷狗热门歌单" else "推荐歌单", y)
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
            hit(x, cy, cardW, cardH) {
                manager.openPlaylist(playlist)
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
        if (searchText.isBlank()) {
            val history = manager.library.searchHistory()
            renderer.text(MusicTypeface.LABEL, "搜索历史", CT_X + 8.0F, y + 16.0F, current.onSurface, UI.ALIGN_LEFT)
            if (history.isNotEmpty()) {
                button(renderer, current, "清空", CT_X + CT_W - 84.0F, y, 68.0F, 32.0F, false) { manager.library.clearSearches() }
            }
            y += 40.0F
            if (history.isEmpty()) {
                renderer.text(MusicTypeface.BODY, "暂无搜索历史，输入关键字搜索", CT_X + 8.0F, y + 16.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
                return y + 40.0F
            }
            for (term in history) {
                val rowY = y
                renderer.text(MusicTypeface.BODY, renderer.truncate(MusicTypeface.BODY, term, CT_W - 100.0F), CT_X + 16.0F, rowY + 18.0F, current.onSurface, UI.ALIGN_LEFT)
                hit(CT_X + 8.0F, rowY, CT_W - 60.0F, 36.0F) {
                    searchBuffer.replace(term)
                    page = Page.SEARCH
                    playlistOpen = false
                    lyricOpen = false
                    manager.library.rememberSearch(searchText)
                    manager.search(searchText)
                    scroll.set(0.0F)
                }
                renderer.icon(I_CLOSE, CT_X + CT_W - 30.0F, rowY + 18.0F, 16.0F, current.onSurfaceVariant)
                hit(CT_X + CT_W - 48.0F, rowY, 36.0F, 36.0F) { manager.library.removeSearch(term) }
                y += 38.0F
            }
            return y
        }
        // The query being edited can be newer than the current asynchronous search.
        if (query != searchText.trim()) {
            renderer.text(MusicTypeface.BODY, "等待搜索…", CT_X + 8.0F, y + 16.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
            return y + 40.0F
        }
        val mode = manager.searchKind
        if (manager.provider == MusicProvider.BILIBILI) {
            button(renderer, current, "视频", CT_X + 8.0F, y, 76.0F, 32.0F, mode == MusicManager.SearchKind.SONG) {
                if (mode != MusicManager.SearchKind.SONG) {
                    manager.search(searchText, MusicManager.SearchKind.SONG); scroll.set(0.0F)
                }
            }
            renderer.text(MusicTypeface.CAPTION,
                renderer.truncate(MusicTypeface.CAPTION, "公开搜索 · 仅音频 .m4s", CT_W - 112.0F),
                CT_X + 96.0F, y + 18.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
        } else {
            button(renderer, current, "单曲", CT_X + 8.0F, y, 76.0F, 32.0F, mode == MusicManager.SearchKind.SONG) {
                if (mode != MusicManager.SearchKind.SONG) {
                    manager.search(searchText, MusicManager.SearchKind.SONG); scroll.set(0.0F)
                }
            }
            button(renderer, current, "歌单", CT_X + 92.0F, y, 76.0F, 32.0F, mode == MusicManager.SearchKind.PLAYLIST) {
                if (mode != MusicManager.SearchKind.PLAYLIST) {
                    manager.search(searchText, MusicManager.SearchKind.PLAYLIST); scroll.set(0.0F)
                }
            }
        }
        y += 42.0F
        if (manager.searching()) {
            renderer.text(MusicTypeface.BODY, "正在搜索…", CT_X + 8.0F, y + 16.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
            return y + 40.0F
        }
        val error = manager.searchError()
        if (error != null) {
            renderer.text(MusicTypeface.BODY, renderer.truncate(MusicTypeface.BODY, "搜索失败：$error", CT_W - 100.0F),
                CT_X + 8.0F, y + 16.0F, current.error, UI.ALIGN_LEFT)
            button(renderer, current, "重试", CT_X + CT_W - 84.0F, y, 68.0F, 32.0F, false) { manager.search(searchText) }
            return y + 40.0F
        }
        if (mode == MusicManager.SearchKind.PLAYLIST) {
            val playlists = manager.playlistSearchResults
            if (playlists.isEmpty()) {
                renderer.text(MusicTypeface.BODY, "没有找到歌单", CT_X + 8.0F, y + 16.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
                return y + 40.0F
            }
            renderer.text(MusicTypeface.LABEL, "找到 ${playlists.size} 个歌单  ·  $query",
                CT_X + 8.0F, y + 12.0F, Material.withAlpha(current.onSurfaceVariant, 0.9F), UI.ALIGN_LEFT)
            y += 28.0F
            return grid(renderer, current, playlists, y, 5, true)
        }
        val results = manager.searchResults
        if (results.isEmpty()) {
            renderer.text(MusicTypeface.BODY, if (manager.provider == MusicProvider.BILIBILI) "没有找到公开视频" else "没有找到歌曲",
                CT_X + 8.0F, y + 16.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
            return y + 40.0F
        }
        renderer.text(MusicTypeface.LABEL,
            if (manager.provider == MusicProvider.BILIBILI) "找到 ${results.size} 个视频  ·  $query" else "找到 ${results.size} 首  ·  $query",
            CT_X + 8.0F, y + 12.0F, Material.withAlpha(current.onSurfaceVariant, 0.9F), UI.ALIGN_LEFT)
        y += 28.0F
        return songList(renderer, current, results, y)
    }

    private fun drawMine(renderer: UI, current: Scheme, startY: Float): Float {
        var y = startY
        val profile = manager.profile
        val library = manager.library
        val queue = manager.queueView()
        if (manager.provider == MusicProvider.KUGOU) {
            y = section(renderer, current, "酷狗账号与版本", y)
            renderer.text(MusicTypeface.CAPTION,
                if (profile == null) "未登录：选择对应 App 扫码" else
                    "当前：${if (KugouClient.conceptSession) "概念版" else "标准版"} · 切换版本需重新扫码",
                CT_X + 8f, y + 10f, current.onSurfaceVariant, UI.ALIGN_LEFT)
            y += 22f
            button(renderer, current, "标准版扫码", CT_X + 8f, y, 132f, 36f, false) { openKugouQr(false) }
            button(renderer, current, "概念版扫码", CT_X + 150f, y, 132f, 36f, true) { openKugouQr(true) }
            y += 48f
        }
        y = section(renderer, current, "播放队列", y)
        val queueButtonY = y
        button(renderer, current, (if (queueExpanded) "收起队列  " else "展开队列  ") + queue.songs.size,
            CT_X + 8.0F, queueButtonY, 180.0F, 36.0F, false) { queueExpanded = !queueExpanded }
        y += 46.0F
        if (queueExpanded) {
            renderer.text(MusicTypeface.CAPTION, "点击播放；拖动左侧或用箭头排序，×移除",
                CT_X + 8.0F, y + 10.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
            y += 24.0F
            y = drawQueue(renderer, current, queue, y)
        }
        y = section(renderer, current, "本地音乐库", y)
        val topY = y
        button(renderer, current, "最近播放  ${library.listeningHistory().size}", CT_X + 8.0F, topY, 150.0F, 36.0F, false) {
            manager.openLocalPlaylist("最近播放", library.listeningHistory()); playlistOpen = true; scroll.set(0.0F)
        }
        button(renderer, current, "本地收藏  ${library.favoriteSongs().size}", CT_X + 168.0F, topY, 150.0F, 36.0F, false) {
            manager.openLocalPlaylist("本地收藏", library.favoriteSongs()); playlistOpen = true; scroll.set(0.0F)
        }
        button(renderer, current, "新建本地歌单", CT_X + 328.0F, topY, 150.0F, 36.0F, false) {
            val name = library.createPlaylist(); activeLocalPlaylist = name
            manager.openLocalPlaylist(name, library.playlistSongs(name)); playlistOpen = true; scroll.set(0.0F)
        }
        y += 46.0F
        renderer.text(MusicTypeface.CAPTION, "♡ 收藏  ·  ＋ 添加或移除当前本地歌单（点击歌单可切换）", CT_X + 8.0F, y + 10.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
        y += 24.0F
        for (name in library.playlistNames()) {
            val rowY = y
            renderer.text(MusicTypeface.BODY, "$name  ·  ${library.playlistSongs(name).size} 首", CT_X + 16.0F, rowY + 18.0F, current.onSurface, UI.ALIGN_LEFT)
            hit(CT_X + 8.0F, rowY, CT_W - 76.0F, 36.0F) {
                activeLocalPlaylist = name; manager.openLocalPlaylist(name, library.playlistSongs(name)); playlistOpen = true; scroll.set(0.0F)
            }
            if (pendingDeletePlaylist == name) {
                renderer.text(MusicTypeface.CAPTION, "确认删除", CT_X + CT_W - 22.0F, rowY + 18.0F, current.error, UI.ALIGN_RIGHT)
            } else {
                renderer.icon(I_CLOSE, CT_X + CT_W - 28.0F, rowY + 18.0F, 16.0F, current.onSurfaceVariant)
            }
            hit(CT_X + CT_W - 86.0F, rowY, 76.0F, 36.0F) {
                if (pendingDeletePlaylist == name) {
                    library.deletePlaylist(name); if (activeLocalPlaylist == name) activeLocalPlaylist = null
                    pendingDeletePlaylist = null
                } else pendingDeletePlaylist = name
            }
            y += 38.0F
        }
        library.error?.let {
            renderer.text(MusicTypeface.CAPTION, renderer.truncate(MusicTypeface.CAPTION, it, CT_W - 16.0F), CT_X + 8.0F, y + 12.0F, current.error, UI.ALIGN_LEFT)
            y += 26.0F
        }
        y += 12.0F
        if (manager.provider == MusicProvider.BILIBILI) {
            y = section(renderer, current, "哔哩哔哩公开视频音源", y)
            renderer.text(MusicTypeface.BODY,
                renderer.truncate(MusicTypeface.BODY, "无需登录；仅获取并下载 DASH 音频 .m4s，不下载视频流，也不加载歌词。", CT_W - 16.0F),
                CT_X + 8.0F, y + 12.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
            return y + 40.0F
        }
        if (profile == null) {
            val cardH = 132.0F
            renderer.rounded(CT_X + 8.0F, y, CT_W - 16.0F, cardH, 20.0F, current.surfaceContainerHigh)
            renderer.text(MusicTypeface.HEADLINE_LARGE, "登录${manager.provider.label}音乐", CT_X + 28.0F, y + 34.0F, current.onSurface, UI.ALIGN_LEFT)
            renderer.text(MusicTypeface.BODY, if (manager.provider == MusicProvider.KUGOU) "酷狗登录；收藏和歌单暂保存在本地" else "扫码登录后可同步歌单、查看每日推荐", CT_X + 28.0F, y + 62.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
            button(renderer, current, if (manager.provider == MusicProvider.KUGOU) "酷狗标准版" else "扫码登录", CT_X + 28.0F, y + 86.0F, 132.0F, 36.0F, true) {
                if (manager.provider == MusicProvider.KUGOU) openKugouQr(false)
                else { manager.cancelQrLogin(); qrOpen = true; manager.startQrLogin(false) }
            }
            if (manager.provider == MusicProvider.KUGOU) {
                button(renderer, current, "酷狗概念版", CT_X + 172.0F, y + 86.0F, 132.0F, 36.0F, false) {
                    openKugouQr(true)
                }
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
            MusicTypeface.BODY, if (manager.provider == MusicProvider.KUGOU) (if (KugouClient.conceptSession) "酷狗概念版" else "酷狗标准版") + " · 权益以平台响应为准" else if (profile.vipType > 0) "VIP 会员" else "普通用户", CT_X + 96.0F, y + 62.0F,
            if (profile.vipType > 0) Material.withAlpha(0xFFF5C542.toInt(), 1.0F) else current.onSurfaceVariant,
            UI.ALIGN_LEFT,
        )
        button(renderer, current, "退出登录", CT_X + CT_W - 130.0F, y + 30.0F, 106.0F, 36.0F, false) {
            manager.logout()
        }
        y += 108.0F
        if (manager.provider == MusicProvider.KUGOU) {
            renderer.text(MusicTypeface.BODY, "酷狗云歌单与云收藏暂未接入；可使用上方本地歌单和收藏。", CT_X + 8.0F, y + 16.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
            return y + 48.0F
        }
        y = section(renderer, current, "网易云歌单（含我喜欢的音乐）", y)
        manager.cloudFavoriteError?.let {
            renderer.text(MusicTypeface.CAPTION, renderer.truncate(MusicTypeface.CAPTION, it, CT_W - 140.0F), CT_X + 8.0F, y + 16.0F, current.error, UI.ALIGN_LEFT)
            button(renderer, current, "重试加载", CT_X + CT_W - 112.0F, y, 96.0F, 32.0F, false) { manager.loadMyPlaylists() }
            y += 40.0F
        }
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

    private fun drawQueue(renderer: UI, current: Scheme, view: MusicManager.QueueView, startY: Float): Float {
        if (view.songs.isEmpty()) {
            renderer.text(MusicTypeface.BODY, "队列为空", CT_X + 8f, startY + 18f, current.onSurfaceVariant, UI.ALIGN_LEFT)
            return startY + 42f
        }
        for (i in view.songs.indices) {
            val y = startY + i * 42f
            if (y + 42f < CT_Y || y > CT_Y + CT_H) continue
            val active = i == view.cursor
            if (active || inside(CT_X + 8f, y, CT_W - 16f, 38f))
                renderer.rounded(CT_X + 8f, y, CT_W - 16f, 38f, 10f,
                    Material.withAlpha(if (active) current.primary else current.onSurface, if (active) .15f else .06f))
            val drag = queueDrag
            if (drag != null && drag.revision == view.revision && queueDragTarget == i)
                renderer.rect(CT_X + 8f, y, CT_W - 16f, 2f, current.primary)
            renderer.icon("\ue25d", CT_X + 22f, y + 19f, 16f, current.onSurfaceVariant)
            val clipTop = max(y + 3f, CT_Y)
            val clipBottom = min(y + 35f, CT_Y + CT_H)
            if (clipBottom > clipTop) hits.add(QueueDragHit(CT_X + 8f, clipTop, 28f,
                clipBottom - clipTop, i, view.revision, view.songs.size, startY))
            val song = view.songs[i]
            val label = "${i + 1}. ${song.name}  ·  ${song.artist}"
            renderer.text(MusicTypeface.BODY, renderer.truncate(MusicTypeface.BODY, label, CT_W - 190f),
                CT_X + 43f, y + 19f, if (active) current.primary else current.onSurface, UI.ALIGN_LEFT)
            hit(CT_X + 38f, y, CT_W - 174f, 38f) {
                if (manager.queuePlay(i, view.revision)) lyricPanel.reset() else notice("队列已变化，请重试")
            }
            val up = CT_X + CT_W - 108f
            val down = CT_X + CT_W - 72f
            val remove = CT_X + CT_W - 36f
            renderer.icon("\ue5d8", up, y + 19f, 18f, if (i > 0) current.onSurfaceVariant else Material.withAlpha(current.onSurfaceVariant, .3f))
            renderer.icon("\ue5db", down, y + 19f, 18f, if (i + 1 < view.songs.size) current.onSurfaceVariant else Material.withAlpha(current.onSurfaceVariant, .3f))
            renderer.icon(I_CLOSE, remove, y + 19f, 17f, if (active) Material.withAlpha(current.onSurfaceVariant, .3f) else current.onSurfaceVariant)
            hit(up - 16f, y + 3f, 32f, 32f) { if (i > 0 && !manager.queueMove(i, -1, view.revision)) notice("队列已变化，请重试") }
            hit(down - 16f, y + 3f, 32f, 32f) { if (i + 1 < view.songs.size && !manager.queueMove(i, 1, view.revision)) notice("队列已变化，请重试") }
            hit(remove - 16f, y + 3f, 32f, 32f) { if (!active && !manager.queueRemove(i, view.revision)) notice("队列已变化，请重试") }
        }
        return startY + view.songs.size * 42f
    }

    private fun setQueueDrawer(value: Boolean) {
        queueDrawerOpen = value
        if (value) { queueDrawerPage = 0; focusSearch(false) }
        queueDrawerMotion.to(if (value) 1f else 0f, 200f, Ease.STANDARD)
    }

    /** A paged queue drawer over the content area; drag sorting remains available in My Music. */
    private fun drawQueueDrawer(renderer: UI, current: Scheme) {
        val alpha = queueDrawerMotion.value().coerceIn(0f, 1f)
        if (alpha <= 0.001f) return
        val view = manager.queueView()
        val pageCount = max(1, (view.songs.size + 5) / 6)
        queueDrawerPage = queueDrawerPage.coerceIn(0, pageCount - 1)
        // Block all underlying controls while either opening or closing.
        hit(0f, 0f, W, H) { }
        renderer.alphaPush(alpha)
        renderer.rect(0f, 0f, W, H, Material.withAlpha(0xff000000.toInt(), .36f))
        val x = W - 366f
        val y = 62f
        val w = 340f
        val h = 360f
        renderer.shadow(x, y, w, h, 18f, 3)
        renderer.rounded(x, y, w, h, 18f, current.surfaceContainerHigh)
        renderer.text(MusicTypeface.HEADLINE, "播放队列  ${view.songs.size}", x + 18f, y + 25f, current.onSurface, UI.ALIGN_LEFT)
        renderer.icon(I_CLOSE, x + w - 24f, y + 25f, 18f, current.onSurfaceVariant)
        val enabled = queueDrawerOpen && alpha > .90f
        if (enabled) hit(x + w - 42f, y + 8f, 36f, 34f) { setQueueDrawer(false) }
        if (view.songs.isEmpty()) renderer.text(MusicTypeface.BODY, "队列为空", x + 18f, y + 75f, current.onSurfaceVariant, UI.ALIGN_LEFT)
        val from = queueDrawerPage * 6
        val endExclusive = min(view.songs.size, from + 6)
        for (i in from until endExclusive) {
            val top = y + 51f + (i - from) * 43f
            val active = i == view.cursor
            if (active || inside(x + 8f, top, w - 16f, 40f))
                renderer.rounded(x + 8f, top, w - 16f, 40f, 9f,
                    Material.withAlpha(if (active) current.primary else current.onSurface, if (active) .14f else .06f))
            val song = view.songs[i]
            renderer.text(MusicTypeface.LABEL, renderer.truncate(MusicTypeface.LABEL, song.name, 202f),
                x + 17f, top + 12f, if (active) current.primary else current.onSurface, UI.ALIGN_LEFT)
            renderer.text(MusicTypeface.CAPTION, renderer.truncate(MusicTypeface.CAPTION, song.artist, 202f),
                x + 17f, top + 29f, current.onSurfaceVariant, UI.ALIGN_LEFT)
            renderer.icon("\ue5d8", x + w - 92f, top + 20f, 16f, current.onSurfaceVariant)
            renderer.icon("\ue5db", x + w - 60f, top + 20f, 16f, current.onSurfaceVariant)
            renderer.icon(I_CLOSE, x + w - 28f, top + 20f, 16f,
                if (active) Material.withAlpha(current.onSurfaceVariant, .3f) else current.onSurfaceVariant)
            if (enabled) {
                hit(x + 8f, top, w - 124f, 40f) { if (manager.queuePlay(i, view.revision)) lyricPanel.reset() else notice("队列已变化") }
                hit(x + w - 108f, top + 4f, 32f, 32f) { if (i > 0 && !manager.queueMove(i, -1, view.revision)) notice("队列已变化") }
                hit(x + w - 76f, top + 4f, 32f, 32f) { if (i + 1 < view.songs.size && !manager.queueMove(i, 1, view.revision)) notice("队列已变化") }
                hit(x + w - 44f, top + 4f, 32f, 32f) { if (!active && !manager.queueRemove(i, view.revision)) notice("队列已变化") }
            }
        }
        renderer.text(MusicTypeface.CAPTION, "${queueDrawerPage + 1} / $pageCount", x + w / 2f, y + h - 24f, current.onSurfaceVariant, UI.ALIGN_CENTER)
        renderer.icon("\ue5cb", x + 26f, y + h - 24f, 18f, current.onSurfaceVariant)
        renderer.icon("\ue5cc", x + w - 26f, y + h - 24f, 18f, current.onSurfaceVariant)
        if (enabled) {
            hit(x + 8f, y + h - 40f, 36f, 32f) { queueDrawerPage = max(0, queueDrawerPage - 1) }
            hit(x + w - 44f, y + h - 40f, 36f, 32f) { queueDrawerPage = min(pageCount - 1, queueDrawerPage + 1) }
        }
        renderer.alphaPop()
    }

    private fun drawSettings(renderer: UI, current: Scheme, startY: Float): Float {
        var y = startY
        y = settingRowHeader(renderer, current, "音乐平台", y)
        y = chipRow(renderer, current, "当前平台", arrayOf("网易云", "酷狗", "哔哩哔哩"),
            arrayOf("NETEASE", "KUGOU", "BILIBILI"), arrayOf(manager.provider.name), y) { value ->
            val selected = MusicProvider.valueOf(value)
            if (manager.provider != selected) {
                manager.setProvider(selected)
                qrOpen = false; playlistOpen = false; lyricOpen = false
                if (searchText.isNotBlank()) manager.search(searchText)
                scroll.set(0.0F)
            }
        }
        if (manager.provider == MusicProvider.KUGOU) {
            y = settingRowHeader(renderer, current, "酷狗账号版本（切换需扫码）", y)
            button(renderer, current, "标准版扫码", CT_X + 8f, y + 4f, 132f, 36f, false) { openKugouQr(false) }
            button(renderer, current, "概念版扫码", CT_X + 150f, y + 4f, 132f, 36f, true) { openKugouQr(true) }
            renderer.text(MusicTypeface.CAPTION,
                if (manager.profile == null) "未登录" else if (KugouClient.conceptSession) "当前：概念版" else "当前：标准版",
                CT_X + 296f, y + 22f, current.onSurfaceVariant, UI.ALIGN_LEFT)
            y += 48f
        }
        y = settingRowHeader(renderer, current, "播放与故障恢复", y)
        button(renderer, current, "重试当前歌曲", CT_X + 8f, y + 4f, 132f, 36f, false) {
            manager.retryPlayback(); notice("已重新请求当前歌曲")
        }
        button(renderer, current, "清缓存并重试", CT_X + 150f, y + 4f, 150f, 36f, false) {
            manager.retryPlayback(true); notice("已清理音频缓存并重试")
        }
        y += 48f
        renderer.text(MusicTypeface.CAPTION, "音乐缓存 7 天未使用后自动清理，正在使用的文件保留",
            CT_X + 8f, y + 10f, current.onSurfaceVariant, UI.ALIGN_LEFT)
        y += 24f
        y = sliderRow(renderer, current, "音量", (manager.volume() * 100.0F).roundToInt().toString() + "%", y, manager.volume(), 0.0F, 1.0F) {
            manager.setVolume(it)
        }
        y = chipRow(
            renderer, current, "音质", arrayOf("标准", "较高", "极高", "无损"),
            arrayOf("standard", "higher", "exhigh", "lossless"), arrayOf(manager.streamLevel()), y,
        ) { manager.setStreamLevel(it) }
        if (manager.provider == MusicProvider.KUGOU) {
            renderer.text(MusicTypeface.CAPTION, "酷狗播放使用平台返回的标准音质；版权和会员限制由酷狗决定", CT_X + 20.0F, y + 12.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
            y += 28.0F
        } else if (manager.provider == MusicProvider.BILIBILI) {
            renderer.text(MusicTypeface.CAPTION, "B站只使用 DASH 音频 .m4s；视频流不下载，歌词不加载。", CT_X + 20.0F, y + 12.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
            y += 28.0F
        }
        y = switchRow(renderer, current, "Minecraft 独立音频通道", "Pojav/安卓固定使用；桌面可切换。游戏音效不会进入音乐解析器", y, manager.minecraftOutput()) {
            manager.setMinecraftOutput(it)
        }
        y = settingRowHeader(renderer, current, "音效", y)
        y = switchRow(renderer, current, "ViPER", "Muonium DSP；仅个人非商用，异常时旁路", y, manager.viperSettings().enabled) {
            manager.setViperSettings(manager.viperSettings().copy(enabled = it))
        }
        button(renderer, current, if (viperExpanded) "收起 ViPER 设置" else "展开 ViPER 设置",
            CT_X + 12f, y + 4f, 180f, 36f, false) { viperExpanded = !viperExpanded }
        renderer.text(MusicTypeface.CAPTION,
            renderer.truncate(MusicTypeface.CAPTION, "状态：${manager.viperStatus()}", CT_W - 220f),
            CT_X + 204f, y + 22f, current.onSurfaceVariant, UI.ALIGN_LEFT)
        y += 48f
        if (viperExpanded) y = drawViperSettings(renderer, current, y)
        y = settingRowHeader(renderer, current, "基础音调（独立于 ViPER）", y)
        y = switchRow(renderer, current, "三段音调", "独立基础均衡器，并非 ViPER", y, manager.toneSettings().enabled) {
            manager.setToneSettings(manager.toneSettings().copy(enabled = it))
        }
        val tone = manager.toneSettings()
        val tonePreset = if (!tone.enabled) "" else when {
            tone.low == 0f && tone.mid == 0f && tone.high == 0f -> "FLAT"
            tone.low == 5f && tone.mid == 0f && tone.high == 0f -> "BASS"
            tone.low == -2f && tone.mid == 3f && tone.high == 1f -> "VOICE"
            tone.low == 0f && tone.mid == 0f && tone.high == 4f -> "BRIGHT"
            else -> ""
        }
        y = chipRow(renderer, current, "预设", arrayOf("平坦", "低音", "人声", "明亮"),
            arrayOf("FLAT", "BASS", "VOICE", "BRIGHT"), arrayOf(tonePreset), y) { preset ->
            val old = manager.toneSettings()
            val next = when (preset) {
                "BASS" -> old.copy(enabled = true, low = 5f, mid = 0f, high = 0f)
                "VOICE" -> old.copy(enabled = true, low = -2f, mid = 3f, high = 1f)
                "BRIGHT" -> old.copy(enabled = true, low = 0f, mid = 0f, high = 4f)
                else -> old.copy(enabled = true, low = 0f, mid = 0f, high = 0f)
            }
            manager.setToneSettings(next)
        }
        y = sliderRow(renderer, current, "低频", String.format("%+.1f dB", tone.low), y, (tone.low + 9f) / 18f, 0f, 1f) {
            manager.setToneSettings(manager.toneSettings().copy(low = it * 18f - 9f))
        }
        y = sliderRow(renderer, current, "中频", String.format("%+.1f dB", tone.mid), y, (tone.mid + 9f) / 18f, 0f, 1f) {
            manager.setToneSettings(manager.toneSettings().copy(mid = it * 18f - 9f))
        }
        y = sliderRow(renderer, current, "高频", String.format("%+.1f dB", tone.high), y, (tone.high + 9f) / 18f, 0f, 1f) {
            manager.setToneSettings(manager.toneSettings().copy(high = it * 18f - 9f))
        }
        y = settingRowHeader(renderer, current, "外观", y)
        y = chipRow(renderer, current, "面板布局", arrayOf("NeoRaven", "宽侧栏", "Muonium 风格"),
            arrayOf("NEO", "WIDE", "MUONIUM"), arrayOf(ModuleMusic.panelLayout().name), y) {
            ModuleMusic.setPanelLayout(ModuleMusic.PanelLayout.valueOf(it))
        }
        y = chipRow(renderer, current, "界面材质（玻璃采样世界画面）", arrayOf("Material", "Glass", "Midnight", "Aurora", "Daylight"),
            arrayOf("MATERIAL", "GLASS", "MIDNIGHT", "AURORA", "DAYLIGHT"), arrayOf(ModuleMusic.panelSurface().name), y) {
            ModuleMusic.setPanelSurface(ModuleMusic.PanelSurface.valueOf(it))
        }
        y = switchRow(renderer, current, "封面取色", "整个界面按专辑封面重新配色", y, manager.coverTint()) { manager.setCoverTint(it) }
        y = sliderRow(renderer, current, "歌词字号（全局）", String.format("%.0f%%", manager.lyricScale() * 100.0F), y, (manager.lyricScale() - 0.6F) / 1.4F, 0.0F, 1.0F) {
            manager.setLyricScale(0.6F + it * 1.4F)
        }
        y = switchRow(renderer, current, "歌词动效", "逐字扫光、行滚动与呼吸放大", y, manager.lyricAnimation()) { manager.setLyricAnimation(it) }
        y = settingRowHeader(renderer, current, "账号", y)
        if (manager.provider == MusicProvider.BILIBILI) {
            renderer.text(MusicTypeface.BODY, "B站公开视频搜索和音频 .m4s 播放无需登录。",
                CT_X + 8.0F, y + 25.0F, current.onSurfaceVariant, UI.ALIGN_LEFT)
            y += 56.0F
        } else {
            val logged = manager.profile != null
            button(renderer, current, if (logged) "退出登录" else "扫码登录", CT_X + 8.0F, y + 6.0F, 132.0F, 38.0F, !logged) {
                if (manager.profile != null) {
                    manager.logout()
                } else if (manager.provider == MusicProvider.KUGOU) {
                    page = Page.MINE; scroll.set(0f); qrOpen = false
                } else {
                    qrOpen = true
                    manager.cancelQrLogin()
                    manager.startQrLogin()
                }
            }
            renderer.text(
                MusicTypeface.BODY,
                if (logged) "${manager.provider.label}会话：" + manager.profile!!.nickname else "未登录，仅能播放试听/免费曲目",
                CT_X + 156.0F, y + 25.0F, current.onSurfaceVariant, UI.ALIGN_LEFT,
            )
            y += 56.0F
        }
        renderer.text(
            MusicTypeface.CAPTION, "当前音频输出：${manager.outputDescription()}",
            CT_X + 8.0F, y + 10.0F, Material.withAlpha(current.onSurfaceVariant, 0.7F), UI.ALIGN_LEFT,
        )
        return y + 28.0F
    }

    private fun drawViperSettings(renderer: UI, current: Scheme, startY: Float): Float {
        var y = startY
        val v = manager.viperSettings()
        y = switchRow(renderer, current, "ViPER Bass", "低频增强", y, v.bass) {
            manager.setViperSettings(manager.viperSettings().copy(bass = it))
        }
        y = chipRow(renderer, current, "低频模式", arrayOf("自然", "纯净", "超低音"),
            arrayOf("0", "1", "2"), arrayOf(v.bassMode.toString()), y) {
            manager.setViperSettings(manager.viperSettings().copy(bassMode = it.toInt()))
        }
        y = sliderRow(renderer, current, "低频频率", "${v.bassFrequency} Hz", y,
            (v.bassFrequency - 15f) / 135f, 0f, 1f) {
            manager.setViperSettings(manager.viperSettings().copy(bassFrequency = (15f + it * 135f).roundToInt()))
        }
        y = sliderRow(renderer, current, "低频增益", String.format("%.1f", v.bassGain), y,
            (v.bassGain - 0.5f) / 9.5f, 0f, 1f) {
            manager.setViperSettings(manager.viperSettings().copy(bassGain = 0.5f + it * 9.5f))
        }
        y = switchRow(renderer, current, "ViPER Clarity", "清晰度增强", y, v.clarity) {
            manager.setViperSettings(manager.viperSettings().copy(clarity = it))
        }
        y = chipRow(renderer, current, "清晰度模式", arrayOf("自然", "Ozone", "XHiFi"),
            arrayOf("0", "1", "2"), arrayOf(v.clarityMode.toString()), y) {
            manager.setViperSettings(manager.viperSettings().copy(clarityMode = it.toInt()))
        }
        y = sliderRow(renderer, current, "清晰度增益", String.format("%.1f", v.clarityGain), y,
            v.clarityGain / 4.5f, 0f, 1f) {
            manager.setViperSettings(manager.viperSettings().copy(clarityGain = it * 4.5f))
        }
        y = switchRow(renderer, current, "ViPER 十段均衡器", "低于 44.1 kHz 自动旁路", y, v.eq) {
            manager.setViperSettings(manager.viperSettings().copy(eq = it))
        }
        y = chipRow(renderer, current, "均衡器预设", arrayOf("平坦", "低音", "人声", "明亮"),
            arrayOf("FLAT", "BASS", "VOICE", "BRIGHT"), emptyArray(), y) { preset ->
            val bands = when (preset) {
                "BASS" -> listOf(5f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)
                "VOICE" -> listOf(-2f, -1f, 0f, 1f, 2f, 3f, 3f, 2f, 0f, 0f)
                "BRIGHT" -> listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 4f, 4f)
                else -> List(10) { 0f }
            }
            manager.setViperSettings(manager.viperSettings().copy(eq = true, bands = bands))
        }
        val frequencies = arrayOf("31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz")
        for (i in frequencies.indices) {
            val db = v.bands[i]
            y = sliderRow(renderer, current, frequencies[i], String.format("%+.1f dB", db), y,
                (db + 12f) / 24f, 0f, 1f) { fraction ->
                val old = manager.viperSettings()
                val bands = old.bands.toMutableList()
                bands[i] = fraction * 24f - 12f
                manager.setViperSettings(old.copy(bands = bands))
            }
        }
        y = switchRow(renderer, current, "ViPER 混响", "房间混响", y, v.reverb) {
            manager.setViperSettings(manager.viperSettings().copy(reverb = it))
        }
        y = sliderRow(renderer, current, "混响强度", "${(v.reverbWet * 100f).roundToInt()}%", y,
            v.reverbWet, 0f, 1f) { manager.setViperSettings(manager.viperSettings().copy(reverbWet = it)) }
        y = switchRow(renderer, current, "ViPER Cure", "耳机交叉馈送", y, v.cure) {
            manager.setViperSettings(manager.viperSettings().copy(cure = it))
        }
        y = chipRow(renderer, current, "交叉馈送强度", arrayOf("轻", "中", "强"),
            arrayOf("0", "1", "2"), arrayOf(v.curePreset.toString()), y) {
            manager.setViperSettings(manager.viperSettings().copy(curePreset = it.toInt()))
        }
        y = switchRow(renderer, current, "电子管模拟", "ViPER Tube Simulator", y, v.tube) {
            manager.setViperSettings(manager.viperSettings().copy(tube = it))
        }
        y = settingRowHeader(renderer, current, "更多 ViPER 效果", y)
        y = switchRow(renderer, current, "自动增益", "固定最大增益 2 倍", y, v.agc) {
            manager.setViperSettings(manager.viperSettings().copy(agc = it))
        }
        y = sliderRow(renderer, current, "自动增益强度", String.format("%.1f倍率", v.agcStrength), y,
            (v.agcStrength - 0.5f) / 2.5f, 0f, 1f) {
            manager.setViperSettings(manager.viperSettings().copy(agcStrength = 0.5f + it * 2.5f))
        }
        y = switchRow(renderer, current, "LUFS 响度控制", "目标响度与增益上限", y, v.lufs) {
            manager.setViperSettings(manager.viperSettings().copy(lufs = it))
        }
        y = sliderRow(renderer, current, "LUFS 目标", String.format("%.1f LUFS", v.lufsTarget), y,
            (v.lufsTarget - -24f) / 16.0f, 0f, 1f) {
            manager.setViperSettings(manager.viperSettings().copy(lufsTarget = -24f + it * 16.0f))
        }
        y = switchRow(renderer, current, "频谱扩展", "参考频率超过奈奎斯特频率时旁路", y, v.spectrum) {
            manager.setViperSettings(manager.viperSettings().copy(spectrum = it))
        }
        y = sliderRow(renderer, current, "激励强度", String.format("%.1f", v.spectrumExciter), y,
            (v.spectrumExciter - 0f) / 6.0f, 0f, 1f) {
            manager.setViperSettings(manager.viperSettings().copy(spectrumExciter = 0f + it * 6.0f))
        }
        y = sliderRow(renderer, current, "频谱参考频率", "${v.spectrumFrequency} Hz", y,
            (v.spectrumFrequency - 2200f) / 6000f, 0f, 1f) {
            manager.setViperSettings(manager.viperSettings().copy(spectrumFrequency = (2200f + it * 6000f).roundToInt()))
        }
        y = switchRow(renderer, current, "场环绕", "Muonium Field Surround", y, v.field) {
            manager.setViperSettings(manager.viperSettings().copy(field = it))
        }
        y = sliderRow(renderer, current, "环绕宽度", String.format("%.1f", v.fieldWidth), y,
            (v.fieldWidth - 0f) / 8.0f, 0f, 1f) {
            manager.setViperSettings(manager.viperSettings().copy(fieldWidth = 0f + it * 8.0f))
        }
        y = switchRow(renderer, current, "差分环绕", "Muonium Differential Surround", y, v.diff) {
            manager.setViperSettings(manager.viperSettings().copy(diff = it))
        }
        y = sliderRow(renderer, current, "差分延迟", String.format("%.1f ms", v.diffDelay), y,
            (v.diffDelay - 1f) / 19.0f, 0f, 1f) {
            manager.setViperSettings(manager.viperSettings().copy(diffDelay = 1f + it * 19.0f))
        }
        y = switchRow(renderer, current, "立体声宽度", "三频段使用同一个宽度", y, v.stereo) {
            manager.setViperSettings(manager.viperSettings().copy(stereo = it))
        }
        y = sliderRow(renderer, current, "声场宽度", String.format("%.1f%", v.stereoWidth), y,
            (v.stereoWidth - 0f) / 200.0f, 0f, 1f) {
            manager.setViperSettings(manager.viperSettings().copy(stereoWidth = 0f + it * 200.0f))
        }
        y = switchRow(renderer, current, "动态系统", "动态低频与音场", y, v.dynamic) {
            manager.setViperSettings(manager.viperSettings().copy(dynamic = it))
        }
        y = sliderRow(renderer, current, "动态强度", String.format("%.1f", v.dynamicStrength), y,
            (v.dynamicStrength - 1f) / 7.0f, 0f, 1f) {
            manager.setViperSettings(manager.viperSettings().copy(dynamicStrength = 1f + it * 7.0f))
        }
        y = switchRow(renderer, current, "模拟音效 AnalogX", "模拟处理", y, v.analog) {
            manager.setViperSettings(manager.viperSettings().copy(analog = it))
        }
        y = chipRow(renderer, current, "模拟模式", arrayOf("模式 0", "模式 1"),
            arrayOf("0", "1"), arrayOf(v.analogMode.toString()), y) {
            manager.setViperSettings(manager.viperSettings().copy(analogMode = it.toInt()))
        }
        y = switchRow(renderer, current, "扬声器校正", "Speaker Correction", y, v.speaker) {
            manager.setViperSettings(manager.viperSettings().copy(speaker = it))
        }
        y = switchRow(renderer, current, "耳机环绕 VHE", "仅 44.1/48 kHz；可能增加延迟", y, v.headphone) {
            manager.setViperSettings(manager.viperSettings().copy(headphone = it))
        }
        y = sliderRow(renderer, current, "耳机环绕级别", v.headphoneQuality.toString(), y,
            v.headphoneQuality / 4f, 0f, 1f) {
            manager.setViperSettings(manager.viperSettings().copy(headphoneQuality = (it * 4f).roundToInt()))
        }
        y = switchRow(renderer, current, "JamesDSP 混响", "可与 ViPER 混响叠加，建议二选一", y, v.jdsp) {
            manager.setViperSettings(manager.viperSettings().copy(jdsp = it))
        }
        val jdspNames = com.muoniumplayer.core.jdsp.JdspReverb.PRESET_NAMES
        y = sliderRow(renderer, current, "JamesDSP 预设", jdspNames[v.jdspPreset], y,
            v.jdspPreset / 18f, 0f, 1f) {
            manager.setViperSettings(manager.viperSettings().copy(jdspPreset = (it * 18f).roundToInt()))
        }
        y = settingRowHeader(renderer, current, "ViPER 输出", y)
        y = sliderRow(renderer, current, "限幅阈值", String.format("%.2f", v.limiterThreshold), y,
            (v.limiterThreshold - 0.3f) / 0.7f, 0f, 1f) {
            manager.setViperSettings(manager.viperSettings().copy(limiterThreshold = 0.3f + it * 0.7f))
        }
        y = sliderRow(renderer, current, "左右声道平衡", String.format("%+.2f", v.channelPan), y,
            (v.channelPan + 1f) / 2f, 0f, 1f) {
            manager.setViperSettings(manager.viperSettings().copy(channelPan = it * 2f - 1f))
        }
        return y
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
            MusicTypeface.LABEL,
            renderer.truncate(MusicTypeface.LABEL, manager.playlistError() ?: if (manager.playlistLoading()) "加载中…" else manager.playlistTracks.size.toString() + " 首", CT_W - 70.0F),
            CT_X + 48.0F, y + 14.0F, if (manager.playlistError() == null) current.onSurfaceVariant else current.error, UI.ALIGN_LEFT,
        )
        y += 38.0F
        // The track list scrolls under a fixed header, so it gets its own clip: without it the first
        // row slid up through the back chip and the count.
        renderer.scissorPush(CT_X, y, CT_W, CT_Y + CT_H - y)
        val start = y - scroll.value()
        clippingContentHits = true
        contentHitTop = y
        val end = try {
            songList(renderer, current, manager.playlistTracks, start)
        } finally {
            clippingContentHits = false
        }
        renderer.scissorPop()
        contentHeight = end - start + 12.0F
        contentViewportHeight = max(0f, CT_Y + CT_H - y)
        contentScrollable = contentHeight > contentViewportHeight
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
            val isCurrent = playing != null && playing.sameTrack(song) && manager.isPlaying()
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
            renderer.text(ROW, renderer.truncate(ROW, song.name, CT_W - 260.0F), coverX + 60.0F, rowY + 20.0F, titleColor, UI.ALIGN_LEFT)
            renderer.text(
                ROW_SUB, renderer.truncate(ROW_SUB, song.artist + "  ·  " + song.album, CT_W - 260.0F),
                coverX + 60.0F, rowY + 38.0F, current.onSurfaceVariant, UI.ALIGN_LEFT,
            )
            renderer.text(
                MusicTypeface.CAPTION, duration(song.durationMs), CT_X + CT_W - 24.0F, rowY + 29.0F,
                Material.withAlpha(current.onSurfaceVariant, 0.85F), UI.ALIGN_RIGHT,
            )
            hit(CT_X + 8.0F, rowY, CT_W - 16.0F, ROW_PITCH - 6.0F) {
                if (page == Page.SEARCH && !playlistOpen) manager.library.rememberSearch(searchText)
                manager.play(song, songs)
                lyricPanel.reset()
            }
            val queueX = CT_X + CT_W - 133.0F
            renderer.icon("\ue03b", queueX, rowY + 29.0F, 18.0F, current.onSurfaceVariant)
            hit(queueX - 15.0F, rowY + 13.0F, 30.0F, 32.0F) {
                manager.queueNext(song)
                notice("已播放或安排下一首")
            }
            val heartX = CT_X + CT_W - 100.0F
            val addX = CT_X + CT_W - 67.0F
            val isLocalFavoriteView = playlistOpen && manager.playlistTitle == "本地收藏"
            val liked = if (isLocalFavoriteView) manager.library.isFavorite(song) else manager.favoriteSelected(song)
            renderer.icon(if (liked) I_FAVORITE else I_FAVORITE_BORDER, heartX, rowY + 29.0F, 18.0F,
                if (liked) current.primary else current.onSurfaceVariant)
            hit(heartX - 15.0F, rowY + 13.0F, 30.0F, 32.0F) {
                if (isLocalFavoriteView) { manager.library.toggleFavorite(song); manager.refreshLocalPlaylist(); notice("本地收藏已更新") }
                else {
                    manager.toggleFavorite(song)
                    notice(if (manager.provider == MusicProvider.KUGOU || song.provider != MusicProvider.NETEASE || manager.profile == null) "本地收藏已更新" else "正在请求网易云收藏")
                }
            }
            renderer.icon(I_ADD, addX, rowY + 29.0F, 18.0F, current.onSurfaceVariant)
            hit(addX - 15.0F, rowY + 13.0F, 30.0F, 32.0F) {
                val names = manager.library.playlistNames()
                val target = activeLocalPlaylist?.takeIf { it in names } ?: names.firstOrNull() ?: manager.library.createPlaylist()
                activeLocalPlaylist = target
                val wasAdded = manager.library.isInPlaylist(target, song)
                manager.library.togglePlaylistSong(target, song)
                manager.refreshLocalPlaylist()
                notice((if (wasAdded) "已从 " else "已加入 ") + target)
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

        // Keep the centred cover clear of the progress strip.
        val trackX = px + 80.0F
        val trackW = pw - 28.0F - 104.0F - 66.0F
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
        val coverY = py + (ph - coverSize) / 2.0F
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
        val textW = if (ModuleMusic.panelLayout() != ModuleMusic.PanelLayout.NEO)
            min(240.0F, max(40.0F, px + pw / 2.0F - 112.0F - textX)) else 240.0F
        val title = song?.name ?: "未在播放"
        val artist = song?.artist ?: "${manager.provider.label}音乐"
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
        if (song != null) {
            MusicSpectrumRenderer.draw(
                renderer, centerX + 108.0F, py + ph - 8.0F, 92.0F, 18.0F, current.primary,
            )
        } else {
            MusicSpectrumAnalyzer.suspend()
        }

        val favoriteError = manager.cloudFavoriteError ?: manager.library.error
        val playbackIssue = manager.playbackError()
        if (manager.isLoadingTrack() || manager.engine.isBuffering()) {
            val status = if (manager.isLoadingTrack()) manager.loadMessage()
                else if (manager.engine.bufferingForMs() > 15000L) "等待：${manager.engine.bufferingStage()}；请查看日志或重启"
                else "解码中…"
            renderer.text(MusicTypeface.CAPTION, renderer.truncate(MusicTypeface.CAPTION, status, textW + 40.0F),
                textX, py + 68.0F, Material.withAlpha(current.primary, 0.9F), UI.ALIGN_LEFT)
        } else if (playbackIssue != null) {
            renderer.text(MusicTypeface.CAPTION, renderer.truncate(MusicTypeface.CAPTION, playbackIssue, textW + 40.0F),
                textX, py + 68.0F, current.error, UI.ALIGN_LEFT)
        } else if (favoriteError != null) {
            renderer.text(MusicTypeface.CAPTION, renderer.truncate(MusicTypeface.CAPTION, favoriteError, textW + 40.0F),
                textX, py + 68.0F, current.error, UI.ALIGN_LEFT)
        } else if (System.currentTimeMillis() < actionMessageUntil) {
            renderer.text(MusicTypeface.CAPTION, renderer.truncate(MusicTypeface.CAPTION, actionMessage, textW + 40.0F),
                textX, py + 68.0F, current.primary, UI.ALIGN_LEFT)
        } else {
            manager.alternateSource()?.let { source ->
                renderer.text(MusicTypeface.CAPTION, "已尝试换源：$source", textX, py + 68.0F,
                    current.primary, UI.ALIGN_LEFT)
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

    private fun openKugouQr(concept: Boolean) {
        manager.cancelQrLogin()
        qrOpen = true
        manager.startQrLogin(concept)
    }

    // ------------------------------------------------------------ QR overlay

    private fun drawQrOverlay(renderer: UI, current: Scheme) {
        // Modal shield comes after the base controls and before the modal's own buttons.
        hit(0f, 0f, W, H) { }
        renderer.rect(0.0F, 0.0F, W, H, Material.withAlpha(0x000000, 0.55F))
        val cardW = 340.0F
        val cardH = 420.0F
        val x = (W - cardW) / 2.0F
        val y = (H - cardH) / 2.0F
        renderer.shadow(x, y, cardW, cardH, 24.0F, 3)
        renderer.rounded(x, y, cardW, cardH, 24.0F, current.surfaceContainerHigh)
        renderer.text(MusicTypeface.HEADLINE_LARGE, if (manager.provider == MusicProvider.KUGOU)
            (if (manager.qrConcept) "酷狗概念版扫码" else "酷狗标准版扫码") else "扫码登录${manager.provider.label}", W / 2.0F, y + 30.0F, current.onSurface, UI.ALIGN_CENTER)
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
            MusicManager.QrState.WAITING -> status = if (manager.qrConcept) "打开酷狗概念版 App 扫描二维码" else "打开${manager.provider.label}音乐 App 扫描二维码"
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
                manager.startQrLogin(manager.qrConcept)
            }
        }
    }

    // ---------------------------------------------------------- small controls

    private fun button(renderer: UI, current: Scheme, label: String, x: Float, y: Float, w: Float, h: Float, filled: Boolean, action: () -> Unit) {
        val hovered = inside(x, y, w, h)
        // Use stable horizontal coordinates (scrolling changes y every frame).
        if (controlAnimations.size > 256) controlAnimations.clear()
        val motion = controlAnimations.getOrPut("button:$label:$x") { Anim(0f) }
        motion.to(if (hovered) 1f else 0f, 150f, Ease.STANDARD)
        val t = motion.value()
        val background = if (filled) {
            Material.mix(current.primary, current.onPrimary, 0.10F * t)
        } else {
            Material.withAlpha(current.onSurface, 0.04F + 0.06F * t)
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
        // Match NeoRaven Widgets.toggle: 38x22, centered 6px -> 8.5px thumb.
        val trackW = 38.0F
        val trackH = 22.0F
        val trackX = CT_X + CT_W - 28.0F - trackW
        val trackY = y + (height - trackH) / 2.0F
        val animation = switchAnimations.getOrPut(title) { Anim(if (value) 1.0F else 0.0F) }
        animation.to(if (value) 1.0F else 0.0F, 220.0F, Ease.STANDARD)
        val t = animation.value().coerceIn(0.0F, 1.0F)
        renderer.rounded(trackX, trackY, trackW, trackH, trackH / 2.0F, Material.mix(current.surfaceContainerHighest, current.primary, t))
        if (inside(trackX, trackY, trackW, trackH)) {
            renderer.rounded(trackX, trackY, trackW, trackH, trackH / 2.0F,
                Material.withAlpha(Material.mix(current.onSurfaceVariant, current.primary, t), 0.08F))
        }
        if (t < 0.999F) renderer.outline(trackX, trackY, trackW, trackH, trackH / 2.0F, 1.5F, Material.withAlpha(current.outline, 1.0F - t))
        val centerX = trackX + trackH / 2.0F + (trackW - trackH) * t
        val radius = 6.0F + 2.5F * t
        renderer.circle(centerX, trackY + trackH / 2.0F, radius, Material.mix(current.outline, current.onPrimary, t))
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
        val progress = value.coerceIn(0.0F, 1.0F)
        val sliderMotion = controlAnimations.getOrPut("slider:$title") { Anim(progress) }
        sliderMotion.to(progress, 100f, Ease.STANDARD)
        val visual = sliderMotion.value().coerceIn(0f, 1f)
        val hoverMotion = controlAnimations.getOrPut("slider-hover:$title") { Anim(0f) }
        hoverMotion.to(if (inside(trackX, trackY - 12f, trackW, 28f)) 1f else 0f, 140f, Ease.STANDARD)
        renderer.rounded(trackX, trackY, trackW, 4.0F, 2.0F, current.surfaceContainerHighest)
        renderer.rounded(trackX, trackY, max(4.0F, trackW * visual), 4.0F, 2.0F, current.primary)
        renderer.circle(trackX + trackW * visual, trackY + 2.0F, 7.0F + hoverMotion.value() * 1.5F, current.primary)
        val hitTop = max(y + 24.0F, CT_Y)
        val hitBottom = min(y + 50.0F, CT_Y + CT_H)
        if (hitBottom > hitTop) hits.add(SliderHit(trackX, hitTop, trackW, hitBottom - hitTop, minValue, maxValue, setter))
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
            val state = controlAnimations.getOrPut("chip:$title:$value") { Anim(if (active) 1f else 0f) }
            val over = controlAnimations.getOrPut("chip-hover:$title:$value") { Anim(0f) }
            state.to(if (active) 1f else 0f, 180f, Ease.STANDARD)
            over.to(if (hovered) 1f else 0f, 140f, Ease.STANDARD)
            val t = state.value().coerceIn(0f, 1f)
            val idle = Material.withAlpha(current.onSurface, 0.04F + 0.06F * over.value())
            renderer.rounded(x, chipY, chipW, 26.0F, 13.0F, Material.mix(idle, current.secondaryContainer, t))
            if (t < 0.999f) renderer.outline(x, chipY, chipW, 26.0F, 13.0F, 1.0F, Material.withAlpha(current.outlineVariant, 1f - t))
            renderer.text(
                MusicTypeface.LABEL_TIGHT, labels[i], x + chipW / 2.0F, chipY + 13.0F,
                Material.mix(current.onSurfaceVariant, current.onSecondaryContainer, t), UI.ALIGN_CENTER,
            )
            hit(x, chipY, chipW, 26.0F) { setter(value) }
            x += chipW + 8.0F
        }
        return y + height + 8.0F
    }

    // ------------------------------------------------------------------ input

    private fun notice(message: String) {
        actionMessage = message
        actionMessageUntil = System.currentTimeMillis() + 2500L
    }

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
            if (hit is SearchHit) {
                if (!qrOpen) focusSearch(true)
                return true
            }
            focusSearch(false)
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
                is QueueDragHit -> {
                    scroll.set(scroll.value())
                    queueDrag = hit
                    queueDragTarget = hit.index
                }
                else -> hit.action?.invoke()
            }
            return true
        }
        if (lyricOpen && lyricPanel.mouseClicked(mouseX, mouseY)) return true
        focusSearch(false)
        return false
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        updateMouse(click.x.toInt(), click.y.toInt())
        if (draggingProgress && progressPreview >= 0.0F) {
            val duration = manager.durationMs()
            if (duration > 0L) manager.seek((duration * progressPreview).toLong())
        }
        val drag = queueDrag
        if (drag != null && queueDragTarget >= 0 && mouseX in CT_X..(CT_X + CT_W) && mouseY in CT_Y..(CT_Y + CT_H)) {
            if (!manager.queueMoveTo(drag.index, queueDragTarget, drag.revision)) notice("队列已变化，请重试")
        }
        queueDrag = null
        queueDragTarget = -1
        draggingProgress = false
        draggingVolume = false
        draggingSlider = null
        progressPreview = -1.0F
        return false
    }

    override fun mouseDragged(click: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        updateMouse(click.x.toInt(), click.y.toInt())
        val drag = queueDrag
        if (drag != null) {
            queueDragTarget = ((mouseY - drag.startY) / 42f).toInt().coerceIn(0, drag.count - 1)
        } else if (draggingProgress) {
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
        if (qrOpen || queueDrag != null) return true
        if (queueDrawerOpen) {
            val pages = max(1, (manager.queueView().songs.size + 5) / 6)
            queueDrawerPage = (queueDrawerPage + if (vertical > 0.0) -1 else 1).coerceIn(0, pages - 1)
            return true
        }
        if (queueDrawerMotion.value() > 0.001f) return true
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
        val max = max(0.0F, contentHeight - contentViewportHeight)
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
        // While the OS IME owns a composition, do not delete/search/close the committed text.
        if (searchFocused && searchBuffer.composition.isNotEmpty()) return true
        if (event.isEscape()) {
            when {
                searchFocused -> {
                    focusSearch(false)
                    return true
                }
                qrOpen -> {
                    qrOpen = false
                    manager.cancelQrLogin()
                    return true
                }
                queueDrawerOpen -> {
                    setQueueDrawer(false)
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
        if (qrOpen || queueDrawerOpen || queueDrawerMotion.value() > 0.001f) return true
        if (searchFocused) {
            when {
                event.key == InputConstants.KEY_BACKSPACE -> {
                    if (searchBuffer.backspace()) {
                        searchTypedAt = System.currentTimeMillis()
                    }
                    return true
                }
                event.isConfirmation() -> {
                    manager.library.rememberSearch(searchText)
                    manager.search(searchText)
                    page = Page.SEARCH
                    return true
                }
                event.isPaste() -> {
                    val clipboard = mc.keyboardHandler.clipboard
                    if (searchBuffer.paste(clipboard)) {
                        searchTypedAt = System.currentTimeMillis()
                        page = Page.SEARCH
                    }
                    return true
                }
            }
            // While editing, never treat space/arrows or other keys as music shortcuts.
            // SDL delivers committed text separately via charTyped.
            return true
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

    /** Toggle SDL text input with the screen as the owner, just as EditBox does. */
    private fun focusSearch(focused: Boolean) {
        if (searchFocused == focused) return
        if (!focused && searchFocused) {
            if (searchText.isNotBlank()) manager.library.rememberSearch(searchText)
            // A fast click can blur the field before the 320ms debounce fires. Submit the
            // committed query now; otherwise the search page can remain waiting indefinitely.
            val committed = searchText.trim()
            if (committed != manager.searchQuery) manager.search(committed)
        }
        searchFocused = focused
        if (!focused) searchBuffer.updateComposition(null)
        // This starts/stops native text input and resubmits/clears the current IME preedit.
        mc.onTextInputFocusChange(this, focused)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (!interactive() || !searchFocused) return false
        if (!event.isAllowedChatCharacter()) return false
        if (searchBuffer.appendCodePoint(event.codepoint)) {
            searchTypedAt = System.currentTimeMillis()
            page = Page.SEARCH
        }
        return true
    }

    /** SDL preedit is transient; the later text-input event commits the selected characters. */
    override fun preeditUpdated(event: PreeditEvent?): Boolean {
        if (!searchFocused) {
            searchBuffer.updateComposition(null)
            return false
        }
        searchBuffer.updateComposition(event?.fullText(), event?.caretPosition() ?: 0)
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
        focusSearch(false)
        Blur.request()
        manager.saveSettings()
    }

    // --------------------------------------------------------------- hit list

    private fun hit(x: Float, y: Float, w: Float, h: Float, action: () -> Unit) {
        if (!clippingContentHits) {
            hits.add(Hit(x, y, w, h, action))
            return
        }
        // Match scroll viewport: off-screen rows must not steal clicks from the header or player.
        val left = max(x, CT_X)
        val top = max(y, contentHitTop)
        val right = min(x + w, CT_X + CT_W)
        val bottom = min(y + h, CT_Y + CT_H)
        if (right > left && bottom > top) hits.add(Hit(left, top, right - left, bottom - top, action))
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

    private class QueueDragHit(x: Float, y: Float, w: Float, h: Float,
                               val index: Int, val revision: Long, val count: Int, val startY: Float) :
        Hit(x, y, w, h, null)

    private class SearchHit(x: Float, y: Float, w: Float, h: Float) : Hit(x, y, w, h, null)

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
        private val RAIL_W get() = when (ModuleMusic.panelLayout()) {
            ModuleMusic.PanelLayout.NEO -> 42f
            ModuleMusic.PanelLayout.WIDE -> 142f
            ModuleMusic.PanelLayout.MUONIUM -> 158f
        }
        private val CT_X get() = when (ModuleMusic.panelLayout()) {
            ModuleMusic.PanelLayout.NEO -> 70f
            ModuleMusic.PanelLayout.WIDE -> 170f
            ModuleMusic.PanelLayout.MUONIUM -> 186f
        }
        private const val CT_Y = 56.0F
        private val CT_W get() = W - CT_X - 20.0F
        private const val CT_H = H - 140.0F
        private val MINI_X get() = CT_X - 12.0F
        private const val MINI_Y = H - 84.0F
        private val MINI_W get() = W - MINI_X - 8.0F
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
        private const val I_FAVORITE = "\ue87d"
        private const val I_FAVORITE_BORDER = "\ue87e"
        private const val I_ADD = "\ue145"

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
