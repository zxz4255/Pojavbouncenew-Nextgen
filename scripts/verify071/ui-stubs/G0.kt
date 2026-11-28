package net.ccbluex.liquidbounce.ui.common
import kotlin.math.max
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.gui.render.TextureSetup
import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.group.Value
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.ui.common.*
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.features.module.*
import net.ccbluex.liquidbounce.render.AbstractFontRenderer
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.input.InputBind
enum class UiFontWeight { REGULAR, MEDIUM, SEMIBOLD, BOLD, MONOSPACE }
object UiDraw {
var currentCanvasScale=1f
    fun rendererFor(weight: UiFontWeight = UiFontWeight.REGULAR, customFamily: String? = null): AbstractFontRenderer { throw UnsupportedOperationException() }
    fun lineHeight(
        fontSizePx: Float,
        weight: UiFontWeight = UiFontWeight.REGULAR,
        customFamily: String? = null,
    ): Float { throw UnsupportedOperationException() }
    fun measureText(
        text: String,
        fontSizePx: Float,
        weight: UiFontWeight = UiFontWeight.REGULAR,
        bold: Boolean = false,
        italic: Boolean = false,
        customFamily: String? = null,
    ): Float { throw UnsupportedOperationException() }
    fun measureComponent(
        component: Component,
        fontSizePx: Float,
        weight: UiFontWeight = UiFontWeight.REGULAR,
    ): Float { throw UnsupportedOperationException() }
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
    ): Float { throw UnsupportedOperationException() }
    fun drawComponent(
        ctx: GuiGraphicsExtractor,
        component: Component,
        x: Float,
        y: Float,
        fontSizePx: Float,
        argb: Int = UiPalette.TEXT_COLOR,
        weight: UiFontWeight = UiFontWeight.REGULAR,
        shadow: Boolean = false,
    ): Float { throw UnsupportedOperationException() }
    fun ellipsize(
        text: String,
        maxWidthPx: Float,
        fontSizePx: Float,
        weight: UiFontWeight = UiFontWeight.REGULAR,
    ): String { throw UnsupportedOperationException() }
    fun wrapText(
        text: String,
        maxWidthPx: Float,
        fontSizePx: Float,
        weight: UiFontWeight = UiFontWeight.REGULAR,
    ): List<String> { throw UnsupportedOperationException() }
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
    ) { throw UnsupportedOperationException() }
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
    ) { throw UnsupportedOperationException() }
    fun drawLineSegment(
        ctx: GuiGraphicsExtractor,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        thickness: Float,
        argb: Int,
    ) { throw UnsupportedOperationException() }
    fun drawIcon(
        ctx: GuiGraphicsExtractor,
        id: Identifier,
        x: Float,
        y: Float,
        boxW: Float,
        boxH: Float,
        argb: Int = -1,
        rotationRad: Float = 0f,
    ) { throw UnsupportedOperationException() }
    fun drawSoftShadow(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float = 5f,
        spread: Float = 8f,
        maxAlpha: Float = 0.45f,
    ) { throw UnsupportedOperationException() }
    fun drawIconStretched(
        ctx: GuiGraphicsExtractor,
        id: Identifier,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        argb: Int = -1,
    ) { throw UnsupportedOperationException() }
    fun drawIconStretched(
        ctx: GuiGraphicsExtractor,
        setup: TextureSetup,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        argb: Int = -1,
    ) { throw UnsupportedOperationException() }
    fun drawTextureRect(
        ctx: GuiGraphicsExtractor,
        id: Identifier,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        argb: Int = -1,
        pixelated: Boolean = false,
    ) { throw UnsupportedOperationException() }
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
    ) { throw UnsupportedOperationException() }
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
    ) { throw UnsupportedOperationException() }
    fun drawPlayerFaceRounded(
        ctx: GuiGraphicsExtractor,
        skinTexture: Identifier?,
        x: Float,
        y: Float,
        size: Float,
        radius: Float,
    ) { throw UnsupportedOperationException() }
    fun drawAnimatedPlusMinus(
        ctx: GuiGraphicsExtractor,
        centerX: Float,
        centerY: Float,
        size: Float,
        expandProgress: Float,
        argb: Int = UiPalette.TEXT_COLOR,
    ) { throw UnsupportedOperationException() }
    fun drawSvPickerSquare(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        hueZeroToOne: Float,
        radius: Float = 3f,
    ) { throw UnsupportedOperationException() }
    fun drawHueBar(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float) { throw UnsupportedOperationException() }
    fun drawAlphaCheckerBar(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rgbNoAlpha: Int,
    ) { throw UnsupportedOperationException() }
    fun drawItemStackView(
        ctx: GuiGraphicsExtractor,
        stack: ItemStack,
        x: Float,
        y: Float,
        sizePx: Float = 32f,
        overrideCountText: String? = null,
        overrideCountColor: Int? = null,
    ) { throw UnsupportedOperationException() }
    fun drawPendingTooltip(ctx: GuiGraphicsExtractor, canvasWidth: Float, canvasHeight: Float) { throw UnsupportedOperationException() }
    inline fun withCssScissor(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        block: () -> Unit,
    ) { throw UnsupportedOperationException() }
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
