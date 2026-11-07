package net.ccbluex.liquidbounce.features.module.modules.render.samsara

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.ChoiceListValue
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.*

/** Opai-native approximation. Unsupported LB setting types remain editable in LB's own GUI. */
internal class SamsaraOpaiScreen(private val color: OpaiColor) : Screen(Component.literal("Samsara Opai")) {
    private val palette get() = color.palette
    private val categories = listOf("Combat", "Movement", "Player", "Visual", "Misc")
    private val expanded = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val motions = IdentityHashMap<Any, Motion>()
    private val enabledMotions = IdentityHashMap<Any, Motion>()
    private val hoverMotions = IdentityHashMap<Any, Motion>()
    private val scroll = FloatArray(5)
    private val scrollTarget = FloatArray(5)
    private val hits = mutableListOf<Hit>()
    private var horizontal = 0f
    private var maxHorizontal = 0f
    private var colW = 152f
    private var openedAt = 0.0
    private var previousFrame = 0.0
    private val menu = Motion(0f, 30.0)
    private var opacity = 0f
    private var menuScale = 1f
    private var centerY = 0f
    private var closing = false
    private var closedAt = 0.0
    private var closeOpacity = 0f
    private var closeScale = 1f
    private var dragging: Hit? = null
    private var scrollingColumn: Int? = null
    private var pendingHit: Hit? = null
    private var pendingRight = false
    private var moved = false
    private var totalDrag = 0.0
    private data class Row(val label: String, val module: ClientModule?, val value: Value<*>?,
                           val depth: Int, val reveal: Float) {
        val height: Float get() = if (value is ChoiceListValue<*> || value is ModeValueGroup<*>) 40f
                                  else if (value is RangedValue<*> && value.get() is Number) 20f else 19.6f
    }
    private data class Hit(val x: Float, val y: Float, val width: Float, val height: Float,
                           val column: Int, val row: Row)
    /** Equivalent to Samsara OpaiMotion: real-time exponential easing, preserving prior destination. */
    private class Motion(private var value: Float, private val rate: Double) {
        private var destination = value
        private var time = Double.NaN
        fun approach(target: Float, now: Double): Float {
            if (!time.isNaN()) {
                val dt = max(0.0, now - time) / 1000.0
                value = destination + (value - destination) * exp(-rate * dt).toFloat()
                if (abs(value - destination) < .0005f) value = destination
            }
            time = now
            destination = target
            return value
        }
        fun snap(v: Float, now: Double) { value = v; destination = v; time = now }
    }
    private fun now() = System.nanoTime() / 1_000_000.0
    private fun category(module: ClientModule) = when (module.category.tag.lowercase()) {
        "combat" -> 0; "movement" -> 1; "player", "world" -> 2; "render", "visual" -> 3; else -> 4
    }
    private fun rows(column: Int, time: Double): List<Row> {
        val result = mutableListOf<Row>()
        fun fraction(item: Any, shown: Boolean): Float =
            motions.getOrPut(item) { Motion(0f, 40.0) }.approach(if (shown) 1f else 0f, time)
        fun children(group: ValueGroup, depth: Int, reveal: Float) {
            if (depth > 8 || reveal <= .0005f) return
            group.containedValues.forEach { v ->
                if (v.notAnOption || !v.visibleCondition.asBoolean || v.name == "Bind" || v.name == "Hidden") return@forEach
                result += Row(v.name, null, v, depth, reveal)
                if (v is ValueGroup) {
                    val f = reveal * fraction(v, v in expanded)
                    if (v is ModeValueGroup<*>) children(v.activeMode, depth + 1, f) else children(v, depth + 1, f)
                }
            }
        }
        ModuleManager.filter { category(it) == column && !it.hidden }.sortedBy { it.name.lowercase() }.forEach { m ->
            result += Row(m.name, m, null, 0, 1f)
            val f = fraction(m, m in expanded)
            children(m, 1, f)
        }
        return result
    }
    override fun init() {
        super.init()
        openedAt = now(); previousFrame = openedAt; closing = false
        menu.snap(0f, openedAt); menu.approach(1f, openedAt)
    }
    override fun isPauseScreen() = false
    override fun extractBackground(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (mc.level == null) extractPanorama(context, delta)
        extractBlurredBackground(context)
        context.fill(0, 0, width, height, 0x00000000)
        mc.gui.hud.extractDeferredSubtitles()
    }
    private fun tint(color: Int, factor: Float = 1f): Int {
        val a = ((color ushr 24) * opacity * factor.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0xFFFFFF)
    }
    private fun alpha(c: Int, f: Float): Int =
        (((c ushr 24) * f.coerceIn(0f, 1f)).roundToInt() shl 24) or (c and 0xFFFFFF)
    private fun mix(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        fun channel(shift: Int): Int = (((a ushr shift and 255) * (1 - t)) + ((b ushr shift and 255) * t)).roundToInt()
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    private fun rect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, r: Float, c: Int) =
        OpaiRender.rect(ctx, x, y, w, h, r, tint(c))
    private fun text(ctx: GuiGraphicsExtractor, str: String, x: Float, centerY: Float, size: Float, c: Int, maxWidth: Float) {
        val label = fit(str, maxWidth, size)
        OpaiLbFont.text(ctx, label, x, centerY - size / 2, size, tint(c))
        // Samsara draws the normal label a second time with a 0.1px offset for weight.
        OpaiLbFont.text(ctx, label, x + .1f, centerY - size / 2, size, tint(c))
    }
    private fun localX(x: Double) = width / 2f + (x.toFloat() - width / 2f) / menuScale
    private fun localY(y: Double) = centerY + (y.toFloat() - centerY) / menuScale
    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val time = now()
        val frameDt = max(0.0, time - previousFrame); previousFrame = time
        opacity = if (closing) closeOpacity * exp(-30 * (time - closedAt) / 1000).toFloat() else menu.approach(1f, time)
        menuScale = if (closing) closeScale + .6f * (1 - opacity / max(.001f, closeOpacity))
                    else if (time - openedAt >= 320) 1f else 1f + (.18 * exp(-17 * (time - openedAt) / 1000) * cos(23 * (time - openedAt) / 1000)).toFloat()
        if (opacity <= .01f) {
            if (closing) { mc.gui.setScreen(null); return }
            hits.clear(); return
        }
        hits.clear()
        val margin = min(30f, width * .0382f)
        colW = min(152f, width - margin * 2)
        val top = max(8f, height * .112f)
        val maxBody = min(16 * 19.6f + 7f, max(19.6f, height - top - 21f - max(8f, height * .1f)))
        centerY = top + (21f + maxBody) / 2
        maxHorizontal = max(0f, margin * 2 + 5 * colW + 4 * 8f - width)
        horizontal = horizontal.coerceIn(0f, maxHorizontal)
        val mx = localX(mouseX.toDouble()); val my = localY(mouseY.toDouble())
        ctx.pose().pushMatrix()
        try {
            ctx.pose().translate(width / 2f, centerY)
            ctx.pose().scale(menuScale, menuScale)
            ctx.pose().translate(-width / 2f, -centerY)
            for (column in 0..4) {
                val x = margin + column * (colW + 8f) - horizontal
                if (x + colW < -20 || x > width + 20) continue
                val rows = rows(column, time)
                val contentHeight = rows.sumOf { (it.height * it.reveal).toDouble() }.toFloat()
                val body = min(maxBody, contentHeight + 7f)
                val maxScroll = max(0f, contentHeight - (body - 7f))
                scrollTarget[column] = scrollTarget[column].coerceIn(0f, maxScroll)
                val smoothing = (1 - exp(-frameDt * .014)).toFloat()
                scroll[column] += (scrollTarget[column] - scroll[column]) * smoothing
                // Square upper edge and rounded lower edge, without stacking translucent fills.
                val bodyTop = top + 21f
                val square = min(7f, body)
                rect(ctx, x, bodyTop, colW, square, 0f, palette.body())
                if (body > square) {
                    OpaiRender.clip(ctx, x, bodyTop + square, x + colW, bodyTop + body) {
                        rect(ctx, x, bodyTop, colW, body, 7f, palette.body())
                    }
                }
                rect(ctx, x, top, colW, 21f, 7f, palette.header())
                rect(ctx, x, top + 14, colW, 7f, 0f, palette.header())
                text(ctx, categories[column], x + 10, top + 10.5f, 9.2f, palette.text(), colW - 20)
                val bodyY = top + 21f
                if (body > 7f) {
                    OpaiRender.clip(ctx, x, bodyY, x + colW, bodyY + body - 7) {
                        var contentY = bodyY - scroll[column]
                        rows.forEach { row ->
                            val y = contentY
                            val visible = row.height * row.reveal
                            contentY += visible
                            if (y + visible <= bodyY || y >= bodyY + body - 7 || visible <= .01f) return@forEach
                            OpaiRender.clip(ctx, x, y, x + colW, y + visible) {
                                drawRow(ctx, row, x, y, mx, my, time)
                            }
                            if (visible >= row.height - .5f && y >= bodyY && y + row.height <= bodyY + body - 7)
                                hits += Hit(x, y, colW, row.height, column, row)
                        }
                    }
                }
                if (maxScroll > 0) {
                    val track = max(1f, body - 7f)
                    val bar = max(12f, track * track / max(track, contentHeight))
                    val offset = (track - bar) * scroll[column] / maxScroll
                    rect(ctx, x + colW - 3.5f, bodyY + offset, 2.5f, bar, 1.25f, palette.scrollbar())
                }
            }
            if (maxHorizontal > 0) {
                rect(ctx, 5f, 3f, 25f, 15f, 4f, palette.header()); text(ctx, "<", 14f, 10.5f, 9f, palette.text(), 12f)
                rect(ctx, width - 30f, 3f, 25f, 15f, 4f, palette.header()); text(ctx, ">", width - 21f, 10.5f, 9f, palette.text(), 12f)
            }
        } finally { ctx.pose().popMatrix() }
    }
    private fun drawRow(ctx: GuiGraphicsExtractor, row: Row, x: Float, y: Float, mx: Float, my: Float, time: Double) {
        val m = row.module; val v = row.value
        val label = "  ".repeat((row.depth - 1).coerceIn(0, 3)) + OpaiRender.spaced(row.label)
        if (m != null) {
            val active = enabledMotions.getOrPut(m) { Motion(if (m.enabled) 1f else 0f, 30.0) }.approach(if (m.enabled) 1f else 0f, time)
            val over = mx >= x && mx < x + colW && my >= y && my < y + row.height
            val hover = hoverMotions.getOrPut(m) { Motion(0f, 30.0) }.approach(if (over) 1f else 0f, time)
            if (hover > .01f && active < .99f) rect(ctx, x, y, colW, row.height, 0f, alpha(palette.hover(), hover * (1 - active)))
            if (active > .01f) rect(ctx, x, y, colW, row.height, 0f, alpha(mix(palette.enabled(), palette.enabledHover(), hover), active))
            text(ctx, label, x + 10, y + row.height / 2, 8.3f, mix(palette.text(), palette.enabledText(), active), colW - 20)
            return
        }
        if (v == null) return
        when {
            v is ToggleableValueGroup || v.get() is Boolean -> {
                val on = if (v is ToggleableValueGroup) v.enabled else v.get() == true
                val t = enabledMotions.getOrPut(v) { Motion(if (on) 1f else 0f, 30.0) }.approach(if (on) 1f else 0f, time)
                text(ctx, label, x + 10, y + 9.8f, 8.5f, palette.text(), colW - 50)
                val bx = x + colW - 36; val by = y + 1.8f
                rect(ctx, bx, by, 26f, 16f, 8f, mix(palette.toggleOutline(), palette.accent(), t))
                if (t < .99f) rect(ctx, bx + 1, by + 1, 24f, 14f, 7f, alpha(palette.field(), 1 - t))
                val knob = bx + 2 + 10 * t
                rect(ctx, knob, by + 2, 12f, 12f, 6f, mix(palette.fieldLine(), palette.enabledText(), t))
                if (t > .01f) {
                    OpaiRender.line(ctx, knob + 3, by + 8, knob + 5.3f, by + 10, 1f, tint(palette.accent(), t))
                    OpaiRender.line(ctx, knob + 5.3f, by + 10, knob + 9, by + 5.5f, 1f, tint(palette.accent(), t))
                }
            }
            v is RangedValue<*> && v.get() is Number -> {
                val lo = (v.range.start as? Number)?.toDouble() ?: return
                val hi = (v.range.endInclusive as? Number)?.toDouble() ?: return
                val value = v.get() as Number
                val f = if (hi > lo) ((value.toDouble() - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) else 0f
                val motion = enabledMotions.getOrPut(v) { Motion(f, 35.0) }
                if (dragging?.row?.value === v) motion.snap(f, time)
                val displayed = motion.approach(f, time)
                val valueText = value.toString()
                val valueWidth = min((colW - 20) * .45f, OpaiLbFont.width(valueText, 8.5f))
                text(ctx, label, x + 10, y + 6.5f, 8.5f, palette.text(), colW - 24 - valueWidth)
                text(ctx, valueText, x + colW - 10 - valueWidth, y + 6.5f, 8.5f, palette.text(), valueWidth + .1f)
                val track = colW - 20; val cy = y + 15.6f; val knob = x + 10 + track * displayed
                rect(ctx, x + 10, cy - 1.25f, track, 2.5f, 1.25f, palette.track())
                rect(ctx, x + 10, cy - 1.25f, track * displayed, 2.5f, 1.25f, palette.accent())
                rect(ctx, knob - 4.5f, cy - 3.5f, 9f, 9f, 4.5f, 0x33000000)
                rect(ctx, knob - 4, cy - 4, 8f, 8f, 4f, palette.accent())
            }
            v is ChoiceListValue<*> || v is ModeValueGroup<*> -> {
                val detail = if (v is ChoiceListValue<*>) v.get().tag else (v as ModeValueGroup<*>).activeMode.name
                text(ctx, label, x + 10, y + 8, 8.5f, palette.text(), colW - 20)
                rect(ctx, x + 10, y + 16, colW - 20, 21f, 2f, palette.field())
                text(ctx, OpaiRender.spaced(detail), x + 16, y + 26.5f, 8.5f, palette.text(), colW - 32)
                rect(ctx, x + 11, y + 36, colW - 22, 1f, 0f, palette.fieldLine())
            }
            else -> {
                text(ctx, label, x + 10, y + 9.8f, 8.5f, palette.text(), colW - 30)
                text(ctx, if (v is ValueGroup) if (v in expanded) "−" else "+" else "…", x + colW - 19, y + 9.8f, 8.5f, palette.text(), 12f)
            }
        }
    }
    private fun fit(text: String, available: Float, size: Float): String {
        if (OpaiLbFont.width(text, size) <= available) return text
        var end = text.length
        while (end > 0 && OpaiLbFont.width(text.substring(0, end) + "…", size) > available)
            end = text.offsetByCodePoints(end, -1)
        return if (end == 0) "" else text.substring(0, end) + "…"
    }
    private fun flip(item: Any) { if (!expanded.add(item)) expanded.remove(item) }
    private fun persist() { ConfigSystem.store(ModuleManager.modulesConfig) }
    private fun number(v: RangedValue<*>, fraction: Float) {
        val lo = (v.range.start as? Number)?.toDouble() ?: return
        val hi = (v.range.endInclusive as? Number)?.toDouble() ?: return
        val target = lo + (hi - lo) * fraction.coerceIn(0f, 1f)
        val value = when (v.get()) {
            is Int -> target.roundToInt().toString()
            is Float -> (round(target * 100) / 100).toFloat().toString()
            is Double -> (round(target * 100) / 100).toString()
            else -> return
        }
        runCatching { v.setByString(value) }
    }
    override fun mouseClicked(click: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (closing) return true
        val x = localX(click.x); val y = localY(click.y)
        if (click.button() != InputConstants.MOUSE_BUTTON_LEFT && click.button() != InputConstants.MOUSE_BUTTON_RIGHT) return false
        if (y < 20 && maxHorizontal > 0) {
            if (x < 35) horizontal = (horizontal - colW - 8).coerceAtLeast(0f)
            else if (x > width - 35) horizontal = (horizontal + colW + 8).coerceAtMost(maxHorizontal)
            return true
        }
        val hit = hits.lastOrNull { x >= it.x && x < it.x + it.width && y >= it.y && y < it.y + it.height } ?: return false
        scrollingColumn = hit.column; pendingHit = hit
        pendingRight = click.button() == InputConstants.MOUSE_BUTTON_RIGHT || x > hit.x + hit.width - 22
        moved = false; totalDrag = 0.0
        val range = hit.row.value as? RangedValue<*>
        if (range != null && range.get() is Number) {
            dragging = hit; number(range, (x - hit.x - 10) / (hit.width - 20))
        }
        return true
    }
    private fun activate(hit: Hit, right: Boolean) {
        val row = hit.row; val m = row.module
        if (m != null) { if (right) flip(m) else { m.enabled = !m.enabled; persist() }; return }
        val v = row.value ?: return
        when (v) {
            is ModeValueGroup<*> -> if (right) flip(v) else {
                val choices = v.modes; if (choices.isNotEmpty()) v.setByString(choices[(choices.indexOf(v.activeMode) + 1) % choices.size].name)
                persist()
            }
            is ToggleableValueGroup -> if (right) flip(v) else { v.enabled = !v.enabled; persist() }
            is ValueGroup -> flip(v)
            is ChoiceListValue<*> -> {
                val choices = v.choices.toList(); if (choices.isNotEmpty()) v.setByString(choices[(choices.indexOf(v.get()) + 1) % choices.size].tag)
                persist()
            }
            is RangedValue<*> -> Unit
            else -> if (v.get() is Boolean) { v.setByString((v.get() != true).toString()); persist() }
        }
    }
    override fun mouseDragged(click: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (closing) return true
        val hit = dragging
        if (hit != null) {
            val value = hit.row.value as? RangedValue<*> ?: return false
            number(value, (localX(click.x) - hit.x - 10) / (hit.width - 20)); return true
        }
        val column = scrollingColumn ?: return super.mouseDragged(click, dx, dy)
        totalDrag += abs(dy) + abs(dx)
        if (totalDrag > 4) moved = true
        if (moved) scrollTarget[column] = (scrollTarget[column] - dy.toFloat() / menuScale).coerceAtLeast(0f)
        return true
    }
    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        val handled = dragging != null || scrollingColumn != null
        if (dragging != null) persist() else if (!moved) pendingHit?.let { activate(it, pendingRight) }
        dragging = null; pendingHit = null; scrollingColumn = null
        return if (handled) true else super.mouseReleased(click)
    }
    override fun mouseScrolled(mx: Double, my: Double, horizontal: Double, vertical: Double): Boolean {
        val margin = min(30f, width * .0382f)
        val column = ((localX(mx) + this.horizontal - margin) / (colW + 8f)).toInt()
        if (column in 0..4) { scrollTarget[column] = (scrollTarget[column] - vertical.toFloat() * 28f).coerceAtLeast(0f); return true }
        return false
    }
    override fun onClose() {
        if (closing) return
        dragging = null; pendingHit = null; scrollingColumn = null; persist()
        closing = true; closedAt = now(); closeOpacity = opacity; closeScale = menuScale
    }
}
