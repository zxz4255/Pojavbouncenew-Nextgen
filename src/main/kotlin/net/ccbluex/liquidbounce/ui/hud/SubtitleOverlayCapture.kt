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

import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.ClosedCaptionDirection
import net.ccbluex.liquidbounce.event.events.ClosedCaptionEntry
import net.ccbluex.liquidbounce.event.events.ClosedCaptionsEvent
import net.minecraft.network.chat.Component

/**
 * Captures vanilla `SubtitleOverlay` entries via `MixinSubtitleOverlay` and publishes
 * [ClosedCaptionsEvent] for `ClosedCaptionsHudComponent`.
 */
object SubtitleOverlayCapture {

    private var active = false
    private var pendingBackgroundColor: Int = 0
    private var pendingDirection: ClosedCaptionDirection = ClosedCaptionDirection.NONE
    private val captured = ArrayList<ClosedCaptionEntry>(8)

    @JvmStatic
    fun begin() {
        active = true
        pendingBackgroundColor = 0
        pendingDirection = ClosedCaptionDirection.NONE
        captured.clear()
    }

    @JvmStatic
    fun captureBackground(color: Int) {
        if (!active) return
        pendingBackgroundColor = color
        pendingDirection = ClosedCaptionDirection.NONE
    }

    @JvmStatic
    fun captureDirection(text: String) {
        if (!active) return
        pendingDirection = when (text.trim()) {
            "<" -> ClosedCaptionDirection.LEFT
            ">" -> ClosedCaptionDirection.RIGHT
            else -> ClosedCaptionDirection.NONE
        }
    }

    @JvmStatic
    fun captureSubtitle(text: Component, color: Int) {
        if (!active) return
        captured.add(
            ClosedCaptionEntry(
                text = text,
                direction = pendingDirection,
                textColor = color,
                backgroundColor = pendingBackgroundColor,
            )
        )
        pendingDirection = ClosedCaptionDirection.NONE
    }

    @JvmStatic
    fun shouldRenderVanilla(): Boolean =
        !HudComponentManager.isTweakEnabled(HudComponentTweak.DISABLE_SUBTITLE_OVERLAY)

    @JvmStatic
    fun publish() {
        if (!active) return
        EventManager.callEvent(ClosedCaptionsEvent(captured.toTypedArray()))
    }

    @JvmStatic
    fun end() {
        active = false
        captured.clear()
    }
}
