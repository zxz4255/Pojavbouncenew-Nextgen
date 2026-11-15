/* GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PlayerPostTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.Timer
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.minecraft.world.item.BlockItem
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Screenshot-inspired center card, independent of the top dynamic island. */
object ModuleScaffoldCounter : ClientModule("ScaffoldCounter", ModuleCategories.RENDER) {
    private enum class Style(override val tag: String) : Tagged { CLASSIC("Classic"), WAVE("Wave") }
    private val style by enumChoice("Style", Style.CLASSIC)
    private val scale by float("Scale", 1f, 0.5f..2f)
    private val offsetX by float("OffsetX", 0f, -400f..400f)
    private val offsetY by float("OffsetY", 0f, -400f..400f)
    private val motion = OpaiScaffoldMotion()
    private const val WIDTH = 82f
    private const val HEIGHT = 38f
    private const val BADGE_X = 22f
    private const val BADGE_Y = 19f
    private const val WAVE_WIDTH = 132f
    private const val WAVE_HEIGHT = 34f
    private var animatedRemaining = -1f
    private var lastBarNanos = 0L
    private var maxBlocks = 0
    private var counterPlayer: Any? = null
    private var counterWorld: Any? = null

    // Cached geometry: an eight-lobed outline like the badge visible in the reference screenshot.
    private val badge: List<IslandPolygon> by lazy {
        val segments = 64
        (0 until segments).map { i ->
            fun point(index: Int): Pair<Float, Float> {
                val a = 2.0 * PI * index / segments
                val radius = 9.1 + 1.3 * cos(8.0 * a)
                return (cos(a) * radius).toFloat() to (sin(a) * radius).toFloat()
            }
            val a = point(i); val b = point(i + 1)
            IslandGeometry.capsule(a.first, a.second, b.first, b.second, 0.9f)
        }
    }

    @Suppress("unused")
    private val tick = handler<PlayerPostTickEvent> {
        val p = mc.player
        val w = mc.level
        if (!ModuleScaffold.running || p == null || w == null) {
            motion.reset()
            resetCounter()
        } else motion.sample(p, w, p.tickCount.toLong(), p.x, p.z, Timer.timerSpeed.toDouble())
    }

    @Suppress("unused")
    private val overlay = handler<OverlayRenderEvent>(priority = EventPriorityConvention.READ_FINAL_STATE) { event ->
        val p = mc.player ?: return@handler
        val world = mc.level ?: return@handler
        if (!ModuleScaffold.running || mc.gui.hud.isHidden || mc.gui.overlay() != null) return@handler
        var blocks = 0
        for (slot in 0 until 9) {
            val stack = p.inventory.getItem(slot)
            if (stack.item is BlockItem) blocks += stack.count
        }
        if (counterPlayer !== p || counterWorld !== world) {
            resetCounter()
            counterPlayer = p
            counterWorld = world
        }
        maxBlocks = max(maxBlocks, blocks)
        val remaining = if (maxBlocks > 0) blocks.toFloat() / maxBlocks else 0f
        val now = System.nanoTime()
        val dt = if (lastBarNanos == 0L) 0f else ((now - lastBarNanos) / 1_000_000_000f).coerceIn(0f, .1f)
        lastBarNanos = now
        if (animatedRemaining < 0f) animatedRemaining = remaining
        else animatedRemaining += (remaining - animatedRemaining) * (1f - kotlin.math.exp(-10f * dt))
        val width = if (style == Style.WAVE) WAVE_WIDTH else WIDTH
        val height = if (style == Style.WAVE) WAVE_HEIGHT else HEIGHT
        val ctx = event.context
        val window = mc.window
        val s = scale.coerceIn(0.5f, 2f)
        val x = window.guiScaledWidth * .5f - width * s * .5f + offsetX
        val y = window.guiScaledHeight * (if (style == Style.WAVE) .78f else .62f) - height * s * .5f + offsetY
        ctx.pose().withPush {
            ctx.pose().translate(x, y)
            ctx.pose().scale(s, s)
            val canvas = IslandCanvas(ctx, s, y, window.guiScale, window.guiScaledWidth, window.guiScaledHeight)
            if (style == Style.WAVE) {
                drawWaveCard(ctx, canvas, blocks, remaining, motion.speed(p, world), s, window.guiScale)
                return@withPush
            }
            canvas.shadow(0f, 0f, WIDTH, HEIGHT, 12f)
            canvas.rounded(0f, 0f, WIDTH, HEIGHT, 12f, 0xc03a3a3a.toInt())
            val batch = IslandBatch()
            for (shape in badge) shape.emit(batch, BADGE_X, BADGE_Y, 1f, 0xffbfc0c2.toInt(), 1f / (s * max(1, window.guiScale)))
            batch.flush(ctx)
            val count = blocks.toString()
            val numSize = if (count.length > 3) 6f else 7f
            val countX = BADGE_X - IslandFonts.measure(count, numSize) * .5f
            IslandFonts.draw(ctx, count, countX, BADGE_Y, numSize, 0xffeeeeee.toInt())
            IslandFonts.draw(ctx, "Blocks", 41f, 11.5f, 6.8f, 0xffeeeeee.toInt())
            val bps = String.format(Locale.ROOT, "%.1f BPS", motion.speed(p, world))
            IslandFonts.draw(ctx, bps, 41f, 20.5f, 6.3f, 0xffcbcbcb.toInt())
            // Classic used to have no quantity-dependent bar at all. Max is the highest
            // hotbar count in this Scaffold session; interpolating avoids jumps on placement.
            canvas.rounded(40f, 29f, 33f, 2.5f, 1.25f, 0x744f5052)
            val fill = 33f * animatedRemaining.coerceIn(0f, 1f)
            if (fill > 0.1f) canvas.rounded(40f, 29f, fill, 2.5f, minOf(1.25f, fill * .5f), 0xffdadbdd.toInt())
        }
    }

