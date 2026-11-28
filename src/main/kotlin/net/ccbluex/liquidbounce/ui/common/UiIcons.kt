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

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import net.ccbluex.liquidbounce.utils.client.clientIdentifier
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.resources.Identifier

/**
 * Registry of all bundled UI and HUD icons rasterized from 043's theme assets.
 */
object UiIcons {

    // Watermark 2-layer logo
    val WATERMARK_FG: Identifier = clientIdentifier("textures/ui/hud/watermark_fg.png")
    val WATERMARK_BADGE: Identifier = clientIdentifier("textures/ui/hud/watermark_badge.png")

    // ClickGUI category & control icons
    val CATEGORY_CLIENT: Identifier = clientIdentifier("textures/ui/clickgui/icon-client.png")
    val CLIENT: Identifier = CATEGORY_CLIENT
    val CATEGORY_COMBAT: Identifier = clientIdentifier("textures/ui/clickgui/icon-combat.png")
    val CATEGORY_PLAYER: Identifier = clientIdentifier("textures/ui/clickgui/icon-player.png")
    val CATEGORY_MOVEMENT: Identifier = clientIdentifier("textures/ui/clickgui/icon-movement.png")
    val CATEGORY_RENDER: Identifier = clientIdentifier("textures/ui/clickgui/icon-render.png")
    val CATEGORY_WORLD: Identifier = clientIdentifier("textures/ui/clickgui/icon-world.png")
    val CATEGORY_MISC: Identifier = clientIdentifier("textures/ui/clickgui/icon-misc.png")
    val CATEGORY_EXPLOIT: Identifier = clientIdentifier("textures/ui/clickgui/icon-exploit.png")
    val CATEGORY_FUN: Identifier = clientIdentifier("textures/ui/clickgui/icon-fun.png")

    val CROSS: Identifier = clientIdentifier("textures/ui/clickgui/icon-cross.png")
    val DRAG: Identifier = clientIdentifier("textures/ui/clickgui/icon-drag.png")
    val OPEN_FILE: Identifier = clientIdentifier("textures/ui/clickgui/icon-open-file.png")
    val RESET: Identifier = clientIdentifier("textures/ui/clickgui/icon-reset.png")
    val SETTINGS_EXPAND: Identifier = clientIdentifier("textures/ui/clickgui/icon-settings-expand.png")
    val TICK: Identifier = clientIdentifier("textures/ui/clickgui/icon-tick.png")
    val TICK_CHECKED: Identifier = clientIdentifier("textures/ui/clickgui/icon-tick-checked.png")
    val LOCATE: Identifier = clientIdentifier("textures/ui/clickgui/icon-locate.png")
    val SEARCH: Identifier = clientIdentifier("textures/ui/clickgui/icon-search.png")

    // HUD Hotbar icons
    val HOTBAR_FOOD: Identifier = clientIdentifier("textures/ui/hud/hotbar/icon-food.png")
    val HOTBAR_HEART: Identifier = clientIdentifier("textures/ui/hud/hotbar/icon-heart.png")
    val HOTBAR_SHIELD: Identifier = clientIdentifier("textures/ui/hud/hotbar/icon-shield.png")

    // HUD KeyBinds icon
    val KEYBINDS: Identifier = clientIdentifier("textures/ui/hud/keybinds/icon-keybinds.png")

    // HUD Notification icons
    val NOTIFY_ERROR: Identifier = clientIdentifier("textures/ui/hud/notification/icon-error.png")
    val NOTIFY_INFO: Identifier = clientIdentifier("textures/ui/hud/notification/icon-info.png")
    val NOTIFY_SUCCESS: Identifier = clientIdentifier("textures/ui/hud/notification/icon-success.png")
    val NOTIFY_TOGGLE: Identifier = clientIdentifier("textures/ui/hud/notification/icon-toggle.png")

