package net.ccbluex.liquidbounce.features.module.modules.render.samsara

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.utils.client.mc
import java.util.Locale
import kotlin.math.exp

/** Samsara Opai ArrayList layout/critically damped motion, adapted to LB modules and native GUI. */
object ModuleSamsaraArrayList : ClientModule("SamsaraArrayList", ModuleCategories.RENDER) {
    private val color by enumChoice("Color", OpaiColor.Lavender)
    private val lowercase by boolean("Lowercase", false)
    private val suffix by boolean("ShowSuffix", true)
    private val background by boolean("Background", true)
    private val shadow by boolean("Shadow", true)
    private val rightLine by boolean("RightLine", true)
    private val hudScale by float("Scale", 1f, 0.5f..3f)
    private val combat by boolean("Combat", true)
    private val movement by boolean("Movement", true)
    private val playerCategory by boolean("Player", true)
    private val visual by boolean("Visual", true)
    private val misc by boolean("Misc", true)

    private class Spring(private var value: Double, private val rate: Double, private val instant: Boolean = false) {
        private var velocity = 0.0; private var target = value; private var last = -1L
        fun step(next: Double, now: Long): Double {
            if (last >= 0 && now > last) {
                val dt = (now - last) / 1000.0; val offset = value - target
                val linear = velocity + rate * offset; val decay = exp(-rate * dt)
                value = target + (offset + linear * dt) * decay
                velocity = (velocity - rate * linear * dt) * decay
            }
            if (next != target && instant && kotlin.math.abs(value - target) < .002 && kotlin.math.abs(velocity) < .02)
                velocity = rate * (next - value)
            last = now; target = next
            return value
        }
    }
    private class Motion {
        val horizontal = Spring(0.0, 25.0); val occupied = Spring(0.0, 12.7, true)
        var enabled = false; var changed = Long.MIN_VALUE / 2
        fun update(show: Boolean, now: Long): Pair<Float, Float> {
            if (enabled != show) { enabled = show; changed = now }
            val x = horizontal.step(if (show) 1.0 else 0.0, now).toFloat().coerceIn(0f, 1f)
            val y = occupied.step(if (show || now - changed < 65) 1.0 else 0.0, now).toFloat().coerceIn(0f, 1f)
            return x to y
        }
    }
    private data class Row(val label: String, val tag: String, val width: Float, val progress: Float, val y: Float)
    private val animations = mutableMapOf<ClientModule, Motion>()
    override fun onDisabled() { animations.clear() }
    private fun allowed(module: ClientModule): Boolean = when (module.category.tag.lowercase(Locale.ROOT)) {
        "combat" -> combat; "movement" -> movement; "player", "world" -> playerCategory
        "render", "visual" -> visual; else -> misc
    }
    @Suppress("unused")
    private val overlay = handler<OverlayRenderEvent> { event ->
        if (mc.gui.hud.isHidden) return@handler
        val now = System.nanoTime() / 1_000_000L
        val items = ModuleManager.map { module ->
            val name = OpaiRender.spaced(module.name).let { if (lowercase) it.lowercase(Locale.ROOT) else it }
            val tag = if (suffix && module.name !in setOf("HUD", "ClickGUI", "Theme")) module.tag.orEmpty().trim() else ""
            val shownTag = if (lowercase) tag.lowercase(Locale.ROOT) else tag
            val w = 3f + OpaiLbFont.width(name, 9.08f) + (if (shownTag.isEmpty()) 0f else OpaiLbFont.width(" $shownTag", 9.08f)) + 3f
            Triple(module, name to shownTag, w)
        }.sortedByDescending { it.third }
        var y = 0f
        val rows = ArrayList<Row>()
        for ((module, texts, w) in items) {
            val (progress, contribution) = animations.getOrPut(module) { Motion() }
                .update(module.enabled && !module.hidden && allowed(module), now)
            if (progress > .001f || contribution > .001f || (module.enabled && !module.hidden && allowed(module))) {
                rows.add(Row(texts.first, texts.second, w, progress, y + .25f))
                y += contribution * 12f
            }
        }
        val ctx = event.context
        ctx.pose().pushMatrix()
        try {
            ctx.pose().translate(ctx.guiWidth().toFloat(), 0f)
            ctx.pose().scale(hudScale, hudScale)
            // Original renders all shadows, then all fills, then text and accent lines.
            if (shadow) rows.forEachIndexed { index, row ->
                val next = rows.getOrNull(index + 1)?.width ?: 0f
                val radius = (row.width - next).coerceIn(0f, 5f)
                val right = -.5f + (row.width + 8f) * (1f - row.progress)
                val x = right - row.width
                for (spread in 6 downTo 1) {
                    val alpha = (3 + (6 - spread) * 2).coerceAtMost(18)
                    val ink = alpha shl 24
                    fun band(x0: Float, y0: Float, x1: Float, y1: Float) {
                        if (x1 <= x0 || y1 <= y0) return
                        OpaiRender.clip(ctx, x0, y0, x1, y1) {
                            OpaiRender.rect(ctx, x - spread, row.y + 1 - spread, row.width + spread * 2,
                                12f + spread * 2, radius + spread, ink)
                        }
                    }
                    band(x - spread, row.y - spread, x, row.y + 13 + spread)
                    band(right, row.y - spread, right + spread, row.y + 13 + spread)
                    band(x, row.y - spread, right, row.y)
                    band(x, row.y + 12, right, row.y + 13 + spread)
                }
            }
            if (background) rows.forEachIndexed { index, row ->
                val next = rows.getOrNull(index + 1)?.width ?: 0f
                val radius = (row.width - next).coerceIn(0f, 5f)
                val right = -.5f + (row.width + 8f) * (1f - row.progress)
                val x = right - row.width
                // Three non-overlapping pieces approximate Samsara's bottom-left-only radius.
                if (radius <= .01f) OpaiRender.rect(ctx, x, row.y, row.width, 12f, 0f, 0xCC101010.toInt())
                else {
                    OpaiRender.rect(ctx, x, row.y, row.width, 12f - radius, 0f, 0xCC101010.toInt())
                    OpaiRender.rect(ctx, x + radius, row.y + 12f - radius, row.width - radius, radius, 0f, 0xCC101010.toInt())
                    OpaiRender.clip(ctx, x, row.y + 12f - radius, x + radius, row.y + 12f) {
                        OpaiRender.rect(ctx, x, row.y + 12f - 2 * radius, 2 * radius, 2 * radius,
                            radius, 0xCC101010.toInt())
                    }
                }
            }
            rows.forEach { row ->
                val right = -.5f + (row.width + 8f) * (1f - row.progress)
                val x = right - row.width
                OpaiLbFont.text(ctx, row.label, x + 3f, row.y + 1.1f, 9.08f, -1)
                if (row.tag.isNotEmpty()) OpaiLbFont.text(ctx, " ${row.tag}", x + 3f + OpaiLbFont.width(row.label, 9.08f), row.y + 1.1f, 9.08f, color.palette.accent())
            }
            if (rightLine) rows.forEach { row ->
                val right = -.5f + (row.width + 8f) * (1f - row.progress)
                OpaiRender.rect(ctx, right - 1f, row.y, 1f, 12f, 0f, color.palette.accent())
            }
        } finally { ctx.pose().popMatrix() }
    }
}
