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

package net.ccbluex.liquidbounce.features.module.modules.render.notifications

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.minecraft.util.Mth

/**
 * Port of Solstice's `Notification` (`Features/Events/NotifyEvent.hpp`).
 */
enum class NotificationType(override val tag: String) : Tagged {
    INFO("Info"),
    WARNING("Warning"),
    ERROR("Error"),
}

/**
 * One notification on screen.
 *
 * Timing and animation are the ones of the original:
 *
 * - [timeShown] is accumulated by the frame delta while the module is enabled,
 * - [isTimeUp] flips once [duration] is reached, which makes [animation] slide the box back out,
 * - the entry itself is only dropped three seconds after that, so the slide out is visible.
 */
class NotificationEntry(
    @JvmField val message: String,
    @JvmField val type: NotificationType,
    /** Seconds the notification stays fully visible. */
    @JvmField val duration: Float,
) {

    /** Time this notification has been on screen, in seconds. */
    @JvmField
    var timeShown: Float = 0f

    @JvmField
    var isTimeUp: Boolean = false

    /**
     * Solstice's `mCurrentDuration`: `1` = fully shown, `0` = fully hidden.
     */
    @JvmField
    var animation: Float = 0f

    /**
     * Solstice's `getPercentComplete`, clamped like the original does before drawing.
     */
    val percentComplete: Float
        get() = if (duration <= 0f) 1f else (timeShown / duration).coerceIn(0f, 1f)

    fun tick(delta: Float, animationSpeed: Float) {
        if (delta <= 0f) {
            return
        }

        timeShown += delta
        isTimeUp = timeShown >= duration
        animation = Mth.lerp(delta * animationSpeed, animation, if (isTimeUp) 0f else 1f).coerceIn(0f, 1f)
    }

}
