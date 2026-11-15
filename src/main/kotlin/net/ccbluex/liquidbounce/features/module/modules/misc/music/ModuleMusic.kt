package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.event.events.ClientShutdownEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.WorldFeatureSubmitEvent
import net.ccbluex.liquidbounce.features.module.modules.misc.music.worldlyrics.WorldLyrics
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.hud.Island
import net.ccbluex.liquidbounce.utils.client.mc
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.MouseHandler

/**
 * The NetEase Cloud Music player.
 *
 * <p>Ported from NeoRaven's music package: a full player window with a nav rail, recommendations, the
 * user's playlists, search, word-by-word lyrics and a QR login, plus the compact island card that
 * lives on the HUD and morphs into the window.</p>
 *
 * <p>Settings layout</p>
 * <ul>
 *   <li>`Island`: the HUD card — visibility, size, position, HUD scale, lyric-or-title row, and the
 *       key that opens the window.</li>
 *   <li>`Playback`: the stream level and the volume the player starts with; everything else (repeat,
 *       shuffle, lyric scale, cover tint) is written to `config/liquidbounce/music/music.txt` by the
 *       manager, exactly like the reference did.</li>
 * </ul>
 */
object ModuleMusic : ClientModule(
    "Music",
    ModuleCategories.MISC,
    bind = InputConstants.KEY_M,
    state = false,
    aliases = listOf("MusicPlayer", "Netease", "网易云"),
    hide = true,
) {

    override val running: Boolean get() = enabled

    /**
     * The window key is ours, not a toggle.
     *
     * <p>Without this, [net.ccbluex.liquidbounce.features.module.ModuleManager] would treat `M` as a
     * module bind and switch the player on/off instead of opening the window (and the very first press
     * — while the module is still off — could not open anything, because LB only dispatches a module's
     * handlers while it is running).</p>
     */
    override val externalBind: Boolean get() = true

    // ---------------------------------------------------------------- settings

    private val volumeSetting = float("Volume", 0.8F, 0.0F..1.0F, "%")

    enum class PanelLayout(override val tag: String) : net.ccbluex.liquidbounce.config.types.list.Tagged {
        NEO("NeoRaven"), WIDE("Wide"), MUONIUM("MuoniumInspired")
    }
    enum class PanelSurface(override val tag: String) : net.ccbluex.liquidbounce.config.types.list.Tagged {
        MATERIAL("Material"), GLASS("Glass"), MIDNIGHT("Midnight"), AURORA("Aurora"),
        DAYLIGHT("Daylight")
    }
    private val panelLayoutSetting = enumChoice("PanelLayout", PanelLayout.NEO)
    private val panelSurfaceSetting = enumChoice("PanelSurface", PanelSurface.MATERIAL)
    fun panelLayout(): PanelLayout = panelLayoutSetting.get()
    fun setPanelLayout(value: PanelLayout) = panelLayoutSetting.set(value)
    fun panelSurface(): PanelSurface = panelSurfaceSetting.get()
    fun setPanelSurface(value: PanelSurface) = panelSurfaceSetting.set(value)

    private val streamLevelSetting = enumChoice("StreamLevel", StreamLevel.EXHIGH)

    enum class StreamLevel(override val tag: String, val value: String) : net.ccbluex.liquidbounce.config.types.list.Tagged {
        STANDARD("Standard", "standard"),
        HIGHER("Higher", "higher"),
        EXHIGH("ExHigh", "exhigh"),
        LOSSLESS("Lossless", "lossless"),
    }

    private object IslandGroup : net.ccbluex.liquidbounce.config.types.group.ValueGroup("Island") {
        val showIsland = boolean("ShowIsland", true)
        val islandSize = float("IslandSize", 1.0F, 0.5F..2.0F)
        val islandScale = float("IslandScale", 1.0F, 0.5F..2.0F)
        val positionX = float("IslandX", 50.0F, 0.0F..100.0F, "%")
        val positionY = float("IslandY", 0.0F, 0.0F..100.0F, "%")
        val islandLyric = boolean("IslandLyric", true)
        val openKey = int("OpenKey", InputConstants.KEY_M, 0..400)
    }

    enum class WorldPreset(override val tag: String) : net.ccbluex.liquidbounce.config.types.list.Tagged {
        CLASSIC("Classic"), CADENZA("Cadenza"), LIGHT("Light"), PULSE("Pulse"),
        LUMINOUS("Luminous"), PARTITA("Partita"), PENDOLO("Pendolo"), DIORAMA("Diorama")
    }
    enum class ScreenPreset(override val tag: String) : net.ccbluex.liquidbounce.config.types.list.Tagged {
        FLOAT("Float"), KARAOKE("Karaoke"), STACK("Stack"), CARD("Card")
    }

    private object WorldLyricsGroup : net.ccbluex.liquidbounce.config.types.group.ValueGroup("WorldLyrics") {
        val show = boolean("Enabled", true)
        val preset = enumChoice("Preset", WorldPreset.CLASSIC)
        val distance = float("Distance", 10.5F, 3.0F..48.0F)
        val size = float("Scale", 1.0F, 0.4F..6.0F)
        val spread = float("Spread", 30.0F, 0.0F..60.0F)
        val glow = float("GlowStrength", 0.35F, 0.0F..1.0F)
        val split = boolean("SplitLines", true)
        val wordPop = boolean("WordPop", false)
        val keepDistance = boolean("KeepDistance", true)
    }

    private object ScreenLyricsGroup : net.ccbluex.liquidbounce.config.types.group.ValueGroup("ScreenLyrics") {
        val show = boolean("Enabled", false)
        val preset = enumChoice("Preset", ScreenPreset.FLOAT)
        val x = float("X", 50f, 0f..100f, "%")
        val y = float("Y", 76f, 0f..100f, "%")
        val scale = float("Scale", 1f, 0.5f..2.5f)
        val translation = boolean("Translation", true)
    }

    init {
        treeAll(IslandGroup)
        treeAll(WorldLyricsGroup)
        treeAll(ScreenLyricsGroup)
    }

    private val worldLyricsRender = handler<WorldFeatureSubmitEvent> { event ->
        if (WorldLyricsGroup.show.get()) {
            WorldLyrics.render(event, WorldLyrics.Options(
                WorldLyricsGroup.distance.get(), WorldLyricsGroup.size.get(),
                WorldLyricsGroup.spread.get(), WorldLyricsGroup.glow.get(),
                WorldLyricsGroup.split.get(), WorldLyricsGroup.wordPop.get(),
                WorldLyricsGroup.keepDistance.get(), WorldLyricsGroup.preset.get(),
            ))
        } else {
            WorldLyrics.clear()
        }
    }

    fun islandSize(): Float = IslandGroup.islandSize.get()

    fun islandScale(): Float = IslandGroup.islandScale.get()

    fun islandPositionX(): Float = IslandGroup.positionX.get()

    fun islandPositionY(): Float = IslandGroup.positionY.get()

    fun islandShowsLyric(): Boolean = IslandGroup.islandLyric.get()

    fun islandPillActive(): Boolean = IslandGroup.showIsland.get()

    private fun openKey(): Int = IslandGroup.openKey.get()

    // ---------------------------------------------------------------- lifecycle

    override fun onEnabled() {
        MusicManager.boot()
        MusicManager.setVolume(volumeSetting.get())
        MusicManager.setStreamLevel(streamLevelSetting.get().value)
    }

    override fun onDisabled() {
        WorldLyrics.clear()
        ScreenLyrics.clear()
        MusicManager.saveSettings()
    }

    // ------------------------------------------------------------------ handlers

    /**
     * Draws the island card over the HUD. Hidden while the player window (or any other screen) is up,
     * and while the client's own HUD is hidden — the same rule the reference applied to `GuiChat`.
     */
    private val overlay = handler<OverlayRenderEvent> { event ->
        if (!enabled || mc.gui.hud.isHidden) return@handler
        if (mc.gui.screen() != null) return@handler
        Island.render(event.context)
        if (ScreenLyricsGroup.show.get()) {
            ScreenLyrics.render(event.context, ScreenLyricsGroup.preset.get(),
                ScreenLyricsGroup.x.get(), ScreenLyricsGroup.y.get(), ScreenLyricsGroup.scale.get(),
                ScreenLyricsGroup.translation.get())
        } else ScreenLyrics.clear()
    }

    /**
     * Clicks on the island: artwork opens the window, the transport row plays, seeks and skips.
     *
     * <p>26.3's `MouseButtonEvent` carries no cursor position, so the GUI coordinates come from the
     * window's mouse handler, converted with the same helpers the client's own HUD modules use.</p>
     */
    private val mouse = handler<MouseButtonEvent> { event ->
        if (!enabled || !event.isLeftClick) return@handler
        if (mc.gui.screen() != null) return@handler
        Island.musicClick(scaledMouseX().toFloat(), scaledMouseY().toFloat())
    }

    private fun scaledMouseX(): Double = MouseHandler.getScaledXPos(mc.window, mc.mouseHandler.xpos())

    private fun scaledMouseY(): Double = MouseHandler.getScaledYPos(mc.window, mc.mouseHandler.ypos())

    /**
     * Whether a key press should open the window.
     *
     * <p>`KeyboardKeyEvent.scanCode` is the SDL scancode and is what every other bind in the client
     * compares against (`InputBind.matchesKey`); `keyCode` is the SDL keycode and would never match a
     * scancode, which is why the previous comparison silently never fired.</p>
     */
    fun matchesWindowKey(scanCode: Int): Boolean = scanCode == openKey() || scanCode == InputConstants.KEY_M

    /** The configured window key, as an SDL scancode. */
    fun windowKey(): Int = openKey()

    /** Keeps [MusicHotkeys] initialised from client start — see the note on that object. */
    @Suppress("unused")
    private val hotkeys = MusicHotkeys

    private val shutdown = handler<ClientShutdownEvent> {
        MusicManager.shutdown()
    }

    /* ------------------------------------------------- across-the-client API */

    /**
     * `.music` / `.monet` — opens the player window.
     *
     * <p>The command runs while the chat screen still holds the input lock, so the screen switch is
     * queued on the client thread instead of happening inside the command body.</p>
     */
    fun requestOpen() {
        if (!enabled) enabled = true
        logger.info("[Music] window requested (screen={})", mc.gui.screen())
        mc.execute { MusicIsland.openScreen() }
    }

    /**
     * Transport surface for `.music pause|next|previous|stop`.
     *
     * <p>A thin facade over [MusicManager]: the reference exposed these on its player object, while the
     * port splits queue handling (the manager) and audio output (the engine). Callers should not care
     * which one owns what.</p>
     */
    object Transport {
        fun togglePause() = MusicManager.togglePlay()
        fun next() = MusicManager.next()
        fun previous() = MusicManager.previous()
        fun stop() = MusicManager.engine.stop()
    }

    val engine: Transport get() = Transport

    /** Called from `LiquidBounce.shutdownClient`; releases the audio line so the JVM can exit. */
    fun close() = MusicManager.shutdown()
}
