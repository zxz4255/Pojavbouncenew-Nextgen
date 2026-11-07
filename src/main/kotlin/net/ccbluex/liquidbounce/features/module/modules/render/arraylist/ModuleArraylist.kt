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

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.engine.font.HorizontalAnchor
import net.ccbluex.liquidbounce.render.engine.font.VerticalAnchor
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.scaledDimension
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FIRST_PRIORITY
import net.ccbluex.liquidbounce.utils.text.withFormat
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.network.chat.Style
import net.minecraft.util.Mth
import kotlin.math.roundToInt

/**
 * Port of Solstice's `Arraylist` module (`src/Features/Modules/Visual/Arraylist.cpp`).
 *
 * Everything the original does is reproduced here instead of being approximated:
 *
 * - rows are sorted by their rendered width, longest first - the original sorts by
 *   `ImGui::GetFont()->CalcTextSizeA`, we sort by the same measurement,
 * - every row slides in from `screenWidth + 14` towards its own right aligned stop position with a
 *   `lerp(anim, target, deltaTime * 12)` animation, and the whole list collapses because the row
 *   advance is multiplied by that animation,
 * - `Display` renders the same three decorations: `Outline` (one continuous outline around the whole
 *   staircase), `Bar` (a vertical accent line at the right edge of every row) and `Split` (a rounded
 *   accent bar per row),
 * - `Background` covers the `Opacity` fill, the `Shadow` halo and `Both`,
 * - hovering a row highlights it and clicking it toggles the module.
 *
 * The only thing that is not a literal translation is the glow: Solstice uses
 * `ImDrawList::AddShadowRect`, which blurs a texture. Instead of imitating that by stacking
 * semi transparent rects, `drawGlow` (`ArraylistGlow.kt`) evaluates the exact gaussian coverage
 * of the rect analytically in one fragment shader invocation - see `shaders/gui/arraylist_glow.fsh`.
 *
 * Layout note: Solstice lays out in physical pixels (ImGui), LiquidBounce in GUI units. Every
 * constant taken from the original therefore goes through `unit`, which applies the vanilla GUI
 * scale of 2 (one Solstice pixel is half a GUI unit) and scales the layout with `FontSize`.
 */
@Suppress("TooManyFunctions")
object ModuleArraylist : ClientModule("Arraylist", ModuleCategories.RENDER, state = true) {

    /**
     * Solstice's `Arraylist::BackgroundStyle`.
     */
    enum class BackgroundStyle(override val tag: String) : Tagged {
        OPACITY("Opacity"),
        SHADOW("Shadow"),
        BOTH("Both"),
    }

    /**
     * Solstice's `Arraylist::Display`.
     */
    enum class Display(override val tag: String) : Tagged {
        OUTLINE("Outline"),
        BAR("Bar"),
        SPLIT("Split"),
        NONE("None"),
    }

    /**
     * Solstice's `Arraylist::ModuleVisibility`.
     */
    enum class ModuleVisibility(override val tag: String) : Tagged {
        ALL("All"),
        BOUND("Bound"),
    }

    /**
     * The original's default `FontSize` (25px) expressed in GUI units at the vanilla GUI scale of 2.
     */
    private const val REFERENCE_FONT_SIZE = 12.5f

    /**
     * `ImGui::GetIO().DeltaTime * 12` in the original.
     */
    private const val ANIMATION_SPEED = 12f

    private const val MAX_FRAME_TIME = 0.1f

    private val colors = tree(ArraylistColors())

    // region settings, same names, order and defaults as the original

    private val background by enumChoice("Background", BackgroundStyle.SHADOW)
    private val backgroundOpacity by float("BackgroundOpacity", 1f, 0f..1f)
    private val backgroundValue by float("BackgroundValue", 0f, 0f..1f)
    private val blurStrength by float("BlurStrength", 0f, 0f..10f)

    private val display by enumChoice("Display", Display.SPLIT)
    private val visibilityMode by enumChoice("Visibility", ModuleVisibility.ALL)
    private val renderTag by boolean("RenderTag", true)

    private val glow by boolean("Glow", true)
    private val glowStrength by float("GlowStrength", 1f, 0.1f..3f)

    private val boldText by boolean("BoldText", true)
    private val fontSize by float("FontSize", REFERENCE_FONT_SIZE, 6f..26f)

    private val offsetX by float("OffsetX", 5f, 0f..100f)
    private val offsetY by float("OffsetY", 2.5f, 0f..100f)

    // endregion

    private val rowStates = Reference2ObjectOpenHashMap<ClientModule, ArraylistRowState>()

