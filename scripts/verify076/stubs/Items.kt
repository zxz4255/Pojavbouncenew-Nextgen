package net.ccbluex.liquidbounce.utils.item
import net.minecraft.world.item.ItemStack
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.effect.MobEffectInstance
val ItemStack.armorToughness get() = 0.0
val ItemStack.armorValue get() = 0.0
val ItemStack.attackDamage get() = 0.0
val ItemStack.durability get() = maxDamage - damageValue
val ItemStack.equipmentSlot: EquipmentSlot? get() = this[DataComponents.EQUIPPABLE]?.slot()
val ItemStack.foodComponent get() = this[DataComponents.FOOD]
fun ItemStack.getEnchantment(key: ResourceKey<Enchantment>): Int = 0
fun ItemStack.getEnchantmentCount() = 0
fun ItemStack.getPotionEffects(): Iterable<MobEffectInstance> = emptyList()
val ItemStack.isAxe get() = false
val ItemStack.isConsumable get() = false
val ItemStack.isFood get() = foodComponent != null
fun ItemStack.isFullBlock() = false
val ItemStack.isGlider get() = false
val ItemStack.isHoe get() = false
val ItemStack.isPickaxe get() = false
val ItemStack.isPlayerArmor get() = false
val ItemStack.isShovel get() = false
val ItemStack.isSpear get() = false
val ItemStack.isSword get() = false
