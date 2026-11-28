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
import com.mojang.renderpearl.api.textures.FilterMode
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerJumpEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.setPipeline
import net.ccbluex.liquidbounce.utils.render.isCustom
import net.ccbluex.liquidbounce.utils.render.registerTexture
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.client.CameraType
import net.minecraft.client.model.Model
import net.minecraft.client.renderer.RenderBuffers
import net.minecraft.client.renderer.SubmitNodeStorage
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.UvMapping
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import org.joml.Vector4f
import java.util.ArrayDeque
import java.util.Optional
import java.util.OptionalDouble
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Hit Afterimage (`受击残影`) — ported with 100% visual fidelity from NeoRaven
 * (`HitAfterimageModule` + `HitAfterimageRenderer` + `HitAfterimageSnapshot`).
 *
 * Hooks into `LevelRenderer`'s entity feature submission, main-pass rendering, and frame-graph
 * compositing (same lifecycle as [ModuleChams]) so `Lighting`, `Fog`, `Lightmap`, and camera
 * view matrices are active when rasterizing the frozen 3D entity pose, while testing depth
 * against `sceneDepth` in the composite shader without live-entity self-occlusion.
 */
object ModuleHitAfterimage : ClientModule("HitAfterimage", ModuleCategories.RENDER) {

    private val afterimageColor by color("Color", Color4b(255, 255, 255, 255))
    private val duration by float("Duration", 1.38f, 0.2f..5.0f, "s")
    private val opacity by int("Opacity", 100, 0..100, "%")
    private val players by boolean("Players", true)
    private val mobs by boolean("Mobs", true)
    private val self by boolean("Self", false)
    private val selfTimed by boolean("SelfTimed", true).visibleWhen { self }
    private val selfInterval by float("SelfInterval", 1.0f, 0.05f..10.0f, "s").visibleWhen { self && selfTimed }
    private val selfOnJump by boolean("SelfOnJump", true).visibleWhen { self }
    private val selfOnLand by boolean("SelfOnLand", true).visibleWhen { self }

    private const val MAX_SNAPSHOTS = 24
    private const val ENTITY_COOLDOWN_MS = 50L

    // Exact 10 volumetric glow shell scales and alphas from NeoRaven HitAfterimageRenderer
    private val SHELL_SCALES = floatArrayOf(1.03f, 1.08f, 1.14f, 1.21f, 1.29f, 1.39f, 1.51f, 1.64f, 1.78f, 1.95f)
    private val SHELL_ALPHAS = floatArrayOf(0.30f, 0.255f, 0.215f, 0.18f, 0.15f, 0.125f, 0.103f, 0.084f, 0.066f, 0.05f)

    private class Snapshot(
        val entityId: Int,
        val isSelf: Boolean,
        val renderState: EntityRenderState,
        val halfHeight: Float,
        val entityScale: Float,
        val createdAtMs: Long,
        val jitter: Float,
    )

    private val snapshots = ArrayDeque<Snapshot>()
    private val pendingEntityIds = IntLinkedOpenHashSet()
    private val cooldowns = Int2LongOpenHashMap()
    private val prevHurtTimes = Int2IntOpenHashMap()
    private val prevHealths = Int2FloatOpenHashMap().apply { defaultReturnValue(Float.NaN) }

    private var lastTimedSelfMs = 0L
    private var wasOnGround = false
    private var groundTrackingInitialized = false
    private var airborneTicks = 0
    private var lastFallSpeed = 0.0

    private val WHITE_TEX_ID: Identifier = LiquidBounce.identifier("hit_afterimage_white_1x1")
    private var whiteTextureRegistered = false

    private val coreTargetHolder = LazyRenderTargetHolder("HitAfterimageCore", useDepth = true)
    private val shellTargetHolder = LazyRenderTargetHolder("HitAfterimageShells", useDepth = true)

    private var coreStorage = SubmitNodeStorage()
    private var shellStorage = SubmitNodeStorage()

    private var coreBuffers: RenderBuffers? = null
    private var coreDispatcher: FeatureRenderDispatcher? = null
    private var coreFrame: FeatureRenderDispatcher.PreparedFrame? = null

    private var shellBuffers: RenderBuffers? = null
    private var shellDispatcher: FeatureRenderDispatcher? = null
    private var shellFrame: FeatureRenderDispatcher.PreparedFrame? = null

    private val nearestSampler by lazy(LazyThreadSafetyMode.NONE) {
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
    }
    private val blendUboRing by lazy(LazyThreadSafetyMode.NONE) {
        ClientUniformDefine.BLEND.createRingBuffer()
    }

