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
import net.ccbluex.fastutil.swap
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.player.cheststealer.features.FeatureChestAura
import net.ccbluex.liquidbounce.features.module.modules.player.cheststealer.features.FeatureSilentScreen
import net.ccbluex.liquidbounce.features.module.modules.render.OpaiChestLoot
import net.ccbluex.liquidbounce.features.module.modules.player.invcleaner.CleanupPlanGenerator
import net.ccbluex.liquidbounce.features.module.modules.player.invcleaner.InventoryCleanupPlan
import net.ccbluex.liquidbounce.features.module.modules.player.invcleaner.ItemCategorization
import net.ccbluex.liquidbounce.features.module.modules.player.invcleaner.ModuleInventoryCleaner
import net.ccbluex.liquidbounce.utils.inventory.CheckScreenHandlerTypeValueGroup
import net.ccbluex.liquidbounce.utils.inventory.CheckScreenTitleValueGroup
import net.ccbluex.liquidbounce.utils.inventory.ContainerItemSlot
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.InventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.ItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.findItemsInContainer
import net.ccbluex.liquidbounce.utils.inventory.mergeableCapacityFor
import net.ccbluex.liquidbounce.utils.inventory.findNonEmptySlotsInInventory
import net.ccbluex.liquidbounce.utils.item.isMergeable
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.item.ItemStack
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.ceil

/**
 * ChestStealer module
 *
 * Automatically steals all items from a chest.
 */

object ModuleChestStealer : ClientModule("ChestStealer", ModuleCategories.PLAYER) {

    private val inventoryConstrains = tree(InventoryConstraints())
    private val autoClose by boolean("AutoClose", true)

    /**
     * Ticks to wait for the container's contents before acting on an apparently empty chest.
     *
     * This is the fix for "ChestAura opens a chest and sometimes nothing gets stolen".
     *
     * <p>A container screen exists on the client *before* the server's contents are applied to its
     * menu: `setScreen` happens on the open packet, the slots are filled by the following
     * `ClientboundContainerSetContentPacket`. On the tick in between, every slot reads empty.
     * `itemsToCollect` is therefore empty, and with `AutoClose` on the module immediately closed the
     * chest and left everything in it — a pure race, which is why it only happened *sometimes*, and
     * why opening the chest by hand usually worked (a hand opens the screen the same way, but the
     * player spends a moment looking at it before the stealer ticks again).</p>
     *
     * <p>So: never conclude "nothing to take" until either we have seen this container holding at least
     * one item, or this many ticks have passed since the screen appeared.</p>
     */
    private val emptyGrace by int("EmptyGrace", 10, 0..40, "ticks")

    /**
     * Re-check the container for a few ticks after it looked finished.
     *
     * Servers resync slots and other players move items; a plan built one tick after the last click can
     * miss an item that was still in flight. Closing on the first empty plan is what made chests end up
     * half looted.
     */
    private val settleTicks by int("SettleTicks", 3, 0..20, "ticks")

    private val selectionMode = choices("SelectionMode", Distance, arrayOf(Distance, Index, Random)).apply(::tagBy)
    private val itemMoveMode by enumChoice("MoveMode", ItemMoveMode.QUICK_MOVE)
    private val quickSwaps by boolean("QuickSwaps", true)

    private val onFull by enumChoice("OnFull", OnFull.THROW)

    private enum class OnFull(override val tag: String) : Tagged {
        NONE("None"),
        THROW("Throw"),
//        PUT_BACK("PutBack"), TODO: Fix this
    }

    private val checkScreenHandlerType = tree(CheckScreenHandlerTypeValueGroup(this))
    private val checkScreenTitle = tree(CheckScreenTitleValueGroup(this))

    init {
        tree(FeatureChestAura)
        tree(FeatureSilentScreen)
    }

    @Suppress("unused")
    private val scheduleInventoryAction = handler<ScheduleInventoryActionEvent> { event ->
        // A container that changes while we plan (another player takes an item, the server
        // resyncs a slot, the chest is closed) must never take down the tick loop.
        try {
            processContainer(event)
        } catch (throwable: Throwable) {
            logger.error("ChestStealer failed to plan the next container action", throwable)
        }
    }

