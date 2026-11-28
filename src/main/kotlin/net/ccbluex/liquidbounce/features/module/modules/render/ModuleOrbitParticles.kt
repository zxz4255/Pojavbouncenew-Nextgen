/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.textures.FilterMode
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientTesselator
import net.ccbluex.liquidbounce.render.bindDynamicTransformsUniform
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getDynamicTransformsUniform
import net.ccbluex.liquidbounce.render.mesh.MeshDraw.DefaultUploader.bindAndDraw
import net.ccbluex.liquidbounce.render.mesh.MeshDraw.DefaultUploader.toMeshDraw
import net.ccbluex.liquidbounce.render.setPipeline
import net.ccbluex.liquidbounce.render.setUniform
import net.ccbluex.liquidbounce.utils.client.clientIdentifier
import net.ccbluex.liquidbounce.utils.item.isAxe
import net.ccbluex.liquidbounce.utils.item.isSpear
import net.ccbluex.liquidbounce.utils.item.isSword
import net.ccbluex.liquidbounce.utils.network.entityIdC2SInteractOrAttack
import net.ccbluex.liquidbounce.utils.render.begin
import net.minecraft.client.CameraType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.joml.Matrix4f
import org.joml.Vector4f
import java.util.ArrayDeque
import java.util.concurrent.ThreadLocalRandom
import java.util.function.Supplier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Orbit Particles / Weapon Wisps (`轨道粒子`) — ported with 100% visual fidelity from NeoRaven
 * (`WeaponWispsModule` + `WeaponWispRenderer` + `WispMotion`).
 *
 * Three luminous wisps with 3-layer tapered ribbons and 3-layer additive Gaussian glow heads that:
 * - Drift independently along the 3D held weapon blade in first-person (`WispMotion.weapon`),
 * - Orbit smoothly around the player when idle or in third-person (`WispMotion.home`),
 * - Launch with velocity-preserving cubic-Hermite flight into a cross-body figure-8 strike weave
 *   around attacked targets (`WispMotion.strike`).
 */