    private val submitPose = PoseStack()

    // Capture state scoped strictly around mc.entityRenderDispatcher.submit(...)
    private var activeSnapshot: Snapshot? = null
    private var activeAlpha = 0f
    private var submittedMainModel = false
    private var internalSubmitDepth = 0

    override fun onEnabled() {
        clearState()
        super.onEnabled()
    }

    override fun onDisabled() {
        clearState()
        releaseGpuResources()
        super.onDisabled()
    }

    private fun clearState() {
        snapshots.clear()
        synchronized(pendingEntityIds) {
            pendingEntityIds.clear()
        }
        cooldowns.clear()
        prevHurtTimes.clear()
        prevHealths.clear()
        lastTimedSelfMs = 0L
        wasOnGround = false
        groundTrackingInitialized = false
        airborneTicks = 0
        lastFallSpeed = 0.0
        resetStorages()
    }

    private fun resetStorages() {
        val improvedTransparency = mc.gameRenderer?.useImprovedTransparency() ?: false
        coreStorage = SubmitNodeStorage().also {
            it.setUseImprovedTransparency(improvedTransparency)
        }
        shellStorage = SubmitNodeStorage().also {
            it.setUseImprovedTransparency(improvedTransparency)
        }
    }

    private fun releaseGpuResources() {
        coreFrame?.close()
        coreFrame = null
        shellFrame?.close()
        shellFrame = null

        coreDispatcher?.close()
        coreBuffers?.close()
        coreDispatcher = null
        coreBuffers = null

        shellDispatcher?.close()
        shellBuffers?.close()
        shellDispatcher = null
        shellBuffers = null

        coreTargetHolder.close()
        shellTargetHolder.close()
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        clearState()
    }

