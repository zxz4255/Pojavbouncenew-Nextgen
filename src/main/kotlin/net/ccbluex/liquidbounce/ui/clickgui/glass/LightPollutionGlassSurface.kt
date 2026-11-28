/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.ui.clickgui.glass

import net.ccbluex.liquidbounce.render.copyPosePooled
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.render.gui.element.ClickGuiGlassGuiElementRenderState
import net.ccbluex.liquidbounce.render.gui.element.CssBoxGuiElementRenderState
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Separate reference materials for LightPollutionGlass.
 * - [Profile.PANEL] supports volumetric inward-to-outward white bloom ([GuiVariant.BLOOM_PANEL] / [GuiVariant.BLOOM_CAPSULE])
 *   as well as 094's standalone quiet Music panel ([GuiVariant.QUIET]).
 * - [Profile.TARGET], [Profile.COUNTER], and [Profile.SOFT] preserve 089 HUD optics unchanged.
 */
object LightPollutionGlassSurface {
    enum class Profile(val lane: Int) { PANEL(0), TARGET(1), COUNTER(2), SOFT(3) }

    enum class GuiVariant { BLOOM_PANEL, BLOOM_CAPSULE, QUIET }

    @JvmOverloads
    @Suppress("LongParameterList", "CognitiveComplexMethod")
    fun draw(
        ctx: GuiGraphicsExtractor,
        x: Float, y: Float, w: Float, h: Float,
        rTL: Float, rTR: Float, rBR: Float, rBL: Float,
        cTL: Int, cTR: Int, cBR: Int, cBL: Int,
        opacity: Float, refraction: Float, rimStrength: Float,
        shadowColor: Int, shadowSpread: Float, setup: TextureSetup?,
        profile: Profile = Profile.PANEL,
        variant: GuiVariant = GuiVariant.BLOOM_PANEL,
    ) {
        if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite() ||
            w <= 0f || h <= 0f || !opacity.isFinite() || opacity <= 0.004f) return
        val fade = opacity.coerceIn(0f, 1f)
        val panel = profile == Profile.PANEL
        val quiet = panel && variant == GuiVariant.QUIET
        val capsule = panel && variant == GuiVariant.BLOOM_CAPSULE

        val maxR = min(
            min(w, h) * 0.5f,
            when {
                quiet -> 3f
                panel -> 24f
                else -> Float.MAX_VALUE
            },
        )
        fun radius(value: Float) = if (value.isFinite()) value.coerceIn(0f, maxR) else 0f
        val tl = radius(rTL)
        val tr = radius(rTR)
        val br = radius(rBR)
        val bl = radius(rBL)

        val strength = when {
            quiet -> 0f
            rimStrength.isFinite() -> rimStrength.coerceIn(0f, 2f)
            else -> 1f
        }
        val lens = when {
            quiet -> 0f
            panel -> if (refraction.isFinite()) (refraction * if (capsule) 0.45f else 0.65f).coerceIn(0f, 2.5f) else 0f
            refraction.isFinite() -> refraction.coerceIn(0f, 3f)
            else -> 0f
        }

        // In the reference shader ONLY: R = half reflection/glow strength; G = neutral frost tint; B = variant/tint.
        // Never feed this transport color to a normal RGB fill (including the fallback).
        fun hudTransport(color: Int, densityScale: Float, tint: Int): Int {
            val density = ((color ushr 24) / 255f * densityScale).coerceIn(0f, 0.72f)
            return ((density * 255f).roundToInt() shl 24) or
                ((strength * 127.5f).roundToInt() shl 16) or (tint shl 8) or tint
        }

        val a: Int
        val b: Int
        val c: Int
        val d: Int
        val rim: Int
        val rimW: Float
        val shadow: Int
        val spread: Float

