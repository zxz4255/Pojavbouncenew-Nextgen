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
package net.ccbluex.liquidbounce.ui.hud

import net.ccbluex.liquidbounce.ui.clickgui.glass.LightPollutionGlassSurface
import com.google.gson.JsonObject
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.ThemeColorChangeEvent
import net.ccbluex.liquidbounce.features.module.modules.render.moderngui.ModernGuiInterceptor
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.ui.common.UiAnimation
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.ccbluex.liquidbounce.ui.hud.components.ArmorItemsHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.ArrayListHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.BlockCounterHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.ClosedCaptionsHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.CraftingInventoryHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.EffectsHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.EnderChestInventoryHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.HotbarHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.ImageHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.InventoryHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.InventoryStatisticsHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.KeyBindsHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.KeystrokesHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.NotificationsHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.ScoreboardHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.TabGuiHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.TacoHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.TargetHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.TextHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.WatermarkHudComponent
import net.ccbluex.liquidbounce.ui.hud.components.minimap.MinimapHudComponent
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Built-in native LiquidBounce HUD theme (`ModuleHud -> Themes -> Liquidbounce`) and component manager.
 *
 * Preserves the exact config tree (`Themes -> Liquidbounce -> Colors -> Accent/Tint` + `Components -> ...`)
 * and `AdditionalComponents -> Minimap` from 043.
 */
object HudComponentManager {

    const val WEB_HUD_BASE_SCALE: Float = 2f
    const val THEME_ID: String = "liquidbounce"

    data class CatalogEntry(
        val name: String,
        val description: String,
        val singleton: Boolean,
        val canAdd: Boolean,
    )

    private val componentFactories: LinkedHashMap<String, () -> HudComponent> = linkedMapOf(
        "Watermark" to ::WatermarkHudComponent,
        "TabGui" to ::TabGuiHudComponent,
        "ArrayList" to ::ArrayListHudComponent,
        "Notifications" to ::NotificationsHudComponent,
        "Hotbar" to ::HotbarHudComponent,
        "Scoreboard" to ::ScoreboardHudComponent,
        "ArmorItems" to ::ArmorItemsHudComponent,
        "InventoryStatistics" to ::InventoryStatisticsHudComponent,
        "Inventory" to ::InventoryHudComponent,
        "CraftingInventory" to ::CraftingInventoryHudComponent,
        "EnderChestInventory" to ::EnderChestInventoryHudComponent,
        "TargetHud" to ::TargetHudComponent,
        "BlockCounter" to ::BlockCounterHudComponent,
        "Effects" to ::EffectsHudComponent,
        "Keystrokes" to ::KeystrokesHudComponent,
        "Taco" to ::TacoHudComponent,
        "Image" to ::ImageHudComponent,
        "Text" to ::TextHudComponent,
        "KeyBinds" to ::KeyBindsHudComponent,
        "ClosedCaptions" to ::ClosedCaptionsHudComponent,
    )

    /**
     * `Components` ValueGroup with `prepareDeserialize` supporting dynamic multi-instance `Text` and `Image`
     * entries saved in user configs, matching 043's `Theme.ComponentSettings`.
     */
    object ThemeComponentsGroup : ValueGroup("Components") {
        init {
            for ((_, factory) in componentFactories) {
                tree(factory())
            }
        }

        override fun prepareDeserialize(jsonObject: JsonObject) {
            val existingCounts = Object2IntOpenHashMap<String>()
            inner.filterIsInstance<HudComponent>().forEach { existingCounts.addTo(it.name, 1) }

            val arr = jsonObject.getAsJsonArray("value") ?: return
            for (elem in arr) {
                if (!elem.isJsonObject) continue
                val name = elem.asJsonObject.get("name")?.asString ?: continue
                val rem = existingCounts.getOrDefault(name, 0)
                if (rem > 0) {
                    existingCounts.put(name, rem - 1)
                    continue
                }
                val factory = componentFactories[name] ?: continue
                val created = factory()
                if (created.singleton) {
                    EventManager.unregisterEventHandler(created)
                    continue
                }
                registerExtraComponent(created)
            }
        }
    }

    object ThemeColorsGroup : ValueGroup("Colors") {
        val accent = color("Accent", Color4b(0x46, 0x77, 0xFF, 0xFF)).apply {
            onChanged { c ->
                UiPalette.accentColor = c
                EventManager.callEvent(ThemeColorChangeEvent(THEME_ID, "Accent", c))
            }
        }
        val tint = color("Tint", Color4b(0x00, 0x00, 0x00, 0xFF)).apply {
            onChanged { c ->
                UiPalette.tintColor = c
                EventManager.callEvent(ThemeColorChangeEvent(THEME_ID, "Tint", c))
            }
        }
    }

    object LiquidBounceThemeGroup : ValueGroup("Liquidbounce") {
        init {
            tree(ThemeColorsGroup)
            tree(ThemeComponentsGroup)
        }
    }

    val themeComponents: List<HudComponent>
        get() = ThemeComponentsGroup.inner.filterIsInstance<HudComponent>()

    val nativeComponents: List<NativeHudComponent> = listOf(MinimapHudComponent)

    val components: List<HudComponent>
        get() = buildList(themeComponents.size + nativeComponents.size) {
            addAll(nativeComponents)
            addAll(themeComponents)
        }

    private val dtTracker = UiAnimation.DeltaTracker()

