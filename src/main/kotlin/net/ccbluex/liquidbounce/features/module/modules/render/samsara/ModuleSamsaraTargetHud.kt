package net.ccbluex.liquidbounce.features.module.modules.render.samsara

import com.samsara.ui.hud.target.OpaiTargetPort.OpaiTargetHudHealth
import com.samsara.ui.hud.target.OpaiTargetPort.OpaiTargetHudPainter
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.READ_FINAL_STATE
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import kotlin.math.roundToInt
import kotlin.math.sqrt
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player

/** Opai target HUD sourced from Samsara's health animation and painter. */
object ModuleSamsaraTargetHud : ClientModule("SamsaraTargetHUD", ModuleCategories.RENDER) {
    private val color by enumChoice("Color", OpaiColor.Lavender)
    private val showArmor by boolean("ShowArmor", true)
    private val scale by float("Scale", 1f, .5f..3f)
    private val xOffset by int("XOffset", 0, -1000..1000, "px")
    private val yOffset by int("YOffset", 0, -1000..1000, "px")
    private val holdSeconds by int("HoldSeconds", 3, 1..15, "s")
    private val health = OpaiTargetHudHealth()
    private var oldTarget: LivingEntity? = null
    private var attackedTarget: LivingEntity? = null
    private var attackedAt = 0L
    override fun onDisabled() {
        oldTarget = null
        attackedTarget = null
        attackedAt = 0L
    }

    // Covers normal mouse/touch attacks and LB's combat attack event; cancelled attacks are ignored.
    @Suppress("unused")
    private val attack = handler<AttackEntityEvent>(priority = READ_FINAL_STATE) { event ->
        val entity = event.entity
        if (!event.isCancelled && entity is LivingEntity && entity !== mc.player &&
            !entity.isRemoved && entity.level() == mc.level) {
            attackedTarget = entity
            attackedAt = System.nanoTime()
        }
    }

    @Suppress("unused")
    private val overlay = handler<OverlayRenderEvent> { event ->
        if (mc.gui.hud.isHidden || mc.gui.overlay() != null) { oldTarget = null; return@handler }
        val recent = attackedTarget?.takeIf {
            System.nanoTime() - attackedAt <= holdSeconds * 1_000_000_000L &&
                !it.isRemoved && it.level() == mc.level
        }
        val aura = if (ModuleKillAura.running) ModuleKillAura.targetTracker.target as? LivingEntity else null
        val target = recent ?: aura?.takeIf { !it.isRemoved && it.level() == mc.level }
        if (target == null) { oldTarget = null; return@handler }
        val now = System.nanoTime() / 1_000_000L
        if (oldTarget !== target) { health.reset(target.health, target.maxHealth, now); oldTarget = target }
        val sample = health.update(target.health, target.maxHealth, now)
        val ctx = event.context
        if (!OpaiRender.visibleScissor(ctx)) return@handler
        val surface = Surface(ctx, target)
        val screenWidth = (ctx.guiWidth() / scale).toInt()
        val screenHeight = (ctx.guiHeight() / scale).toInt()
        val bounds = OpaiTargetHudPainter.bounds(surface, target.name.string, sample.maximum(), screenWidth, screenHeight,
            (xOffset / scale).toInt(), (yOffset / scale).toInt())
        ctx.pose().pushMatrix()
        try {
            ctx.pose().scale(scale, scale)
            OpaiTargetHudPainter.paint(surface, bounds, target.name.string, sample, showArmor, color.palette)
        } finally { ctx.pose().popMatrix() }
    }