    private val rows = ArrayList<ArraylistRowState>()

    private val lineSegments = ArrayList<LineSegment>()

    private var lastFrameNanos = 0L

    private var hoveredModule: ClientModule? = null

    private val boldStyle = Style.EMPTY.withBold(true)

    /**
     * One line of the `Outline` / `Bar` decoration, Solstice's `TempLineRenderInfo`.
     */
    private class LineSegment(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val color: Color4b,
    )

    override fun onDisabled() {
        rowStates.values.forEach { state -> state.animation = 0f }
        hoveredModule = null
        rows.clear()
        lineSegments.clear()
    }

    /**
     * Maps a length given in Solstice (physical) pixels into the LiquidBounce GUI space, keeping the
     * proportions of the original and following `FontSize`.
     */
    private fun unit(value: Float): Float = value * 0.5f * (fontSize / REFERENCE_FONT_SIZE)

    private fun frameDelta(): Float {
        val now = System.nanoTime()
        val last = lastFrameNanos
        lastFrameNanos = now
        if (last == 0L) {
            return 0f
        }

        return ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, MAX_FRAME_TIME)
    }

    private fun collectRows(deltaTime: Float, textScale: Float) {
        rows.clear()

        val font = FontManager.FONT_RENDERER
        val bold = boldText
        val style = if (bold) boldStyle else Style.EMPTY

        for (module in ModuleManager) {
            if (module.hidden) {
                continue
            }
            if (visibilityMode == ModuleVisibility.BOUND && module.bind.isUnbound) {
                continue
            }

            var state = rowStates[module]
            if (state == null) {
                state = ArraylistRowState(module)
                rowStates[module] = state
            }

            state.animation = Mth.lerp(
                deltaTime * ANIMATION_SPEED,
                state.animation,
                if (module.enabled) 1f else 0f,
            ).coerceIn(0f, 1f)

            if (state.animation < 0.01f) {
                continue
            }

            val tag = if (renderTag) module.tag else null
            val signature = "${module.name}\u0000$tag\u0000$bold\u0000$fontSize"
            if (state.textSignature != signature) {
                state.textSignature = signature

                val nameText = module.name.withFormat(style)
                val tagText = tag?.withFormat(style)

                state.nameText = nameText
                state.tagText = tagText
            }

            // The active font/atlas can change without the module name or font-size setting
            // changing. Measure with the same renderer and scale used by this frame's draw.
            state.nameWidth = state.nameText?.let { font.getStringWidth(it, false) * textScale } ?: 0f
            state.tagWidth = state.tagText?.let { font.getStringWidth(it, false) * textScale } ?: 0f

            rows.add(state)
        }

        // The original sorts by the width of the rendered name, longest first.
        rows.sortByDescending { it.contentWidth }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent>(priority = FIRST_PRIORITY) { event ->
        if (!inGame) {
            return@handler
        }

        val font = FontManager.FONT_RENDERER
        val textScale = fontSize / font.height
        val textHeight = font.height * textScale
        val (screenWidth, screenHeight) = mc.window.scaledDimension
        val deltaTime = frameDelta()
        val now = System.currentTimeMillis()

        collectRows(deltaTime, textScale)
        if (rows.isEmpty()) {
            return@handler
        }

        val withAccent = display == Display.BAR || display == Display.SPLIT
        val rightEdge = screenWidth - offsetX

        // The backdrop blur is a real gaussian pass over the frame, prepared at most once per frame.
        val frameScale = mc.window.width.toFloat() / screenWidth.coerceAtLeast(1).toFloat()
        val blurredBackdrop = if (blurStrength > 0f) {
            ArraylistBackdrop.prepare(blurStrength * frameScale)
        } else {
            null
        }

        val glowSigma = textHeight * 0.55f * glowStrength
        val backgroundSigma = textHeight * 0.8f

        with(event.context) {
            // ---- pass 1: glow and the split accent bar, behind the text -------------------------
            var y = offsetY
            for (row in rows) {
                row.y = y
                row.height = textHeight
                row.color = colors.color(y * 2f, now)

                val contentWidth = row.contentWidth
                val anchor = rightEdge - if (withAccent) unit(7f) else 0f
                val textX = Mth.lerp(row.animation, screenWidth + unit(14f), anchor - contentWidth)
                row.x = textX

                val color = row.color

                // `Outline` and `None` glow around the text rect, `Bar` and `Split` one around the accent.
                if (glow && !withAccent) {
                    drawGlow(
                        textX,
                        y,
                        textX + contentWidth,
                        y + textHeight,
                        color.fade(0.83f * row.animation),
                        glowSigma * row.animation,
                    )
                }

                if (display == Display.SPLIT) {
                    val rectRight = textX + contentWidth + unit(4f)
                    val barLeft = rectRight + unit(2f)
                    val barTop = y + unit(4f)
                    val barWidth = unit(4f)
                    val barHeight = textHeight - unit(6f)

                    if (barHeight > 0f) {
                        if (glow) {
                            drawGlow(
                                barLeft,
                                barTop,
                                barLeft + barWidth,
                                barTop + barHeight,
                                color.fade(0.7f * row.animation),
                                glowSigma * row.animation,
                            )
                        }

                        drawRoundedRect(
                            x1 = barLeft,
                            y1 = barTop,
                            x2 = barLeft + barWidth,
                            y2 = barTop + barHeight,
                            radius = unit(3f),
                            fillColor = color,
                        )
                    }
                }

                y += textHeight * row.animation
            }

            // ---- pass 2: backdrop, background, text ---------------------------------------------
            y = offsetY

            val mouseX = scaledMouseX()
            val mouseY = scaledMouseY()
            var hovered: ClientModule? = null

            for (row in rows) {
                row.y = y

                val contentWidth = row.contentWidth
                val anchor = rightEdge - if (withAccent) unit(7f) else 0f
                val textX = Mth.lerp(row.animation, screenWidth + unit(14f), anchor - contentWidth)
                row.x = textX

                val color = row.color

                // The original pads the rect by 3 on the left and 4 on the right.
                val rectLeft = textX - unit(3f)
                val rectTop = y
                val rectRight = textX + contentWidth + unit(4f)
                val rectBottom = y + textHeight

                // The rect the outline pass works with additionally carries the accent padding.
                val accentPadding = if (withAccent) unit(7f) else 0f
                row.rectLeft = rectLeft + accentPadding
                row.rectTop = rectTop
                row.rectRight = rectRight + accentPadding
                row.rectBottom = rectBottom

                if (blurredBackdrop != null) {
                    drawBackdrop(
                        blurredBackdrop,
                        rectLeft,
                        rectTop,
                        rectRight,
                        rectBottom,
                        screenWidth.toFloat(),
                        screenHeight.toFloat(),
                    )
                }

                if ((background == BackgroundStyle.SHADOW || background == BackgroundStyle.BOTH) &&
                    display != Display.OUTLINE
                ) {
                    drawGlow(
                        rectLeft,
                        rectTop,
                        rectRight,
                        rectBottom,
                        Color4b.BLACK.alpha((255 * backgroundOpacity).roundToInt().coerceIn(0, 255)),
                        backgroundSigma,
                    )
                }

                if (display == Display.OUTLINE) {
                    drawQuad(rectLeft, rectTop, rectRight, rectBottom, color.multiply(backgroundValue).alpha(102))
                }

                // Capture the layout coordinate under a different name. In the DrawParameters
                // receiver, `y = y` assigns its reset value (0) back to itself, NOT the row Y.
                val rowTextY = row.y
                row.nameText?.let { text ->
                    font.draw(text) {
                        this.x = textX
                        this.y = rowTextY
                        this.color = row.color
                        this.shadow = false
                        this.scale = textScale
                        this.horizontalAnchor = HorizontalAnchor.START
                        this.verticalAnchor = VerticalAnchor.TOP
                    }
                }

                row.tagText?.let { text ->
                    font.draw(text) {
                        this.x = textX + row.nameWidth
                        this.y = rowTextY
                        this.color = Color4b(230, 230, 230, 255)
                        this.shadow = false
                        this.scale = textScale
                        this.horizontalAnchor = HorizontalAnchor.START
                        this.verticalAnchor = VerticalAnchor.TOP
                    }
                }

                val isHovered = mouseX >= textX && mouseX <= textX + contentWidth &&
                    mouseY >= y && mouseY <= y + textHeight
                if (isHovered) {
                    hovered = row.module
                    drawQuad(rectLeft, rectTop, rectRight, rectBottom, Color4b.WHITE.alpha(26))
                }

                y += textHeight * row.animation
            }

            hoveredModule = hovered

            if (display == Display.NONE) {
                return@handler
            }

            // ---- pass 3: outline / bar lines -----------------------------------------------------
            buildLineSegments()

            val thickness = if (display == Display.OUTLINE) unit(2f) else unit(4f)
            for (line in lineSegments) {
                drawLineSegment(line.x1, line.y1, line.x2, line.y2, thickness, line.color)
            }
        }
    }

    /**
     * Literal port of the `backgroundRects` / `lines` loop of the original, including the "pick the
     * following row that reaches furthest to the left" logic that turns the per row rects into one
     * continuous outline around the staircase.
     */
    private fun buildLineSegments() {
        lineSegments.clear()

        var lowestRow: ArraylistRowState? = null
        var lowestStartX = Float.MAX_VALUE
        var startingRectLeft = 0f

        for ((index, row) in rows.withIndex()) {
            val startX = row.rectLeft - unit(2f)
            val endX = row.rectRight - unit(2f)

            var nextStartX: Float? = null
            for (i in index + 1 until rows.size) {
                val candidateStartX = rows[i].rectLeft - unit(2f)
                if (nextStartX == null || candidateStartX < nextStartX) {
                    nextStartX = candidateStartX
                }
            }

            when (display) {
                Display.OUTLINE -> {
                    // right side
                    lineSegments.add(
                        LineSegment(endX + unit(2f), row.rectTop, endX + unit(2f), row.rectBottom, row.color),
                    )

                    // left side, only where no following row reaches further to the left
                    if (nextStartX == null || nextStartX >= startX) {
                        lineSegments.add(LineSegment(startX, row.rectTop, startX, row.rectBottom, row.color))
                    } else {
                        lineSegments.add(LineSegment(nextStartX, row.rectTop, nextStartX, row.rectTop, row.color))
                    }

                    // top of the first row
                    if (index == 0) {
                        startingRectLeft = startX
                        lineSegments.add(
                            LineSegment(startX, row.rectTop, endX + unit(2f), row.rectTop, row.color),
                        )
                    }

                    // bottom of the row, or up to the next row when that one is wider
                    if (nextStartX == null) {
                        lineSegments.add(
                            LineSegment(startX, row.rectBottom, endX + unit(2f), row.rectBottom, row.color),
                        )
                    } else if (nextStartX >= startX && nextStartX - startX > unit(2f)) {
                        lineSegments.add(
                            LineSegment(startX, row.rectBottom, nextStartX - unit(1f), row.rectBottom, row.color),
                        )
                    }
                }

                Display.BAR -> {
                    lineSegments.add(LineSegment(endX, row.rectTop, endX, row.rectBottom, row.color))
                }

                else -> {}
            }

            if (startX < lowestStartX) {
                lowestStartX = startX
                lowestRow = row
            }
        }

        if (display == Display.OUTLINE && lowestRow != null) {
            lineSegments.add(
                LineSegment(
                    lowestStartX,
                    lowestRow.rectTop,
                    startingRectLeft + unit(2f),
                    lowestRow.rectTop,
                    lowestRow.color,
                ),
            )
        }
    }

    @Suppress("unused")
    private val clickHandler = handler<MouseButtonEvent> { event ->
        if (!event.isLeftClick || mc.gui.screen() != null) {
            return@handler
        }

        val module = hoveredModule ?: return@handler
        hoveredModule = null
        module.enabled = !module.enabled
    }

    private fun scaledMouseX(): Float =
        (mc.mouseHandler.xpos() * mc.window.guiScaledWidth / mc.window.width.coerceAtLeast(1)).toFloat()

    private fun scaledMouseY(): Float =
        (mc.mouseHandler.ypos() * mc.window.guiScaledHeight / mc.window.height.coerceAtLeast(1)).toFloat()

    private fun Color4b.multiply(value: Float): Color4b = Color4b(
        (r * value).roundToInt().coerceIn(0, 255),
        (g * value).roundToInt().coerceIn(0, 255),
        (b * value).roundToInt().coerceIn(0, 255),
        a,
    )

    /**
     * Paints the pre blurred frame inside the row rect, the equivalent of Solstice's clipped
     * `D2D` blur fill.
     */
    private fun GuiGraphicsExtractor.drawBackdrop(
        backdrop: TextureSetup,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        screenWidth: Float,
        screenHeight: Float,
    ) {
        drawTexQuad(
            backdrop,
            x1,
            y1,
            x2,
            y2,
            x1 / screenWidth,
            y1 / screenHeight,
            x2 / screenWidth,
            y2 / screenHeight,
        )
    }

    /**
     * Solstice draws its outlines with `ImDrawList::AddLine`, whose thickness is 2px for `Outline`
     * and 4px for `Bar`.
     */
    private fun GuiGraphicsExtractor.drawLineSegment(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        thickness: Float,
        color: Color4b,
    ) {
        val half = thickness * 0.5f
        drawQuad(
            minOf(x1, x2) - half,
            minOf(y1, y2) - half,
            maxOf(x1, x2) + half,
            maxOf(y1, y2) + half,
            color,
        )
    }

}
