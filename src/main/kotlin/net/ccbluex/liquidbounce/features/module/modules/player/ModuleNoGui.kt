/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * Copyright (c) 2015 - 2026 CCBlueX
 * GPL-3.0-or-later; see the project LICENSE.
 */
package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.DeathEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.player.cheststealer.features.FeatureSilentScreen
import net.ccbluex.liquidbounce.utils.input.InputBind
import net.ccbluex.liquidbounce.utils.inventory.typeOrNull
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.MenuType

/** Keeps a selected container installed but invisible and routes mouse/game input to the world. */
object ModuleNoGui : ClientModule("GuiController", ModuleCategories.PLAYER, aliases = listOf("NoGui")) {
    private val playerInventory by boolean("PlayerInventory", true)
    private val chest by boolean("Chest", true)
    private val barrel by boolean("Barrel", true)
    private val enderChest by boolean("EnderChest", true)
    private val shulker by boolean("ShulkerBox", true)
    private val furnace by boolean("Furnace", true)
    private val smoker by boolean("Smoker", true)
    private val blastFurnace by boolean("BlastFurnace", true)
    private val hopper by boolean("Hopper", true)
    private val dispenser by boolean("Dispenser", true)
    private val crafting by boolean("CraftingTable", true)
    private val anvil by boolean("Anvil", true)
    private val enchanting by boolean("Enchanting", true)
    private val brewing by boolean("BrewingStand", true)
    private val cartography by boolean("CartographyTable", true)
    private val loom by boolean("Loom", true)
    private val stonecutter by boolean("Stonecutter", true)
    private val beacon by boolean("Beacon", true)

    private var controlledCursor = false

    /** Evaluated against the actual screen; no stale ScreenEvent cache or setScreen recursion. */
    @JvmStatic
    fun shouldHide(screen: Screen?): Boolean = running && matchesSettings(screen)

    @JvmStatic
    fun isHidingCurrentScreen(): Boolean = shouldHide(mc.gui.screen())

    private fun matchesSettings(screen: Screen?): Boolean {
        if (screen !is AbstractContainerScreen<*>) return false
        if (screen is InventoryScreen) return playerInventory
        return when (screen.menu.typeOrNull) {
            MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x4,
            MenuType.GENERIC_9x5, MenuType.GENERIC_9x6 -> chest
            MenuType.GENERIC_9x3 -> {
                val title = screen.title.string
                when (title) {
                    Component.translatable("container.enderchest").string -> enderChest
                    Component.translatable("container.barrel").string -> barrel
                    else -> chest
                }
            }
            MenuType.GENERIC_3x3 -> dispenser
            MenuType.SHULKER_BOX -> shulker
            MenuType.FURNACE -> furnace
            MenuType.SMOKER -> smoker
            MenuType.BLAST_FURNACE -> blastFurnace
            MenuType.HOPPER -> hopper
            MenuType.CRAFTING -> crafting
            MenuType.ANVIL -> anvil
            MenuType.ENCHANTMENT -> enchanting
            MenuType.BREWING_STAND -> brewing
            MenuType.CARTOGRAPHY_TABLE -> cartography
            MenuType.LOOM -> loom
            MenuType.STONECUTTER -> stonecutter
            MenuType.BEACON -> beacon
            else -> false
        }
    }

    private fun releaseCursorIfSafe() {
        val silentScreenNeedsMouse = FeatureSilentScreen.shouldHide && !FeatureSilentScreen.unlockCursor
        if (mc.gui.screen() != null && mc.mouseHandler.isMouseGrabbed && !silentScreenNeedsMouse) {
            mc.mouseHandler.releaseMouse()
        }
        controlledCursor = false
    }

    override fun onDisabled() {
        if (controlledCursor || matchesSettings(mc.gui.screen())) releaseCursorIfSafe()
    }

    // ModuleManager normally does not activate binds inside screens. This independent listener
    // handles only this module's TOGGLE bind while a container is open; no gameplay double toggle.
    private object BindListener : EventListener {
        @Suppress("unused")
        private val keyHandler = handler<KeyboardKeyEvent> { event ->
            if (event.screen !is AbstractContainerScreen<*> || ModuleNoGui.bind.action != InputBind.BindAction.TOGGLE) {
                return@handler
            }
            if (ModuleNoGui.bind.matchesKeyPress(event)) ModuleNoGui.enabled = !ModuleNoGui.enabled
        }
    }
    init { BindListener }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (isHidingCurrentScreen()) {
            controlledCursor = true
            if (!mc.mouseHandler.isMouseGrabbed) mc.mouseHandler.grabMouse()
        } else if (controlledCursor) {
            releaseCursorIfSafe()
        }
    }

    @Suppress("unused")
    private val deathHandler = handler<DeathEvent> { enabled = false }

    // The module handler may not run once a world is gone, so use a global listener.
    private object Lifecycle : EventListener {
        @Suppress("unused")
        private val worldHandler = handler<WorldChangeEvent> { ModuleNoGui.controlledCursor = false }
    }
    init { Lifecycle }
}
