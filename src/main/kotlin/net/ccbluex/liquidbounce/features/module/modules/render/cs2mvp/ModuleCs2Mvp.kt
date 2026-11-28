/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.render.cs2mvp

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.vfp.VfpCompatibility1_8
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.LivingEntity

/** Standalone, noninteractive MVP strip. Does not change the camera, attack logic, cursor or existing HUD. */
object ModuleCs2Mvp : ClientModule("CS2MVP", ModuleCategories.RENDER) {
    enum class Side(override val tag: String) : Tagged { CT("CT"), T("T") }
    enum class Attribution(override val tag: String) : Tagged {
        AUTO("Auto"), DAMAGE_SOURCE("DamageSource"), RECENT_HIT("RecentHit")
    }
    private val side by enumChoice("Side", Side.CT)
    private val attribution by enumChoice("Attribution", Attribution.AUTO)
    private val duration by float("Duration", 4f, 1f..10f)
    private val scale by float("Scale", 1f, .5f..2f)
    private val verticalPosition by float("VerticalPosition", 190f, 0f..900f)
    private val evidenceWindow by int("EvidenceWindow", 2000, 250..5000)
    private val nameFont by text("NameFont", "Stratum2 Regular Condensed")
    private val reasonFont by text("ReasonFont", "Stratum2 Medium Condensed")
    private val preview by boolean("Preview", false)
    private val tracker = Cs2MvpKillTracker()
    private val timeline = Cs2MvpTimeline()
    private val watched = linkedMapOf<MvpLife, LivingEntity>()
    @Volatile
    private var activeEpoch = 0L
    private var epochLevel: Any? = null
    private var epochPlayer: Any? = null
    private var previousPreview = false
    private var pendingPreview = false
    private fun nowMs() = System.nanoTime() / 1_000_000L
    private fun life(entity: LivingEntity) = MvpLife(entity.uuid, entity.id)

    private fun clear() {
        activeEpoch++
        tracker.reset(); timeline.reset(); watched.clear(); previousPreview = false; pendingPreview = false
        epochLevel = mc.level; epochPlayer = mc.player
    }
    private fun ensureEpoch() {
        if (epochLevel !== mc.level || epochPlayer !== mc.player) clear()
    }
    override fun onEnabled() { super.onEnabled(); clear() }
    override fun onDisabled() { clear(); super.onDisabled() }

    private fun eligible(entity: LivingEntity): Boolean {
        val local = mc.player ?: return false
        // 097: every living target counts, not just enemy players; never award our own death.
        return entity !== local && !entity.isSpectator
    }
    private fun watch(entity: LivingEntity) {
        watched[life(entity)] = entity
        while (watched.size > 128) watched.remove(watched.keys.first())
    }
    private fun allowRecentHit(): Boolean = when (attribution) {
        Attribution.DAMAGE_SOURCE -> false
        Attribution.RECENT_HIT -> true
        Attribution.AUTO -> VfpCompatibility1_8.INSTANCE.is1_8ConnectionActive
    }
    private fun recordDeath(entity: LivingEntity) {
        val key = life(entity)
        val accepted = tracker.death(key, nowMs(), evidenceWindow.toLong(), allowRecentHit())
        watched.remove(key)
        if (accepted && eligible(entity)) {
            mc.player?.let { timeline.show(it.gameProfile.name, nowMs()) }
        }
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> { clear() }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent>(priority = EventPriorityConvention.READ_FINAL_STATE) { event ->
        if (event.isCancelled) return@handler
        ensureEpoch()
        val target = event.entity as? LivingEntity ?: return@handler
        if (!target.isAlive || !eligible(target)) return@handler
        tracker.attack(life(target), nowMs()); watch(target)
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent>(priority = EventPriorityConvention.READ_FINAL_STATE) { event ->
        if (event.isCancelled || event.origin != TransferOrigin.INCOMING) return@handler
        val packet = event.packet
        if (packet !is ClientboundDamageEventPacket && packet !is ClientboundEntityEventPacket) return@handler
        val capturedLevel = mc.level ?: return@handler
        val capturedPlayer = mc.player ?: return@handler
        val capturedEpoch = activeEpoch
        // Packet hooks may run on Netty; only touch tracking and GUI state on the client thread.
        mc.execute {
            if (!running || activeEpoch != capturedEpoch || mc.level !== capturedLevel || mc.player !== capturedPlayer) return@execute
            ensureEpoch()
            when (packet) {
                is ClientboundDamageEventPacket -> {
                    val target = capturedLevel.getEntity(packet.entityId) as? LivingEntity ?: return@execute
                    if (!eligible(target)) return@execute
                    val byLocal = packet.sourceCauseId == capturedPlayer.id
                    // Old-protocol translations may have no source ID. Unknown is not proof of
                    // a competing killer; only the explicitly enabled legacy fallback may ignore it.
                    if (byLocal || packet.sourceCauseId >= 0 || !allowRecentHit()) {
                        tracker.damage(life(target), byLocal, nowMs())
                    }
                    if (tracker.contains(life(target))) watch(target)
                }
                is ClientboundEntityEventPacket -> {
                    if (packet.eventId != EntityEvent.DEATH) return@execute
                    val target = packet.getEntity(capturedLevel) as? LivingEntity ?: return@execute
                    recordDeath(target)
                }
            }
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        ensureEpoch()
        val local = mc.player
        if (local == null || mc.level == null || !local.isAlive) { clear(); return@handler }
        val now = nowMs()
        tracker.prune(now)
        // Health zero is a death signal. Entity removal, range loss and a plain attack are NOT.
        for ((key, target) in watched.toMap()) {
            if (!tracker.contains(key)) watched.remove(key)
            else if (target.health <= 0f) recordDeath(target)
        }
        if (preview && !previousPreview) pendingPreview = true
        if (!preview) pendingPreview = false
        if (pendingPreview && mc.gui.screen() == null && mc.gui.overlay() == null && !mc.gui.hud.isHidden) {
            timeline.show(local.gameProfile.name, now)
            pendingPreview = false
        }
        previousPreview = preview
    }

    @Suppress("unused")
    private val overlayHandler = handler<OverlayRenderEvent> { event ->
        ensureEpoch()
        val local = mc.player ?: return@handler
        if (mc.level == null || !local.isAlive || mc.gui.hud.isHidden || mc.gui.overlay() != null || mc.gui.screen() != null) {
            return@handler
        }
        val frame = timeline.sample(nowMs(), (duration * 1000f).toLong()) ?: return@handler
        Cs2MvpRenderer.draw(event.context, local.skin, frame,
            mc.window.guiScaledWidth, mc.window.guiScaledHeight, scale, verticalPosition,
            side == Side.CT, nameFont, reasonFont)
    }
}
