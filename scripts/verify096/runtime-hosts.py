"""Explicit deterministic game/DSL fixtures. Production module, item adapter, transport and policy stay whole.
The separate compile-api.sh verifies real MC26.3 symbols; these fixtures are NOT a Minecraft runtime.
"""
from pathlib import Path
import sys
w=Path(sys.argv[1]);w.mkdir(parents=True,exist_ok=True)
def put(n,s):(w/n).write_text(s)
put('Event.kt','''package net.ccbluex.liquidbounce.event
interface EventListener {val running:Boolean get()=true}
object FixtureEvents {
 val ticks=mutableListOf<Pair<EventListener,()->Unit>>()
 fun tick(){ticks.toList().forEach{(o,f)->if(o.running)f()}}
}
inline fun <reified T> EventListener.handler(noinline fn:(T)->Unit):Any {
 if(T::class.java==net.ccbluex.liquidbounce.event.events.GameTickEvent::class.java)
  FixtureEvents.ticks.add(this to {fn(net.ccbluex.liquidbounce.event.events.GameTickEvent() as T)})
 return Any()
}
''')
put('Events.kt','''package net.ccbluex.liquidbounce.event.events
class GameTickEvent
class WorldChangeEvent
''')
put('Module.kt','''package net.ccbluex.liquidbounce.features.module
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.utils.input.InputBind
import kotlin.reflect.KProperty
class FixtureValue<T>(var v:T){
 fun get()=v;fun set(x:T){v=x}
 operator fun getValue(o:Any?,p:KProperty<*>)=v
 fun onChange(fn:(T)->T)=this
 fun notAnOption()=this
}
open class ClientModule(val name:String,category:Any,aliases:List<String> = emptyList()):EventListener {
 val values=mutableMapOf<String,FixtureValue<*>>()
 val enabledValue=FixtureValue(false)
 var enabled:Boolean get()=enabledValue.get();set(v){if(v!=enabled){if(v)onEnabled() else onDisabled();enabledValue.set(v)}}
 override val running get()=enabled && net.ccbluex.liquidbounce.utils.client.mc.player!=null && net.ccbluex.liquidbounce.utils.client.mc.level!=null
 open val externalBind=false
 val bindValue=FixtureValue(InputBind());val bind get()=bindValue.get()
 open val tag=""
 open fun onEnabled(){};open fun onDisabled(){}
 fun <T> value(n:String,v:T)=FixtureValue(v).also{values[n]=it}
 fun boolean(n:String,v:Boolean)=value(n,v)
 fun int(n:String,v:Int,r:IntRange,s:String="")=value(n,v)
 fun items(n:String,v:MutableSet<net.minecraft.world.item.Item>)=value(n,v)
 fun literalDescription(fn:()->String){}
}
object ModuleCategories{val PLAYER=Any()}
object ModuleManager{val modulesConfig=Any()}
''')
put('Config.kt','''package net.ccbluex.liquidbounce.config
object ConfigSystem{fun store(config:Any){}}
''')
put('Input.kt','''package net.ccbluex.liquidbounce.utils.input
data class InputBind(val action:BindAction=BindAction.TOGGLE){enum class BindAction{HOLD,TOGGLE,SMART}}
''')
put('Client.kt','''package net.ccbluex.liquidbounce.utils.client
import fixture.*
val mc=Client()
object logger{fun warn(s:String,e:Throwable){};fun error(s:String,e:Throwable){throw AssertionError(s,e)}}
''')
put('Collection.kt','''package net.ccbluex.liquidbounce.utils.collection
fun itemSortedSetOf():MutableSet<net.minecraft.world.item.Item> = linkedSetOf()
''')
put('Neo.kt','''package net.ccbluex.liquidbounce.features.module.modules.player.neoraven
object NeoInvHotkeys
object NeoInvDiagnostics {
 val notes=mutableListOf<String>();val lastSummary get()=notes.lastOrNull().orEmpty()
 fun install(v:Any){}
 fun note(stage:String,detail:String,includeStack:Boolean=false){notes.add("$stage $detail")}
}
''')
put('Cleaner.kt','''package net.ccbluex.liquidbounce.features.module.modules.player.invcleaner
object ModuleInventoryCleaner:net.ccbluex.liquidbounce.features.module.ClientModule("Cleaner",Any())
''')
put('Armor.kt','''package net.ccbluex.liquidbounce.features.module.modules.combat.autoarmor
object ModuleAutoArmor:net.ccbluex.liquidbounce.features.module.ClientModule("Armor",Any())
''')
put('Aura.kt','''package net.ccbluex.liquidbounce.features.module.modules.combat.killaura
object ModuleKillAura{var running=false;val targetTracker=Tracker()}
class Tracker{var target:Any?=null}
''')
put('Scaffold.kt','''package net.ccbluex.liquidbounce.features.module.modules.world.scaffold
object ModuleScaffold{var running=false}
''')
put('Blink.kt','''package net.ccbluex.liquidbounce.features.blink
object BlinkManager{var isLagging=false}
''')
put('ModuleBlink.kt','''package net.ccbluex.liquidbounce.features.module.modules.player
object ModuleBlink{var running=false}
''')
put('Manager.kt','''package net.ccbluex.liquidbounce.utils.inventory
object InventoryManager{var lastClickedSlot=-1}
''')
put('Screen.kt','''package net.minecraft.client.gui.screens
open class Screen
''')
put('InventoryScreen.kt','''package net.minecraft.client.gui.screens.inventory
class InventoryScreen(val menu:net.minecraft.world.inventory.InventoryMenu):net.minecraft.client.gui.screens.Screen()
''')
put('Menu.kt','''package net.minecraft.world.inventory
import fixture.Player
import net.minecraft.world.item.*
enum class ContainerInput{SWAP,PICKUP,QUICK_MOVE,THROW}
class Slot(val id:Int){
 var item=ItemStack.EMPTY;var pickup=true;var place=true;var capacity=64
 fun mayPickup(p:Player)=pickup
 fun mayPlace(s:ItemStack)=place && (id !in 5..8 || s[net.minecraft.core.component.DataComponents.EQUIPPABLE]?.slot()?.name==listOf("HEAD","CHEST","LEGS","FEET")[id-5])
 fun getMaxStackSize(s:ItemStack)=minOf(capacity,s.maxStackSize)
}
class InventoryMenu{
 val slots=MutableList(46){Slot(it).also{s->if(it in 5..8)s.capacity=1}}
 var carried=ItemStack.EMPTY;val containerId=0
 fun getSlot(id:Int)=slots[id]
}
''')
put('Core.kt','''package net.minecraft.core
class BlockPos{companion object{val ZERO=BlockPos()}}
''')
put('Equipment.kt','''package net.minecraft.world.entity
enum class EquipmentSlot{HEAD,CHEST,LEGS,FEET,MAINHAND,OFFHAND}
''')
put('Attributes.kt','''package net.minecraft.world.entity.ai.attributes
object Attributes{val ARMOR="armor";val ATTACK_DAMAGE="attack_damage"}
''')
put('Empty.kt','''package net.minecraft.world.level
object EmptyBlockGetter{val INSTANCE=this}
''')
put('Blocks.kt','''package net.minecraft.world.level.block
class Block(val name:String){
 fun defaultBlockState()=this
 fun isCollisionShapeFullBlock(g:Any,p:Any)=true
}
object Blocks{
 val GRAVEL=Block("gravel");val SAND=Block("sand");val SOUL_SAND=Block("soul_sand");val TNT=Block("tnt")
 val CRAFTING_TABLE=Block("crafting_table");val FURNACE=Block("furnace");val DISPENSER=Block("dispenser");val DROPPER=Block("dropper")
 val NOTE_BLOCK=Block("note_block");val COMMAND_BLOCK=Block("command_block");val GLASS=Block("glass");val STONE=Block("stone")
}
''')
put('Tags.kt','''package net.minecraft.tags
class Tag(val name:String)
object ItemTags{val SWORDS=Tag("sword");val PICKAXES=Tag("pickaxe");val AXES=Tag("axe");val SHOVELS=Tag("shovel")}
''')
put('Components.kt','''package net.minecraft.core.component
import fixture.*
class Key<T>
object DataComponents{
 val CUSTOM_NAME=Key<String>();val CUSTOM_DATA=Key<String>();val LORE=Key<Lore>()
 val ENCHANTMENTS=Key<Enchantments>();val EQUIPPABLE=Key<Equippable>();val ATTRIBUTE_MODIFIERS=Key<Modifiers>()
 val FOOD=Key<String>();val POTION_CONTENTS=Key<String>();val CONTAINER=Key<String>();val BUNDLE_CONTENTS=Key<String>()
}
''')
put('ComponentsTypes.kt','''package fixture
import java.util.Optional
import net.minecraft.world.entity.EquipmentSlot
class Lore(val contents:List<String>){fun lines()=contents}
data class Equippable(val type:EquipmentSlot){fun slot()=type}
class Enchantments(val values:Map<String,Int> = emptyMap()){
 val isEmpty get()=values.isEmpty()
 fun entrySet()=values.map{EnchantEntry(EnchantKey(it.key),it.value)}
}
class EnchantEntry(val key:EnchantKey,val intValue:Int)
class EnchantKey(val path:String){fun unwrapKey()=Optional.of(this);fun identifier()=this}
class Modifiers(val values:List<Attribute>){fun modifiers()=values}
class Attribute(val key:String,val value:Double){fun attribute()=key;fun modifier()=this;fun amount()=value}
''')
put('Items.kt','''package net.minecraft.world.item
import net.minecraft.tags.*
import net.minecraft.core.component.*
import fixture.*
class Patch(val isEmpty:Boolean)
open class Item(val name:String,val max:Int=64,val tags:Set<Tag> = emptySet(),val defaults:Map<Key<*>,Any> = emptyMap())
class BlockItem(val block:net.minecraft.world.level.block.Block):Item(block.name)
class ItemStack(val item:Item,var count:Int=1,val custom:Map<Key<*>,Any> = emptyMap(),val damageValue:Int=0){
 val isEmpty get()=count<=0 || item===Items.AIR
 val maxStackSize get()=item.max
 val maxDamage get()=if(item.max==1)200 else 0
 val isDamageableItem get()=maxDamage>0
 val componentsPatch get()=Patch(custom.isEmpty())
 val enchantments get()=this[DataComponents.ENCHANTMENTS]?:Enchantments()
 operator fun <T> get(k:Key<T>):T?=(custom[k]?:item.defaults[k]) as T?
 fun `is`(i:Item)=item===i
 fun `is`(t:Tag)=t in item.tags
 fun copyWithCount(c:Int)=ItemStack(item,c,custom,damageValue)
 fun getDestroySpeed(b:Any)=5f
 override fun toString()=if(isEmpty)"empty" else "${item.name}:$count:$damageValue:$custom"
 companion object{
  val EMPTY get()=ItemStack(Items.AIR,0)
  fun isSameItemSameComponents(a:ItemStack,b:ItemStack)=a.item===b.item && a.custom==b.custom && a.damageValue==b.damageValue
 }
}
object Items{
 val AIR=Item("air");val FIRE_CHARGE=Item("fire_charge");val SHEARS=Item("shears",1)
 val BOW=Item("bow",1);val ARROW=Item("arrow");val POTION=Item("potion",1);val SPLASH_POTION=Item("splash",1)
 val LINGERING_POTION=Item("lingering",1);val ENDER_PEARL=Item("pearl",16);val EGG=Item("egg",16);val SNOWBALL=Item("snowball",16)
 val FISHING_ROD=Item("rod",1);val GOLDEN_APPLE=Item("gapple",64,defaults=mapOf(DataComponents.FOOD to "food"))
 val ENCHANTED_GOLDEN_APPLE=Item("egapple",64,defaults=mapOf(DataComponents.FOOD to "food"))
 val DIAMOND_SWORD=Item("diamond_sword",1,setOf(ItemTags.SWORDS),mapOf(DataComponents.ATTRIBUTE_MODIFIERS to Modifiers(listOf(Attribute("attack_damage",7.0)))))
 val IRON_SWORD=Item("iron_sword",1,setOf(ItemTags.SWORDS),mapOf(DataComponents.ATTRIBUTE_MODIFIERS to Modifiers(listOf(Attribute("attack_damage",6.0)))))
 val STONE=BlockItem(net.minecraft.world.level.block.Blocks.STONE)
 fun armor(type:net.minecraft.world.entity.EquipmentSlot,score:Double)=Item("armor_${type}_$score",1,defaults=mapOf(DataComponents.EQUIPPABLE to Equippable(type),DataComponents.ATTRIBUTE_MODIFIERS to Modifiers(listOf(Attribute("armor",score)))))
}
''')
put('Fixture.kt','''package fixture
import net.minecraft.client.gui.screens.Screen
import net.minecraft.world.inventory.*
import net.minecraft.world.item.*
class Gui{var current:Screen?=null;fun screen()=current}
class Player{
 val inventoryMenu=InventoryMenu();var containerMenu=inventoryMenu
 var isAlive=true;var isSpectator=false;var isUsingItem=false;var tickCount=0;var closes=0
 fun closeContainer(){closes++;net.ccbluex.liquidbounce.utils.client.mc.gui.current=null}
}
data class NativeClick(val slot:Int,val button:Int,val mode:ContainerInput)
class GameMode{
 val clicks=mutableListOf<NativeClick>();var intercept:((NativeClick,Player)->Boolean)?=null
 fun handleContainerInput(id:Int,slot:Int,button:Int,mode:ContainerInput,p:Player){
  val c=NativeClick(slot,button,mode);clicks.add(c)
  if(intercept?.invoke(c,p)==true)return
  val menu=p.containerMenu
  // Independent modeled native operation; never calls InventorySafetyPolicy.predict().
  when(mode){
   ContainerInput.SWAP->{val src=menu.getSlot(slot);val dst=menu.getSlot(36+button);val old=src.item;src.item=dst.item;dst.item=old}
   ContainerInput.THROW->menu.getSlot(slot).item=ItemStack.EMPTY
   else->error("Fixture supports only the real operations used by Neo096")
  }
 }
}
class Client{
 var player:Player?=Player();var level:Any?=Any();var gameMode:GameMode?=GameMode();val gui=Gui()
}
''')
print('Runtime game/DSL fixtures generated. Not real game/native input/network.')
