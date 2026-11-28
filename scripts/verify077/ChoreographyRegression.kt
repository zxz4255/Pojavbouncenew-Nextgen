import net.ccbluex.liquidbounce.features.module.modules.render.fpsknife.*
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.abs

private fun triangleHits(a0: Vector3f, b0: Vector3f, c0: Vector3f, lo: Vector3f, hi: Vector3f): Boolean {
    val centre=Vector3f(lo).add(hi).mul(.5f); val half=Vector3f(hi).sub(lo).mul(.5f)
    val a=Vector3f(a0).sub(centre); val b=Vector3f(b0).sub(centre); val c=Vector3f(c0).sub(centre)
    fun separates(axis: Vector3f): Boolean {
        if(axis.lengthSquared()<1e-14f) return false
        val radius=half.x*abs(axis.x)+half.y*abs(axis.y)+half.z*abs(axis.z)
        val pa=axis.dot(a); val pb=axis.dot(b); val pc=axis.dot(c)
        return minOf(pa,pb,pc)>radius-1e-7f || maxOf(pa,pb,pc)< -radius+1e-7f
    }
    val axes=listOf(Vector3f(1f,0f,0f),Vector3f(0f,1f,0f),Vector3f(0f,0f,1f))
    for(axis in axes) if(separates(axis)) return false
    val ab=Vector3f(b).sub(a); val bc=Vector3f(c).sub(b); val ca=Vector3f(a).sub(c)
    if(separates(Vector3f(ab).cross(bc))) return false
    for(e in listOf(ab,bc,ca)) for(axis in axes) if(separates(Vector3f(e).cross(axis))) return false
    return true
}

private fun neutral(m: KnifeModel): FpsKnifePose {
    FpsKnifeAnimator.reset()
    return FpsKnifePose().also {
        FpsKnifeAnimator.evaluatePose(it,FpsRenderMode.KNIFE_VIEWMODEL,m,InspectStyle.AUTO_BY_MODEL,Block18Style.TACTICAL_PARRY,FpsKnifeChoreography.BLADE_TILT,1f,0f,1f,true,0f,0f,0f,0f,false,true,false,false,frameTimeNanos=1_000_000_000L)
    }
}
private var frames=0
private var triangles=0
private var hits=0
private val errors=linkedSetOf<String>()
fun main() {
    for(m in KnifeModel.entries) for(action in 0..4) for(side in listOf(-1f,1f)) for(slim in listOf(false,true)) for(i in 0..240) {
        val p=neutral(m); val t=i/240f; val profile=FpsKnifeChoreography.profile(m)
        when(action) {
            0 -> FpsKnifeChoreography.draw(p,m,t)
            1,2 -> FpsKnifeChoreography.inspect(p,m,t,action-1)
            3,4 -> FpsKnifeChoreography.attack(p,m,t,action==4,0)
        }
        validate(p,m,side,slim,"$m/$action/$side/$slim/$i")
    }
    liveStreams()
    println("SAMPLED: $frames poses, $triangles triangle/palm tests, $hits intersections")
    check(hits==0) { errors.joinToString("\n") }
    states()
    endpoints()
}

