import net.ccbluex.liquidbounce.utils.inventory.InventorySafetyPolicy as P
import net.ccbluex.liquidbounce.utils.inventory.InventorySafetyGate
import net.ccbluex.liquidbounce.features.module.modules.player.neoraven.NeoInventoryPolicy as N
import kotlin.random.Random

fun image(fill: Boolean = false): P.Image = P.Image(List(46) { if (fill && it in 5..45) P.Stack(it,1,1) else null })
fun swap(items: List<N.Item?>, c: P.Click): List<N.Item?> = items.toMutableList().apply {
    val d=36+c.button;val a=this[c.slot];this[c.slot]=this[d];this[d]=a
}
fun armorClicks(source: Int,armor: Int,scratch: Int) = if(source>=36) listOf(P.Click(armor,source-36,P.Mode.SWAP)) else
    listOf(P.Click(source,scratch,P.Mode.SWAP),P.Click(armor,scratch,P.Mode.SWAP),P.Click(source,scratch,P.Mode.SWAP))
fun main() {
    val random=Random(760075)
    var scoreChecks=0
    repeat(20000) {
        val kind=N.Kind.entries.random(random);val count=random.nextInt(1,65);val score=random.nextInt(0,12).toDouble()
        val item=N.Item(kind,score,count,random.nextInt(5,9),random.nextBoolean(),random.nextBoolean(),random.nextBoolean())
        val old=NeoUpstreamInventoryOracle.ItemStack(kind.ordinal,score,count,item.golden,item.splash,item.placeable)
        for((index,target) in N.order.withIndex()) {
            val actual=NeoUpstreamInventoryOracle.evaluate(old,index)
            check(N.matches(item,target)==!actual.isNaN()) { "match $kind $target" }
            if(!actual.isNaN())check(N.score(item,target)==actual) { "score $kind $target" }
            scoreChecks++
        }
    }
    println("PASS: $scoreChecks category/score comparisons against unchanged pinned upstream Java match/score bodies (modeled items).")

    // Reproduce upstream equal-score ping-pong; new allocation retains the destination on a tie.
    val tie=MutableList<N.Item?>(46){null};tie[9]=N.Item(N.Kind.SWORD,5.0);tie[36]=N.Item(N.Kind.SWORD,5.0)
    val oldTie=arrayOfNulls<NeoUpstreamInventoryOracle.ItemStack>(46)
    oldTie[9]=NeoUpstreamInventoryOracle.ItemStack(1,5.0,1,false,false,false)
    oldTie[36]=NeoUpstreamInventoryOracle.ItemStack(1,5.0,1,false,false,false)
    val config=IntArray(12);config[0]=1
    check(NeoUpstreamInventoryOracle.next(oldTie,config)!!.contentEquals(intArrayOf(9,0,2)))
    check(N.nextSort(tie,mapOf(N.Kind.SWORD to 1))==null)
    // Different categories assigned the same target: original changes its mind after the first swap.
    val conflict=MutableList<N.Item?>(46){null};conflict[9]=N.Item(N.Kind.SWORD,5.0);conflict[10]=N.Item(N.Kind.BLOCK,count=64,placeable=true)
    val cfg=mapOf(N.Kind.SWORD to 1,N.Kind.BLOCK to 1)
    val chosen=N.nextSort(conflict,cfg)!!;check(chosen.slot==9)
    check(N.nextSort(swap(conflict,chosen),cfg)==null)
    // Same type requested in multiple TS hotbar slots: never steal an earlier allocated winner.
    val dup=listOf(P.Target(0,listOf(P.Candidate(36,10.0),P.Candidate(9,9.0))),P.Target(1,listOf(P.Candidate(36,10.0),P.Candidate(9,9.0))))
    check(P.nextSwap(dup)==P.Click(9,1,P.Mode.SWAP))
    // Missing early category must not pin a wrong item in that category's target.
    check(P.nextSwap(listOf(P.Target(0,emptyList()),P.Target(1,listOf(P.Candidate(36,1.0)))))==P.Click(36,1,P.Mode.SWAP))
    println("PASS: reproduced upstream tie churn; destination ties, duplicate destinations/types, missing categories and first-owner allocation now converge.")

    var swaps=0
    repeat(12000) {
        var items=List<N.Item?>(46) { id -> if(id !in 9..44 || random.nextInt(5)==0) null else {
            val kind=N.Kind.entries.random(random)
            N.Item(kind,random.nextInt(8).toDouble(),random.nextInt(1,65),golden=random.nextBoolean(),splash=random.nextBoolean(),placeable=true)
        }}
        val cfg=N.order.associateWith { random.nextInt(0,10) }
        val pinned=(9..44).filter { random.nextInt(25)==0 }.toSet()
        val seen=HashSet<List<N.Item?>>()
        repeat(40) {
            val action=N.nextSort(items,cfg,pinned) ?: return@repeat
            check(seen.add(items)) { "cycle! $cfg" }
            check(action.slot !in pinned && 36+action.button !in pinned)
            val before=items.filterNotNull().groupingBy { it }.eachCount()
            items=swap(items,action)
            check(items.filterNotNull().groupingBy { it }.eachCount()==before)
            swaps++
        }
        check(N.nextSort(items,cfg,pinned)==null) { "did not converge" }
    }
    println("PASS: 12000 randomized inventories with ties/overlapping categories/duplicate targets/pinned slots converge; $swaps swaps conserve every modeled stack.")

    var armorCases=0
    for(source in 9..44) for(armor in 5..8) for(scratch in 0..8) {
        val before=image(true);var after=before
        for(c in armorClicks(source,armor,scratch))after=P.predict(after,c)!!
        check(after.cursor==null && after.contents()==before.contents())
        check(after.slots[armor]==before.slots[source] && after.slots[source]==before.slots[armor])
        if(source<36)check(after.slots[36+scratch]==before.slots[36+scratch])
        check(after.slots[45]==before.slots[45])
        armorCases++
    }
    val gear=MutableList<N.Item?>(46){null};gear[5]=N.Item(N.Kind.ARMOR,8.0,armor=5);gear[9]=N.Item(N.Kind.ARMOR,8.0,armor=5)
    check(N.armorUpgrade(gear)==null);gear[9]=gear[9]!!.copy(score=9.0)
    check(N.armorUpgrade(gear)==(9 to 5));check(N.armorUpgrade(gear,setOf(5))==null)
    println("PASS: $armorCases full-inventory armor exchanges conserve equipment, restore scratch hotbar, avoid cursor/THROW, preserve offhand; equal armor never churns.")

    var mergeCases=0
    for(a in 1..63)for(b in a..63) {
        val s=MutableList<P.Stack?>(46){null};s[9]=P.Stack(1,a);s[10]=P.Stack(1,b);s[11]=P.Stack(2,7)
        val before=P.Image(s);var after=before
        val clicks=mutableListOf(P.Click(9,0,P.Mode.PICKUP),P.Click(10,0,P.Mode.PICKUP))
        if(a+b>64)clicks.add(P.Click(9,0,P.Mode.PICKUP))
        for(c in clicks)after=P.predict(after,c)!!
        check(after.cursor==null && after.contents()==before.contents())
        check(after.slots[10]!!.count==minOf(64,a+b));check(after.slots[11]==before.slots[11]);mergeCases++
    }
    for(flags in 0..63) {
        val result=P.discardAllowed(flags and 1 !=0,flags and 2 !=0,flags and 4 !=0,flags and 8 !=0,flags and 16 !=0,flags and 32 !=0)
        check(result==(flags==1))
    }
    val full=image(true)
    for(slot in listOf(-999,0,1,2,3,4,45,46))for(mode in P.Mode.entries)check(P.predict(full,P.Click(slot,0,mode))==null)
    for(slot in 5..8)check(P.predict(full,P.Click(slot,1,P.Mode.THROW))==null)
    check(P.predict(full,P.Click(9,40,P.Mode.SWAP))==null)
    val dropped=P.predict(full,P.Click(9,1,P.Mode.THROW))!!
    check(dropped.contents()==full.contents()-9)
    println("PASS: $mergeCases partial-stack merges conserve exact component IDs/cursor; all 64 discard guards, illegal slots/buttons/armor/offhand protection and explicit whole-stack THROW accounting.")

    val base=image(true);val c=P.Click(9,0,P.Mode.SWAP);val after=P.predict(base,c)!!
    val gate=InventorySafetyGate()
    check(gate.begin(base,after,0,0,0));gate.verify(after)
    check(!gate.ready(after,1000,0));check(!gate.ready(after,249,1));check(gate.ready(after,250,2))
    // A later tick/packet cannot silently re-arm after correction, even after the settle floor.
    check(!gate.ready(base,1000,3) && gate.fault!=null)
    check(!gate.begin(base,after,1000,3,0))
    gate.reset();check(gate.begin(base,after,0,0,0));gate.verify(base);check(gate.fault!=null)
    gate.reset();check(gate.begin(base,after,0,0,0));check(!gate.ready(after,250,1));check(!gate.ready(after,250,1));check(gate.ready(after,300,2))
    val back=P.predict(after,c)!!;check(back==base)
    check(gate.begin(after,back,300,2,0));gate.verify(back)
    check(!gate.ready(back,600,3));check(gate.ready(back,650,4));check(!gate.begin(base,after,650,4,0));check(gate.fault!=null)
    gate.reset();check(gate.ready(base,0,0))
    println("PASS: 250ms/two-distinct-tick settle floor, no same-tick burst, prediction no-op failure, late/early server rollback, repeated-state circuit breaker and explicit session reset. Settlement is NOT a server acknowledgement.")

    // Shears bug upstream considered every pair mutually inferior; safe derivation retains equal-value copies.
    val shears=MutableList<N.Item?>(46){null};shears[9]=N.Item(N.Kind.SHEARS,10.0);shears[10]=N.Item(N.Kind.SHEARS,10.0)
    check(N.trashCandidates(shears,N.Settings()).isEmpty())
    shears[10]=shears[10]!!.copy(score=11.0);check(N.trashCandidates(shears,N.Settings())==listOf(9))
    println("PASS: equal shears retained; strict-better-only duplicate candidate test (actual discard still requires explicit allowlist).")
    println("NOT proven: real MC item-component binding, applied Mixins, multiplayer/Via acceptance, live GUI or Android/Pojav. No full build/runtime claim.")
}
