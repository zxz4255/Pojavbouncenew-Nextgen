/* GPL-3.0-only. Derived from NeoRaven b483ffc ItemUtils/BlockUtils; see licenses/neoraven/CHESTS.md. */
package net.ccbluex.liquidbounce.features.module.modules.player.neoraven

import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.tags.ItemTags
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.Blocks
import net.ccbluex.liquidbounce.features.module.modules.player.neoraven.NeoChestPolicy.Kind

/** Modern components/tags replace removed 1.8 ItemSword/ItemArmor subclasses; old scores remain. */
object NeoChestItems {
    private val excludedBlocks get() = setOf(Blocks.GRAVEL, Blocks.SAND, Blocks.SOUL_SAND, Blocks.TNT,
        Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.DISPENSER, Blocks.DROPPER, Blocks.NOTE_BLOCK, Blocks.COMMAND_BLOCK)

    private fun enchant(stack: ItemStack, path: String): Int = stack[DataComponents.ENCHANTMENTS]?.entrySet()
        ?.firstOrNull { it.key.unwrapKey().orElse(null)?.identifier()?.path == path }?.intValue ?: 0

    fun describe(stack: ItemStack): NeoChestPolicy.Item? {
        if (stack.isEmpty) return null
        val item = stack.item
        val attributes = stack[DataComponents.ATTRIBUTE_MODIFIERS]?.modifiers().orEmpty()
        val baseArmor = attributes.filter { it.attribute() == Attributes.ARMOR }.sumOf { it.modifier().amount() }
        val equip = stack[DataComponents.EQUIPPABLE]
        val kind = when {
            equip != null && baseArmor > 0 && equip.slot() in listOf(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET) -> Kind.ARMOR
            item is BlockItem -> Kind.BLOCK
            stack.`is`(Items.BOW) -> Kind.BOW
            stack.`is`(Items.ARROW) -> Kind.ARROW
            stack[DataComponents.FOOD] != null -> Kind.FOOD
            stack[DataComponents.POTION_CONTENTS] != null &&
                (stack.`is`(Items.POTION) || stack.`is`(Items.SPLASH_POTION) || stack.`is`(Items.LINGERING_POTION)) -> Kind.POTION
            stack.`is`(Items.ENDER_PEARL) -> Kind.PEARL
            stack.`is`(Items.EGG) -> Kind.EGG
            stack.`is`(Items.SNOWBALL) -> Kind.SNOWBALL
            stack.`is`(Items.FISHING_ROD) -> Kind.ROD
            stack.`is`(ItemTags.SWORDS) -> Kind.SWORD
            stack.`is`(ItemTags.PICKAXES) -> Kind.PICKAXE
            stack.`is`(ItemTags.AXES) -> Kind.AXE
            stack.`is`(ItemTags.SHOVELS) -> Kind.SHOVEL
            else -> Kind.OTHER
        }
        val score = when (kind) {
            Kind.ARMOR -> NeoChestPolicy.armorScore(baseArmor, enchant(stack,"protection"))
            Kind.SWORD -> NeoChestPolicy.swordScore(
                attributes.firstOrNull { it.attribute() == Attributes.ATTACK_DAMAGE }?.modifier()?.amount() ?: 0.0,
                enchant(stack,"sharpness"),enchant(stack,"fire_aspect"))
            Kind.BOW -> (enchant(stack,"power") + enchant(stack,"punch") + enchant(stack,"flame")).toDouble()
            Kind.PICKAXE, Kind.AXE, Kind.SHOVEL -> if (!stack.isDamageableItem || stack.maxDamage <= 0 ||
                (stack.maxDamage - stack.damageValue).toDouble()/stack.maxDamage >= .3)
                stack.getDestroySpeed(Blocks.STONE.defaultBlockState()).toDouble() else 0.0
            else -> 0.0
        }
        val block = (item as? BlockItem)?.block
        val placeable = block != null && (block == Blocks.GLASS || block !in excludedBlocks &&
            block.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
        return NeoChestPolicy.Item(kind,score,equip?.slot()?.name.orEmpty(),
            stack.`is`(Items.GOLDEN_APPLE) || stack.`is`(Items.ENCHANTED_GOLDEN_APPLE),placeable)
    }
}
