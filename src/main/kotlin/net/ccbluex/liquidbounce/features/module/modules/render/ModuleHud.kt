/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.DisconnectEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.ScreenEvent
import net.ccbluex.liquidbounce.event.events.SpaceSeperatedNamesChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.SelfDestruct.isDestructed
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.ui.clickgui.NativeScreens
import net.ccbluex.liquidbounce.ui.common.UiLanguageMode
import net.ccbluex.liquidbounce.ui.common.UiLocalization
import net.ccbluex.liquidbounce.ui.hud.HudComponentManager
import net.ccbluex.liquidbounce.ui.hud.components.minimap.MinimapHudComponent
import net.ccbluex.liquidbounce.utils.client.inGame
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.LevelLoadingScreen
import net.minecraft.client.gui.screens.Screen

/**
 * Module HUD
 *
 * Renders the LiquidBounce Nextgen native HUD dashboard and configures theme colors & HUD components.
 */
object ModuleHud : ClientModule("HUD", ModuleCategories.RENDER, state = true, hide = true) {

    override val running
        get() = this.enabled && !isDestructed
    override val baseKey: String
        get() = "${ConfigSystem.KEY_PREFIX}.module.hud"

    private val isVisible: Boolean
        get() = inGame

    private val isHudHidden: Boolean
        get() = mc.gui.hud.isHidden

    private var lastHudHidden = false

    var hudEditorSelected = false
        set(value) {
            if (value != field) {
                field = value
                updateOverlayVisibility(mc.gui.screen())
            }
        }

    private fun shouldRenderHudOverlay(screen: Screen?): Boolean {
        if (!running || !isVisible) return false
        if (isHudHidden && !OpaiChestLoot.isReplacing(screen)) return false
        if (screen is DisconnectedScreen || screen is LevelLoadingScreen) return false
        // When the HUD Editor tab is open inside TabbedClickGuiScreen, HudEditorTabView draws
        // the HUD components itself with interactive drag handles, so skip the background pass.
        if (hudEditorSelected && NativeScreens.isClientScreen(screen)) return false
        return true
    }

    private fun updateOverlayVisibility(@Suppress("UNUSED_PARAMETER") screen: Screen?) {
        lastHudHidden = isHudHidden
    }

    @Suppress("unused")
    private val nativeRenderHandler = handler<OverlayRenderEvent> { event ->
        if (mc.gui.overlay() != null) {
            return@handler
        }
        renderNative(event.context, event.tickDelta)
    }

    fun renderNative(ctx: GuiGraphicsExtractor, tickDelta: Float = 0f) {
        val currentScreen = mc.gui.screen()
        if (!shouldRenderHudOverlay(currentScreen)) {
            return
        }
        HudComponentManager.renderAll(ctx, tickDelta, inEditor = false)
    }

    private val languageValue = enumChoice("Language", UiLanguageMode.AUTO).apply {
        onChanged { nextLang ->
            UiLocalization.setLanguageMode(nextLang)
        }
    }

    init {
        UiLocalization.registerSyncListener { syncedMode ->
            if (languageValue.get() != syncedMode) {
                languageValue.set(syncedMode)
            }
        }
        tree(Blur)
    }

    object Blur : ToggleableValueGroup(ModuleHud, "Blur", enabled = true) {
        val sigma by float("Sigma", 5.0F, 1.0F..15.0F)
        val alphaBlendRange by floatRange("AlphaBlendRange", 0.0F..0.75F, 0.0F..1.0F)
    }

    /** Independent size multipliers for vanilla HUD elements; 1.0x keeps the Minecraft default. */
    private val componentScale = tree(ValueGroup("ComponentScale"))

    val scoreboardScale by componentScale.float("Scoreboard", 1.0F, 0.5F..2.0F, "x")
    val playerListScale by componentScale.float("PlayerList", 1.0F, 0.5F..2.0F, "x")
    val bossBarScale by componentScale.float("BossBar", 1.0F, 0.5F..2.0F, "x")
    val statusEffectsScale by componentScale.float("StatusEffects", 1.0F, 0.5F..2.0F, "x")
    val crosshairScale by componentScale.float("Crosshair", 1.0F, 0.5F..2.0F, "x")
    val hotbarScale by componentScale.float("Hotbar", 1.0F, 0.5F..2.0F, "x")
    val statusBarScale by componentScale.float("StatusBars", 1.0F, 0.5F..2.0F, "x")
    val contextualBarScale by componentScale.float("ContextualBar", 1.0F, 0.5F..2.0F, "x")
    val selectedItemScale by componentScale.float("SelectedItem", 1.0F, 0.5F..2.0F, "x")
    val chatScale by componentScale.float("Chat", 1.0F, 0.5F..2.0F, "x")
    val titleScale by componentScale.float("Title", 1.0F, 0.5F..2.0F, "x")
    val overlayMessageScale by componentScale.float("OverlayMessage", 1.0F, 0.5F..2.0F, "x")
    val subtitleScale by componentScale.float("Subtitles", 1.0F, 0.5F..2.0F, "x")
    val demoOverlayScale by componentScale.float("DemoOverlay", 1.0F, 0.5F..2.0F, "x")

    val spaceSeperatedNames by boolean("SpaceSeperatedNames", true).onChange { state ->
        EventManager.callEvent(SpaceSeperatedNamesChangeEvent(state))
        state
    }

    val isBlurEffectActive
        get() = Blur.enabled && !(isHudHidden && mc.gui.screen() == null)

    val themes = tree(ValueGroup("Themes")).apply {
        tree(HudComponentManager.LiquidBounceThemeGroup)
    }

    val components = tree(ValueGroup("AdditionalComponents")).apply {
        tree(MinimapHudComponent)
    }

    fun updateThemes() {
        if (!themes.inner.contains(HudComponentManager.LiquidBounceThemeGroup)) {
            themes.tree(HudComponentManager.LiquidBounceThemeGroup)
        }
        themes.walkInit()
        themes.walkKeyPath()
        HudComponentManager.syncPaletteFromTheme()
    }

    override fun onEnabled() {
        updateOverlayVisibility(mc.gui.screen())
    }

    override fun onDisabled() {
        // Gated by `running`.
    }

    @Suppress("unused")
    private val screenHandler = handler<ScreenEvent> { event ->
        updateOverlayVisibility(event.screen)
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (isHudHidden != lastHudHidden) {
            updateOverlayVisibility(mc.gui.screen())
        }
    }

    @Suppress("unused")
    private val disconnectHandler = handler<DisconnectEvent> {
        HudComponentManager.syncPaletteFromTheme()
    }

    fun reopen() {
        HudComponentManager.syncPaletteFromTheme()
        updateOverlayVisibility(mc.gui.screen())
    }
}