    private fun processContainer(event: ScheduleInventoryActionEvent) {
        // Check if we are in a chest screen
        val screen = getChestScreen() ?: return

        val cleanupPlan = createCleanupPlan(screen)
        val itemsToCollect = cleanupPlan.usefulItems.filterIsInstanceTo(ArrayList<ContainerItemSlot>())

        // Start the render-only Chest session before QuickSwaps can return early. Otherwise a
        // hotbar setup action delays the panel until ChestStealer reaches its normal pickup path.
        OpaiChestLoot.onCandidates(screen, itemsToCollect.map { it.slotInContainer })

        // Quick swap items in hotbar (i.e. swords), some servers hate them
        if (quickSwaps && performQuickSwaps(event, cleanupPlan, screen)) {
            return
        }

        val stillRequiredSpace = getStillRequiredSpace(cleanupPlan, itemsToCollect.size)
        selectionMode.activeMode.process(itemsToCollect)
        // Refresh the observer with the final pickup order; it remains read-only.
        OpaiChestLoot.onCandidates(screen, itemsToCollect.map { it.slotInContainer })

        val targetBlacklist = ObjectOpenHashSet<ItemSlot>()
        var scheduled = false

        for (slot in itemsToCollect) {
            // The plan was built earlier in this tick; the slot can already be empty by now
            // (server resync, another player, a partial quick-move). Empty stacks have no facets,
            // which used to end in `maxOf` on an empty list.
            if (slot.itemStack.isEmpty) {
                continue
            }

            val moveActions = Slots.HotbarAndInventory.findPossiblePickActions(screen, slot, targetBlacklist)

            if (moveActions != null) {
                scheduled = true
                event.schedule(
                    inventoryConstrains, moveActions,
                    /**
                     * we prioritize item based on how important it is
                     * for example we should prioritize armor over apples
                     */
                    ItemCategorization.Default.getItemFacets(slot)
                        .maxOfOrNull { it.category.type.allocationPriority } ?: Priority.NORMAL
                )
            } else if (stillRequiredSpace > 0) {
                // Throw useless items
                val throwAction = throwItem(cleanupPlan, screen, targetBlacklist) ?: break
                scheduled = true
                event.schedule(inventoryConstrains, throwAction)
            }
        }

        /*
         * A container can legitimately look empty and still be full.
         *
         * The screen is created by the open packet, but its slots are filled by the *following*
         * container-set-content packet. Between those two the menu reports every slot as empty, so on
         * that one tick `itemsToCollect` is empty and the old code closed the chest immediately
         * (AutoClose) and never touched it again. Servers that answer the open one tick late, or
         * servers that resend the content after a plugin delay, therefore looked exactly like
         * "ChestAura opened it but the stealer did nothing" — intermittently, which is why a manual
         * open appeared to work.
         *
         * Same story at the other end: the last click's answer can still be in flight when the plan is
         * rebuilt, so a plan that comes back empty right after a click does not mean the chest is
         * done. Both are races, so both are given a tick budget instead of being trusted on the first
         * pass.
         */
        val containerSeenWithItems = seenContainerWithItems(screen, itemsToCollect.isNotEmpty())
        val hasAnything = itemsToCollect.isNotEmpty() || scheduled

        /*
         * Nothing worth taking (and nothing scheduled): decide whether we are allowed to finish yet.
         *
         * The three counters are independent on purpose, because "finished" is sticky: once a
         * container has settled and been closed, the module must not re-open it or wait again. That is
         * why the result is cached per menu in [finishedMenus] instead of being recomputed from
         * timers that reset.
         */
        val menuId = System.identityHashCode(screen.menu)
        val alreadyFinished = finishedMenus.contains(menuId)

        val mayFinish: Boolean
        if (hasAnything) {
            // Real work exists: this container is not finished, and anything we concluded is void.
            emptyWait = 0
            settleWait = 0
            finishedMenus.remove(menuId)
            mayFinish = false
        } else if (alreadyFinished) {
            /*
             * Already emptied and closed once. Do NOT run the grace timers again: without this the
             * container oscillated between "wait for content" and "closed", so the close packet was
             * emitted every few ticks and the chest never actually went away.
             */
            mayFinish = true
        } else if (!containerSeenWithItems && emptyWait < emptyGrace) {
            // The contents have not arrived yet; never close a chest we have not actually read.
            emptyWait++
            mayFinish = false
        } else if (settleWait < settleTicks) {
            // Contents were read and look done, but wait for the server to settle: a click answer can
            // still be in flight. This is what stops a chest being abandoned half looted.
            settleWait++
            mayFinish = false
        } else {
            mayFinish = true
        }

        if (mayFinish) {
            // Sticky per menu. Without this, closing and re-entering the same plan would loop: the
            // counters reset on every pass and an empty chest would be waited on forever instead of
            // closed once.
            finishedMenus.add(menuId)
            if (finishedMenus.size > 64) {
                // Bounded: the identities only matter while the container is on screen.
                finishedMenus.clear()
            }
            emptyWait = 0
            settleWait = 0
        }

        // With the replacement screen there is no manual inventory UI. Finish and restore the
        // island when nothing can be scheduled (e.g. the inventory is full), rather than trapping
        // the player in a hidden container. Outside replacement mode keep AutoClose unchanged.
        val replacementFinished = mayFinish && OpaiChestLoot.closeWhenFinished(screen)
        if (replacementFinished && itemsToCollect.isNotEmpty()) {
            OpaiChestLoot.onCandidates(screen, emptyList())
        }
        if ((autoClose && mayFinish) || replacementFinished) {
            event.schedule(inventoryConstrains, InventoryAction.CloseScreen(screen))
        }
    }

