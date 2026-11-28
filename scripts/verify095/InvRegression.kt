package net.ccbluex.liquidbounce.features.module.modules.player.neoraven

import net.ccbluex.liquidbounce.utils.input.InputBind
import net.ccbluex.liquidbounce.config.ConfigSystem
import com.mojang.blaze3d.platform.InputConstants

fun main(){
 var n=0
 fun ok(v:Boolean){n++;check(v){"inv $n"}}
 val m=ModuleNeoInvManager
 m.bindValue.set(InputBind(InputConstants.Type.KEYBOARD,45,InputBind.BindAction.HOLD))
 val before=m.enabled
 m.migrateTapToggle()
 ok(m.bind.action==InputBind.BindAction.TOGGLE);ok(m.bind.code==45);ok(m.enabled==before);ok(ConfigSystem.stores==1)
 repeat(50){m.migrateTapToggle()};ok(ConfigSystem.stores==1)
 // A user can explicitly choose HOLD later; the one-time marker must not fight them each frame.
 m.bindValue.set(m.bind.copy(action=InputBind.BindAction.HOLD));m.migrateTapToggle()
 ok(m.bind.action==InputBind.BindAction.HOLD);ok(ConfigSystem.stores==1)
 val old=NeoInvBindLatch()
 ok(old.press(NeoInvBindLatch.Action.HOLD,false,1000)==true);ok(old.release(1100)==false)
 val tap=NeoInvBindLatch();var enabled=false
 repeat(100){cycle->
  val prior=enabled
  tap.press(NeoInvBindLatch.Action.TOGGLE,enabled,cycle*1000L)?.let{enabled=it}
  ok(enabled!=prior)
  repeat(10){ok(tap.press(NeoInvBindLatch.Action.TOGGLE,enabled,cycle*1000L+10)==null);tap.repeat()}
  ok(tap.release(cycle*1000L+100)==null);ok(enabled!=prior)
  ok(tap.release(cycle*1000L+110)==null)
 }
 // Default bind was already toggle; no global HOLD semantics are altered by this migration.
 ok(NeoInvBindLatch().press(NeoInvBindLatch.Action.TOGGLE,false,0)==true)
 println("PASS: $n actual Neo migration/latch assertions; HOLD release reproduces log behavior; one-time TOGGLE conversion preserves key/state, persists once, later explicit HOLD respected. Bind/config persistence are explicit fixtures, not on-device input.")
}
