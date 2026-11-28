#!/usr/bin/env python3
from pathlib import Path
import sys
out=Path(sys.argv[1])/'fixtures';out.mkdir(parents=True,exist_ok=True)
def put(n,s):(out/n).write_text(s)
put('Annotations.kt','''package net.ccbluex.liquidbounce.config.gson.stategies
annotation class Exclude
annotation class ProtocolExclude
''')
put('Addon.kt','''package net.ccbluex.liquidbounce.features.addon
annotation class AddonApi
''')
put('Config.kt','''package net.ccbluex.liquidbounce.config
enum class OptionalInclusion { ALL }
''')
put('AutoConfig.kt','''package net.ccbluex.liquidbounce.config.autoconfig
import net.ccbluex.liquidbounce.config.OptionalInclusion
object AutoConfig { val includeConfiguration=Include() }
class Include { val optionalInclusions=emptySet<OptionalInclusion>() }
''')
put('Events.kt','''package net.ccbluex.liquidbounce.event
object EventManager { var events=0; fun callEvent(event:Any) {events++} }
''')
put('ValueEvent.kt','''package net.ccbluex.liquidbounce.event.events
class ValueChangedEvent(val value:Any)
''')
put('Lang.kt','''package net.ccbluex.liquidbounce.lang
class Component(val string:String)
fun translation(key:String)=Component(key)
''')
put('Strings.kt','''package net.ccbluex.liquidbounce.utils.text
fun String.toLowerCamelCase()=replaceFirstChar { it.lowercase() }
''')
put('Client.kt','''package net.ccbluex.liquidbounce.utils.client
object logger { var errors=0; fun error(message:String,error:Throwable) { errors++;throw AssertionError(message,error) } }
object mc { val keyboardHandler=Keyboard() }
class Keyboard { var clipboard:String="" }
''')
put('OtherValues.kt','''package net.ccbluex.liquidbounce.config.types
class BindValue:Value<String>("bind",defaultValue="",valueType=ValueType.BIND)
class CurveValue:Value<String>("curve",defaultValue="",valueType=ValueType.CURVE)
class FileValue:Value<String>("file",defaultValue="",valueType=ValueType.FILE)
class RangedValue<T:Any>(default:T):Value<T>("range",defaultValue=default,valueType=ValueType.INT)
''')
put('OtherLists.kt','''package net.ccbluex.liquidbounce.config.types.list
import net.ccbluex.liquidbounce.config.types.*
interface Tagged { val tag:String }
class MultiChoiceListValue<T:Tagged>:Value<MutableSet<T>>("multi",defaultValue=mutableSetOf(),valueType=ValueType.MULTI_CHOOSE) { val choices=emptyList<T>() }
class ChoiceListValue<T>:Value<String>("choice",defaultValue="",valueType=ValueType.CHOOSE)
class ItemListValue<T:MutableSet<E>,E>(value:T):Value<T>("items",defaultValue=value,valueType=ValueType.NAMED_ITEM_LIST)
class RegistryListValue<T:java.util.SequencedSet<E>,E>(value:T):Value<T>("registry",defaultValue=value,valueType=ValueType.REGISTRY_LIST)
class RegistryMutableListValue<T:MutableList<E>,E>(value:T):Value<T>("registryMutable",defaultValue=value,valueType=ValueType.REGISTRY_MUTABLE_LIST)
''')
put('Deserializer.kt','''package net.ccbluex.liquidbounce.utils.input
object HumanInputDeserializer {
    class StringDeserializer<T>(val parser:(String)->T) { fun deserializeThrowing(s:String):T=parser(s) }
    val textDeserializer=StringDeserializer { it }
    fun <T> parseArray(s:String,d:StringDeserializer<T>):MutableList<T> = s.split(',').map {d.deserializeThrowing(it)}.toMutableList()
}
''')
put('InputConstants.kt','''package com.mojang.blaze3d.platform
object InputConstants {
    class Key
    const val MOUSE_BUTTON_LEFT=0
    const val KEY_ESCAPE=256
    const val KEY_RETURN=257
    const val KEY_NUMPADENTER=335
    const val KEY_BACKSPACE=259
    const val KEY_DELETE=261
    const val KEY_LEFT=263
    const val KEY_RIGHT=262
    const val KEY_HOME=268
    const val KEY_END=269
}
''')
put('InputEvents.kt','''package net.minecraft.client.input
class CharacterEvent(private val text:String) { fun codepointAsString()=text }
class PreeditEvent(private val text:String) { fun fullText()=text }
class KeyEvent(private val k:Int,private val shift:Boolean=false,private val ctrl:Boolean=false,private val action:String="") {
 fun key()=k
 fun hasShiftDown()=shift
 fun hasControlDownWithQuirk()=ctrl
 fun isSelectAll()=action=="all"
 fun isCopy()=action=="copy"
 fun isCut()=action=="cut"
 fun isPaste()=action=="paste"
}
''')
put('Graphics.kt','''package net.minecraft.client.gui
class GuiGraphicsExtractor
''')
put('Color.kt','''package net.ccbluex.liquidbounce.render.engine.type
class Color4b
''')
put('LayoutStore.kt','''package net.ccbluex.liquidbounce.ui.clickgui
import kotlin.reflect.KProperty
object ClickGuiLayoutStore {
 fun booleanState(key:()->String?,field:String,default:Boolean=false)=State(default)
 fun scrollState(key:()->String?,field:String)=State(0f)
}
class State<T>(private var current:T) { operator fun getValue(a:Any?,p:KProperty<*>)=current;operator fun setValue(a:Any?,p:KProperty<*>,v:T){current=v} }
''')
put('Draw.kt','''package net.ccbluex.liquidbounce.ui.common
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.exp
enum class UiFontWeight {REGULAR,MEDIUM,BOLD}
object UiAnimation {fun expLerp(a:Float,b:Float,speed:Float,dt:Float)=b+(a-b)*exp(-speed*dt)}
object UiTextUtils {fun displayName(s:String)=s}
object UiLocalization {val isChinese=true;fun text(en:String,cn:String)=cn}
object UiPalette {
 const val TEXT_COLOR=0;const val TEXT_DIMMED_COLOR=1;const val TEXT_MUTED_COLOR=2;const val ERROR_COLOR=3
 val inputBorder=4;val inputBg=5
 fun accent(f:Float)=6;fun surface(f:Float)=7;fun white(f:Float)=8;fun lerpArgb(a:Int,b:Int,t:Float)=a
}
object UiIcons {const val RESET="reset";const val SETTINGS_EXPAND="expand";const val CROSS="cross"}
object UiTooltipManager {fun request(text:String,anchorRightX:Float,centerY:Float,fallbackLeftX:Float,canvasWidth:Float){}}
object UiDraw {
 val texts=ArrayList<String>()
 fun measureText(text:String,size:Float,weight:UiFontWeight=UiFontWeight.REGULAR)=text.length*size*0.5f
 fun ellipsize(text:String,width:Float,size:Float,weight:UiFontWeight)=text
 fun drawText(ctx:GuiGraphicsExtractor,text:String,x:Float,y:Float,size:Float,argb:Int,weight:UiFontWeight=UiFontWeight.REGULAR,italic:Boolean=false){texts.add(text)}
 fun fillBox(ctx:GuiGraphicsExtractor,x:Float,y:Float,w:Float,h:Float,argb:Int,radius:Float=0f,borderColor:Int=0,borderWidth:Float=0f){}
 fun drawTextFieldContent(ctx:GuiGraphicsExtractor,state:UiTextEditState,x:Float,y:Float,w:Float,h:Float,fontSizePx:Float,placeholder:String=""){}
 fun drawIcon(ctx:GuiGraphicsExtractor,icon:String,x:Float,y:Float,w:Float,h:Float,argb:Int,rotationRad:Float=0f){}
 fun withCssScissor(ctx:GuiGraphicsExtractor,x:Float,y:Float,w:Float,h:Float,block:()->Unit)=block()
}
''')
# Unrelated dispatch targets are explicit no-op nodes, never tested as actual widget implementations.
put('OtherNodes.kt','''package net.ccbluex.liquidbounce.ui.clickgui.setting
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.ui.common.UiTextEditState
import net.minecraft.client.gui.GuiGraphicsExtractor
object SettingOverlayManager {
 var focused:UiTextEditState?=null
 fun focusTextInput(s:UiTextEditState,selectAll:Boolean){focused?.unfocus();focused=s;s.focus(selectAll)}
}
open class OtherNode(v:Value<*>):SettingNode(v) {
 override fun measureHeight(width:Float)=24f
 override fun render(ctx:GuiGraphicsExtractor,x:Float,y:Float,width:Float,mouseX:Float,mouseY:Float,dtSeconds:Float,canvasWidth:Float)=24f
}
'''+''.join('class '+name+'(v:Value<*>'+args+'):OtherNode(v)\n' for name,args in [
('BooleanSettingNode',''),('SingleSliderSettingNode',',isInteger:Boolean'),('RangeSliderSettingNode',',isInteger:Boolean'),
('ColorSettingNode',''),('ChooseSettingNode',''),('MultiChooseSettingNode',''),('ChoiceModeGroupNode',''),('ToggleableGroupNode',''),('ConfigurableGroupNode',''),('BindSettingNode',''),('KeySettingNode',''),('FileSettingNode',''),('VectorSettingNode',',dimensions:Int,integerMode:Boolean'),('CurveSettingNode',''),('NamedItemListSettingNode',''),('RegistryListSettingNode',''),('RegistryMutableListSettingNode','')]))