object ModuleOrbitParticles : ClientModule(
    "OrbitParticles",
    ModuleCategories.RENDER,
    aliases = listOf("WeaponWisps"),
) {

    private val wispColor by color("Color", Color4b(0xB4, 0xC4, 0xFF, 0xFF))
    private val size by float("Size", 0.075f, 0.03f..0.18f, "m")
    private val heldRadius by float("HeldRadius", 0.8f, 0.3f..1.8f, "m")
    private val attackRadius by float("AttackRadius", 0.8f, 0.3f..1.8f, "m")
    private val speed by float("Speed", 1.0f, 0.25f..3.0f)
    private val trailLength by float("TrailLength", 0.45f, 0.1f..1.0f, "s")
    private val attackDuration by float("AttackDuration", 1.8f, 0.5f..4.0f, "s")

    private const val COUNT = 3
    private const val TAU = PI * 2.0
    private const val SAMPLE_NS = 8_333_333L
    private const val FLIGHT_NS = 320_000_000L
    private const val MAX_HAND_AGE_NS = 250_000_000L
    private const val SWORD_HOME = 1L
    private const val PLAYER_HOME = 2L

    private class Point(val x: Double, val y: Double, val z: Double) {
        fun plus(p: Point) = Point(x + p.x, y + p.y, z + p.z)
        fun minus(p: Point) = Point(x - p.x, y - p.y, z - p.z)
        fun times(s: Double) = Point(x * s, y * s, z * s)
        fun cross(p: Point) = Point(y * p.z - z * p.y, z * p.x - x * p.z, x * p.y - y * p.x)
        fun length() = sqrt(x * x + y * y + z * z)
        fun unit(): Point {
            val n = length()
            return if (n < 1e-9) Point(1.0, 0.0, 0.0) else times(1.0 / n)
        }

        companion object {
            val ZERO = Point(0.0, 0.0, 0.0)
            fun mix(a: Point, b: Point, t: Double) = Point(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t,
            )
        }
    }

    private class Sample(val point: Point, val time: Long)

    // Motion state (1:1 port of WispMotion)
    private val points = arrayOfNulls<Point>(COUNT)
    private val from = arrayOfNulls<Point>(COUNT)
    private val velocity = arrayOfNulls<Point>(COUNT)
    private val launchVelocity = arrayOfNulls<Point>(COUNT)
    private val trails = Array(COUNT) { ArrayDeque<Sample>() }
    private var motionKey = Long.MIN_VALUE
    private var motionChanged = 0L
    private var previousTime = 0L
    private var sampledTime = 0L

    // Module state
    private var target: LivingEntity? = null
    private var beganNs = 0L
    private var attackUntilNs = 0L
    private var handTimeNs = 0L
    private var driftSeed = 0L
    private var handHome: Array<Point>? = null
    private var previousOwnerPos: Point? = null
    private var handDraw = false

    // World & hand matrices
    private val worldViewMat = Matrix4f()
    private val worldProjMat = Matrix4f()
    private val invWorldViewProj = Matrix4f()
    private val handBladeMat = Matrix4f()
    private val handProjMat = Matrix4f()
    private val worldToHandMat = Matrix4f()
    private val identityMat = Matrix4f()
    private val tmpVec4 = Vector4f()

    private var camOrigin = Point.ZERO
    private var camRight = Point(1.0, 0.0, 0.0)
    private var camUp = Point(0.0, 1.0, 0.0)
    private var worldValid = false
    private var handValid = false
    private var inMainHandSubmit = false
    private var capturedHandProjSlice: GpuBufferSlice? = null

    private var wispTexture: DynamicTexture? = null
    private val wispTexId = clientIdentifier("orbit_particles_gaussian_64")

    override fun onEnabled() {
        clearState()
        super.onEnabled()
    }

    override fun onDisabled() {
        clearState()
        wispTexture?.close()
        wispTexture = null
        super.onDisabled()
    }

    private fun clearMotion() {
        motionKey = Long.MIN_VALUE
        motionChanged = 0L
        previousTime = 0L
        sampledTime = 0L
        for (i in 0 until COUNT) {
            points[i] = null
            from[i] = null
            velocity[i] = null
            launchVelocity[i] = null
            trails[i].clear()
        }
    }

    private fun clearState() {
        clearMotion()
        worldValid = false
        handValid = false
        target = null
        handHome = null
        previousOwnerPos = null
        handDraw = false
        beganNs = System.nanoTime()
        driftSeed = ThreadLocalRandom.current().nextLong()
        attackUntilNs = 0L
        handTimeNs = 0L
        capturedHandProjSlice = null
    }

    private fun isWeapon(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        return stack.isSword || stack.isAxe || stack.isSpear ||
            stack.item == Items.MACE || stack.item == Items.TRIDENT
    }

    private fun firstPerson(): Boolean =
        mc.options.cameraType == CameraType.FIRST_PERSON && mc.cameraEntity === mc.player

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        clearState()
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        onAttackedEntity(event.entity)
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.OUTGOING || event.isCancelled) return@handler
        val id = event.packet.entityIdC2SInteractOrAttack ?: return@handler
        mc.execute {
            val entity = mc.level?.getEntity(id) ?: return@execute
            onAttackedEntity(entity)
        }
    }

    private fun onAttackedEntity(entity: Entity) {
        val p = mc.player ?: return
        val lvl = mc.level ?: return
        if (!running || !p.isAlive || p.isSpectator || !isWeapon(p.mainHandItem)) return
        if (entity !is LivingEntity || entity === p || !entity.isAlive || entity.level() !== lvl) return
        target = entity
        attackUntilNs = System.nanoTime() + (attackDuration.coerceIn(0.5f, 4.0f) * 1e9).toLong()
    }

    // --- WispMotion equations (1:1 with NeoRaven WispMotion.java) ---

    private fun random64(seed: Long, index: Long): Double {
        var x = seed + index * -0x61c8864680b583ebL // 0x9E3779B97F4A7C15L
        x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9L
        x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EBL
        return ((x xor (x ushr 31)) ushr 11).toDouble() * 1.1102230246251565e-16 * 2.0 - 1.0 // 0x1.0p-52 - 1
    }

    private fun catmullNoise(seed: Long, time: Double): Double {
        val n = floor(time).toLong()
        val t = time - n.toDouble()
        val a = random64(seed, n - 1L)
        val b = random64(seed, n)
        val c = random64(seed, n + 1L)
        val d = random64(seed, n + 2L)
        return 0.5 * (
            2.0 * b +
                (c - a) * t +
                (2.0 * a - 5.0 * b + 4.0 * c - d) * t * t +
                (-a + 3.0 * b - 3.0 * c + d) * t * t * t
            )
    }

    private fun weaponOffset(orb: Int, seconds: Double, spd: Double, radius: Double, seed: Long): Point {
        val t = seconds * spd * 2.1
        val s = seed + orb * 0x632BE59BD9B4E019L
        return Point(
            radius * (0.34 * catmullNoise(s, t) + 0.10 * catmullNoise(s + 11L, t * 1.63 + 7.4)),
            radius * (0.038 * catmullNoise(s + 23L, t * 0.71 + 2.3) + 0.012 * catmullNoise(s + 31L, t * 1.27 + 9.1)),
            radius * (0.23 * catmullNoise(s + 47L, t * 0.89 + 4.8) + 0.07 * catmullNoise(s + 59L, t * 1.49 + 1.2)),
        )
    }

    private fun homeOffset(orb: Int, seconds: Double, spd: Double, radius: Double, height: Double): Point {
        val a = seconds * 2.8 * spd + orb * TAU / COUNT
        return Point(
            radius * cos(a),
            height * (0.53 + 0.36 * sin(a + orb * 0.48)),
            radius * sin(a),
        )
    }

    private fun strikeOffset(orb: Int, seconds: Double, spd: Double, radius: Double, height: Double): Point {
        val a = seconds * 3.8 * spd + orb * TAU / COUNT
        val x = radius * sin(a)
        val z = radius * 0.72 * sin(2.0 * a)
        val angle = seconds * 0.28 + orb * 0.8
        val c = cos(angle)
        val s = sin(angle)
        return Point(
            x * c - z * s,
            height * (0.5 + 0.37 * sin(a)),
            x * s + z * c,
        )
    }

    private fun motionSettled(now: Long): Boolean =
        points[0] != null && now - motionChanged >= FLIGHT_NS

    private fun motionAt(dest: Long): Boolean = motionKey == dest

    private fun motionProgress(now: Long): Double =
        ((now - motionChanged).toDouble() / FLIGHT_NS.toDouble()).coerceIn(0.0, 1.0)

    private fun updateMotion(now: Long, destination: Long, desired: Array<Point>, lifetimeSec: Double) {
        val before = Array(COUNT) { i -> points[i] ?: desired[i] }
        if (points[0] == null) {
            motionKey = destination
            motionChanged = now - FLIGHT_NS
            for (i in 0 until COUNT) {
                points[i] = desired[i]
                from[i] = desired[i]
                velocity[i] = Point.ZERO
                launchVelocity[i] = Point.ZERO
                before[i] = desired[i]
            }
        } else if (motionKey != destination) {
            motionKey = destination
            motionChanged = now
            for (i in 0 until COUNT) {
                from[i] = points[i] ?: desired[i]
                launchVelocity[i] = velocity[i] ?: Point.ZERO
            }
        }

        val t = motionProgress(now)
        val blend = t * t * (3.0 - 2.0 * t)
        val carry = 0.32 * t * (1.0 - t) * (1.0 - t)
        for (i in 0 until COUNT) {
            val f = from[i] ?: desired[i]
            val lv = launchVelocity[i] ?: Point.ZERO
            val next = Point.mix(f, desired[i], blend).plus(lv.times(carry))
            points[i] = next
            if (previousTime != 0L && now > previousTime && now - previousTime < 250_000_000L) {
                velocity[i] = next.minus(before[i]).times(1e9 / (now - previousTime).toDouble())
            }
        }

        if (previousTime == 0L || now - previousTime > 250_000_000L || now < previousTime) {
            for (trail in trails) trail.clear()
            sampledTime = now - SAMPLE_NS
            previousTime = now
        }

        var tick = sampledTime + SAMPLE_NS
        while (tick <= now) {
            val k = if (now == previousTime) {
                1.0
            } else {
                ((tick - previousTime).toDouble() / (now - previousTime).toDouble()).coerceIn(0.0, 1.0)
            }
            for (i in 0 until COUNT) {
                val cur = points[i] ?: desired[i]
                trails[i].addLast(Sample(Point.mix(before[i], cur, k), tick))
            }
            sampledTime = tick
            tick += SAMPLE_NS
        }

        val oldest = now - (lifetimeSec * 1e9).toLong()
        for (trail in trails) {
            while (trail.isNotEmpty() && (trail.peekFirst().time < oldest || trail.size > 160)) {
                trail.removeFirst()
            }
        }
        previousTime = now
    }

    private fun entityInterpPos(entity: Entity, partialTicks: Float): Point {
        val pt = partialTicks.toDouble()
        return Point(
            Mth.lerp(pt, entity.xo, entity.x),
            Mth.lerp(pt, entity.yo, entity.y),
            Mth.lerp(pt, entity.zo, entity.z),
        )
    }

    private fun captureWorldMatrices() {
        val cameraState = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState
        worldViewMat.set(cameraState.viewRotationMatrix)
        worldProjMat.set(cameraState.projectionMatrix)
        invWorldViewProj.set(worldProjMat).mul(worldViewMat)
        worldValid = invWorldViewProj.determinant() != 0f
        if (worldValid) {
            invWorldViewProj.invert()
        }
        val cam = cameraState.pos
        camOrigin = Point(cam.x, cam.y, cam.z)
        // Row 0 and Row 1 of viewRotationMatrix are camera right and camera up in world space
        camRight = Point(
            worldViewMat.m00().toDouble(),
            worldViewMat.m10().toDouble(),
            worldViewMat.m20().toDouble(),
        ).unit()
        camUp = Point(
            worldViewMat.m01().toDouble(),
            worldViewMat.m11().toDouble(),
            worldViewMat.m21().toDouble(),
        ).unit()
    }

    private fun mapHandToWorld(seconds: Double, spd: Double, radiusScale: Double, seed: Long): Array<Point>? {
        if (!worldValid || !handValid) return null
        return Array(COUNT) { i ->
            val p = weaponOffset(i, seconds, spd, radiusScale, seed)
            tmpVec4.set(p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), 1f)
            handBladeMat.transform(tmpVec4)
            handProjMat.transform(tmpVec4)
            invWorldViewProj.transform(tmpVec4)
            if (abs(tmpVec4.w) < 1e-7f) return null
            val invW = 1.0 / tmpVec4.w.toDouble()
            Point(
                camOrigin.x + tmpVec4.x * invW,
                camOrigin.y + tmpVec4.y * invW,
                camOrigin.z + tmpVec4.z * invW,
            )
        }
    }

    private fun handWorldSize(baseSize: Double): Double {
        return if (handValid && worldValid && abs(worldProjMat.m00()) > 1e-5f) {
            baseSize * 0.28 * abs(handProjMat.m00() / worldProjMat.m00()).toDouble()
        } else {
            baseSize
        }
    }

    // --- Hooks called from MixinHeldSpinFirstPerson, MixinHeldSpinItemLayer, and MixinGameRenderer ---

    @JvmStatic
    fun withMainHandSubmit(hand: InteractionHand, item: ItemStack, action: Runnable) {
        if (!running || hand != InteractionHand.MAIN_HAND || !isWeapon(item) || !firstPerson()) {
            action.run()
            return
        }
        inMainHandSubmit = true
        try {
            action.run()
        } finally {
            inMainHandSubmit = false
        }
    }

    @JvmStatic
    fun onItemDisplayTransform(pose: PoseStack.Pose) {
        if (!inMainHandSubmit || !running) return
        val m = pose.pose()
        val scale = sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02()).coerceAtLeast(0.05f)
        handBladeMat.identity()
        handBladeMat.m00(scale)
        handBladeMat.m11(scale)
        handBladeMat.m22(scale)
        handBladeMat.m30(m.m30())
        handBladeMat.m31(m.m31())
        handBladeMat.m32(m.m32())

        val target = mc.gameRenderer.mainRenderTarget()
        val aspect = if (target.height > 0) target.width.toFloat() / target.height.toFloat() else 1.7777778f
        val m11 = (1.0 / tan(Math.toRadians(35.0))).toFloat()
        val worldP = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix
        handProjMat.identity()
        handProjMat.m00(m11 / aspect)
        handProjMat.m11(m11)
        handProjMat.m22(worldP.m22())
        handProjMat.m23(worldP.m23())
        handProjMat.m32(worldP.m32())
        handProjMat.m33(worldP.m33())
        handValid = true

        val now = System.nanoTime()
        if (beganNs == 0L) beganNs = now
        val seconds = (now - beganNs) * 1e-9
        val mapped = mapHandToWorld(
            seconds,
            speed.toDouble(),
            (heldRadius / 0.8f).toDouble(),
            driftSeed,
        )
        if (mapped != null) {
            handHome = mapped
            handTimeNs = now
        }
    }

    @JvmStatic
    fun captureHandProjectionSlice() {
        if (running) {
            capturedHandProjSlice = RenderSystem.getProjectionMatrixBuffer()
        }
    }

    @JvmStatic
    fun renderFirstPersonHand(mainTarget: RenderTarget) {
        val projSlice = capturedHandProjSlice
        capturedHandProjSlice = null
        if (!running || mc.isPaused || !firstPerson()) return
        val p = mc.player ?: return
        if (!p.isAlive || p.isSpectator || !isWeapon(p.mainHandItem)) return
        if (target != null || !handDraw || !handValid || !worldValid || projSlice == null) return

        val now = System.nanoTime()
        val seconds = (now - beganNs) * 1e-9
        val mapped = mapHandToWorld(
            seconds,
            speed.toDouble(),
            (heldRadius / 0.8f).toDouble(),
            driftSeed,
        )
        if (mapped != null) {
            handHome = mapped
            handTimeNs = now
        }
        val home = handHome ?: return
        val life = trailLength.coerceIn(0.1f, 1.0f).toDouble()
        updateMotion(now, SWORD_HOME, home, life)
        if (points[0] == null) return

        val invHandProj = Matrix4f(handProjMat)
        if (invHandProj.determinant() == 0f) return
        invHandProj.invert()
        worldToHandMat.set(invHandProj).mul(worldProjMat).mul(worldViewMat)

        val handHeads = Array(COUNT) { i -> toHandEye(points[i]!!) }
        val handTrails = Array(COUNT) { i ->
            val src = trails[i]
            val list = ArrayList<Sample>(src.size + 1)
            for (s in src) {
                list.add(Sample(toHandEye(s.point), s.time))
            }
            list.add(Sample(handHeads[i], now))
            list
        }

        val fade = min(1.0, (now - beganNs) / 180_000_000.0).toFloat()
        val alpha = fade * ((wispColor.a and 255) / 255f)
        if (alpha <= 0.002f) return

        drawWispPass(
            target = mainTarget,
            pipeline = ClientRenderPipelines.NeoRaven.OrbitWispQuads,
            projSlice = projSlice,
            modelView = identityMat,
            heads = handHeads,
            trailLists = handTrails,
            origin = Point.ZERO,
            right = Point(1.0, 0.0, 0.0),
            up = Point(0.0, 1.0, 0.0),
            now = now,
            life = life,
            drawSize = size.toDouble() * 0.28,
            rgb = wispColor.argb and 0x00FFFFFF,
            alpha = alpha.toDouble(),
        )
    }

    private fun toHandEye(worldPoint: Point): Point {
        val rel = worldPoint.minus(camOrigin)
        tmpVec4.set(rel.x.toFloat(), rel.y.toFloat(), rel.z.toFloat(), 1f)
        worldToHandMat.transform(tmpVec4)
        val invW = if (abs(tmpVec4.w) < 1e-7f) 1.0 else 1.0 / tmpVec4.w.toDouble()
        return Point(tmpVec4.x * invW, tmpVec4.y * invW, tmpVec4.z * invW)
    }

    @Suppress("unused")
    private val worldRenderHandler = handler<WorldRenderEvent> { event ->
        handDraw = false
        val p = mc.player ?: return@handler
        val lvl = mc.level ?: return@handler
        if (!p.isAlive || p.isSpectator) {
            clearMotion()
            target = null
            handHome = null
            return@handler
        }
        if (mc.isPaused) {
            return@handler
        }

        val now = System.nanoTime()
        if (beganNs == 0L) beganNs = now
        var seconds = (now - beganNs) * 1e-9
        captureWorldMatrices()

        val playerPos = entityInterpPos(p, event.partialTicks)
        val prevOwner = previousOwnerPos
        if (prevOwner != null && playerPos.minus(prevOwner).length() > 12.0) {
            clearState()
            captureWorldMatrices()
            seconds = 0.0
        }
        previousOwnerPos = playerPos

        val holdingSword = isWeapon(p.mainHandItem)
        val curTarget = target
        if (curTarget != null && (
                !holdingSword ||
                    now >= attackUntilNs ||
                    curTarget.level() !== lvl ||
                    !curTarget.isAlive ||
                    p.distanceTo(curTarget) > 24f
                )
        ) {
            target = null
            driftSeed = ThreadLocalRandom.current().nextLong()
        }

        val wantsSwordHome = holdingSword && firstPerson()
        if (!wantsSwordHome) {
            handHome = null
            handTimeNs = 0L
        }
        val activeTarget = target
        val useSwordHome = activeTarget == null && wantsSwordHome && handHome != null &&
            now - handTimeNs < MAX_HAND_AGE_NS

        val spd = speed.toDouble()
        val life = trailLength.coerceIn(0.1f, 1.0f).toDouble()
        val destination: Array<Point>
        val key: Long

        if (activeTarget != null) {
            key = (activeTarget.id.toLong() shl 2) or 3L
            val center = entityInterpPos(activeTarget, event.partialTicks)
            val rad = max(0.4, activeTarget.bbWidth.toDouble() * 1.35) * (attackRadius.toDouble() / 0.8)
            val h = activeTarget.bbHeight.toDouble()
            destination = Array(COUNT) { i ->
                center.plus(strikeOffset(i, seconds, spd, rad, h))
            }
        } else if (useSwordHome) {
            key = SWORD_HOME
            if (motionAt(key) && motionSettled(now)) {
                handDraw = true
                return@handler
            }
            val mapped = mapHandToWorld(seconds, spd, (heldRadius / 0.8f).toDouble(), driftSeed)
            if (mapped != null) {
                handHome = mapped
            }
            destination = handHome ?: return@handler
        } else {
            key = PLAYER_HOME
            val rad = heldRadius.toDouble()
            val h = p.bbHeight.toDouble()
            destination = Array(COUNT) { i ->
                playerPos.plus(homeOffset(i, seconds, spd, rad, h))
            }
        }

        updateMotion(now, key, destination, life)
        if (useSwordHome && motionSettled(now)) {
            handDraw = true
            return@handler
        }

        val head0 = points[0] ?: return@handler
        val heads = Array(COUNT) { i -> points[i] ?: head0 }
        val trailLists = Array(COUNT) { i ->
            ArrayList<Sample>(trails[i].size + 1).apply {
                addAll(trails[i])
                add(Sample(heads[i], now))
            }
        }

        val crossBody = activeTarget != null && p.hasLineOfSight(activeTarget)
        val fade = min(1.0, (now - beganNs) / 180_000_000.0).toFloat()
        val alpha = fade * ((wispColor.a and 255) / 255f)
        if (alpha <= 0.002f) return@handler

        var drawSize = size.toDouble()
        if (useSwordHome) {
            drawSize += (handWorldSize(drawSize) - drawSize) * motionProgress(now)
        }

        val projSlice = RenderSystem.getProjectionMatrixBuffer() ?: return@handler
        val pipeline = if (crossBody) {
            ClientRenderPipelines.NeoRaven.OrbitWispQuadsNoDepth
        } else {
            ClientRenderPipelines.NeoRaven.OrbitWispQuads
        }

        drawWispPass(
            target = event.renderTarget,
            pipeline = pipeline,
            projSlice = projSlice,
            modelView = worldViewMat,
            heads = heads,
            trailLists = trailLists,
            origin = camOrigin,
            right = camRight,
            up = camUp,
            now = now,
            life = life,
            drawSize = drawSize,
            rgb = wispColor.argb and 0x00FFFFFF,
            alpha = alpha.toDouble(),
        )
    }

    private fun ensureWispTexture(): DynamicTexture {
        wispTexture?.let { return it }
        val img = NativeImage(64, 64, true)
        val edgeExp = exp(-4.8)
        val invDenom = 1.0 / (1.0 - edgeExp)
        for (y in 0 until 64) {
            val dy = (y - 31.5) / 31.5
            for (x in 0 until 64) {
                val dx = (x - 31.5) / 31.5
                val r2 = dx * dx + dy * dy
                val a = max(0.0, (exp(-r2 * 4.8) - edgeExp) * invDenom)
                val alphaByte = (a * 255.0).roundToInt().coerceIn(0, 255)
                img.setPixelABGR(x, y, (alphaByte shl 24) or 0x00FFFFFF)
            }
        }
        val tex = object : DynamicTexture(Supplier { "OrbitParticles Gaussian 64x64" }, img) {
            init {
                sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
            }
        }
        mc.textureManager.register(wispTexId, tex)
        tex.upload()
        wispTexture = tex
        return tex
    }

    private fun mixRgbWhite(rgb: Int, factor: Double): Int {
        val r = ((rgb ushr 16) and 255)
        val g = ((rgb ushr 8) and 255)
        val b = (rgb and 255)
        val nr = (r + (255 - r) * factor).roundToInt().coerceIn(0, 255)
        val ng = (g + (255 - g) * factor).roundToInt().coerceIn(0, 255)
        val nb = (b + (255 - b) * factor).roundToInt().coerceIn(0, 255)
        return (nr shl 16) or (ng shl 8) or nb
    }

    private fun packArgb(rgb: Int, opacity: Double): Int {
        val a = (opacity.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255)
        return ARGB.color(a, (rgb ushr 16) and 255, (rgb ushr 8) and 255, rgb and 255)
    }

    @Suppress("LongParameterList")
    private fun drawWispPass(
        target: RenderTarget,
        pipeline: RenderPipeline,
        projSlice: GpuBufferSlice,
        modelView: Matrix4f,
        heads: Array<Point>,
        trailLists: Array<ArrayList<Sample>>,
        origin: Point,
        right: Point,
        up: Point,
        now: Long,
        life: Double,
        drawSize: Double,
        rgb: Int,
        alpha: Double,
    ) {
        val tex = ensureWispTexture()
        val view = right.cross(up).unit()
        val pale = mixRgbWhite(rgb, 0.45)

        val bufferBuilder = ClientTesselator.Shared.begin(pipeline)
        for (i in 0 until COUNT) {
            val samples = trailLists[i]
            emitRibbon(bufferBuilder, samples, origin, right, view, now, life, drawSize * 2.4, rgb, alpha * 0.18)
            emitRibbon(bufferBuilder, samples, origin, right, view, now, life, drawSize * 0.9, pale, alpha * 0.70)
            emitRibbon(bufferBuilder, samples, origin, right, view, now, life, drawSize * 0.20, 0xFFFFFF, alpha * 0.85)
        }
        for (head in heads) {
            emitQuad(bufferBuilder, head, origin, right, up, drawSize * 2.9, rgb, alpha * 0.58)
            emitQuad(bufferBuilder, head, origin, right, up, drawSize * 1.15, pale, alpha * 0.95)
            emitQuad(bufferBuilder, head, origin, right, up, drawSize * 0.48, 0xFFFFFF, alpha)
        }

        val meshData = bufferBuilder.build()
        if (meshData == null) {
            ClientTesselator.Shared.clear()
            return
        }

        try {
            meshData.use { built ->
                val meshDraw = built.toMeshDraw(pipeline)
                val dynTransforms = getDynamicTransformsUniform(modelView = modelView)
                val useDepth = pipeline === ClientRenderPipelines.NeoRaven.OrbitWispQuads
                target.createRenderPass({ "OrbitParticles pass" }, useDepthAttachment = useDepth).use { pass ->
                    pass.setPipeline(pipeline)
                    pass.setUniform("Projection", projSlice)
                    pass.bindDynamicTransformsUniform(dynTransforms)
                    pass.setUniform("Sampler0", tex)
                    pass.bindAndDraw(meshDraw)
                }
            }
        } finally {
            ClientTesselator.Shared.clear()
        }
    }

    @Suppress("LongParameterList")
    private fun emitRibbon(
        vc: VertexConsumer,
        samples: List<Sample>,
        origin: Point,
        fallback: Point,
        view: Point,
        now: Long,
        life: Double,
        width: Double,
        rgb: Int,
        alpha: Double,
    ) {
        val n = samples.size
        if (n < 2) return

        var prevLx = 0f
        var prevLy = 0f
        var prevLz = 0f
        var prevRx = 0f
        var prevRy = 0f
        var prevRz = 0f
        var prevArgb = 0

        for (i in 0 until n) {
            val sample = samples[i]
            val before = samples[max(0, i - 1)].point
            val after = samples[min(n - 1, i + 1)].point
            val dx = after.x - before.x
            val dy = after.y - before.y
            val dz = after.z - before.z
            var sx = view.y * dz - view.z * dy
            var sy = view.z * dx - view.x * dz
            var sz = view.x * dy - view.y * dx
            var len = sqrt(sx * sx + sy * sy + sz * sz)
            if (len < 1e-8) {
                sx = fallback.x
                sy = fallback.y
                sz = fallback.z
                len = 1.0
            }
            val remaining = (1.0 - (now - sample.time).toDouble() / (life * 1e9)).coerceIn(0.0, 1.0)
            val half = width * remaining.pow(0.8)
            val opacity = alpha * remaining * remaining
            val argb = packArgb(rgb, opacity)

            val x = sample.point.x - origin.x
            val y = sample.point.y - origin.y
            val z = sample.point.z - origin.z
            val ox = (sx / len * half)
            val oy = (sy / len * half)
            val oz = (sz / len * half)

            val lx = (x - ox).toFloat()
            val ly = (y - oy).toFloat()
            val lz = (z - oz).toFloat()
            val rx = (x + ox).toFloat()
            val ry = (y + oy).toFloat()
            val rz = (z + oz).toFloat()

            if (i > 0 && ((prevArgb ushr 24) != 0 || (argb ushr 24) != 0)) {
                vc.addVertex(prevLx, prevLy, prevLz).setUv(0f, 0.5f).setColor(prevArgb)
                vc.addVertex(prevRx, prevRy, prevRz).setUv(1f, 0.5f).setColor(prevArgb)
                vc.addVertex(rx, ry, rz).setUv(1f, 0.5f).setColor(argb)
                vc.addVertex(lx, ly, lz).setUv(0f, 0.5f).setColor(argb)
            }

            prevLx = lx
            prevLy = ly
            prevLz = lz
            prevRx = rx
            prevRy = ry
            prevRz = rz
            prevArgb = argb
        }
    }

    @Suppress("LongParameterList")
    private fun emitQuad(
        vc: VertexConsumer,
        p: Point,
        origin: Point,
        r: Point,
        u: Point,
        s: Double,
        rgb: Int,
        alpha: Double,
    ) {
        val argb = packArgb(rgb, alpha)
        if ((argb ushr 24) == 0) return
        val x = p.x - origin.x
        val y = p.y - origin.y
        val z = p.z - origin.z
        val rx = r.x * s
        val ry = r.y * s
        val rz = r.z * s
        val ux = u.x * s
        val uy = u.y * s
        val uz = u.z * s

        vc.addVertex((x - rx - ux).toFloat(), (y - ry - uy).toFloat(), (z - rz - uz).toFloat())
            .setUv(0f, 0f).setColor(argb)
        vc.addVertex((x - rx + ux).toFloat(), (y - ry + uy).toFloat(), (z - rz + uz).toFloat())
            .setUv(0f, 1f).setColor(argb)
        vc.addVertex((x + rx + ux).toFloat(), (y + ry + uy).toFloat(), (z + rz + uz).toFloat())
            .setUv(1f, 1f).setColor(argb)
        vc.addVertex((x + rx - ux).toFloat(), (y + ry - uy).toFloat(), (z + rz - uz).toFloat())
            .setUv(1f, 0f).setColor(argb)
    }
}
