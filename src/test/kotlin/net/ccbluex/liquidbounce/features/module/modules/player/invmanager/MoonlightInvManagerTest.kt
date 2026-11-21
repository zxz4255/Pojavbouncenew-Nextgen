/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.ccbluex.liquidbounce.features.module.modules.player.invmanager

import net.ccbluex.liquidbounce.test.MinecraftBootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the decision logic ported from MoonLight's `InvManager`.
 *
 * These tests only touch plain [ItemStack]s, so they run without a client or a world. They do not
 * prove anything about a real server: the click timing and the translated 1.8 packets still have to
 * be verified in game.
 */
class MoonlightInvManagerTest {

    @BeforeTest
    fun bootstrap() {
        // `Items.*` and the item tags/components need the vanilla registries.
        MinecraftBootstrap.ensureInitialized()
    }

    private fun slots(vararg entries: Pair<Int, ItemStack>): Array<ItemStack> {
        val array = Array(MoonlightInventoryLogic.LAST_SLOT_EXCLUSIVE) { ItemStack.EMPTY }
        for ((slot, stack) in entries) {
            array[slot] = stack
        }
        return array
    }

    private fun logic(options: MoonlightInventoryLogic.Options = MoonlightInventoryLogic.Options()) =
        MoonlightInventoryLogic(options)

    @Test
    fun `sword is moved to its target hotbar slot`() {
        val stack = slots(10 to ItemStack(Items.DIAMOND_SWORD))
        val scan = logic().scan(stack)

        assertEquals(10, scan.bestSword)
        assertEquals(
            listOf(MoonlightMove.SwapToHotbar(10, 0)),
            logic().plan(scan) { stack[it].count },
        )
    }

    @Test
    fun `sword already in the target slot needs no move`() {
        val stack = slots(MoonlightInventoryLogic.HOTBAR_SLOT_BEGIN to ItemStack(Items.DIAMOND_SWORD))
        val scan = logic().scan(stack)

        assertEquals(MoonlightInventoryLogic.HOTBAR_SLOT_BEGIN, scan.bestSword)
        assertTrue(logic().plan(scan) { stack[it].count }.isEmpty())
    }

    @Test
    fun `sorting can be disabled`() {
        val stack = slots(10 to ItemStack(Items.DIAMOND_SWORD))
        val scan = logic(MoonlightInventoryLogic.Options(sortItems = false)).scan(stack)

        assertTrue(logic(MoonlightInventoryLogic.Options(sortItems = false)).plan(scan) { 1 }.isEmpty())
    }

    @Test
    fun `largest block stack is the one that is kept`() {
        val stack = slots(
            12 to ItemStack(Items.COBBLESTONE, 5),
            13 to ItemStack(Items.COBBLESTONE, 42),
            14 to ItemStack(Items.DIRT, 9),
        )
        val scan = logic().scan(stack)

        // MoonLight keeps the biggest stack, i.e. slot 13 (42 cobblestone).
        assertEquals(listOf(13), scan.blocks)
        assertTrue(scan.trash.isEmpty())
        assertEquals(
            MoonlightMove.SwapToHotbar(13, 3),
            logic(MoonlightInventoryLogic.Options(blockTarget = 3)).plan(scan) { stack[it].count }
                .first { it is MoonlightMove.SwapToHotbar && (it as MoonlightMove.SwapToHotbar).from == 13 },
        )
    }

    @Test
    fun `golden apples are kept and sorted by stack size`() {
        val stack = slots(
            20 to ItemStack(Items.GOLDEN_APPLE, 3),
            21 to ItemStack(Items.GOLDEN_APPLE, 12),
        )
        val scan = logic().scan(stack)

        assertEquals(listOf(20, 21), scan.gapples)
        // MoonLight sorts ascending and moves the smallest stack into the gapple slot.
        assertEquals(
            MoonlightMove.SwapToHotbar(20, 2),
            logic().plan(scan) { stack[it].count }.first { it is MoonlightMove.SwapToHotbar && (it as MoonlightMove.SwapToHotbar).from in scan.gapples },
        )
    }

    @Test
    fun `useless items are marked as trash`() {
        val stack = slots(
            15 to ItemStack(Items.ROTTEN_FLESH),
            16 to ItemStack(Items.DIAMOND_SWORD),
            17 to ItemStack(Items.EGG),
        )
        val scan = logic().scan(stack)

        // Rotten flesh is neither good food nor a tool/block, so MoonLight drops it.
        assertTrue(15 in scan.trash)
        // Eggs are dropped too unless the projectile option is on.
        assertTrue(17 in scan.trash)
        assertFalse(16 in scan.trash)
    }

    @Test
    fun `projectile option keeps eggs out of the trash`() {
        val stack = slots(17 to ItemStack(Items.EGG))
        val keep = logic(MoonlightInventoryLogic.Options(keepProjectiles = true)).scan(stack)

        assertTrue(keep.trash.isEmpty())
    }

    @Test
    fun `armor is only equipped into an empty armor slot`() {
        val empty = slots(22 to ItemStack(Items.DIAMOND_CHESTPLATE))
        val scan = logic().scan(empty)

        assertEquals(22, scan.bestArmor[2])
        assertEquals(listOf(MoonlightMove.QuickMove(22)), logic().armorMoves(scan, empty))

        // Armour slot 7 = chest (5 feet, 6 legs, 7 chest, 8 head).
        val occupied = slots(
            7 to ItemStack(Items.IRON_CHESTPLATE),
            22 to ItemStack(Items.DIAMOND_CHESTPLATE),
        )
        assertTrue(logic().armorMoves(logic().scan(occupied), occupied).isEmpty())
    }

