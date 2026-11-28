import net.ccbluex.liquidbounce.features.module.modules.render.fpsknife.*
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.abs

private val inspect = FpsKnifeAnimator::class.java.getDeclaredMethod("applyInspectAnimation", FpsKnifePose::class.java, KnifeModel::class.java, InspectStyle::class.java, Float::class.javaPrimitiveType).apply { isAccessible = true }
private fun pose(model: KnifeModel, style: InspectStyle, phase: Float): FpsKnifePose {
    FpsKnifeAnimator.reset()
    return FpsKnifePose().also {
        FpsKnifeAnimator.evaluatePose(it,FpsRenderMode.KNIFE_VIEWMODEL,model,style,Block18Style.TACTICAL_PARRY,22f,1f,0f,1f,true,0f,0f,0f,0f,false,true,false,false)
        inspect.invoke(FpsKnifeAnimator,it,model,style,phase)
    }
}
private fun partMatrix(base: Matrix4f, part: FpsKnifeMeshes.ArticulatedPart, angle: Float) =
    Matrix4f(base).translate(part.pivotX,part.pivotY,0f).rotateZ(Math.toRadians(angle.toDouble()).toFloat()).translate(-part.pivotX,-part.pivotY,0f)

// Triangle/AABB separating axis test in inverse-transformed model space (sleeve included).
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

fun main() {
    var frames=0; var tip=0; var butterfly=0; var bad=0; var triangles=0
    val errors=linkedSetOf<String>()
    for (model in KnifeModel.entries) for (style in InspectStyle.entries) for(i in 0..100) {
        val p=pose(model,style,i/100f)
        if(style!=InspectStyle.BAYONET_TOSS) {
            check(p.knifeRelease==0f && p.knifeFreePoseBlend==0f)
            check(p.knifePalmOffsetX==0f && p.knifePalmOffsetY==0f && p.knifePalmOffsetZ==0f)
        }
        if(style==InspectStyle.BALISONG_FAN && model==KnifeModel.BUTTERFLY) {
            check(p.safeHandleDeg==0f && p.ringSpinDeg==0f)
            if(p.biteHandleDeg!=0f) check(p.heldBalisongBlend==1f)
        }
        if(p.palmFrontSpinBlend>0f && p.ringSpinDeg!=0f) check(p.palmFrontSpinBlend==1f)
        frames++
    }
    for(model in KnifeModel.entries) for(style in listOf(InspectStyle.RING_SPIN,InspectStyle.BALISONG_FAN)) {
        if(style==InspectStyle.BALISONG_FAN && model!=KnifeModel.BUTTERFLY) continue
        val mesh=FpsKnifeMeshes.getMesh(model)
        for(scale in listOf(.55f,1f,1.65f)) for(slim in listOf(false,true)) for(side in listOf(-1f,1f)) for(requested in listOf(.48f,.62f,.85f)) for(i in 0..100) {
            val p=pose(model,style,i/100f); val f=FpsKnifeRig.frames(p)
            val palm=Vector3f(p.mainArmX,p.mainArmY,p.mainArmZ)
            val arm=FpsKnifeRig.canonicalArmMatrix(palm,f.hand,side,slim,FpsKnifeRig.thickness(requested,scale,true))
            val bm=FpsKnifeRig.bladeMatrix(p,mesh,f.blade,scale)
            val local=Matrix4f(arm).invert().mul(bm)
            val lowX=(if(side>0) (if(slim) -2f else -3f) else -1f)/16f-.25f/16f
            val highX=(if(side>0) 1f else (if(slim) 2f else 3f))/16f+.25f/16f
            fun inArm(v: FpsKnifeMeshes.KnifeVertex, m: Matrix4f): Boolean {
                val q=m.transformPosition(Vector3f(v.x,v.y,v.z))
                return q.x > lowX+1e-5f && q.x < highX-1e-5f && q.y > -2.25f/16f+1e-5f && q.y < 10.25f/16f-1e-5f && abs(q.z)<2.25f/16f-1e-5f
            }
            fun verify(quads: List<FpsKnifeMeshes.KnifeQuad>, m: Matrix4f, what: String, bodyOnly: Boolean=false) {
                for(q in quads) for(v in listOf(q.v0,q.v1,q.v2,q.v3)) if(!bodyOnly || v.x > .08f) {
                    if(p.palmFrontSpinBlend==1f) {
                        val t=m.transformPosition(Vector3f(v.x,v.y,v.z))
                        check(t.y >= 10.25f/16f) { "Palm-front plane cuts fingertip face" }
                    }
                    if(inArm(v,m)) { bad++; if(errors.size<30) errors.add("$model/$style/$scale/$slim/$side/$requested/$i $what") }
                }
            }
            // Always check sampled sharp blade body, including entering/leaving each regrip.
            verify(mesh.mainQuads,local,"blade-body",bodyOnly=true)
            if(p.palmFrontSpinBlend==1f) {
                verify(mesh.mainQuads,local,"tip-main")
                mesh.safeHandle?.let { verify(it.quads,partMatrix(local,it,p.safeHandleDeg),"tip-safe") }
                mesh.biteHandle?.let { verify(it.quads,partMatrix(local,it,p.biteHandleDeg),"tip-bite") }
                tip++
            }
            if(p.heldBalisongBlend==1f) {
                val safeGrip=bm.transformPosition(Vector3f(mesh.gripPivotX,.010f,0f))
                val expected=f.hand.transform(Vector3f(0f,.68f*.9f/16f,0f)).add(palm)
                check(safeGrip.distance(expected)<1e-5f) { "Safe handle detached from hand" }
                check(inArm(FpsKnifeMeshes.KnifeVertex(mesh.gripPivotX,.010f,0f,0f,0f),local)) { "Safe grip is floating beyond fist" }
                // Whole triangles, not just their vertices: a long handle may straddle the fist.
                mesh.biteHandle?.let { part ->
                    val m=partMatrix(local,part,p.biteHandleDeg)
                    verify(part.quads,m,"held-bite")
                    val lo=Vector3f(lowX,-2.25f/16f,-2.25f/16f)
                    val hi=Vector3f(highX,10.25f/16f,2.25f/16f)
                    for((quads, transform) in listOf(part.quads to m, mesh.mainQuads to local)) for(q in quads) {
                        val v=listOf(q.v0,q.v1,q.v2,q.v3).map { transform.transformPosition(Vector3f(it.x,it.y,it.z)) }
                        for(t in listOf(intArrayOf(0,1,2),intArrayOf(0,2,3))) {
                            triangles++
                            if(triangleHits(v[t[0]],v[t[1]],v[t[2]],lo,hi)) { bad++; if(errors.size<30) errors.add("triangle:$scale/$slim/$side/$requested/$i") }
                        }
                    }
                }
                butterfly++
            }
        }
    }
    if(errors.isNotEmpty()) println(errors)
    check(bad==0) { "Held inspect intersects sleeve in sampled poses" }
    println("PASS: $frames inspect policy frames (only Toss releases); $tip palm-front spin clearance poses; $butterfly safe-handle grips embedded in hand; $triangles blade/moving-handle triangle-vs-sleeve tests, $bad intersections.")
}
