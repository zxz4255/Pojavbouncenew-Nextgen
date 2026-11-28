package net.ccbluex.liquidbounce.features.module.modules.render
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
object ModuleOpaiDynamicIsland { val running = false }
object OpaiChestLoot {
 fun onCandidates(screen: AbstractContainerScreen<*>, candidates: List<Int>) {}
 fun isReplacing(screen: AbstractContainerScreen<*>): Boolean = false
}
