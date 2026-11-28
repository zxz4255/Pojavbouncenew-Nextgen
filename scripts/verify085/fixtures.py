#!/usr/bin/env python3
"""Small project-host fixtures; Minecraft classes are NEVER replaced in compile/API checks."""
from pathlib import Path
import sys
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
def put(name,package,body):
 p=out/(name+'.kt');p.write_text('package '+package+'\n'+body+'\n')
base='net.ccbluex.liquidbounce'
put('Value',base+'.config.types','''
import kotlin.reflect.KProperty
open class Value<T>(private var v:T) {
 fun get():T=v
 fun set(x:T){v=x}
 operator fun getValue(a:Any?,p:KProperty<*>):T=v
 fun onChange(f:(T)->T):Value<T> = this
}
''')
put('Tagged',base+'.config.types.list','''
import net.ccbluex.liquidbounce.config.types.Value
interface Tagged { val tag:String; companion object { fun String.asTagged():Tagged=object:Tagged{override val tag=this@asTagged} } }
class ChoiceListValue<T:Tagged>(val choices:Set<T>,default:T):Value<T>(default)
''')
put('ValueGroup',base+'.config.types.group','''
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.list.*
import net.ccbluex.liquidbounce.render.engine.type.Color4b
open class ValueGroup(val name:String) {
 val fixtureValues=linkedMapOf<String,Value<*>>()
 fun boolean(n:String,d:Boolean)=Value(d).also{fixtureValues[n]=it}
 fun float(n:String,d:Float,r:ClosedFloatingPointRange<Float>)=Value(d).also{fixtureValues[n]=it}
 fun int(n:String,d:Int,r:IntRange)=Value(d).also{fixtureValues[n]=it}
 fun <C:MutableCollection<String>> textList(n:String,d:C)=Value(d).also{fixtureValues[n]=it}
 fun color(n:String,d:Color4b)=Value(d).also{fixtureValues[n]=it}
 fun <T:Tagged> enumChoice(n:String,d:T,c:Set<T>)=ChoiceListValue(c,d).also{fixtureValues[n]=it}
 inline fun <reified T> enumChoice(n:String,d:T):ChoiceListValue<T> where T:Enum<T>,T:Tagged = enumChoice(n,d,enumValues<T>().toSet())
 fun <T:ValueGroup> tree(g:T)=g
}
''')
put('ClientModule',base+'.features.module','''
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.EventListener
open class ClientModule(n:String,c:ModuleCategories,disableOnQuit:Boolean=false):ValueGroup(n),EventListener {
 var enabled=false
 val running get()=enabled
 open val tag:String? get()=null
 open fun onEnabled(){}
 open fun onDisabled(){}
}
enum class ModuleCategories { COMBAT }
''')
put('Event',base+'.event','''
interface EventListener
open class Event
open class CancellableEvent:Event() { var isCancelled=false; fun cancelEvent(){isCancelled=true} }
enum class EventState { PRE, POST }
class EventHook<E>(val owner:EventListener,val fn:(E)->Unit)
object FixtureEvents { val hooks=mutableMapOf<String,MutableList<EventHook<*>>>() }
inline fun <reified E> EventListener.handler(priority:Short=0,noinline handler:(E)->Unit):EventHook<E> {
 val h=EventHook(this,handler);FixtureEvents.hooks.getOrPut(E::class.java.simpleName){mutableListOf()}.add(h);return h
}
''')
put('Events',base+'.event.events','''
import net.ccbluex.liquidbounce.event.*
import net.minecraft.network.protocol.Packet
import net.minecraft.world.InteractionHand
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
class WorldChangeEvent:Event()
class RotationUpdateEvent:Event()
class GameTickEvent:Event()
class GameRenderEvent:Event()
class ModuleToggleEvent(val moduleName:String,val hidden:Boolean,val enabled:Boolean):Event()
class PlayerNetworkMovementTickEvent(val state:EventState):Event()
enum class TransferOrigin { INCOMING,OUTGOING }
class PacketEvent(val origin:TransferOrigin,val packet:Packet<*>,val original:Boolean=true):CancellableEvent()
class PlayerInteractItemEvent(val hand:InteractionHand):CancellableEvent()
class PlayerUseMultiplier(var forward:Float,var sideways:Float):Event()
class WorldRenderEvent(val partialTicks:Float):Event()
''')
for name,pkg in [('ModuleMoonlightKillAura','combat'),('ModuleTs2KillAura','combat'),('ModuleKillAura','combat.killaura'),('ModuleScaffold','world.scaffold')]:
 put(name,base+'.features.module.modules.'+pkg,f'''import net.ccbluex.liquidbounce.features.module.*
object {name}:ClientModule("{'Aura' if name=='ModuleTs2KillAura' else name.removeprefix('Module')}",ModuleCategories.COMBAT)''')
put('ModuleAntiBot',base+'.features.module.modules.misc.antibot','''import net.minecraft.world.entity.Entity
object ModuleAntiBot { fun isBot(e:Entity)=false }''')
put('FriendManager',base+'.features.misc','''import net.minecraft.world.entity.Entity
object FriendManager { fun isFriend(e:Entity)=false }''')
put('Client',base+'.utils.client','''import net.minecraft.client.Minecraft
val mc:Minecraft get()=Minecraft.getInstance()''')
put('Rotation',base+'.utils.aiming.data','''data class Rotation(val yaw:Float,val pitch:Float,val isNormalized:Boolean=false)''')
put('MovementCorrection',base+'.utils.aiming.features','''enum class MovementCorrection { OFF,SILENT,STRICT,CHANGE_LOOK }''')
put('Priority',base+'.utils.kotlin','''enum class Priority { IMPORTANT_FOR_USAGE_2 }''')
put('RotationManager',base+'.utils.aiming','''
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.event.EventListener
import net.minecraft.world.entity.Entity
class RotationTarget(val rotation:Rotation,var entity:Entity?=null,val movementCorrection:MovementCorrection=MovementCorrection.SILENT,val considerInventory:Boolean=false)
object RotationManager { var serverRotation=Rotation(0f,0f);var activeRotationTarget:RotationTarget?=null
 fun setRotationTarget(p:RotationTarget,priority:Priority,provider:EventListener){activeRotationTarget=p} }
''')
put('ItemExtensions',base+'.utils.item','''
import net.minecraft.world.item.ItemStack
import net.minecraft.tags.ItemTags
val net.minecraft.core.TypedInstance<net.minecraft.world.item.Item>.isSword get()=this.`is`(ItemTags.SWORDS)
val net.minecraft.core.TypedInstance<net.minecraft.world.item.Item>.isMiningTool get()=this.`is`(ItemTags.AXES)||this.`is`(ItemTags.PICKAXES)||this.`is`(ItemTags.SHOVELS)||this.`is`(ItemTags.HOES)
val net.minecraft.world.item.ItemInstance.attackDamage get()=1.0
''')
put('Swing',base+'.utils.block','''import net.minecraft.world.InteractionHand
enum class SwingMode { HIDE_CLIENT,DO_NOT_HIDE;fun swing(hand:InteractionHand){} }''')
put('Combat',base+'.utils.combat','''import net.minecraft.world.entity.Entity
import net.ccbluex.liquidbounce.utils.block.SwingMode
fun attackEntity(e:Entity,swing:SwingMode,keepSprint:Boolean=false)=true''')
put('Network',base+'.utils.network','''import net.minecraft.network.protocol.Packet
fun sendPacketSilently(p:Packet<*>){}''')
put('Entity',base+'.utils.entity','''import net.minecraft.world.entity.Entity
fun Entity.interpolateCurrentPosition(t:Float)=position()''')
put('Color',base+'.render.engine.type','''data class Color4b(val argb:Int) {
 fun with(a:Int)=Color4b((argb and 0xffffff) or (a shl 24))
 companion object { val TRANSPARENT=Color4b(0) }
}
data class Vec3f(val x:Float,val y:Float,val z:Float)''')
put('Render',base+'.render','''
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.render.engine.type.*
import net.minecraft.world.phys.*
class WorldRenderEnvironment
fun WorldRenderEvent.renderEnvironment(f:WorldRenderEnvironment.()->Unit){WorldRenderEnvironment().f()}
fun WorldRenderEnvironment.withPositionRelativeToCamera(p:Vec3,f:WorldRenderEnvironment.()->Unit){f()}
fun WorldRenderEnvironment.drawBox(box:AABB,faceColor:Color4b?,outlineColor:Color4b?){}
fun WorldRenderEnvironment.drawLineStrip(argb:Int,vararg positions:Vec3f){}
''')
put('Theme',base+'.features.module.modules.misc.music.onyx.material','''class Scheme { val primary=0;val primaryContainer=0 }
object Theme { fun scheme()=Scheme() }''')

put('InputTracker',base+'.utils.input',"""import net.minecraft.client.KeyMapping
object InputTracker { val KeyMapping.isPressedOnAny get()=isDown }""")
put('CombatManager',base+'.utils.combat',"object CombatManager { var shouldPauseCombat=false }")
