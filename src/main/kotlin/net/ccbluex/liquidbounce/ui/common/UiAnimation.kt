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

import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.exp

/**
 * Frame-rate-independent animation helpers and shared hover tooltip state for ClickGUI & HUD.
 */
object UiAnimation {

    class DeltaTracker {
        private var lastNs: Long = 0L

        fun nextDeltaSeconds(): Float {
            val now = System.nanoTime()
            val prev = lastNs
            lastNs = now
            if (prev == 0L) return 1f / 60f
            return ((now - prev) * 1e-9f).coerceIn(0.0005f, 0.1f)
        }
    }

    /**
     * Smooth exponential decay step independent of frame rate.
     */
    fun expLerp(current: Float, target: Float, speed: Float, dtSeconds: Float): Float {
        if (dtSeconds <= 0f) return current
        val factor = 1f - exp(-speed * dtSeconds.coerceAtMost(0.1f))
        val next = current + (target - current) * factor
        return if (kotlin.math.abs(target - next) < 0.0005f) target else next
    }

    fun easeOutCubic(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        val inv = 1f - c
        return 1f - inv * inv * inv
    }

    fun easeOutQuint(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        val inv = 1f - c
        return 1f - inv * inv * inv * inv * inv
    }
}

/**
 * Shared per-frame tooltip request matching 043's `Description.svelte` speech bubble.
 */
object UiTooltipManager {
    var pendingText: String? = null
        private set
    var anchorX: Float = 0f
        private set
    var anchorY: Float = 0f
        private set
    var preferLeft: Boolean = false
        private set

    fun beginFrame() {
        pendingText = null
    }

    fun request(text: String?, anchorRightX: Float, centerY: Float, fallbackLeftX: Float = anchorRightX, canvasWidth: Float = Float.MAX_VALUE) {
        if (text.isNullOrBlank()) return
        pendingText = text
        // Flip to left of element if overflowing right edge of viewport
        val estimatedWidth = text.length * 6.8f + 28f
        if (anchorRightX + 12f + estimatedWidth > canvasWidth && fallbackLeftX - 12f - estimatedWidth > 0f) {
            anchorX = fallbackLeftX
            preferLeft = true
        } else {
            anchorX = anchorRightX
            preferLeft = false
        }
        anchorY = centerY
    }

    fun render(ctx: GuiGraphicsExtractor, canvasWidth: Float = 4096f, canvasHeight: Float = 4096f) {
        UiDraw.drawPendingTooltip(ctx, canvasWidth, canvasHeight)
    }
}
