package verify091
import net.ccbluex.liquidbounce.features.module.modules.player.neoraven.NeoInvBindLatch
import net.ccbluex.liquidbounce.features.module.modules.render.motionblur.MotionBlurHistory
import kotlin.math.abs
import kotlin.random.Random
private var checks=0
private fun test(b:Boolean) { checks++;check(b) { "check $checks" } }
fun main() {
 val random=Random(91)
 for (initial in listOf(false,true)) for (n in 0..1000) {
  val l=NeoInvBindLatch();var state=initial
  l.press(NeoInvBindLatch.Action.TOGGLE,state,0)?.let { state=it };test(state!=initial)
  repeat(n%17) {test(l.press(NeoInvBindLatch.Action.TOGGLE,state,1)==null);l.repeat()}
  test(l.release(10000)==null);test(state!=initial);test(l.release(10001)==null)
  test(l.press(NeoInvBindLatch.Action.TOGGLE,state,10002)==initial)
 }
 for (initial in listOf(false,true)) for (mode in NeoInvBindLatch.Action.entries) {
  val l=NeoInvBindLatch();test(l.release(0)==null)
  test(l.press(mode,initial,1)==if(mode==NeoInvBindLatch.Action.TOGGLE) !initial else true)
  l.clear();test(l.release(999)==null)
 }
 for (initial in listOf(false,true)) {
  for (ms in listOf(0L,100L,199L,200L,1000L)) {
   val l=NeoInvBindLatch();test(l.press(NeoInvBindLatch.Action.SMART,initial,0)==true)
   test(l.release(ms)==if(ms>=200)false else !initial)
  }
  val l=NeoInvBindLatch();l.press(NeoInvBindLatch.Action.SMART,initial,0);l.repeat();test(l.release(2)==false)
  l.press(NeoInvBindLatch.Action.HOLD,initial,3);test(l.release(4)==false)
 }
 val w=Any();val p=Any();val v=Any()
 fun step(h:MotionBlurHistory,t:Long,width:Int=1920,world:Any=w,owner:Any=p,view:Any=v,x:Double=0.0,yaw:Float=0f,strength:Float=.55f)=
  h.begin(world,owner,view,width,1080,t,x,0.0,0.0,yaw,0f,strength)
 for (fps in listOf(30,60,120,240)) {
  val h=MotionBlurHistory();test(step(h,1)==0f)
  val dt=1_000_000_000L/fps;var cumulative=1.0
  repeat(fps) { cumulative*=step(h,1+(it+1)*dt) }
  test(abs(cumulative-Math.pow(.55,60.0))<1e-18)
 }
 for (kind in 0..10) {
  val h=MotionBlurHistory();step(h,1)
  val t=16_666_668L
  val value=when(kind){
   0->step(h,t,width=1280);1->step(h,t,world=Any());2->step(h,t,owner=Any());3->step(h,t,view=Any())
   4->step(h,300_000_002);5->step(h,0);6->step(h,t,x=9.0);7->step(h,t,yaw=91f)
   8->step(h,t,width=0);9->step(h,t,x=Double.NaN);else->{h.reset();step(h,t)}
  };test(value==0f)
 }
 val h=MotionBlurHistory();step(h,1,yaw=179f);test(step(h,16_666_668,yaw=-179f)>0f)
 repeat(20000) {
  val policy=MotionBlurHistory();val s=random.nextFloat()*.95f
  step(policy,1,strength=s)
  val a=step(policy,1+random.nextLong(1,250_000_000),strength=s)
  test(a.isFinite() && a in 0f..1f)
 }
 println("PASS: $checks actual pure-code assertions: physical toggle/repeat/release, HOLD/SMART, temporal FPS normalization, resize/world/player/view/cut/gap/nonfinite reset.")
}
