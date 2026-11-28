/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.render.cs2mvp

import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.world.entity.player.PlayerSkin
import kotlin.math.min
import kotlin.math.roundToInt

/** Measurements from Valve's tracked hudwinpanel: non-premier strip 640x90, avatar72+gap8,
 * reason21/font18, name250x38/font28. No arbitrary glass/rainbow, character scene or full-screen overlay.
 * See licenses/cs2mvp088/SOURCES.md for pinned source, typography/asset and trigger deviations.
 */
internal object Cs2MvpRenderer {
    const val WIDTH = 640f
    const val HEIGHT = 90f
    const val AVATAR = 72
    const val NAME_WIDTH = 250f

    @Suppress("LongParameterList")
    fun draw(
        ctx: GuiGraphicsExtractor, skin: PlayerSkin, frame: Cs2MvpFrame,
        screenWidth: Int, screenHeight: Int, scale: Float, verticalPosition: Float,
        ct: Boolean, nameFont: String, reasonFont: String,
    ) {
        if (frame.unfold <= .001f || frame.alpha <= .001f || screenWidth <= 0 || screenHeight <= 0) return
        val fit = min(screenHeight / 1080f * scale, screenWidth / (WIDTH + 24f)).coerceAtLeast(.01f)
        val y = (screenHeight * verticalPosition / 1080f).coerceIn(0f, (screenHeight - HEIGHT * fit).coerceAtLeast(0f))
        val accent = if (ct) 0x96C8FA else 0xEABE54
        val background = if (ct) 0x09283D else 0x422E08
        fun color(rgb: Int, alpha: Float = 1f) =
            (((alpha * frame.alpha * 255f).roundToInt().coerceIn(0, 255)) shl 24) or rgb
        ctx.pose().pushMatrix()
        try {
            ctx.pose().translate(screenWidth * .5f, y)
            ctx.pose().scale(fit * frame.unfold, fit)
            ctx.pose().translate(-WIDTH * .5f, 0f)
            // Three analytic gradient quads instead of a stack of translucent strips.
            // The proprietary opacity-mask texture is intentionally not redistributed.
            fun band(rgb: Int, alpha: Float) {
                val edge = 96f
                val opaque = color(rgb, alpha)
                val transparent = color(rgb, 0f)
                UiDraw.fillCssBox(ctx, 0f, 0f, edge, HEIGHT,
                    cTL = transparent, cTR = opaque, cBR = opaque, cBL = transparent)
                UiDraw.fillBox(ctx, edge, 0f, WIDTH - edge * 2f, HEIGHT, opaque)
                UiDraw.fillCssBox(ctx, WIDTH - edge, 0f, edge, HEIGHT,
                    cTL = opaque, cTR = transparent, cBR = transparent, cBL = opaque)
            }
            band(background, .85f)

            // Original winner group is fit-content centred inside the band, not flush to its edge.
            val groupX = (WIDTH - (AVATAR + 8f + NAME_WIDTH)) * .5f
            val iconY = (HEIGHT - AVATAR) * .5f
            UiDraw.fillBox(ctx, groupX, iconY, AVATAR.toFloat(), AVATAR.toFloat(), color(0x555555))
            PlayerFaceExtractor.extractRenderState(ctx, skin, groupX.roundToInt(), iconY.roundToInt(), AVATAR,
                color(0xFFFFFF))
            val detailsX = groupX + AVATAR + 8f
            val detailsY = (HEIGHT - (21f + 38f)) * .5f
            val label = "MVP"
            val labelWidth = UiDraw.measureText(label, 18f, UiFontWeight.MEDIUM, customFamily = reasonFont) + 12f
            UiDraw.fillBox(ctx, detailsX, detailsY, labelWidth, 21f, color(accent))
            UiDraw.drawText(ctx, label, detailsX + 6f, detailsY, 18f, color(0x000000),
                UiFontWeight.MEDIUM, customFamily = reasonFont)
            val winner = mvpEllipsis(frame.name, NAME_WIDTH) {
                UiDraw.measureText(it, 28f, UiFontWeight.MEDIUM, customFamily = nameFont)
            }
            // Valve's winner-name rule applies brightness:1.5 to the team colour.
            val brightName = if (ct) 0xE1FFFF else 0xFFFF7E
            UiDraw.drawText(ctx, winner, detailsX, detailsY + 21f, 28f, color(brightName),
                UiFontWeight.MEDIUM, shadow = true, customFamily = nameFont)

            if (frame.whiteFlash > .001f) band(0xFFFFFF, frame.whiteFlash)
        } finally {
            ctx.pose().popMatrix()
        }
    }

}
