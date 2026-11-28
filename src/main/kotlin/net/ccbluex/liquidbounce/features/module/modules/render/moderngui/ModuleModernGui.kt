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
package net.ccbluex.liquidbounce.features.module.modules.render.moderngui

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassBackdrop
import net.ccbluex.liquidbounce.ui.hud.HudComponentManager

enum class ModernGuiStyle(override val tag: String) : Tagged {
    GLASS_MONET("Glass+Monet"),
    LIQUID_GLASS("LiquidGlass"),
    LIGHT_POLLUTION_GLASS("LightPollutionGlass"),
    MONET_CARD("MonetCard"),
    CUSTOM_CARD("CustomCard"),
}

/**
 * Global Liquid Glass + Monet Dynamic Color + Modern Rounded-Card UI overhaul for all in-game GUIs.
 */
object ModuleModernGui : ClientModule(
    name = "ModernGUI",
    category = ModuleCategories.RENDER,
    aliases = listOf("GlassGUI", "LiquidGlassGUI", "MonetGUI", "CardGUI"),
) {

    val style by enumChoice("Style", ModernGuiStyle.GLASS_MONET).onChanged {
        ClickGuiGlassBackdrop.retryOnOpen()
    }

    val usesGlass: Boolean
        get() = style == ModernGuiStyle.GLASS_MONET || style == ModernGuiStyle.LIQUID_GLASS || isLightPollutionGlass

    val isLightPollutionGlass: Boolean
        get() = style == ModernGuiStyle.LIGHT_POLLUTION_GLASS

    val usesMonet: Boolean
        get() = style == ModernGuiStyle.GLASS_MONET || style == ModernGuiStyle.MONET_CARD

    object Targets : ValueGroup("Targets") {
        val containers by boolean("Containers", true)
        val containerSlots by boolean("ContainerSlots", true)
        val screensAndMenus by boolean("ScreensAndMenus", true)
        val includeTitleScreen by boolean("IncludeTitleScreen", false)
        val widgets by boolean("Widgets", true)
        val tooltips by boolean("Tooltips", true)
        val chatAndSuggestions by boolean("ChatAndSuggestions", true)
        val vanillaHud by boolean("VanillaHud", true)
        val clientHudCards by boolean("ClientHudCards", true)
        val syncClientPalette by boolean("SyncClientPalette", true)
    }

    object CardDesign : ValueGroup("CardDesign") {
        val panelRadius by float("PanelRadius", 14f, 0f..28f)
        val widgetRadius by float("WidgetRadius", 8f, 0f..18f)
        val slotRadius by float("SlotRadius", 5f, 0f..10f)
        val tooltipRadius by float("TooltipRadius", 9f, 0f..18f)
        val hudCardRadius by float("HudCardRadius", 11f, 0f..24f)
        val containerPadding by float("ContainerPadding", 0f, 0f..12f)
        val headerAccentLine by boolean("HeaderAccentLine", true)
        val cardOpacity by float("CardOpacity", 0.42f, 0.08f..0.96f)
        val slotOpacity by float("SlotOpacity", 0.34f, 0f..0.90f)
        val borderWidth by float("BorderWidth", 1.15f, 0f..3.0f)
        val borderOpacity by float("BorderOpacity", 0.56f, 0f..1.0f)
        val shadowSpread by float("ShadowSpread", 13f, 0f..28f)
        val shadowOpacity by float("ShadowOpacity", 0.46f, 0f..1.0f)
        val screenScrimOpacity by float("ScreenScrimOpacity", 0.26f, 0f..0.85f)
        val openAnimation by boolean("OpenAnimation", true)
        val animationSpeed by float("AnimationSpeed", 1.0f, 0.25f..3.0f)
            .visibleWhen { openAnimation }
    }

    object GlassOptics : ValueGroup("GlassOptics") {
        val blurStrength by float("BlurStrength", 1.20f, 0.20f..3.0f)
            .visibleWhen { usesGlass }
        val refraction by float("Refraction", 1.05f, 0f..2.5f)
            .visibleWhen { usesGlass }
        val specularRim by float("SpecularRim", 1.05f, 0f..2.0f)
            .visibleWhen { usesGlass }
        val vibrance by float("Vibrance", 1.08f, 0.70f..1.60f)
            .visibleWhen { usesGlass }
        val accentRimMix by float("AccentRimMix", 0.34f, 0f..1.0f)
            .visibleWhen { usesGlass }
    }

    object Monet : ValueGroup("Monet") {
        val source by enumChoice("Source", MonetSource.WORLD_HYBRID)
            .visibleWhen { usesMonet }
        val variant by enumChoice("Variant", MonetVariant.TONAL_SPOT)
            .visibleWhen { usesMonet }
        val brightness by enumChoice("Brightness", MonetBrightness.DARK)
            .visibleWhen { usesMonet }
        val surfaceTint by float("SurfaceTint", 0.45f, 0f..1.0f)
            .visibleWhen { usesMonet }
        val chromaBoost by float("ChromaBoost", 1.15f, 0.40f..2.0f)
            .visibleWhen { usesMonet }
        val contrast by float("Contrast", 0.0f, -0.50f..0.80f)
            .visibleWhen { usesMonet }
        val transitionSpeed by float("TransitionSpeed", 3.5f, 0.30f..15.0f)
            .visibleWhen { usesMonet }
        val sampleIntervalTicks by int("SampleIntervalTicks", 4, 1..20)
            .visibleWhen { usesMonet }
        val preset by enumChoice("Preset", MonetPreset.CERULEAN)
            .visibleWhen { usesMonet && source == MonetSource.PRESET_SEED }
        val customSeed by color("CustomSeed", Color4b(0x67, 0x89, 0xFF, 0xFF))
            .visibleWhen { usesMonet }
    }

    object CustomColors : ValueGroup("CustomColors") {
        val surfaceColor by color("SurfaceColor", Color4b(18, 22, 32, 255))
            .visibleWhen { !usesMonet }
        val accentColor by color("AccentColor", Color4b(86, 138, 255, 255))
            .visibleWhen { !usesMonet }
        val borderColor by color("BorderColor", Color4b(232, 242, 255, 255))
            .visibleWhen { !usesMonet }
    }

    init {
        tree(Targets)
        tree(CardDesign)
        tree(GlassOptics)
        tree(Monet)
        tree(CustomColors)
    }

    override fun onEnabled() {
        ClickGuiGlassBackdrop.retryOnOpen()
        HudComponentManager.syncPaletteFromTheme()
    }

    override fun onDisabled() {
        HudComponentManager.syncPaletteFromTheme()
    }

    /**
     * Avoids running legacy full-screen HUD blur when Liquid Glass is already refracting & blurring HUD cards.
     */
    fun suppressesLegacyHudBlur(): Boolean =
        running && usesGlass && (Targets.clientHudCards || Targets.vanillaHud)
}