    /**
     * Tracks whether this container has ever been observed holding an item.
     *
     * Identity is the menu, so moving to another chest (a different menu instance) starts over. This is
     * what lets [emptyGrace] be a *one-time* wait per chest rather than a permanent delay on genuinely
     * empty ones: an empty chest we already read is finished immediately on the next tick.
     */
    private var graceMenu: net.minecraft.world.inventory.AbstractContainerMenu? = null

    /**
     * Identity hashes of menus already emptied and closed.
     *
     * Using the JVM identity hash instead of holding the menu itself keeps this from pinning a closed
     * container (and from growing without bound): a recycled identity simply causes one extra settle
     * pass, never a leak. `containerId` is per-session but reused, so identity is the safer key.
     */
    private val finishedMenus: MutableSet<Int> = HashSet()

    private var emptyWait = 0
    private var settleWait = 0
    private var sawItemsInThisContainer = false

    private fun seenContainerWithItems(
        screen: AbstractContainerScreen<*>,
        hasItems: Boolean,
    ): Boolean {
        if (graceMenu !== screen.menu) {
            graceMenu = screen.menu
            emptyWait = 0
            settleWait = 0
            sawItemsInThisContainer = false
        }
        if (hasItems) {
            sawItemsInThisContainer = true
        }
        return sawItemsInThisContainer
    }

    /**
     * Gets the clicks from mergeable or empty slots, or null if impossible to pick
     */
    @Suppress("CognitiveComplexMethod")
    private fun Iterable<ItemSlot>.findPossiblePickActions(
        screen: AbstractContainerScreen<*>,
        from: ItemSlot,
        targetBlacklist: MutableSet<ItemSlot>? = null,
    ): List<InventoryAction.Click>? {
        val fromStack = from.itemStack

        // Nothing to move, and an empty stack reports a capacity of 1 per free slot, so without
        // this guard we would schedule a quick-move on an empty slot.
        if (fromStack.isEmpty) {
            targetBlacklist?.add(from)
            return null
        }

        val remaining = mergeableCapacityFor(fromStack, blacklist = targetBlacklist)

        // Impossible to pick any item into inventory
        if (remaining == 0) return null

        targetBlacklist?.add(from)
        return when (itemMoveMode) {
            ItemMoveMode.QUICK_MOVE -> listOf(InventoryAction.Click.performQuickMove(screen, from))

            ItemMoveMode.DRAG_AND_DROP -> {
                // Never empty
                val targets = filterTo(ArrayDeque()) {
                    (targetBlacklist == null || it !in targetBlacklist) &&
                        (it.itemStack.isEmpty || it.itemStack.isMergeable(fromStack))
                }

                /* The remaining count after merged with [fromStack]. Negative -> fromStack has remaining */
                fun mergedRemaining(target: ItemStack) = fromStack.maxStackSize - fromStack.count - target.count

                buildList {
                    // Pick up
                    this += InventoryAction.Click.performPickup(screen, from)

                    val possibleSinglePut = targets.firstOrNull { mergedRemaining(it.itemStack) >= 0 }
                    if (possibleSinglePut != null) {
                        this += InventoryAction.Click.performPickup(screen, possibleSinglePut)
                        targetBlacklist?.add(possibleSinglePut)
                    } else {
                        // Now all `mergedRemaining` result of [targets] are negative
                        // Minimize click count
                        targets.sortBy { mergedRemaining(it.itemStack) }
                        var count = fromStack.count
                        while (count >= 0) {
                            val target = targets.removeFirstOrNull() ?: break
                            count += mergedRemaining(target.itemStack)
                            this += InventoryAction.Click.performPickup(screen, target)
                            targetBlacklist?.add(target)
                        }
                    }

                    if (remaining < fromStack.count) {
                        // Unable to take all, put remaining items back
                        this += InventoryAction.Click.performPickup(screen, from)
                    }
                }
            }
        }
    }

