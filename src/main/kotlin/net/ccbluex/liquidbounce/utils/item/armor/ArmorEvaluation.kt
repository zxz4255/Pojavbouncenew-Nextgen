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

package net.ccbluex.liquidbounce.utils.item.armor

import net.ccbluex.fastutil.enumMapOf
import net.ccbluex.liquidbounce.utils.inventory.ItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.item.isPlayerArmor
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.world.entity.EquipmentSlot

object ArmorEvaluation {
    /**
     * We expect damage to be around diamond sword hits
     */
    private const val EXPECTED_DAMAGE: Float = 6.0F

    fun findBestArmorPieces(
        slots: List<ItemSlot> = Slots.All,
        durabilityThreshold: Int = Int.MIN_VALUE,
        mode: ArmorComparatorMode = ArmorComparatorMode.SMART,
        considerProjectileProtection: Boolean = true
    ): Map<EquipmentSlot, ArmorPiece?> {
        /*
         * This is called from `ScheduleInventoryActionEvent`, which the inventory scheduler may run
         * several times per tick while a container is open (ChestStealer closing a chest, AutoArmor
         * equipping, InvManager rearranging). Every call used to walk every armour piece in the whole
         * inventory, twice, and each comparison reaches into data components and the attribute
         * modifier stack.
         *
         * A thread dump taken while the client was frozen showed the render thread sitting inside
         * this method (maxWithOrNull -> ArmorComparator -> ItemStack.getComponents -> isEmpty), so the
         * cost is real and it lands on the render thread.
         *
         * The result only depends on the contents of the inventory, so it is memoised per tick-ish
         * window. `slots` is `Slots.All` (a stable list of slot objects) in every caller, and the
         * cache key includes the mutation-counter of the player inventory and of the open menu, which
         * the game bumps whenever a stack changes. That makes a stale hit impossible while a chest is
         * being emptied — the exact situation where correctness matters.
         */
        val key = CacheKey(
            slots = slots,
            threshold = durabilityThreshold,
            mode = mode,
            projectiles = considerProjectileProtection,
            inventoryVersion = inventoryVersion(),
        )
        cachedResult?.let { cached ->
            if (cached.key.matches(key)) {
                return cached.result
            }
        }

        val armorPiecesGroupedByType = groupArmorByType(slots)

        // We start with assuming that the best pieces are those which have the most damage points.
        val currentBestPieces = armorPiecesGroupedByType.mapValuesTo(enumMapOf()) { (_, piecesForType) ->
            piecesForType.maxByOrNull { it.toughness }
        }

        // Run some passes in which we try to find best armor pieces based on the parameters of the last pass
        repeat(2) {
            val comparator =
                getArmorComparatorFor(currentBestPieces, durabilityThreshold, mode, considerProjectileProtection)

            currentBestPieces.clear()
            armorPiecesGroupedByType.mapValuesTo(currentBestPieces) { it.value.maxWithOrNull(comparator) }
        }

        /*
         * Hand out an immutable snapshot, and keep one for the cache: `currentBestPieces` is an
         * EnumMap that the passes above mutate in place, so storing it directly would let a caller
         * observe later mutations and would make the comparison of two results meaningless.
         */
        val snapshot: Map<EquipmentSlot, ArmorPiece?> =
            java.util.Collections.unmodifiableMap(enumMapOf<EquipmentSlot, ArmorPiece?>().apply {
                putAll(currentBestPieces)
            })
        cachedResult = CachedResult(key, snapshot)
        return snapshot
    }

    /**
     * Identifies an evaluation. Two evaluations with the same key cannot differ.
     *
     * `inventoryVersion` folds in the game's own change counters, so any click, container update or
     * equipment change invalidates the cache without us having to listen for it.
     */
    private class CacheKey(
        val slots: List<ItemSlot>,
        val threshold: Int,
        val mode: ArmorComparatorMode,
        val projectiles: Boolean,
        val inventoryVersion: Int,
    ) {
        /**
         * Compares by identity for [slots], not by content.
         *
         * Every caller passes the shared `Slots.All` instance, so identity is the right test and it is
         * O(1). A content comparison here would walk 41 slots — and do it on every single evaluation,
         * which is the cost this cache is meant to avoid.
         */
        fun matches(other: CacheKey): Boolean =
            slots === other.slots &&
                threshold == other.threshold &&
                mode == other.mode &&
                projectiles == other.projectiles &&
                inventoryVersion == other.inventoryVersion
    }

    private class CachedResult(val key: CacheKey, val result: Map<EquipmentSlot, ArmorPiece?>)

    private var cachedResult: CachedResult? = null

    /**
     * A cheap fingerprint of everything that can change the answer.
     *
     * `stateId` is incremented by the game on every slot mutation (container clicks, resyncs, held
     * item changes), which is exactly the granularity needed here. Falling back to 0 when there is no
     * menu simply costs one extra evaluation, never a wrong one.
     */
    private fun inventoryVersion(): Int {
        val player = mc.player ?: return 0
        // `containerMenu` is non-null in this mapping (it is always at least the inventory menu).
        val menu = player.containerMenu
        /*
         * `stateId` covers every slot mutation the game knows about — clicks, resyncs, held-item
         * changes — and is an int read. Do NOT hash the inventory contents here: that is O(slots)
         * with item-stack hashing, i.e. exactly the kind of cost this cache exists to remove.
         *
         * Equipment changes go through the inventory too, so `stateId` alone is sufficient; the
         * selected-hotbar-slot is added because it flips `isReachableByHand`, which the comparator
         * ranks on.
         */
        return menu.stateId * 31 + player.inventory.selectedSlot
    }

    fun findBestArmorPiecesWithComparator(
        slots: List<ItemSlot> = Slots.All,
        comparator: ArmorComparator
    ): Map<EquipmentSlot, ArmorPiece?> {
        val armorPiecesGroupedByType = groupArmorByType(slots)

        return armorPiecesGroupedByType.mapValuesTo(enumMapOf()) { it.value.maxWithOrNull(comparator) }
    }

    private fun groupArmorByType(slots: List<ItemSlot>): Map<EquipmentSlot, List<ArmorPiece>> {
        val armorPiecesGroupedByType = slots.mapNotNull { slot ->
            if (slot.itemStack.isPlayerArmor) {
                // Filter out animal armor which is an armor item but not for the player
                // Note: in 1.21.4 [AnimalArmorItem] is not a subclass of [ArmorItem]

                // ArmorItem class has been removed from 1.21.5
                ArmorPiece(slot)
            } else {
                null
            }
        }.groupByTo(enumMapOf(), ArmorPiece::slotType)

        return armorPiecesGroupedByType
    }

    fun getArmorComparatorFor(
        currentKit: Map<EquipmentSlot, ArmorPiece?>,
        durabilityThreshold: Int = Int.MIN_VALUE,
        mode: ArmorComparatorMode = ArmorComparatorMode.SMART,
        considerProjectileProtection: Boolean = true
    ): ArmorComparator {
        return getArmorComparatorForParameters(
            ArmorKitParameters.getParametersForSlots(currentKit),
            durabilityThreshold,
            mode,
            considerProjectileProtection
        )
    }

    fun getArmorComparatorForParameters(
        currentParameters: ArmorKitParameters,
        durabilityThreshold: Int = Int.MIN_VALUE,
        mode: ArmorComparatorMode = ArmorComparatorMode.SMART,
        considerProjectileProtection: Boolean = true
    ): ArmorComparator {
        return ArmorComparator(
            EXPECTED_DAMAGE,
            currentParameters,
            durabilityThreshold,
            mode,
            considerProjectileProtection
        )
    }


}
