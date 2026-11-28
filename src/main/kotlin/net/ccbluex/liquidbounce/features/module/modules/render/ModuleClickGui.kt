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

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.ClickGuiScaleChangeEvent
import net.ccbluex.liquidbounce.event.events.ClickGuiValueChangeEvent
import net.ccbluex.liquidbounce.event.events.DisconnectEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.addon.AddonApi
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawCircleOutline
import net.ccbluex.liquidbounce.render.drawGradientCircle
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironment
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.ui.clickgui.ClickGuiLayoutStore
import net.ccbluex.liquidbounce.ui.clickgui.NativeScreens
import net.ccbluex.liquidbounce.ui.clickgui.TabbedClickGuiScreen
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassBackdrop
import net.ccbluex.liquidbounce.ui.common.UiLanguageMode
import net.ccbluex.liquidbounce.ui.common.UiLocalization
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.minecraft.client.gui.screens.Screen

/**
 * ClickGUI module
 *
 * Shows you an easy-to-use menu to toggle and configure modules, edit the HUD, and adjust global settings.
 */
@AddonApi
object ModuleClickGui :
    ClientModule("ClickGUI", ModuleCategories.RENDER, bind = InputConstants.KEY_RSHIFT, disableActivation = true) {

    enum class ClickGuiStyle(override val tag: String) : Tagged {
        MODERN("Modern") {
            // Read old saved profiles as Modern without retaining a selectable retired style.
            override val tagAliases: List<String> get() = listOf("Neumorphism")
        },
        GLASS("Glass"),

        /**
         * 098's original quiet light-pollution glass: pure separable Gaussian-blurred card with no bloom,
         * no header glaze and a sakura accent. Kept as its own style beside the 099 white-bloom variant.
         */
        GAUSSIAN_GLASS("GaussianGlass") {
            override val tagAliases: List<String> get() = listOf("LegacyLightPollutionGlass", "QuietGlass")
        },

        /** 099's volumetric inward-to-outward white-bloom light-pollution glass. */
        LIGHT_POLLUTION_GLASS("LightPollutionGlass"),
        WORLD_3D("World3D") {
            override val tagAliases: List<String> get() = listOf("3D", "Spatial3D", "World")
        },
    }

    override val running get() = true

    private val languageValue = enumChoice("Language", UiLanguageMode.AUTO).apply {
        onChanged { nextLang ->
            UiLocalization.setLanguageMode(nextLang)
            EventManager.callEvent(ClickGuiValueChangeEvent(this@ModuleClickGui))
        }
    }

    init {
        UiLocalization.registerSyncListener { syncedMode ->
            if (languageValue.get() != syncedMode) {
                languageValue.set(syncedMode)
            }
        }
    }

    private val styleOption = enumChoice("Style", ClickGuiStyle.MODERN).apply {
        onChanged { nextStyle ->
            if (nextStyle != ClickGuiStyle.MODERN) {
                ClickGuiGlassBackdrop.retryOnOpen()
            }
            EventManager.callEvent(ClickGuiValueChangeEvent(this@ModuleClickGui))
        }
    }
    private val styleSetting: ClickGuiStyle get() = styleOption.get()

    private val world3dCurvature by float("World3DCurvature", 1.0f, 0.45f..1.55f)
        .visibleWhen { isWorld3dStyle }
        .onChanged {
            EventManager.callEvent(ClickGuiValueChangeEvent(this))
        }

    private val world3dParallax by float("World3DParallax", 1.0f, 0.0f..2.0f)
        .visibleWhen { isWorld3dStyle }
        .onChanged {
            EventManager.callEvent(ClickGuiValueChangeEvent(this))
        }

    private val world3dWorldRings by boolean("World3DRings", true)
        .visibleWhen { isWorld3dStyle }
        .onChanged {
            EventManager.callEvent(ClickGuiValueChangeEvent(this))
        }

    private val glassBlur by float("GlassBlur", 1.15f, 0.25f..2.5f)
        .visibleWhen { isGlassStyle }
        .onChanged {
            EventManager.callEvent(ClickGuiValueChangeEvent(this))
        }

    private val glassTintOpacity by float("GlassTintOpacity", 0.34f, 0.08f..0.82f)
        .visibleWhen { isGlassStyle }
        .onChanged {
            EventManager.callEvent(ClickGuiValueChangeEvent(this))
        }

    private val glassRefraction by float("GlassRefraction", 1.0f, 0.0f..2.5f)
        .visibleWhen { isGlassStyle }
        .onChanged {
            EventManager.callEvent(ClickGuiValueChangeEvent(this))
        }

    private val glassRim by float("GlassRim", 1.0f, 0.0f..2.0f)
        .visibleWhen { isGlassStyle }
        .onChanged {
            EventManager.callEvent(ClickGuiValueChangeEvent(this))
        }

    private val glassShadow by float("GlassShadow", 1.0f, 0.0f..2.0f)
        .visibleWhen { isGlassStyle }
        .onChanged {
            EventManager.callEvent(ClickGuiValueChangeEvent(this))
        }

    private val scaleSetting by float("Scale", 1.0f, 0.5f..2f).onChanged {
        EventManager.callEvent(ClickGuiScaleChangeEvent(it))
        EventManager.callEvent(ClickGuiValueChangeEvent(this))
    }

    private val searchBarAutoFocus by boolean("SearchBarAutoFocus", true)
        .onChanged {
            EventManager.callEvent(ClickGuiValueChangeEvent(this))
        }

    val isWorld3dStyle: Boolean get() = styleSetting == ClickGuiStyle.WORLD_3D
    val isLightPollutionGlass: Boolean get() = styleSetting == ClickGuiStyle.LIGHT_POLLUTION_GLASS
    val isGaussianGlass: Boolean get() = styleSetting == ClickGuiStyle.GAUSSIAN_GLASS
    val isGlassStyle: Boolean get() = styleSetting != ClickGuiStyle.MODERN
    val world3dCurvatureValue: Float get() = world3dCurvature
    val world3dParallaxValue: Float get() = world3dParallax
    val glassBlurValue: Float get() = glassBlur
    val glassTintOpacityValue: Float get() = glassTintOpacity
    val glassRefractionValue: Float get() = glassRefraction
    val glassRimValue: Float get() = glassRim
    val glassShadowValue: Float get() = glassShadow
    val scaleValue: Float get() = scaleSetting
    val searchBarAutoFocusValue: Boolean get() = searchBarAutoFocus
    val snappingEnabledValue: Boolean get() = Snapping.enabledValue.get()
    val gridSizeValue: Float get() = Snapping.gridSizeValue

    fun cycleStyleFromWorld3d() {
        styleOption.set(ClickGuiStyle.LIGHT_POLLUTION_GLASS)
        net.ccbluex.liquidbounce.config.ConfigSystem.store(
            net.ccbluex.liquidbounce.features.module.ModuleManager.modulesConfig
        )
    }

    val isInSearchBar: Boolean
        get() = (mc.gui.screen() as? TabbedClickGuiScreen)?.isTypingIntoSetting() == true

    object Snapping : ToggleableValueGroup(this, "Snapping", true) {

        private val gridSize by int("GridSize", 10, 1..100, "px").onChanged {
            EventManager.callEvent(ClickGuiValueChangeEvent(ModuleClickGui))
        }

        val gridSizeValue: Float get() = gridSize.toFloat()

        init {
            inner.find { it.name == "Enabled" }?.onChanged {
                EventManager.callEvent(ClickGuiValueChangeEvent(ModuleClickGui))
            }
        }
    }

    init {
        tree(Snapping)
    }

    fun createScreen(): Screen = TabbedClickGuiScreen()

    override fun onEnabled() {
        if (!LiquidBounce.isInitialized || !inGame) {
            return
        }

        if (isGlassStyle) {
            ClickGuiGlassBackdrop.retryOnOpen()
        }
        mc.execute {
            mc.gui.setScreen(createScreen())
        }
        super.onEnabled()
    }

    fun onScreenClosed() {
        if (enabled) {
            enabled = false
        }
    }

    @Suppress("unused")
    private val disconnectHandler = handler<DisconnectEvent> {
        if (NativeScreens.isClientScreen(mc.gui.screen())) {
            mc.gui.setScreen(null)
        }
    }

    /**
     * Renders spatial 3D holo-compass rings in actual Minecraft world space around the player
     * while the World3D ClickGUI stage is open.
     */
    @Suppress("unused")
    private val world3dStageRings = handler<WorldRenderEvent> { event ->
        if (!isWorld3dStyle || !world3dWorldRings) return@handler
        val screen = mc.gui.screen() as? TabbedClickGuiScreen ?: return@handler
        if (ClickGuiLayoutStore.activeTabIndex != 0) return@handler
        val player = mc.player ?: return@handler

        val accentArgb = UiPalette.accent(1f)
        val ar = (accentArgb ushr 16) and 0xFF
        val ag = (accentArgb ushr 8) and 0xFF
        val ab = accentArgb and 0xFF

        val groundOuter = Color4b(ar, ag, ab, 68)
        val groundInner = Color4b(ar, ag, ab, 0)
        val groundOutline = Color4b(ar, ag, ab, 148)
        val deckOutline = Color4b(ar, ag, ab, 92)
        val deckGlow = Color4b(ar, ag, ab, 28)

        val basePos = player.interpolateCurrentPosition(event.partialTicks)
        event.renderEnvironment {
            withPositionRelativeToCamera(basePos.add(0.0, 0.06, 0.0)) {
                drawGradientCircle(3.35f, 2.75f, groundOuter, groundInner)
                drawCircleOutline(3.35f, groundOutline)
            }
            withPositionRelativeToCamera(basePos.add(0.0, 1.12, 0.0)) {
                drawGradientCircle(4.25f, 4.02f, deckGlow, groundInner)
                drawCircleOutline(4.25f, deckOutline)
            }
        }
    }

    /**
     * Preserved for external callers (commands, auto-config, addon registration, Live2D, etc.).
     * [TabbedClickGuiScreen] reads live [Value] trees directly each frame.
     */
    fun sync() {
        // Live binding; no cached web JSON state to push.
    }

    fun invalidate() {
        val current = mc.gui.screen()
        if (NativeScreens.isClientScreen(current)) {
            mc.gui.setScreen(null)
            mc.gui.setScreen(createScreen())
        }
    }
}
