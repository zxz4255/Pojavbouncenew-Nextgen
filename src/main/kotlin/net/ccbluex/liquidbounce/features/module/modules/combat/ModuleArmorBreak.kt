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

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerNetworkMovementTickEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleGapple
import net.ccbluex.liquidbounce.utils.item.attackDamage
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundPunchPacket
import net.minecraft.tags.ItemTags
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Cycles through progressively stronger weapons while an attacked target is recovering,
 * allowing repeated weapon swaps to wear down its armor.
 *
 * Adapted from GP.Pro's ArmorBreak module to the current LiquidBounce module and packet APIs.
 */
object ModuleArmorBreak : ClientModule("ArmorBreak", ModuleCategories.COMBAT) {

    private enum class SendEvent(override val tag: String) : Tagged {
        PRE_ATTACK("PreAttack"),
        ATTACK("Attack"),
        POST_MOTION("PostMotion"),
        PRE_MOTION("PreMotion")
    }

    private enum class ClickMode(override val tag: String) : Tagged {
        WINDOW_CLICK("WindowClick"),
        SEND_PACKET("SendPacket")
    }

    private enum class MultiHit(override val tag: String) : Tagged {
        OFF("Off"),
        BLOCKING("Blocking"),
        ALWAYS("Always")
    }

    private enum class CritPattern(override val tag: String) : Tagged {
        ONE_CRIT_ONE_NORMAL("OneCritOneNormal"),
        BOTH_CRIT("BothCrit"),
        BOTH_NORMAL("BothNormal")
    }

    private val sendEvent by enumChoice("SendEvent", SendEvent.PRE_ATTACK)
    private val clickMode by enumChoice("ClickMode", ClickMode.WINDOW_CLICK)
    private val minHurtTime by int("MinHurtTime", 3, 0..9)
    private val addAxeToArmorBreak by boolean("AddAxeToArmorBreak", false)
    private val autoSwitchBackToLowestDamageWeapon by boolean("AutoSwitchBackToLowestDamageWeapon", false)
    private val onGap by boolean("onGap", false)
    private val multiHit by enumChoice("MultiHit", MultiHit.OFF)
    private val hitCount by int("HitCount", 2, 2..5)
    private val critPattern by enumChoice("CritPattern", CritPattern.ONE_CRIT_ONE_NORMAL)
    private val critDrop by float("CritDrop", 0.1f, 0.0f..1.0f)
    private val swingEachHit by boolean("SwingEachHit", true)

    private var lastSwordDamage = -1.0
    private var switchEnd = false
    private var lastMultiHitTick = -1

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        if (sendEvent != SendEvent.PRE_ATTACK) return@handler

        (event.entity as? LivingEntity)?.let(::doIt)
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (sendEvent != SendEvent.ATTACK || event.origin != TransferOrigin.OUTGOING || event.isCancelled) {
            return@handler
        }