    private fun drawWaveCard(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor, canvas: IslandCanvas,
        blocks: Int, remaining: Float, bps: Double, scale: Float, guiScale: Int,
    ) {
        canvas.shadow(0f, 0f, WAVE_WIDTH, WAVE_HEIGHT, 8f)
        canvas.rounded(0f, 0f, WAVE_WIDTH, WAVE_HEIGHT, 8f, 0xd824303b.toInt())
        val white = 0xffe3f3f5.toInt()
        val muted = 0xffa7b8c0.toInt()
        IslandFonts.draw(ctx, "Blocks", 6f, 7f, 6.4f, white)
        val count = blocks.toString()
        IslandFonts.draw(ctx, count, WAVE_WIDTH - 7f - IslandFonts.measure(count, 6.4f), 7f, 6.4f, white)
        IslandFonts.draw(ctx, String.format(Locale.ROOT, "%.1f BPS", bps), 6f, 16f, 6.2f, white)
        val label = "${(remaining.coerceIn(0f, 1f) * 100f).toInt()}% remaining"
        IslandFonts.draw(ctx, label, WAVE_WIDTH - 7f - IslandFonts.measure(label, 5.3f), 16f, 5.3f, muted)

        val begin = 6f
        val end = WAVE_WIDTH - 18f
        val cutoff = begin + (end - begin) * remaining.coerceIn(0f, 1f)
        val phase = (System.nanoTime() % 6_000_000_000L).toDouble() / 6_000_000_000.0 * 2.0 * PI
        val fringe = 1f / (scale * max(1, guiScale))
        // A soft under-stroke and a thinner pale stroke approximate the screenshot glow without
        // a blur pipeline. The unfilled tail is deliberately dark rather than claiming 100%.
        fun stroke(width: Float, color: Int) {
            val batch = IslandBatch()
            val steps = 96
            for (i in 0 until steps) {
                val x1 = begin + (end - begin) * i / steps
                val x2 = minOf(cutoff, begin + (end - begin) * (i + 1) / steps)
                if (x2 <= x1) break
                fun wave(x: Float) = (26f + 1.45 * sin((x - begin) * (2.0 * PI / 16.0) + phase)).toFloat()
                IslandGeometry.capsule(x1, wave(x1), x2, wave(x2), width / 2f)
                    .emit(batch, 0f, 0f, 1f, color, fringe)
            }
            batch.flush(ctx)
        }
        canvas.line(begin, 26f, end, 26f, 1.2f, 0x74505c62)
        if (cutoff > begin) {
            stroke(4.0f, 0x2875efff)
            stroke(2.2f, 0x889befff.toInt())
            stroke(1.1f, 0xffd7ffff.toInt())
        }
        canvas.line(WAVE_WIDTH - 12f, 26f, WAVE_WIDTH - 10f, 26f, 1f, white)
        canvas.line(WAVE_WIDTH - 11f, 25f, WAVE_WIDTH - 11f, 27f, 1f, white)
    }

    private fun resetCounter() { maxBlocks = 0; animatedRemaining = -1f; lastBarNanos = 0L; counterPlayer = null; counterWorld = null }
    override fun onDisabled() { motion.reset(); resetCounter() }
}
