import net.ccbluex.liquidbounce.features.module.modules.player.neoraven.*
import net.ccbluex.liquidbounce.features.module.modules.player.neoraven.NeoChestPolicy.Item
import net.ccbluex.liquidbounce.features.module.modules.player.neoraven.NeoChestPolicy.Kind
import net.ccbluex.liquidbounce.features.module.modules.player.neoraven.NeoChestPolicy.Settings
import kotlin.random.Random

private fun setting(bits: Int): Settings {
    fun on(i:Int)=bits and (1 shl i)!=0
    return Settings(on(0),on(1),on(2),on(3),on(4),on(5),on(6),on(7),on(8),on(9))
}
private fun oracleItem(x: Item?): NeoUpstreamLootOracle.ItemStack? = x?.let {
    NeoUpstreamLootOracle.ItemStack(it.kind.ordinal,it.value,it.armorType.toIntOrNull()?:0,it.goldenApple,it.placeable)
}
fun main() {
    val random=Random(75075)
    fun item(): Item? = if(random.nextInt(7)==0) null else {
        val kind=Kind.entries.random(random)
        Item(kind,random.nextInt(0,21).toDouble(),if(kind==Kind.ARMOR)random.nextInt(4).toString() else "",
            kind==Kind.FOOD && random.nextBoolean(),random.nextBoolean())
    }
    var compared=0
    for(i in 0 until 16384) {
        val chest=List(if(i%3==0)54 else 27){ item() };val inv=List(40){item()}
        val bits=i%1024
        val got=NeoChestPolicy.slots(chest,inv,setting(bits))
        val expected=NeoUpstreamLootOracle.evaluate(chest.map(::oracleItem).toTypedArray(),inv.map(::oracleItem).toTypedArray(),bits)
        check(got==expected){"filter divergence flags=$bits: $got != $expected"};compared++
    }
    println("PASS: $compared randomized 27/54-slot chests over all 1024 toggle combinations match actual pinned upstream Java filtering methods (toy scored items).")
    val sword=Item(Kind.SWORD,7.0)
    check(NeoChestPolicy.slots(listOf(sword,sword),emptyList(),Settings())==listOf(0,1)) // upstream ties in chest both survive
    check(NeoChestPolicy.slots(listOf(sword),listOf(sword),Settings()).isEmpty())
    check(NeoChestPolicy.slots(listOf(Item(Kind.OTHER)),emptyList(),Settings(ignoreTrash=false))==listOf(0))
    check(NeoChestPolicy.slots(listOf(Item(Kind.OTHER)),emptyList(),Settings(instant=true))==listOf(0))
    check(NeoChestPolicy.slots(listOf(Item(Kind.FOOD)),listOf(Item(Kind.FOOD,goldenApple=true)),Settings()).isEmpty())
    check(NeoChestPolicy.slots(listOf(Item(Kind.ROD)),listOf(Item(Kind.ROD)),Settings()).isEmpty())
    check(NeoChestPolicy.swordScore(7.0,5,2)==20.25)
    check(NeoChestPolicy.armorScore(8.0,0)==8.0)
    check(NeoChestPolicy.armorScore(8.0,4)==12.0) // floor(22/3*.75)=5 -> (15+1)/4=4
    println("PASS: ties/inventory equality, Instant and IgnoreTrash overrides, golden-apple/rod rules and legacy scores.")

    for(a in listOf(0,50,1000))for(b in listOf(0,50,1000))for(t in listOf(0.0,.1,.5,.999999)) {
        val d=NeoChestPolicy.jitter(a,b,t);check(d in minOf(a,b).toLong()..maxOf(a,b).toLong())
    }
    for(name in listOf("Ender Chest","TEAM chest","My island","末影箱","队伍箱"))check(NeoChestPolicy.blockedTitle(name))
    for(name in listOf("Chest","大箱子","loot"))check(!NeoChestPolicy.blockedTitle(name))
    check(NeoChestPolicy.lobby(true,listOf("LOBBY","xx  §8L22")))
    check(NeoChestPolicy.lobby(true,listOf("SKYWARS","a","b","Players: 12")))
    check(!NeoChestPolicy.lobby(false,listOf("SKYWARS","a","b","Players: 12")))
    check(!NeoChestPolicy.lobby(true,listOf("THE HYPIXEL PIT","  §8L1")))
    println("PASS: min/max delay normalization, bounded jitter, original multilingual blocked names and Hypixel lobby/queue projections.")

    val world=Any();val owner=Any();val aura=NeoAuraCycle<Int>()
    aura.sync(world,owner,0);aura.enable(0)
    check(!aura.ready(null,100){aura.opened.add(it)})
    check(aura.ready(null,101){aura.opened.add(it)})
    aura.select(1,101);check(!aura.selectedLongEnough(180));check(aura.selectedLongEnough(181))
    aura.select(1,170);check(aura.selectedLongEnough(181))
    aura.select(2,181);check(!aura.selectedLongEnough(200))
    aura.interacted(2,200);check(aura.opened.isEmpty());check(aura.pending==2)
    check(!aura.ready(null,1400){aura.opened.add(it)} && aura.pending==2)
    check(!aura.ready(null,1401){aura.opened.add(it)} && aura.pending==null)
    aura.interacted(3,1500);val menu=Any();aura.confirm(menu){aura.opened.add(it)}
    check(aura.opened==setOf(3) && aura.pending==null)
    check(!aura.ready(null,1510){aura.opened.add(it)})
    check(!aura.ready(null,1610){aura.opened.add(it)})
    check(aura.ready(null,1611){aura.opened.add(it)})
    aura.clearPending();aura.enable(1700);check(3 in aura.opened)
    aura.sync(world,Any(),1800);check(aura.opened.isEmpty())
    aura.opened.add(9);aura.sync(Any(),owner,1900);check(aura.opened.isEmpty())
    println("PASS: Aura 80/100/1200ms boundaries, stable/changed target timing, confirmation-only marking, close cooldown, enable history, respawn/world isolation.")

    val cycle=NeoLootCycle();val chest=Any();val clicks=mutableListOf<Int>();var closes=0;var open=true
    fun tick(now:Long,instant:Boolean=false,allowed:Boolean=true,autoClose:Boolean=true,slots:List<Int> = listOf(2,4),replace:Boolean=false) =
        cycle.tick(chest,now,instant,allowed,50,autoClose,{slots},{open},{clicks.add(it)},{50},{closes++;open=false},replace)
    tick(0);tick(49);check(clicks.isEmpty());tick(50);check(clicks==listOf(2));tick(99);check(clicks.size==1)
    tick(100);check(clicks==listOf(2,4));check(closes==0);tick(149);check(closes==0);tick(150);check(closes==1)
    check(cycle.session?.completed==true && cycle.session?.processed==2)
    cycle.reset(200,true);clicks.clear();closes=0;open=true
    tick(200,true,slots=emptyList());check(closes==0 && clicks.isEmpty()) // no contents yet
    cycle.contents(chest);tick(201,true,slots=emptyList());check(closes==1)
    cycle.reset(300,true);clicks.clear();closes=0;open=true
    tick(300,true,slots=(0 until 54).toList());check(clicks==(0 until 54).toList() && closes==1)
    tick(301,true,slots=(0 until 54).toList());check(clicks.size==54)
    cycle.reset(400,true);clicks.clear();closes=0;open=true
    tick(400,true,allowed=false);check(clicks.isEmpty() && closes==0)
    tick(401,true,autoClose=false);check(clicks==listOf(2,4) && closes==0)
    cycle.reset(500,true);clicks.clear();open=true
    tick(500,autoClose=false);cycle.session!!.replacedScreen=true
    tick(550,autoClose=false);tick(600,autoClose=false);tick(650,autoClose=false,replace=true);check(closes==1)
    // A menu identity change must drop a half-finished queue before the next native click.
    cycle.reset(700,true);clicks.clear();open=true;tick(700);tick(750)
    val next=Any();cycle.tick(next,751,false,true,50,true,{listOf(8)},{open},{clicks.add(it)},{50},{closes++},false)
    check(cycle.session?.identity===next && clicks==listOf(2))
    cycle.tick(next,801,false,true,50,true,{listOf(8)},{open},{clicks.add(it)},{50},{closes++},false)
    check(clicks==listOf(2,8))
    // Mid-batch closure stops Instant immediately, even if the remaining slots are nonempty.
    cycle.reset(900,true);clicks.clear();open=true
    cycle.tick(Any(),900,true,true,0,true,{(0 until 54).toList()},{open},{clicks.add(it);open=false},{0},{closes++},false)
    check(clicks==listOf(0) && cycle.session?.completed==false)
    println("PASS: normal deadlines/order/completion, Instant same-call 54-click bound, empty-shell guard, repeat-snapshot idempotence, integrity/AutoClose, replacement close, identity-switch and mid-batch closure.")
    println("NOT tested here: full Gradle/game/Mixin application, actual server acknowledgements, mobile UI/rendering, live rotations or packet-cancellation ordering.")
}
