#!/usr/bin/env python3
"""Project-only hosts; actual MinecraftShortcuts/AddonApi and Minecraft/RenderPearl/JOML stay real."""
from pathlib import Path
import sys
out=Path(sys.argv[1]);root=Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
def put(name,pkg,body):(out/(name+'.kt')).write_text('package '+pkg+'\n'+body+'\n')
b='net.ccbluex.liquidbounce'
put('Tagged',b+'.config.types.list','interface Tagged {val tag:String}')
put('ClientModule',b+'.features.module','''
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.config.types.list.Tagged
import kotlin.reflect.KProperty
class Value<T>(val value:T) { operator fun getValue(o:Any?,p:KProperty<*>):T=value }
open class ClientModule(val name:String,category:ModuleCategories):EventListener,MinecraftShortcuts {
 var enabled=false
 val running get()=enabled
 open fun onEnabled(){}
 open fun onDisabled(){}
 fun boolean(n:String,v:Boolean)=Value(v)
 fun float(n:String,v:Float,r:ClosedFloatingPointRange<Float>)=Value(v)
 fun int(n:String,v:Int,r:IntRange)=Value(v)
 fun text(n:String,v:String)=Value(v)
 inline fun <reified T> enumChoice(n:String,v:T):Value<T> where T:Enum<T>,T:Tagged =Value(v)
}
enum class ModuleCategories { RENDER }
''')
put('Events',b+'.event','''
interface EventListener
open class Event
open class CancellableEvent:Event() {var isCancelled=false}
class EventHook<T>
inline fun <reified T> EventListener.handler(priority:Short=0,noinline handler:(T)->Unit)=EventHook<T>()
''')
put('EventTypes',b+'.event.events','''
import net.ccbluex.liquidbounce.event.*
import net.minecraft.world.entity.Entity
import net.minecraft.network.protocol.Packet
import net.minecraft.client.gui.GuiGraphicsExtractor
class AttackEntityEvent(val entity:Entity):CancellableEvent()
class GameTickEvent:Event()
class WorldChangeEvent:Event()
class OverlayRenderEvent(val context:GuiGraphicsExtractor,val tickDelta:Float):Event()
enum class TransferOrigin { INCOMING, OUTGOING }
class PacketEvent(val origin:TransferOrigin,val packet:Packet<*>,val original:Boolean=true):CancellableEvent()
''')
put('Client',b+'.utils.client','''
import net.minecraft.client.Minecraft
val mc:Minecraft get()=Minecraft.getInstance()
val inGame:Boolean get()=mc.player!=null && mc.level!=null
''')
put('FriendManager',b+'.features.misc','''
import net.minecraft.world.entity.Entity
object FriendManager {fun isFriend(e:Entity)=false}
''')
put('Priority',b+'.utils.kotlin','object EventPriorityConvention {const val READ_FINAL_STATE:Short = -1000}')
# Compile hosts preserve exact current UiDraw public parameter lists, not fake game types.
s=(root/'src/main/kotlin/net/ccbluex/liquidbounce/ui/common/UiDraw.kt').read_text()
methods=[]
for name in ['fillBox','fillCssBox','measureText','drawText']:
 start=s.index('    fun '+name+'(');brace=s.index('{',start)
 sig=s[start:brace+1];ret='return 0f' if name in ['measureText','drawText'] else ''
 methods.append(sig+ret+'}\n')
put('UiDraw',b+'.ui.common','''
import net.minecraft.client.gui.GuiGraphicsExtractor
enum class UiFontWeight { REGULAR, MEDIUM, SEMIBOLD, BOLD, MONOSPACE }
object UiDraw {
'''+''.join(methods)+'}\n')
print('Explicit project hosts generated; ClientModule inherits full actual production MinecraftShortcuts')
