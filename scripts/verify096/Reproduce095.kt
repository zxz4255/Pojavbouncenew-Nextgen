package verify096baseline
import fixture.*
import net.ccbluex.liquidbounce.event.FixtureEvents
import net.ccbluex.liquidbounce.features.module.FixtureValue
import net.ccbluex.liquidbounce.features.module.modules.player.neoraven.ModuleNeoInvManager as M
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.item.*
import net.minecraft.world.entity.EquipmentSlot
@Suppress("UNCHECKED_CAST") fun <T> config(n:String,v:T){(M.values.getValue(n) as FixtureValue<T>).set(v)}
fun main(){
 fun setup(open:Boolean){
  M.enabled=false;mc.player=Player();mc.gameMode=GameMode();mc.level=Any()
  mc.gui.current=if(open)InventoryScreen(mc.player!!.inventoryMenu)else null
  config("StartDelay",0);config("ThrowTrash",false);M.enabled=true
 }
 setup(false);mc.player!!.inventoryMenu.getSlot(9).item=ItemStack(Items.DIAMOND_SWORD)
 repeat(30){mc.player!!.tickCount++;FixtureEvents.tick()}
 check(mc.gameMode!!.clicks.isEmpty())
 println("REPRODUCED 095: closed inventory emits ZERO clicks even with a misplaced sword.")
 setup(true);val m=mc.player!!.inventoryMenu
 m.getSlot(9).item=ItemStack(Items.armor(EquipmentSlot.HEAD,5.0));m.getSlot(10).item=ItemStack(Items.DIAMOND_SWORD)
 repeat(30){mc.player!!.tickCount++;FixtureEvents.tick()}
 check(mc.gameMode!!.clicks.isEmpty());check(m.getSlot(5).item.isEmpty);check(m.getSlot(36).item.isEmpty)
 println("REPRODUCED 095: open inventory, empty armor + scratch -> all armor submissions rejected, ZERO clicks; later sword sorting starves while module remains enabled.")
 setup(true);mc.player!!.inventoryMenu.getSlot(10).item=ItemStack(Items.DIAMOND_SWORD)
 FixtureEvents.tick();check(mc.gameMode!!.clicks.size==1)
 println("CONTROL 095: same hosts, open inventory WITHOUT armor blocker -> sword native click submitted. Not a generally disconnected fixture.")
}
