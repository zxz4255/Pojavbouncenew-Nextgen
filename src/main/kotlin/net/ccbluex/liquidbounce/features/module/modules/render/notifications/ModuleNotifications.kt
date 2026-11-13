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
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.ServerConnectEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.arraylist.ArraylistColors
import net.ccbluex.liquidbounce.features.module.modules.render.arraylist.drawGlow
import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.utils.client.clientIdentifier
import net.ccbluex.liquidbounce.utils.client.clientLogger
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.scaledDimension
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FIRST_PRIORITY
import net.ccbluex.liquidbounce.utils.text.withFormat
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.util.Mth
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Port of Solstice's `Notifications` module (`Features/Modules/Visual/Notifications.cpp`).
 *
 * The Solaris style is reproduced as it is:
 *
 * - boxes are anchored to the bottom right corner and stack upwards, each one pushing the ones
 *   before it up by its own height,
 * - every box slides in from the right edge with `lerp(anim, target, delta * 5)` and slides back out
 *   once its duration is over, three seconds later it is dropped,
 * - the box is split by an elapsed-time boundary that moves to the right: the elapsed part is filled
 *   with the theme color (or a horizontal gradient when `ColorGradient` is on), the remaining part is
 *   translucent black; both halves are clipped exactly like the original does with `PushClipRect`,
 * - the message is drawn white in the top left corner of the box,
 * - `ShowOnToggle` (module toggle) and `ShowOnJoin` (server connect) feed it like the original.
 *
 * The shadow of the original (`AddShadowRect`) is drawn with the same analytic gaussian quad the
 * arraylist uses - one draw call, no stacked glow layers. Because of that this module requires the
 * arraylist port (`drawGlow`) to be installed.
 */
@Suppress("TooManyFunctions")
object ModuleNotifications : ClientModule("Notifications", ModuleCategories.RENDER, state = true) {

    /**
     * Solstice's `Notifications::Style`. Solaris is currently the only style there as well.
     */
    enum class Style(override val tag: String) : Tagged {
        SOLARIS("Solaris"),
    }

    /**
     * The original draws its text at `fontSize = 20` physical pixels, which is 10 GUI units at the
     * vanilla GUI scale of 2. Everything else below is derived from it.
     */
    private const val FONT_SIZE = 10f

    /**
     * Default lifetime of a notification, in seconds. The original used 3, which on a phone is barely
     * enough to read a module name plus a message, so the default is longer and exposed as a setting.
     */
    private const val DEFAULT_DURATION = 5.5f

    /**
     * Corner radius of the notification box, in Solstice pixels. 5 was the original rounded look,
     * 1 gives the near square corners this build ships with.
     */
    private const val DEFAULT_CORNER_RADIUS = 1f

    /**
     * Minimum box width in Solstice pixels (200 in the original), so long messages get a wider bar
     * instead of wrapping or being cut off.
     */
    private const val DEFAULT_MIN_WIDTH = 240f

    /**
     * Horizontal padding between the box edge and the text, in Solstice pixels. 4 was the original.
     */
    private const val DEFAULT_TEXT_PADDING = 12f

    /**
     * `delta * 5` in the original.
     */
    private const val ANIMATION_SPEED = 5f

    /**
     * The original keeps a notification three seconds after it expired, so that the slide out is seen.
     */
    private const val REMOVE_DELAY = 3f

    /**
     * Safety net for the case that nobody renders the list (module paused, no world, ...).
     */
    private const val MAX_STORED = 64

    private val style by enumChoice("Style", Style.SOLARIS)

    private val showOnToggle by boolean("ShowOnToggle", true)
    private val toggleSound by boolean("ToggleSound", true)
    private val toggleSoundVolume by float("ToggleSoundVolume", 0.6f, 0f..1f)
    private val showOnJoin by boolean("ShowOnJoin", true)

    private enum class ColorSource(override val tag: String) : Tagged {
        ORIGINAL("Original"), CUSTOM("Custom"),
    }
    // Original keeps the existing palette and warning/error colours. Custom applies to every type.
    private val colorSource by enumChoice("ColorSource", ColorSource.ORIGINAL)
    private val customColor by color("CustomColor", Color4b(0x5B, 0x8C, 0xFF, 179))
    private val gradientEndColor by color("GradientEndColor", Color4b(0xB4, 0x66, 0xFF, 179))
    private val backgroundColor by color("BackgroundColor", Color4b(0, 0, 0, 179))
    private val textColor by color("TextColor", Color4b.WHITE)
    private val colorGradientFill by boolean("ColorGradient", false)

    /**
     * Lifetime of one notification in seconds.
     */
    private val duration by float("Duration", DEFAULT_DURATION, 1f..20f, "s")

    /**
     * Corner radius in Solstice pixels: `1` is almost square, `5` is the original rounded box.
     */
    private val cornerRadius by float("CornerRadius", DEFAULT_CORNER_RADIUS, 0f..12f, "px")

