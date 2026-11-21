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
package net.ccbluex.liquidbounce.features.module.modules.player.invmanager

/*
 * Ported from MoonLight `InvManager` (https://github.com/randomguy3725/MoonLight, GPL-3.0).
 *
 * Kept 1:1 with the original:
 *  - the option set (Drop Items / Sort Items / Auto Armor / Using Item Check / Target Slots),
 *  - the working mode (silently send the open-container request and keep the GUI closed),
 *  - the order of operations (armour, then trash, then sorting), one click per pass,
 *  - the click encodings: hotbar targets use SWAP, armour uses shift-click, trash uses drop.
 *
 * Adapted for this client instead of MoonLight's 1.8 `PlayerController.windowClick`:
 *  - every click goes through `InventoryManager` / `InventoryAction`, so it is rate-limited,
 *    cancelled with the module and translated by ViaFabricPlus for 1.8 servers,
 *  - slots are addressed with `HotbarItemSlot` / `ArmorItemSlot`, which resolve the server slot id
 *    for the currently open menu instead of assuming `36..44`,
 *  - armour is only equipped when this client's `AutoArmor` module is off, so both modules cannot
 *    fight over the same four slots.
 */

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.autoarmor.ModuleAutoArmor
import net.ccbluex.liquidbounce.utils.inventory.ArmorItemSlot
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.InventoryItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.inventory.PlayerInventoryConstraints
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.world.item.ItemStack

/**
 * MoonLight InvManager
 *
 * Sorts the player's own inventory into a fixed hotbar layout, equips the best armour and throws
 * away everything the player has no use for.
 */