    /**
     * @return if we should wait
     */
    private fun throwItem(
        cleanupPlan: InventoryCleanupPlan,
        screen: AbstractContainerScreen<*>,
        targetBlacklist: MutableSet<ItemSlot>,
    ): List<InventoryAction>? {
        val itemsInInv = findNonEmptySlotsInInventory()
        val itemToThrowOut = cleanupPlan.findItemsToThrowOut(itemsInInv)
            .firstOrNull { it.getIdForServer(screen) != null } ?: return null

        return when (onFull) {
            OnFull.NONE -> null
//            OnFull.PUT_BACK -> screen.getSlotsInContainer()
//                .findPossiblePickActions(screen, itemToThrowOut, targetBlacklist)
            OnFull.THROW -> {
                targetBlacklist.add(itemToThrowOut)
                listOf(InventoryAction.Click.performThrow(screen, itemToThrowOut))
            }
        }
    }

    /**
     * @param slotsToCollect amount of items we need to take
     */
    private fun getStillRequiredSpace(
        cleanupPlan: InventoryCleanupPlan,
        slotsToCollect: Int,
    ): Int {
        val freeSlotsInInv = Slots.HotbarAndInventory.count { it.itemStack.isEmpty }

        val spaceGainedThroughMerge = cleanupPlan.mergeableItems.entries.sumOf { (id, slots) ->
            val slotsInChest = slots.count { it.slotType == ItemSlot.Type.CONTAINER }
            val totalCount = slots.sumOf { it.itemStack.count }

            val mergedStackCount = ceil(totalCount.toDouble() / id.item.defaultMaxStackSize.toDouble()).toInt()

            (slots.size - mergedStackCount).coerceAtMost(slotsInChest)
        }

        return (slotsToCollect - freeSlotsInInv - spaceGainedThroughMerge).coerceAtLeast(0)
    }

    /**
     * @return true if a quick swap transaction was scheduled and the plan should be regenerated next tick
     */
    @Suppress("CognitiveComplexMethod")
    private fun performQuickSwaps(
        event: ScheduleInventoryActionEvent,
        cleanupPlan: InventoryCleanupPlan,
        screen: AbstractContainerScreen<*>
    ): Boolean {
        cleanupPlan.swaps.forEach { hotbarSwap ->
            // We only care about swaps from the chest to the hotbar
            if (hotbarSwap.from.slotType != ItemSlot.Type.CONTAINER) {
                return@forEach
            }

            val hotbarSlot = hotbarSwap.to as? HotbarItemSlot ?: return@forEach
            if (!hotbarSlot.canBeSwapTarget) {
                return@forEach
            }

            val actions = when {
                // Target slot is empty, swap
                hotbarSlot.itemStack.isEmpty -> listOf(
                    InventoryAction.Click.performSwap(screen, hotbarSwap.from, hotbarSlot)
                )

                // Target slot item is useful, swap it with another empty slot
                hotbarSlot in cleanupPlan.usefulItems ->
                    Slots.Inventory.firstOrNull { it.itemStack.isEmpty }?.let { emptyInventorySlot ->
                        listOf(
                            InventoryAction.Click.performSwap(screen, emptyInventorySlot, hotbarSlot),
                            InventoryAction.Click.performSwap(screen, hotbarSwap.from, hotbarSlot),
                        )
                    } ?: return@forEach

                // Target slot item is useless, throw and swap
                else -> if (hotbarSlot.isOffHand) {
                    // Throwing offhand item inside container looks not legit
                    listOf(InventoryAction.Click.performSwap(screen, hotbarSwap.from, hotbarSlot))
                } else {
                    listOf(
                        InventoryAction.Click.performThrow(screen, hotbarSlot),
                        InventoryAction.Click.performSwap(screen, hotbarSwap.from, hotbarSlot),
                    )
                }
            }

            if (actions.any { it.slot.getIdForServer(screen) == null }) {
                return@forEach
            }

            event.schedule(
                inventoryConstrains,
                actions,
                /**
                 * we prioritize item based on how important it is
                 * for example we should prioritize armor over apples
                 */
                hotbarSwap.priority
            )

            return true
        }

        return false
    }

    /**
     * Either asks [ModuleInventoryCleaner] what to do or just takes everything.
     */
    private fun createCleanupPlan(screen: AbstractContainerScreen<*>): InventoryCleanupPlan {
        val cleanupPlan = if (!ModuleInventoryCleaner.running) {
            val usefulItems = screen.findItemsInContainer()

            InventoryCleanupPlan(ObjectOpenHashSet(usefulItems), mutableListOf(), hashMapOf())
        } else {
            val availableItems = findNonEmptySlotsInInventory() + screen.findItemsInContainer()

            CleanupPlanGenerator(ModuleInventoryCleaner.cleanupTemplateFromSettings, availableItems).generatePlan()
        }

        return cleanupPlan
    }

