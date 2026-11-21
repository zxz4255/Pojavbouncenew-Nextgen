/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce).
 * Copyright (C) 2015-2026 CCBlueX and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * MoonLight ChestAura port for a ViaFabricPlus 1.8 target.
 * Original project: https://github.com/randomguy3725/MoonLight (GPL-3.0).
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.moonlight2.Moonlight1_8Packets
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBlockRotation
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.block.anotherChestPartDirection
import net.ccbluex.liquidbounce.utils.block.searchBlocksInRangeSorted
import net.ccbluex.liquidbounce.utils.block.state
import net.ccbluex.liquidbounce.utils.client.isEqual1_8
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.combat.CombatManager
import net.ccbluex.liquidbounce.utils.inventory.findBlocksEndingWith
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.raytracing.raytraceBlock
import net.minecraft.core.BlockPos
import net.ccbluex.liquidbounce.features.module.modules.player.cheststealer.ModuleChestStealer
import net.ccbluex.liquidbounce.utils.inventory.getSlotsInContainer
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.HitResult

/**
 * MoonLight-style ChestAura: opens every reachable storage block around the player in turn.
 *
 * <p>Written for the 1.8 target this client runs through ViaFabricPlus. Two run modes are exposed
 * because the two eras need different behaviour:</p>
 * - **Legacy (1.8)** — the C08 use-item-on packet is written raw through [Moonlight1_8Packets]'s
 *   connection, the block is marked as done in the same tick, and the next one is picked after
 *   [delay]. This is what MoonLight's ChestAura does, and it is what an anti-cheat sees as a normal
 *   chest open.
 * - **Silent** — the aura keeps the chest screen open on the client side only. Instead of closing and
 *   re-opening, it re-aims at the next block behind the interface: the container screen is not
 *   dismissed, the cursor stays free, and the vanilla server never sees a close packet until the
 *   module stops. `SilentOpen` additionally sends the 1.8 C16 open-inventory command first, so a
 *   server that requires the inventory to be open to accept clicks is satisfied.
 *
 * <p>The interaction itself goes through the client's own `useItemOn`, so block placement, item
 * consumption and the server's own validation are all unchanged. Wall reach and chest double-block
 * handling follow the same rules the built-in ChestStealer uses.</p>
 */
