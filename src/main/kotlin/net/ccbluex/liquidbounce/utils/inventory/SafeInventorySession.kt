/* GPL-3.0-or-later. 076 fail-closed inventory click transport shared by TS and Neo planners. */
package net.ccbluex.liquidbounce.utils.inventory

import net.ccbluex.liquidbounce.features.blink.BlinkManager
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleBlink
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.ccbluex.liquidbounce.utils.inventory.InventorySafetyPolicy.Click
import net.ccbluex.liquidbounce.utils.inventory.InventorySafetyPolicy.Image
import net.ccbluex.liquidbounce.utils.inventory.InventorySafetyPolicy.Mode

/** Never writes ItemStack/menu state or fabricates acknowledgements. All mutations go through vanilla. */
class SafeInventorySession(private val allowClosedInventory: Boolean = false) {
    private val gate = InventorySafetyGate()
    private val keys = ArrayList<ItemStack>()
    private var screen: InventoryScreen? = null
    private var world: Any? = null
    private var owner: Any? = null
    private val sequence = ArrayDeque<Click>()
    private var stageBefore: Image? = null
    val fault get() = gate.fault
    val busy get() = sequence.isNotEmpty()
    var waitReason: String = "Not started"
        private set
    private var delay = 250L
    fun reset() { gate.reset(); keys.clear(); screen = null; owner = null; world = null; sequence.clear(); stageBefore = null }
    private fun context(): InventoryMenu? {
        val player = mc.player ?: return null
        if (mc.level == null) return null
        val current = mc.gui.screen()
        if (current == null) {
            if (!allowClosedInventory) return null
        } else if (current !is InventoryScreen || current.menu !== player.inventoryMenu) return null
        // Never use a chest/workbench/creative menu, or an unrecognised slot layout, in the background.
        if (player.containerMenu !== player.inventoryMenu || player.inventoryMenu.slots.size != 46) return null
        return player.inventoryMenu
    }
    /** Returns false on any outstanding stage/settlement, manual cursor or unsafe screen. */
    fun ready(): Boolean {
        if (context() == null) { reset(); waitReason = "Open player inventory or close other screens"; return false }
        if (screen !== mc.gui.screen() || world !== mc.level || owner !== mc.player) {
            reset(); screen = mc.gui.screen() as? InventoryScreen; world = mc.level; owner = mc.player
        }
        waitReason = "Packet queue/Blink active"
        if (ModuleBlink.running || BlinkManager.isLagging) {
            if (busy) gate.fail("Packet queue/Blink interrupted a transaction; reopen inventory")
            return false
        }
        val player = mc.player ?: return false
        waitReason = "Native game mode unavailable"
        if (mc.gameMode == null) return false
        waitReason = "Player dead, spectator or using an item"
        if (!player.isAlive || player.isSpectator || player.isUsingItem) return false
        val image = image() ?: return false
        waitReason = "Waiting for click settlement"
        if (!gate.ready(image, System.currentTimeMillis(), player.tickCount)) {
            waitReason = gate.fault ?: waitReason
            return false
        }
        if (busy) {
            if (stageBefore != image) { gate.fail("Inventory changed between transaction stages; reopen inventory"); return false }
            if (!send(sequence.removeFirst())) {
                sequence.clear()
                gate.fail("Transaction stage could not be sent; reopen inventory")
            }
            return false
        }
        waitReason = if (image.cursor == null) "Ready" else "Put down the cursor item"
        return image.cursor == null
    }
    private fun image(): Image? {
        val menu = context() ?: return null
        fun describe(stack: ItemStack): InventorySafetyPolicy.Stack? {
            if (stack.isEmpty) return null
            var id = keys.indexOfFirst { ItemStack.isSameItemSameComponents(it, stack) }
            if (id < 0) { keys.add(stack.copyWithCount(1)); id = keys.lastIndex }
            return InventorySafetyPolicy.Stack(id, stack.count, stack.maxStackSize)
        }
        return try { Image(menu.slots.map { describe(it.item) }, describe(menu.carried)) }
        catch (_: IllegalArgumentException) { gate.fail("Invalid stack shape; no automatic click"); null }
    }
    /** End a fully settled successful pass; later legitimate pickups/edits may start a new one.
     * A fault or an outstanding transaction can never be cleared by this path. */
    fun completePass(): Boolean {
        if (busy || !ready()) return false
        gate.reset(); stageBefore = null
        return true
    }
    fun equip(source: Int, armor: Int, scratchHotbar: Int): List<Click>? =
        image()?.let { InventorySafetyPolicy.armorSwapPlan(it, source, armor, scratchHotbar) }
    fun submit(clicks: List<Click>, delayMs: Long = 250): Boolean {
        if (clicks.isEmpty() || !ready()) return false
        waitReason = "Plan rejected: unsupported or no-progress click"
        var prediction = image() ?: return false
        val before = prediction
        for (click in clicks) prediction = InventorySafetyPolicy.predict(prediction, click) ?: return false
        // All multi-stage transactions MUST end empty-handed and conserve every component-equivalent item.
        if (prediction.cursor != null || clicks.size > 4 ||
            (clicks.none { it.mode == Mode.THROW } && before.contents() != prediction.contents())) return false
        delay = maxOf(250, delayMs)
        sequence.addAll(clicks.drop(1))
        val sent = send(clicks.first())
        if (!sent) sequence.clear()
        waitReason = fault ?: if (sent) "Click submitted" else "Native click unavailable"
        return sent
    }
    private fun send(click: Click): Boolean {
        val current = context() ?: return false
        val player = mc.player ?: return false
        val gameMode = mc.gameMode ?: return false
        val before = image() ?: return false
        val after = InventorySafetyPolicy.predict(before, click) ?: return false
        val source = current.getSlot(click.slot)
        if (!source.mayPickup(player)) { gate.fail("Source slot disallows pickup"); return false }
        val incoming = if (click.mode == Mode.SWAP) current.getSlot(36 + click.button).item else current.carried
        if ((click.mode == Mode.SWAP || click.mode == Mode.PICKUP) && !incoming.isEmpty && !source.mayPlace(incoming)) {
            gate.fail("Destination slot disallows this item"); return false
        }
        if (click.mode == Mode.SWAP) {
            val target = current.getSlot(36 + click.button)
            val original = source.item
            // Native SWAP may eject overflow when a restricted slot cannot fit the incoming stack.
            // Reject BEFORE invoking vanilla; a post-click conservation assertion would be too late.
            if (!target.mayPickup(player) ||
                (!original.isEmpty && (!target.mayPlace(original) || original.count > target.getMaxStackSize(original))) ||
                (!incoming.isEmpty && incoming.count > source.getMaxStackSize(incoming))) {
                gate.fail("Restricted slot capacity; swap rejected before native click"); return false
            }
        }
        if (click.mode == Mode.PICKUP) {
            val predictedSlot = after.slots[click.slot]
            val sample = if (!incoming.isEmpty) incoming else source.item
            if (predictedSlot != null && predictedSlot.count > source.getMaxStackSize(sample)) {
                gate.fail("Restricted slot capacity; pickup rejected before native click"); return false
            }
        }
        if (!gate.begin(before, after, System.currentTimeMillis(), player.tickCount, delay)) return false
        val nativeMode = when (click.mode) {
            Mode.SWAP -> ContainerInput.SWAP
            Mode.PICKUP -> ContainerInput.PICKUP
            Mode.QUICK_MOVE -> ContainerInput.QUICK_MOVE
            Mode.THROW -> ContainerInput.THROW
        }
        try {
            gameMode.handleContainerInput(current.containerId, click.slot, click.button, nativeMode, player)
            val actual = image() ?: run { gate.fail("Container changed during click"); return false }
            gate.verify(actual)
            stageBefore = after
            InventoryManager.lastClickedSlot = click.slot
        } catch (_: Exception) { gate.fail("Native inventory click failed; no retry") }
        if (fault != null) sequence.clear()
        return fault == null
    }
    companion object {
        /** Named/server-custom items are not even shuffled automatically. */
        fun pinned(stack: ItemStack): Boolean = !stack.isEmpty &&
            (stack[DataComponents.CUSTOM_NAME] != null || stack[DataComponents.CUSTOM_DATA] != null ||
                stack[DataComponents.LORE]?.lines()?.isNotEmpty() == true)
        /** No unknown-item heuristic: explicit opt-in item list AND no per-stack component modifications. */
        fun mayDiscard(stack: ItemStack, allow: Set<Item>): Boolean = !stack.isEmpty && InventorySafetyPolicy.discardAllowed(stack.item in allow,
            !stack.componentsPatch.isEmpty, !stack.enchantments.isEmpty, stack.isDamageableItem,
            stack[DataComponents.CONTAINER] != null || stack[DataComponents.BUNDLE_CONTENTS] != null, pinned(stack))
    }
}