    /**
     * Minimum box width in Solstice pixels. The box still grows with the message.
     */
    private val minWidth by float("MinWidth", DEFAULT_MIN_WIDTH, 120f..420f, "px")

    /**
     * Horizontal padding between the box edge and the text, in Solstice pixels.
     */
    private val textPadding by float("TextPadding", DEFAULT_TEXT_PADDING, 4f..40f, "px")

    private val limitNotifications by boolean("LimitNotifications", false)
    private val maxNotifications by int("MaxNotifications", 6, 1..25)

    private val colors = tree(ArraylistColors())

    override val tag: String?
        get() = style.tag

    private val notifications = CopyOnWriteArrayList<NotificationEntry>()
    private val soundLogger = clientLogger("NotificationSounds")
    private val enabledSound = SoundEvent.createVariableRangeEvent(clientIdentifier("notification_toggle_enable"))
    private val disabledSound = SoundEvent.createVariableRangeEvent(clientIdentifier("notification_toggle_disable"))

    // Use Minecraft's UI sound engine rather than Java Sound, which is unreliable on Android.
    // Called only for shown module-toggle notifications; join/info/warning messages are silent.
    private fun playToggleSound(enabled: Boolean) {
        if (!toggleSound || toggleSoundVolume <= 0f) return
        val sound = if (enabled) enabledSound else disabledSound
        val volume = toggleSoundVolume
        mc.execute {
            runCatching { mc.soundManager.play(SimpleSoundInstance.forUI(sound, 1f, volume)) }
                .onFailure { soundLogger.warn("Could not play notification toggle sound", it) }
        }
    }

    private var lastFrameNanos = 0L

    override fun onDisabled() {
        notifications.clear()
    }

    /**
     * Solstice's `NotifyUtils::notify`, reachable from other modules, addons and scripts.
     */
    fun push(message: String, type: NotificationType = NotificationType.INFO, duration: Float = this.duration) {
        if (!enabled) {
            return
        }

        while (notifications.size >= MAX_STORED) {
            notifications.removeAt(0)
        }

        notifications += NotificationEntry(message, type, duration)
    }

    /**
     * Maps a length given in Solstice (physical) pixels into GUI units, keeping the proportions of
     * the original.
     */
    private fun unit(value: Float): Float = value * 0.5f * (FONT_SIZE / 10f)

