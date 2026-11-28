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
@file:Suppress("TooManyFunctions", "LongParameterList")

package net.ccbluex.liquidbounce.ui.common

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import net.ccbluex.liquidbounce.features.module.modules.render.moderngui.ModernGuiInterceptor
import net.ccbluex.liquidbounce.render.AbstractFontRenderer
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.render.copyPosePooled
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.drawTriangle
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.render.gui.element.CssBoxGuiElementRenderState
import net.ccbluex.liquidbounce.render.gui.element.RoundedTexGuiElementRenderState
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassSurface
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.item.ItemStack
import java.awt.Color
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class UiFontWeight(val faceName: String) {
    REGULAR("Inter Regular"),
    MEDIUM("Inter Medium"),
    SEMIBOLD("Inter SemiBold"),
    BOLD("Inter Bold"),
    MONOSPACE("Monospace"),
}

object UiDraw {

    var currentCanvasScale: Float = 1f

    private val STYLE_PLAIN: Style = Style.EMPTY

    fun rendererFor(weight: UiFontWeight = UiFontWeight.REGULAR, customFamily: String? = null): AbstractFontRenderer {
        if (!customFamily.isNullOrBlank()) {
            FontManager.fontFace(customFamily)?.let { return it.renderer }
            FontManager.fontFaces.entries.firstOrNull {
                it.key.equals(customFamily, ignoreCase = true)
            }?.value?.let { return it.renderer }
        }
        return (FontManager.fontFace(weight.faceName)
            ?: FontManager.fontFace("Inter Regular")
            ?: return FontManager.FONT_RENDERER).renderer
    }

    fun lineHeight(
        fontSizePx: Float,
        weight: UiFontWeight = UiFontWeight.REGULAR,
        customFamily: String? = null,
    ): Float {
        val r = rendererFor(weight, customFamily)
        return r.height * (fontSizePx / r.size)
    }

    fun measureText(
        text: String,
        fontSizePx: Float,
        weight: UiFontWeight = UiFontWeight.REGULAR,
        bold: Boolean = false,
        italic: Boolean = false,
        customFamily: String? = null,
    ): Float {
        if (text.isEmpty() || fontSizePx <= 0f) return 0f
        val r = rendererFor(weight, customFamily)
        val style = buildStyle(bold = bold, italic = italic, underline = false, strikethrough = false)
        val fcs = FormattedCharSequence.forward(text, style)
        return r.getStringWidth(fcs, false) * (fontSizePx / r.size)
    }

    fun measureComponent(
        component: Component,
        fontSizePx: Float,
        weight: UiFontWeight = UiFontWeight.REGULAR,
    ): Float {
        if (fontSizePx <= 0f) return 0f
        val r = rendererFor(weight)
        return r.getStringWidth(component, scale = fontSizePx / r.size, shadow = false)
    }

    fun drawText(
        ctx: GuiGraphicsExtractor,
        text: String,
        x: Float,
        y: Float,
        fontSizePx: Float,
        argb: Int,
        weight: UiFontWeight = UiFontWeight.REGULAR,
        shadow: Boolean = false,
        bold: Boolean = false,
        italic: Boolean = false,
        underline: Boolean = false,
        strikethrough: Boolean = false,
        customFamily: String? = null,
    ): Float {
        if (text.isEmpty() || fontSizePx <= 0f || (argb ushr 24) == 0) return 0f
        val r = rendererFor(weight, customFamily)
        val scale = fontSizePx / r.size
        val style = buildStyle(bold, italic, underline, strikethrough)
        val fcs = FormattedCharSequence.forward(text, style)
        val unscaled = with(ctx) {
            r.draw(fcs) {
                this.x = x
                this.y = y
                this.scale = scale
                this.shadow = shadow
                this.color = Color4b(argb)
            }
        }
        return unscaled * scale
    }

