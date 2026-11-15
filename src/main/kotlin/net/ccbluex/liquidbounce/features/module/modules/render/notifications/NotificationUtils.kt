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

import net.ccbluex.liquidbounce.features.addon.AddonApi
import net.ccbluex.liquidbounce.utils.client.clientLogger

/**
 * Port of Solstice's `NotifyUtils` (`Utils/MiscUtils/NotifyUtils.{hpp,cpp}`).
 *
 * Solstice triggers a `NotifyEvent` on its dispatcher, which the `Notifications` module listens to.
 * LiquidBounce already has such an event (`NotificationEvent`) and the `notification(...)` helpers, so
 * pushing a notification works either way:
 *
 * ```kotlin
 * // Solaris style, warning:
 * NotificationUtils.warn("Config could not be loaded")
 *
 * // or through the regular client notification pipeline, which this module also renders:
 * notification("Auto Config", "Loaded", NotificationEvent.Severity.INFO)
 * ```
 */
@AddonApi
object NotificationUtils {

    private val logger = clientLogger("NotifyUtils")

    @JvmStatic
    @JvmOverloads
    fun notify(message: String, duration: Float = 3f, type: NotificationType = NotificationType.INFO) {
        logger.info("[NotifyUtils] Notified: $message")
        ModuleNotifications.push(message, type, duration)
    }

    @JvmStatic
    @JvmOverloads
    fun info(message: String, duration: Float = 3f) =
        notify(message, duration, NotificationType.INFO)

    @JvmStatic
    @JvmOverloads
    fun warn(message: String, duration: Float = 3f) =
        notify(message, duration, NotificationType.WARNING)

    @JvmStatic
    @JvmOverloads
    fun error(message: String, duration: Float = 3f) =
        notify(message, duration, NotificationType.ERROR)

}