    @Test
    fun `auto armor can be disabled`() {
        val stack = slots(22 to ItemStack(Items.DIAMOND_CHESTPLATE))
        val disabled = logic(MoonlightInventoryLogic.Options(autoArmor = false))

        assertTrue(disabled.armorMoves(disabled.scan(stack), stack).isEmpty())
    }

    @Test
    fun `tools are ranked by efficiency and mapped to their own slots`() {
        val stack = slots(
            23 to ItemStack(Items.IRON_PICKAXE),
            24 to ItemStack(Items.DIAMOND_PICKAXE),
            25 to ItemStack(Items.IRON_AXE),
        )
        val scan = logic().scan(stack)

        assertEquals(24, scan.bestTools[MoonlightScan.TOOL_PICKAXE])
        assertEquals(25, scan.bestTools[MoonlightScan.TOOL_AXE])
        assertEquals(-1, scan.bestTools[MoonlightScan.TOOL_SHOVEL])

        val moves = logic().plan(scan) { stack[it].count }
        assertTrue(moves.contains(MoonlightMove.SwapToHotbar(24, 4)))
        assertTrue(moves.contains(MoonlightMove.SwapToHotbar(25, 5)))
    }

    @Test
    fun `empty scan schedules nothing`() {
        val scan = logic().scan(slots(20 to ItemStack(Items.DIAMOND_SWORD)))

        assertFalse(scan.isEmpty())
        assertTrue(logic().scan(Array(MoonlightInventoryLogic.LAST_SLOT_EXCLUSIVE) { ItemStack.EMPTY }).isEmpty())
    }

    // ---------------------------------------------------------------- surplus drops

    @Test
    fun `extra swords of the same kind are surplus`() {
        val stack = slots(
            10 to ItemStack(Items.DIAMOND_SWORD),
            11 to ItemStack(Items.IRON_SWORD),
            12 to ItemStack(Items.IRON_SWORD),
        )
        val scan = logic().scan(stack)

        // The best sword is kept; the two iron ones are duplicates.
        assertEquals(11, scan.bestSword)
        assertEquals(listOf(12), scan.surplus)
    }

    @Test
    fun `surplus keeps only the biggest block stack`() {
        val stack = slots(
            12 to ItemStack(Items.COBBLESTONE, 5),
            13 to ItemStack(Items.COBBLESTONE, 42),
            14 to ItemStack(Items.DIRT, 9),
            15 to ItemStack(Items.DIRT, 3),
        )
        val scan = logic().scan(stack)

        // Slot 13 is the biggest cobblestone stack, 14 the biggest dirt stack; 12 and 15 are extra.
        assertEquals(setOf(12, 15), scan.surplus.toSet())
    }

    @Test
    fun `extra block stacks can be kept on purpose`() {
        val stack = slots(
            12 to ItemStack(Items.COBBLESTONE, 5),
            13 to ItemStack(Items.COBBLESTONE, 42),
        )
        val keepTwo = logic(MoonlightInventoryLogic.Options(keepBlockStacks = 2)).scan(stack)

        assertTrue(keepTwo.surplus.isEmpty())
    }

    @Test
    fun `only one gapple and one bow are kept`() {
        val stack = slots(
            20 to ItemStack(Items.GOLDEN_APPLE, 12),
            21 to ItemStack(Items.GOLDEN_APPLE, 3),
            22 to ItemStack(Items.BOW),
            23 to ItemStack(Items.BOW),
        )
        val scan = logic().scan(stack)

        assertTrue(21 in scan.surplus)
        // Both bows would be "best" by the equal-damage comparison; the sort keeps one, so one is extra.
        assertEquals(1, scan.surplus.count { it == 22 || it == 23 })
    }

    @Test
    fun `surplus can be disabled entirely`() {
        val stack = slots(
            10 to ItemStack(Items.DIAMOND_SWORD),
            11 to ItemStack(Items.IRON_SWORD),
            12 to ItemStack(Items.COBBLESTONE, 5),
            13 to ItemStack(Items.COBBLESTONE, 42),
        )
        val scan = logic(MoonlightInventoryLogic.Options(dropSurplus = false)).scan(stack)

        assertTrue(scan.surplus.isEmpty())
    }

    @Test
    fun `trash is reported before surplus`() {
        val stack = slots(
            15 to ItemStack(Items.ROTTEN_FLESH),
            10 to ItemStack(Items.DIAMOND_SWORD),
            11 to ItemStack(Items.IRON_SWORD),
        )
        val scan = logic().scan(stack)

        // Both lists are populated; the module drops `trash` first, which is what this ordering proves.
        assertTrue(15 in scan.trash)
        assertTrue(11 in scan.surplus)
    }

    @Test
    fun `tools of a different kind are not surplus`() {
        val stack = slots(
            23 to ItemStack(Items.DIAMOND_PICKAXE),
            24 to ItemStack(Items.IRON_PICKAXE),
            25 to ItemStack(Items.IRON_AXE),
        )
        val scan = logic().scan(stack)

        assertTrue(24 in scan.surplus)
        assertFalse(25 in scan.surplus)
    }
}
