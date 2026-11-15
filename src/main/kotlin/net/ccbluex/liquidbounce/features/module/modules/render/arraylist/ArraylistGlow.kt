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

package net.ccbluex.liquidbounce.features.module.modules.render.arraylist

import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.utils.math.ceilToInt
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * How far the glow quad has to reach past the rect for the tail to be invisible. `3 sigma` covers
 * 99.7% of a gaussian.
 */
private const val GLOW_REACH_SIGMA = 3f

/**
 * Analytic glow, the replacement for Solstice's `ImDrawList::AddShadowRect` calls.
 *
 * Most ports of that effect stack a handful of scaled, alpha faded copies of the rect on top of each
 * other until it "looks soft". That is neither correct (the result depends on the number of layers)
 * nor cheap. Here the glow is a single quad whose fragment shader evaluates the exact gaussian
 * coverage of the rectangle in closed form, and the palette shader
 * (`shaders/gui/arraylist_glow2.fsh`) additionally disperses the red, green and blue channels by a
 * couple of percent of sigma and boosts the coverage right around the shape edge, so a saturated
 * scheme colour gets a faint prismatic fringe instead of the flat monochrome halo every client has.
 *
 * @param sigma gaussian sigma in GUI units, `0` disables the glow
 */
internal fun GuiGraphicsExtractor.drawGlow(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    color: Color4b,
    sigma: Float,
) {
    if (color.a <= 0 || sigma <= 0f) {
        return
    }

    val halfWidth = (x2 - x1) * 0.5f
    val halfHeight = (y2 - y1) * 0.5f
    if (halfWidth <= 0f || halfHeight <= 0f) {
        return
    }

    val centerX = x1 + halfWidth
    val centerY = y1 + halfHeight

    val reach = sigma * GLOW_REACH_SIGMA
    val left = centerX - halfWidth - reach
    val top = centerY - halfHeight - reach
    val right = centerX + halfWidth + reach
    val bottom = centerY + halfHeight + reach

    // The shader works with integer half extents, round up so the rect never loses a fraction.
    val halfX = halfWidth.ceilToInt()
    val halfY = halfHeight.ceilToInt()

    emitGlowQuad(left, top, right, bottom, centerX, centerY, halfX, halfY, sigma, color)
}

/**
 * Two tone bloom: a tight core in the inner palette colour and a wide wash in the outer one.
 *
 * Both quads are the same analytic gaussian, only with different sigma and colour, which is what
 * gives the layered "aurora" look without any blur pass. The outer tone is derived from the scheme
 * by [ArraylistColors.glowTones], so the glow of a row always belongs to the active palette.
 *
 * @param inner tight, bright halo, normally the row colour
 * @param outer wide, softer wash, normally the next palette stop
 * @param sigma gaussian sigma of the inner halo in GUI units
 * @param strength global multiplier applied to both tones, `0` disables the glow
 */
internal fun GuiGraphicsExtractor.drawGlowTone(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    inner: Color4b,
    outer: Color4b,
    sigma: Float,
    strength: Float,
) {
    if (sigma <= 0f || strength <= 0f) {
        return
    }

    val alphaScale = strength.coerceIn(0f, 4f)

    drawGlow(x1, y1, x2, y2, outer.fade(0.55f * alphaScale), sigma * 2.1f)
    drawGlow(x1, y1, x2, y2, inner.fade(0.85f * alphaScale), sigma)
}

/**
 * Core only variant, used where a wide wash would bleed into the neighbouring rows.
 */
internal fun GuiGraphicsExtractor.drawGlowCore(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    inner: Color4b,
    sigma: Float,
    strength: Float,
) {
    if (sigma <= 0f || strength <= 0f) {
        return
    }

    drawGlow(x1, y1, x2, y2, inner.fade(strength.coerceIn(0f, 4f)), sigma)
}

private fun GuiGraphicsExtractor.emitGlowQuad(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    centerX: Float,
    centerY: Float,
    halfX: Int,
    halfY: Int,
    sigma: Float,
    color: Color4b,
) {
    val argb = color.argb

    drawCustomElement(
        pipeline = ClientRenderPipelines.ArrayListGlowTone,
        bounds = getBounds(left, top, right, bottom),
    ) { pose ->
        addVertexWith2DPose(pose, left, top).setUv(left - centerX, top - centerY).setColor(argb)
            .setUv1(halfX, halfY).setLineWidth(sigma)
        addVertexWith2DPose(pose, left, bottom).setUv(left - centerX, bottom - centerY).setColor(argb)
            .setUv1(halfX, halfY).setLineWidth(sigma)
        addVertexWith2DPose(pose, right, bottom).setUv(right - centerX, bottom - centerY).setColor(argb)
            .setUv1(halfX, halfY).setLineWidth(sigma)
        addVertexWith2DPose(pose, right, top).setUv(right - centerX, top - centerY).setColor(argb)
            .setUv1(halfX, halfY).setLineWidth(sigma)
    }
}
