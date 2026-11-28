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
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.additions.realSelectedSlot
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleGapple
import net.ccbluex.liquidbounce.utils.client.isOlderThanOrEqual1_8
import net.ccbluex.liquidbounce.utils.client.usesViaFabricPlus
import net.ccbluex.liquidbounce.utils.client.vfp.VfpCompatibility1_8
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.item.attackDamage
import net.minecraft.tags.ItemTags
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.LinkedList
import java.util.Queue

/**
 * 1:1 port of ts2 `ArmorBreak` (`combat/ArmorBreak.kt`) with 1.8 cross-version `C0EPacketClickWindow`
 * (`mode = 2`, hotbar swap) support via ViaFabricPlus.
 */
object ModuleArmorBreak : ClientModule("ArmorBreak", ModuleCategories.COMBAT) {

    val hurtTime by int("HurtTime", 3, 0..9)
    val axe by boolean("AddAxe", false)
    val switchBack by boolean("LowestDamageWeapon", false)
    val onGap by boolean("GappleWork", false)
    private val openInventory1_8 by boolean("OpenInventory1.8", false)

    var lastSwordDamage = -1.0
    var switchEnd = false
    var attack = false

    @Volatile
    var needSwitch = false
        private set

    fun isArmorBreakActive(): Boolean = running && needSwitch

    override fun onDisabled() {
        resetState()
    }

    private fun resetState() {
        lastSwordDamage = -1.0
        switchEnd = false
        attack = false
        needSwitch = false
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        resetState()
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        needSwitch = false
    }

    fun getSwitchSlot(targetHurtTime: Int): Int {
        val player = mc.player ?: return -1
        val swordDamages: Queue<Double> = LinkedList()
        val swordSlots: Queue<Int> = LinkedList()

        if (targetHurtTime <= this.hurtTime) {
            lastSwordDamage = -1.0
            switchEnd = false
        }

        if (switchEnd && switchBack) {
            lastSwordDamage = -1.0
        }

        val menu = player.containerMenu
        for (slot in FIRST_WEAPON_SLOT until LAST_WEAPON_SLOT_EXCLUSIVE) {
            if (slot >= menu.slots.size) break
            val stack = menu.getSlot(slot).item
            if (stack.isEmpty) continue

            val isSword = stack.`is`(ItemTags.SWORDS)
            val isAxe = stack.`is`(ItemTags.AXES)
            if (!isSword && (!isAxe || !axe)) {
                continue
            }

            val damage = stack.attackDamage
            if (damage > lastSwordDamage) {
                swordDamages.add(damage)
                swordSlots.add(slot)
            }
        }

        var minSwordDamage = 10000.0
        var minSwordSlot = -1

        while (swordDamages.isNotEmpty()) {
            val damage = swordDamages.poll() ?: break
            val slot = swordSlots.poll() ?: break
            if (damage < minSwordDamage) {
                minSwordDamage = damage
                minSwordSlot = slot
            }
        }

        if (minSwordDamage != 10000.0) {
            lastSwordDamage = minSwordDamage
            return minSwordSlot
        }

        switchEnd = true
        return -1
    }

    fun findBestSword(): Int {
        val player = mc.player ?: return -1
        val menu = player.containerMenu
        var maxDamage = -1.0
        var bestSlot = -1

        for (index in FIRST_WEAPON_SLOT until LAST_WEAPON_SLOT_EXCLUSIVE) {
            if (index >= menu.slots.size) break
            val stack = menu.getSlot(index).item
            if (stack.isEmpty || !stack.`is`(ItemTags.SWORDS)) {
                continue
            }

            val damage = stack.attackDamage
            if (damage > maxDamage) {
                maxDamage = damage
                bestSlot = index
            }
        }

        return bestSlot
    }

    private fun performWindowSwap(slot: Int) {
        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return
        val menu = player.containerMenu
        if (slot !in FIRST_WEAPON_SLOT until LAST_WEAPON_SLOT_EXCLUSIVE || slot >= menu.slots.size) {
            return
        }

        val currentHotbar = player.inventory.realSelectedSlot.coerceIn(0, 8)
        needSwitch = true

        if (openInventory1_8 && usesViaFabricPlus && isOlderThanOrEqual1_8 && !InventoryManager.isInventoryOpenServerSide) {
            VfpCompatibility1_8.INSTANCE.sendOpenInventory()
        }

        // Emits 1.8 C0EPacketClickWindow(windowId, slot, currentHotbar, actionNumber, mode = 2, clickedItem)
        // through ViaFabricPlus's 1.8 inventory tracker so transaction IDs remain synchronized.
        gameMode.handleContainerInput(
            menu.containerId,
            slot,
            currentHotbar,
            ContainerInput.SWAP,
            player,
        )
    }

    fun doIt(targetEntity: LivingEntity) {
        val player = mc.player ?: return
        val gappleEating = ModuleGapple.isEating ||
            (player.isUsingItem && player.useItem.isGoldenApple())

        if (gappleEating && !onGap) {
            val slot = findBestSword()
            if (slot != -1) {
                performWindowSwap(slot)
            }
            return
        }

        val slot = getSwitchSlot(targetEntity.hurtTime)
        if (slot == -1) {
            return
        }

        performWindowSwap(slot)
    }

    @Suppress("unused")
    private val onAttack = handler<AttackEntityEvent> { event ->
        val target = event.entity as? LivingEntity ?: return@handler
        attack = true
        doIt(target)
        attack = false
    }

    private fun ItemStack.isGoldenApple(): Boolean =
        !isEmpty && (`is`(Items.GOLDEN_APPLE) || `is`(Items.ENCHANTED_GOLDEN_APPLE))

    private const val FIRST_WEAPON_SLOT = 9
    private const val LAST_WEAPON_SLOT_EXCLUSIVE = 45
}
