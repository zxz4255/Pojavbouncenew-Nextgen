package net.ccbluex.liquidbounce.features.module.modules.render.samsara

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.interfaces.EntityRenderStateAddition
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import kotlin.math.roundToInt

/** Native approximation of Samsara's player/item capsule nametags. */
object ModuleSamsaraNametags : ClientModule("SamsaraNametags", ModuleCategories.RENDER) {
    private val players by boolean("Players", true)
    private val items by boolean("Items", true)
    private val armor by boolean("PlayerArmor", true)
    private val scale by float("Scale", .6f, .1f..2f)
    private val shadow by boolean("Shadow", true)
    private val slots = arrayOf(EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
        EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND)

    private fun accepts(entity: Entity): Boolean = !entity.isRemoved && !mc.gui.hud.isHidden && when (entity) {
        is Player -> players && entity !== mc.player
        is ItemEntity -> items && !entity.item.isEmpty
        else -> false
    }
    @JvmStatic
    fun replaces(state: EntityRenderState): Boolean {
        if (!running) return false
        val entity = (state as? EntityRenderStateAddition)?.`liquid_bounce$getEntity`() ?: return false
        if (!accepts(entity) || mc.level == null || mc.player == null) return false
        val rise = if (entity is ItemEntity) entity.bbHeight + .35 else entity.eyeHeight + .55
        val projected = WorldToScreen.calculateScreenPos(Vec3(state.x, state.y + rise, state.z)) ?: return false
        return projected.x.isFinite() && projected.y.isFinite() && projected.z in -1f..1f &&
            projected.x >= 0f && projected.x <= mc.window.guiScaledWidth &&
            projected.y >= 0f && projected.y <= mc.window.guiScaledHeight
    }
    private data class Label(val entity: Entity, val x: Float, val y: Float, val distance: Double)
    @Suppress("unused")
    private val overlay = handler<OverlayRenderEvent> { event ->
        val level = mc.level ?: return@handler
        val player = mc.player ?: return@handler
        if (mc.gui.hud.isHidden) return@handler
        val camera = mc.gameRenderer.mainCamera().position()
        val labels = level.entitiesForRendering().mapNotNull { entity ->
            if (!accepts(entity)) return@mapNotNull null
            val pos = entity.getPosition(event.tickDelta)
            val rise = if (entity is ItemEntity) entity.bbHeight + .35 else entity.eyeHeight + .55
            val projected = WorldToScreen.calculateScreenPos(pos.add(0.0, rise, 0.0)) ?: return@mapNotNull null
            if (!projected.x.isFinite() || !projected.y.isFinite() || projected.z !in -1f..1f) return@mapNotNull null
            Label(entity, projected.x, projected.y, camera.distanceToSqr(pos))
        }.sortedByDescending { it.distance }
        labels.forEach { draw(event.context, it, player) }
    }
    private fun draw(ctx: GuiGraphicsExtractor, label: Label, local: Player) {
        val entity = label.entity
        val parts = when (entity) {
            is Player -> listOf(
                ("♥ " + (entity.health + entity.absorptionAmount).roundToInt()) to
                    (if (entity.health > 12) 0xFF88D922.toInt() else if (entity.health > 6) 0xFFFFFF55.toInt() else 0xFFFF3333.toInt()),
                entity.displayName.string to -1,
                (local.distanceTo(entity).roundToInt().toString() + "m") to 0xFF888888.toInt()
            )
            is ItemEntity -> listOf(entity.item.hoverName.string to -1, ("x" + entity.item.count) to 0xFFFFCC00.toInt())
            else -> return
        }
        val widths = parts.map { OpaiLbFont.width(it.first, 16f) + 14f }
        val total = widths.sum() + (parts.size - 1) * 2f
        ctx.pose().pushMatrix()
        try {
            ctx.pose().translate(label.x, label.y)
            ctx.pose().scale(scale, scale)
            var x = -total / 2f
            parts.forEachIndexed { i, (text, color) ->
                val w = widths[i]
                if (shadow) OpaiRender.rect(ctx, x - 3, -18f, w + 6, 36f, 14f, 0x30000000)
                OpaiRender.rect(ctx, x, -15f, w, 30f, 12f, 0x98000000.toInt())
                OpaiLbFont.text(ctx, text, x + 7f, -8f, 16f, color)
                x += w + 2f
            }
            if (armor && entity is Player) {
                val equipment = slots.map { entity.getItemBySlot(it) }.filter { !it.isEmpty }
                val start = -(equipment.size * 32f - 4f) / 2f
                equipment.forEachIndexed { index, stack ->
                    ctx.pose().pushMatrix()
                    try {
                        ctx.pose().translate(start + index * 32f, -51f)
                        ctx.pose().scale(1.75f, 1.75f)
                        ctx.item(entity, stack, 0, 0, index)
                    } finally { ctx.pose().popMatrix() }
                }
            }
        } finally { ctx.pose().popMatrix() }
    }
}
