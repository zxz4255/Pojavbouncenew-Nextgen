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
 * Ported from MoonLight `utils/player/InventoryUtils` + `modules/impl/player/InvManager`
 * (https://github.com/randomguy3725/MoonLight, GPL-3.0). Only the decision logic lives here: which
 * item is the best of its kind, what is trash, and which slot each item should end up in. All slot
 * access is done through plain `ItemStack` arrays so the logic is testable without a client.
 *
 * Deliberate differences from the 1.8 original:
 *  - 1.8 `ItemSword`/`ItemTool`/`ItemArmor` classes no longer exist; item tags and data components
 *    are used instead (`ItemTags.SWORDS`, `DataComponents.EQUIPPABLE`, `DataComponents.TOOL`).
 *  - 1.8 attack damage came from the attribute map; here `ATTACK_DAMAGE` on the stack is used.
 *  - Potions are checked through `DataComponents.POTION_CONTENTS` instead of `ItemPotion`.
 *  - MoonLight's `AutoProjectile`/`AutoRod` conditions became options, because this client has no
 *    equivalent modules to ask.
 */

import net.ccbluex.liquidbounce.utils.item.armorValue
import net.ccbluex.liquidbounce.utils.item.armorToughness
import net.ccbluex.liquidbounce.utils.item.equipmentSlot
import net.ccbluex.liquidbounce.utils.item.foodComponent
import net.ccbluex.liquidbounce.utils.item.getAttributeValue
import net.ccbluex.liquidbounce.utils.item.getEnchantment
import net.ccbluex.liquidbounce.utils.item.getPotionEffects
import net.ccbluex.liquidbounce.utils.item.isAxe
import net.ccbluex.liquidbounce.utils.item.isHoe
import net.ccbluex.liquidbounce.utils.item.isPickaxe
import net.ccbluex.liquidbounce.utils.item.isPlayerArmor
import net.ccbluex.liquidbounce.utils.item.isShovel
import net.ccbluex.liquidbounce.utils.item.isSword
import net.ccbluex.liquidbounce.utils.item.toolComponent
import net.minecraft.core.BlockPos
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.EggItem
import net.minecraft.world.item.EnderpearlItem
import net.minecraft.world.item.FishingRodItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.SnowballItem
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.state.BlockState

/** Result of the scanner: the best slot per hotbar target, what is trash and what may be dropped. */
data class MoonlightScan(
    val bestSword: Int = -1,
    val bestBow: Int = -1,
    /** index 0 = pickaxe, 1 = axe, 2 = shovel, -1 = none */
    val bestTools: IntArray = IntArray(TOOL_TYPES) { -1 },
    /** index follows [EquipmentSlot.index]: 0 feet, 1 legs, 2 chest, 3 head */
    val bestArmor: IntArray = IntArray(ARMOR_TYPES) { -1 },
    val gapples: List<Int> = emptyList(),
    val blocks: List<Int> = emptyList(),
    val trash: List<Int> = emptyList(),
    /**
     * Usable-but-redundant slots: duplicates of a kind the player already has the best of, extra
     * stacks of the same block, a second sword, and so on. Only filled when
     * [MoonlightInventoryLogic.Options.dropSurplus] is on; these are dropped *after* the pure trash,
     * so a bad stack can never displace something the player still needs.
     */
    val surplus: List<Int> = emptyList(),
) {
    fun isEmpty(): Boolean =
        bestSword == -1 && bestBow == -1 && bestTools.all { it == -1 } && bestArmor.all { it == -1 } &&
            gapples.isEmpty() && blocks.isEmpty() && trash.isEmpty() && surplus.isEmpty()

    companion object {
        const val TOOL_TYPES = 3
        const val ARMOR_TYPES = 4

        /** 1.8 tool type order, also the order of the tool hotbar targets. */
        const val TOOL_PICKAXE = 0
        const val TOOL_AXE = 1
        const val TOOL_SHOVEL = 2
    }
}

/** One planned action, already resolved to MoonLight's window-click form. */
sealed interface MoonlightMove {
    /** Hotbar swap (`WindowClick` mode 2 with button = target hotbar index). */
    data class SwapToHotbar(val from: Int, val hotbarIndex: Int) : MoonlightMove

    /** Shift-left click on the given slot (move a piece into its armour slot). */
    data class QuickMove(val slot: Int) : MoonlightMove

    /** Drop one item from the given slot. */
    data class Drop(val slot: Int) : MoonlightMove
}

/**
 * Pure decision logic of MoonLight's InvManager.
 *
 * The scan order is identical to the original: one pass over slots 5..44, with `if/else if`
 * chaining, so an item only ever counts for the first category it matches. `bestSwordSlot` is
 * reassigned as the loop goes, which is why the *last* best sword wins in the original and here.
 */
class MoonlightInventoryLogic(private val options: Options) {

    data class Options(
        val dropItems: Boolean = true,
        val sortItems: Boolean = true,
        val autoArmor: Boolean = true,
        /** MoonLight kept egg/snowball when `AutoProjectile` was on. */
        val keepProjectiles: Boolean = false,
        /** MoonLight kept fishing rods when `AutoRod` was on. */
        val keepRods: Boolean = false,
        /**
         * Drop duplicates of things the player already has a best copy of: extra swords/bows/tools/
         * armour of the same kind, extra block stacks beyond [keepBlockStacks], extra gapple stacks
         * beyond [keepFoodStacks].
         */
        val dropSurplus: Boolean = true,
        /** How many block stacks to keep (1 = only the biggest stack survives). */
        val keepBlockStacks: Int = 1,
        /** How many gapple/food stacks to keep. */
        val keepFoodStacks: Int = 1,
        /** Target hotbar slot of the best sword, -1 disables sorting swords. */
        val swordTarget: Int = 0,
        val bowTarget: Int = 1,
        val gappleTarget: Int = 2,
        val blockTarget: Int = 3,
        val toolTargets: IntArray = intArrayOf(4, 5, 6),
    )

    /**
     * @param slots container slots 5..44 (armour 5..8, main inventory 9..35, hotbar 36..44), so the
     *              index is the MoonLight slot id.
     */
    fun scan(slots: Array<ItemStack>): MoonlightScan {
        var bestSword = -1
        var bestBow = -1
        val bestTools = IntArray(MoonlightScan.TOOL_TYPES) { -1 }
        val bestArmor = IntArray(MoonlightScan.ARMOR_TYPES) { -1 }
        val gapples = ArrayList<Int>()
        val blocks = ArrayList<Int>()
        val trash = ArrayList<Int>()

        val bestBlockSlot = bestBlockStack(slots)

        for (slot in FIRST_SLOT until LAST_SLOT_EXCLUSIVE) {
            val stack = slots.getOrElse(slot) { ItemStack.EMPTY }
            if (stack.isEmpty) continue

            when {
                stack.isSwordForScoring() -> {
                    if (isBestSword(slots, stack)) bestSword = slot
                }
                stack.item is BowItem -> {
                    if (isBestBow(slots, stack)) bestBow = slot
                }
                stack.isMiningTool() -> {
                    if (isBestTool(slots, stack)) {
                        val type = toolType(stack)
                        if (type != -1) bestTools[type] = slot
                    }
                }
                stack.isPlayerArmor -> {
                    if (isBestArmor(slots, stack)) {
                        val type = armorIndex(stack)
                        if (type != -1) bestArmor[type] = slot
                    }
                }
                stack.item is BlockItem -> {
                    // MoonLight adds every block slot whose stack is the best one; in practice the
                    // stack-size comparison makes that a single slot.
                    if (slot == bestBlockSlot) blocks.add(slot)
                }
                isGapple(stack) -> gapples.add(slot)
                !isValidStack(stack) -> if (options.dropItems) trash.add(slot)
            }
        }

        val surplus = if (options.dropSurplus) {
            findSurplus(slots, bestSword, bestBow, bestTools, bestArmor, gapples, blocks, trash)
        } else {
            emptyList()
        }

        return MoonlightScan(bestSword, bestBow, bestTools, bestArmor, gapples, blocks, trash, surplus)
    }

    /**
     * Which *usable* stacks are nevertheless redundant.
     *
     * <p>MoonLight only ever dropped items it considered useless, so five iron swords and four dirt
     * stacks in the backpack survived every pass and the player had to clean up by hand. This is the
     * "drop the extra" pass: it keeps the best copy of each kind — the exact stack the sorter already
     * picked — plus a configurable number of stacks per block and per food item.</p>
     *
     * <p>Grouping is per item type for blocks and food, so "two dirt and two cobblestone" does not
     * collapse into "one of them". For swords, bows, tools and armour the rule is the MoonLight one:
     * exactly one keeper, the best of that kind, everything else is spare.</p>
     *
     * <p>Precedence is fixed and keeps-then-drops, never the other way round, so this can never
     * remove the stack the sort step is about to move into the hotbar.</p>
     */
    private fun findSurplus(
        slots: Array<ItemStack>,
        bestSword: Int,
        bestBow: Int,
        bestTools: IntArray,
        bestArmor: IntArray,
        gapples: List<Int>,
        blocks: List<Int>,
        trash: List<Int>,
    ): List<Int> {
        val keep = HashSet<Int>(32)
        if (bestSword != -1) keep.add(bestSword)
        if (bestBow != -1) keep.add(bestBow)
        bestTools.forEach { if (it != -1) keep.add(it) }
        bestArmor.forEach { if (it != -1) keep.add(it) }

        // Blocks and food are kept per item type, biggest stack first: a 42-stack is worth more than
        // a 5-stack of the same block, and a different block is a different tool for the player.
        keepBiggestPerItem(slots, keep, options.keepBlockStacks) { it.item is BlockItem }
        keepBiggestPerItem(slots, keep, options.keepFoodStacks) { isGapple(it) }

        val surplus = ArrayList<Int>()
        for (slot in FIRST_INVENTORY_ROW_SLOT until LAST_SLOT_EXCLUSIVE) {
            if (slot in keep || slot in trash) continue
            val stack = slots.getOrElse(slot) { ItemStack.EMPTY }
            if (stack.isEmpty) continue
            if (isRedundant(stack, keep, slots)) surplus.add(slot)
        }
        return surplus
    }

    /** Adds the [limit] biggest stacks of every distinct item that matches [selector] to [keep]. */
    private fun keepBiggestPerItem(
        slots: Array<ItemStack>,
        keep: HashSet<Int>,
        limit: Int,
        selector: (ItemStack) -> Boolean,
    ) {
        val groups = LinkedHashMap<Any, MutableList<Int>>()
        for (slot in FIRST_INVENTORY_ROW_SLOT until LAST_SLOT_EXCLUSIVE) {
            val stack = slots.getOrElse(slot) { ItemStack.EMPTY }
            if (stack.isEmpty || !selector(stack)) continue
            groups.getOrPut(stack.item) { ArrayList() }.add(slot)
        }
        val perGroup = limit.coerceAtLeast(1)
        for (group in groups.values) {
            group.sortByDescending { slots.getOrElse(it) { ItemStack.EMPTY }.count }
            group.take(perGroup).forEach(keep::add)
        }
    }

    /** True when this usable stack duplicates something already kept. */
    private fun isRedundant(stack: ItemStack, keep: Set<Int>, slots: Array<ItemStack>): Boolean = when {
        stack.isSwordForScoring() -> hasKept(slots, keep) { it.isSwordForScoring() }
        stack.item is BowItem -> hasKept(slots, keep) { it.item is BowItem }
        stack.isMiningTool() -> {
            val type = toolType(stack)
            type != -1 && hasKept(slots, keep) { it.isMiningTool() && toolType(it) == type }
        }
        stack.isPlayerArmor -> {
            val type = armorIndex(stack)
            type != -1 && hasKept(slots, keep) { it.isPlayerArmor && armorIndex(it) == type }
        }
        // Food duplicates are grouped per item, matching keepBiggestPerItem above.
        isGapple(stack) -> hasKept(slots, keep) { isGapple(it) && it.item === stack.item }
        stack.item is BlockItem -> hasKept(slots, keep) {
            it.item is BlockItem && it.item === stack.item
        }
        else -> false
    }

    private inline fun hasKept(
        slots: Array<ItemStack>,
        keep: Set<Int>,
        predicate: (ItemStack) -> Boolean,
    ): Boolean = keep.any { slots.getOrElse(it) { ItemStack.EMPTY }.let(predicate) }

    /**
     * MoonLight's `sortItems(true)`, i.e. the ordered move list for one pass.
     *
     * <p>The original performs one move per tick and rescans, so only the head of this list is ever
     * needed in practice; the full list is returned because it makes the intent testable.</p>
     */
    fun plan(scan: MoonlightScan, slotSizes: (Int) -> Int): List<MoonlightMove> {
        val moves = ArrayList<MoonlightMove>()
        if (!options.sortItems) return moves

        if (scan.bestSword != -1 && scan.bestSword != hotbarSlot(options.swordTarget)) {
            moves += MoonlightMove.SwapToHotbar(scan.bestSword, options.swordTarget)
        }
        if (scan.bestBow != -1 && scan.bestBow != hotbarSlot(options.bowTarget)) {
            moves += MoonlightMove.SwapToHotbar(scan.bestBow, options.bowTarget)
        }
        val bestGapple = scan.gapples.minByOrNull { slotSizes(it) }
        if (bestGapple != null && bestGapple != hotbarSlot(options.gappleTarget)) {
            moves += MoonlightMove.SwapToHotbar(bestGapple, options.gappleTarget)
        }
        val bestBlock = scan.blocks.maxByOrNull { slotSizes(it) }
        if (bestBlock != null && bestBlock != hotbarSlot(options.blockTarget)) {
            moves += MoonlightMove.SwapToHotbar(bestBlock, options.blockTarget)
        }
        for (type in 0 until MoonlightScan.TOOL_TYPES) {
            val toolSlot = scan.bestTools[type]
            if (toolSlot == -1) continue
            val target = options.toolTargets.getOrElse(type) { -1 }
            if (target == -1) continue
            if (toolSlot != hotbarSlot(target)) {
                moves += MoonlightMove.SwapToHotbar(toolSlot, target)
            }
        }

        return moves
    }

    /** Armour pieces that should be shift-clicked into their empty armour slot, in MoonLight order. */
    fun armorMoves(scan: MoonlightScan, slots: Array<ItemStack>): List<MoonlightMove> {
        if (!options.autoArmor) return emptyList()
        val moves = ArrayList<MoonlightMove>()
        for (type in 0 until MoonlightScan.ARMOR_TYPES) {
            val piece = scan.bestArmor[type]
            if (piece == -1) continue
            // Armour slots are 5..8, in EquipmentSlot.index order (feet, legs, chest, head).
            val armorSlot = ARMOR_SLOT_BEGIN + type
            if (!slots.getOrElse(armorSlot) { ItemStack.EMPTY }.isEmpty) continue
            moves += MoonlightMove.QuickMove(piece)
        }
        return moves
    }

    // ---------------------------------------------------------------- item rules

    /** MoonLight `isValidStack`: keep blocks, buff potions, good food, projectiles, arrows, pearls. */
    fun isValidStack(stack: ItemStack): Boolean = when {
        stack.item is BlockItem && isGoodBlockStack(stack) -> true
        isBuffPotion(stack) -> true
        isGoodFood(stack) -> true
        stack.item is EggItem || stack.item is SnowballItem -> options.keepProjectiles
        stack.item === Items.ARROW -> true
        stack.item is FishingRodItem -> options.keepRods
        else -> isGoodItem(stack)
    }

    private fun isGoodItem(stack: ItemStack): Boolean {
        val item = stack.item
        return item is EnderpearlItem
    }

    fun isGoodFood(stack: ItemStack): Boolean {
        if (isGapple(stack)) return true
        val food = stack.foodComponent ?: return false
        // 1.8 heal amount 4 / saturation 0.3 is 1.21 nutrition 4 / saturation 0.3.
        return food.nutrition >= 4 && food.saturation >= 0.3F
    }

    private fun isGapple(stack: ItemStack): Boolean =
        stack.item === Items.GOLDEN_APPLE || stack.item === Items.ENCHANTED_GOLDEN_APPLE

    fun isBuffPotion(stack: ItemStack): Boolean {
        val effects = runCatching { stack.getPotionEffects().toList() }.getOrNull() ?: return false
        if (effects.isEmpty()) return false
        // MoonLight's BAD_EFFECTS_IDS: poison, weakness, wither, blindness, mining fatigue, harming.
        return effects.none { effect ->
            effect.effect.value() === MobEffects.POISON.value() ||
                effect.effect.value() === MobEffects.WEAKNESS.value() ||
                effect.effect.value() === MobEffects.WITHER.value() ||
                effect.effect.value() === MobEffects.BLINDNESS.value() ||
                effect.effect.value() === MobEffects.MINING_FATIGUE.value() ||
                effect.effect.value() === MobEffects.INSTANT_DAMAGE.value()
        }
    }

    fun isGoodBlockStack(stack: ItemStack): Boolean = stack.count >= 1 && isPlaceableBlock(stack)

    /**
     * MoonLight `isValidBlock`: full solid cube, not a container, not TNT, not falling.
     * `BlockContainer`/`BlockTNT`/`BlockFalling` were separate classes in 1.8; on 1.21 the same set
     * is expressed with block behaviour checks plus the vanilla item list.
     */
    private fun isPlaceableBlock(stack: ItemStack): Boolean {
        val block = (stack.item as? BlockItem)?.block ?: return false
        if (stack.item in NON_PLACEABLE) return false
        val state = runCatching { block.defaultBlockState() }.getOrNull() ?: return false
        return isPlaceableState(state)
    }

    /**
     * MoonLight's `isFullBlock() && isFullCube() && !isLiquid()` mouthful, kept separate so it can be
     * exercised by tests on plain block states (no level, no client).
     */
    fun isPlaceableState(state: BlockState): Boolean =
        // Liquid blocks (water/lava) are not placeable inventory blocks: 1.8 `material.isLiquid`.
        state.fluidState.isEmpty &&
            // `material.isSolid()` in 1.8.
            state.isSolid &&
            // `isFullBlock() && isFullCube()`. The level only feeds shape caching, so the empty
            // getter is enough and this works with no world loaded.
            state.isCollisionShapeFullBlock(EMPTY_LEVEL, BlockPos.ZERO)

    /** MoonLight `findBestBlockStack`, scanning downwards (44 -> 9) and keeping the biggest stack. */
    private fun bestBlockStack(slots: Array<ItemStack>): Int {
        var bestSlot = -1
        var bestCount = -1
        for (slot in LAST_SLOT_EXCLUSIVE - 1 downTo FIRST_INVENTORY_ROW_SLOT) {
            val stack = slots.getOrElse(slot) { ItemStack.EMPTY }
            if (stack.item is BlockItem && isGoodBlockStack(stack) && stack.count > bestCount) {
                bestSlot = slot
                bestCount = stack.count
            }
        }
        return bestSlot
    }

    // ---------------------------------------------------------------- comparisons

    private fun isBestSword(slots: Array<ItemStack>, stack: ItemStack): Boolean {
        var best = 0.0
        for (slot in FIRST_INVENTORY_ROW_SLOT until LAST_SLOT_EXCLUSIVE) {
            val other = slots.getOrElse(slot) { ItemStack.EMPTY }
            if (!other.isSwordForScoring()) continue
            val damage = itemDamage(other)
            if (damage > best) best = damage
        }
        // MoonLight: `bestStack == stack || best < damage(stack)`.
        return itemDamage(stack) >= best
    }

    private fun isBestBow(slots: Array<ItemStack>, stack: ItemStack): Boolean {
        var best = -1.0
        for (slot in FIRST_INVENTORY_ROW_SLOT until LAST_SLOT_EXCLUSIVE) {
            val other = slots.getOrElse(slot) { ItemStack.EMPTY }
            if (other.item !is BowItem) continue
            val damage = bowDamage(other)
            if (damage > best) best = damage
        }
        return bowDamage(stack) >= best
    }

    private fun isBestArmor(slots: Array<ItemStack>, stack: ItemStack): Boolean {
        val type = armorIndex(stack)
        if (type == -1) return false
        var best = 0.0
        for (slot in ARMOR_SLOT_BEGIN until LAST_SLOT_EXCLUSIVE) {
            val other = slots.getOrElse(slot) { ItemStack.EMPTY }
            if (!other.isPlayerArmor) continue
            if (armorIndex(other) != type) continue
            val reduction = damageReduction(other)
            if (reduction > best) best = reduction
        }
        return damageReduction(stack) >= best
    }

    private fun isBestTool(slots: Array<ItemStack>, stack: ItemStack): Boolean {
        val type = toolType(stack)
        if (type == -1) return false
        var best = -1.0
        for (slot in FIRST_INVENTORY_ROW_SLOT until LAST_SLOT_EXCLUSIVE) {
            val other = slots.getOrElse(slot) { ItemStack.EMPTY }
            if (!other.isMiningTool()) continue
            if (toolType(other) != type) continue
            val efficiency = toolEfficiency(other)
            if (efficiency > best) best = efficiency
        }
        return toolEfficiency(stack) >= best
    }

    /** 1.8 `ItemTool` subclasses; a multi-tool keeps the first match, like MoonLight's `-1` fallback. */
    fun toolType(stack: ItemStack): Int = when {
        stack.isPickaxe -> MoonlightScan.TOOL_PICKAXE
        stack.isAxe -> MoonlightScan.TOOL_AXE
        stack.isShovel -> MoonlightScan.TOOL_SHOVEL
        else -> -1
    }

    fun armorIndex(stack: ItemStack): Int = when (stack.equipmentSlot) {
        EquipmentSlot.FEET -> 0
        EquipmentSlot.LEGS -> 1
        EquipmentSlot.CHEST -> 2
        EquipmentSlot.HEAD -> 3
        else -> -1
    }

    /** MoonLight `getItemDamage`: attack damage attribute + fire aspect + sharpness * 1.25. */
    fun itemDamage(stack: ItemStack): Double {
        var damage = stack.getAttributeValue(Attributes.ATTACK_DAMAGE, stack.equipmentSlot)
        if (stack.isEnchanted) {
            damage += stack.getEnchantment(Enchantments.FIRE_ASPECT).toDouble()
            damage += stack.getEnchantment(Enchantments.SHARPNESS) * 1.25
        }
        return damage
    }

    /** MoonLight `getBowDamage`: only the power enchantment counted. */
    fun bowDamage(stack: ItemStack): Double =
        if (stack.isEnchanted) stack.getEnchantment(Enchantments.POWER).toDouble() else 0.0

    /** MoonLight `getDamageReduction`: armour points + protection * 0.25. */
    fun damageReduction(stack: ItemStack): Double {
        var reduction = stack.armorValue.toDouble()
        if (stack.isEnchanted) {
            reduction += stack.getEnchantment(Enchantments.PROTECTION) * 0.25
        }
        // Toughness is not in MoonLight's 1.8 formula; it is added so diamond/netherite pieces of the
        // same armour points still rank apart on modern versions.
        reduction += stack.armorToughness.toDouble() * 0.1
        return reduction
    }

    /** MoonLight `getToolEfficiency`: 4 + level^2 + 1. */
    fun toolEfficiency(stack: ItemStack): Double {
        val base = stack.toolComponent?.defaultMiningSpeed?.toDouble() ?: 4.0
        val level = stack.getEnchantment(Enchantments.EFFICIENCY)
        return if (level > 0) base + level * level + 1.0 else base
    }

    private fun ItemStack.isSwordForScoring(): Boolean = this.isSword

    private fun ItemStack.isMiningTool(): Boolean = isAxe || isPickaxe || isShovel || isHoe

    private fun hotbarSlot(target: Int): Int = HOTBAR_SLOT_BEGIN + target

    companion object {
        /** MoonLight `InventoryUtils.INCLUDE_ARMOR_BEGIN`. */
        const val ARMOR_SLOT_BEGIN = 5
        /** MoonLight `EXCLUDE_ARMOR_BEGIN`. */
        const val FIRST_INVENTORY_ROW_SLOT = 9
        /** MoonLight `ONLY_HOT_BAR_BEGIN`. */
        const val HOTBAR_SLOT_BEGIN = 36
        /** MoonLight `END`. */
        const val LAST_SLOT_EXCLUSIVE = 45
        /** MoonLight scans armour slots too; only the sword/bow/tool scans start at 9. */
        const val FIRST_SLOT = ARMOR_SLOT_BEGIN

        /** An empty level, so full-cube checks work with no world loaded. */
        private val EMPTY_LEVEL = EmptyBlockGetter.INSTANCE

        private val NON_PLACEABLE = setOf(
            Items.TNT, Items.CHEST, Items.TRAPPED_CHEST, Items.ENDER_CHEST, Items.FURNACE,
            Items.SAND, Items.GRAVEL, Items.ANVIL, Items.CHIPPED_ANVIL, Items.DAMAGED_ANVIL,
            Items.SCAFFOLDING, Items.CAKE, Items.HOPPER, Items.BARRIER,
        )
    }
}