    @Suppress("unused")
    private val jumpHandler = handler<PlayerJumpEvent> {
        if (self && selfOnJump) {
            queueTrigger(player)
        }
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        when (val packet = event.packet) {
            is ClientboundDamageEventPacket -> {
                val id = packet.entityId
                mc.execute {
                    val entity = mc.level?.getEntity(id) as? LivingEntity ?: return@execute
                    queueTrigger(entity)
                }
            }
            is ClientboundEntityEventPacket -> {
                val code = packet.eventId.toInt()
                // Vanilla & 1.8 ViaFabricPlus hurt status codes (2 = generic hurt, 33/36/37/44 = directional/thorns/drown/burn/berry)
                if (code == 2 || code == 33 || code == 36 || code == 37 || code == 44) {
                    mc.execute {
                        val level = mc.level ?: return@execute
                        val entity = packet.getEntity(level) as? LivingEntity ?: return@execute
                        queueTrigger(entity)
                    }
                }
            }
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val now = System.currentTimeMillis()
        pruneExpired(now)

        // Exact NeoRaven HitAfterimageModule hurtTime rising-edge + health-drop detection
        for (entity in world.entitiesForRendering()) {
            if (entity !is LivingEntity) continue
            val id = entity.id
            val hurt = entity.hurtTime
            val prevHurt = prevHurtTimes.put(id, hurt)

            val hp = entity.health + entity.absorptionAmount
            val prevHp = prevHealths.put(id, hp)
            val hurtRising = hurt > 0 && hurt > prevHurt
            val healthDropped = !prevHp.isNaN() && hp < prevHp - 0.01f
            if (hurtRising || healthDropped) {
                queueTrigger(entity)
            }
        }
        prevHurtTimes.int2IntEntrySet().removeIf { entry -> world.getEntity(entry.intKey) == null }
        prevHealths.int2FloatEntrySet().removeIf { entry -> world.getEntity(entry.intKey) == null }

        // Self triggers (Timed, On jump, On land) matching NeoRaven HitAfterimageModule
        if (!self) {
            groundTrackingInitialized = false
            airborneTicks = 0
            lastFallSpeed = 0.0
            return@handler
        }

        val onGround = player.onGround()
        val vy = player.deltaMovement.y
        if (!groundTrackingInitialized) {
            wasOnGround = onGround
            groundTrackingInitialized = true
            airborneTicks = 0
            lastFallSpeed = 0.0
        } else {
            if (!onGround) {
                if (wasOnGround && selfOnJump && (vy > 0.2 || mc.options.keyJump.isDown)) {
                    queueTrigger(player)
                }
                airborneTicks++
                if (vy < lastFallSpeed) {
                    lastFallSpeed = vy
                }
            } else {
                if (!wasOnGround && selfOnLand && airborneTicks >= 2 && lastFallSpeed <= -0.07) {
                    queueTrigger(player)
                }
                airborneTicks = 0
                lastFallSpeed = 0.0
            }
            wasOnGround = onGround
        }

        if (selfTimed) {
            val intervalMs = (selfInterval.coerceIn(0.05f, 10.0f) * 1000f).toLong().coerceAtLeast(50L)
            if (now - lastTimedSelfMs >= intervalMs) {
                lastTimedSelfMs = now
                queueTrigger(player)
            }
        } else {
            lastTimedSelfMs = 0L
        }
    }

    private fun shouldCapture(entity: LivingEntity): Boolean {
        if (!entity.isAlive || entity is ArmorStand) {
            return false
        }
        if (entity === mc.player) {
            return self
        }
        if (entity is Player) {
            return players
        }
        return if (entity is Mob || entity is Animal) mobs else mobs
    }

    private fun queueTrigger(entity: LivingEntity) {
        if (!running || !shouldCapture(entity)) {
            return
        }
        val now = System.currentTimeMillis()
        val prev = cooldowns.get(entity.id)
        if (prev != 0L && now - prev < ENTITY_COOLDOWN_MS) {
            return
        }
        cooldowns.put(entity.id, now)
        synchronized(pendingEntityIds) {
            pendingEntityIds.add(entity.id)
        }
    }

    private fun materializePendingSnapshots(now: Long) {
        val level = mc.level ?: return
        val ids: IntArray = synchronized(pendingEntityIds) {
            if (pendingEntityIds.isEmpty()) return
            val arr = pendingEntityIds.toIntArray()
            pendingEntityIds.clear()
            arr
        }

        val partialTicks = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
        for (id in ids) {
            val entity = level.getEntity(id) as? LivingEntity ?: continue
            if (!shouldCapture(entity)) continue

            val state = runCatching {
                val renderer = mc.entityRenderDispatcher.getRenderer(entity)
                renderer.createRenderState(entity, partialTicks).also { rs ->
                    rs.isCustom = true
                    rs.nameTag = null
                    rs.outlineColor = 0
                    if (rs is LivingEntityRenderState) {
                        rs.hasRedOverlay = false
                    }
                }
            }.getOrNull() ?: continue

            val jitter = sin((now * 0.017 + entity.id * 1.7).toDouble()).toFloat()
            val snap = Snapshot(
                entityId = entity.id,
                isSelf = entity === mc.player,
                renderState = state,
                halfHeight = (entity.bbHeight * 0.5f).coerceAtLeast(0.1f),
                entityScale = entity.scale.coerceAtLeast(0.05f),
                createdAtMs = now,
                jitter = jitter,
            )
            while (snapshots.size >= MAX_SNAPSHOTS) {
                snapshots.pollFirst()
            }
            snapshots.addLast(snap)
        }
    }

    private fun lifetimeMs(): Long =
        (duration.coerceIn(0.2f, 5.0f) * 1000f).roundToInt().coerceAtLeast(200).toLong()

    private fun pruneExpired(now: Long) {
        val life = lifetimeMs()
        while (snapshots.isNotEmpty()) {
            val head = snapshots.peekFirst() ?: break
            if (now - head.createdAtMs >= life) {
                snapshots.pollFirst()
            } else {
                break
            }
        }
    }

    private fun ensureWhiteTexture(): Identifier {
        if (!whiteTextureRegistered) {
            val img = NativeImage(1, 1, false)
            img.setPixelABGR(0, 0, -1)
            img.registerTexture(WHITE_TEX_ID)
            whiteTextureRegistered = true
        }
        return WHITE_TEX_ID
    }

    fun isCapturingSnapshot(): Boolean = activeSnapshot != null && internalSubmitDepth == 0

    @Suppress("UNCHECKED_CAST", "UnusedParameter")
    fun captureModel(
        model: Model<*>,
        state: Any,
        poseStack: PoseStack,
        lightCoords: Int,
        overlayCoords: Int,
        uvMapping: UvMapping?,
    ): Boolean {
        val snap = activeSnapshot ?: return false
        if (internalSubmitDepth > 0) {
            return false
        }
        if (submittedMainModel) {
            // Skip armor, elytra, capes, and feature layers — render only the clean silhouette model
            return true
        }
        submittedMainModel = true

        val alpha = activeAlpha
        if (alpha <= 0.002f) {
            return true
        }

        val whiteTex = ensureWhiteTexture()
        val typedModel = model as Model<Any>
        val renderType = RenderTypes.entityTranslucentCull(whiteTex)
        val pivot = 1.501f - (snap.halfHeight / snap.entityScale)

        internalSubmitDepth++
        try {
            val coreAlpha = (alpha * 0.22f).coerceIn(0f, 1f)
            if (coreAlpha > 0.002f) {
                val coreArgb = ARGB.color((coreAlpha * 255f).roundToInt().coerceIn(1, 255), 255, 255, 255)
                coreStorage.submitModel(
                    typedModel,
                    state,
                    poseStack,
                    renderType,
                    LightCoordsUtil.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY,
                    coreArgb,
                    null,
                    0,
                )
            }

            for (i in SHELL_SCALES.indices) {
                val shellJitter = sin(((snap.entityId + i) * 12.9898f).toDouble()).toFloat() * 0.5f
                val jitterFactor = (1.0f + snap.jitter * 0.08f + shellJitter * 0.06f).coerceIn(0.85f, 1.15f)
                val shellAlpha = (alpha * SHELL_ALPHAS[i] * jitterFactor).coerceIn(0f, 1f)
                if (shellAlpha <= 0.002f) {
                    continue
                }
                val shellArgb = ARGB.color((shellAlpha * 255f).roundToInt().coerceIn(1, 255), 255, 255, 255)
                val s = SHELL_SCALES[i]
                poseStack.pushPose()
                try {
                    poseStack.translate(0f, pivot, 0f)
                    poseStack.scale(s, s, s)
                    poseStack.translate(0f, -pivot, 0f)
                    shellStorage.submitModel(
                        typedModel,
                        state,
                        poseStack,
                        renderType,
                        LightCoordsUtil.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY,
                        shellArgb,
                        null,
                        0,
                    )
                } finally {
                    poseStack.popPose()
                }
            }
        } finally {
            internalSubmitDepth--
        }
        return true
    }

    /**
     * Step 1 (called from `MixinLevelRenderer.hookWorldFeatureSubmit`):
     * Materializes newly queued snapshots while `EntityRenderDispatcher` & `CameraRenderState` are
     * initialized, and submits active snapshots into `coreStorage` / `shellStorage` with an
     * identity `PoseStack` (matching `BlinkEspModel` and `LevelRenderer.submitEntities`).
     */
    fun submitFrame() {
        coreFrame?.close()
        coreFrame = null
        shellFrame?.close()
        shellFrame = null
        resetStorages()

        if (!running) {
            return
        }

        val now = System.currentTimeMillis()
        materializePendingSnapshots(now)
        pruneExpired(now)
        if (snapshots.isEmpty()) {
            return
        }

        val opacityScale = (opacity.coerceIn(0, 100) / 100f) * ((afterimageColor.a and 255) / 255f)
        if (opacityScale <= 0.001f) {
            return
        }

        val firstPerson = mc.options.cameraType == CameraType.FIRST_PERSON
        val life = lifetimeMs().toFloat()
        val cameraState = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState

        for (snap in snapshots) {
            if (snap.isSelf && firstPerson) {
                continue
            }
            val fade = 1f - (now - snap.createdAtMs).toFloat() / life
            if (fade <= 0.002f) {
                continue
            }
            val rs = snap.renderState
            rs.distanceToCameraSq = cameraState.pos.distanceToSqr(rs.x, rs.y, rs.z)

            activeSnapshot = snap
            activeAlpha = (fade * opacityScale).coerceIn(0f, 1f)
            submittedMainModel = false
            try {
                submitPose.setIdentity()
                mc.entityRenderDispatcher.submit(
                    rs,
                    cameraState,
                    rs.x - cameraState.pos.x,
                    rs.y - cameraState.pos.y,
                    rs.z - cameraState.pos.z,
                    submitPose,
                    coreStorage,
                )
            } catch (_: Throwable) {
                // Ignore unrenderable custom entity states safely
            } finally {
                activeSnapshot = null
                submittedMainModel = false
            }
        }
    }

    /**
     * Step 2 (called from `MixinLevelRenderer.prepareChamsRenderTarget` right after
     * `Lighting.setupFor(Lighting.Entry.LEVEL)` inside the main pass):
     * Rasterizes `coreStorage` and `shellStorage` into dedicated offscreen targets cleared to
     * Reversed-Z far depth `0.0` (so the live entity never self-occludes the hit afterimage).
     */
    fun prepareAndRender() {
        if (!running) return
        val hasCore = coreStorage.submitsPerOrder.isNotEmpty()
        val hasShells = shellStorage.submitsPerOrder.isNotEmpty()
        if (!hasCore && !hasShells) return

        val width = mc.window.width
        val height = mc.window.height
        if (width <= 0 || height <= 0) return

        val cTarget = coreTargetHolder.initAndGet(width, height)
        val sTarget = shellTargetHolder.initAndGet(width, height)

        if (hasCore) {
            val buffers = coreBuffers ?: RenderBuffers(1).also { coreBuffers = it }
            val disp = coreDispatcher ?: FeatureRenderDispatcher(
                buffers,
                mc.modelManager,
                mc.atlasManager,
                mc.font,
                mc.gameRenderer.gameRenderState(),
            ).also { coreDispatcher = it }
            val frame = disp.prepareFrame(coreStorage).also { coreFrame = it }
            cTarget.createRenderPass(
                { "HitAfterimage Core" },
                Optional.of(Vector4f(0f, 0f, 0f, 0f)),
                OptionalDouble.of(0.0),
            ).use { pass ->
                RenderSystem.bindDefaultUniforms(pass)
                FeatureRenderDispatcher.renderAllFeatures(pass, frame)
            }
        }

        if (hasShells) {
            val buffers = shellBuffers ?: RenderBuffers(1).also { shellBuffers = it }
            val disp = shellDispatcher ?: FeatureRenderDispatcher(
                buffers,
                mc.modelManager,
                mc.atlasManager,
                mc.font,
                mc.gameRenderer.gameRenderState(),
            ).also { shellDispatcher = it }
            val frame = disp.prepareFrame(shellStorage).also { shellFrame = it }
            sTarget.createRenderPass(
                { "HitAfterimage Shells" },
                Optional.of(Vector4f(0f, 0f, 0f, 0f)),
                OptionalDouble.of(0.0),
            ).use { pass ->
                RenderSystem.bindDefaultUniforms(pass)
                FeatureRenderDispatcher.renderAllFeatures(pass, frame)
            }
        }
    }

    /**
     * Step 3 (called from `MixinLevelRenderer.scheduleChamsComposite` frame-graph pass):
     * Composites the hollow core + volumetric glow shells onto `mainTarget` with Reversed-Z
     * depth testing against `sceneDepth` (`mainTarget.depthTextureView`).
     */
    fun compositeIfNeeded(mainTarget: RenderTarget) {
        val hasCore = coreStorage.submitsPerOrder.isNotEmpty()
        val hasShells = shellStorage.submitsPerOrder.isNotEmpty()
        if (!hasCore && !hasShells) {
            return
        }

        try {
            val cTarget = coreTargetHolder.get() ?: return
            val sTarget = shellTargetHolder.get() ?: return
            val coreView = cTarget.colorTextureView ?: return
            val coreDepthView = cTarget.depthTextureView ?: return
            val shellView = sTarget.colorTextureView ?: return
            val shellDepthView = sTarget.depthTextureView ?: return
            val sceneDepthView = mainTarget.depthTextureView ?: return

            blendUboRing.rotate()
            val colorSlice = blendUboRing.currentBuffer().slice()
            colorSlice.writeStd140 {
                putVec4(
                    (afterimageColor.r and 255) / 255f,
                    (afterimageColor.g and 255) / 255f,
                    (afterimageColor.b and 255) / 255f,
                    1f,
                )
            }

            mainTarget.createRenderPass({ "HitAfterimage Composite" }, useDepthAttachment = false).use { pass ->
                pass.setPipeline(ClientRenderPipelines.NeoRaven.HitAfterimageComposite)
                pass.setUniform("coreTex", coreView, nearestSampler)
                pass.setUniform("coreDepth", coreDepthView, nearestSampler)
                pass.setUniform("shellTex", shellView, nearestSampler)
                pass.setUniform("shellDepth", shellDepthView, nearestSampler)
                pass.setUniform("sceneDepth", sceneDepthView, nearestSampler)
                pass.setUniform(ClientUniformDefine.BLEND.uboName, colorSlice)
                pass.draw(3, 1, 0, 0)
            }
        } finally {
            coreFrame?.close()
            coreFrame = null
            coreBuffers?.endFrame()

            shellFrame?.close()
            shellFrame = null
            shellBuffers?.endFrame()

            resetStorages()
        }
    }
}