        val packet = event.packet as? ServerboundAttackPacket ?: return@handler
        val target = mc.level?.getEntity(packet.entityId) as? LivingEntity ?: return@handler
        doIt(target)
    }

    @Suppress("unused")
    private val movementHandler = handler<PlayerNetworkMovementTickEvent> { event ->
        val matchesTiming = when (sendEvent) {
            SendEvent.PRE_MOTION -> event.state == EventState.PRE
            SendEvent.POST_MOTION -> event.state == EventState.POST
            else -> false
        }

        if (matchesTiming) {
            ModuleKillAura.targetTracker.target?.let(::doIt)
        }
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        resetState()
    }

    private fun doIt(target: LivingEntity) {
        val player = mc.player ?: return

        // Match GP.Pro's Gapple guard: keep the low-to-high armor-break cycle paused while the
        // AutoGapple module is consuming, unless the user explicitly opts in.
        if (ModuleGapple.enabled && !onGap && player.isUsingItem && player.useItem.isGoldenApple()) {
            val bestSword = findBestSword()
            if (bestSword != -1) {
                sendSwitchPacket(bestSword)
            }
            return
        }

        val switchSlot = getSwitchSlot(target.hurtTime)
        if (switchSlot == -1) return

        if (sendSwitchPacket(switchSlot)) {
            sendMultiHit(target)
        }
    }

    /**
     * Chooses the least damaging sword (or optionally axe) whose damage exceeds the
     * last selected weapon. This preserves GP.Pro's low-to-high cycling behavior.
     */
    private fun getSwitchSlot(targetHurtTime: Int): Int {
        if (targetHurtTime <= minHurtTime) {
            lastSwordDamage = -1.0
            switchEnd = false
        }

        if (switchEnd && autoSwitchBackToLowestDamageWeapon) {
            lastSwordDamage = -1.0
        }

        val player = mc.player ?: return -1
        var bestSlot = -1
        var bestDamage = Double.POSITIVE_INFINITY

        for (slot in INVENTORY_MENU_FIRST_WEAPON_SLOT..INVENTORY_MENU_LAST_WEAPON_SLOT) {
            val stack = player.inventoryMenu.getSlot(slot).getItem()
            if (stack.isEmpty) continue

            val isSword = stack.`is`(ItemTags.SWORDS)
            val isAxe = addAxeToArmorBreak && stack.`is`(ItemTags.AXES)
            if (!isSword && !isAxe) continue

            val damage = stack.attackDamage
            if (damage > lastSwordDamage && damage < bestDamage) {
                bestDamage = damage
                bestSlot = slot
            }
        }

        if (bestSlot != -1) {
            lastSwordDamage = bestDamage
            return bestSlot
        }

        switchEnd = true
        return -1
    }

    /** Returns the inventory-menu slot containing the highest-damage sword, or -1. */
    private fun findBestSword(): Int {
        val player = mc.player ?: return -1
        var bestSlot = -1
        var bestDamage = -1.0

        for (slot in INVENTORY_MENU_FIRST_WEAPON_SLOT..INVENTORY_MENU_LAST_WEAPON_SLOT) {
            val stack = player.inventoryMenu.getSlot(slot).getItem()
            if (stack.isEmpty || !stack.`is`(ItemTags.SWORDS)) continue

            val damage = stack.attackDamage
            if (damage > bestDamage) {
                bestDamage = damage
                bestSlot = slot
            }
        }

        return bestSlot
    }

    /**
     * Switches to a weapon in the main inventory or hotbar. Inventory items are swapped
     * into the selected hotbar slot; hotbar-only SendPacket mode changes the held slot.
     */
    private fun sendSwitchPacket(slot: Int): Boolean {
        val player = mc.player ?: return false
        val gameMode = mc.gameMode ?: return false

        if (mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu) return false
        if (slot !in INVENTORY_MENU_FIRST_WEAPON_SLOT..INVENTORY_MENU_LAST_WEAPON_SLOT) return false

        val selectedHotbarSlot = player.inventory.selectedSlot

        return runCatching {
            if (clickMode == ClickMode.SEND_PACKET && slot >= Inventory.INVENTORY_SIZE) {
                val hotbarSlot = slot - Inventory.INVENTORY_SIZE
                player.inventory.selectedSlot = hotbarSlot
                sendPacketSilently(ServerboundSetCarriedItemPacket(hotbarSlot))
            } else {
                gameMode.handleContainerInput(
                    player.inventoryMenu.containerId,
                    slot,
                    selectedHotbarSlot,
                    ContainerInput.SWAP,
                    player
                )
            }

            true
        }.getOrDefault(false)
    }

    private fun sendMultiHit(target: LivingEntity) {
        if (multiHit == MultiHit.OFF) return
        if (multiHit == MultiHit.BLOCKING && (target !is Player || !target.isBlocking)) return

        val player = mc.player ?: return
        if (lastMultiHitTick == player.tickCount) return
        lastMultiHitTick = player.tickCount

        val wasOnGround = player.onGround()
        val wasSneaking = player.isShiftKeyDown

        try {
            repeat((hitCount - 1).coerceAtLeast(1)) { index ->
                val crit = when (critPattern) {
                    CritPattern.BOTH_CRIT -> true
                    CritPattern.BOTH_NORMAL -> false
                    CritPattern.ONE_CRIT_ONE_NORMAL -> index % 2 == 0
                }

                sendFakeFall(crit)
                if (swingEachHit) {
                    sendPacketSilently(ServerboundPunchPacket.INSTANCE)
                }
                sendPacketSilently(ServerboundAttackPacket(target.id))
            }
        } finally {
            sendGroundState(wasOnGround, wasSneaking)
        }
    }

    private fun sendFakeFall(crit: Boolean) {
        val player = mc.player ?: return
        val y = if (crit) player.y - critDrop else player.y

        sendPacketSilently(
            ServerboundMovePlayerPacket.Pos(
                player.x,
                y,
                player.z,
                false,
                false
            )
        )
    }

    private fun sendGroundState(onGround: Boolean, sneaking: Boolean) {
        val player = mc.player ?: return

        if (sneaking) {
            player.setShiftKeyDown(true)
        }

        sendPacketSilently(
            ServerboundMovePlayerPacket.PosRot(
                player.x,
                player.y,
                player.z,
                player.yRot,
                player.xRot,
                onGround,
                false
            )
        )
    }

    private fun ItemStack.isGoldenApple(): Boolean =
        `is`(Items.GOLDEN_APPLE) || `is`(Items.ENCHANTED_GOLDEN_APPLE)

    private fun resetState() {
        lastSwordDamage = -1.0
        switchEnd = false
        lastMultiHitTick = -1
    }

    override fun onDisabled() {
        resetState()
    }

    private const val INVENTORY_MENU_FIRST_WEAPON_SLOT = 9
    private const val INVENTORY_MENU_LAST_WEAPON_SLOT = 44
}
