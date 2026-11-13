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
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.network.protocol.game.ServerboundPunchPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.ArrayDeque

/**
 * Eats a golden apple while withholding movement packets, then releases them in a burst.
 *
 * Ported from a 26.2 decompile (`ModuleGapple`, category `GPCategories.MORE`).
 *
 * The original queued outgoing packets until 32 movement packets had accumulated ("C03" in 1.8
 * naming), then switched to the apple, used it, and flushed the queue. On legacy servers the client
 * tick rate drives eating, so compressing the ticks makes the apple finish sooner.
 *
 * ## What changed during the port
 *
 *  - `GPCategories.MORE` does not exist; registered under [ModuleCategories.PLAYER].
 *  - `ServerboundSwingPacket` is `ServerboundPunchPacket` in 26.3.
 *  - `ServerboundClientCommandPacket` (respawn) is `ServerboundPlayerCommandPacket`; it is passed
 *    through untouched rather than queued, since queueing a respawn would strand the player.
 *  - `ServerboundChatCommandSignedPacket` no longer exists and was dropped from the pass-through list.
 *  - The reflection that nulled `SilentHotbar.hotbarState` was replaced with the public
 *    [SilentHotbar.resetSlot] API.
 *  - `connection.send(packet, null, true)` was replaced with [sendPacketSilently], which is the
 *    client's own raw-send helper and still notifies the rotation manager and packet logger.
 *  - Settings now use `int`/`boolean` value groups; the decompiled `RangedValue` delegates are an
 *    artefact of Kotlin compilation, not a distinct API.
 *  - The alias `AutoGapple` was NOT carried over: `ModuleAutoBuff` already registers it. This module
 *    uses `BlinkGapple` instead so `.toggle` stays unambiguous.
 *
 * ## Safety
 *
 * This module deliberately delays movement packets, which servers may treat as a blink/lag exploit.
 * It is off by default. The queue is always flushed or dropped on disable, death and world change so
 * the player cannot be left desynced.
 *
 * @see net.ccbluex.liquidbounce.features.blink.BlinkManager
 */
@Suppress("detekt:TooManyFunctions")
object ModuleGapple : ClientModule("Gapple", ModuleCategories.PLAYER, aliases = listOf("BlinkGapple")) {

    private val health by int("Health", 20, 0..40, "HP")
    private val sendDelay by int("SendDelay", 10, 1..10, "ticks")
    private val stuck by boolean("Stuck", false)
    private val stopMove by boolean("StopMove", false)

    /**
     * Let attacks and swings through while queueing instead of holding them back.
     */
    private val noCancelAttack by boolean("NoCancelAttack", false)

    /**
     * Drop queued attacks and swings entirely on flush.
     */
    private val noAttack by boolean("NoAttack", false)

    private val autoGapple by boolean("AutoGapple", false)
    private val smartGapple by boolean("SmartGapple", false)
    private val maxSmartDelay by int("MaxSmartDelay", 55, 0..200, "ticks")
    private val progressBar by boolean("ProgressBar", true)
    private val enchanted by boolean("Enchanted", true)

    private val packetQueue = ArrayDeque<Packet<*>>()

    private var queueing = false
    private var blinking = false
    private var appleSlot = -1
    /**
     * Number of movement packets withheld so far in this eat cycle.
     *
     * Counts *arrivals*, not the current queue length. The original decremented its counter in
     * `releaseOne`, so with `SendDelay = 1` the per-tick arrival and release cancelled out and the
     * 32-packet threshold was never reached — the module stalled with the queue growing forever.
     * Progress is now monotonic and the queue length is read from [packetQueue] directly.
     */
    private var movePackets = 0
    private var syncedServerSlot = -1
    private var noAppleWarned = false
    private var smartCooldown = 0
    private var smartDelayTicks = INITIAL_SMART_DELAY
    private var lastHealth = 0f
    private var lastGappleTime = 0L
    private var suppressReleaseTicks = 0

    private var freezeX = 0.0
    private var freezeY = 0.0
    private var freezeZ = 0.0

    override fun onEnabled() {
        resetState()
    }

    override fun onDisabled() {
        queueing = false

        // Only flush if there is still a live connection to flush into.
        val player = mc.player
        val release = player != null && player.isAlive && mc.level != null
        stopQueue(release)
        suppressReleaseTicks = 0
    }

    private fun resetState() {
        queueing = false
        blinking = false
        packetQueue.clear()
        appleSlot = -1
        movePackets = 0
        syncedServerSlot = -1
        noAppleWarned = false
        smartCooldown = 0
        smartDelayTicks = INITIAL_SMART_DELAY
        lastHealth = 0f
        lastGappleTime = 0L
        suppressReleaseTicks = 0
    }

