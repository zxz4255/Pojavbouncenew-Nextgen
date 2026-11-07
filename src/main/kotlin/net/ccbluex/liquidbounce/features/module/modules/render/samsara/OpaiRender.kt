package net.ccbluex.liquidbounce.features.module.modules.render.samsara

import com.samsara.ui.clickgui.opai.OpaiStyle
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.floor
import kotlin.math.ceil

/** Native GUI backend, replacing Samsara's NanoVG calls. */
internal enum class OpaiColor(override val tag: String) : Tagged {
    Lavender("Lavender"), LightPink("Light Pink");
    val palette get() = if (this == LightPink) OpaiStyle.LIGHT_PINK else OpaiStyle.LAVENDER
}

internal object OpaiRender {
    /** Never submit a draw under Minecraft's empty (0x0) intersected scissor. */
    fun visibleScissor(ctx: GuiGraphicsExtractor): Boolean {
        val clipped = ctx.scissorStack.peek() ?: return true
        return clipped.width() > 0 && clipped.height() > 0
    }
    inline fun clip(ctx: GuiGraphicsExtractor, x0: Float, y0: Float, x1: Float, y1: Float, draw: () -> Unit) {
        if (!x0.isFinite() || !y0.isFinite() || !x1.isFinite() || !y1.isFinite() || x1 <= x0 || y1 <= y0) return
        ctx.enableScissor(floor(x0).toInt(), floor(y0).toInt(), ceil(x1).toInt(), ceil(y1).toInt())
        try {
            // Check the same scissor exposed to GUI draw-state submission. A positive input
            // can become empty after transformation/intersection with the screen or parent.
            // Minecraft 26.3 peek() exposes the oldest entry when scissors are nested.
            if (visibleScissor(ctx)) draw()
        } finally { ctx.disableScissor() }
    }
    fun rect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        if (w <= 0 || h <= 0 || color ushr 24 == 0 || !visibleScissor(ctx)) return
        ctx.drawRoundedRect(x, y, x + w, y + h, radius, Color4b(color))
    }
    fun width(text: String, size: Float) = mc.font.width(text) * size / mc.font.lineHeight.toFloat()
    fun text(ctx: GuiGraphicsExtractor, text: String, x: Float, y: Float, size: Float, color: Int) {
        if (text.isEmpty() || color ushr 24 < 4 || !visibleScissor(ctx)) return
        val f = size / mc.font.lineHeight.toFloat()
        ctx.pose().pushMatrix()
        try {
            ctx.pose().translate(x, y)
            ctx.pose().scale(f, f)
            ctx.text(mc.font, text, 0, 0, color, false)
        } finally { ctx.pose().popMatrix() }
    }
    fun line(ctx: GuiGraphicsExtractor, x1: Float, y1: Float, x2: Float, y2: Float, stroke: Float, color: Int) {
        val dx = x2 - x1; val dy = y2 - y1
        val len = hypot(dx, dy)
        if (len < 0.01f) return
        ctx.pose().pushMatrix()
        try {
            ctx.pose().translate(x1, y1)
            ctx.pose().rotate(atan2(dy, dx))
            rect(ctx, 0f, -stroke / 2, len, stroke, stroke / 2, color)
        } finally { ctx.pose().popMatrix() }
    }
    fun spaced(name: String): String = name
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
}
