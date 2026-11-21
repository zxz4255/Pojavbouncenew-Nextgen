/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.additions.realSelectedSlot
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerNetworkMovementTickEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.isEqual1_8
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.client.usesViaFabricPlus
import net.ccbluex.liquidbounce.utils.client.vfp.VfpCompatibility1_8
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.network.protocol.game.ServerboundPunchPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import java.util.ArrayDeque

/**
 * MoonLight AutoGap-style packet buffering for a ViaFabricPlus 1.8 target.
 *
 * Movement packets are held in short, tick-delimited batches. After 32 network-movement ticks the
 * module emits the actual 1.8 C09 held-slot packet and C08 use-item packet through ViaFabricPlus's
 * raw 1.8 connection. The local inventory selection is never changed to the apple slot.
 */
@Suppress("detekt:TooManyFunctions")
object ModuleGapple : ClientModule("Gapple", ModuleCategories.PLAYER, aliases = listOf("BlinkGapple")) {

    private val delay by int("Delay", 1000, 0..10000, "ms")
    private val health by float("Health", 15f, 0f..20f, "HP")
    private val stopMove by boolean("Stop Move When Eating", false)
    private val alwaysAttack by boolean("Always Attack", false)
    private val autoClose by boolean("Close When No Golden Apple", true)
    private val lagWhenInAir by boolean("Lag When In Air", false)
    private val progressBar by boolean("ProgressBar", true)

    private sealed interface QueueEntry {
        class PacketEntry(val packet: Packet<*>) : QueueEntry
        object MovementBoundary : QueueEntry
    }

    private data class PendingUse(
        val appleSlot: Int,
        val restoreSlot: Int,
        val clientSlot: Int,
        val stackCountBefore: Int,
        val healthBefore: Float,
        val sentAt: Long,
    )

    private val packetQueue = ArrayDeque<QueueEntry>()

    private var eating = false
    private var appleSlot = -1
    private var movingPackets = 0
    private var pendingUse: PendingUse? = null
    private var lastUseAt = Long.MIN_VALUE
    private var needSkip = false
    private var noAppleWarned = false
    private var protocolWarningShown = false

    override fun onEnabled() {
        resetState()
    }

    override fun onDisabled() {
        val player = mc.player
        stopEating(release = player != null && mc.level != null && player.isAlive)
        pendingUse?.let { sendHeldItemChange(it.restoreSlot) }
        pendingUse = null
        needSkip = false
    }

    private fun resetState() {
        packetQueue.clear()
        eating = false
        appleSlot = -1
        movingPackets = 0
        pendingUse = null
        lastUseAt = Long.MIN_VALUE
        needSkip = false
        noAppleWarned = false
        protocolWarningShown = false
    }

    /**
     * PRE is the equivalent of MoonLight's pre-motion phase. POST counts one C03-equivalent
     * movement tick and appends a boundary marker, so a drain never crosses a tick boundary.
     */
    @Suppress("unused")
    private val networkMovementHandler = handler<PlayerNetworkMovementTickEvent> { event ->
        if (event.state == EventState.POST) {
            if (eating) {
                movingPackets = (movingPackets + 1).coerceAtMost(TARGET_MOVEMENT_TICKS)
                packetQueue.addLast(QueueEntry.MovementBoundary)
            }
            return@handler
        }

        handlePreMovement()
    }

    private fun handlePreMovement() {
        val player = mc.player
        if (player == null || mc.level == null) {
            stopEating(release = false)
            pendingUse = null
            return
        }

        if (!usesViaFabricPlus || !isEqual1_8) {
            if (!protocolWarningShown) {
                notification(
                    "Gapple",
                    "此模块需要 ViaFabricPlus，并将目标协议设为 1.8",
                    NotificationEvent.Severity.ERROR,
                )
                protocolWarningShown = true
            }
            stopEating(release = true)
            pendingUse?.let { sendHeldItemChange(it.restoreSlot) }
            pendingUse = null
            enabled = false
            return
        }
        protocolWarningShown = false

        if (!player.isAlive || player.isDeadOrDying) {
            stopEating(release = false)
            pendingUse = null
            return
        }

        val gameMode = mc.gameMode?.localPlayerMode
        if (gameMode != GameType.SURVIVAL && gameMode != GameType.ADVENTURE) {
            stopEating(release = true)
            pendingUse?.let { sendHeldItemChange(it.restoreSlot) }
            pendingUse = null
            return
        }

        updatePendingUse(player)

        if (player.health >= health) {
            stopEating(release = true)
            pendingUse = null
            noAppleWarned = false
            return
        }

        // Do not start another use attempt while the server is still confirming the previous one.
        if (pendingUse != null) {
            return
        }

        val now = System.currentTimeMillis()
        if (!eating) {
            if (!delayElapsed(now)) {
                return
            }

            appleSlot = findGoldenAppleSlot()
            if (appleSlot == -1) {
                handleNoApple()
                return
            }

            noAppleWarned = false
            startEating()
        } else {
            appleSlot = findGoldenAppleSlot()
            if (appleSlot == -1) {
                stopEating(release = true)
                handleNoApple()
                return
            }
        }

        if (movingPackets >= TARGET_MOVEMENT_TICKS) {
            useGoldenApple(player, now)
            return
        }

        // Same cadence as MoonLight AutoGap: release queued traffic in small movement-tick batches.
        if (player.tickCount % DRAIN_EVERY_TICKS == 0) {
            flushThroughMovementBoundary(player)
        }
    }

