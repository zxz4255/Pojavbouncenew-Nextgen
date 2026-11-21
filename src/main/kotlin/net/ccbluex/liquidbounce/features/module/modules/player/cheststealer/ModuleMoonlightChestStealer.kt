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
package net.ccbluex.liquidbounce.features.module.modules.player.cheststealer

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.moonlight2.Moonlight1_8Packets
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleOpaiDynamicIsland
import net.ccbluex.liquidbounce.features.module.modules.render.OpaiChestLoot
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBlockRotation
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.block.searchBlocksInRangeSorted
import net.ccbluex.liquidbounce.utils.client.isEqual1_8
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.collection.Filter
import net.ccbluex.liquidbounce.utils.collection.itemSortedSetOf
import net.ccbluex.liquidbounce.utils.combat.CombatManager
import net.ccbluex.liquidbounce.utils.inventory.CheckScreenHandlerTypeValueGroup
import net.ccbluex.liquidbounce.utils.inventory.CheckScreenTitleValueGroup
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.mergeableCapacityFor
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.ChestBlock

/**
 * MoonLight-style ChestStealer for a ViaFabricPlus **1.8 target**.
 *
 * <p>This is a separate module, not a switch on the built-in [ModuleChestStealer]: that one is
 * driven by `ScheduleInventoryActionEvent` and the shared `InventoryManager` rhythm (with its
 * constraint tree, miss-clicks and delays), so it cannot produce the 1.8-era "click slot, wait a
 * tick, click next slot" cadence MoonLight has. Nothing here touches the built-in module's state, so
 * both can be present in the client without influencing each other.</p>
 *
 * <p>Behaviour, and why it differs from the built-in stealer:</p>
 * - **Clicks** — [Mode.LEGACY_CLICK] sends one `QUICK_MOVE` per slot, which is what MoonLight's
 *   looting routine does on a 1.8 server. [Mode.PICKUP] sends a plain `PICKUP` click on every stack
 *   (slower, but some servers only accept that) and [Mode.THROW] throws the matched items instead.
 *   Everything goes through `MultiPlayerGameMode.handleContainerInput`, i.e. the vanilla packet with
 *   vanilla hashes, so no hand-written C0E ever leaves the client.
 * - **Pacing** — [SlotDelay] / [ClickDelay] are counted in client ticks, and [ClicksPerTick] is
 *   exposed because a 1.8 server expects roughly one click per tick.
 * - **Filtering** — a plain item whitelist/blacklist (MoonLight's `ItemFilter`) with
 *   [IgnoreFullStacks] and [TakeOnlyNamed] on top, instead of delegating to `InventoryCleaner`.
 * - **1.8 specifics** — under a 1.8 target the module sends the raw C16 `open inventory` command
 *   before the first click ([RawOpenInventory]; the same packet MoonLight sends), can keep the
 *   container open until it is empty ([CloseWhenDone]) and can re-close it after every finished
 *   container ([AutoClose]).
 * - **Rotation** — [RotateToSlot] keeps the head on the nearest storage block through
 *   `RotationManager` while the container is open, so the interaction looks deliberate.
 *
 * <p>Robustness: a click is only sent when the container id and slot still match what the server has,
 * and planning runs inside `runCatching`. A container that disappears mid-loot ends the session
 * instead of throwing inside the tick loop.</p>
 */