private val actionField=FpsKnifeAnimator::class.java.getDeclaredField("currentAction").apply { isAccessible=true }
private val queueField=FpsKnifeAnimator::class.java.getDeclaredField("queuedAttacks").apply { isAccessible=true }
private fun action()=actionField.get(FpsKnifeAnimator).toString()
private var time=1_000_000_000L
private fun tick(dt:Long=8_333_333L, moving:Boolean=false, busy:Boolean=false, block:Boolean=false, ground:Boolean=true, air:Boolean=true, yaw:Float=0f): FpsKnifePose {
    time+=dt
    return FpsKnifePose().also {
        FpsKnifeAnimator.evaluatePose(it,FpsRenderMode.KNIFE_VIEWMODEL,KnifeModel.TALON,InspectStyle.AUTO_BY_MODEL,Block18Style.TACTICAL_PARRY,18f,1f,1f,1f,true,0f,yaw,0f,if(moving) .22f else 0f,moving,ground,block,busy,air,frameTimeNanos=time)
    }
}
private fun setup() {
    FpsKnifeAnimator.reset(); time=1_000_000_000L
    FpsKnifeAnimator.checkEquipChange(0,KnifeModel.TALON,FpsRenderMode.KNIFE_VIEWMODEL,false)
    tick()
}
private fun states() {
    setup(); FpsKnifeAnimator.triggerInspect(); repeat(125){tick()}
    val before=tick(); FpsKnifeAnimator.triggerInspect(); val after=tick()
    check(action()=="INSPECT")
    check(abs(before.ringSpinDeg-after.ringSpinDeg)<20f) { "Repeated inspect reset its phase" }
    FpsKnifeAnimator.triggerAttack(false)
    check(action()=="RECOVER" && queueField.getInt(FpsKnifeAnimator)==1)
    val recovery=tick()
    check(abs(after.ringSpinDeg-recovery.ringSpinDeg)<40f) { "Interrupt teleported ring phase" }
    repeat(100){tick()}; check(action()=="ATTACK" || action()=="NONE")
    repeat(120){tick()}; check(action()=="NONE")
    for(reason in 0..3) {
        setup(); FpsKnifeAnimator.triggerInspect(); repeat(50){tick()}
        tick(moving=reason==0,busy=reason==1,block=reason==2,ground=reason!=3,air=false)
        check(action()=="RECOVER")
        repeat(100){tick()}; check(action()=="NONE")
    }
    setup(); FpsKnifeAnimator.triggerInspect(); repeat(60){tick()}
    FpsKnifeAnimator.checkEquipChange(1,KnifeModel.BUTTERFLY,FpsRenderMode.KNIFE_VIEWMODEL,false)
    check(action()=="NONE" && queueField.getInt(FpsKnifeAnimator)==0)
    for(rate in listOf(30,60,120,240)) {
        setup(); FpsKnifeAnimator.triggerInspect()
        var elapsed=0.0
        while(action()!="NONE" && elapsed<6) { tick(1_000_000_000L/rate); elapsed+=1.0/rate }
        check(abs(elapsed-FpsKnifeChoreography.profile(KnifeModel.TALON).inspectSeconds)<1.5/rate) { "Timeline drift $rate/$elapsed" }
    }
    val input=FpsKnifeAttackInput()
    check(input.physical(1000));check(!input.entity(1001));check(input.physical(1010));check(!input.held(1020,true))
    check(input.held(1500,true));check(!input.entity(1501))
    println("PASS: repeated-inspect phase, deferred attack/return, move/busy/block/air recovery, equip reset; 30/60/120/240 Hz duration; raw click deduplication")
}
private fun endpoints() {
    var checks=0
    for(m in KnifeModel.entries) for(action in 0..4) {
        val base=neutral(m); val end=neutral(m)
        when(action) {
            0 -> FpsKnifeChoreography.draw(end,m,1f)
            1,2 -> FpsKnifeChoreography.inspect(end,m,1f,action-1)
            3,4 -> FpsKnifeChoreography.attack(end,m,1f,action==4,0)
        }
        val a=FpsKnifeRig.frames(base); val b=FpsKnifeRig.frames(end)
        check(abs(a.hand.dot(b.hand))>.99999f && abs(a.blade.dot(b.blade))>.99999f)
        val mesh=FpsKnifeMeshes.getMesh(m); val scale=FpsKnifeChoreography.profile(m).scale
        val am=FpsKnifeRig.bladeMatrix(base,mesh,a.blade,scale); val bm=FpsKnifeRig.bladeMatrix(end,mesh,b.blade,scale)
        val aa=FloatArray(16);val bb=FloatArray(16);am.get(aa);bm.get(bb)
        check(aa.indices.all { abs(aa[it]-bb[it])<.00001f }) { "Endpoint snap: $m/$action" }
        checks++
    }
    // Folded length physically fits inside the handle, independent of the emitted transforms.
    for(m in listOf(KnifeModel.FLIP_KNIFE,KnifeModel.STILETTO)) {
        val mesh=FpsKnifeMeshes.getMesh(m);val blade=mesh.foldingBlade!!
        val furthest=blade.quads.flatMap { listOf(it.v0,it.v1,it.v2,it.v3) }.maxOf { it.x }
        val handleStart=mesh.mainQuads.flatMap { listOf(it.v0,it.v1,it.v2,it.v3) }.minOf { it.x }
        check(furthest-blade.pivotX <= blade.pivotX-handleStart)
    }
    println("PASS: $checks complete-action grip/matrix endpoints; both folding blade lengths fit their handles")
}