object ModuleInvManager : ClientModule(
    "InvManager",
    ModuleCategories.PLAYER,
    aliases = listOf("MoonlightInvManager", "InventorySorter"),
) {

    private val inventoryConstraints = tree(PlayerInventoryConstraints())

    /** MoonLight `Drop Items`. */
    private val dropItems by boolean("DropItems", true)

    /** MoonLight `Sort Items`. */
    private val sortItems by boolean("SortItems", true)

    /** MoonLight `Auto Armor`; skipped while [ModuleAutoArmor] owns the armour slots. */
    private val autoArmor by boolean("AutoArmor", true)

    /** MoonLight `Using Item Check`: never touch the inventory while eating/blocking. */
    private val usingItemCheck by boolean("UsingItemCheck", true)

    /**
     * MoonLight kept eggs, snowballs and fishing rods only when its own AutoProjectile/AutoRod
     * modules were on. This client has neither, so the behaviour is exposed directly.
     */
    private val keepProjectiles by boolean("KeepProjectiles", false)
    private val keepRods by boolean("KeepRods", false)

    /**
     * "Drop the extra", not just the useless.
     *
     * <p>MoonLight's original `Drop Items` only throws away items it considers worthless, so a
     * backpack full of five iron swords and four dirt stacks survived every pass. With this on, the
     * module also drops the *duplicates*: everything beyond the single best sword/bow/tool of each
     * kind, every extra stack of a block it already keeps, and extra gapple stacks.</p>
     */
    private val dropSurplus by boolean("DropSurplus", true)

    /** How many block stacks to keep; 1 means only the biggest stack of each block survives. */
    private val keepBlockStacks by int("KeepBlockStacks", 1, 1..9)

    /** How many gapple/food stacks to keep. */
    private val keepFoodStacks by int("KeepFoodStacks", 1, 1..9)

    private val targets = tree(TargetSlots)

    object TargetSlots : ToggleableValueGroup(ModuleInvManager, "TargetSlots", true) {
        val sword by int("Sword", 0, 0..8)
        val bow by int("Bow", 1, 0..8)
        val gapple by int("Gapple", 2, 0..8)
        val block by int("Block", 3, 0..8)
        val pickaxe by int("Pickaxe", 4, 0..8)
        val axe by int("Axe", 5, 0..8)
        val shovel by int("Shovel", 6, 0..8)
    }

    private val logic: MoonlightInventoryLogic
        get() = MoonlightInventoryLogic(
            MoonlightInventoryLogic.Options(
                dropItems = dropItems,
                dropSurplus = dropSurplus && dropItems,
                keepBlockStacks = keepBlockStacks,
                keepFoodStacks = keepFoodStacks,
                sortItems = sortItems,
                autoArmor = autoArmor && !ModuleAutoArmor.enabled,
                keepProjectiles = keepProjectiles,
                keepRods = keepRods,
                swordTarget = targets.sword,
                bowTarget = targets.bow,
                gappleTarget = targets.gapple,
                blockTarget = targets.block,
                toolTargets = intArrayOf(targets.pickaxe, targets.axe, targets.shovel),
            )
        )

    @Suppress("unused")
    private val scheduleHandler = handler<ScheduleInventoryActionEvent> { event ->
        if (!inGame || player.isSpectator) {
            return@handler
        }
        // MoonLight: `if (usingItemCheck && mc.thePlayer.isUsingItem()) return;`
        if (usingItemCheck && player.isUsingItem) {
            return@handler
        }
        // The container screen owns the slots; a chest click would edit the wrong window.
        if (!InventoryManager.isInventoryOpen && InventoryManager.isHandledScreenOpen) {
            return@handler
        }

        val slots = readSlots()
        val scan = logic.scan(slots)
        if (scan.isEmpty()) {
            return@handler
        }

        // MoonLight's exact order: equip armour, drop one trash item, then sort one item. Only the
        // first applicable step is scheduled, so one pass can never queue two conflicting clicks on
        // the same slot.
        val armorMove = logic.armorMoves(scan, slots).firstOrNull()
        if (armorMove != null) {
            schedule(event, armorMove, Priority.IMPORTANT_FOR_PLAYER_LIFE)
            return@handler
        }

        // Real trash first; only once nothing useless is left does the surplus pass start, so the
        // player never loses a spare sword while a rotten-flesh stack is still in the way.
        val trashSlot = scan.trash.firstOrNull()
        if (trashSlot != null && dropItems) {
            schedule(event, MoonlightMove.Drop(trashSlot), Priority.NOT_IMPORTANT)
            return@handler
        }

        val surplusSlot = scan.surplus.firstOrNull()
        if (surplusSlot != null && dropItems && dropSurplus) {
            schedule(event, MoonlightMove.Drop(surplusSlot), Priority.NOT_IMPORTANT)
            return@handler
        }

        val sortMove = logic.plan(scan, slotSizes = { slots.getOrElse(it) { ItemStack.EMPTY }.count })
            .firstOrNull()
        if (sortMove != null) {
            schedule(event, sortMove, Priority.NORMAL)
        }
    }

    private fun schedule(event: ScheduleInventoryActionEvent, move: MoonlightMove, priority: Priority) {
        event.schedule(inventoryConstraints, toAction(move), priority)
    }

    private fun toAction(move: MoonlightMove): InventoryAction = when (move) {
        is MoonlightMove.SwapToHotbar -> {
            val target = HotbarItemSlot.entries.first { it.hotbarIndex == move.hotbarIndex }
            InventoryAction.Click.performSwap(screen = null, from = inventorySlot(move.from), to = target)
        }
        is MoonlightMove.QuickMove -> InventoryAction.Click.performQuickMove(
            screen = null,
            slot = inventorySlot(move.slot),
        )
        is MoonlightMove.Drop -> InventoryAction.Click.performThrow(
            screen = null,
            slot = inventorySlot(move.slot),
        )
    }

    /**
     * MoonLight addresses window slots directly (9..35 inventory, 36..44 hotbar). This client uses
     * slot objects, so the same window slot id is mapped back onto its `ItemSlot`.
     */
    private fun inventorySlot(windowSlot: Int) = when {
        windowSlot >= MoonlightInventoryLogic.HOTBAR_SLOT_BEGIN -> HotbarItemSlot.entries
            .first { it.hotbarIndex == windowSlot - MoonlightInventoryLogic.HOTBAR_SLOT_BEGIN }
        windowSlot >= MoonlightInventoryLogic.ARMOR_SLOT_BEGIN &&
            windowSlot < MoonlightInventoryLogic.FIRST_INVENTORY_ROW_SLOT -> armorSlot(windowSlot)
        else -> InventoryItemSlot(windowSlot - MoonlightInventoryLogic.FIRST_INVENTORY_ROW_SLOT)
    }

    /** Armour slots 5..8 in `EquipmentSlot.index` order, which is the enum order of [ArmorItemSlot]. */
    private fun armorSlot(windowSlot: Int): ArmorItemSlot {
        val type = windowSlot - MoonlightInventoryLogic.ARMOR_SLOT_BEGIN
        return ArmorItemSlot.entries.first { it.equipmentSlot.index == type }
    }

    /**
     * Reads the player's own container as MoonLight's slot array: index 5..44.
     *
     * <p>The player inventory menu is used directly instead of `player.containerMenu`, so the scan
     * still works while a chest is open on a modern server (the module only acts when it is closed).</p>
     */
    private fun readSlots(): Array<ItemStack> {
        val slots = Array(MoonlightInventoryLogic.LAST_SLOT_EXCLUSIVE) { ItemStack.EMPTY }
        val menu = player.inventoryMenu
        val container = menu.slots
        val windowSlots = MoonlightInventoryLogic.LAST_SLOT_EXCLUSIVE - MoonlightInventoryLogic.ARMOR_SLOT_BEGIN
        for (index in 0 until windowSlots) {
            val slot = container.getOrNull(MoonlightInventoryLogic.ARMOR_SLOT_BEGIN + index) ?: continue
            slots[MoonlightInventoryLogic.ARMOR_SLOT_BEGIN + index] = slot.item
        }
        return slots
    }

    /** Number of trash stacks currently known, for `/invmanager` style diagnostics. */
    fun describe(): String {
        val scan = logic.scan(readSlots())
        return "sword=${scan.bestSword} bow=${scan.bestBow} trash=${scan.trash.size} " +
            "surplus=${scan.surplus.size} blocks=${scan.blocks.size} gapples=${scan.gapples.size}"
    }

}
