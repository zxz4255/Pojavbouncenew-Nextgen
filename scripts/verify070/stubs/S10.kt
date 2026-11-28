package net.ccbluex.liquidbounce.features.module.modules.combat
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
object ModuleSwordBlock {
 val hideShieldSlot = true
 @JvmStatic @JvmOverloads fun shouldAnimateSwordBlock(entity: LivingEntity, mainHandItem: ItemStack = entity.mainHandItem): Boolean = false
 fun shouldHideOffhand(offHandStack: ItemStack): Boolean = false
}