    fun syncPaletteFromTheme() {
        val monetOverride = ModernGuiInterceptor.resolveClientPaletteOverride()
        if (monetOverride != null) {
            UiPalette.accentColor = monetOverride.first
            UiPalette.tintColor = monetOverride.second
        } else {
            UiPalette.accentColor = ThemeColorsGroup.accent.get()
            UiPalette.tintColor = ThemeColorsGroup.tint.get()
        }
    }

    private fun registerExtraComponent(component: HudComponent) {
        ThemeComponentsGroup.tree(component)
        component.walkInit()
        ThemeComponentsGroup.key?.let(component::walkKeyPath)
    }

    @JvmStatic
    fun isTweakEnabled(tweak: HudComponentTweak): Boolean =
        components.any { it.running && it.hasTweak(tweak) }

    @JvmStatic
    fun getComponentWithTweak(tweak: HudComponentTweak): HudComponent? =
        components.firstOrNull { it.running && it.hasTweak(tweak) }

    fun catalog(): List<CatalogEntry> {
        val all = components
        val entries = ArrayList<CatalogEntry>(componentFactories.size + nativeComponents.size)
        for ((name, _) in componentFactories) {
            val sample = all.firstOrNull { it.name == name } ?: continue
            val canAdd = !sample.singleton || all.none { it.name == name && it.enabled }
            entries.add(CatalogEntry(name, sample.componentDescription, sample.singleton, canAdd))
        }
        for (nc in nativeComponents) {
            entries.add(CatalogEntry(nc.name, nc.componentDescription, singleton = true, canAdd = !nc.enabled))
        }
        entries.sortBy { it.name.lowercase() }
        return entries
    }

    fun addComponent(name: String): HudComponent? {
        val nativeMatch = nativeComponents.firstOrNull { it.name == name }
        if (nativeMatch != null) {
            if (nativeMatch.enabled) return null
            nativeMatch.enabled = true
            bringToFront(nativeMatch)
            return nativeMatch
        }

        val factory = componentFactories[name] ?: return null
        val existingOfName = themeComponents.filter { it.name == name }
        val first = existingOfName.firstOrNull()
        if (first?.singleton == true && existingOfName.any { it.enabled }) {
            return null
        }

        val reusableDisabled = existingOfName.firstOrNull { !it.enabled }
        val target = if (reusableDisabled != null) {
            if (!reusableDisabled.singleton) {
                reusableDisabled.restore()
            }
            reusableDisabled
        } else {
            factory().also(::registerExtraComponent)
        }

        target.enabled = true
        bringToFront(target)
        return target
    }

    fun removeOrDisableComponent(component: HudComponent) {
        component.enabled = false
        if (!component.singleton) {
            val sameType = themeComponents.filter { it.name == component.name }
            if (sameType.size > 1) {
                EventManager.unregisterEventHandler(component)
                ThemeComponentsGroup.drop(component)
            }
        }
    }

    fun bringToFront(component: HudComponent) {
        val maxZ = components.maxOfOrNull { it.zIndex } ?: 0
        if (component.zIndex < maxZ || maxZ == 0) {
            component.zIndex = maxZ + 1
        }
    }

    /**
     * Renders all active HUD components in `OverlayRenderEvent` (when `inEditor == false`) or inside
     * `HudEditorTab` (when `inEditor == true`).
     */
    fun renderAll(ctx: GuiGraphicsExtractor, tickDelta: Float, inEditor: Boolean) {
        syncPaletteFromTheme()
        val dt = dtTracker.nextDeltaSeconds()

        val guiW = mc.window.guiScaledWidth.toFloat()
        val guiH = mc.window.guiScaledHeight.toFloat()
        val hudW = guiW * WEB_HUD_BASE_SCALE
        val hudH = guiH * WEB_HUD_BASE_SCALE
        val cssToGui = 1f / WEB_HUD_BASE_SCALE // 0.5f

        val ordered = components
            .filter { it.enabled }
            .sortedBy { it.zIndex }

        ModernGuiInterceptor.withClientHudScope {
            for (comp in ordered) {
                if (comp is MinimapHudComponent) {
                    comp.renderMinimapGuiSpace(ctx, tickDelta)
                    continue
                }

                val (hx, hy) = comp.computeHudPosition(comp.lastWidth, comp.lastHeight, hudW, hudH)
                comp.lastHudX = hx
                comp.lastHudY = hy

                val prevScale = UiDraw.currentCanvasScale
                UiDraw.currentCanvasScale = prevScale * cssToGui
                ctx.pose().pushMatrix()
                ctx.pose().scale(cssToGui, cssToGui)
                ctx.pose().translate(hx, hy)

                // Match the reference by component identity, not screen aspect ratio or text content.
                val profile = when (comp) {
                    is TargetHudComponent -> LightPollutionGlassSurface.Profile.TARGET
                    is BlockCounterHudComponent -> LightPollutionGlassSurface.Profile.COUNTER
                    else -> LightPollutionGlassSurface.Profile.SOFT
                }
                val measured = ModernGuiInterceptor.withClientHudScope(profile) {
                    comp.renderHud(ctx, tickDelta, dt, hudW, hudH, inEditor)
                }
                comp.lastWidth = measured.width
                comp.lastHeight = measured.height
                val (updatedX, updatedY) = comp.computeHudPosition(measured.width, measured.height, hudW, hudH)
                comp.lastHudX = updatedX
                comp.lastHudY = updatedY

                ctx.pose().popMatrix()
                UiDraw.currentCanvasScale = prevScale
            }
        }
    }
}
