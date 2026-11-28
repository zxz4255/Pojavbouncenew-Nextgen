package net.ccbluex.liquidbounce.utils.item.armor
import net.ccbluex.liquidbounce.utils.inventory.ItemSlot
import net.minecraft.world.entity.EquipmentSlot
class ArmorPiece(val itemSlot: ItemSlot)
object ArmorEvaluation { fun findBestArmorPieces(slots: List<ItemSlot>): Map<EquipmentSlot,ArmorPiece?> = emptyMap() }
