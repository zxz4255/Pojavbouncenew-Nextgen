import net.ccbluex.liquidbounce.features.module.modules.render.neoraven.*
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import org.joml.Matrix4f
import org.joml.Vector4f
import kotlin.math.*

private fun near(a: Float, b: Float, e: Float = 0.00001f) = check(abs(a-b) < e) { "$a != $b" }

fun main() {
    val world = Any()
    val a = NeoEyeAdaptation()
    near(a.update(world, 0f, 0), 1.30f)
    val darkToLight = a.update(world, 1f, 100_000_000)
    near(darkToLight, 1.30f + (.88f - 1.30f) * (1f - exp(-.3f)))
    val before = a.exposure
    near(a.update(world, 0f, 200_000_000), before + (1.30f-before)*(1f-exp(-.11f)))
    val beforeStall = a.exposure
    near(a.update(world, 1f, 10_000_000_000), beforeStall + (.88f-beforeStall)*(1f-exp(-.6f)))
    near(a.update(Any(), 1f, 11_000_000_000), .88f)
    // Exponential response is FPS independent before the upstream .2-second stall clamp.
    fun adapt(fps: Int): Float {
        val state = NeoEyeAdaptation(); state.update(world,0f,0)
        for (i in 1..fps) state.update(world,1f, i * 1_000_000_000L / fps)
        return state.exposure
    }
    for (fps in listOf(15,30,60,90,144,240)) near(adapt(fps), adapt(60), .000002f)
    println("PASS: eye adaptation defaults, asymmetric exponential speeds, world reset, stall cap and 15–240 FPS.")

    val m = NeoRainExposure()
    for (x in -2049..2049) {
        val o = NeoRainExposure.origin(x + .3)
        check(o % 16 == 0 && x - o in 48..63)
        val wrap = NeoRainExposure.wrap(x + .3)
        check(wrap >= 0 && wrap < 1024)
        near(wrap, NeoRainExposure.wrap(x + .3 + 1024.0), .0001f)
    }
    check(m.needsRefresh(world, -.1, 0.0, 50))
    m.refreshed(world,-.1,0.0,50)
    check(!m.needsRefresh(world,-15.9,15.9,59))
    check(m.needsRefresh(world,-.1,0.0,60))
    check(m.needsRefresh(world,-.1,0.0,49))
    check(m.needsRefresh(world,0.0,0.0,51))
    check(m.needsRefresh(Any(),-.1,0.0,51))
    m.invalidate(); check(m.needsRefresh(world,-.1,0.0,51))
    var heights = 0
    for (minimum in listOf(-2032,-64,0,256)) for (y in minimum..minimum+4095) {
        val packed = m.encodeHeight(y,minimum)
        check((packed and 255) + ((packed ushr 8) and 255)*256 + minimum == y)
        heights++
    }
    println("PASS: 4099 chunk/noise origins, refresh/movement/time rollback/world invalidation; $heights modern heights round-trip.")

    var projections = 0
    for (modern in listOf(false,true)) for (fov in listOf(30f,70f,110f)) {
        val p = Matrix4f().setPerspective(Math.toRadians(fov.toDouble()).toFloat(), 16f/9f,.05f,512f)
        if(modern) p.m22(0f).m32(.05f)
        check(NeoDepthConvention.zeroToOne(p) == modern)
        check(NeoDepthConvention.reversed(p) == modern)
        val inverse = Matrix4f(p).invert()
        for (distance in listOf(.06f,.5f,3f,32f,47f,128f)) for(x in listOf(-.4f,0f,.4f)) {
            val point = Vector4f(x*distance,.1f*distance,-distance,1f)
            val clip = p.transform(Vector4f(point)); clip.div(clip.w)
            val raw = if(modern) clip.z else clip.z*.5f+.5f
            val rebuilt = inverse.transform(Vector4f(clip.x,clip.y,if(modern) raw else raw*2-1,1f))
            rebuilt.div(rebuilt.w)
            check(abs(rebuilt.z-point.z) / distance < .0002f)
            projections++
        }
    }
    check(ClientUniformDefine.CINEMATIC.size == 128)
    check(ClientUniformDefine.NEO_RAIN.size == 256)
    println("PASS: $projections conventional/reversed-depth reconstructions at 3 FOVs; actual std140 UBO sizes 128/256 bytes.")
}