    // HUD TargetHud icons
    val TARGET_HEALTH: Identifier = clientIdentifier("textures/ui/hud/targethud/icon-health.png")
    val TARGET_ABSORPTION: Identifier = clientIdentifier("textures/ui/hud/targethud/icon-absorption.png")
    val TARGET_ARMOR: Identifier = clientIdentifier("textures/ui/hud/targethud/icon-armor.png")
    val TARGET_ARMOR_WHITE: Identifier = clientIdentifier("textures/ui/hud/targethud/icon-armor-white.png")
    val STEVE_FACE: Identifier = clientIdentifier("textures/ui/hud/targethud/steve.png")

    // HUD Taco frames (12 frames @ 70ms per frame)
    const val TACO_FRAME_COUNT = 12
    const val TACO_FRAME_DURATION_MS = 70L
    val TACO_FRAMES: List<Identifier> = (0 until TACO_FRAME_COUNT).map { idx ->
        clientIdentifier("textures/ui/hud/taco/frame_$idx.png")
    }

    fun tacoFrame(index: Int): Identifier = TACO_FRAMES[index.mod(TACO_FRAME_COUNT)]

    private val tabGuiCategoryIcons: Map<String, Identifier> = mapOf(
        "combat" to clientIdentifier("textures/ui/hud/tabgui/combat.png"),
        "player" to clientIdentifier("textures/ui/hud/tabgui/player.png"),
        "movement" to clientIdentifier("textures/ui/hud/tabgui/movement.png"),
        "render" to clientIdentifier("textures/ui/hud/tabgui/render.png"),
        "world" to clientIdentifier("textures/ui/hud/tabgui/world.png"),
        "misc" to clientIdentifier("textures/ui/hud/tabgui/misc.png"),
        "exploit" to clientIdentifier("textures/ui/hud/tabgui/exploit.png"),
        "fun" to clientIdentifier("textures/ui/hud/tabgui/fun.png"),
    )

    private val clickGuiCategoryIcons: Map<String, Identifier> = mapOf(
        "combat" to CATEGORY_COMBAT,
        "player" to CATEGORY_PLAYER,
        "movement" to CATEGORY_MOVEMENT,
        "render" to CATEGORY_RENDER,
        "world" to CATEGORY_WORLD,
        "misc" to CATEGORY_MISC,
        "exploit" to CATEGORY_EXPLOIT,
        "fun" to CATEGORY_FUN,
        "client" to CATEGORY_CLIENT,
    )

    // Intrinsic SVG aspect ratios (width / height) so non-square icons never stretch
    private val intrinsicAspects: Map<Identifier, Float> = mapOf(
        CATEGORY_COMBAT to (17f / 15f),
        CATEGORY_MOVEMENT to (16f / 15f),
        CATEGORY_PLAYER to (14f / 15f),
        CATEGORY_RENDER to (15f / 9f),
        SETTINGS_EXPAND to (10.061f / 6.091f),
        TICK to (10f / 7.324f),
        TICK_CHECKED to (10f / 7.324f),
        DRAG to (9f / 15f),
        NOTIFY_ERROR to (14f / 12f),
        NOTIFY_INFO to (7f / 17f),
        NOTIFY_SUCCESS to (17f / 12f),
        NOTIFY_TOGGLE to (18f / 10f),
        TARGET_ARMOR to (8f / 9f),
        TARGET_ARMOR_WHITE to (8f / 9f),
    )

    fun categoryIcon(categoryName: String): Identifier =
        clickGuiCategoryIcons[categoryName.lowercase()] ?: CATEGORY_CLIENT

    fun tabGuiIcon(categoryName: String): Identifier =
        tabGuiCategoryIcons[categoryName.lowercase()] ?: CATEGORY_CLIENT

    fun aspectRatio(id: Identifier): Float = intrinsicAspects[id] ?: 1f

    fun linearSetup(id: Identifier): TextureSetup? = runCatching {
        val tex = mc.textureManager.getTexture(id)
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        TextureSetup.singleTexture(tex.textureView, sampler)
    }.getOrNull()

    fun nearestSetup(id: Identifier): TextureSetup? = runCatching {
        val tex = mc.textureManager.getTexture(id)
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        TextureSetup.singleTexture(tex.textureView, sampler)
    }.getOrNull()
}