object ModuleMoonlightChestStealer : ClientModule(
    "MoonlightChestStealer", ModuleCategories.PLAYER,
    disableOnQuit = true, aliases = listOf("MoonlightStealer"),
) {

    private val mode by enumChoice("Mode", Mode.LEGACY_CLICK)
    private val clicksPerTick by int("ClicksPerTick", 1, 1..4, "clicks")
    private val slotDelay by intRange("SlotDelay", 1..2, 0..20, "ticks")

    private val autoClose by boolean("AutoClose", true)
    private val closeWhenDone by boolean("CloseWhenDone", true)
    private val rawOpenInventory by boolean("RawOpenInventory", true)
    private val swingOnClick by boolean("Swing", true)
    private val rotateToChest by boolean("RotateToChest", true)
    private val ignoreFullStacks by boolean("IgnoreFullStacks", false)
    private val takeOnlyNamed by boolean("TakeOnlyNamed", false)
    private val pauseInCombat by boolean("PauseInCombat", false)
    private val pauseWhileUsing by boolean("PauseWhileUsing", true)

    private val filter by enumChoice("Filter", Filter.BLACKLIST)
    private val items by items("Items", itemSortedSetOf())

    /** Rotation settings; only used when [rotateToChest] is on. */
    private val rotations = tree(RotationsValueGroup(this))

    private val checkScreenHandlerType = tree(CheckScreenHandlerTypeValueGroup(this))
    private val checkScreenTitle = tree(CheckScreenTitleValueGroup(this))

    /** Container-owned slot indices that already received their click in this container session. */
    private val handledSlots: MutableSet<Int> = ObjectOpenHashSet()
    private var sessionContainerId: Int? = null
    private var openInventorySent = false
    private var nextActionTick = 0
    private var announcedFailure = false

    private var tickCounter = 0

    /** Screen the Opai island is currently tracking for this module, so a new one starts a session. */
    private var islandScreen: AbstractContainerScreen<*>? = null

    override fun onEnabled() {
        resetSession()
    }

    override fun onDisabled() {
        if (closeWhenDone) {
            closeContainer()
        }
        resetSession()
    }

    private fun resetSession() {
        handledSlots.clear()
        islandScreen = null
        sessionContainerId = null
        openInventorySent = false
        nextActionTick = 0
        announcedFailure = false
    }

    // ------------------------------------------------------------- screen state

    private fun lootableScreen(): AbstractContainerScreen<*>? {
        val screen = mc.gui.screen() ?: return null
        return if (acceptsScreen(screen)) screen as AbstractContainerScreen<*> else null
    }

    /**
     * Whether this module would loot [screen], **ignoring** whether it is enabled.
     *
     * <p>Used by the Opai island: it has to know whether replacing the vanilla container with its own
     * panel is safe. Replacing a screen this module refuses to loot would leave the player in front of
     * a hidden chest with no way to interact, so the island asks first.</p>
     */
    fun acceptsScreen(screen: Screen): Boolean {
        if (screen !is AbstractContainerScreen<*>) return false
        if (screen is InventoryScreen) return false
        if (!checkScreenHandlerType.isValid(screen)) return false
        if (!checkScreenTitle.isValid(screen)) return false
        return true
    }

    /** Same shape as [ModuleChestStealer.canBeStolen], so the island can treat both modules alike. */
    fun Screen.canBeStolen(): Boolean =
        this@ModuleMoonlightChestStealer.running && acceptsScreen(this)

    private fun closeContainer() {
        val player = mc.player ?: return
        if (mc.gui.screen() !is AbstractContainerScreen<*>) return
        // Not `mc.execute`: the tick handler already runs on the client thread, and deferring the
        // close used to leave the module reading a screen that was already gone.
        player.closeContainer()
    }

    // ------------------------------------------------------------- tick

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        tickCounter++
        if (pauseWhileUsing && mc.player?.isUsingItem == true) return@handler
        if (pauseInCombat && CombatManager.isInCombat) return@handler

        val screen = lootableScreen()
        if (screen == null) {
            // Closed by the server, replaced by another menu, or the player pressed ESC.
            reportToIsland(null, emptyList())
            resetSession()
            return@handler
        }

        // `syncId` lives on the menu, not on the screen (the screen only owns it in 1.21+, and this
        // client also targets 1.8/1.12.2 where `AbstractContainerScreen` has no such member).
        if (sessionContainerId != screen.menu.containerId) {
            // New container: the slot ids of the previous one mean nothing here.
            handledSlots.clear()
            sessionContainerId = screen.menu.containerId
            openInventorySent = false
            nextActionTick = 0
            islandScreen = null
        }

        if (rotateToChest) aimAtChest()
        // Mount the panel on the very first tick the container is seen, rather than waiting for the
        // first click delay to elapse; otherwise the vanilla chest stays visible for a few ticks.
        if (islandScreen !== screen) reportToIsland(screen, remainingCandidates(screen))
        if (tickCounter < nextActionTick) return@handler

        val plan = runCatching { planClicks(screen) }
            .onFailure {
                if (!announcedFailure) {
                    announcedFailure = true
                    logger.error("Could not plan clicks for '${screen.title.string}'", it)
                }
            }
            .getOrNull() ?: return@handler

        if (plan.isEmpty()) {
            // Nothing left worth taking in this container. Report the finished state first so the
            // island plays its completion ripple instead of snapping shut mid-animation.
            reportToIsland(screen, emptyList())
            if (autoClose) closeContainer()
            resetSession()
            return@handler
        }

        // Mount / refresh the island session with everything still worth taking. The panel only
        // observes: it diffs the menu itself, so this list is purely "what is left to loot".
        reportToIsland(screen, remainingCandidates(screen))

        if (isEqual1_8 && rawOpenInventory && !openInventorySent) {
            // C16 "open inventory", exactly the packet MoonLight sends before its looting routine.
            openInventorySent = Moonlight1_8Packets.sendOpenInventory()
        }

        var performed = 0
        for (slot in plan) {
            if (performed >= clicksPerTick) break
            val click = clickFor(slot)
            if (!performClick(screen, slot, click)) break
            handledSlots += slot
            performed++
        }

        if (performed == 0) {
            // Screen vanished or the server resynced: retry shortly instead of spinning.
            nextActionTick = tickCounter + slotDelay.random().coerceAtLeast(1)
            return@handler
        }

        nextActionTick = tickCounter + slotDelay.random().coerceAtLeast(1)
    }

    // ------------------------------------------------------------- planning

    private fun clickFor(slot: Int): Click = when (mode) {
        Mode.LEGACY_CLICK -> Click(slot, 0, ContainerInput.QUICK_MOVE)
        Mode.PICKUP -> Click(slot, 0, ContainerInput.PICKUP)
        Mode.PICKUP_ALL -> Click(slot, 0, ContainerInput.PICKUP_ALL)
        Mode.THROW -> Click(slot, 1, ContainerInput.THROW)
    }

    private data class Click(val slot: Int, val button: Int, val input: ContainerInput)

    /**
     * Picks the container slots that should be clicked right now, in slot order.
     *
     * <p>Only container-owned slots are considered; the player inventory is the destination. A slot
     * is skipped when it was already handled, when the filter rejects it, or when the stack cannot
     * fit into the inventory at all.</p>
     */
    private fun planClicks(screen: AbstractContainerScreen<*>): List<Int> {
        val player = mc.player ?: return emptyList()
        val containerSlots = screen.menu.slots.filter { it.container !== player.inventory }
        if (containerSlots.isEmpty()) return emptyList()

        val wanted = ArrayList<Int>(clicksPerTick)

        for (slot in containerSlots) {
            if (wanted.size >= clicksPerTick) break
            if (slot.index in handledSlots) continue
            if (!slot.hasItem()) continue

            val stack = slot.item
            if (ignoreFullStacks && stack.count >= stack.maxStackSize) continue
            if (takeOnlyNamed && !stack.has(DataComponents.CUSTOM_NAME)) continue
            if (!matchesFilter(stack.item)) continue

            // Quick-move needs somewhere to put the stack; empty slots count as full capacity.
            if (Slots.HotbarAndInventory.mergeableCapacityFor(stack) <= 0) continue

            wanted += slot.index
        }

        return wanted
    }

    private fun matchesFilter(item: Item): Boolean {
        if (items.isEmpty()) {
            // An empty list is "everything" for a blacklist and "nothing" for a whitelist.
            return filter == Filter.BLACKLIST
        }
        return filter(item, items)
    }

    /**
     * Everything in this container this module would still take, in slot order.
     *
     * <p>Unlike [planClicks] this is not capped by [clicksPerTick]: the Opai panel draws the whole
     * remainder of the chest, not just the next click. Both use the same predicates, so the panel can
     * never show an item the module would refuse to loot.</p>
     */
    private fun remainingCandidates(screen: AbstractContainerScreen<*>): List<Int> {
        val player = mc.player ?: return emptyList()
        val candidates = ArrayList<Int>()
        for (slot in screen.menu.slots) {
            if (slot.container === player.inventory) continue
            if (slot.index in handledSlots) continue
            if (!slot.hasItem()) continue
            val stack = slot.item
            if (ignoreFullStacks && stack.count >= stack.maxStackSize) continue
            if (takeOnlyNamed && !stack.has(DataComponents.CUSTOM_NAME)) continue
            if (!matchesFilter(stack.item)) continue
            if (Slots.HotbarAndInventory.mergeableCapacityFor(stack) <= 0) continue
            candidates += slot.index
        }
        return candidates
    }

    /**
     * Hands the current state to the Opai Dynamic Island, which is a pure observer.
     *
     * <p>An empty [candidates] list while [screen] is not null means "this container is done", which
     * is what makes the panel play its completion ripple and close. A null [screen] means the
     * container went away entirely.</p>
     */
    private fun reportToIsland(screen: AbstractContainerScreen<*>?, candidates: List<Int>) {
        if (!ModuleOpaiDynamicIsland.running) return
        if (screen == null) {
            islandScreen = null
            return
        }
        islandScreen = screen
        runCatching { OpaiChestLoot.onCandidates(screen, candidates) }
            .onFailure { logger.warn("Opai island could not follow the container", it) }
    }

    private fun performClick(screen: AbstractContainerScreen<*>, slot: Int, click: Click): Boolean {
        val player = mc.player ?: return false
        val interaction = mc.gameMode ?: return false
        // Re-validate against the *current* menu: a click for a container the server no longer knows
        // desyncs the window, and some anti-cheats treat that as a combat/inventory exploit.
        val current = mc.gui.screen() as? AbstractContainerScreen<*> ?: return false
        if (current.menu.containerId != screen.menu.containerId) return false
        if (click.slot !in current.menu.slots.indices) return false

        val sent = runCatching {
            interaction.handleContainerInput(current.menu.containerId, click.slot, click.button, click.input, player)
            true
        }.getOrElse {
            logger.warn("Click on slot $slot failed", it)
            false
        }
        if (sent && swingOnClick) {
            // A container click has no arm animation of its own; 1.8 clients swing on the click.
            SwingMode.DO_NOT_HIDE.swing(InteractionHand.MAIN_HAND)
        }
        return sent
    }

    // ------------------------------------------------------------- rotation

    private fun aimAtChest() {
        val player = mc.player ?: return
        val world = mc.level ?: return
        // Throttled: the rotation manager holds the plan for several ticks, and this scan is not free.
        if (tickCounter % ROTATION_REFRESH_TICKS != 0) return

        val eye = player.eyePosition
        val candidates = eye.searchBlocksInRangeSorted(AURA_RANGE) { pos, state ->
            state.block in targetBlocks && !ChestBlock.isChestBlockedAt(world, pos)
        }
        val (pos, state) = candidates.firstOrNull() ?: return
        val rotation = raytraceBlockRotation(eye, pos, state, AURA_RANGE.toDouble(), 0.0) ?: return
        RotationManager.setRotationTarget(
            rotation.rotation,
            considerInventory = true,
            valueGroup = rotations,
            priority = Priority.IMPORTANT_FOR_USAGE_1,
            provider = this,
        )
    }


    private val targetBlocks: Set<net.minecraft.world.level.block.Block> by lazy {
        net.ccbluex.liquidbounce.utils.inventory.findBlocksEndingWith("CHEST", "SHULKER_BOX", "BARREL")
    }

    enum class Mode(override val tag: String) : Tagged {
        LEGACY_CLICK("LegacyClick"),
        PICKUP("Pickup"),
        PICKUP_ALL("PickupAll"),
        THROW("Throw"),
    }

    private const val ROTATION_REFRESH_TICKS = 2
    private const val AURA_RANGE = 4.5f
}
