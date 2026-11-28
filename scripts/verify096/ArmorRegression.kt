package verify096
import net.ccbluex.liquidbounce.utils.inventory.InventorySafetyPolicy as P
import net.ccbluex.liquidbounce.utils.inventory.InventorySafetyGate
fun main(){
 var n=0
 fun ok(b:Boolean){n++;check(b){"armor $n"}}
 // The actual 095 three-click sequence rejects its empty third swap, BEFORE any native click.
 val empty=MutableList<P.Stack?>(46){null};empty[9]=P.Stack(1,1,1)
 var old=P.Image(empty);old=P.predict(old,P.Click(9,0,P.Mode.SWAP))!!;old=P.predict(old,P.Click(5,0,P.Mode.SWAP))!!
 ok(P.predict(old,P.Click(9,0,P.Mode.SWAP))==null)
 var cases=0
 for(source in 9..44)for(armor in 5..8)for(scratch in 0..8)for(armorPresent in listOf(false,true))for(scratchPresent in listOf(false,true)){
  val slots=MutableList<P.Stack?>(46){null}
  slots[source]=P.Stack(1,1,1)
  if(armorPresent)slots[armor]=P.Stack(2,1,1)
  if(scratchPresent && source!=36+scratch)slots[36+scratch]=P.Stack(3,64)
  slots[45]=P.Stack(4,1,1)
  val before=P.Image(slots);val plan=P.armorSwapPlan(before,source,armor,scratch)!!
  var after=before
  for(click in plan){after=P.predict(after,click)!!;ok(after.cursor==null)}
  ok(after.contents()==before.contents());ok(after.slots[armor]==before.slots[source]);ok(after.slots[source]==before.slots[armor])
  if(source<36)ok(after.slots[36+scratch]==before.slots[36+scratch])
  ok(after.slots[45]==before.slots[45]);ok(plan.size in 1..3)
  if(source<36 && !armorPresent && !scratchPresent)ok(plan.size==2)
  cases++
 }
 // Same component-equivalent armor/scratch yields a redundant final restore, also safe to omit.
 val eq=empty.toMutableList();eq[5]=P.Stack(2,1,1);eq[36]=eq[5]
 ok(P.armorSwapPlan(P.Image(eq),9,5,0)!!.size==2)
 for(src in listOf(-1,0,5,8,45,46))ok(P.armorSwapPlan(P.Image(empty),src,5,0)==null)
 ok(P.armorSwapPlan(P.Image(empty),9,4,0)==null);ok(P.armorSwapPlan(P.Image(empty),9,5,9)==null)
 ok(P.armorSwapPlan(P.Image(empty,P.Stack(9,1)),9,5,0)==null)
 val gate=InventorySafetyGate();val before=P.Image(empty);val after=P.predict(before,P.Click(9,0,P.Mode.SWAP))!!
 ok(gate.begin(before,after,0,0,0));gate.verify(after);ok(!gate.ready(after,249,1));ok(gate.ready(after,250,2))
 gate.fail("correction");ok(!gate.ready(after,10000,50))
 println("PASS: $n pure production assertions; $cases armor source/slot/scratch × empty/full cases, exact conservation/restoration/no cursor; historical empty-tail reject reproduced.")
}
