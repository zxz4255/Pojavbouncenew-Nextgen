package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.autoconfig.AutoConfig
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.ClientShutdownEvent
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.inGame
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * A built-in module, registered by ModuleManager. No addon JSON, entrypoint, browser or CEF.
 *
 * Settings layout
 *  - top level: the original playback / lyric options (kept under their old names for configs)
 *  - `Interface`  : music screen size + colour picking (界面大小与取色)
 *  - `Hud`        : the minimal on-screen card
 *  - `Glow`       : the adjustable 3D world lyric glow (辉光)
 */
object ModuleMusic : ClientModule(
    "Music", ModuleCategories.MISC, bind = InputConstants.UNKNOWN.value,
    state = false, aliases = listOf("MonetMusic", "MusicPlayer"), hide = true,
) {
    override val running: Boolean get() = enabled

    init {
        // Register the world-lyric render pipelines while the pipeline registry is still open.
        MusicRenderPipelines.prewarm()
        MusicLogin.restore()
    }

    private val volumeSetting = int("Volume", 35, 0..100, "%")
    private val volumePercent by volumeSetting
    private val hudSetting = boolean("HUDLyrics", true)
    val showHud: Boolean get() = hudSetting.get()
    private val worldSetting = boolean("WorldLyrics", true)
    val showWorld: Boolean get() = worldSetting.get()
    val showTranslation by boolean("Translation", true)
    val fixedPlane by boolean("FixedWorldPlane", false)
    val worldDepth by boolean("WorldOcclusion", true)
    private val worldDistance by float("PlaceDistance", 4.5f, 2f..12f, "m")
    private val worldLineHeight by float("WorldTextHeight", 0.32f, 0.15f..0.8f, "m")
    private val lyricOffset by int("LyricOffset", 0, -3000..3000, "ms")
    private val hudBottom by int("HUDOffset", 64, 0..240)
    private val hudScale by float("HUDScale", 0.85f, 0.4f..2f, "x")
    private val hudColorMode by enumChoice("HUDColorMode", HudColorMode.COVER)
    private val hudManualColor by color("HUDColor", Color4b(178, 215, 199))
    private val worldBackdrop by int("WorldBackdrop", 46, 0..100, "%")

    enum class HudColorMode(override val tag: String) : Tagged {
        COVER("Cover"), MANUAL("Manual"), DEFAULT("Default")
    }

    enum class HudStyle(override val tag: String) : Tagged {
        MINIMAL("Minimal"), GLASS("Glass")
    }

    enum class GlowColorMode(override val tag: String) : Tagged {
        ACCENT("Accent"), MANUAL("Manual")
    }

    /** Single-pass SDF profiles; no geometry layers. */
    enum class GlowMode(override val tag: String, val strength: Float, val spread: Float) : Tagged {
        OFF("Off", 0f, 1f), SOFT("Soft", 0.7f, 1f), BLOOM("Bloom", 1f, 1.25f), NEON("Neon", 1.35f, 0.7f)
    }

    data class GlowSettings(
        val enabled: Boolean,
        val mode: GlowMode,
        val intensity: Float,
        val radius: Float,
        val softness: Float,
        val color: Color4b,
        val pulse: Boolean,
    )

    data class ThemeSettings(
        val background: Color4b,
        val surface: Color4b,
        val accent: Color4b,
        val text: Color4b,
        val useCoverAccent: Boolean,
    )

    /** 界面: music screen size and colour picking. */
    private object Interface : ValueGroup("Interface") {
        val panelWidth by int("PanelWidth", 680, 600..960, "px")
        val panelHeight by int("PanelHeight", 568, 540..720, "px")
        val uiScale by float("UIScale", 1f, 0.7f..1.3f, "x")
        val cornerRadius by float("CornerRadius", 20f, 8f..34f)
        val panelOpacity by int("PanelOpacity", 94, 30..100, "%")
        val accentColor by color("AccentColor", Color4b(178, 215, 199))
        val surfaceColor by color("SurfaceColor", Color4b(25, 38, 43))
        val backgroundColor by color("BackgroundColor", Color4b(17, 27, 32))
        val textColor by color("TextColor", Color4b(229, 237, 235))
        val coverAccent by boolean("UseCoverAccent", true)
    }

    /** HUD card. Only the title and the current lyric line are rendered. */
    private object Hud : ValueGroup("Hud") {
        val style by enumChoice("Style", HudStyle.MINIMAL)
        val backgroundOpacity by int("Background", 42, 0..100, "%")
        val maxWidth by float("MaxWidth", 320f, 140f..560f)
    }

    /** 世界歌词辉光: adjustable glow for the 3D lyric plane. */
    private object Playback : ValueGroup("Playback") {
        val persistentCache by boolean("PrecacheAudio", true)
    }
    private object WorldLayout : ValueGroup("WorldLayout") {
        val surround by boolean("SurroundPlacement", false)
    }

    private object Glow : ValueGroup("Glow") {
        val glowEnabled by boolean("Enabled", true)
        val mode by enumChoice("Mode", GlowMode.SOFT)
        val intensity by float("Intensity", 0.85f, 0f..2f)
        val radius by float("Radius", 3f, 1f..5f)
        val softness by float("Softness", 1.35f, 0.4f..4f)
        val colorMode by enumChoice("ColorMode", GlowColorMode.ACCENT)
        val glowColor by color("Color", Color4b(255, 209, 102))
        val pulse by boolean("Pulse", true)
    }

    init {
        treeAll(Interface, Hud, Glow, Playback, WorldLayout)
    }

    // --- theme / settings accessors ---------------------------------------------------------

    val themeSettings: ThemeSettings
        get() = ThemeSettings(
            background = Interface.backgroundColor,
            surface = Interface.surfaceColor,
            accent = Interface.accentColor,
            text = Interface.textColor,
            useCoverAccent = Interface.coverAccent,
        )

    val uiWidth: Int get() = Interface.panelWidth
    val uiHeight: Int get() = Interface.panelHeight
    val cacheAudio: Boolean get() = Playback.persistentCache

    val uiScale: Float get() = Interface.uiScale
    val cornerRadius: Float get() = Interface.cornerRadius
    val panelOpacity: Int get() = Interface.panelOpacity

    val hudStyle: HudStyle get() = Hud.style
    val hudBackgroundOpacity: Int get() = Hud.backgroundOpacity
    val hudMaxWidth: Float get() = Hud.maxWidth
    val hudOffset: Int get() = hudBottom
    val hudCardScale: Float get() = hudScale

    val worldBackdropOpacity: Int get() = worldBackdrop
    val worldTextColor: Color4b get() = MonetPalette.text
    val lineHeight: Float get() = worldLineHeight

    /** Accent used for the whole feature: cover derived, manual, or the theme default. */
    fun accent(): Color4b {
        val track = engine.state.track
        return when (hudColorMode) {
            HudColorMode.COVER -> track?.let { MusicArtwork.accentFor(it.key) } ?: MonetPalette.primary
            HudColorMode.MANUAL -> hudManualColor
            HudColorMode.DEFAULT -> MonetPalette.primary
        }
    }

    val glowSettings: GlowSettings
        get() = GlowSettings(
            enabled = Glow.glowEnabled,
            mode = Glow.mode,
            intensity = Glow.intensity,
            radius = Glow.radius,
            softness = Glow.softness,
            color = when (Glow.colorMode) {
                GlowColorMode.ACCENT -> accent()
                GlowColorMode.MANUAL -> Glow.glowColor
            },
            pulse = Glow.pulse,
        )

    /** Refreshes the derived palette once per frame (cheap, allocation only on change). */
    fun refreshTheme() {
        MusicTheme.refresh(engine.state.track?.let { MusicArtwork.accentFor(it.key) })
    }

    // --- engine -----------------------------------------------------------------------------

    val engine by lazy { MusicEngine().also { it.onTrackChanged = ::loadLyrics } }
    private val lyricExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "MonetMusic-Lyrics").apply { isDaemon = true } }
    private val lyricGeneration = AtomicLong()

    @Volatile
    var lyrics = LyricDocument.empty()
        private set

    private var anchor: Vec3? = null
    private var placementRevision = 0
    private var planeRotation = Quaternionf()
    private var lastWorld = ""
    private var shutdown = false
    private var opening = false

    val currentLyricIndex: Int get() = LrcParser.indexAt(lyrics.lines, engine.positionMs + lyricOffset)
    val lyricOffsetMs: Int get() = lyricOffset
    val placementDistance: Float get() = worldDistance

    init {
        // Value changes are observed after `enabled` is updated, including outside a world.
        // Queue the screen switch so the current ClickGUI input finishes first.
        enabledValue.onChanged { state ->
            if (!shutdown) {
                if (state && !opening && !AutoConfig.loadingNow) {
                    mc.schedule {
                        if (!shutdown && enabled) {
                            engine.setVolume(volumePercent / 100f)
                            open()
                        }
                    }
                } else if (!state) {
                    mc.schedule {
                        if (!shutdown && !enabled) {
                            engine.stop()
                            if (mc.gui.screen() is MusicScreen) mc.gui.setScreen(null)
                        }
                    }
                }
            }
        }
        volumeSetting.onChanged { value ->
            if (!shutdown && enabled) engine.setVolume(value / 100f)
        }
    }

    override fun onEnabled() {
        if (!shutdown) engine.setVolume(volumePercent / 100f)
    }

    fun requestOpen() {
        mc.schedule { if (!shutdown) open() }
    }

    fun open() {
        if (shutdown) return
        if (!enabled) {
            opening = true
            try {
                enabled = true
            } finally {
                opening = false
            }
        }
        engine.setVolume(volumePercent / 100f)
        if (mc.gui.screen() !is MusicScreen) mc.gui.setScreen(MusicScreen())
    }

    fun placeInFront(): Boolean {
        if (!inGame) return false
        placementRevision++
        anchor = MusicWorldLyrics.anchorInFront(worldDistance.toDouble())
        planeRotation = MusicWorldLyrics.orientation(mc.gameRenderer.mainCamera())
        return true
    }

    fun setVolumePercent(value: Int) {
        volumeSetting.set(value.coerceIn(0, 100))
        engine.setVolume(volumePercent / 100f)
    }

    fun toggleHud() = hudSetting.set(!showHud)

    fun toggleWorld() = worldSetting.set(!showWorld)

    private fun loadLyrics(track: MusicTrack) {
        MusicArtwork.request(track)
        if (lyrics.trackKey == track.key) return
        val token = lyricGeneration.incrementAndGet()
        lyrics = LyricDocument.empty(track.key, "正在读取歌词…")
        MusicArtwork.request(track)
        lyricExecutor.execute {
            val next = try {
                MusicProviders.lyrics(track, engine.library)
            } catch (e: Exception) {
                LyricDocument.empty(track.key, "歌词不可用：${e.message?.take(90) ?: "网络错误"}")
            }
            mc.execute { if (token == lyricGeneration.get()) lyrics = next }
        }
    }

    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { event ->
        if (event.isPressed && event.screen == null && mc.gui.screen() == null) {
            when (event.keyCode) {
                InputConstants.KEY_M -> open()
                InputConstants.KEY_F8 -> engine.togglePause()
                InputConstants.KEY_F9 -> engine.next()
            }
        }
    }

    @Suppress("unused")
    private val worldChange = handler<WorldChangeEvent> {
        val id = mc.level?.dimension()?.toString().orEmpty()
        if (id != lastWorld) {
            anchor = null
            lastWorld = id
        }
    }

    @Suppress("unused")
    private val overlay = handler<OverlayRenderEvent> { event ->
        if (!showHud || mc.gui.screen() is MusicScreen || mc.gui.hud.isHidden) return@handler
        val state = engine.state
        val track = state.track ?: return@handler
        refreshTheme()
        MusicHud.draw(
            event.context, state, lyrics.lines.getOrNull(currentLyricIndex), lyrics.notice,
            hudCardScale, hudOffset, accent(), hudStyle, hudBackgroundOpacity, hudMaxWidth,
        )
    }

    private fun worldAnchor(): Vec3 {
        if (!inGame) return Vec3.ZERO
        val camera = mc.gameRenderer.mainCamera()
        val key = "${lyrics.trackKey}:$currentLyricIndex:$placementRevision"
        return MusicWorldLyrics.followingAnchor(camera, key, worldDistance, WorldLayout.surround)
    }

    private fun canRenderWorld(): Boolean {
        if (!showWorld || !inGame || lyrics.lines.isEmpty()) return false
        val a = worldAnchor()
        return a.distanceTo(mc.gameRenderer.mainCamera().position()) <= 48.0
    }

    @Suppress("unused")
    private val worldForeground = handler<WorldRenderEvent> { event ->
        if (!canRenderWorld()) return@handler
        refreshTheme()
        MusicWorldLyrics.render(
            event, lyrics, currentLyricIndex, lyricOffset.toLong(),
            worldAnchor(), planeRotation, fixedPlane, worldLineHeight, worldDepth,
            glowSettings, worldTextColor,
        )
    }

    @Suppress("unused")
    private val shutdownHandler = handler<ClientShutdownEvent> { close() }

    fun close() {
        if (shutdown) return
        shutdown = true
        lyricGeneration.incrementAndGet()
        lyricExecutor.shutdownNow()
        engine.close()
        MusicFont.close()
        MusicWorldLyrics.close()
        MusicArtwork.close()
        MusicQr.clear()
    }
}
