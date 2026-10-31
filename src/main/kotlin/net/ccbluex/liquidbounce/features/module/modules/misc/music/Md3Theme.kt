package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.min

/** Material 3 风格色板与圆角绘制（26.3 GuiGraphics.fill 使用 Int 坐标）。 */
object Md3Theme {
    const val SURFACE = 0xFF1C1B1F.toInt()
    const val SURFACE_CONTAINER = 0xFF2B2930.toInt()
    const val SURFACE_HIGH = 0xFF36343B.toInt()
    const val PRIMARY = 0xFFD0BCFF.toInt()
    const val ON_PRIMARY = 0xFF381E72.toInt()
    const val PRIMARY_CONTAINER = 0xFF4F378B.toInt()
    const val ON_SURFACE = 0xFFE6E1E5.toInt()
    const val ON_SURFACE_VARIANT = 0xFFCAC4D0.toInt()
    const val OUTLINE = 0xFF938F99.toInt()
    const val ERROR = 0xFFF2B8B5.toInt()

    fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    fun fillRound(g: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        val xi = x.toInt()
        val yi = y.toInt()
        val wi = w.toInt().coerceAtLeast(1)
        val hi = h.toInt().coerceAtLeast(1)
        val rad = min(r.toInt(), min(wi, hi) / 2).coerceAtLeast(0)
        g.fill(xi + rad, yi, xi + wi - rad, yi + hi, color)
        g.fill(xi, yi + rad, xi + rad, yi + hi - rad, color)
        g.fill(xi + wi - rad, yi + rad, xi + wi, yi + hi - rad, color)
        fillCircleQuarter(g, xi + rad, yi + rad, rad, color, 0)
        fillCircleQuarter(g, xi + wi - rad, yi + rad, rad, color, 1)
        fillCircleQuarter(g, xi + rad, yi + hi - rad, rad, color, 2)
        fillCircleQuarter(g, xi + wi - rad, yi + hi - rad, rad, color, 3)
    }

    private fun fillCircleQuarter(
        g: GuiGraphicsExtractor,
        cx: Int,
        cy: Int,
        r: Int,
        color: Int,
        quarter: Int,
    ) {
        val ri = r.coerceAtLeast(1)
        for (dy in 0..ri) {
            for (dx in 0..ri) {
                if (dx * dx + dy * dy > ri * ri) continue
                val px = when (quarter) {
                    0 -> cx - dx
                    1 -> cx + dx
                    2 -> cx - dx
                    else -> cx + dx
                }
                val py = when (quarter) {
                    0, 1 -> cy - dy
                    else -> cy + dy
                }
                g.fill(px, py, px + 1, py + 1, color)
            }
        }
    }
}
