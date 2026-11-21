/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce).
 * Copyright (C) 2015-2026 CCBlueX and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * AutoGap behaviour ported from MoonLight's combat/AutoGap.java:
 * https://github.com/randomguy3725/MoonLight (GPL-3.0).
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerNetworkMovementTickEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.moonlight.MoonlightApplePlanner
import net.ccbluex.liquidbounce.features.module.modules.combat.moonlight.MoonlightBufferedActions
import net.ccbluex.liquidbounce.features.module.modules.combat.moonlight.MoonlightLegacyPackets
import net.ccbluex.liquidbounce.features.module.modules.combat.moonlight.MoonlightPacketQueue
import net.ccbluex.liquidbounce.interfaces.LocalPlayerAddition
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.markAsError
import net.ccbluex.liquidbounce.utils.client.usesViaFabricPlus
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FINAL_DECISION
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.network.sendCloseInventory
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundChatAckPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.resources.Identifier
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Locale
import kotlin.math.roundToInt

/**
 * MoonLight AutoGap on a 26.3 client talking to a 1.8 server through ViaFabricPlus.
 *
 * Like MoonLight, it buffers outgoing packets, drains one tick every third tick, and on 32
 * buffered motion ticks sends C09, C08, the remaining movement packets, then C09 to restore.
 * C09/C08 are encoded and sent as raw protocol-47 packets. Other buffered 26.3 packets run
 * through ViaFabricPlus when replayed and become their 1.8 C0-family equivalents on the wire.
 * ViaFabricPlus's 1.8 movement mixin supplies the 1.8 idle C03 every tick, including when still.
 */
@Suppress("TooManyFunctions", "MagicNumber")
object ModuleMoonlightAutoGap : ClientModule("AutoGap", ModuleCategories.COMBAT, disableOnQuit = true) {

    private val delay by int("Delay", 1000, 0..10000, "ms")
        .onChange { (((it + 50) / 100) * 100).coerceIn(0, 10000) }
    private val health by float("Health", 15f, 0f..20f, "HP")
        .onChange { ((it * 2f).roundToInt() / 2f).coerceIn(0f, 20f) }
    private val stopMove by boolean("StopMoveWhenEating", false)
    private val alwaysAttack by boolean("AlwaysAttack", false)
    private val autoClose by boolean("CloseWhenNoGoldenApple", true)
    private val lagWhenInAir by boolean("LagWhenInAir", false)

    // Extensions requested in addition to MoonLight's original hotbar-only logic.
    // This watcher is independent of module.running, so it can enable a disabled module.
    private val wholeInventory by boolean("WholeInventory", true)
    private val autoEnableWithApple by boolean("AutoEnableWithApple", true)
    private var autoEnableAfter = 0L
    private var waitForRefillUntilTick = -1

    private val stateLock = Any()
    // Deferred old-protocol Aura actions share the same ordering and tick markers as normal
    // packets. If eating ends, C08 is discarded but C02 INTERACT is released like MoonLight.
    private val packets = MoonlightPacketQueue<Any>()
    private val auraAttack = ThreadLocal.withInitial { false }
    @Volatile private var eating = false
    @Volatile private var needSkip = false
    private var lastEatMillis = System.currentTimeMillis()

    /** Exposed for MoonLight KillAura's original AutoGap blocking interaction. */
    val isEating: Boolean get() = running && eating

    private object AutoEnableWatcher : EventListener {
        @Suppress("unused")
        private val tick = handler<GameTickEvent> {
            val p = mc.player ?: return@handler
            if (!autoEnableWithApple || enabled || !p.isAlive || mc.gameMode?.playerMode?.isSurvival != true ||
                System.currentTimeMillis() < autoEnableAfter || !usesViaFabricPlus ||
                !MoonlightLegacyPackets.isAvailable() || !hasPersonalApple(p)
            ) return@handler
            enabled = true
        }
    }
    init { AutoEnableWatcher }

    // The original bar's animation continually retargets over 40 ms.
    private var barFrom = 0f
    private var barTo = 0f
    private var barChangedAt = 0L
    private val interStyle = Style.EMPTY.withFont(
        FontDescription.Resource(Identifier.fromNamespaceAndPath("liquidbounce", "moonlight_inter"))
    )