    private class Surface(private val ctx: GuiGraphicsExtractor, private val target: LivingEntity) : OpaiTargetHudPainter.Surface {
        override fun measure(text: String) = OpaiLbFont.width(text, 8f)
        override fun panel(bounds: OpaiTargetHudPainter.Bounds) {
            // Original Samsara 4x panel, horizontally nine-sliced to preserve the corners and shadow.
            val sourceWidth = 552
            val sourceHeight = 224
            val edge = 56
            val destWidth = (bounds.width() + 16) * 4
            val source = intArrayOf(0, edge, sourceWidth - edge, sourceWidth)
            val dest = intArrayOf(0, edge, destWidth - edge, destWidth)
            val texture = OpaiTextures.resolve("hud/target-opai.png")
            if (texture == null) {
                // Keep HUD usable if GPU texture creation fails.
                OpaiRender.rect(ctx, bounds.x() - 3f, bounds.y() - 2f, bounds.width() + 6f, bounds.height() + 6f, 9f, 0x32000000)
                OpaiRender.rect(ctx, bounds.x().toFloat(), bounds.y().toFloat(), bounds.width().toFloat(), bounds.height().toFloat(), 6f, 0xCC101010.toInt())
                return
            }
            ctx.nextStratum()
            ctx.pose().pushMatrix()
            try {
                ctx.pose().translate(bounds.x() - 8f, bounds.y() - 8f)
                ctx.pose().scale(.25f, .25f)
                for (i in 0..2) ctx.blit(RenderPipelines.GUI_TEXTURED, texture, dest[i], 0,
                    source[i].toFloat(), 0f, dest[i + 1] - dest[i], sourceHeight,
                    source[i + 1] - source[i], sourceHeight, sourceWidth, sourceHeight, -1)
            } finally { ctx.pose().popMatrix() }
            ctx.nextStratum()
        }
        override fun face(x: Float, y: Float, size: Float, radius: Float) {
            if (target !is Player) {
                // Living non-player targets have no player skin; use a neutral avatar instead.
                OpaiRender.rect(ctx, x, y, size, size, radius, 0xFF30363E.toInt())
                val label = "MOB"
                OpaiLbFont.text(ctx, label, x + (size - OpaiLbFont.width(label, 7f)) / 2f,
                    y + (size - 7f) / 2f, 7f, 0xFFE5E9EF.toInt())
                return
            }
            val skin = if (target is AbstractClientPlayer) target.skin else DefaultPlayerSkin.get(target.gameProfile)
            val texture = skin.body().texturePath()
            ctx.pose().pushMatrix()
            try {
                ctx.pose().translate(x, y)
                ctx.pose().scale(.25f, .25f)
                val pixels = (size * 4).roundToInt()
                val round = (radius * 4).roundToInt().coerceIn(0, pixels / 2)
                fun strip(u: Int, left: Int, top: Int, w: Int, h: Int) {
                    if (w <= 0 || h <= 0) return
                    val factor = 8f / pixels
                    ctx.blit(texture, left, top, left + w, top + h,
                        (u + left * factor) / 64f, (u + (left + w) * factor) / 64f,
                        (8 + top * factor) / 64f, (8 + (top + h) * factor) / 64f)
                }
                for (u in intArrayOf(8, 40)) {
                    for (row in 0 until round) {
                        val dy = round - row - .5
                        val inset = (round - sqrt(round * round - dy * dy)).roundToInt()
                        strip(u, inset, row, pixels - inset * 2, 1)
                        strip(u, inset, pixels - row - 1, pixels - inset * 2, 1)
                    }
                    strip(u, 0, round, pixels, pixels - round * 2)
                }
            } finally { ctx.pose().popMatrix() }
        }
        override fun armor(slot: Int, x: Float, y: Float) {
            val slots = arrayOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
            val stack = target.getItemBySlot(slots[slot])
            if (stack.isEmpty) return
            ctx.pose().pushMatrix()
            try {
                ctx.pose().translate(x, y)
                ctx.pose().scale(.95f, .95f)
                ctx.item(target, stack, 0, 0, slot)
            } finally { ctx.pose().popMatrix() }
        }
        override fun text(text: String, x: Float, y: Float, color: Int) = OpaiLbFont.text(ctx, text, x, y, 8f, color)
        override fun bar(x: Float, y: Float, width: Float, height: Float, color: Int) {
            if (width <= 0) return
            val texture = OpaiTextures.resolve("hud/target-opai-bar.png")
            if (texture == null) {
                OpaiRender.rect(ctx, x, y, width, height, height / 2, color)
                return
            }
            ctx.pose().pushMatrix()
            try {
                ctx.pose().translate(x, y)
                ctx.pose().scale(.25f, .25f)
                val w = (width * 4).roundToInt().coerceAtLeast(1)
                val h = (height * 4).roundToInt().coerceAtLeast(1)
                if (w <= h) {
                    ctx.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0f, 0f, w, h, 20, 20, 20, 20, color)
                } else {
                    val cap = h / 2
                    ctx.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0f, 0f, cap, h, 10, 20, 20, 20, color)
                    ctx.blit(RenderPipelines.GUI_TEXTURED, texture, cap, 0, 9f, 0f, w - cap * 2, h, 2, 20, 20, 20, color)
                    ctx.blit(RenderPipelines.GUI_TEXTURED, texture, w - cap, 0, 10f, 0f, cap, h, 10, 20, 20, 20, color)
                }
            } finally { ctx.pose().popMatrix() }
        }
    }
}
