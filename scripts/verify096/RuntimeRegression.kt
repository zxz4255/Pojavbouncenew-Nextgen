package verify096

import fixture.*
import net.ccbluex.liquidbounce.event.FixtureEvents
import net.ccbluex.liquidbounce.features.module.FixtureValue
import net.ccbluex.liquidbounce.features.module.modules.player.neoraven.ModuleNeoInvManager as M
import net.ccbluex.liquidbounce.features.module.modules.player.neoraven.NeoInvDiagnostics
import net.ccbluex.liquidbounce.utils.inventory.InventorySafetyPolicy as P
import net.ccbluex.liquidbounce.utils.inventory.SafeInventorySession
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.inventory.*
import net.minecraft.world.item.*
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.core.component.DataComponents as D
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleBlink
import net.ccbluex.liquidbounce.features.blink.BlinkManager
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.player.invcleaner.ModuleInventoryCleaner
import net.ccbluex.liquidbounce.features.module.modules.combat.autoarmor.ModuleAutoArmor

var assertions=0
fun ok(v:Boolean,msg:String=""){assertions++;check(v){"#$assertions $msg tag=${M.tag}"}}
@Suppress("UNCHECKED_CAST") fun <T> config(n:String,v:T){(M.values.getValue(n) as FixtureValue<T>).set(v)}
fun fixture(open:Boolean=true):InventoryMenu{
 M.enabled=false
 mc.player=Player();mc.level=Any();mc.gameMode=GameMode();mc.gui.current=if(open) InventoryScreen(mc.player!!.inventoryMenu) else null
 ModuleBlink.running=false;BlinkManager.isLagging=false;ModuleScaffold.running=false;ModuleKillAura.running=false;ModuleKillAura.targetTracker.target=null
 ModuleInventoryCleaner.enabled=false;ModuleAutoArmor.enabled=false
 config("InventoryOnly",false);config("ProtectCustomItems",true);config("AutoArmor",true);config("AutoClose",false)
 config("StartDelay",0);config("MinDelay",0);config("MaxDelay",0);config("OnlySortOnce",false);config("ThrowTrash",false)
 config("DiscardItems",linkedSetOf<Item>())
 M.enabled=true
 return mc.player!!.inventoryMenu
}
fun settleClock(){
 // Test clock acceleration ONLY: expire the real deadline, retain all image/fault/2-distinct-tick checks.
 val f=M.javaClass.getDeclaredField("session").apply{isAccessible=true};val s=f.get(M)?:return
 val g=s.javaClass.getDeclaredField("gate").apply{isAccessible=true}.get(s)
 g.javaClass.getDeclaredField("settleAt").apply{isAccessible=true}.setLong(g,0)
}
fun tick(fast:Boolean=true){mc.player?.let{it.tickCount++};FixtureEvents.tick();if(fast)settleClock()}
fun drive(n:Int=60){repeat(n){tick()}}
fun clicks()=mc.gameMode!!.clicks
fun content(menu:InventoryMenu)=menu.slots.map{it.item}.filter{!it.isEmpty}.groupingBy{it.toString()}.eachCount()
fun put(menu:InventoryMenu,slot:Int,item:Item,count:Int=1,custom:Map<net.minecraft.core.component.Key<*>,Any> = emptyMap()){menu.getSlot(slot).item=ItemStack(item,count,custom)}
fun main(){
 // Reproduce both reported no-op paths with the COMPLETE event→module→adapter→transport chain.
 for(open in listOf(true,false))for(occupiedArmor in listOf(false,true))for(occupiedScratch in listOf(false,true)){
  val m=fixture(open);val helmet=Items.armor(EquipmentSlot.HEAD,5.0)
  put(m,9,helmet);put(m,10,Items.DIAMOND_SWORD);put(m,11,Items.STONE,64)
  if(occupiedArmor)put(m,5,Items.armor(EquipmentSlot.HEAD,1.0))
  if(occupiedScratch)put(m,36,Items.ARROW,24)
  val before=content(m);drive()
  ok(m.getSlot(5).item.item===helmet,"armor open=$open armor=$occupiedArmor scratch=$occupiedScratch")
  ok(m.getSlot(36).item.item===Items.DIAMOND_SWORD,"sword starvation")
  ok(m.getSlot(37).item.item===Items.STONE,"block")
  ok(m.carried.isEmpty && content(m)==before,"no loss / cursor")
  ok(clicks().isNotEmpty() && clicks().all{it.mode==ContainerInput.SWAP},"native swap calls")
  ok(M.enabled && M.tag.contains("Done"),"completion")
 }
 println("PASS: complete modeled tick→classification→armor plan→transport→independent native SWAP→hotbar convergence, open/closed × armor/scratch empty/full.")
 // Hotbar armor sources, all four armor slots, inventory-only restriction.
 for((index,type) in listOf(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET).withIndex()){
  val m=fixture(false);val a=Items.armor(type,5.0);put(m,40,a);val before=content(m);drive()
  ok(m.getSlot(5+index).item.item===a && content(m)==before && m.carried.isEmpty)
 }
 run{
  val m=fixture(false);put(m,9,Items.DIAMOND_SWORD);config("InventoryOnly",true);drive();ok(clicks().isEmpty())
  mc.gui.current=InventoryScreen(m);drive();ok(m.getSlot(36).item.item===Items.DIAMOND_SWORD)
 }
 // New inventory contents after a completed pass must not hit the previous image's permanent latch.
 run{
  val m=fixture(false);put(m,9,Items.IRON_SWORD);drive();val n=clicks().size
  put(m,12,Items.DIAMOND_SWORD);drive()
  ok(m.getSlot(36).item.item===Items.DIAMOND_SWORD && clicks().size>n)
 }
 // Unsafe contexts have no native click. On release, normal sorting resumes.
 val pauses=listOf<(InventoryMenu)->Unit>(
  {mc.gui.current=Screen()}, {mc.player!!.containerMenu=InventoryMenu()}, {it.carried=ItemStack(Items.STONE)},
  {ModuleBlink.running=true},{BlinkManager.isLagging=true},{mc.player!!.isUsingItem=true},
  {mc.player!!.isSpectator=true},{mc.player!!.isAlive=false},{ModuleScaffold.running=true},
  {ModuleKillAura.running=true;ModuleKillAura.targetTracker.target=Any()},
  {ModuleInventoryCleaner.enabled=true},{ModuleAutoArmor.enabled=true}, {mc.gameMode=null}
 )
 for(pause in pauses){
  // Simulate a competing module enabled AFTER Neo's one-time ownership handoff.
  val m2=fixture(false)
  val field=M.javaClass.getDeclaredField("acquirePending").apply{isAccessible=true};field.setBoolean(M,false)
  put(m2,9,Items.DIAMOND_SWORD);val before=content(m2);pause(m2);drive(8)
  ok(mc.gameMode?.clicks?.isEmpty()!=false && content(m2)==before,"pause made a click")
  mc.gui.current=null;mc.player!!.containerMenu=m2;m2.carried=ItemStack.EMPTY
  ModuleBlink.running=false;BlinkManager.isLagging=false;ModuleScaffold.running=false
  ModuleKillAura.running=false;ModuleInventoryCleaner.enabled=false;ModuleAutoArmor.enabled=false
  mc.player!!.isUsingItem=false;mc.player!!.isSpectator=false;mc.player!!.isAlive=true
  if(mc.gameMode==null)mc.gameMode=GameMode()
  drive();ok(m2.getSlot(36).item.item===Items.DIAMOND_SWORD,"resume after pause")
 }
 // Named stacks stay pinned by default; user opt-out affects moving only, never discard protection.
 run{
  val m=fixture(false);put(m,9,Items.DIAMOND_SWORD,custom=mapOf(D.CUSTOM_NAME to "server sword"));drive()
  ok(clicks().isEmpty() && M.tag.contains("protected"))
  config("ProtectCustomItems",false);drive();ok(m.getSlot(36).item.item===Items.DIAMOND_SWORD)
  ok(!SafeInventorySession.mayDiscard(m.getSlot(36).item,setOf(Items.DIAMOND_SWORD)))
 }
 // Unplannable armor no longer returns before hotbar sorting.
 run{
  val m=fixture(false);put(m,9,Items.armor(EquipmentSlot.HEAD,5.0));m.getSlot(5).pickup=false;put(m,10,Items.DIAMOND_SWORD);drive()
  ok(m.getSlot(36).item.item===Items.DIAMOND_SWORD && M.tag.contains("Blocked"))
 }
 // Cancellation/divergence are faults, not an endless automatic click storm. Toggle/reopen explicitly recovers.
 for(diverge in listOf(false,true)){
  val m=fixture(false);put(m,9,Items.DIAMOND_SWORD)
  mc.gameMode!!.intercept={_,_->if(diverge)put(m,12,Items.STONE);true};drive()
  ok(clicks().size==1 && M.enabled && M.tag.contains("Paused"),"native no-op fault")
  mc.gameMode!!.intercept=null;mc.gui.current=InventoryScreen(m);drive()
  ok(m.getSlot(36).item.item===Items.DIAMOND_SWORD,"explicit reopen recovery")
 }
 // Pending rollback cannot be erased by completePass; neither can an outstanding multi-stage plan.
 run{
  fixture(false);val m=mc.player!!.inventoryMenu;put(m,9,Items.DIAMOND_SWORD)
  val s=SafeInventorySession(true);ok(s.submit(listOf(P.Click(9,0,P.Mode.SWAP)),0))
  ok(!s.completePass());val old=m.getSlot(36).item;m.getSlot(36).item=m.getSlot(9).item;m.getSlot(9).item=old
  ok(!s.ready() && s.fault!=null && !s.completePass())
 }
 // Transport-level default stays inventory-only, and every unsafe native capacity fails BEFORE a click.
 run{
  val m=fixture(false);put(m,9,Items.DIAMOND_SWORD)
  val strict=SafeInventorySession();ok(!strict.ready());mc.gui.current=InventoryScreen(m);ok(strict.ready())
  mc.gui.current=null;m.getSlot(36).capacity=0
  val s=SafeInventorySession(true);ok(!s.submit(listOf(P.Click(9,0,P.Mode.SWAP))))
  ok(clicks().isEmpty() && s.fault!=null && !s.completePass())
 }
 // Blink in the middle of an armor transaction must not let the remaining stage leak out on resume.
 run{
  val m=fixture(false);put(m,9,Items.armor(EquipmentSlot.HEAD,5.0));tick();ok(clicks().size==1)
  ModuleBlink.running=true;tick();ModuleBlink.running=false;drive()
  ok(clicks().size==1 && M.tag.contains("Paused"))
 }
 // World/player identity changes discard the old cursorless plan and rebuild for the new player only.
 run{
  val m=fixture(false);put(m,9,Items.armor(EquipmentSlot.HEAD,5.0));tick();ok(clicks().size==1)
  mc.player=Player();mc.level=Any();val next=mc.player!!.inventoryMenu;put(next,11,Items.DIAMOND_SWORD)
  drive();ok(next.getSlot(36).item.item===Items.DIAMOND_SWORD && next.getSlot(5).item.isEmpty)
 }
 // OnlySortOnce intentionally blocks a second hotbar pass within the same session.
 run{
  val m=fixture(false);config("OnlySortOnce",true);put(m,9,Items.IRON_SWORD);drive();val n=clicks().size
  put(m,12,Items.DIAMOND_SWORD);drive();ok(clicks().size==n)
  mc.gui.current=InventoryScreen(m);drive();ok(m.getSlot(36).item.item===Items.DIAMOND_SWORD)
 }
 // AutoClose only closes an actually open player inventory, never calls closeContainer every background tick.
 run{val m=fixture(false);config("AutoClose",true);drive();ok(mc.player!!.closes==0)
  mc.gui.current=InventoryScreen(m);drive(4);ok(mc.player!!.closes==1)}
 // Empty discard list is NOT permission to throw. Explicit, plain safe stack does throw.
 run{
  val m=fixture(false);config("ThrowTrash",true);val trash=Item("trash");put(m,9,trash,12);drive()
  ok(clicks().isEmpty() && M.tag.contains("allowlist"))
  config("DiscardItems",linkedSetOf(trash));drive();ok(m.getSlot(9).item.isEmpty && clicks().single().mode==ContainerInput.THROW)
 }
 // A real-time (not accelerated) settlement run: second stage waits >=250ms and 2 distinct ticks.
 run{
  val m=fixture(false);val a=Items.armor(EquipmentSlot.HEAD,5.0);put(m,9,a)
  tick(false);ok(clicks().size==1);repeat(4){tick(false)};ok(clicks().size==1,"delay skipped")
  Thread.sleep(270);tick(false);ok(clicks().size==2)
  repeat(4){tick(false)};ok(!M.tag.contains("Done"));Thread.sleep(270);tick(false);tick(false)
  ok(m.getSlot(5).item.item===a && M.tag.contains("Done"))
 }
 ok(NeoInvDiagnostics.notes.any{it.startsWith("ACTION ")})
 println("PASS: $assertions full production module/transport runtime-fixture assertions. Game/DSL/native swap are explicit models, NOT actual Minecraft/Pojav/server acceptance. Fast cases accelerate only wall-clock deadlines; one real 250ms settlement run included.")
}