    private fun delayElapsed(now: Long): Boolean =
        lastUseAt == Long.MIN_VALUE || now - lastUseAt >= delay

    private fun startEating() {
        packetQueue.clear()
        movingPackets = 0
        eating = true
        needSkip = false
    }

    private fun updatePendingUse(player: net.minecraft.client.player.LocalPlayer) {
        val pending = pendingUse ?: return

        // Respect manual hotbar input (and other modules' intentional silent slot changes).
        if (player.inventory.realSelectedSlot != pending.clientSlot ||
            SilentHotbar.serversideSlot != pending.restoreSlot
        ) {
            pendingUse = null
            return
        }

        val currentCount = player.inventory.getItem(pending.appleSlot).count
        val consumed = currentCount < pending.stackCountBefore || player.health > pending.healthBefore + HEALTH_EPSILON
        if (consumed) {
            pendingUse = null
            movingPackets = 0
            noAppleWarned = false
            return
        }

        if (System.currentTimeMillis() - pending.sentAt >= USE_CONFIRM_TIMEOUT_MS) {
            pendingUse = null
            movingPackets = 0
            notification(
                "Gapple",
                "服务器尚未确认金苹果已吃下，将在延迟后重试",
                NotificationEvent.Severity.INFO,
            )
        }
    }

    private fun findGoldenAppleSlot(): Int {
        val inventory = mc.player?.inventory ?: return -1
        return (0..8).firstOrNull { slot ->
            val stack = inventory.getItem(slot)
            !stack.isEmpty && (stack.`is`(Items.GOLDEN_APPLE) || stack.`is`(Items.ENCHANTED_GOLDEN_APPLE))
        } ?: -1
    }

    private fun handleNoApple() {
        if (!noAppleWarned) {
            notification(
                "Gapple",
                "快捷栏没有金苹果",
                NotificationEvent.Severity.INFO,
            )
            noAppleWarned = true
        }

        if (autoClose) {
            enabled = false
        }
    }

    private fun useGoldenApple(player: net.minecraft.client.player.LocalPlayer, now: Long) {
        val slot = appleSlot
        if (slot !in 0..8) {
            stopEating(release = true)
            return
        }

        val stack = player.inventory.getItem(slot)
        if (stack.isEmpty || (!stack.`is`(Items.GOLDEN_APPLE) && !stack.`is`(Items.ENCHANTED_GOLDEN_APPLE))) {
            stopEating(release = true)
            return
        }

        val restoreSlot = SilentHotbar.serversideSlot.takeIf { it in 0..8 }
            ?: player.inventory.realSelectedSlot.coerceIn(0, 8)
        val clientSlot = player.inventory.realSelectedSlot.coerceIn(0, 8)
        val switchedServerSlot = slot != restoreSlot
        var appleUseSent = false

        try {
            if (switchedServerSlot) {
                VfpCompatibility1_8.INSTANCE.sendHeldItemChange(slot)
            }

            VfpCompatibility1_8.INSTANCE.sendGoldenAppleUse(
                stack.count,
                stack.`is`(Items.ENCHANTED_GOLDEN_APPLE),
            )
            appleUseSent = true
        } catch (exception: Throwable) {
            logger.error("Failed to send the 1.8 golden-apple C08 packet", exception)
        }

        if (!appleUseSent) {
            if (switchedServerSlot) {
                sendHeldItemChange(restoreSlot)
            }
            stopEating(release = true)
            notification(
                "Gapple",
                "发送 1.8 金苹果使用包失败；已恢复服务器快捷栏",
                NotificationEvent.Severity.ERROR,
            )
            return
        }

        // Keep MoonLight's ordering: use C08 first, release buffered movement, then restore C09.
        flushAllQueued()
        if (switchedServerSlot) {
            sendHeldItemChange(restoreSlot)
        }

        pendingUse = PendingUse(
            appleSlot = slot,
            restoreSlot = restoreSlot,
            clientSlot = clientSlot,
            stackCountBefore = stack.count,
            healthBefore = player.health,
            sentAt = now,
        )
        lastUseAt = now
        eating = false
        appleSlot = -1
        movingPackets = 0
        needSkip = false
    }

    private fun sendHeldItemChange(slot: Int) {
        if (slot !in 0..8) return

        if (usesViaFabricPlus && isEqual1_8) {
            runCatching {
                VfpCompatibility1_8.INSTANCE.sendHeldItemChange(slot)
            }.onFailure { exception ->
                logger.error("Failed to send the 1.8 C09 held-item-change packet", exception)
                sendPacketSilently(ServerboundSetCarriedItemPacket(slot))
            }
        } else {
            // If the target protocol changed while Gapple was active, restore through the current
            // protocol translation instead of attempting to write a raw 1.8 packet.
            sendPacketSilently(ServerboundSetCarriedItemPacket(slot))
        }
    }

