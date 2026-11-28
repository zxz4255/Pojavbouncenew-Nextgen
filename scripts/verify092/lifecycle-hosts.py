#!/usr/bin/env python3
"""Full production Value/ToggleableValueGroup/ClientModule/Neo activation chain.
Only ValueGroup DSL facade and external services/events are hosts, not activation/setter methods.
"""
from pathlib import Path
import sys
R=Path(__file__).resolve().parents[2];W=Path(sys.argv[1]);W.mkdir(parents=True,exist_ok=True)
def put(n,s): (W/n).write_text(s)
# Old module/game adapters retained explicitly as external hosts.
for p in (R/'scripts/verify076/stubs').glob('*.kt'):
 if p.name in ['S1.kt','S3.kt','S4.kt','S6.kt','Client.kt','Events.kt','Value.kt']:continue
 s=p.read_text()
 if p.name in ['Cleaner.kt','ArmorModule.kt']:
  s=s.replace(' { val running = false }', ' : net.ccbluex.liquidbounce.features.module.ClientModule("'+p.stem+'", net.ccbluex.liquidbounce.features.module.ModuleCategories.PLAYER)')
 put(p.name,s)
put('Config.kt','''package net.ccbluex.liquidbounce.config
class OptionalInclusion
object ConfigSystem {const val KEY_PREFIX="liquidbounce"}
''')
put('AutoConfig.kt','''package net.ccbluex.liquidbounce.config.autoconfig
object AutoConfig {var loadingNow=false;val includeConfiguration=Inclusion()}
class Inclusion {val optionalInclusions=emptySet<net.ccbluex.liquidbounce.config.OptionalInclusion>();val includeBinds=true;val includeHidden=true}
''')
put('Annotations.kt','''package net.ccbluex.liquidbounce.config.gson.stategies
@Target(AnnotationTarget.PROPERTY,AnnotationTarget.FIELD,AnnotationTarget.VALUE_PARAMETER) annotation class Exclude
@Target(AnnotationTarget.PROPERTY,AnnotationTarget.FIELD,AnnotationTarget.VALUE_PARAMETER) annotation class ProtocolExclude
''')
put('Addon.kt','package net.ccbluex.liquidbounce.features.addon\nannotation class AddonApi\n')
put('Types.kt','''package net.ccbluex.liquidbounce.config.types
class Deserializer {fun deserializeThrowing(s:String):Any=s.toBoolean()}
enum class ValueType(val deserializer:Deserializer?=null) {INVALID,CONFIGURABLE,TOGGLEABLE,BOOLEAN(Deserializer()),INT,FLOAT,INT_RANGE,BIND,ITEM}
''')
put('Tagged.kt','''package net.ccbluex.liquidbounce.config.types.list
interface Tagged { val tag:String;val tagAliases:List<String> get()=emptyList() }
class MultiChoiceListValue<T>:net.ccbluex.liquidbounce.config.types.Value<MutableSet<T>>("multi",defaultValue=mutableSetOf(),valueType=net.ccbluex.liquidbounce.config.types.ValueType.INVALID) { val choices=emptyList<T>() }
''')
put('Groups.kt','''package net.ccbluex.liquidbounce.config.types.group
import net.ccbluex.liquidbounce.config.types.*
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.utils.input.InputBind
open class ValueGroup(name:String,value:MutableCollection<Value<*>> = mutableListOf(),valueType:ValueType=ValueType.CONFIGURABLE,aliases:List<String> = emptyList()):Value<MutableCollection<Value<*>>>(name,aliases,value,valueType){
 open val baseKey:String get()=name
 // Same production value ownership / registration, not no-op delegates.
 fun <V:Value<*>> value(value:V)=value.apply {this@ValueGroup.inner.add(this);this@ValueGroup.inclusionGroup?.let {inclusionGroup(it)}}
 fun <T:Any> value(name:String,defaultValue:T,valueType:ValueType=ValueType.INVALID,aliases:List<String> = emptyList())=value(Value(name,aliases,defaultValue,valueType))
 fun boolean(name:String,default:Boolean,aliases:List<String> = emptyList())=value(name,default,ValueType.BOOLEAN,aliases)
 fun int(name:String,default:Int,range:IntRange,suffix:String="")=value(name,default,ValueType.INT)
 fun float(name:String,default:Float,range:ClosedFloatingPointRange<Float>,suffix:String="")=value(name,default,ValueType.FLOAT)
 fun intRange(name:String,default:IntRange,range:IntRange,suffix:String="")=value(name,default,ValueType.INT_RANGE)
 fun items(name:String,default:MutableSet<net.minecraft.world.item.Item>)=value(name,default,ValueType.ITEM)
 fun color(name:String,default:net.ccbluex.liquidbounce.render.engine.type.Color4b)=value(name,default)
 inline fun <reified T> enumChoice(name:String,default:T):Value<T> where T:Enum<T>,T:net.ccbluex.liquidbounce.config.types.list.Tagged = value(name,default)
 fun bind(name:String,default:InputBind)=value(name,default,ValueType.BIND)
 fun key(name:String,default:Int)=value(name,com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD.getOrCreate(default))
 fun <T:ValueGroup> tree(group:T)=group.also { inner.add(it) }
 fun <T:Mode> modes(parent:EventListener,name:String,active:T,choices:Array<T>)=ModeValueGroup(name,active)
 fun <T:Mode> modes(parent:EventListener,name:String,activeIndex:Int,choices:(ModeValueGroup<T>)->Array<T>):ModeValueGroup<T> = error("unused mode factory")
 fun <T:Mode> modes(parent:EventListener,name:String,activeIndex:()->Int,choices:(ModeValueGroup<T>)->Array<T>):ModeValueGroup<T> = error("unused mode factory")
}
open class Mode(name:String):ValueGroup(name)
class ModeValueGroup<T:Mode>(name:String,val activeMode:T):ValueGroup(name)
''')
put('Events.kt','''package net.ccbluex.liquidbounce.event.events
import net.ccbluex.liquidbounce.event.Event
class ValueChangedEvent(val value:net.ccbluex.liquidbounce.config.types.Value<*>):Event
class ModuleActivationEvent(val moduleName:String):Event
class ModuleToggleEvent(val moduleName:String,val hidden:Boolean,val enabled:Boolean):Event
class NotificationEvent:Event {enum class Severity {ENABLED,DISABLED}}
object RefreshArrayListEvent:Event
class WorldChangeEvent:Event
class GameTickEvent:Event
class DisconnectEvent:Event
class RotationUpdateEvent:Event
class PlayerTickEvent:Event
enum class EventState {PRE,POST}
class PlayerNetworkMovementTickEvent(val state:EventState):Event
class PacketEvent(val packet:net.minecraft.network.protocol.Packet<*>):Event
class KeyboardKeyEvent(val screen:net.minecraft.client.gui.screens.Screen?=null,val scanCode:Int=0,val isRepeat:Boolean=false):Event
class MouseButtonEvent(val screen:net.minecraft.client.gui.screens.Screen?=null):Event
''')
put('Event.kt','''package net.ccbluex.liquidbounce.event
import kotlinx.coroutines.*
interface Event
interface EventListener {
 val running:Boolean get()=parent()?.running?:true
 fun parent():EventListener?=null
 val debugDisplayName:net.minecraft.util.FormattedCharSequence get()=net.minecraft.util.FormattedCharSequence.EMPTY
 val debugOwnerId:String get()="host"
}
class EventHook<T:Event>(val owner:EventListener,val fn:(T)->Unit)
object EventManager {
 val callbacks=mutableListOf<(Event)->Unit>()
 fun <T:Event> callEvent(e:T):T {callbacks.toList().forEach {it(e)};return e}
}
inline fun <reified T:Event> EventListener.handler(noinline fn:(T)->Unit):EventHook<T> = EventHook(this,fn)
private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
val EventListener.eventListenerScope:CoroutineScope get()=scope
fun EventListener.removeEventListenerScope() {}
''')
put('Input.kt','''package net.ccbluex.liquidbounce.utils.input
import net.ccbluex.liquidbounce.event.events.*
class InputBind(type:com.mojang.blaze3d.platform.InputConstants.Type = com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD,code:Int=0,val action:BindAction=BindAction.TOGGLE){
 enum class BindAction {TOGGLE,HOLD,SMART}
 fun matchesKeyRelease(e:KeyboardKeyEvent)=false;fun matchesKeyPress(e:KeyboardKeyEvent)=false
 fun matchesKey(i:Int)=false;fun matchesMouseRelease(e:MouseButtonEvent)=false;fun matchesMousePress(e:MouseButtonEvent)=false
}
''')
put('Category.kt','''package net.ccbluex.liquidbounce.features.module
class ModuleCategory(val tag:String,val inclusionGroup:net.ccbluex.liquidbounce.config.OptionalInclusion?=null)
object ModuleCategories {val PLAYER=ModuleCategory("Player");val MISC=ModuleCategory("Misc");val FUN=ModuleCategory("Fun");val RENDER=ModuleCategory("Render")}
''')
put('AntiBot.kt','''package net.ccbluex.liquidbounce.features.module.modules.misc.antibot
object ModuleAntiBot:net.ccbluex.liquidbounce.features.module.ClientModule("AntiBot",net.ccbluex.liquidbounce.features.module.ModuleCategories.MISC)
''')
put('Client.kt','''package net.ccbluex.liquidbounce.utils.client
var inGame=true
val mc:net.minecraft.client.Minecraft get()=error("No game access permitted during activation")
val logger:org.slf4j.Logger=org.slf4j.helpers.NOPLogger.NOP_LOGGER
fun clientLogger(name:String):org.slf4j.Logger=logger
fun notification(title:net.minecraft.network.chat.Component,name:String,severity:net.ccbluex.liquidbounce.event.events.NotificationEvent.Severity) {}
''')
put('Lang.kt','''package net.ccbluex.liquidbounce.lang
fun translation(key:String,vararg args:Any)=net.minecraft.network.chat.Component.literal(key)
object LanguageManager {fun hasFallbackTranslation(key:String)=true}
''')
put('Text.kt','''package net.ccbluex.liquidbounce.utils.text
fun String.toLowerCamelCase()=replaceFirstChar {it.lowercase()}
operator fun net.minecraft.network.chat.Style.plus(format:net.minecraft.ChatFormatting)=this
fun String.withFormat(style:net.minecraft.network.chat.Style):net.minecraft.util.FormattedCharSequence=net.minecraft.util.FormattedCharSequence.EMPTY
''')
print('Hosts: real Value setter/delegation, ToggleableValueGroup lifecycle/child traversal, ClientModule lifecycle/feedback and complete Neo code will compile unmodified. Game/event/DSL/config/bind services hosted.')