        if (!panel) {
            a = hudTransport(cTL, 1.08f, 32)
            b = hudTransport(cTR, 0.70f, 32)
            c = hudTransport(cBR, 0.46f, 32)
            d = hudTransport(cBL, 1.02f, 32)
            rim = ((strength.coerceAtMost(1f) * fade * 255f).roundToInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
            rimW = 0.45f
            spread = if (shadowSpread.isFinite()) shadowSpread.coerceIn(0f, 40f) else 0f
            val shadowA = ((min(shadowColor ushr 24, 255) * fade).roundToInt().coerceIn(0, 255))
            shadow = shadowA shl 24
        } else if (quiet) {
            val panelAlpha = (((cTL ushr 24) + (cTR ushr 24) + (cBR ushr 24) + (cBL ushr 24)) / 4)
            val quietColor = hudTransport(panelAlpha shl 24, 0.55f, 96)
            a = quietColor
            b = quietColor
            c = quietColor
            d = quietColor
            rim = 0
            rimW = 0f
            spread = if (shadowSpread.isFinite()) shadowSpread.coerceIn(0f, 6f) else 0f
            val shadowA = ((min(shadowColor ushr 24, 28) * fade).roundToInt().coerceIn(0, 255))
            shadow = shadowA shl 24
        } else {
            val avgAlpha = (((cTL ushr 24) + (cTR ushr 24) + (cBR ushr 24) + (cBL ushr 24)) * 0.25f) / 255f
            val density = if (capsule) {
                (avgAlpha * 0.34f).coerceIn(0.05f, 0.32f)
            } else {
                (avgAlpha * 0.62f).coerceIn(0.06f, 0.54f)
            }
            val glowByte = (strength * 127.5f).roundToInt().coerceIn(0, 255)
            val tintG = if (capsule) 246 else 78
            val variantB = if (capsule) 242 else 160
            val bloomBodyCol = ((density * 255f).roundToInt().coerceIn(1, 255) shl 24) or
                (glowByte shl 16) or (tintG shl 8) or variantB
            a = bloomBodyCol
            b = bloomBodyCol
            c = bloomBodyCol
            d = bloomBodyCol

            val rimFactor = if (capsule) 0.045f else 0.13f
            val rimA = (rimFactor * strength.coerceAtMost(1.5f) * fade * 255f).roundToInt().coerceIn(0, 255)
            rim = if (rimA > 0) (rimA shl 24) or 0xFFFFFF else 0
            rimW = if (rimA > 0) 0.95f else 0f

            val rawShadowA = shadowColor ushr 24
            val glowEnabled = strength > 0.01f || rawShadowA > 0
            if (glowEnabled) {
                val shadowNorm = if (rawShadowA > 0) {
                    (rawShadowA / 135f).coerceIn(0.28f, 1.35f)
                } else {
                    if (capsule) 0.76f else 0.88f
                }
                val bloomFactor = (fade * (0.34f + 0.66f * strength.coerceAtMost(1.6f)) * shadowNorm).coerceIn(0f, 1.35f)
                val baseSpread = if (shadowSpread.isFinite() && shadowSpread > 0.5f) {
                    shadowSpread
                } else {
                    if (capsule) 11f else 15f
                }
                spread = baseSpread.coerceIn(if (capsule) 8.5f else 11f, if (capsule) 18f else 24f)
                val peakAlpha = if (capsule) 0.54f else 0.35f
                val bloomA = (peakAlpha * bloomFactor * 255f).roundToInt().coerceIn(0, 235)
                shadow = if (bloomA > 0) (bloomA shl 24) or (255 shl 16) or (255 shl 8) or variantB else 0
            } else {
                spread = 0f
                shadow = 0
            }
        }

        val shadowA = shadow ushr 24
        val shadowPad = if (spread > 0.05f && shadowA > 0) spread * 2.5f + 4f else 0f
        val arcPad = if (profile == Profile.TARGET || profile == Profile.COUNTER) max(h * 0.20f, 3f) else 0f
        val pad = max(shadowPad, arcPad)
        val bounds = ctx.getBounds(x - pad, y - pad, x + w + pad, y + h + pad)
        if (setup != null) {
            ctx.guiRenderState.addGuiElement(ClickGuiGlassGuiElementRenderState(
                x, y, x + w, y + h, tl, tr, br, bl,
                a, b, c, d, fade, lens,
                rim, rimW, shadow, spread, true, profile.lane, setup,
                ctx.copyPosePooled(), ctx.scissorStack.peek(), bounds,
            ))
        } else {
            // Framebuffer absent: legible neutral fallback, explicitly NOT the optical blurred material.
            val fallbackTint = when {
                quiet -> 0x727272
                capsule -> 0xE8EEF8
                panel -> 0x5E6470
                else -> 0x343C44
            }
            val fallbackFillAlpha = if (capsule) 0.42f else 0.52f
            val fallback = ((fallbackFillAlpha * fade * 255f).roundToInt().coerceIn(0, 255) shl 24) or fallbackTint
            val fbShadow = if (panel && !quiet && shadowA > 0) {
                val glowA = (((if (capsule) 0.30f else 0.18f) * fade * 255f).roundToInt().coerceIn(0, 255))
                (glowA shl 24) or 0xFFFFFF
            } else {
                shadow
            }
            val fbSpread = if (panel && !quiet) min(spread * 0.55f, 10f) else spread
            ctx.guiRenderState.addGuiElement(CssBoxGuiElementRenderState(
                x, y, x + w, y + h, tl, tr, br, bl,
                fallback, fallback, fallback, fallback,
                0, 0f, fbShadow, fbSpread,
                ctx.copyPosePooled(), ctx.scissorStack.peek(), bounds,
            ))
        }
    }
}
