from pathlib import Path
import sys
w=Path(sys.argv[1]);w.mkdir(parents=True,exist_ok=True)
def put(n,pkg,s):(w/(n+'.kt')).write_text('package '+pkg+'\n'+s+'\n')
b='net.ccbluex.liquidbounce'
put('Tagged',b+'.config.types.list','interface Tagged{val tag:String}')
put('Module',b+'.features.module','''
import kotlin.reflect.KProperty
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventListener
class Value<T>(var value:T){operator fun getValue(o:Any?,p:KProperty<*>)=value}
open class ClientModule(val name:String,c:ModuleCategories):EventListener{
 val mc get()=fixture.client
 val values=mutableMapOf<String,Value<*>>()
 var enabled=false
 override val running get()=enabled
 open fun onEnabled(){};open fun onDisabled(){}
 fun <T> v(n:String,x:T)=Value(x).also{values[n]=it}
 fun boolean(n:String,x:Boolean)=v(n,x);fun float(n:String,x:Float,r:ClosedFloatingPointRange<Float>)=v(n,x)
 fun int(n:String,x:Int,r:IntRange)=v(n,x);fun text(n:String,x:String)=v(n,x)
 inline fun <reified T> enumChoice(n:String,x:T):Value<T> where T:Enum<T>,T:Tagged =v(n,x)
}
enum class ModuleCategories{RENDER}
''')
put('Event',b+'.event','''
interface EventListener{val running:Boolean}
open class Event
open class CancellableEvent:Event(){var isCancelled=false}
object Dispatch{
 val callbacks=mutableListOf<(Event)->Unit>()
 fun fire(e:Event){callbacks.toList().forEach{it(e)}}
}
inline fun <reified T:Event> EventListener.handler(priority:Short=0,noinline fn:(T)->Unit):Any{
 Dispatch.callbacks.add{if(running && it is T)fn(it)};return Any()
}
''')
put('Types',b+'.event.events','''
import net.ccbluex.liquidbounce.event.*
import net.minecraft.world.entity.Entity
class AttackEntityEvent(val entity:Entity):CancellableEvent()
class GameTickEvent:Event();class OverlayRenderEvent(val context:Any=Any()):Event();class WorldChangeEvent:Event()
enum class TransferOrigin{INCOMING,OUTGOING}
class PacketEvent(val origin:TransferOrigin,val packet:Any):CancellableEvent()
''')
put('Priority',b+'.utils.kotlin','object EventPriorityConvention{const val READ_FINAL_STATE:Short = -1000}')
put('Vfp',b+'.utils.client.vfp','object VfpCompatibility1_8{val INSTANCE=this;var is1_8ConnectionActive=false}')
put('Entity','net.minecraft.world.entity','''
import java.util.UUID
open class Entity(val id:Int,val uuid:UUID=UUID(0,id.toLong()))
open class LivingEntity(id:Int):Entity(id){var health=20f;var isSpectator=false;val isAlive get()=health>0}
object EntityEvent{const val DEATH:Byte=3}
''')
put('Player','net.minecraft.world.entity.player','''
class Profile(val name:String)
class Player(id:Int,name:String="Player"):net.minecraft.world.entity.LivingEntity(id){val gameProfile=Profile(name);val skin=Any()}
''')
put('Packets','net.minecraft.network.protocol.game','''
class ClientboundDamageEventPacket(val entityId:Int,val sourceCauseId:Int)
class ClientboundEntityEventPacket(val id:Int,val eventId:Byte){fun getEntity(l:fixture.Level)=l.getEntity(id)}
''')
put('Fixture','fixture','''
import net.minecraft.world.entity.*
import net.minecraft.world.entity.player.Player
class Level{val entities=mutableMapOf<Int,Entity>();fun getEntity(id:Int)=entities[id]}
class Hud{var isHidden=false}
class Gui{val hud=Hud();var current:Any?=null;var overlayValue:Any?=null;fun screen()=current;fun overlay()=overlayValue}
class Window{val guiScaledWidth=1920;val guiScaledHeight=1080}
class Client{
 var level:Level?=Level();var player:Player?=Player(1,"LocalWinner");val gui=Gui();val window=Window()
 val queue=mutableListOf<()->Unit>()
 fun execute(fn:()->Unit){queue.add(fn)}
 fun flush(){val work=queue.toList();queue.clear();work.forEach{it()}}
}
val client=Client()
''')
put('Renderer',b+'.features.module.modules.render.cs2mvp','''
internal object Cs2MvpRenderer{
 val frames=mutableListOf<Cs2MvpFrame>()
 fun draw(c:Any,skin:Any,f:Cs2MvpFrame,w:Int,h:Int,s:Float,y:Float,ct:Boolean,n:String,r:String){frames.add(f)}
}
''')
print('MVP adapter fixtures: entities, packets, queue, DSL, renderer are models; full module/state remain production.')