    private fun startQueue() {
        val player = mc.player ?: return

        packetQueue.clear()
        movePackets = 0
        blinking = true
        syncedServerSlot = -1
        syncServerSlot()

        freezeX = player.x
        freezeY = player.y
        freezeZ = player.z
    }

    /**
     * Keeps the server's carried slot in step with the real client slot while we are withholding
     * packets, so the eventual apple use is not rejected for holding the wrong item.
     */
    private fun syncServerSlot() {
        val slot = mc.player?.inventory?.realSelectedSlot ?: return

        if (slot !in 0..8 || slot == syncedServerSlot) {
            return
        }

        sendPacketSilently(ServerboundSetCarriedItemPacket(slot))
        syncedServerSlot = slot
    }

    private fun isAttack(packet: Packet<*>): Boolean =
        packet is ServerboundAttackPacket || packet is ServerboundPunchPacket

    private fun flushQueuedAttacks() {
        if (packetQueue.isEmpty()) {
            return
        }

        val remaining = ArrayDeque<Packet<*>>()

        while (true) {
            val packet = packetQueue.poll() ?: break

            if (isAttack(packet)) {
                if (!noAttack) sendPacketSilently(packet)
            } else {
                remaining.add(packet)
            }
        }

        packetQueue.addAll(remaining)
    }

    private fun releaseOne() {
        val packet = packetQueue.poll() ?: return

        sendPacketSilently(packet)
    }

    private fun stopQueue(release: Boolean) {
        blinking = false

        if (release) {
            while (true) {
                val packet = packetQueue.poll() ?: break

                if (noAttack && isAttack(packet)) continue

                sendPacketSilently(packet)
            }
        } else {
            packetQueue.clear()
        }

        movePackets = 0
        syncedServerSlot = -1
    }

    private fun finishEating() {
        val player = mc.player ?: return
        val apple = appleSlot

        if (apple !in 0..8) {
            queueing = false
            stopQueue(true)
            return
        }

        val originalSlot = player.inventory.realSelectedSlot
        val restoreSlot = if (originalSlot in 0..8) originalSlot else 0

        syncServerSlot()
        flushQueuedAttacks()

        // Select the apple on both sides, use it, then put the original slot back.
        sendPacketSilently(ServerboundSetCarriedItemPacket(apple))
        syncedServerSlot = apple
        SilentHotbar.resetSlot(this)
        player.inventory.selectedSlot = apple

        mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)

        // The local use prediction would otherwise emit RELEASE_USE_ITEM and cancel the eat.
        suppressReleaseTicks = 2
        player.stopUsingItem()

        queueing = false
        stopQueue(true)

        sendPacketSilently(ServerboundSetCarriedItemPacket(restoreSlot))
        syncedServerSlot = restoreSlot
        player.inventory.selectedSlot = restoreSlot
        SilentHotbar.resetSlot(this)

        lastGappleTime = System.currentTimeMillis()

        if (!autoGapple) {
            enabled = false
            return
        }

