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
package net.ccbluex.liquidbounce.ui.clickgui

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.config.ConfigSystem
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import net.ccbluex.liquidbounce.utils.client.logger

/**
 * Persistent UI layout store replacing 043's browser `localStorage` (`clickgui.tab` and
 * `clickgui.panel.<category>`).
 */
object ClickGuiLayoutStore {

    data class PanelConfig(
        var left: Float,
        var top: Float,
        var expanded: Boolean,
        var scrollTop: Float = 0f,
        var zIndex: Int = 0,
    )

    var activeTabIndex: Int = 0
    var maxPanelZIndex: Int = 0
    var highlightModuleName: String? = null
    var highlightTriggeredMs: Long = 0L

    private val panels = LinkedHashMap<String, PanelConfig>()
    private var loaded = false

    // Independent UI preferences, keyed by untranslated module names or full Value.key paths.
    // Never use translated labels or list indices: both change when sorting or switching language.
    private val widgetStates = LinkedHashMap<String, JsonObject>()

    fun booleanState(key: () -> String?, field: String, default: Boolean = false): ReadWriteProperty<Any?, Boolean> =
        object : ReadWriteProperty<Any?, Boolean> {
            private var fallback = default
            override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean {
                ensureLoaded()
                val id = key() ?: return fallback
                return runCatching {
                    widgetStates[id]?.get(field)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                }.getOrNull() ?: fallback
            }
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
                ensureLoaded()
                fallback = value
                key()?.let { widgetStates.getOrPut(it) { JsonObject() }.addProperty(field, value) }
            }
        }

    fun scrollState(key: () -> String?, field: String = "scrollTop"): ReadWriteProperty<Any?, Float> =
        object : ReadWriteProperty<Any?, Float> {
            private var fallback = 0f
            override fun getValue(thisRef: Any?, property: KProperty<*>): Float {
                ensureLoaded()
                val id = key() ?: return fallback
                return runCatching {
                    widgetStates[id]?.get(field)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asFloat
                }.getOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: fallback
            }
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) {
                ensureLoaded()
                fallback = if (value.isFinite()) value.coerceAtLeast(0f) else 0f
                key()?.let { widgetStates.getOrPut(it) { JsonObject() }.addProperty(field, fallback) }
            }
        }

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val storageFile: File
        get() = File(ConfigSystem.rootFolder, "ui_layout.json")

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val file = runCatching { storageFile }.getOrNull() ?: return
        if (!file.isFile) return

        runCatching {
            val root = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
            activeTabIndex = runCatching { root.get("activeTab")?.asInt?.coerceIn(0, 2) }.getOrNull() ?: 0
            root.get("widgetStates")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (key, state) ->
                if (state.isJsonObject) widgetStates[key] = state.asJsonObject.deepCopy()
            }
            val panelsObj = root.get("panels")?.takeIf { it.isJsonObject }?.asJsonObject
            if (panelsObj != null) {
                for ((cat, elem) in panelsObj.entrySet()) {
                    if (!elem.isJsonObject) continue
                    // One malformed entry must not discard the other saved panels.
                    runCatching {
                        val o = elem.asJsonObject
                        fun finite(name: String, default: Float): Float =
                            o.get(name)?.asFloat?.takeIf { it.isFinite() } ?: default
                        val cfg = PanelConfig(
                            left = finite("left", 20f),
                            top = finite("top", 20f),
                            expanded = o.get("expanded")?.asBoolean ?: false,
                            scrollTop = finite("scrollTop", 0f).coerceAtLeast(0f),
                            zIndex = o.get("zIndex")?.asInt ?: 0,
                        )
                        if (cfg.zIndex > maxPanelZIndex) {
                            maxPanelZIndex = cfg.zIndex
                        }
                        panels[cat] = cfg
                    }.onFailure { logger.warn("Skipping invalid ClickGUI panel '$cat'", it) }
                }
            }
        }.onFailure { logger.warn("Failed to load ClickGUI layout", it) }
    }

    fun panelFor(category: String, panelIndex: Int): PanelConfig {
        ensureLoaded()
        return panels.getOrPut(category) {
            // Matches 043 `Panel.svelte` default: `left: 20, top: panelIndex * 50 + 20, expanded: false`
            PanelConfig(
                left = 20f,
                top = panelIndex * 50f + 20f,
                expanded = false,
                scrollTop = 0f,
                zIndex = panelIndex,
            )
        }
    }

    fun opaiPanelFor(key: String, defaultX: Float, defaultY: Float, defaultExpanded: Boolean = true): PanelConfig {
        ensureLoaded()
        return panels.getOrPut(key) {
            PanelConfig(
                left = defaultX,
                top = defaultY,
                expanded = defaultExpanded,
                scrollTop = 0f,
                zIndex = 0,
            )
        }
    }

    fun triggerHighlight(moduleName: String) {
        highlightModuleName = moduleName
        highlightTriggeredMs = System.currentTimeMillis()
    }

    fun save() {
        // A save before constructing a panel must not overwrite an existing layout with empty maps.
        ensureLoaded()
        runCatching {
            val file = storageFile
            file.parentFile?.mkdirs()
            val root = JsonObject()
            root.addProperty("version", 2)
            root.addProperty("activeTab", activeTabIndex)
            val states = JsonObject()
            widgetStates.forEach { (key, state) -> states.add(key, state.deepCopy()) }
            root.add("widgetStates", states)
            val panelsObj = JsonObject()
            for ((cat, cfg) in panels) {
                val o = JsonObject()
                o.addProperty("left", cfg.left)
                o.addProperty("top", cfg.top)
                o.addProperty("expanded", cfg.expanded)
                o.addProperty("scrollTop", cfg.scrollTop)
                o.addProperty("zIndex", cfg.zIndex)
                panelsObj.add(cat, o)
            }
            root.add("panels", panelsObj)
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(gson.toJson(root), Charsets.UTF_8)
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { logger.warn("Failed to save ClickGUI layout", it) }
    }
}
