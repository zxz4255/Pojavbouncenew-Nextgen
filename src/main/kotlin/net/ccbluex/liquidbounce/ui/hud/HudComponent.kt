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

import net.ccbluex.liquidbounce.config.gson.stategies.Exclude
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.features.misc.SelfDestruct.isDestructed
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHud
import net.ccbluex.liquidbounce.render.engine.type.BoundingBox2f
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.render.Alignment
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.UUID

data class HudSize(val width: Float, val height: Float)

/**
 * Base HUD component matching 043's `HudComponent` contract.
 *
 * Theme HUD components draw in 043's `WEB_HUD_BASE_SCALE = 2f` CSS coordinate space
 * (`1 HUD CSS px = 0.5` MC GUI units; virtual HUD viewport is `guiScaledWidth * 2f` by `guiScaledHeight * 2f`).
 */
abstract class HudComponent(
    name: String,
    enabledByDefault: Boolean,
    defaultAlignment: Alignment,
    @Exclude val componentDescription: String = "",
    @Exclude val singleton: Boolean = true,
    @Exclude val tweaks: Array<HudComponentTweak> = emptyArray(),
) : ToggleableValueGroup(ModuleHud, name, enabledByDefault), EventListener, MinecraftShortcuts {

    @Exclude
    val id: UUID = UUID.randomUUID()

    val alignment: Alignment = tree(defaultAlignment)

    var zIndex: Int by int("ZIndex", 0, -1000..1000).apply {
        notAnOption()
    }

    @Exclude
    var lastWidth: Float = 60f

    @Exclude
    var lastHeight: Float = 24f

    @Exclude
    var lastHudX: Float = 0f

    @Exclude
    var lastHudY: Float = 0f

    /**
     * Some components (`BlockCounter`) use `transform: translate(-100%, 0)` in 043 CSS.
     */
    open val translateMinusFullWidth: Boolean get() = false

    override val running: Boolean
        get() = ModuleHud.running && this.enabled && inGame && !isDestructed

    fun hasTweak(tweak: HudComponentTweak): Boolean = tweak in tweaks

    /**
     * Resolves top-left `(x, y)` in HUD CSS pixels (`width = guiScaledWidth * 2f`, `height = guiScaledHeight * 2f`),
     * matching 043's `DraggableComponent.svelte` `generateStyleString(alignment)`.
     */
    fun computeHudPosition(elemW: Float, elemH: Float, hudW: Float, hudH: Float): Pair<Float, Float> {
        val rawX = when (alignment.horizontalAlignment) {
            Alignment.ScreenAxisX.LEFT -> alignment.horizontalOffset.toFloat()
            Alignment.ScreenAxisX.RIGHT -> hudW - elemW - alignment.horizontalOffset.toFloat()
            Alignment.ScreenAxisX.CENTER -> hudW * 0.5f + alignment.horizontalOffset.toFloat()
            Alignment.ScreenAxisX.CENTER_TRANSLATED -> hudW * 0.5f - elemW * 0.5f + alignment.horizontalOffset.toFloat()
        }
        val rawY = when (alignment.verticalAlignment) {
            Alignment.ScreenAxisY.TOP -> alignment.verticalOffset.toFloat()
            Alignment.ScreenAxisY.BOTTOM -> hudH - elemH - alignment.verticalOffset.toFloat()
            Alignment.ScreenAxisY.CENTER -> hudH * 0.5f + alignment.verticalOffset.toFloat()
            Alignment.ScreenAxisY.CENTER_TRANSLATED -> hudH * 0.5f - elemH * 0.5f + alignment.verticalOffset.toFloat()
        }
        val finalX = if (translateMinusFullWidth) rawX - elemW else rawX
        return finalX to rawY
    }

    /**
     * Renders the component at local `(0, 0)` in HUD CSS coordinate space (already translated & scaled by `0.5f`),
     * returning its measured `(width, height)` in HUD CSS pixels.
     */
    abstract fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize
}

/**
 * Native MC-GUI-space HUD component (such as `MinimapHudComponent`) matching `ReadOnlyComponentSerializer`.
 */
abstract class NativeHudComponent(
    name: String,
    enabledByDefault: Boolean,
    defaultAlignment: Alignment,
    description: String = "",
    tweaks: Array<HudComponentTweak> = emptyArray(),
) : HudComponent(
    name = name,
    enabledByDefault = enabledByDefault,
    defaultAlignment = defaultAlignment,
    componentDescription = description,
    singleton = true,
    tweaks = tweaks,
) {
    abstract val guiScaledWidth: Float
    abstract val guiScaledHeight: Float

    val width: Float get() = guiScaledWidth * HudComponentManager.WEB_HUD_BASE_SCALE
    val height: Float get() = guiScaledHeight * HudComponentManager.WEB_HUD_BASE_SCALE

    fun getGuiScaledBounds(w: Float = guiScaledWidth, h: Float = guiScaledHeight): BoundingBox2f {
        val hudW = mc.window.guiScaledWidth * HudComponentManager.WEB_HUD_BASE_SCALE
        val hudH = mc.window.guiScaledHeight * HudComponentManager.WEB_HUD_BASE_SCALE
        val (hx, hy) = computeHudPosition(
            w * HudComponentManager.WEB_HUD_BASE_SCALE,
            h * HudComponentManager.WEB_HUD_BASE_SCALE,
            hudW,
            hudH,
        )
        val gx = hx / HudComponentManager.WEB_HUD_BASE_SCALE
        val gy = hy / HudComponentManager.WEB_HUD_BASE_SCALE
        return BoundingBox2f(gx, gy, gx + w, gy + h)
    }

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize = HudSize(width, height)
}