    private fun flushThroughMovementBoundary(player: net.minecraft.client.player.LocalPlayer) {
        if (lagWhenInAir && !player.onGround() && !needSkip) {
            return
        }

        while (packetQueue.isNotEmpty()) {
            when (val entry = packetQueue.removeFirst()) {
                QueueEntry.MovementBoundary -> break
                is QueueEntry.PacketEntry -> sendPacketSilently(entry.packet)
            }
        }

        if (needSkip) {
            needSkip = false
        }
    }

    private fun flushAllQueued() {
        while (packetQueue.isNotEmpty()) {
            when (val entry = packetQueue.removeFirst()) {
                QueueEntry.MovementBoundary -> Unit
                is QueueEntry.PacketEntry -> {
                    if (!isDiscardedOnRelease(entry.packet)) {
                        sendPacketSilently(entry.packet)
                    }
                }
            }
        }
    }

    private fun isDiscardedOnRelease(packet: Packet<*>): Boolean =
        packet is ServerboundUseItemPacket ||
            packet is ServerboundUseItemOnPacket ||
            packet is ServerboundPlayerActionPacket

    private fun stopEating(release: Boolean) {
        eating = false
        appleSlot = -1

        if (release && mc.connection != null) {
            flushAllQueued()
        } else {
            packetQueue.clear()
        }

        movingPackets = 0
        needSkip = false
    }

    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent> { event ->
        if (eating && stopMove) {
            event.directionalInput = DirectionalInput.NONE
        }
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet
        val player = mc.player

        if (event.origin == TransferOrigin.INCOMING) {
            if (packet is ClientboundSetEntityMotionPacket && packet.id == player?.id) {
                needSkip = true
            }
            return@handler
        }

        if (event.origin != TransferOrigin.OUTGOING || event.isCancelled || player == null) {
            return@handler
        }

        if (!eating && pendingUse == null) {
            return@handler
        }

        if (packet is ServerboundSetCarriedItemPacket) {
            val expectedSlot = SilentHotbar.serversideSlot
            if (packet.slot != expectedSlot) {
                event.cancelEvent()
                return@handler
            }

            pendingUse?.let { pending ->
                if (packet.slot != pending.restoreSlot) {
                    pendingUse = null
                }
            }
            return@handler
        }

        if (!eating) {
            return@handler
        }

        // These packets must never be delayed; the rest of AutoGap's packet handling is intentionally
        // narrow so keep-alive, chat, inventory operations and respawn/sprint commands stay responsive.
        when (packet) {
            is ServerboundKeepAlivePacket,
            is ServerboundChatPacket,
            is ServerboundChatCommandPacket,
            is ServerboundContainerClickPacket,
            is ServerboundContainerClosePacket,
            is ServerboundPlayerCommandPacket -> return@handler
        }

        if (alwaysAttack && pendingUse == null && isAttack(packet)) {
            return@handler
        }

        packetQueue.addLast(QueueEntry.PacketEntry(packet))
        event.cancelEvent()
    }

    private fun isAttack(packet: Packet<*>): Boolean =
        packet is ServerboundAttackPacket || packet is ServerboundPunchPacket

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (!progressBar || (!eating && pendingUse == null)) {
            return@handler
        }

        val context = event.context
        val barWidth = 140
        val barHeight = 7
        val x = context.guiWidth() / 2 - barWidth / 2
        val y = (context.guiHeight() - 75).coerceAtLeast(20)
        val progress = if (pendingUse != null) {
            1f
        } else {
            (movingPackets.toFloat() / TARGET_MOVEMENT_TICKS).coerceIn(0f, 1f)
        }
        val filled = (barWidth * progress).toInt()
        val label = if (pendingUse != null) "Confirming" else "Time"

        context.fill(x - 3, y - 3, x + barWidth + 3, y + barHeight + 3, 0x50000000)
        context.fill(x - 2, y - 2, x + barWidth + 2, y + barHeight + 2, 0x2A000000)
        context.fill(x, y, x + barWidth, y + barHeight, -1778384896)
        if (filled > 0) {
            context.fill(x, y, x + filled, y + barHeight, PROGRESS_COLOR)
        }

        context.text(mc.font, label, x + (barWidth - mc.font.width(label)) / 2, y - 14, -1, false)
        context.text(mc.font, "${(progress * 100).toInt()}%", x + barWidth + 5, y, -1, false)
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        stopEating(release = false)
        pendingUse?.let { sendHeldItemChange(it.restoreSlot) }
        resetState()
        enabled = false
    }

    private const val TARGET_MOVEMENT_TICKS = 32
    private const val DRAIN_EVERY_TICKS = 3
    private const val USE_CONFIRM_TIMEOUT_MS = 5_000L
    private const val HEALTH_EPSILON = 0.01f
    private const val PROGRESS_COLOR = -12158977
}
