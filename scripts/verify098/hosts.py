"""Explicit project-service compile hosts. Minecraft classes are NEVER stubbed.
This is targeted source/API compilation, not a full build or gameplay test.
"""
from pathlib import Path
import sys
w=Path(sys.argv[1]);w.mkdir(parents=True,exist_ok=True)
files={
'Config.kt':'''package net.ccbluex.liquidbounce.config.types.group
import kotlin.reflect.KProperty
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.event.EventListener
class Value<T>(var v:T){operator fun getValue(a:Any?,p:KProperty<*>)=v;fun onChange(f:(T)->T)=this}
open class ValueGroup(val name:String):MinecraftShortcuts,EventListener {
 fun <T> tree(v:T)=v
 fun boolean(n:String,v:Boolean)=Value(v)
 fun float(n:String,v:Float,r:ClosedFloatingPointRange<Float>)=Value(v)
 fun int(n:String,v:Int,r:IntRange,s:String="")=Value(v)
 fun floatRange(n:String,v:ClosedFloatingPointRange<Float>,r:ClosedFloatingPointRange<Float>)=Value(v)
 fun <T> enumChoice(n:String,v:T,choices:Any?=null)=Value(v)
 fun <T> multiEnumChoice(n:String,v:T)=Value(v)
 fun blocks(n:String,v:MutableSet<net.minecraft.world.level.block.Block>)=Value(v)
 fun vec3d(n:String,useLocateButton:Boolean)=Value(net.minecraft.world.phys.Vec3.ZERO)
 fun <T:Mode> choices(n:String,v:T,a:Array<T>)=ModeValueGroup<T>(n,v)
 fun <T:Mode> modes(p:Any,n:String,i:Int,f:(ModeValueGroup<T>)->Array<T>)=ModeValueGroup<T>(n, TODO())
 fun tagBy(v:Any){}
 fun doNotIncludeAlways(){}
}
open class Mode(n:String):ValueGroup(n){open val parent:ModeValueGroup<*> get()=error("host")}
class ModeValueGroup<T:Mode>(n:String,val activeMode:T):ValueGroup(n)
open class ToggleableValueGroup(val parent:Any,n:String,var enabled:Boolean):ValueGroup(n){open val running get()=enabled;open fun onDisabled(){}}
''',
'Tagged.kt':'''package net.ccbluex.liquidbounce.config.types.list
interface Tagged{val tag:String}
''',
'Module.kt':'''package net.ccbluex.liquidbounce.features.module
import net.ccbluex.liquidbounce.config.types.group.*
object ModuleCategories {val PLAYER=Any()}
open class ClientModule(n:String,c:Any):ValueGroup(n){var enabled=false;open val running get()=enabled}
''',
'Events.kt':'''package net.ccbluex.liquidbounce.event
interface EventListener
inline fun <reified T> EventListener.handler(priority:Int=0,noinline f:(T)->Unit)=f
fun EventListener.tickHandler(f:suspend ()->Unit)=f
suspend fun tickConditional(t:Int,f:java.util.function.BooleanSupplier)=f.asBoolean
suspend fun waitTicks(t:Int){}
''',
'EventTypes.kt':'''package net.ccbluex.liquidbounce.event.events
import net.ccbluex.liquidbounce.utils.inventory.*
import net.ccbluex.liquidbounce.utils.kotlin.Priority
class ScheduleInventoryActionEvent {
 fun schedule(c:InventoryConstraints,a:InventoryAction,p:Priority=Priority.NORMAL){}
 fun schedule(c:InventoryConstraints,a:List<InventoryAction>,p:Priority=Priority.NORMAL){}
}
class RotationUpdateEvent
class WorldChangeEvent
class PacketEvent(val packet:net.minecraft.network.protocol.Packet<*>){val isCancelled=false}
class ScreenEvent(val screen:net.minecraft.client.gui.screens.Screen?)
class OverlayRenderEvent(val context:net.minecraft.client.gui.GuiGraphicsExtractor)
''',
'Inventory.kt':'''package net.ccbluex.liquidbounce.utils.inventory
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.ItemStack
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
interface ItemSlot{val itemStack:ItemStack;val slotType:Type;fun getIdForServer(s:AbstractContainerScreen<*>?):Int?;enum class Type{CONTAINER,HOTBAR,INVENTORY}}
class ContainerItemSlot(val slotInContainer:Int):ItemSlot{override val itemStack get()=ItemStack.EMPTY;override val slotType=ItemSlot.Type.CONTAINER;override fun getIdForServer(s:AbstractContainerScreen<*>?)=slotInContainer;fun distance(s:ContainerItemSlot):Int {val a=slotInContainer;val b=s.slotInContainer;return (a%9-b%9)*(a%9-b%9)+(a/9-b/9)*(a/9-b/9)}}
class HotbarItemSlot:ItemSlot{override val itemStack get()=ItemStack.EMPTY;override val slotType=ItemSlot.Type.HOTBAR;override fun getIdForServer(s:AbstractContainerScreen<*>?)=0;val canBeSwapTarget=true;val isOffHand=false}
object Slots{val HotbarAndInventory=emptyList<ItemSlot>();val Inventory=emptyList<ItemSlot>()}
class InventoryConstraints:ValueGroup("Constraints")
class CheckScreenHandlerTypeValueGroup(p:Any):ValueGroup("CheckScreenHandlerType"){fun isValid(s:AbstractContainerScreen<*>)=true}
class CheckScreenTitleValueGroup(p:Any):ValueGroup("CheckScreenTitle"){fun isValid(s:Screen)=true}
fun AbstractContainerScreen<*>.findItemsInContainer()=emptyList<ContainerItemSlot>()
fun AbstractContainerScreen<*>.getSlotsInContainer()=emptyList<ContainerItemSlot>()
fun findNonEmptySlotsInInventory()=emptyList<ItemSlot>()
fun Iterable<ItemSlot>.mergeableCapacityFor(s:ItemStack,blacklist:Collection<ItemSlot>?=null)=0
fun findBlocksEndingWith(vararg n:String)=mutableSetOf<net.minecraft.world.level.block.Block>()
sealed interface InventoryAction {
 class CloseScreen(val screen:AbstractContainerScreen<*>):InventoryAction
 class Click(val screen:AbstractContainerScreen<*>,val slot:ItemSlot):InventoryAction{companion object{
 fun performQuickMove(s:AbstractContainerScreen<*>,f:ItemSlot)=Click(s,f)
 fun performPickup(s:AbstractContainerScreen<*>,f:ItemSlot)=Click(s,f)
 fun performThrow(s:AbstractContainerScreen<*>,f:ItemSlot)=Click(s,f)
 fun performSwap(s:AbstractContainerScreen<*>,f:ItemSlot,t:HotbarItemSlot)=Click(s,f)
 }}
}
''',
'Cleaner.kt':'''package net.ccbluex.liquidbounce.features.module.modules.player.invcleaner
import net.ccbluex.liquidbounce.utils.inventory.*
import net.ccbluex.liquidbounce.utils.kotlin.Priority
class ItemAndComponents(val item:net.minecraft.world.item.Item)
class InventorySwap(val from:ItemSlot,val to:ItemSlot,val priority:Priority)
class InventoryCleanupPlan(val usefulItems:MutableSet<ItemSlot>,val swaps:MutableList<InventorySwap>,val mergeableItems:MutableMap<ItemAndComponents,MutableList<ItemSlot>>){fun findItemsToThrowOut(s:List<ItemSlot>)=emptyList<ItemSlot>()}
class CleanupPlanGenerator(t:Any,s:List<ItemSlot>){fun generatePlan()=InventoryCleanupPlan(hashSetOf(),mutableListOf(),hashMapOf())}
object ModuleInventoryCleaner{val running=false;val cleanupTemplateFromSettings=Any()}
class CategoryType(val allocationPriority:Priority)
class Category(val type:CategoryType)
class Facet(val category:Category)
object ItemCategorization{object Default{fun getItemFacets(s:ItemSlot)=listOf<Facet>()}}
''',
'Item.kt':'''package net.ccbluex.liquidbounce.utils.item
fun net.minecraft.world.item.ItemStack.isMergeable(o:net.minecraft.world.item.ItemStack)=false
''',
'Fastutil.kt':'''package net.ccbluex.fastutil
inline fun <reified T:Enum<T>> enumSetOf():java.util.EnumSet<T> = java.util.EnumSet.noneOf(T::class.java)
fun <T> java.util.ArrayList<T>.swap(a:Int,b:Int){java.util.Collections.swap(this,a,b)}
''',
'Priority.kt':'''package net.ccbluex.liquidbounce.utils.kotlin
enum class Priority{NORMAL}
object EventPriorityConvention{const val READ_FINAL_STATE=0}
fun ClosedFloatingPointRange<Float>.random()=start
''',
'Rotation.kt':'''package net.ccbluex.liquidbounce.utils.aiming
class Rotation
object RotationManager{val serverRotation=Rotation()}
class RotationsValueGroup(p:Any):net.ccbluex.liquidbounce.config.types.group.ValueGroup("Rotations")
''',
'RotationFind.kt':'''package net.ccbluex.liquidbounce.utils.aiming.utils
import net.minecraft.world.phys.Vec3
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
fun selectBlockTarget(e:Vec3,r:Float,w:Float,c:Iterable<Pair<BlockPos,BlockState>>,v:net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup,p:net.ccbluex.liquidbounce.features.module.ClientModule,considerInventory:Boolean=true,rotate:Boolean=true):BlockPos?=null
''',
'Blocks.kt':'''package net.ccbluex.liquidbounce.utils.block
import net.minecraft.core.*
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
val BlockPos.state:BlockState? get()=null
fun BlockState?.anotherChestPartDirection():Direction?=null
fun Vec3.searchBlocksInRangeSorted(r:Float,f:(BlockPos,BlockState)->Boolean)=emptyList<Pair<BlockPos,BlockState>>()
enum class SwingMode{DO_NOT_HIDE;fun interactSwing(h:net.minecraft.world.InteractionHand){};companion object{val INTERACT_CHOICES=entries.toTypedArray()}}
''',
'Combat.kt':'''package net.ccbluex.liquidbounce.utils.combat
object CombatManager{val isInCombat=false}
''',
'Entity.kt':'''package net.ccbluex.liquidbounce.utils.entity
fun net.minecraft.world.InteractionResult.Success.shouldSwingHand()=false
''',
'Ray.kt':'''package net.ccbluex.liquidbounce.utils.raytracing
fun raytraceBlock(r:Double,rot:net.ccbluex.liquidbounce.utils.aiming.Rotation,p:net.minecraft.core.BlockPos,s:net.minecraft.world.level.block.state.BlockState):net.minecraft.world.phys.BlockHitResult?=null
''',
'Math.kt':'''package net.ccbluex.liquidbounce.utils.math
val net.minecraft.core.BlockPos.center:net.minecraft.world.phys.Vec3 get()=net.minecraft.world.phys.Vec3.atCenterOf(this)
fun net.minecraft.core.BlockPos.toVec3d(x:Double,y:Double,z:Double)=net.minecraft.world.phys.Vec3(x+this.x,y+this.y,z+this.z)
''',
'Vec.kt':'''package net.ccbluex.liquidbounce.render.engine.type
class Vec3f(val x:Float,val y:Float,val z:Float)
''',
'Projector.kt':'''package net.ccbluex.liquidbounce.utils.render
object WorldToScreen{fun calculateScreenPos(v:net.minecraft.world.phys.Vec3):net.ccbluex.liquidbounce.render.engine.type.Vec3f?=null}
''',
'TagRenderer.kt':'''package net.ccbluex.liquidbounce.render.gui
import net.ccbluex.liquidbounce.config.types.group.Mode
class ItemStackListRenderer{class BackgroundMode:Mode("Background"){companion {fun backgroundChoices(parent:net.ccbluex.liquidbounce.config.types.group.ModeValueGroup<*>)=arrayOf(BackgroundMode())}}}
class ItemStackListRenderState(s:List<net.minecraft.world.item.ItemStack>){fun title(t:net.minecraft.network.chat.Component?)=this;fun centerX(v:Float)=this;fun centerY(v:Float)=this;fun scale(v:Float)=this;fun background(m:Mode)=this;fun draw(g:net.minecraft.client.gui.GuiGraphicsExtractor){}}
'''
}
for n,s in files.items():(w/n).write_text(s)
