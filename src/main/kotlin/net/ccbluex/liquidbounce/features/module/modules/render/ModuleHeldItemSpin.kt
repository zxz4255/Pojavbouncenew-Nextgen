package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.interfaces.EntityRenderStateAddition
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.abs

/** Client-side rendering only; no packets or inventory changes. */
object ModuleHeldItemSpin : ClientModule("HeldItemSpin", ModuleCategories.RENDER) {
    private val firstPerson by boolean("FirstPerson", true)
    private val thirdPerson by boolean("ThirdPerson", true)
    private val angle by float("Angle", 0f, -180f..180f, "°")
    // Fixed fore/aft lean relative to the pre-arm view, applied before the Y spin.
    private val tilt by float("Tilt", 0f, -90f..90f, "°")
    private val itemScale by float("Scale", 1f, 0.1f..3f)
    private val roll by float("Roll", 0f, -180f..180f, "°")
    private val speedValue = float("Speed", 90f, -720f..720f, "°/s").onChanged { newSpeed ->
        synchronized(clockLock) {
            advanceClock(System.nanoTime())
            clockSpeed = newSpeed
        }
    }
    private val speed by speedValue

    private val clockLock = Any()
    private var phase = 0.0
    private var clockSpeed = 90f
    private var lastNanos = 0L

    override fun onEnabled() {
        synchronized(clockLock) {
            phase = 0.0
            clockSpeed = speed
            lastNanos = System.nanoTime()
        }
    }

    private fun advanceClock(now: Long) {
        if (lastNanos != 0L) {
            val elapsed = ((now - lastNanos).coerceAtLeast(0L) / 1_000_000_000.0).coerceAtMost(1.0)
            phase = (phase + elapsed * clockSpeed) % 360.0
        }
        lastNanos = now
    }

    private fun spinAngle(): Float = synchronized(clockLock) {
        advanceClock(System.nanoTime())
        (phase + angle).toFloat()
    }

    private data class Basis(val matrix: Matrix4f, val spin: Float, val tilt: Float, val roll: Float, val size: Float)
    private val context = ThreadLocal<Basis?>()

    /** Mixin wrappers bracket the vanilla render with a nested, exception-safe context. */
    @JvmStatic
    fun withFirstPerson(stack: PoseStack, action: Runnable) {
        withBasis(stack, running && firstPerson, false, action)
    }

    @JvmStatic
    fun withThirdPerson(stack: PoseStack, state: ArmedEntityRenderState, action: Runnable) {
        val entity = (state as EntityRenderStateAddition).`liquid_bounce$getEntity`()
        withBasis(stack, running && thirdPerson && mc.player != null && entity === mc.player, true, action)
    }

    private fun withBasis(stack: PoseStack, enabled: Boolean, third: Boolean, action: Runnable) {
        if (!enabled) { action.run(); return }
        val prior = context.get()
        val base = Matrix4f(stack.last().pose())
        // LivingEntityRenderer flips model X/Y before rendering layers. Undo that flip for items.
        if (third) base.rotateZ(Math.PI.toFloat())
        context.set(Basis(base, spinAngle(), tilt, roll, itemScale))
        try { action.run() } finally {
            if (prior == null) context.remove() else context.set(prior)
        }
    }

    @JvmStatic
    fun active(): Boolean = context.get() != null

    /** Rebuild in the pre-arm basis while retaining the current center and model scale. */
    private fun upright(pose: PoseStack.Pose, pivot: Float): Boolean {
        val basis = context.get() ?: return false
        val base = basis.matrix
        if (abs(base.determinant()) < 1.0e-8f) return false
        val current = Matrix4f(pose.pose())
        val relative = Matrix4f(base).invert().mul(current)
        val center = relative.transformPosition(Vector3f(pivot, pivot, pivot))
        val scale = relative.getScale(Vector3f())
        if (!scale.x.isFinite() || !scale.y.isFinite() || !scale.z.isFinite() ||
            scale.x < 1.0e-6f || scale.y < 1.0e-6f || scale.z < 1.0e-6f) return false
        // Preserve reflection for negatively scaled model/resource-pack transforms.
        if (relative.determinant() < 0f) scale.x = -scale.x
        pose.setIdentity()
        pose.mulPose(base)
        pose.translate(center.x, center.y, center.z)
        // Lean is fixed in the pre-arm basis; the item spins around its now-tilted Y axis.
        pose.rotateDegrees(Axis.XP, basis.tilt)
        pose.rotateDegrees(Axis.YP, basis.spin)
        pose.rotateDegrees(Axis.ZP, basis.roll)
        pose.scale(scale.x * basis.size, scale.y * basis.size, scale.z * basis.size)
        pose.translate(-pivot, -pivot, -pivot)
        return true
    }

    /** Called after the vanilla item display transform, before special-model local transforms. */
    @JvmStatic
    fun uprightItem(pose: PoseStack.Pose) { upright(pose, 0.5f) }

    /** The map renderer itself adds Y180/Z180; cancel them so its sheet remains upright. */
    @JvmStatic
    fun uprightMap(stack: PoseStack) {
        if (!active()) return
        if (!upright(stack.last(), 0f)) return
        stack.rotateDegrees(Axis.ZP, 180f)
        stack.rotateDegrees(Axis.YP, 180f)
    }
}
