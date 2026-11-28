/* SPDX-License-Identifier: GPL-3.0-only. Deterministic production-logic tests; no game/server claim. */
package net.ccbluex.liquidbounce.features.module.modules.combat.neoraven

import kotlin.math.*
import kotlin.random.Random

private var assertions=0
private fun checkThat(ok:Boolean,message:String) { assertions++; check(ok) { message } }
private fun equal(a:Any?,b:Any?,message:String) = checkThat(a==b,"$message: $a != $b")

private class Host(override val mode:NeoBlock):NeoBlockMachine.Host {
    override var hasTarget=true
    override var blockAllowed=true
    override var sword=true
    override var aps=10.0
    override var phase=-1
    override var queued=false
    override var fake=false
    override var serverBlocking=false
    override var lagging=false
    override var lastUse=0
    override var serverSlot=0
    override val currentSlot=0
    var roll=true
    var slots=listOf(0,1)
    var dual=0
    val log=mutableListOf<String>()
    override fun releaseFlow(phase:Int){log.add("release:$phase")}
    override fun block(value:Boolean,packet:Boolean) {
        log.add("block:$value:$packet")
        if(packet){fake=value;serverBlocking=value;lastUse=if(value)1 else 0}
    }
    override fun rollLag():Boolean {log.add("roll");return roll}
    override fun swapAway(){log.add("swap");lastUse = -1}
    override fun freeSlot()=8
    override fun select(slot:Int){log.add("slot:$slot");serverSlot=slot;lastUse = -1}
    override fun swordSlots()=slots
    override fun nextDualSlot(best:Int,second:Int)=if(dual++ and 1==0)best else second
}

private fun blocks() {
    val modes=NeoBlock.entries
    equal(modes.map { it.name },listOf("MANUAL","VANILLA","DAMAGE","FAKE","INTERACT_A","INTERACT_B","INTERACT_C","INTERACT_D","SWAP","DUAL"),"block indices")
    for(mode in modes) for(phase in listOf(-2,-1,0,1)) for(queued in listOf(false,true))
        for(roll in listOf(false,true)) for(hasTarget in listOf(false,true))
        for(allowed in listOf(false,true)) for(sword in listOf(false,true)) for(aps in listOf(1.0,9.5,10.0,20.0)) {
        val h=Host(mode).apply {this.phase=phase;this.queued=queued;this.roll=roll;this.hasTarget=hasTarget
            this.blockAllowed=allowed;this.sword=sword;this.aps=aps;this.fake=true;this.serverBlocking=true;this.lastUse=1}
        val result=NeoBlockMachine.prepare(h)
        if(!mode.packet || !hasTarget) {
            equal(h.phase,phase,"inactive unchanged");equal(h.log.size,0,"inactive no emissions");checkThat(result,"inactive allows native attack")
        } else if(!allowed || !sword) {
            equal(h.phase,-1,"release phase")
            equal(result,phase == -1,"release suppresses one attack")
            if(phase != -1){equal(h.log,listOf("release:$phase"),"exact release");checkThat(!h.fake&&!h.serverBlocking,"released flags")}
        } else {
            checkThat(h.phase in -2..1,"bounded phase")
            if(mode in listOf(NeoBlock.INTERACT_A,NeoBlock.INTERACT_B,NeoBlock.INTERACT_C,NeoBlock.INTERACT_D)) {
                if(phase == -1 || phase==1) {equal(h.phase,0,"interact release phase");checkThat(!result,"release cannot attack")}
                if(phase==0 && (queued||aps>=10)) {equal(h.phase,1,"interact attack phase");checkThat(result,"interact attack permitted")}
            }
            if(mode==NeoBlock.SWAP && (phase == -1||phase==1)) {
                equal(h.phase,0,"swap zero phase");checkThat("swap" in h.log,"swap emits slots");checkThat(!h.serverBlocking,"swap releases server block")
            }
            if(mode==NeoBlock.SWAP && phase==0) {equal(h.phase,1,"swap block phase");checkThat("block:true:true" in h.log,"swap reblocks")}
            if(mode==NeoBlock.DUAL && queued) {equal(h.phase,1,"dual hit phase");checkThat(h.serverSlot!=0,"dual changes sword")}
        }
    }
    for(mode in listOf(NeoBlock.INTERACT_A,NeoBlock.INTERACT_B,NeoBlock.INTERACT_C,NeoBlock.INTERACT_D)) {
        val h=Host(mode).apply {phase=1;serverBlocking=true;lastUse=1}
        checkThat(!NeoBlockMachine.prepare(h),"interact release")
        when(mode) {
            NeoBlock.INTERACT_A -> checkThat("block:false:true" in h.log && "swap" !in h.log,"A direct release")
            NeoBlock.INTERACT_B -> checkThat("slot:8" in h.log && "block:false:true" !in h.log,"B slot release")
            NeoBlock.INTERACT_C -> checkThat("slot:8" in h.log && "block:false:true" in h.log,"C slot+release")
            NeoBlock.INTERACT_D -> checkThat("swap" in h.log && "block:false:true" in h.log,"D swap+release")
            else -> error("unreachable")
        }
    }
    for(size in 0..1) {
        val h=Host(NeoBlock.DUAL).apply {slots=List(size){it};lastUse=1;queued=true}
        NeoBlockMachine.prepare(h)
        equal(h.phase,-2,"dual lacks second sword");checkThat(h.log.none{it.startsWith("slot:")},"never invent second sword")
    }
}