    fun drawComponent(
        ctx: GuiGraphicsExtractor,
        component: Component,
        x: Float,
        y: Float,
        fontSizePx: Float,
        argb: Int = UiPalette.TEXT_COLOR,
        weight: UiFontWeight = UiFontWeight.REGULAR,
        shadow: Boolean = false,
    ): Float {
        if (fontSizePx <= 0f || (argb ushr 24) == 0) return 0f
        val r = rendererFor(weight)
        val scale = fontSizePx / r.size
        val unscaled = r.draw(
            ctx = ctx,
            text = component,
            x = x,
            y = y,
            color = Color4b(argb),
            shadow = shadow,
            scale = scale,
        )
        return unscaled * scale
    }

    fun ellipsize(
        text: String,
        maxWidthPx: Float,
        fontSizePx: Float,
        weight: UiFontWeight = UiFontWeight.REGULAR,
    ): String {
        if (text.isEmpty() || maxWidthPx <= 0f) return ""
        if (measureText(text, fontSizePx, weight) <= maxWidthPx) return text
        val ellipsis = "..."
        val ellipsisW = measureText(ellipsis, fontSizePx, weight)
        if (ellipsisW >= maxWidthPx) return ""
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (measureText(text.substring(0, mid), fontSizePx, weight) + ellipsisW <= maxWidthPx) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        return text.substring(0, lo) + ellipsis
    }

