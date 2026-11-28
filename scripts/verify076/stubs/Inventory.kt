package net.ccbluex.liquidbounce.utils.inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
interface ItemSlot { val itemStack: ItemStack; fun getIdForServer(s: AbstractContainerScreen<*>?): Int? }
class Slots<T : ItemSlot>(val inner: List<T>) : List<T> by inner {
 operator fun plus(other: Slots<*>) = Slots<ItemSlot>(inner + other.inner)
 companion object {
  val All = Slots<ItemSlot>(emptyList()); val Inventory = All; val Hotbar = All; val Armor = All; val HotbarAndInventory = All
 }
}
object InventoryManager { var lastClickedSlot: Int = 0 }
val net.minecraft.world.inventory.AbstractContainerMenu.isPlayerInventory get() = this is net.minecraft.world.inventory.InventoryMenu