private fun validate(p:FpsKnifePose,m:KnifeModel,side:Float,slim:Boolean,label:String) {
    val profile=FpsKnifeChoreography.profile(m)
        val f=FpsKnifeRig.frames(p)
        val main=FpsKnifeRig.canonicalArmMatrix(Vector3f(p.mainArmX,p.mainArmY,p.mainArmZ),f.hand,side,slim,FpsKnifeChoreography.HAND_THICKNESS)
        val off=FpsKnifeRig.canonicalArmMatrix(Vector3f(p.offArmX,p.offArmY,p.offArmZ),FpsKnifeRig.armRotation(p.offArmYawY,p.offArmPitchX,p.offArmRollZ),-side,slim,FpsKnifeChoreography.HAND_THICKNESS)
        val mesh=FpsKnifeMeshes.getMesh(m)
        FpsKnifeRig.clearFlight(p,mesh,f,profile.scale,FpsKnifeRig.armBounds(main,side,slim),FpsKnifeRig.armBounds(off,-side,slim))
        val bm=FpsKnifeRig.bladeMatrix(p,mesh,f.blade,profile.scale)
        val bladeBounds=FpsKnifeRig.meshBounds(mesh,bm,p.safeHandleDeg,p.biteHandleDeg,p.bladeFoldDeg)
        val mainBounds=FpsKnifeRig.armBounds(main,side,slim)
        val offBounds=FpsKnifeRig.armBounds(off,-side,slim)
        val delta=FpsKnifeRig.supportCorrection(mainBounds,offBounds,bladeBounds)
        val corrected=FpsKnifeRig.Bounds().also { it.min.set(offBounds.min).add(delta); it.max.set(offBounds.max).add(delta) }
        check(!corrected.overlaps(bladeBounds,.024f) && !corrected.overlaps(mainBounds,.024f)) { "Support collision $label" }
        check(delta.z==0f || corrected.max.z < -.17f) // solver may not push a sleeve into the camera
        val local=Matrix4f(main).invert().mul(bm)
        val lo=Vector3f((if(side>0) (if(slim) -2f else -3f) else -1f)/16f-.25f/16f,-2.25f/16f,-2.25f/16f)
        val hi=Vector3f((if(side>0) 1f else (if(slim) 2f else 3f))/16f+.25f/16f,10.25f/16f,2.25f/16f)
        fun test(qs: List<FpsKnifeMeshes.KnifeQuad>,mat: Matrix4f,what:String,all:Boolean=false) {
            for(q in qs) if(all || q.isBlade) {
                val v=listOf(q.v0,q.v1,q.v2,q.v3).map { mat.transformPosition(Vector3f(it.x,it.y,it.z)) }
                for(ids in listOf(intArrayOf(0,1,2),intArrayOf(0,2,3))) {
                    triangles++
                    if(triangleHits(v[ids[0]],v[ids[1]],v[ids[2]],lo,hi)) {
                        hits++; if(errors.size<40) errors.add("$label $what")
                    }
                }
            }
        }
        test(mesh.mainQuads,local,"main")
        mesh.foldingBlade?.let { part ->
            // A closed blade is intentionally inside the handle cavity. Only exposed opening
            // frames are contact-tested against the palm; the cavity fit is checked separately.
            if(p.bladeFoldDeg<170f) test(part.quads, Matrix4f(local).translate(part.pivotX,part.pivotY,0f).rotateZ(Math.toRadians(p.bladeFoldDeg.toDouble()).toFloat()).translate(-part.pivotX,-part.pivotY,0f),"folder")
        }
        mesh.biteHandle?.let { part ->
            if(p.heldBalisongBlend==1f) test(part.quads,Matrix4f(local).translate(part.pivotX,part.pivotY,0f).rotateZ(Math.toRadians(p.biteHandleDeg.toDouble()).toFloat()).translate(-part.pivotX,-part.pivotY,0f),"bite",true)
        }
        check(listOf(p.mainArmX,p.mainArmY,p.mainArmZ,p.knifeViewRollZ,p.ringSpinDeg,p.bladeFoldDeg).all { it.isFinite() })
        frames++
}

private fun liveStreams() {
    for(m in KnifeModel.entries) for(style in Block18Style.entries) for(side in listOf(-1f,1f)) for(slim in listOf(false,true)) {
        FpsKnifeAnimator.reset()
        FpsKnifeAnimator.checkEquipChange(0,m,FpsRenderMode.KNIFE_VIEWMODEL,true)
        for(i in 0 until 1440) {
            if(i==96 || i==228 || i==540 || i==1080) FpsKnifeAnimator.triggerInspect()
            if(i==288 || i==432 || i==840 || i==1320) FpsKnifeAnimator.triggerAttack(i==432,i==840,false)
            if(i==1248) FpsKnifeAnimator.checkEquipChange(1,m,FpsRenderMode.KNIFE_VIEWMODEL,true)
            val walking=i in 600..779; val blocking=i in 780..959
            val p=FpsKnifePose()
            FpsKnifeAnimator.evaluatePose(p,FpsRenderMode.KNIFE_VIEWMODEL,m,InspectStyle.AUTO_BY_MODEL,style,18f,1f,1f,1f,true,0f,
                i*.75f,12f*kotlin.math.sin(i*.01f),if(walking) .24f else 0f,walking && i>690,i !in 1140..1199,blocking,i in 1380..1439,
                allowAirInspect=i !in 1160..1199,frameTimeNanos=1_000_000_000L+i*8_333_333L)
            validate(p,m,side,slim,"live/$m/$style/$side/$slim/$i")
        }
    }
}