        if (smartGapple) {
            val currentHealth = player.health

            if (lastHealth > 0f) {
                // Health went up -> the apple worked, wait a little longer next time.
                smartDelayTicks += if (currentHealth > lastHealth) SMART_DELAY_STEP else -SMART_DELAY_STEP
                smartDelayTicks = smartDelayTicks.coerceIn(0, maxSmartDelay)
            }

            lastHealth = currentHealth
            smartCooldown = smartDelayTicks
        } else {
            movePackets = 0
            appleSlot = findGappleSlot()
        }
    }

    private fun ItemStack.isGapple(): Boolean =
        `is`(Items.GOLDEN_APPLE) || (enchanted && `is`(Items.ENCHANTED_GOLDEN_APPLE))

    private fun findGappleSlot(): Int {
        val inventory = mc.player?.inventory ?: return -1

        return (0..8).firstOrNull { inventory.getItem(it).isGapple() } ?: -1
    }

    private fun freeze() {
        val player = mc.player ?: return

        player.deltaMovement = Vec3.ZERO
        player.setPos(freezeX, freezeY, freezeZ)
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (suppressReleaseTicks > 0) {
            suppressReleaseTicks--
        }

        val player = mc.player ?: return@handler

        if (mc.level == null || player.isDeadOrDying || !player.isAlive) {
            queueing = false
            stopQueue(false)
            return@handler
        }

        // Reset the adaptive delay after a quiet spell so a stale value does not persist.
        if (smartGapple && lastGappleTime != 0L &&
            System.currentTimeMillis() - lastGappleTime > SMART_RESET_MS
        ) {
            smartDelayTicks = INITIAL_SMART_DELAY
            lastHealth = 0f
            smartCooldown = 0
            lastGappleTime = 0L
        }

        if (player.health >= health.toFloat()) {
            queueing = false
            if (blinking) stopQueue(true)
            smartCooldown = 0
            lastHealth = 0f
            return@handler
        }

        if (!queueing && smartGapple && smartCooldown > 0) {
            smartCooldown--
            return@handler
        }

        if (!queueing) {
            if (smartGapple) lastHealth = player.health
            movePackets = 0
            appleSlot = findGappleSlot()
        }

        if (appleSlot !in 0..8) {
            queueing = false
            stopQueue(false)

            if (!noAppleWarned) {
                notification(
                    "Gapple",
                    "快捷栏没有可用的金苹果（检查快捷栏 / Enchanted 设置），模块保持开启",
                    NotificationEvent.Severity.INFO
                )
                noAppleWarned = true
            }

            return@handler
        }

        noAppleWarned = false

        if (queueing) {
            if (stuck) freeze()
            if (!blinking) startQueue()
        } else {
            queueing = true
        }

        syncServerSlot()

        if (movePackets >= TARGET_MOVE_PACKETS) {
            finishEating()
            return@handler
        }

        if (blinking && player.tickCount % sendDelay == 0) {
            releaseOne()
        }
    }

    @Suppress("unused")
    private val movementHandler = handler<MovementInputEvent> { event ->
        if (queueing && stopMove) {
            event.directionalInput = DirectionalInput.NONE
        }
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.OUTGOING || event.isCancelled) {
            return@handler
        }

        val packet = event.packet

        // Swallow the predicted release so the server keeps the item in use.
        if (suppressReleaseTicks > 0 &&
            packet is ServerboundPlayerActionPacket &&
            packet.action == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM
        ) {
            event.cancelEvent()
            return@handler
        }

        if (!blinking) {
            return@handler
        }

        when (packet) {
            is ServerboundSetCarriedItemPacket -> {
                val realSlot = mc.player?.inventory?.realSelectedSlot ?: packet.slot

                if (packet.slot != realSlot) {
                    // Something tried to change the carried slot behind our back.
                    event.cancelEvent()
                    syncServerSlot()
                } else {
                    syncedServerSlot = packet.slot
                }
            }

            // Inventory interaction, item use and chat must not be delayed.
            is ServerboundContainerClickPacket,
            is ServerboundContainerClosePacket,
            is ServerboundUseItemPacket,
            is ServerboundChatPacket,
            is ServerboundChatCommandPacket -> Unit

            // A respawn must never sit in the queue.
            is ServerboundPlayerCommandPacket -> Unit

            is ServerboundPlayerActionPacket ->
                if (packet.action != ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
                    packetQueue.add(packet)
                    event.cancelEvent()
                }

            else -> {
                // Attacks can be allowed through so combat keeps working while eating.
                if (isAttack(packet) && noCancelAttack) {
                    syncServerSlot()
                    return@handler
                }

                if (packet is ServerboundMovePlayerPacket) {
                    movePackets++
                }

                packetQueue.add(packet)
                event.cancelEvent()
            }
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (!progressBar) {
            return@handler
        }

        val showCooldown = smartCooldown > 0
        if (!queueing && !showCooldown) {
            return@handler
        }

        val context = event.context
        val barWidth = 140
        val barHeight = 7
        val x = context.guiWidth() / 2 - barWidth / 2
        val y = (context.guiHeight() / 2 + 150).coerceAtMost(context.guiHeight() - 30)

        val progress = if (queueing) {
            (movePackets.toFloat() / TARGET_MOVE_PACKETS).coerceIn(0f, 1f)
        } else {
            (smartCooldown.toFloat() / smartDelayTicks.coerceAtLeast(1)).coerceIn(0f, 1f)
        }

        val filled = (barWidth * progress).toInt()
        val color = if (queueing) EATING_COLOR else COOLDOWN_COLOR

        context.fill(x - 3, y - 3, x + barWidth + 3, y + barHeight + 3, 0x50000000)
        context.fill(x - 2, y - 2, x + barWidth + 2, y + barHeight + 2, 0x2A000000)
        context.fill(x, y, x + barWidth, y + barHeight, -1778384896)

        if (filled > 0) {
            context.fill(x, y, x + filled, y + barHeight, color)
        }

        context.text(mc.font, "${(progress * 100).toInt()}%", x + barWidth + 5, y, -1, false)
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        resetState()
    }

    /**
     * 32 withheld movement packets, matching the original's `c03s >= 32` threshold.
     */
    private const val TARGET_MOVE_PACKETS = 32
    private const val INITIAL_SMART_DELAY = 15
    private const val SMART_DELAY_STEP = 5
    private const val SMART_RESET_MS = 20_000L

    private const val EATING_COLOR = -12158977
    private const val COOLDOWN_COLOR = -24576
}