private fun selectors() {
    val s=NeoOriginalSwitch<Int>()
    equal(s.choose(listOf(1,2,3),1000,150,3,null,0),1,"original starts first")
    equal(s.choose(listOf(1,2,3),1149,150,3,1,0),1,"original holds until boundary")
    equal(s.choose(listOf(1,2,3),1150,150,3,1,0),2,"original switches at boundary")
    equal(s.choose(listOf(1,2,3),1300,150,3,2,0),3,"original third target")
    equal(s.choose(listOf(1,2,3),1450,150,3,3,0),1,"original repeats after full cycle")
    s.clear();equal(s.choose(emptyList(),2000,50,3,null,0),null,"empty original")
    val x=NeoIndexedSwitch<Int>()
    equal(x.choose(listOf(1,2,3),true,1000,50),1,"leader first")
    equal(x.choose(listOf(1,2,3),true,1049,50),1,"leader delay")
    equal(x.choose(listOf(1,2,3),true,1050,50),2,"leader tick delay")
    equal(x.choose(listOf(3,2,1),false,1051,50),3,"single takes new priority")
    x.clear()
    repeat(10000) { i ->
        val list=(1..(i%8+1)).toList()
        val r=x.choose(list,true,i*100000000L,100000000L,3,true)
        checkThat(r in list.take(3),"flux cannot exceed switch size after list shrink")
    }
    // Differential oracle, transcribed literally from the pinned findTarget signed-time loop.
    val ref=HashMap<Int,Long>(); val impl=NeoOriginalSwitch<Int>(); val random=Random(85)
    var previous:Int?=null
    for(t in 0..10000) {
        val now=10000L+t*25L
        val list=(1..8).shuffled(random).take(random.nextInt(1,9))
        val delay=50L*(1+t%5); val count=1+t%8; val cycle=minOf(list.size,count)*delay
        var chosen:Int?=null
        for(e in list) {
            val time=ref[e] ?: continue
            if(time>0) {
                if(abs(now-time)<delay) {chosen=e;break}
                if(cycle!=delay) {ref[e] = -time;continue}
                ref.remove(e);continue
            }
            if(abs(now+time)<cycle)continue
            ref.remove(e)
        }
        if(chosen==null) {
            for(e in list) {if(ref[e]!=null)continue;ref[e]=now;chosen=e;break}
            if(chosen==null && previous in list) chosen=previous
        }
        equal(impl.choose(list,now,delay,count,previous,0),chosen,"original differential $t")
        previous=chosen
    }
}

private fun queueAndLag() {
    val queue=NeoHeldQueue<Int>(4); val sent=mutableListOf<Int>()
    checkThat(queue.add(1),"queue normal");checkThat(queue.add(2,NeoHeldQueue.Kind.ATTACK),"queue attack")
    checkThat(queue.add(3,NeoHeldQueue.Kind.SWING),"queue swing");checkThat(queue.add(4,NeoHeldQueue.Kind.START_DIG),"queue dig")
    checkThat(!queue.add(5),"capacity cannot grow")
    queue.drain(true,sent::add);equal(sent,listOf(1),"disable drops attack/swing/startdig");equal(queue.size,0,"drained")
    repeat(1000) { cycle ->
        sent.clear()
        repeat(4){queue.add(it,NeoHeldQueue.Kind.entries[it])}
        queue.drain(false,sent::add);equal(sent,listOf(0,1,2,3),"exact FIFO $cycle")
        queue.add(99,NeoHeldQueue.Kind.ATTACK);queue.clear();queue.drain(false,sent::add)
        checkThat(99 !in sent,"world/connection clear cannot replay attack")
    }
    for(percent in 0..100) {
        val l=NeoLagAccumulator();var lagged=0
        repeat(10000){if(l.roll(percent))lagged++}
        equal(lagged,percent*100,"deterministic lag percent $percent")
        l.reset();equal(l.roll(0),false,"reset accumulator")
    }
}

private fun rotations() {
    for(yaw in -720..720) for(smoothing in listOf(0f,1f,4f,8f)) {
        val wanted=yaw.toFloat()
        val result=NeoAuraMath.smooth(0f,wanted,smoothing,true)
        val delta=NeoAuraMath.wrap(wanted)
        checkThat(abs(result)<=abs(delta)+1e-4,"smooth no overshoot")
        checkThat(result*delta>=0,"smooth shortest path")
        val pitch=NeoAuraMath.branchSmooth(0f,wanted.coerceIn(-90f,90f),50f,90f,false)
        checkThat(abs(pitch)<=45.001,"branch half smoothing")
    }
    equal(NeoAuraMath.approach(179f,-179f,1f),180f,"wrap crosses seam")
    equal(NeoAuraMath.approach(0f,90f,30f),30f,"angle step")
    for(s in 0..100) for(a in -180..180 step 3) {
        val sensitivity=s/100.0
        val f=sensitivity*.6+.2;val step=f*f*f*1.2
        val result=NeoAuraMath.quantize(a.toFloat(),0f,sensitivity)
        checkThat(abs(result/step-round(result/step))<.003,"sensitivity lattice")
    }
}

fun main() {
    blocks();selectors();queueAndLag();rotations()
    println("PASS: $assertions deterministic assertions on production switch/math/queue/block state machines")
    println("Not a Minecraft session, Mixin bootstrap, network-translation test, Android test or server result.")
}