@Suppress("TooManyFunctions")
object ModuleMoonlightChestAura : ClientModule(
    "MoonlightChestAura", ModuleCategories.PLAYER, disableOnQuit = true, aliases = listOf("ChestAura")
) {

    private val range by float("Range", 4.5f, 1f..6f, "blocks")
    private val wallRange by float("WallRange", 0f, 0f..6f, "blocks")
    private val delay by int("Delay", 3, 1..40, "ticks")
    private val mode by enumChoice("OpenMode", OpenMode.LEGACY)
    private val silentOpen by boolean("SilentOpen", false)
    private val closeWhenDone by boolean("CloseWhenDone", true)
    private val swingHand by boolean("Swing", true)
    private val pauseInCombat by boolean("PauseInCombat", true)
    private val pauseWhileUsing by boolean("PauseWhileUsing", true)
    private val rotateOnly by boolean("RotateOnly", false)
    private val skipBlockedChests by boolean("SkipBlockedChests", true)
    private val maxPerTick by int("MaxPerTick", 1, 1..4)
    private val skipUpperShulkers by boolean("SkipUpperShulkers", true)

    /**
     * Storage blocks the aura may open. The default is derived from the registry so a modded
     * `*_chest` is included automatically, exactly like the built-in ChestStealer does it.
     */
    private val validBlocks by blocks(
        "ValidBlocks",
        findBlocksEndingWith("CHEST", "SHULKER_BOX", "BARREL", "FURNACE", "HOPPER", "DISPENSER", "DROPPER")
            .also { it.add(Blocks.BREWING_STAND) },
    )

    /** Rotation settings shared with the aura when both are on; a private group keeps them separate. */
    private val rotations = tree(RotationsValueGroup(this))

    private val visited: MutableSet<BlockPos> = HashSet()
    private var currentTarget: BlockPos? = null
    private var lastInteraction = 0L
    private var attempts = 0
    private var silentScreenHeld = false
    private var lastRotationPublish = 0L

    /** Block we asked the server to open, waiting for the container screen to confirm it. */
    private var pendingOpen: BlockPos? = null

    /** When [pendingOpen] was requested, so a lost confirmation cannot wedge the aura. */
    private var pendingSince = 0L

    /** Container the aura opened that is still being looted; not finished until it is empty. */
    private var lootingBlock: BlockPos? = null

    /** When looting started, so a container that never empties cannot stall the sweep. */
    private var lootStartedAt = 0L

    override fun onEnabled() {
        visited.clear()
        currentTarget = null
        attempts = 0
        lastInteraction = 0L
        pendingOpen = null
        lootingBlock = null
    }

    override fun onDisabled() {
        visited.clear()
        currentTarget = null
        pendingOpen = null
        lootingBlock = null
        if (silentScreenHeld) {
            mc.execute { player.closeContainer() }
            silentScreenHeld = false
        }
    }

    @Suppress("unused")
    private val worldChange = handler<WorldChangeEvent> {
        visited.clear()
        currentTarget = null
        pendingOpen = null
        lootingBlock = null
        silentScreenHeld = false
    }

    /**
     * Manual clicks are recorded, so the aura does not re-open a chest the player just used.
     */
    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet
        if (event.origin != TransferOrigin.OUTGOING) return@handler
        if (packet is ServerboundUseItemOnPacket && !event.isCancelled) {
            mc.execute { track(packet.hitResult.blockPos) }
        }
    }

    /**
     * Keeps the aura's rotation alive between ticks. Re-picking a target needs a block scan, so it is
     * throttled: the rotation manager holds the last plan for several ticks anyway.
     */
    @Suppress("unused")
    private val rotationHandler = handler<RotationUpdateEvent> {
        val now = System.currentTimeMillis()
        if (now - lastRotationPublish < ROTATION_REFRESH_MS) return@handler
        lastRotationPublish = now
        pickTarget()
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        /*
         * Resolve the outstanding open request FIRST, before every early return below.
         *
         * This used to sit after the screen and pause guards. Two bugs came from that: once a
         * container was open the confirmation could never run (so the aura depended on ChestStealer
         * having already finished), and a pause taken right after an open (combat, eating) left
         * `pendingOpen` set with nothing to resolve it — the chest was then silently lost. Resolving
         * first means a pause can delay the *next* chest but can never lose the current one.
         */
        confirmPendingOpen()
        if (pendingOpen != null) {
            // Still waiting on the server; do not start another chest.
            return@handler
        }

        /*
         * Containers the aura opened stay "in progress" until they are actually empty.
         *
         * While one is in progress the sweep does not start another: that is what makes the whole
         * thing smooth (open, empty, close, next) instead of opening chest after chest and leaving
         * the earlier ones half full. The loot window is bounded so an unreadable or permanently
         * non-empty container cannot stall the sweep forever.
         *
         * Like the confirmation above, this is resolved before the pause guards: a chest that gets
         * emptied while the player is fighting must still be released, otherwise the sweep would be
         * blocked on it for the rest of the session.
         */
        val lootInProgress = advanceLooting()
        if (lootInProgress) {
            return@handler
        }

        if (pauseInCombat && CombatManager.isInCombat) return@handler
        if (pauseWhileUsing && player.isUsingItem) return@handler

        /*
         * Some container is on screen that the aura is not tracking (a hand-opened chest, a server
         * menu). Do not open another chest over it, and do not leave the player stuck in it either:
         * close it once it is emptied.
         */
        if (!rotateOnly && mc.gui.screen() != null && mode != OpenMode.SILENT) {
            if (isContainerLootable()) {
                return@handler
            }
            if (!closeStaleContainer()) {
                return@handler
            }
        }
        if (isEqual1_8 && mode == OpenMode.SILENT && !Moonlight1_8Packets.isAvailable()) return@handler

        val target = pickTarget()
        if (target == null) {
            // Nothing left in reach: release the silently held screen once, so the player is not left
            // staring at a container they cannot reach anything else from.
            if (closeWhenDone && silentScreenHeld && mc.gui.screen() != null) {
                silentScreenHeld = false
                mc.execute { player.closeContainer() }
            }
            return@handler
        }
        val now = System.currentTimeMillis()
        if (now - lastInteraction < delay * 50L) return@handler

        val rotation = RotationManager.serverRotation
        val state = target.state ?: return@handler
        val hit = raytraceBlock(range.toDouble(), rotation, target, state) ?: return@handler
        if (hit.type != HitResult.Type.BLOCK || hit.blockPos != target) return@handler

        if (rotateOnly) {
            track(target)
            return@handler
        }

        var opened = 0
        while (opened < maxPerTick) {
            val next = if (opened == 0) target else (pickTarget() ?: break)
            val nextState = next.state ?: break
            val nextHit = raytraceBlock(range.toDouble(), RotationManager.serverRotation, next, nextState) ?: break
            if (nextHit.type != HitResult.Type.BLOCK || nextHit.blockPos != next) break
            if (mode == OpenMode.LEGACY) {
                openLegacy(next, nextHit)
                // One real interaction per tick: the server answers with a container screen and the
                // next chest has to wait for that round trip.
                break
            }
            if (!openSilent(next, nextHit)) break
            opened++
        }
    }

    /**
     * Opens one container and **waits for the server to actually hand us a screen** before marking the
     * block as used.
     *
     * <p>This is the fix for "ChestAura sometimes doesn't get stolen from":</p>
     *
     * <p>The old code called `track(target)` as soon as `useItemOn` returned
     * [InteractionResult.Success]. That result only means *the client sent the packet* — the server can
     * still reject it (out of range, line of sight, a cooldown, a region plugin, the chest being
     * occupied). Because the block was already in [visited], the aura never retried it, so the chest
     * silently stayed unopened and ChestStealer had nothing to loot. It only ever worked when the
     * server happened to accept, which is exactly the "概率无用" the user reported.</p>
     *
     * <p>Now the block is marked only once [onContainerOpened] confirms a container screen is up. If no
     * screen arrives within [CONFIRM_TIMEOUT_MS], the attempt is counted and the chest stays eligible,
     * so the next pass retries it. A block that fails [MAX_ATTEMPTS]-and-then-some times is finally
     * dropped so one unreachable chest cannot stall the whole sweep.</p>
     */
    private fun openLegacy(target: BlockPos, hit: net.minecraft.world.phys.BlockHitResult) {
        val state = target.state ?: return
        val interaction = mc.gameMode ?: return
        val result = interaction.useItemOn(player, InteractionHand.MAIN_HAND, hit)
        lastInteraction = System.currentTimeMillis()
        if (result is InteractionResult.Success) {
            if (swingHand) SwingMode.DO_NOT_HIDE.swing(InteractionHand.MAIN_HAND)

            /*
             * Client accepted the interaction; the server has not confirmed it yet. Queue this block as
             * "awaiting server" instead of marking it done. `onContainerOpened` promotes it to visited
             * when the screen arrives, `onConfirmTimeout` gives up on it otherwise.
             */
            pendingOpen = target
            pendingSince = System.currentTimeMillis()
            currentTarget = null
        } else {
            attempts++
            if (attempts > MAX_ATTEMPTS) {
                // The client itself refused (no line of sight, block changed): stop retrying this one.
                track(target)
                attempts = 0
            }
        }
        // The block may have changed while the interaction was being built; never keep a stale one.
        if (target.state !== state) currentTarget = null
    }

    /**
     * Called whenever a container screen becomes visible.
     *
     * When the aura was the one who asked for it, this is the confirmation the old code never waited
     * for: only now does the block count as looted, and only now may the next chest be considered.
     */
    fun onContainerOpened() {
        val pending = pendingOpen ?: return
        pendingOpen = null
        attempts = 0

        /*
         * Do NOT mark the chest finished here.
         *
         * Opening a chest is only half the job: ChestStealer empties it afterwards, and that takes
         * several ticks. The old code recorded the block immediately, so the aura raced ahead to the
         * next chest while the previous one still had items in it — and once ChestStealer closed the
         * screen to move on, whatever it had not reached yet was lost. That is the "not smooth, items
         * left behind" half of the report.
         *
         * Instead the block is remembered as [lootingBlock] and only becomes [visited] once the
         * container is actually empty (or the loot window times out).
         */
        lootingBlock = pending
        lootStartedAt = System.currentTimeMillis()
        lastInteraction = lootStartedAt
    }

    /**
     * Advances the "looting in progress" state.
     *
     * @return true when the aura must not start another chest yet
     */
    private fun advanceLooting(): Boolean {
        if (lootingBlock == null) {
            return false
        }
        val screen = mc.gui.screen()
        val stillFull = screen is AbstractContainerScreen<*> &&
            ModuleChestStealer.acceptsScreen(screen) &&
            screen.getSlotsInContainer().any { !it.itemStack.isEmpty }
        val timedOut = System.currentTimeMillis() - lootStartedAt > LOOT_TIMEOUT_MS
        if (stillFull && !timedOut) {
            // ChestStealer is working on it; let it finish.
            return true
        }
        finishLooting()
        return false
    }

    /**
     * Called once the container the aura opened has been emptied, so the sweep may continue.
     *
     * Returns true when the block was released; the caller does not need to do anything else.
     */
    private fun finishLooting(): Boolean {
        val looting = lootingBlock ?: return false
        lootingBlock = null
        attempts = 0
        track(looting)
        lastInteraction = System.currentTimeMillis()
        return true
    }

    /**
     * A pending open that never produced a screen: the server refused it, or the round trip was lost.
     * Count it, and keep the chest eligible so a later pass retries instead of skipping it forever.
     */
    fun onConfirmTimeout() {
        val pending = pendingOpen ?: return
        pendingOpen = null
        lastInteraction = System.currentTimeMillis()
        attempts++
        if (attempts > MAX_ATTEMPTS) {
            track(pending)
            attempts = 0
        }
    }

    /**
     * Opens one container without dismissing the screen.
     *
     * @return true when another container may be opened this tick
     */
    private fun openSilent(target: BlockPos, hit: net.minecraft.world.phys.BlockHitResult): Boolean {
        if (!isEqual1_8) {
            // Outside a 1.8 target there is no silent screen to hold; fall back to the normal path,
            // which needs the server's answer before the next chest.
            openLegacy(target, hit)
            return false
        }
        if (silentOpen && !silentScreenHeld) {
            // 1.8 C16: "open inventory" client command. Some servers require this before accepting
            // C0E clicks, and it is what MoonLight sends before its own silent chest routine.
            Moonlight1_8Packets.sendOpenInventory()
            silentScreenHeld = true
        }
        val interaction = mc.gameMode ?: return false
        val result = interaction.useItemOn(player, InteractionHand.MAIN_HAND, hit)
        lastInteraction = System.currentTimeMillis()
        if (result is InteractionResult.Success) {
            if (swingHand) SwingMode.DO_NOT_HIDE.swing(InteractionHand.MAIN_HAND)
            /*
             * Silent mode keeps the screen open on purpose, so there is no confirmation packet to wait
             * for — but the block still must not be finalised before the container has been emptied.
             * Handing it to the same "looting in progress" slot gives the silent path the same
             * guarantee as the legacy one: no chest is written off while it still has items.
             */
            lootingBlock = target
            lootStartedAt = System.currentTimeMillis()
            currentTarget = null
            attempts = 0
            // The chest GUI is deliberately *not* dismissed: the next chest is opened over it, and the
            // server only ever sees the container it was told about.
            return true
        }
        attempts++
        if (attempts > MAX_ATTEMPTS) {
            track(target)
            attempts = 0
        }
        return false
    }

    /**
     * Promotes / expires the outstanding open request.
     *
     * A container screen on the client is the only reliable proof that the server accepted the open.
     * If it does not arrive in [CONFIRM_TIMEOUT_MS] the request is written off and the chest stays
     * eligible, so a refused open costs one retry instead of losing the chest for the whole session.
     */
    private fun confirmPendingOpen() {
        if (pendingOpen == null) {
            return
        }
        if (mc.gui.screen() is AbstractContainerScreen<*>) {
            onContainerOpened()
            return
        }
        if (System.currentTimeMillis() - pendingSince > CONFIRM_TIMEOUT_MS) {
            onConfirmTimeout()
        }
    }

    /**
     * Whether the container currently on screen still holds something the stealer would take.
     *
     * The aura must not open the next chest over one that is still being looted, and must not wait
     * forever on one that has already been emptied. This asks the stealer directly, so both modules
     * agree on what "finished" means instead of relying on timing.
     */
    private fun isContainerLootable(): Boolean {
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return false
        if (!ModuleChestStealer.acceptsScreen(screen)) {
            return false
        }
        // Any non-empty container slot means there is still work to do.
        return screen.getSlotsInContainer().any { !it.itemStack.isEmpty }
    }

    /**
     * Closes a container that is no longer being looted, so the sweep can continue.
     *
     * @return true when the aura may proceed this tick
     */
    private fun closeStaleContainer(): Boolean {
        val screen = mc.gui.screen() ?: return true
        if (screen !is AbstractContainerScreen<*>) {
            // Some other GUI (chat, pause menu) is open: do not fight the player for it.
            return false
        }
        // Give the stealer / the player a moment before forcing a close, so a container that just
        // opened is never closed out from under an in-flight click.
        val now = System.currentTimeMillis()
        if (now - lastInteraction < STALE_CONTAINER_GRACE_MS) {
            return false
        }
        lastInteraction = now
        mc.execute { player.closeContainer() }
        return false
    }

    /** Chooses the nearest reachable, not-yet-used storage block and aims at it. */
    private fun pickTarget(): BlockPos? {
        val eye = player.eyePosition
        val candidates = eye.searchBlocksInRangeSorted(range) { pos, state ->
            if (pos in visited) return@searchBlocksInRangeSorted false
            if (state.block !in validBlocks) return@searchBlocksInRangeSorted false
            if (skipBlockedChests && !isReachableChest(state, pos)) return@searchBlocksInRangeSorted false
            if (skipUpperShulkers && state.block is ShulkerBoxBlock && pos.y > player.blockY + 1) {
                return@searchBlocksInRangeSorted false
            }
            true
        }
        for ((pos, state) in candidates) {
            val rotation = raytraceBlockRotation(eye, pos, state, range.toDouble(), wallRange.toDouble())
                ?: continue
            currentTarget = pos
            if (!rotateOnly) {
                // Aim through the shared manager, at a lower priority than combat so an aura still owns
                // the view when both are running.
                RotationManager.setRotationTarget(
                    rotation.rotation,
                    considerInventory = true,
                    valueGroup = rotations,
                    priority = Priority.IMPORTANT_FOR_USAGE_1,
                    provider = this,
                )
            }
            return pos
        }
        currentTarget = null
        return null
    }

    /** A chest that is blocked by a block above it cannot be opened; skip it instead of retrying. */
    private fun isReachableChest(state: BlockState, pos: BlockPos): Boolean {
        if (state.block !is ChestBlock) return true
        val world = mc.level ?: return false
        if (ChestBlock.isChestBlockedAt(world, pos)) return false
        val other = state.anotherChestPartDirection() ?: return true
        return !ChestBlock.isChestBlockedAt(world, pos.relative(other))
    }

    private fun track(pos: BlockPos) {
        visited += pos
        // Track the other half of a double chest too, so it is not opened twice.
        pos.state?.anotherChestPartDirection()?.let { direction -> visited += pos.relative(direction) }
        if (visited.size > MAX_VISITED) {
            // A long session in one chunk must not grow without bound.
            val iterator = visited.iterator()
            repeat(visited.size - MAX_VISITED / 2) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    /** Called by the kill aura so both modules never fight over the rotation priority. */
    fun hasTarget(): Boolean = currentTarget != null

    fun visitedCount(): Int = visited.size

    fun stateLabel(): String = when {
        currentTarget != null -> "Open"
        visited.isNotEmpty() -> "Done"
        else -> "Idle"
    }

    enum class OpenMode(override val tag: String) : Tagged {
        LEGACY("Legacy"),
        SILENT("Silent"),
    }

    private const val MAX_VISITED = 512
    private const val ROTATION_REFRESH_MS = 100L

    /** How long to wait for the server to answer an open request with a container screen. */
    private const val CONFIRM_TIMEOUT_MS = 1_000L

    /** How long an emptied container may stay on screen before the aura closes it and moves on. */
    private const val STALE_CONTAINER_GRACE_MS = 250L

    /** Client-side refusals tolerated per block before it is written off. */
    private const val MAX_ATTEMPTS = 8

    /** Upper bound on how long one container may be looted before the sweep moves on. */
    private const val LOOT_TIMEOUT_MS = 6_000L
}
