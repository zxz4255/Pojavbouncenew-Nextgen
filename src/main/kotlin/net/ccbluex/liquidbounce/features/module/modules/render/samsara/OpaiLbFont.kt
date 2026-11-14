package net.ccbluex.liquidbounce.features.module.modules.render.samsara

import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/** Uses the font configured by LiquidBounce (Inter Regular when registered), including its fallback glyphs. */
internal object OpaiLbFont {
    fun width(text: String, size: Float): Float {
        if (text.isEmpty()) return 0f
        val renderer = FontManager.FONT_RENDERER
        return renderer.getStringWidth(Component.literal(text), size / renderer.height)
    }
    fun text(ctx: GuiGraphicsExtractor, text: String, x: Float, y: Float, size: Float, color: Int) {
        if (text.isEmpty() || color ushr 24 == 0 || !OpaiRender.visibleScissor(ctx)) return
        val renderer = FontManager.FONT_RENDERER
        renderer.draw(ctx, Component.literal(text), x, y, Color4b(color), false, size / renderer.height)
    }
}
