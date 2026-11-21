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

import net.minecraft.client.gui.GuiGraphicsExtractor

/** Applies a component-local scale while keeping its screen anchor fixed. */
object HudComponentScaling {

    @JvmStatic
    fun withAnchor(
        graphics: GuiGraphicsExtractor,
        scale: Float,
        anchorX: Float,
        anchorY: Float,
        draw: Runnable,
    ) {
        val safeScale = if (scale.isFinite()) scale.coerceIn(0.25F, 4.0F) else 1.0F
        val pose = graphics.pose()
        pose.pushMatrix()
        try {
            if (safeScale != 1.0F) {
                pose.translate(anchorX, anchorY)
                pose.scale(safeScale, safeScale)
                pose.translate(-anchorX, -anchorY)
            }
            draw.run()
        } finally {
            pose.popMatrix()
        }
    }
}