    fun wrapText(
        text: String,
        maxWidthPx: Float,
        fontSizePx: Float,
        weight: UiFontWeight = UiFontWeight.REGULAR,
    ): List<String> {
        if (text.isEmpty()) return listOf("")
        if (maxWidthPx <= 4f) return listOf(text)
        val lines = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            val words = paragraph.split(' ')
            val current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (measureText(candidate, fontSizePx, weight) <= maxWidthPx) {
                    current.clear().append(candidate)
                } else {
                    if (current.isNotEmpty()) {
                        lines.add(current.toString())
                        current.clear()
                    }
                    if (measureText(word, fontSizePx, weight) <= maxWidthPx) {
                        current.append(word)
                    } else {
                        // Break long unbroken token character by character
                        for (ch in word) {
                            val next = "$current$ch"
                            if (current.isNotEmpty() && measureText(next, fontSizePx, weight) > maxWidthPx) {
                                lines.add(current.toString())
                                current.clear()
                            }
                            current.append(ch)
                        }
                    }
                }
            }
            lines.add(current.toString())
        }
        return lines
    }

    private fun buildStyle(
        bold: Boolean,
        italic: Boolean,
        underline: Boolean,
        strikethrough: Boolean,
    ): Style {
        if (!bold && !italic && !underline && !strikethrough) return STYLE_PLAIN
        var s = STYLE_PLAIN
        if (bold) s = s.withBold(true)
        if (italic) s = s.withItalic(true)
        if (underline) s = s.withUnderlined(true)
        if (strikethrough) s = s.withStrikethrough(true)
        return s
    }

    fun fillBox(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        color: Int,
        radius: Float = 0f,
        borderColor: Int = 0,
        borderWidth: Float = 0f,
        shadowColor: Int = 0,
        shadowBlur: Float = 0f,
    ) {
        fillCssBox(
            ctx = ctx,
            x = x, y = y, w = w, h = h,
            rTL = radius, rTR = radius, rBR = radius, rBL = radius,
            cTL = color, cTR = color, cBR = color, cBL = color,
            borderColor = borderColor, borderWidth = borderWidth,
            shadowColor = shadowColor, shadowBlur = shadowBlur,
        )
    }

    fun fillCssBox(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rTL: Float = 0f,
        rTR: Float = 0f,
        rBR: Float = 0f,
        rBL: Float = 0f,
        cTL: Int,
        cTR: Int = cTL,
        cBR: Int = cTL,
        cBL: Int = cTL,
        borderColor: Int = 0,
        borderWidth: Float = 0f,
        shadowColor: Int = 0,
        shadowBlur: Float = 0f,
    ) {
        if (w <= 0f || h <= 0f) return
        if (ModernGuiInterceptor.tryUpgradeClientHudBox(
                ctx = ctx,
                x = x, y = y, w = w, h = h,
                rTL = rTL, rTR = rTR, rBR = rBR, rBL = rBL,
                cTL = cTL, cTR = cTR, cBR = cBR, cBL = cBL,
                borderColor = borderColor, borderWidth = borderWidth,
                shadowColor = shadowColor, shadowBlur = shadowBlur,
            )
        ) {
            return
        }
        val pad = if ((shadowColor ushr 24) != 0 && shadowBlur > 0.01f) shadowBlur * 2.2f else 0f
        val bounds = ctx.getBounds(x - pad, y - pad, x + w + pad, y + h + pad)
        ctx.guiRenderState.addGuiElement(
            CssBoxGuiElementRenderState(
                x, y, x + w, y + h,
                rTL, rTR, rBR, rBL,
                cTL, cTR, cBR, cBL,
                borderColor, borderWidth,
                shadowColor, shadowBlur,
                ctx.copyPosePooled(),
                ctx.scissorStack.peek(),
                bounds,
            )
        )
    }

    fun drawLineSegment(
        ctx: GuiGraphicsExtractor,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        thickness: Float,
        argb: Int,
    ) {
        if ((argb ushr 24) == 0 || thickness <= 0f) return
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.hypot(dx, dy)
        if (len < 0.001f) return
        val nx = -dy / len * (thickness * 0.5f)
        val ny = dx / len * (thickness * 0.5f)
        val minX = min(min(x0 + nx, x0 - nx), min(x1 + nx, x1 - nx))
        val minY = min(min(y0 + ny, y0 - ny), min(y1 + ny, y1 - ny))
        val maxX = max(max(x0 + nx, x0 - nx), max(x1 + nx, x1 - nx))
        val maxY = max(max(y0 + ny, y0 - ny), max(y1 + ny, y1 - ny))
        ctx.drawCustomElement(
            pipeline = ClientRenderPipelines.GUI.triangles(false),
            bounds = ctx.getBounds(minX, minY, maxX, maxY),
        ) { pose ->
            addVertexWith2DPose(pose, x0 + nx, y0 + ny).setColor(argb)
            addVertexWith2DPose(pose, x0 - nx, y0 - ny).setColor(argb)
            addVertexWith2DPose(pose, x1 - nx, y1 - ny).setColor(argb)

            addVertexWith2DPose(pose, x0 + nx, y0 + ny).setColor(argb)
            addVertexWith2DPose(pose, x1 - nx, y1 - ny).setColor(argb)
            addVertexWith2DPose(pose, x1 + nx, y1 + ny).setColor(argb)
        }
    }

    /**
     * Draws a crisp smooth vector icon centered & aspect-fitted inside `(x, y, boxW, boxH)`.
     */
    fun drawIcon(
        ctx: GuiGraphicsExtractor,
        id: Identifier,
        x: Float,
        y: Float,
        boxW: Float,
        boxH: Float,
        argb: Int = -1,
        rotationRad: Float = 0f,
    ) {
        if (boxW <= 0f || boxH <= 0f || (argb ushr 24) == 0) return
        val setup = UiIcons.linearSetup(id) ?: return
        val aspect = UiIcons.aspectRatio(id)
        val drawW: Float
        val drawH: Float
        if (boxW / boxH > aspect) {
            drawH = boxH
            drawW = boxH * aspect
        } else {
            drawW = boxW
            drawH = boxW / aspect
        }
        val cx = x + boxW * 0.5f
        val cy = y + boxH * 0.5f
        if (rotationRad == 0f) {
            ctx.drawTexQuad(
                textureSetup = setup,
                x0 = cx - drawW * 0.5f,
                y0 = cy - drawH * 0.5f,
                x1 = cx + drawW * 0.5f,
                y1 = cy + drawH * 0.5f,
                argb = argb,
                pipeline = ClientRenderPipelines.GUI.TexQuadNoCull,
            )
        } else {
            ctx.pose().withPush {
                translate(cx, cy)
                rotate(rotationRad)
                ctx.drawTexQuad(
                    textureSetup = setup,
                    x0 = -drawW * 0.5f,
                    y0 = -drawH * 0.5f,
                    x1 = drawW * 0.5f,
                    y1 = drawH * 0.5f,
                    argb = argb,
                    pipeline = ClientRenderPipelines.GUI.TexQuadNoCull,
                )
            }
        }
    }

    fun drawSoftShadow(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float = 5f,
        spread: Float = 8f,
        maxAlpha: Float = 0.45f,
    ) {
        if (w <= 0f || h <= 0f || spread <= 0f || maxAlpha <= 0f) return
        fillCssBox(
            ctx = ctx,
            x = x, y = y, w = w, h = h,
            rTL = radius, rTR = radius, rBR = radius, rBL = radius,
            cTL = 0, cTR = 0, cBR = 0, cBL = 0,
            shadowColor = UiPalette.black(maxAlpha),
            shadowBlur = spread,
        )
    }

    /**
     * Draws an exact stretched texture (like Watermark 2-layer logo or Taco sprite).
     */
    fun drawIconStretched(
        ctx: GuiGraphicsExtractor,
        id: Identifier,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        argb: Int = -1,
    ) {
        drawTextureRect(ctx, id, x, y, w, h, argb)
    }

    fun drawIconStretched(
        ctx: GuiGraphicsExtractor,
        setup: TextureSetup,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        argb: Int = -1,
    ) {
        if (w <= 0f || h <= 0f || (argb ushr 24) == 0) return
        ctx.drawTexQuad(
            textureSetup = setup,
            x0 = x,
            y0 = y,
            x1 = x + w,
            y1 = y + h,
            argb = argb,
            pipeline = ClientRenderPipelines.GUI.TexQuadNoCull,
        )
    }

    fun drawTextureRect(
        ctx: GuiGraphicsExtractor,
        id: Identifier,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        argb: Int = -1,
        pixelated: Boolean = false,
    ) {
        if (w <= 0f || h <= 0f || (argb ushr 24) == 0) return
        val setup = if (pixelated) UiIcons.nearestSetup(id) else UiIcons.linearSetup(id)
        if (setup == null) return
        ctx.drawTexQuad(
            textureSetup = setup,
            x0 = x,
            y0 = y,
            x1 = x + w,
            y1 = y + h,
            argb = argb,
            pipeline = ClientRenderPipelines.GUI.TexQuadNoCull,
        )
    }

    fun drawRoundedTexture(
        ctx: GuiGraphicsExtractor,
        textureSetup: TextureSetup,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
        u0: Float = 0f,
        v0: Float = 0f,
        u1: Float = 1f,
        v1: Float = 1f,
        argb: Int = -1,
    ) {
        if (w <= 0f || h <= 0f || (argb ushr 24) == 0) return
        ctx.guiRenderState.addGuiElement(
            RoundedTexGuiElementRenderState(
                x, y, x + w, y + h,
                u0, v0, u1, v1,
                radius,
                argb,
                textureSetup,
                ctx.copyPosePooled(),
                ctx.scissorStack.peek(),
                ctx.getBounds(x, y, x + w, y + h),
            )
        )
    }

    fun drawRoundedTexture(
        ctx: GuiGraphicsExtractor,
        id: Identifier,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
        u0: Float = 0f,
        v0: Float = 0f,
        u1: Float = 1f,
        v1: Float = 1f,
        argb: Int = -1,
        pixelated: Boolean = true,
    ) {
        if (w <= 0f || h <= 0f || (argb ushr 24) == 0) return
        val setup = runCatching {
            val tex = mc.textureManager.getTexture(id)
            val sampler = RenderSystem.getSamplerCache()
                .getClampToEdge(if (pixelated) FilterMode.NEAREST else FilterMode.LINEAR)
            TextureSetup.singleTexture(tex.textureView, sampler)
        }.getOrNull() ?: return
        drawRoundedTexture(ctx, setup, x, y, w, h, radius, u0, v0, u1, v1, argb)
    }

    fun drawPlayerFaceRounded(
        ctx: GuiGraphicsExtractor,
        skinTexture: Identifier?,
        x: Float,
        y: Float,
        size: Float,
        radius: Float,
    ) {
        if (skinTexture != null) {
            val setup = runCatching {
                val tex = mc.textureManager.getTexture(skinTexture)
                val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
                TextureSetup.singleTexture(tex.textureView, sampler)
            }.getOrNull()
            if (setup != null) {
                // Base face (8..16, 8..16 on 64x64 skin) + Hat overlay (40..48, 8..16)
                drawRoundedTexture(ctx, setup, x, y, size, size, radius, 8f / 64f, 8f / 64f, 16f / 64f, 16f / 64f)
                drawRoundedTexture(ctx, setup, x, y, size, size, radius, 40f / 64f, 8f / 64f, 48f / 64f, 16f / 64f)
                return
            }
        }
        val steveSetup = UiIcons.nearestSetup(UiIcons.STEVE_FACE) ?: return
        drawRoundedTexture(ctx, steveSetup, x, y, size, size, radius, 0f, 0f, 1f, 1f)
    }

    /**
     * Animated `+` -> `-` toggle icon from `043` `Panel.svelte` (`.expand-toggle .icon`).
     */
    fun drawAnimatedPlusMinus(
        ctx: GuiGraphicsExtractor,
        centerX: Float,
        centerY: Float,
        size: Float,
        expandProgress: Float,
        argb: Int = UiPalette.TEXT_COLOR,
    ) {
        val half = size * 0.5f
        val vAngle = expandProgress.coerceIn(0f, 1f) * (Math.PI.toFloat() * 0.5f)
        val hAngle = expandProgress.coerceIn(0f, 1f) * Math.PI.toFloat()

        val vx = sin(vAngle) * half
        val vy = cos(vAngle) * half
        drawLineSegment(ctx, centerX - vx, centerY - vy, centerX + vx, centerY + vy, 2f, argb)

        val hx = cos(hAngle) * half
        val hy = sin(hAngle) * half
        drawLineSegment(ctx, centerX - hx, centerY - hy, centerX + hx, centerY + hy, 2f, argb)
    }

    /**
     * Exact 2D Saturation-Value gradient square for `ColorSetting`.
     */
    fun drawSvPickerSquare(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        hueZeroToOne: Float,
        radius: Float = 3f,
    ) {
        val pureHueRgb = (Color.HSBtoRGB(hueZeroToOne.coerceIn(0f, 1f), 1f, 1f) and 0x00FFFFFF) or (0xFF shl 24)
        // Base horizontal white -> pureHue
        fillCssBox(
            ctx, x, y, w, h,
            rTL = radius, rTR = radius, rBR = radius, rBL = radius,
            cTL = 0xFFFFFFFF.toInt(), cTR = pureHueRgb, cBR = pureHueRgb, cBL = 0xFFFFFFFF.toInt(),
        )
        // Overlay vertical transparent -> opaque black
        fillCssBox(
            ctx, x, y, w, h,
            rTL = radius, rTR = radius, rBR = radius, rBL = radius,
            cTL = 0x00000000, cTR = 0x00000000, cBR = 0xFF000000.toInt(), cBL = 0xFF000000.toInt(),
        )
    }

    fun drawHueBar(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float) {
        val stops = intArrayOf(
            0xFFFF0000.toInt(),
            0xFFFFFF00.toInt(),
            0xFF00FF00.toInt(),
            0xFF00FFFF.toInt(),
            0xFF0000FF.toInt(),
            0xFFFF00FF.toInt(),
            0xFFFF0000.toInt(),
        )
        val segCount = stops.size - 1
        val segW = w / segCount
        for (i in 0 until segCount) {
            val sx = x + i * segW
            val rLeft = if (i == 0) h * 0.5f else 0f
            val rRight = if (i == segCount - 1) h * 0.5f else 0f
            fillCssBox(
                ctx, sx, y, segW + 0.35f, h,
                rTL = rLeft, rTR = rRight, rBR = rRight, rBL = rLeft,
                cTL = stops[i], cTR = stops[i + 1], cBR = stops[i + 1], cBL = stops[i],
            )
        }
    }

    fun drawAlphaCheckerBar(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rgbNoAlpha: Int,
    ) {
        val cell = 4f
        val cols = (w / cell).toInt().coerceAtLeast(1)
        val rows = (h / cell).toInt().coerceAtLeast(1)
        withCssScissor(ctx, x, y, w, h) {
            for (r in 0..rows) {
                for (c in 0..cols) {
                    val col = if ((r + c) % 2 == 0) 0xFFCCCCCC.toInt() else 0xFF777777.toInt()
                    val cx = x + c * cell
                    val cy = y + r * cell
                    val cw = min(cell, x + w - cx)
                    val ch = min(cell, y + h - cy)
                    if (cw > 0f && ch > 0f) {
                        fillBox(ctx, cx, cy, cw, ch, col)
                    }
                }
            }
        }
        val transparent = rgbNoAlpha and 0x00FFFFFF
        val opaque = (rgbNoAlpha and 0x00FFFFFF) or (0xFF shl 24)
        fillCssBox(
            ctx, x, y, w, h,
            rTL = h * 0.5f, rTR = h * 0.5f, rBR = h * 0.5f, rBL = h * 0.5f,
            cTL = transparent, cTR = opaque, cBR = opaque, cBL = transparent,
        )
    }

    /**
     * Exact native reproduction of `043` `ItemStackView.svelte` (32x32 CSS px).
     */
    fun drawItemStackView(
        ctx: GuiGraphicsExtractor,
        stack: ItemStack,
        x: Float,
        y: Float,
        sizePx: Float = 32f,
        overrideCountText: String? = null,
        overrideCountColor: Int? = null,
    ) {
        if (stack.isEmpty) return

        // Radial purple enchantment glow matching `&.has-enchantment { background: radial-gradient(..., rgba(128,0,128,0.75), transparent 70%); }`
        if (stack.hasFoil()) {
            fillBox(
                ctx = ctx,
                x = x + sizePx * 0.12f,
                y = y + sizePx * 0.12f,
                w = sizePx * 0.76f,
                h = sizePx * 0.76f,
                color = 0x559020B8,
                radius = sizePx * 0.38f,
                shadowColor = 0x99800080.toInt(),
                shadowBlur = sizePx * 0.22f,
            )
        }

        // Render real 3D/2D Minecraft item model scaled cleanly into `sizePx x sizePx` CSS box
        val itemScale = sizePx / 16f
        ctx.pose().withPush {
            translate(x, y)
            scale(itemScale, itemScale)
            ctx.item(stack, 0, 0)
        }

        // Custom durability bar (`width: 28px; height: 2px; bottom: 2px` centered, `mapToColor(120 * remaining / max)`)
        val maxDamage = stack.maxDamage
        val damage = stack.damageValue
        if (maxDamage > 0 && damage > 0) {
            val remaining = (maxDamage - damage).coerceIn(0, maxDamage)
            val ratio = remaining.toFloat() / maxDamage.toFloat()
            val barTotalW = sizePx * (28f / 32f)
            val barH = max(2f, sizePx * (2f / 32f))
            val barX = x + (sizePx - barTotalW) * 0.5f
            val barY = y + sizePx - barH * 2f
            fillBox(ctx, barX, barY, barTotalW, barH, 0xBF000000.toInt())
            val barColor = UiPalette.mapToColor(120f * ratio)
            fillBox(ctx, barX, barY, barTotalW * ratio, barH, barColor)
        }

        // Stack count (`bottom: 0; right: 1px; font-family: monospace; font-size: 12px; text-shadow: 1px 1px black`)
        val countLabel = overrideCountText ?: if (stack.count > 1) stack.count.toString() else null
        if (!countLabel.isNullOrEmpty()) {
            val countColor = overrideCountColor ?: if (stack.count <= 0) UiPalette.ERROR_COLOR else UiPalette.TEXT_COLOR
            val fontSize = 12f * (sizePx / 32f)
            val tw = measureText(countLabel, fontSize, UiFontWeight.MONOSPACE)
            val lh = lineHeight(fontSize, UiFontWeight.MONOSPACE)
            val tx = x + sizePx - 1f - tw
            val ty = y + sizePx - lh + 1f
            drawText(ctx, countLabel, tx + 1f, ty + 1f, fontSize, 0xFF000000.toInt(), UiFontWeight.MONOSPACE)
            drawText(ctx, countLabel, tx, ty, fontSize, countColor, UiFontWeight.MONOSPACE)
        }
    }

    /**
     * Draws the `Description.svelte` speech-bubble tooltip at the end of the frame.
     */
    fun drawPendingTooltip(ctx: GuiGraphicsExtractor, canvasWidth: Float, canvasHeight: Float) {
        val raw = UiTooltipManager.pendingText ?: return
        val maxW = min(320f, canvasWidth * 0.45f)
        val lines = wrapText(raw, maxW, 12f, UiFontWeight.REGULAR)
        val lh = lineHeight(12f, UiFontWeight.REGULAR)
        val textW = lines.maxOfOrNull { measureText(it, 12f, UiFontWeight.REGULAR) } ?: 0f
        val boxW = textW + 20f
        val boxH = lines.size * lh + 14f
        val triW = 6f
        val triH = 12f
        val accent = UiPalette.accent(1f)

        val anchorX = UiTooltipManager.anchorX
        val anchorY = UiTooltipManager.anchorY.coerceIn(boxH * 0.5f + 4f, max(boxH * 0.5f + 4f, canvasHeight - boxH * 0.5f - 4f))
        val glassMode = ClickGuiGlassSurface.isActive
        val triCol = if (glassMode) UiPalette.accent(0.72f) else accent

        if (!UiTooltipManager.preferLeft) {
            val bx = anchorX + triW + 4f
            val by = anchorY - boxH * 0.5f
            ctx.drawTriangle(
                x0 = anchorX + 4f, y0 = anchorY,
                x1 = bx + 0.5f, y1 = anchorY - triH * 0.5f,
                x2 = bx + 0.5f, y2 = anchorY + triH * 0.5f,
                fillColor = Color4b(triCol),
            )
            if (glassMode) {
                ClickGuiGlassSurface.drawAccentCapsule(
                    ctx = ctx, x = bx, y = by, w = boxW, h = boxH, radius = 7f,
                    accentMix = 0.72f, tintDensity = 0.56f, rimAlpha = 0.78f,
                    shadowSpread = 10f, shadowAlpha = 0.44f,
                )
            } else {
                fillBox(ctx, bx, by, boxW, boxH, accent, radius = 5f, shadowColor = UiPalette.black(0.45f), shadowBlur = 6f)
            }
            val tipTextCol = 0xFFFFFFFF.toInt()
            lines.forEachIndexed { idx, line ->
                drawText(ctx, line, bx + 10f, by + 7f + idx * lh, 12f, tipTextCol, UiFontWeight.REGULAR)
            }
        } else {
            val bx = anchorX - triW - 4f - boxW
            val by = anchorY - boxH * 0.5f
            ctx.drawTriangle(
                x0 = anchorX - 4f, y0 = anchorY,
                x1 = bx + boxW - 0.5f, y1 = anchorY - triH * 0.5f,
                x2 = bx + boxW - 0.5f, y2 = anchorY + triH * 0.5f,
                fillColor = Color4b(triCol),
            )
            if (glassMode) {
                ClickGuiGlassSurface.drawAccentCapsule(
                    ctx = ctx, x = bx, y = by, w = boxW, h = boxH, radius = 7f,
                    accentMix = 0.72f, tintDensity = 0.56f, rimAlpha = 0.78f,
                    shadowSpread = 10f, shadowAlpha = 0.44f,
                )
            } else {
                fillBox(ctx, bx, by, boxW, boxH, accent, radius = 5f, shadowColor = UiPalette.black(0.45f), shadowBlur = 6f)
            }
            val tipTextCol = 0xFFFFFFFF.toInt()
            lines.forEachIndexed { idx, line ->
                drawText(ctx, line, bx + 10f, by + 7f + idx * lh, 12f, tipTextCol, UiFontWeight.REGULAR)
            }
        }
    }

    inline fun withCssScissor(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        block: () -> Unit,
    ) {
        if (w <= 0f || h <= 0f) return
        val rect: ScreenRectangle = ctx.getBounds(x, y, x + w, y + h)
        ctx.scissorStack.withPush(rect) {
            block()
        }
    }

    /**
     * Renders a `UiTextEditState` inside a clipped text field box `(x, y, w, h)` with selection & caret.
     */
    fun drawTextFieldContent(
        ctx: GuiGraphicsExtractor,
        state: UiTextEditState,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        fontSizePx: Float,
        textColor: Int = UiPalette.TEXT_COLOR,
        placeholder: String = "",
        placeholderColor: Int = UiPalette.TEXT_MUTED_COLOR,
        weight: UiFontWeight = UiFontWeight.REGULAR,
        paddingX: Float = 8f,
    ) {
        val innerX = x + paddingX
        val innerW = (w - paddingX * 2f).coerceAtLeast(1f)
        val lh = lineHeight(fontSizePx, weight)
        val ty = y + (h - lh) * 0.5f

        state.ensureCaretVisible(innerW, fontSizePx, weight)

        withCssScissor(ctx, innerX - 1f, y, innerW + 2f, h) {
            val drawOriginX = innerX - state.scrollOffsetPx
            val hasAnyText = state.text.isNotEmpty() || state.preeditText.isNotEmpty()
            if (!hasAnyText && !state.focused && placeholder.isNotEmpty()) {
                drawText(ctx, placeholder, innerX, ty, fontSizePx, placeholderColor, weight)
            } else {
                if (!hasAnyText && placeholder.isNotEmpty()) {
                    drawText(ctx, placeholder, innerX, ty, fontSizePx, UiPalette.withAlpha(placeholderColor, 0.65f), weight)
                }
                if (state.hasSelection) {
                    val s0 = measureText(state.text.substring(0, state.selectionStart), fontSizePx, weight)
                    val s1 = measureText(state.text.substring(0, state.selectionEnd), fontSizePx, weight)
                    fillBox(ctx, drawOriginX + s0, ty - 1f, max(1f, s1 - s0), lh + 2f, UiPalette.accent(0.42f), radius = 2f)
                }
                val safeCursor = state.cursor.coerceIn(0, state.text.length)
                if (state.preeditText.isNotEmpty() && state.focused) {
                    val before = state.text.substring(0, safeCursor)
                    val after = state.text.substring(safeCursor)
                    val beforeW = if (before.isNotEmpty()) drawText(ctx, before, drawOriginX, ty, fontSizePx, textColor, weight) else 0f
                    val preW = drawText(ctx, state.preeditText, drawOriginX + beforeW, ty, fontSizePx, UiPalette.accent(0.95f), weight)
                    fillBox(ctx, drawOriginX + beforeW, ty + lh - 1f, max(2f, preW), 1.2f, UiPalette.accent(0.85f))
                    if (after.isNotEmpty()) {
                        drawText(ctx, after, drawOriginX + beforeW + preW, ty, fontSizePx, textColor, weight)
                    }
                    if (state.isCaretVisible) {
                        fillBox(ctx, drawOriginX + beforeW + preW, ty, 1.5f, lh, UiPalette.accent(1f))
                    }
                } else {
                    if (state.text.isNotEmpty()) {
                        drawText(ctx, state.text, drawOriginX, ty, fontSizePx, textColor, weight)
                    }
                    if (state.isCaretVisible) {
                        val cx = drawOriginX + measureText(state.text.substring(0, safeCursor), fontSizePx, weight)
                        fillBox(ctx, cx, ty, 1.5f, lh, UiPalette.accent(1f))
                    }
                }
            }
        }
    }
}