    private sealed class SelectionMode(name: String) : Mode(name) {
        final override val parent: ModeValueGroup<*>
            get() = selectionMode

        abstract fun process(slots: ArrayList<ContainerItemSlot>)
    }

    private object Distance : SelectionMode("Distance") {
        private val startItem by enumChoice("StartItem", StartItem.DEFAULT)
        private val randomFactor by floatRange("RandomFactor", 1.0f..1.0f, 0.25f..2f)

        override fun process(slots: ArrayList<ContainerItemSlot>) {
            val n = slots.size
            if (n <= 1) return

            val startIndex = startItem.getStartIndex(slots)
            if (startIndex != 0) {
                slots.swap(0, startIndex)
            }

            if (n <= 2) return

            for (i in 0..<n - 1) {
                var bestIdx = i + 1
                var bestDist = Float.POSITIVE_INFINITY

                val current = slots[i]

                for (j in i + 1..<n) {
                    val candidate = slots[j]
                    val distance = current.distance(candidate).toFloat()
                    val factor = if (randomFactor.isEmpty()) 1F else randomFactor.random()
                    val effectiveDistance = distance * factor

                    if (effectiveDistance < bestDist) {
                        bestDist = effectiveDistance
                        bestIdx = j
                    }
                }

                slots.swap(i + 1, bestIdx)
            }
        }

        private enum class StartItem(override val tag: String) : Tagged {
            DEFAULT("Default") {
                override fun getStartIndex(slots: List<ContainerItemSlot>): Int = 0
            },
            RANDOM("Random") {
                override fun getStartIndex(slots: List<ContainerItemSlot>): Int {
                    return ThreadLocalRandom.current().nextInt(slots.size)
                }
            },
            MAX_SLOT("MaxSlot") {
                override fun getStartIndex(slots: List<ContainerItemSlot>): Int {
                    return slots.indices.maxBy { slots[it].slotInContainer }
                }
            },
            MIN_SLOT("MinSlot") {
                override fun getStartIndex(slots: List<ContainerItemSlot>): Int {
                    return slots.indices.minBy { slots[it].slotInContainer }
                }
            };

            abstract fun getStartIndex(slots: List<ContainerItemSlot>): Int
        }
    }

    private object Index : SelectionMode("Index") {
        private val order by enumChoice("Order", Order.ASCENDING)

        override fun process(slots: ArrayList<ContainerItemSlot>) {
            slots.sortWith(order)
        }

        private enum class Order(override val tag: String) : Tagged, Comparator<ContainerItemSlot> {
            ASCENDING("Ascending") {
                override fun compare(o1: ContainerItemSlot, o2: ContainerItemSlot): Int {
                    return o1.slotInContainer.compareTo(o2.slotInContainer)
                }
            },
            DESCENDING("Descending") {
                override fun compare(o1: ContainerItemSlot, o2: ContainerItemSlot): Int {
                    return o2.slotInContainer.compareTo(o1.slotInContainer)
                }
            },
        }
    }

    private object Random : SelectionMode("Random") {
        override fun process(slots: ArrayList<ContainerItemSlot>) = slots.shuffle()
    }

    /**
     * @return the chest screen if it is open and the title matches the chest title
     */
    private fun getChestScreen(): AbstractContainerScreen<*>? {
        return mc.gui.screen()?.takeIf { it.canBeStolen() } as AbstractContainerScreen<*>?
    }

    fun Screen.canBeStolen(): Boolean {
        // `running` is the module's own property; inside this extension it previously resolved against
        // the receiver (Screen) and did not compile.
        return this@ModuleChestStealer.running && acceptsScreen(this)
    }

    /**
     * Whether this module would loot [screen], **ignoring** whether it is currently enabled.
     *
     * <p>Split out of [canBeStolen] so the Opai island can ask "would this stealer handle this
     * container?" for either stealer module without duplicating the handler-type/title rules and the
     * inventory exclusion. The `running` check stays in the callers that need it.</p>
     */
    fun acceptsScreen(screen: Screen): Boolean =
        screen is AbstractContainerScreen<*> &&
            screen !is InventoryScreen &&
            checkScreenHandlerType.isValid(screen) && checkScreenTitle.isValid(screen)

    private enum class ItemMoveMode(override val tag: String) : Tagged {
        QUICK_MOVE("QuickMove"),
        DRAG_AND_DROP("DragAndDrop"),
    }

}
