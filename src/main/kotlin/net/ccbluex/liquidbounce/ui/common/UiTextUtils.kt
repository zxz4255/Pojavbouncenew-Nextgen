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
package net.ccbluex.liquidbounce.ui.common

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHud
import net.ccbluex.liquidbounce.utils.input.InputBind

/**
 * Exact naming and keybind formatting utilities ported from 043's `theme_config.ts` and `key_display`.
 */
object UiTextUtils {

    // Exact regex from 043 `src-theme/src/theme/theme_config.ts`:
    // /[A-Z]?[a-z]+|[A-Z]+[0-9]+|[0-9]+|[A-Z]+(?![a-z])/g
    private val SPACED_WORD_REGEX = Regex("[A-Z]?[a-z]+|[A-Z]+[0-9]+|[0-9]+|[A-Z]+(?![a-z])")

    fun convertToSpacedString(name: String): String {
        if (name.isEmpty() || name.contains(' ')) return name
        val words = SPACED_WORD_REGEX.findAll(name).map { it.value }.toList()
        return if (words.isEmpty()) name else words.joinToString(" ")
    }

    fun formatEnglishName(name: String): String =
        if (ModuleHud.spaceSeperatedNames) convertToSpacedString(name) else name

    fun displayName(name: String): String =
        UiLocalization.displayName(name)

    fun formatKeyName(key: InputConstants.Key): String {
        if (key == InputConstants.UNKNOWN) {
            return UiLocalization.text("None", "无")
        }

        val rawName = key.name
        if (key.type == InputConstants.Type.MOUSE) {
            return when (key.value) {
                InputConstants.MOUSE_BUTTON_LEFT -> UiLocalization.text("LMB", "左键")
                InputConstants.MOUSE_BUTTON_RIGHT -> UiLocalization.text("RMB", "右键")
                InputConstants.MOUSE_BUTTON_MIDDLE -> UiLocalization.text("MMB", "中键")
                else -> UiLocalization.text("Mouse ${key.value}", "鼠标${key.value}")
            }
        }

        val shortKey = rawName
            .removePrefix("key.keyboard.")
            .removePrefix("key.mouse.")
        return when (shortKey) {
            "left.shift" -> "LShift"
            "right.shift" -> "RShift"
            "left.control" -> "LCtrl"
            "right.control" -> "RCtrl"
            "left.alt" -> "LAlt"
            "right.alt" -> "RAlt"
            "left.win" -> "Super"
            "right.win" -> "RSuper"
            "caps.lock" -> "CapsLock"
            "grave.accent" -> "`"
            "space" -> "Space"
            "escape" -> "Esc"
            "enter", "keypad.enter" -> "Enter"
            "backspace" -> "Backspace"
            "tab" -> "Tab"
            "delete" -> "Del"
            "insert" -> "Ins"
            "page.up" -> "PgUp"
            "page.down" -> "PgDn"
            "home" -> "Home"
            "end" -> "End"
            "up" -> "Up"
            "down" -> "Down"
            "left" -> "Left"
            "right" -> "Right"
            else -> {
                val localized = runCatching { key.displayName.string }.getOrNull()
                if (!localized.isNullOrBlank() && !localized.startsWith("key.")) {
                    localized
                } else {
                    shortKey.split('.').joinToString(" ") { part ->
                        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }
                }
            }
        }
    }

    fun formatInputBind(bind: InputBind): String {
        if (bind.isUnbound) {
            return UiLocalization.text("None", "无")
        }
        val modPrefix = if (bind.modifiers.isEmpty()) {
            ""
        } else {
            bind.modifiers.joinToString("+") { mod ->
                mod.platformRenderName
            } + "+"
        }
        return modPrefix + formatKeyName(bind.boundKey)
    }
}
