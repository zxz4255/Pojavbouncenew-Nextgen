package net.ccbluex.liquidbounce.features.module.modules.render.samsara

import com.samsara.ui.dynamicIsland.DynamicIslandPainter
import com.samsara.ui.dynamicIsland.DynamicIslandState
import com.samsara.ui.dynamicIsland.DynamicIslandStatus
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines

/** Samsara's original island spring/state/painter, with a Minecraft GUI drawing adapter. */
object ModuleSamsaraIsland : ClientModule("SamsaraIsland", ModuleCategories.RENDER) {
    private val color by enumChoice("Color", OpaiColor.Lavender)
    private val reducedMotion by boolean("ReducedMotion", false)
    private val moduleNotices by boolean("ModuleNotices", true)
    private val notifications by boolean("Notifications", true)
    private val shadow by boolean("Shadow", true)
    private val hudScale by float("Scale", 1f, 0.5f..2f)
    private val state = DynamicIslandState()
    private fun now() = System.nanoTime() / 1_000_000L
    override fun onDisabled() { synchronized(state) { state.clear() } }

    @Suppress("unused")
    private val toggles = handler<ModuleToggleEvent> { e ->
        if (moduleNotices && e.moduleName != name) synchronized(state) {
            state.post("module:${e.moduleName}", "Module Toggled", OpaiRender.spaced(e.moduleName) + " has been ",
                if (e.enabled) "Enabled" else "Disabled", DynamicIslandState.Icon.TOGGLE, e.enabled, now(), DynamicIslandState.TOGGLE_MS)
        }
    }
    @Suppress("unused")
    private val notices = handler<NotificationEvent> { e ->
        if (notifications && e.severity != NotificationEvent.Severity.ENABLED && e.severity != NotificationEvent.Severity.DISABLED) {
            val icon = when (e.severity) {
                NotificationEvent.Severity.SUCCESS -> DynamicIslandState.Icon.SUCCESS
                NotificationEvent.Severity.ERROR -> DynamicIslandState.Icon.WARNING
                else -> DynamicIslandState.Icon.INFO
            }
            synchronized(state) { state.post("notice:${e.title}:${e.message}", e.title, e.message, "", icon, false, now(), 2600) }
        }
    }
    @Suppress("unused")
    private val overlay = handler<OverlayRenderEvent> { event ->
        val player = mc.player ?: return@handler
        if (mc.gui.hud.isHidden || mc.gui.overlay() != null) return@handler
        val ctx = event.context
        val width = ctx.guiWidth() / hudScale
        val server = mc.currentServer?.ip
        val ping = mc.connection?.getPlayerInfo(player.uuid)?.latency ?: -1
        val status = DynamicIslandStatus(player.name.string, server, ping, mc.fps)
        val surface = Surface(ctx, shadow)
        val frame = synchronized(state) { state.frame(now(), width, status, surface, reducedMotion) }
        ctx.pose().pushMatrix()
        try {
            ctx.pose().scale(hudScale, hudScale)
            DynamicIslandPainter.paint(surface, frame, width, color.palette)
        } finally { ctx.pose().popMatrix() }
    }

    private class Surface(private val ctx: GuiGraphicsExtractor, private val shadows: Boolean) : DynamicIslandPainter.Surface {
        override fun measure(text: String, size: Float) = OpaiLbFont.width(text, size)
        override fun rounded(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Int) =
            OpaiRender.rect(ctx, x, y, width, height, radius, color)
        override fun line(x1: Float, y1: Float, x2: Float, y2: Float, stroke: Float, color: Int) =
            OpaiRender.line(ctx, x1, y1, x2, y2, stroke, color)
        override fun text(text: String, x: Float, centerY: Float, size: Float, color: Int) =
            OpaiLbFont.text(ctx, text, x, centerY - size / 2, size, color)
        override fun shadow(x: Float, y: Float, width: Float, height: Float, radius: Float) {
            if (!shadows) return
            // Samsara uses 0.25px rings and its own shadow-coverage curve. The rectangular
            // scissor is a native approximation of its rounded NanoVG hole.
            var spread = DynamicIslandPainter.SHADOW_EXTENT
            while (spread > 0) {
                val layer = DynamicIslandPainter.shadowLayer(spread)
                if (layer ushr 24 != 0) {
                    fun band(x0: Float, y0: Float, x1: Float, y1: Float) {
                        if (x1 <= x0 || y1 <= y0) return
                        OpaiRender.clip(ctx, x0, y0, x1, y1) {
                            OpaiRender.rect(ctx, x - spread, y - spread, width + 2 * spread,
                                height + 2 * spread, radius + spread, layer)
                        }
                    }
                    band(x - spread, y - spread, x, y + height + spread)
                    band(x + width, y - spread, x + width + spread, y + height + spread)
                    band(x, y - spread, x + width, y)
                    band(x, y + height, x + width, y + height + spread)
                }
                spread -= DynamicIslandPainter.SHADOW_STEP
            }
        }
        override fun clip(x: Float, y: Float, width: Float, height: Float, content: Runnable) {
            OpaiRender.clip(ctx, x, y, x + width, y + height) { content.run() }
        }
        override fun symbol(symbol: DynamicIslandStatus.Symbol, x: Float, y: Float, size: Float, color: Int) {
            // Pre-rasterized from Samsara's original vector paths, tinted with the live palette.
            val file = when (symbol) {
                DynamicIslandStatus.Symbol.USER -> "user"
                DynamicIslandStatus.Symbol.LINK -> "link"
                DynamicIslandStatus.Symbol.REFRESH -> "refresh"
                DynamicIslandStatus.Symbol.BED -> "bed"
                DynamicIslandStatus.Symbol.CHROME -> "chrome"
            }
            val texture = OpaiTextures.resolve("gui/island/$file.png")
            if (texture == null) {
                OpaiRender.rect(ctx, x + size * .2f, y + size * .2f, size * .6f, size * .6f, size * .3f, color)
                return
            }
            if (!OpaiRender.visibleScissor(ctx)) return
            ctx.pose().pushMatrix()
            try {
                ctx.pose().translate(x, y)
                ctx.pose().scale(size / 96f, size / 96f)
                ctx.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0f, 0f, 96, 96, 96, 96, 96, 96, color)
            } finally { ctx.pose().popMatrix() }
        }
    }
}
