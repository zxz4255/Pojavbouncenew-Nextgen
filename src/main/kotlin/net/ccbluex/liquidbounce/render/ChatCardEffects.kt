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

package net.ccbluex.liquidbounce.render

import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Surface variants understood by [ClientRenderPipelines.ChatCard] and `chat_card.fsh`. */
internal object ChatCardSurface {
    const val SHADOW = 0
    const val GLASS = 1
    const val MATERIAL = 2
}

/** Draws one antialiased rounded panel with the module's `.vsh`/`.fsh` shader pair. */
internal fun GuiGraphicsExtractor.drawChatCardSurface(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    radius: Float,
    color: Color4b,
    surface: Int = ChatCardSurface.SHADOW,
    strokeWidth: Float = 0f,
) {
    val left = minOf(x1, x2)
    val top = minOf(y1, y2)
    val right = maxOf(x1, x2)
    val bottom = maxOf(y1, y2)
    val width = right - left
    val height = bottom - top
    if (width <= 0f || height <= 0f || color.a <= 0) return

    val encodedWidth = width.roundToInt().coerceIn(1, 32767)
    val encodedHeight = height.roundToInt().coerceIn(1, 32767)
    val encodedRadius = radius.coerceIn(0f, minOf(width, height) * 0.5f).roundToInt().coerceIn(0, 32767)
    val argb = color.argb

    drawCustomElement(
        pipeline = ClientRenderPipelines.ChatCard,
        bounds = getBounds(left, top, right, bottom),
    ) { pose ->
        addVertexWith2DPose(pose, left, top).setUv(0f, 0f).setColor(argb)
            .setUv1(encodedWidth, encodedHeight).setUv2(encodedRadius, surface).setLineWidth(strokeWidth)
        addVertexWith2DPose(pose, left, bottom).setUv(0f, 1f).setColor(argb)
            .setUv1(encodedWidth, encodedHeight).setUv2(encodedRadius, surface).setLineWidth(strokeWidth)
        addVertexWith2DPose(pose, right, bottom).setUv(1f, 1f).setColor(argb)
            .setUv1(encodedWidth, encodedHeight).setUv2(encodedRadius, surface).setLineWidth(strokeWidth)
        addVertexWith2DPose(pose, right, top).setUv(1f, 0f).setColor(argb)
            .setUv1(encodedWidth, encodedHeight).setUv2(encodedRadius, surface).setLineWidth(strokeWidth)
    }
}

/** One-pass analytic Gaussian shadow around a rounded rectangle, using the existing Onyx shader. */
internal fun GuiGraphicsExtractor.drawSoftRoundedShadow(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    radius: Float,
    sigma: Float,
    color: Color4b,
) {
    if (sigma <= 0f || color.a <= 0) return

    val left = minOf(x1, x2)
    val top = minOf(y1, y2)
    val right = maxOf(x1, x2)
    val bottom = maxOf(y1, y2)
    val halfWidth = (right - left) * 0.5f
    val halfHeight = (bottom - top) * 0.5f
    if (halfWidth <= 0f || halfHeight <= 0f) return

    val centerX = left + halfWidth
    val centerY = top + halfHeight
    val reach = sigma * 3f
    val quadLeft = left - reach
    val quadTop = top - reach
    val quadRight = right + reach
    val quadBottom = bottom + reach
    val encodedHalfWidth = ceil(halfWidth * 8f).toInt().coerceIn(1, 32767)
    val encodedHalfHeight = ceil(halfHeight * 8f).toInt().coerceIn(1, 32767)
    val encodedRadius = (radius.coerceIn(0f, minOf(halfWidth, halfHeight)) * 8f).roundToInt().coerceIn(0, 32767)
    val argb = color.argb

    drawCustomElement(
        pipeline = ClientRenderPipelines.OnyxSoftShadow,
        bounds = getBounds(quadLeft, quadTop, quadRight, quadBottom),
    ) { pose ->
        addVertexWith2DPose(pose, quadLeft, quadTop).setUv(quadLeft - centerX, quadTop - centerY).setColor(argb)
            .setUv1(encodedHalfWidth, encodedHalfHeight).setUv2(encodedRadius, 0).setLineWidth(sigma)
        addVertexWith2DPose(pose, quadLeft, quadBottom).setUv(quadLeft - centerX, quadBottom - centerY).setColor(argb)
            .setUv1(encodedHalfWidth, encodedHalfHeight).setUv2(encodedRadius, 0).setLineWidth(sigma)
        addVertexWith2DPose(pose, quadRight, quadBottom).setUv(quadRight - centerX, quadBottom - centerY).setColor(argb)
            .setUv1(encodedHalfWidth, encodedHalfHeight).setUv2(encodedRadius, 0).setLineWidth(sigma)
        addVertexWith2DPose(pose, quadRight, quadTop).setUv(quadRight - centerX, quadTop - centerY).setColor(argb)
            .setUv1(encodedHalfWidth, encodedHalfHeight).setUv2(encodedRadius, 0).setLineWidth(sigma)
    }
}

internal fun Color4b.withAlphaScale(factor: Float): Color4b =
    Color4b(r, g, b, (a * factor.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255))