    override fun onEnabled() {
        synchronized(stateLock) {
            packets.reset()
            eating = false
            needSkip = false
        }
        waitForRefillUntilTick = -1
        barFrom = 0f
        barTo = 0f
        barChangedAt = System.currentTimeMillis()
    }

    override fun onDisabled() {
        val queued = synchronized(stateLock) {
            eating = false
            packets.drainAll(::dropOnFullRelease)
        }
        sendQueued(queued)
        needSkip = false
    }

    @Suppress("unused")
    private val motionHandler = handler<PlayerNetworkMovementTickEvent> { event ->
        if (event.state == EventState.POST) {
            synchronized(stateLock) {
                if (eating) packets.postMotion()
            }
            return@handler
        }

        val p = mc.player
        if (p == null || !p.isAlive) {
            synchronized(stateLock) {
                eating = false
                // Dead-player queues are discarded; never leave a phantom movement count behind.
                packets.reset()
            }
            return@handler
        }

        // ViaFabricPlus is an optional dependency: only load the legacy-packet adapter when present.
        if (!usesViaFabricPlus || !MoonlightLegacyPackets.isAvailable()) {
            synchronized(stateLock) {
                eating = false
                packets.reset()
            }
            chat(markAsError("AutoGap requires an active ViaFabricPlus 1.8 connection (protocol 47)."))
            enabled = false
            return@handler
        }

        if (mc.gameMode?.playerMode?.isSurvival != true ||
            System.currentTimeMillis() - lastEatMillis <= delay.toLong()
        ) {
            stopAndRelease()
            return@handler
        }

        // Preserve MoonLight's hotbar-first search. With WholeInventory, a missing hotbar apple
        // does not shut the module off: we move a personal-inventory apple into a hotbar slot.
        val slot = (0 until 9).firstOrNull { isGoldenApple(p.inventory.getItem(it)) } ?: -1
        if (slot == -1 && !hasPersonalApple(p) && autoClose) {
            enabled = false
            return@handler
        }
        if (slot == -1 || p.health >= health) {
            if (eating) stopAndRelease()
            if (slot == -1 && p.health < health && wholeInventory) tryRefillHotbar(p)
            return@handler
        }
        // Let the click and the server's inventory update get a chance to settle before sending C08.
        if (p.tickCount < waitForRefillUntilTick) return@handler

        synchronized(stateLock) { eating = true }
        if (packets.movingPackets() >= USE_TICKS) {
            eatWithLegacyPackets(slot)
        } else if (p.tickCount % 3 == 0) {
            val queued = synchronized(stateLock) {
                packets.drainOneTick()
            }
            sendQueued(queued)
        }
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent>(priority = FINAL_DECISION) { event ->
        val p = mc.player ?: return@handler
        if (mc.gameMode?.playerMode?.isSurvival != true) return@handler
        val packet = event.packet

        if (event.origin == TransferOrigin.INCOMING) {
            if (packet is ClientboundSetEntityMotionPacket && packet.id == p.id) needSkip = true
            return@handler
        }
        if (event.isCancelled || isExempt(packet)) return@handler
        // MoonLight's AlwaysAttack bypasses its queue for KillAura's C02 attack, not for every
        // player's attack or the separate C0A swing.
        if (packet is ServerboundAttackPacket && auraAttack.get()) return@handler

        synchronized(stateLock) {
            if (eating) {
                if (packet is ServerboundMovePlayerPacket) packets.addMovement(packet) else packets.add(packet)
                event.cancelEvent()
            }
        }
    }

    @Suppress("unused")
    private val inputHandler = handler<MovementInputEvent>(priority = FINAL_DECISION) { event ->
        if (eating && stopMove) event.directionalInput = DirectionalInput.NONE
    }

    @Suppress("unused")
    private val worldHandler = handler<WorldChangeEvent> {
        // Mirror MoonLight's world-change toggle; never carry the queue into a new world.
        enabled = false
    }

    /** Used around KillAura's attack only, matching MoonLight's Always Attack integration. */
    fun <T> withAuraAttack(action: () -> T): T {
        if (!running || !eating || !alwaysAttack) return action()
        val previous = auraAttack.get()
        auraAttack.set(true)
        return try {
            action()
        } finally {
            auraAttack.set(previous)
        }
    }

    /** Hooked at LivingEntity.travel, the 26.3 counterpart to MoonLight's MoveMathEvent. */
    fun shouldCancelTravel(): Boolean {
        if (!running) return false
        val p = mc.player ?: return false
        if (eating && lagWhenInAir &&
            (p as LocalPlayerAddition).`liquid_bounce$getPositionReminder`() < 20 && !needSkip
        ) {
            return true
        }
        if (needSkip) needSkip = false
        return false
    }

    private fun stopAndRelease() {
        val queued = synchronized(stateLock) {
            eating = false
            packets.drainAll(::dropOnFullRelease)
        }
        sendQueued(queued)
    }

    private fun sendQueued(queued: List<Any>): Boolean {
        if (queued.isEmpty()) return true
        if (mc.connection == null) return false
        for (entry in queued) {
            try {
                when (entry) {
                    is Packet<*> -> sendPacketSilently(entry)
                    is MoonlightBufferedActions.Interact -> MoonlightLegacyPackets.sendInteract(entry.entityId())
                    is MoonlightBufferedActions.SwordUse -> MoonlightLegacyPackets.sendSwordBlock(entry.stack(), -1)
                }
            } catch (exception: Exception) {
                logger.error("Failed to replay MoonLight AutoGap's packet queue", exception)
                autoEnableAfter = System.currentTimeMillis() + 10_000L
                if (enabled) enabled = false
                return false
            }
        }
        return true
    }

    /**
     * Buffer a legacy Aura block while eating. Ordinary 26.3 packets cannot encode a 1.8
     * sword C08 or a plain C02 INTERACT, so preserve their order explicitly in this queue.
     * Returns false when the Aura should send the actions immediately instead.
     */
    fun bufferAuraBlock(stack: ItemStack, interactEntityId: Int, sendUse: Boolean): Boolean =
        synchronized(stateLock) {
            if (!running || !eating) return@synchronized false
            if (interactEntityId >= 0) packets.add(MoonlightBufferedActions.Interact(interactEntityId))
            if (sendUse) packets.add(MoonlightBufferedActions.SwordUse(stack.copy()))
            true
        }

    private fun eatWithLegacyPackets(slot: Int) {
        val p = mc.player ?: return
        val stack = p.inventory.getItem(slot).copy()
        try {
            // Keep combat interactions out of the C08 + movement burst: in 26.3 they can
            // otherwise be replayed while the 1.8 server has the spoofed apple selected.
            var afterRestore: List<Any> = emptyList()
            MoonlightLegacyPackets.sendBurst(stack, slot, p.inventory.selectedSlot) {
                val queued = synchronized(stateLock) { packets.drainAll(::dropOnFullRelease) }
                val order = MoonlightBufferedActions.partitionForBurst(queued)
                afterRestore = order.afterRestore()
                check(sendQueued(order.duringUse())) { "Could not replay movement burst" }
            }
            check(sendQueued(afterRestore)) { "Could not replay deferred combat actions" }
            // Raw C08 never starts a local 26.3 item use. Do not decrement a different
            // held item's use timer or make it negative; wait for authoritative server state.
            lastEatMillis = System.currentTimeMillis()
        } catch (exception: Exception) {
            logger.error("Failed to send MoonLight AutoGap's 1.8 packets", exception)
            stopAndRelease()
            // Do not automatically re-enable every tick after an actual transmission failure.
            autoEnableAfter = System.currentTimeMillis() + 10_000L
            chat(markAsError("AutoGap could not send its 1.8 packets; module disabled for 10 seconds."))
            enabled = false
        }
    }

    private fun hasPersonalApple(p: LocalPlayer): Boolean {
        val limit = if (wholeInventory) 36 else 9
        if ((0 until limit).any { isGoldenApple(p.inventory.getItem(it)) }) return true
        if (!wholeInventory) return false
        // Include the player crafting and armor slots, offhand, and the cursor in detection.
        // 1.8 has no offhand; unmovable/occupied-cursor cases are intentionally left alone.
        return (1 until p.inventoryMenu.slots.size).any { isGoldenApple(p.inventoryMenu.getSlot(it).item) } ||
            isGoldenApple(p.inventoryMenu.carried)
    }

    private fun tryRefillHotbar(p: LocalPlayer) {
        if (p.tickCount < waitForRefillUntilTick || p.containerMenu !== p.inventoryMenu ||
            !p.containerMenu.carried.isEmpty || (mc.gui.screen() is AbstractContainerScreen<*> &&
                mc.gui.screen() !is InventoryScreen)
        ) return
        val source = (9 until 36).firstOrNull { isGoldenApple(p.inventory.getItem(it)) }
            ?: (1..4).firstOrNull { isGoldenApple(p.inventoryMenu.getSlot(it).item) }
            ?: return
        val empty = BooleanArray(9) { p.inventory.getItem(it).isEmpty }
        val destination = MoonlightApplePlanner.chooseHotbar(empty, p.inventory.selectedSlot)
        val connection = mc.connection ?: return
        val gameMode = mc.gameMode ?: return
        val openedHere = !InventoryManager.isInventoryOpenServerSide && mc.gui.screen() !is InventoryScreen
        try {
            if (openedHere) {
                // Send 1.8 C16 on this exact VFP connection, before the translated C0E click.
                MoonlightLegacyPackets.sendOpenInventory()
                InventoryManager.isInventoryOpenServerSide = true
            }
            // Source ids 9..35 and crafting ids 1..4 are identical in the player's 1.8 window.
            // Normal client click semantics update local inventory and VFP emits 1.8 C0E.
            gameMode.handleContainerInput(p.inventoryMenu.containerId, source, destination, ContainerInput.SWAP, p)
            waitForRefillUntilTick = p.tickCount + 3
        } catch (exception: Exception) {
            waitForRefillUntilTick = p.tickCount + 20
            logger.warn("Unable to move a golden apple into the hotbar", exception)
        } finally {
            if (openedHere) connection.sendCloseInventory() // 1.8 C0D
        }
    }

    private fun isGoldenApple(stack: ItemStack): Boolean =
        stack.`is`(Items.GOLDEN_APPLE) || stack.`is`(Items.ENCHANTED_GOLDEN_APPLE)

    private fun isExempt(packet: Packet<*>): Boolean = when (packet) {
        // MoonLight excludes chat and C09/C0E/C16/C0D from its blink queue.
        is ServerboundChatPacket, is ServerboundChatCommandPacket, is ServerboundChatCommandSignedPacket,
        is ServerboundChatAckPacket, is ServerboundChatSessionUpdatePacket,
        is ServerboundSetCarriedItemPacket, is ServerboundContainerClickPacket,
        is ServerboundClientCommandPacket, is ServerboundContainerClosePacket -> true
        else -> false
    }

    // Full releases discard C08/C07; every-third-tick partial drains do not.
    private fun dropOnFullRelease(packet: Any): Boolean = MoonlightBufferedActions.discardOnFullRelease(packet)

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (!eating) return@handler
        val percent = packets.movingPackets().coerceIn(0, USE_TICKS).toFloat() / USE_TICKS
        val x = event.context.guiWidth() / 2f
        val y = event.context.guiHeight() - 75f
        val width = animateBar(98f * percent)
        with(event.context) {
            // MoonLight's default Interface colours: cyan (NeverLose) and the synced dark background.
            drawRoundedRect(x - 51f, y - 13f, x + 50f, y + 8f, 2f, Color4b(0, 91, 124, 100))
            drawRoundedRect(x - 51f, y - 1f, x + 50f, y + 5f, 2f, Color4b(0, 91, 124, 100))
            if (width > 0f) {
                drawRoundedRect(x - 50f, y - 1f, x - 50f + width, y + 4f, 2f, Color4b(0, 187, 255))
            }
            centeredInter("Time", x, y - 9f, 0.68f)
            centeredInter(String.format(Locale.ROOT, "%.1f%%", percent * 100f), x, y + 2f, 0.55f)
        }
    }

    private fun GuiGraphicsExtractor.centeredInter(text: String, x: Float, y: Float, scale: Float) {
        val component = Component.literal(text).setStyle(interStyle)
        pose().pushMatrix()
        try {
            pose().translate(x, y)
            pose().scale(scale, scale)
            text(mc.font, component, -(mc.font.width(component) / 2), 0, Color4b.WHITE.argb, false)
        } finally {
            pose().popMatrix()
        }
    }

    private fun animateBar(destination: Float): Float {
        val now = System.currentTimeMillis()
        val t = ((now - barChangedAt).toFloat() / 40f).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        val current = barFrom + (barTo - barFrom) * smooth
        if (destination != barTo) {
            barFrom = current
            barTo = destination
            barChangedAt = now
        }
        return current
    }

    private const val USE_TICKS = 32
}
