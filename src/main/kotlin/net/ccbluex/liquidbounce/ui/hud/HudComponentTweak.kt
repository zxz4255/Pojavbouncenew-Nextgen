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

import net.ccbluex.liquidbounce.config.types.list.Tagged

/**
 * Tweaks declared by [HudComponent]s to suppress or replace vanilla HUD elements when active.
 */
enum class HudComponentTweak(override val tag: String) : Tagged {
    TWEAK_HOTBAR("TWEAK_HOTBAR"),
    DISABLE_STATUS_BAR("DISABLE_STATUS_BAR"),
    DISABLE_EXP_BAR("DISABLE_EXP_BAR"),
    DISABLE_HELD_ITEM_TOOL_TIP("DISABLE_HELD_ITEM_TOOL_TIP"),
    DISABLE_OVERLAY_MESSAGE("DISABLE_OVERLAY_MESSAGE"),
    DISABLE_LOCATOR_BAR("DISABLE_LOCATOR_BAR"),
    DISABLE_SCOREBOARD("DISABLE_SCOREBOARD"),
    DISABLE_STATUS_EFFECT_OVERLAY("DISABLE_STATUS_EFFECT_OVERLAY"),
    DISABLE_SUBTITLE_OVERLAY("DISABLE_SUBTITLE_OVERLAY"),
    DISABLE_CROSSHAIR("DISABLE_CROSSHAIR"),
}
