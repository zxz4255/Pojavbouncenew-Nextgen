package net.ccbluex.liquidbounce.features.module.modules.render.cs2mvp
import fixture.*
import net.ccbluex.liquidbounce.event.Dispatch
import net.ccbluex.liquidbounce.event.events.*
import net.ccbluex.liquidbounce.features.module.Value
import net.ccbluex.liquidbounce.utils.client.vfp.VfpCompatibility1_8
import net.minecraft.world.entity.*
import net.minecraft.world.entity.player.Player
import net.minecraft.network.protocol.game.*

private var checks=0
private fun ok(v:Boolean){checks++;check(v){"adapter #$checks"}}
@Suppress("UNCHECKED_CAST") private fun <T> config(n:String,v:T){(ModuleCs2Mvp.values.getValue(n) as Value<T>).value=v}
private val module get()=ModuleCs2Mvp
private fun reset(){
 module.enabled=false;module.onDisabled();client.queue.clear();client.level=Level();client.player=Player(1,"LocalWinner")
 client.level!!.entities[1]=client.player!!;client.gui.current=null;client.gui.overlayValue=null;client.gui.hud.isHidden=false
 VfpCompatibility1_8.is1_8ConnectionActive=false;config("Attribution",ModuleCs2Mvp.Attribution.AUTO);config("Preview",false)
 module.enabled=true;module.onEnabled();Cs2MvpRenderer.frames.clear()
}
private fun add(e:Entity){client.level!!.entities[e.id]=e}
private fun packet(p:Any,cancelled:Boolean=false,outgoing:Boolean=false){
 Dispatch.fire(PacketEvent(if(outgoing)TransferOrigin.OUTGOING else TransferOrigin.INCOMING,p).also{it.isCancelled=cancelled})
}
private fun death(e:Entity){packet(ClientboundEntityEventPacket(e.id,EntityEvent.DEATH));client.flush()}
private fun damage(e:Entity,source:Int=1){packet(ClientboundDamageEventPacket(e.id,source));client.flush()}
private fun frame():Cs2MvpFrame?{val n=Cs2MvpRenderer.frames.size;Dispatch.fire(OverlayRenderEvent());return if(Cs2MvpRenderer.frames.size>n)Cs2MvpRenderer.frames.last() else null}
private fun timeline():Cs2MvpTimeline=module.javaClass.getDeclaredField("timeline").apply{isAccessible=true}.get(module) as Cs2MvpTimeline
private fun stamp()=Cs2MvpTimeline::class.java.getDeclaredField("startedAt").apply{isAccessible=true}
fun main(){
 // Same complete adapter for enemy/friendly player, animal, monster and other LivingEntity.
 for(target in listOf(Player(2,"Enemy"),Player(3,"FriendOrTeammate"),LivingEntity(4),LivingEntity(5),LivingEntity(6))){
  reset();add(target);Dispatch.fire(AttackEntityEvent(target));ok(frame()==null)
  damage(target);ok(frame()==null);target.health=0f;Dispatch.fire(GameTickEvent())
  ok(frame()?.name=="LocalWinner")
  val mark=stamp().getLong(timeline())-17;stamp().setLong(timeline(),mark)
  repeat(4){death(target);Dispatch.fire(GameTickEvent())}
  ok(stamp().getLong(timeline())==mark) // duplicate health/status death never restarts the banner
 }
 // Projectile/source-attributed death without an AttackEntityEvent still counts.
 reset();val mob=LivingEntity(8);add(mob);damage(mob);death(mob);ok(frame()!=null)
 val first=stamp().getLong(timeline())-17;stamp().setLong(timeline(),first)
 val second=LivingEntity(9);add(second);damage(second);death(second)
 ok(frame()?.name=="LocalWinner" && stamp().getLong(timeline())!=first) // next kill refreshes one banner
 // Swing/hurt/unload, self, other killer and nonliving objects do not count.
 reset();val alive=LivingEntity(10);add(alive);Dispatch.fire(AttackEntityEvent(alive));Dispatch.fire(GameTickEvent());ok(frame()==null)
 client.level!!.entities.remove(alive.id);Dispatch.fire(GameTickEvent());ok(frame()==null)
 reset();damage(client.player!!);death(client.player!!);ok(frame()==null)
 reset();val objectEntity=Entity(11);add(objectEntity);Dispatch.fire(AttackEntityEvent(objectEntity));damage(objectEntity);death(objectEntity);ok(frame()==null)
 reset();val other=LivingEntity(12);add(other);Dispatch.fire(AttackEntityEvent(other));damage(other,77);death(other);ok(frame()==null)
 reset();val spectator=Player(13);spectator.isSpectator=true;add(spectator);damage(spectator);death(spectator);ok(frame()==null)
 // Existing attribution settings retain their explicit evidence boundaries for all living targets.
 for(legacy in listOf(false,true)){
  reset();VfpCompatibility1_8.is1_8ConnectionActive=legacy;val e=LivingEntity(20);add(e)
  Dispatch.fire(AttackEntityEvent(e));death(e);ok((frame()!=null)==legacy)
 }
 reset();config("Attribution",ModuleCs2Mvp.Attribution.RECENT_HIT);val recent=LivingEntity(21);add(recent)
 Dispatch.fire(AttackEntityEvent(recent));death(recent);ok(frame()!=null)
 reset();config("Attribution",ModuleCs2Mvp.Attribution.DAMAGE_SOURCE);VfpCompatibility1_8.is1_8ConnectionActive=true
 val strict=LivingEntity(22);add(strict);Dispatch.fire(AttackEntityEvent(strict));death(strict);ok(frame()==null)
 // Cancelled/outgoing packets and cancelled attacks are ignored.
 for(out in listOf(false,true)){
  reset();val e=LivingEntity(30);add(e)
  packet(ClientboundDamageEventPacket(e.id,1),cancelled=!out,outgoing=out);client.flush();death(e);ok(frame()==null)
 }
 reset();config("Attribution",ModuleCs2Mvp.Attribution.RECENT_HIT);val c=LivingEntity(31);add(c)
 Dispatch.fire(AttackEntityEvent(c).also{it.isCancelled=true});death(c);ok(frame()==null)
 // Queued packets from a previous level/player/activation must not paint a stale MVP.
 for(change in 0..2){
  reset();val e=LivingEntity(40);add(e);damage(e);packet(ClientboundEntityEventPacket(e.id,EntityEvent.DEATH))
  when(change){0->client.level=Level();1->client.player=Player(90);else->{module.onDisabled();module.enabled=false}}
  client.flush();ok(frame()==null)
 }
 reset();val hidden=LivingEntity(50);add(hidden);damage(hidden);death(hidden)
 client.gui.hud.isHidden=true;ok(frame()==null);client.gui.hud.isHidden=false;ok(frame()!=null)
 Dispatch.fire(WorldChangeEvent());ok(frame()==null)
 println("PASS: $checks full production MVP adapter/state fixture assertions: players/all living targets, local source and death-only, one-death dedup, consecutive refresh, legacy modes, cancellation, self/nonliving exclusions, client-thread epoch and HUD guards. Entities/packets/DSL/renderer are explicit models, not game/server acceptance.")
}