    private fun frameDelta(): Float {
        val now = System.nanoTime()
        val last = lastFrameNanos
        lastFrameNanos = now
        if (last == 0L) {
            return 0f
        }

        return ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent>(priority = FIRST_PRIORITY) { event ->
        if (!inGame || notifications.isEmpty()) {
            return@handler
        }

        val delta = frameDelta()
        val now = System.currentTimeMillis()

        for (notification in notifications) {
            notification.tick(delta, ANIMATION_SPEED)
        }
        notifications.removeIf { it.isTimeUp && it.timeShown > it.duration + REMOVE_DELAY }

        val font = FontManager.FONT_RENDERER
        val textScale = FONT_SIZE / font.height
        val textHeight = font.height * textScale
        val (screenWidth, screenHeight) = mc.window.scaledDimension

        // Near square corners by default; `CornerRadius` restores the rounded original look.
        val rounding = unit(cornerRadius)

        var y = screenHeight - unit(10f)
        var counted = 0

        // `with(event.context)` supplies the `GuiGraphicsExtractor` context argument of the draw
        // helpers as well as the implicit receiver of the `GuiGraphicsExtractor` extensions.
        with(event.context) {
            for (notification in notifications) {
                if (limitNotifications && counted > maxNotifications) {
                    break
                }

                val text = notification.message.withFormat()
                val textWidth = font.getStringWidth(text) * textScale

                // Box sizing of the original, widened: at least `MinWidth` px, text + padding, height + 30px.
                val boxWidth = maxOf(unit(minWidth), unit(50f) + textWidth)
                val boxHeight = textHeight + unit(30f)

                // `boxSize.y - 10` in the original: the visual height of the box.
                val visualHeight = boxHeight - unit(10f)

                val beginX = screenWidth - boxWidth - unit(10f)
                val endX = screenWidth + boxWidth
                val x = Mth.lerp(notification.animation, endX, beginX)
                y = Mth.lerp(notification.animation, y, y - boxHeight)

                if (x > screenWidth + boxWidth && y > screenHeight + boxHeight) {
                    continue
                }

                val percentDone = notification.percentComplete
                val boxBottom = y + visualHeight
                val maxX = x + boxWidth
                val boundary = x + boxWidth * percentDone

                // Theme color of the row, warnings and errors override it like in the original.
                val themed = colors.color(y * 2f, now)
                val fillColor = if (colorSource == ColorSource.CUSTOM) customColor else when (notification.type) {
                    NotificationType.INFO -> themed.alpha(179)
                    NotificationType.WARNING -> Color4b(255, 204, 0, 179)
                    NotificationType.ERROR -> Color4b(255, 0, 0, 179)
                }

                // 1. the shadow / glow of the elapsed part, analytically evaluated in one draw call
                drawGlow(
                    x1 = x,
                    y1 = y,
                    x2 = (boundary + unit(6f)).coerceAtMost(maxX),
                    y2 = boxBottom,
                    color = if (colorSource == ColorSource.CUSTOM) fillColor else fillColor.alpha(255),
                    sigma = unit(50f) / 6f,
                )

                // 2. elapsed part, clipped to the elapsed region like `PushClipRect` does
                withNotificationClip(x, y, boundary, boxBottom) {
                    if (colorGradientFill) {
                        // The original uses a horizontal gradient here and notes that it loses the
                        // rounding; with near square corners that difference is gone anyway.
                        if (colorSource == ColorSource.CUSTOM) {
                            // Keep the full two-stop gradient stationary across the card. The
                            // elapsed clip reveals it rather than stretching as progress changes.
                            drawHorizontalGradient(x, y, maxX, boxBottom, fillColor, gradientEndColor)
                        } else {
                            val second = colors.color(y * 2f + (x - boundary) * 1.2f, now).alpha(179)
                            drawHorizontalGradient(x, y, boundary + unit(6f), boxBottom, fillColor, second)
                        }
                    } else {
                        drawRoundedRect(
                            x1 = x,
                            y1 = y,
                            x2 = boundary + unit(6f),
                            y2 = boxBottom,
                            radius = rounding,
                            fillColor = fillColor,
                        )
                    }
                }

                // 3. remaining part, translucent black, clipped to the remaining region
                withNotificationClip(boundary, y, maxX, boxBottom) {
                    drawRoundedRect(
                        x1 = boundary - unit(6f),
                        y1 = y,
                        x2 = maxX,
                        y2 = boxBottom,
                        radius = rounding,
                        fillColor = backgroundColor,
                    )
                }

                // 4. the message
                font.draw(text) {
                    this.x = x + unit(textPadding)
                    this.y = y + unit(10f)
                    color = textColor
                    shadow = false
                    scale = textScale
                }

                counted++
            }
        }
    }

    @Suppress("unused")
    private val notificationHandler = handler<NotificationEvent> { event ->
        val type = when (event.severity) {
            NotificationEvent.Severity.ERROR -> NotificationType.ERROR
            else -> NotificationType.INFO
        }

        when (event.severity) {
            NotificationEvent.Severity.ENABLED -> {
                if (showOnToggle) {
                    push("${event.message} was enabled", NotificationType.INFO, duration)
                    if (!event.message.equals(name, ignoreCase = true)) playToggleSound(true)
                }
            }

            NotificationEvent.Severity.DISABLED -> {
                if (showOnToggle) {
                    push("${event.message} was disabled", NotificationType.INFO, duration)
                    if (!event.message.equals(name, ignoreCase = true)) playToggleSound(false)
                }
            }

            else -> push(event.title.ifBlank { event.message }.let {
                if (it == event.message) it else "$it: ${event.message}"
            }, type, duration)
        }
    }

    @Suppress("unused")
    private val connectHandler = handler<ServerConnectEvent> { event ->
        if (showOnJoin) {
            push("Connecting to ${event.address}...", NotificationType.INFO, duration + 1.5f)
        }
    }

}

/**
 * A notification can start or end completely outside the screen. In 26.3 the input rectangle
 * can have a positive size while ScissorStack.push intersects it with the screen or parent and
 * stores an empty rectangle. Do not submit draws under that empty scissor; always balance push/pop.
 */
private inline fun GuiGraphicsExtractor.withNotificationClip(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    draw: () -> Unit,
) {
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
        right <= left || bottom <= top
    ) return
    val bounds = getBounds(left, top, right, bottom)
    if (bounds.width <= 0 || bounds.height <= 0) return
    val stack = scissorStack
    stack.push(bounds)
    try {
        val actual = stack.peek()
        if (actual != null && actual.width > 0 && actual.height > 0) draw()
    } finally {
        stack.pop()
    }
}

/**
 * Fills a rect with a horizontal gradient, left to right. Used by the `ColorGradient` setting, which
 * replaces the rounded fill of the original.
 */
private fun GuiGraphicsExtractor.drawHorizontalGradient(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    left: Color4b,
    right: Color4b,
) {
    if (x2 <= x1 || y2 <= y1) {
        return
    }

    val leftArgb = left.argb
    val rightArgb = right.argb

    drawCustomElement(
        pipeline = RenderPipelines.GUI,
        bounds = getBounds(x1, y1, x2, y2),
    ) { pose ->
        addVertexWith2DPose(pose, x1, y1).setColor(leftArgb)
        addVertexWith2DPose(pose, x1, y2).setColor(leftArgb)
        addVertexWith2DPose(pose, x2, y2).setColor(rightArgb)
        addVertexWith2DPose(pose, x2, y1).setColor(rightArgb)
    }
}
