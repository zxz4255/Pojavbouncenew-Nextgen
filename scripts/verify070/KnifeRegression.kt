import net.ccbluex.liquidbounce.features.module.modules.render.fpsknife.*
import org.joml.Matrix4f
import org.joml.Vector3f

fun main() {
    var quads = 0
    var poses = 0
    var socketChecks = 0
    val clock = FpsKnifeAnimator::class.java.getDeclaredField("lastFrameNanos").apply { isAccessible = true }
    val action = FpsKnifeAnimator::class.java.getDeclaredField("currentAction").apply { isAccessible = true }
    fun step(model: KnifeModel, mode: FpsRenderMode, style: InspectStyle, guard: Block18Style, tilt: Float, moving: Boolean = false, blocking: Boolean = false): FpsKnifePose {
        clock.setLong(FpsKnifeAnimator, System.nanoTime() - 16_666_667L)
        val p = FpsKnifePose()
        FpsKnifeAnimator.evaluatePose(p, mode, model, style, guard, tilt, 1f, 1f, 1f, true, 0f, 20f, -10f, if (moving) 0.2f else 0f, moving, true, blocking, false)
        for (f in FpsKnifePose::class.java.declaredFields) {
            if (f.type == Float::class.javaPrimitiveType) {
                f.isAccessible = true
                check(f.getFloat(p).isFinite()) { "Nonfinite ${f.name} for $model/$style/$mode" }
            }
        }
        check(p.knifeVisible == (mode == FpsRenderMode.KNIFE_VIEWMODEL))
        poses++
        return p
    }
    for (m in KnifeModel.entries) {
        val mesh = FpsKnifeMeshes.getMesh(m)
        val qs = mesh.mainQuads + (mesh.safeHandle?.quads ?: emptyList()) + (mesh.biteHandle?.quads ?: emptyList())
        check(qs.isNotEmpty())
        for (q in qs) {
            for (v in listOf(q.v0,q.v1,q.v2,q.v3)) check(listOf(v.x,v.y,v.z,v.u,v.v).all { it.isFinite() })
            check(listOf(q.nx,q.ny,q.nz).all { it.isFinite() })
        }
        quads += qs.size
        for (scale in listOf(0.55f,1f,1.65f)) for (side in listOf(-1f,1f)) {
            val socket=Vector3f(0.31f*side,-0.34f,-0.72f)
            val transform=Matrix4f().translate(socket).rotateZ(-0.4f*side).rotateY(2.93f).rotateX(0.66f).scale(scale).translate(-mesh.gripPivotX,-mesh.gripPivotY,0f)
            val grip=transform.transformPosition(Vector3f(mesh.gripPivotX,mesh.gripPivotY,0f))
            check(grip.distance(socket) < 1e-5f)
            socketChecks++
        }
        for (mode in FpsRenderMode.entries) for (style in InspectStyle.entries) for (guard in Block18Style.entries) for (tilt in listOf(0f,22f,30f)) {
            FpsKnifeAnimator.reset()
            step(m,mode,style,guard,tilt)
            FpsKnifeAnimator.triggerInspect(true,true)
            repeat(15) { step(m,mode,style,guard,tilt) }
            step(m,mode,style,guard,tilt,moving=true)
            check(action.get(FpsKnifeAnimator).toString() == "NONE") { "Inspect did not cancel on movement" }
            FpsKnifeAnimator.triggerInspect(true,false)
            check(action.get(FpsKnifeAnimator).toString() == "NONE")
            FpsKnifeAnimator.triggerAttack(false,true,mode==FpsRenderMode.EMPTY_HAND_BOXING)
            repeat(30) { step(m,mode,style,guard,tilt,blocking=true) }
        }
    }
    println("PASS: ${KnifeModel.entries.size} meshes, $quads quads, $poses finite pose snapshots, $socketChecks palm-pivot scale/mirror checks; idle inspect gating and movement cancellation.")
}
