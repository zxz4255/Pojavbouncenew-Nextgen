import net.ccbluex.liquidbounce.features.module.modules.render.fpsknife.*
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs

private val clock = FpsKnifeAnimator::class.java.getDeclaredField("lastFrameNanos").apply { isAccessible = true }
private val action = FpsKnifeAnimator::class.java.getDeclaredField("currentAction").apply { isAccessible = true }
private val inspect = FpsKnifeAnimator::class.java.getDeclaredMethod("applyInspectAnimation", FpsKnifePose::class.java, KnifeModel::class.java, InspectStyle::class.java, Float::class.javaPrimitiveType).apply { isAccessible = true }
private fun step(m: KnifeModel = KnifeModel.entries.first(), ground: Boolean = true, air: Boolean = true, moving: Boolean = false, auto: Float = 0f, busy: Boolean = false, block: Boolean = false): FpsKnifePose {
    clock.setLong(FpsKnifeAnimator, System.nanoTime() - 16_666_667L)
    return FpsKnifePose().also {
        FpsKnifeAnimator.evaluatePose(it,FpsRenderMode.KNIFE_VIEWMODEL,m,InspectStyle.RING_SPIN,Block18Style.TACTICAL_PARRY,22f,1f,1f,1f,true,auto,0f,0f,if(moving) .2f else 0f,moving,ground,block,busy,air,1.15f)
    }
}
private fun same(a: Quaternionf, b: Quaternionf) = abs(a.dot(b)) > .99999f
fun main() {
    val input = FpsKnifeAttackInput()
    check(input.physical(1000)); check(!input.entity(1001)); check(!input.held(1020,true))
    check(input.physical(1030)); check(input.physical(1060)); check(!input.entity(1061))
    check(!input.held(1190,true)); check(input.held(1501,true)); check(!input.held(1502,true))
    input.held(1510,false); check(input.held(1520,true)); check(!input.entity(1521))
    input.reset(); check(input.entity(2000)); check(!input.entity(2050)); check(input.entity(2100))
    println("PASS: raw short/rapid clicks, entity de-duplication, touch rising edge and hold-repeat.")

    FpsKnifeAnimator.reset(); FpsKnifeAnimator.triggerInspect(); step(ground=false,moving=true)
    check(action.get(FpsKnifeAnimator).toString()=="INSPECT")
    step(ground=false,air=false); check(action.get(FpsKnifeAnimator).toString()=="NONE")
    FpsKnifeAnimator.triggerInspect(); step(ground=false,busy=true); check(action.get(FpsKnifeAnimator).toString()=="NONE")
    FpsKnifeAnimator.reset(); repeat(80) { step(ground=false,auto=.3f) }; check(action.get(FpsKnifeAnimator).toString()=="NONE")
    FpsKnifeAnimator.triggerInspect(); step(block=true); check(action.get(FpsKnifeAnimator).toString()=="NONE")
    FpsKnifeAnimator.reset(); FpsKnifeAnimator.triggerAttack(false); FpsKnifeAnimator.triggerAttack(false); FpsKnifeAnimator.triggerAttack(false)
    val queued = FpsKnifeAnimator::class.java.getDeclaredField("queuedAttacks").apply { isAccessible = true }
    check(queued.getInt(FpsKnifeAnimator)==2)
    var attacks=1; var oldQueue=2
    repeat(100) { step(); val q=queued.getInt(FpsKnifeAnimator); if(q<oldQueue) attacks++; oldQueue=q }
    check(attacks==3 && action.get(FpsKnifeAnimator).toString()=="NONE")
    println("PASS: manual airborne inspect, AirInspect off/busy/block cancellation, no airborne auto-inspect, three-click attack queue.")

    var clearChecks=0; var rigidChecks=0; var sockets=0; var inverseChecks=0
    var pommelChecks=0
    for (model in KnifeModel.entries) for (slim in listOf(false,true)) for (side in listOf(-1f,1f)) for (scale in listOf(.55f,1f,1.65f)) {
        val mesh=FpsKnifeMeshes.getMesh(model)
        val thick=FpsKnifeRig.thickness(.62f,scale,true)
        check(thick <= scale*.70001f)
        FpsKnifeAnimator.reset(); val base=step(model)
        val baseFrames=FpsKnifeRig.frames(base)
        val relation=Quaternionf(baseFrames.blade).invert().mul(baseFrames.hand)
        val palm=Vector3f(base.mainArmX,base.mainArmY,base.mainArmZ)
        val canonical=FpsKnifeRig.canonicalArmMatrix(palm,baseFrames.hand,side,slim,thick)
        val vanilla=Matrix4f().translate(-side*5f/16f,2f/16f,0f).rotateZ(side*.1f)
        val actual=FpsKnifeRig.avatarArmMatrix(palm,baseFrames.hand,side,slim,thick).mul(vanilla)
        val a=FloatArray(16); val b=FloatArray(16); canonical.get(a); actual.get(b)
        check(a.indices.all { abs(a[it]-b[it]) < 1e-5f }); inverseChecks++
        // Confirm a handle end exists OUTSIDE the fist cuboid; insertion is intentional at the grip.
        val bm=FpsKnifeRig.bladeMatrix(base,mesh,baseFrames.blade,scale)
        val local=Matrix4f(canonical).invert().mul(bm)
        val all=mesh.mainQuads+(mesh.safeHandle?.quads?:emptyList())+(mesh.biteHandle?.quads?:emptyList())
        val handleEnds=all.flatMap { listOf(it.v0,it.v1,it.v2,it.v3) }.filter { it.x < mesh.gripPivotX-.04f }
        val loX = (if (side > 0) (if (slim) -2f else -3f) else -1f) / 16f
        val hiX = (if (side > 0) 1f else (if (slim) 2f else 3f)) / 16f
        check(handleEnds.any { v -> val q=local.transformPosition(Vector3f(v.x,v.y,v.z));
            (q.x < loX) || (q.x > hiX) || (q.y > 10f/16f) || (q.y < -2f/16f) || (abs(q.z) > .125f)
        }) { "No exposed handle end: $model/$slim/$side/$scale" }; pommelChecks++
        // Inward neutral silhouette, independent of mirroring the whole rig.
        if(side>0) {
            val wrist=canonical.transformPosition(Vector3f(-1f/16f,0f,0f))
            check(wrist.x>palm.x) { "Main arm splays out at fist" }
            val offPalm=Vector3f(base.offArmX,base.offArmY,base.offArmZ)
            val off=FpsKnifeRig.canonicalArmMatrix(offPalm,FpsKnifeRig.armRotation(base.offArmYawY,base.offArmPitchX,base.offArmRollZ),-1f,slim,thick)
            val ow=off.transformPosition(Vector3f(1f/16f,0f,0f))
            check(ow.x<offPalm.x) { "Support arm splays out at fist" }
        }
        // Sampled blade body (beyond collar/handle hardware) must not enter the holding arm.
        // Rigid B^-1 H makes this identical throughout every still-gripped action.
        for (requested in listOf(.48f,.62f,.85f)) {
            val arm = FpsKnifeRig.canonicalArmMatrix(palm,baseFrames.hand,side,slim,FpsKnifeRig.thickness(requested,scale,true))
            val intoArm = Matrix4f(arm).invert().mul(bm)
            val sleeveLo = loX-.25f/16f; val sleeveHi = hiX+.25f/16f
            for(q in mesh.mainQuads) for(v in listOf(q.v0,q.v1,q.v2,q.v3)) if(v.x > .08f) {
                val t=intoArm.transformPosition(Vector3f(v.x,v.y,v.z))
                check(!(t.x in sleeveLo..sleeveHi && t.y in -2.25f/16f..10.25f/16f && t.z in -2.25f/16f..2.25f/16f)) { "Blade-body vertex intersects sleeve: $model/$scale/$slim/$side" }
            }
        }
        for (style in InspectStyle.entries) for (i in 0..100) {
            val p=step(model); inspect.invoke(FpsKnifeAnimator,p,model,style,i/100f)
            val f=FpsKnifeRig.frames(p)
            val socket=FpsKnifeRig.bladeMatrix(p,mesh,f.blade,scale).transformPosition(Vector3f(mesh.gripPivotX,mesh.gripPivotY,0f))
            val target=Vector3f(p.mainArmX+p.knifePalmOffsetX,p.mainArmY+p.knifePalmOffsetY,p.mainArmZ+p.knifePalmOffsetZ)
            // 073 held inspections deliberately move the socket onto the hand's front face.
            // Their attachment/clearance invariants are checked by verify073 instead.
            val ordinaryGrip = p.palmFrontSpinBlend==0f && p.heldBalisongBlend==0f
            if(ordinaryGrip) { check(socket.distance(target)<1e-5f); sockets++ }
            if(p.knifeRelease==0f && ordinaryGrip) { check(same(relation,Quaternionf(f.blade).invert().mul(f.hand))); rigidChecks++ }
            if(p.ringSpinDeg != 0f || abs(p.safeHandleDeg)>.01f || abs(p.biteHandleDeg)>.01f) check(p.knifeRelease>=.999f || p.palmFrontSpinBlend>=.999f || p.heldBalisongBlend>=.999f)
            val main=FpsKnifeRig.armBounds(FpsKnifeRig.canonicalArmMatrix(Vector3f(p.mainArmX,p.mainArmY,p.mainArmZ),f.hand,side,slim,thick),side,slim)
            val off=FpsKnifeRig.armBounds(FpsKnifeRig.canonicalArmMatrix(Vector3f(p.offArmX,p.offArmY,p.offArmZ),FpsKnifeRig.armRotation(p.offArmYawY,p.offArmPitchX,p.offArmRollZ),-side,slim,thick),-side,slim)
            FpsKnifeRig.clearFlight(p,mesh,f,scale,main,off)
            if(p.knifeRelease>=.999f) {
                val bounds=FpsKnifeRig.meshBounds(mesh,FpsKnifeRig.bladeMatrix(p,mesh,f.blade,scale),p.safeHandleDeg,p.biteHandleDeg)
                check(bounds.max.z <= minOf(main.min.z,off.min.z)-.0449f) { "Clearance failed $model/$style/$i" }; clearChecks++
            }
        }
    }
    val attackMethod=FpsKnifeAnimator::class.java.getDeclaredMethod("applyKnifeAttackAnimation",FpsKnifePose::class.java,KnifeModel::class.java,Float::class.javaPrimitiveType).apply { isAccessible=true }
    val heavy=FpsKnifeAnimator::class.java.getDeclaredField("currentAttackHeavy").apply { isAccessible=true }
    var attackRigs=0
    for(model in KnifeModel.entries) for(isHeavy in listOf(false,true)) {
        FpsKnifeAnimator.reset(); val neutral=step(model); val nf=FpsKnifeRig.frames(neutral)
        val relative=Quaternionf(nf.blade).invert().mul(nf.hand)
        heavy.setBoolean(FpsKnifeAnimator,isHeavy)
        for(i in 0..100){
            val p=step(model); attackMethod.invoke(FpsKnifeAnimator,p,model,i/100f)
            val f=FpsKnifeRig.frames(p)
            check(same(relative,Quaternionf(f.blade).invert().mul(f.hand)))
            check(p.knifeRelease==0f && p.ringSpinDeg==0f)
            if(i==34) check(Vector3f(p.mainArmX,p.mainArmY,p.mainArmZ).distance(Vector3f(neutral.mainArmX,neutral.mainArmY,neutral.mainArmZ))>.30f)
            attackRigs++
        }
    }
    println("PASS: $attackRigs attack rigid-grip frames, sampled blade-body vertices outside sleeve at min/default/max thickness, >0.30 view-unit power-stroke travel.")
    println("PASS: $sockets grip sockets, $rigidChecks rigid-grip inspect frames, $clearChecks fully released blade/handle-to-both-sleeves depth clearances; $inverseChecks vanilla transform inverses, $pommelChecks exposed-handle-end checks; inward neutral arms.")
}
