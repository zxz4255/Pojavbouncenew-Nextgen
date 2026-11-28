/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
@file:Suppress("MagicNumber", "LongParameterList", "TooManyFunctions", "CyclomaticComplexMethod")

package net.ccbluex.liquidbounce.features.module.modules.render.fpsknife

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleSwordBlock
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.item.isAxe
import net.ccbluex.liquidbounce.utils.item.isSword
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.item.ShieldItem
import net.minecraft.world.entity.player.PlayerModelType
import net.minecraft.world.phys.HitResult
import org.joml.Vector3f
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * FPS Tactical Knife & Empty-Hand Boxing Viewmodel Module (`ModuleFpsKnife`).
 *
 * 077: fixed proportions, per-model authored choreography, articulated folder blades,
 * explicit inspect-return scheduling and shared render/test clearance resolution.
 * Hands remain Minecraft skin arms, NOT Valve's articulated glove assets.
 * Keeps 13 knife selections/skins, empty-hand boxing, 1.8 guards and StatTrak.
 * Animation is cosmetic only; no changes to actual hit, damage or block interaction logic.
 */
object ModuleFpsKnife : ClientModule("FpsKnife", ModuleCategories.RENDER, aliases = listOf("KnifeSkin", "ValorantKnife", "CsgoKnife")) {

    enum class WeaponFilter(override val tag: String) : Tagged {
        SWORDS_ONLY("SwordsOnly"),
        SWORDS_AND_AXES("SwordsAndAxes"),
    }

    val model by enumChoice("Model", KnifeModel.NEXUS_SKELETON)
    val skin by enumChoice("Skin", KnifeSkin.NEXUS_HOLO)
    val engraving by enumChoice("Engraving", BladeEngraving.NEXUS)
    private val weaponFilter by enumChoice("Weapons", WeaponFilter.SWORDS_ONLY)
    val emptyHandBoxing by boolean("EmptyHandBoxing", true)
    val dualHands by boolean("DualHands", true)

    object Animation : ValueGroup("Animation") {
        val inspectKey by key("InspectKey", InputConstants.KEY_V)
        val inspectOnlyWhenIdle by boolean("InspectOnlyWhenIdle", true)
        val airInspect by boolean("AirInspect", true)
        val autoInspectIdleSeconds by float("AutoInspectIdle", 0f, 0f..15f, "s")
        val block18Animation by boolean("Block18Animation", true)
        val block18Style by enumChoice("Block18Style", Block18Style.TACTICAL_PARRY)
        val rightClickSwordGuard by boolean("RightClickSwordGuard", true)
        val drawOnSwitch by boolean("DrawOnSwitch", true)
    }

    object Viewmodel : ValueGroup("Viewmodel") {
        // 077: geometry and motion are authored together. Free offsets/scales/speed have
        // intentionally been removed; old saved keys cannot silently stretch the knife rig.
        val emissiveStrength by float("NeonGlow", 0.92f, 0.0f..1.0f)
    }

    init {
        tree(Animation)
        tree(Viewmodel)
    }

    private val poseSnapshot = FpsKnifePose()
    private val attackInput = FpsKnifeAttackInput()

    // StatTrak kill counter tracking
    private var statTrakKills: Int = 0
    private var lastAttackedTargetId: Int = -1
    private var lastAttackedTimestampMs: Long = 0L

    override fun onEnabled() {
        FpsKnifeAnimator.reset()
        attackInput.reset()
    }

    override fun onDisabled() {
        FpsKnifeAnimator.reset()
        attackInput.reset()
    }

    /**
     * Resolves whether a given main-hand [stack] activates Knife Viewmodel, Empty-Hand Boxing, or neither (`null`).
     */
    fun resolveRenderMode(stack: ItemStack): FpsRenderMode? {
        if (!running) return null
        if (stack.isEmpty) {
            return if (emptyHandBoxing) FpsRenderMode.EMPTY_HAND_BOXING else null
        }
        val validWeapon = when (weaponFilter) {
            WeaponFilter.SWORDS_ONLY -> stack.isSword
            WeaponFilter.SWORDS_AND_AXES -> stack.isSword || stack.isAxe
        }
        return if (validWeapon) FpsRenderMode.KNIFE_VIEWMODEL else null
    }

    @JvmStatic
    fun shouldReplaceMainHand(stack: ItemStack): Boolean = resolveRenderMode(stack) != null

    @JvmStatic
    fun shouldInstantReplace(expectedStack: ItemStack): Boolean = shouldReplaceMainHand(expectedStack)

    /**
     * Suppresses vanilla offhand rendering when dual FPS hands or empty-hand boxing are active,
     * OR when a virtual 1.8 sword-block offhand shield is present.
     */
    @JvmStatic
    fun shouldRenderVanillaOffhand(offHandStack: ItemStack): Boolean {
        if (!running) return true
        val p = mc.player ?: return true
        val mode = resolveRenderMode(p.mainHandItem) ?: return true

        if (mode == FpsRenderMode.EMPTY_HAND_BOXING) {
            return !offHandStack.isEmpty
        }

        // Hide virtual/1.8 offhand shield while holding FPS knife so the shield never blocks the screen
        if (offHandStack.item is ShieldItem && (Animation.block18Animation || ModuleSwordBlock.hideShieldSlot)) {
            return false
        }

        if (!dualHands) return true

        if (!offHandStack.isEmpty && p.isUsingItem && p.usedItemHand == InteractionHand.OFF_HAND) {
            return true
        }
        return false
    }

    private fun isCurrentlyBlocking18(player: LocalPlayer?, mainStack: ItemStack, mode: FpsRenderMode): Boolean {
        if (player == null) return false
        if (mode == FpsRenderMode.EMPTY_HAND_BOXING) {
            return Animation.rightClickSwordGuard &&
                mc.gui.screen() == null &&
                mc.options.keyUse.isDown &&
                player.offhandItem.isEmpty &&
                mc.hitResult?.type != HitResult.Type.BLOCK &&
                !player.isUsingItem
        }

        if (!Animation.block18Animation) return false

        // 1. LiquidBounce ModuleSwordBlock / KillAuraAutoBlock autoblock
        if (ModuleSwordBlock.shouldAnimateSwordBlock(player, mainStack)) {
            return true
        }

        // 2. Native 1.8 / ViaFabricPlus sword block (`BLOCKS_ATTACKS` on main-hand sword or virtual offhand shield)
        if (player.isUsingItem) {
            if (player.usedItemHand == InteractionHand.MAIN_HAND) {
                val anim = mainStack.useAnimation
                if (anim == ItemUseAnimation.BLOCK || mainStack.has(DataComponents.BLOCKS_ATTACKS)) {
                    return true
                }
            } else if (player.usedItemHand == InteractionHand.OFF_HAND && player.offhandItem.item is ShieldItem && mainStack.isSword) {
                return true
            }
        }

        // 3. Direct 1.8 right-click hold (`右键防砍`) when holding a sword & offhand isn't consuming/placing an item
        if (Animation.rightClickSwordGuard && mainStack.isSword && mc.gui.screen() == null && mc.options.keyUse.isDown) {
            val off = player.offhandItem
            val offIsUsable = !off.isEmpty && off.item !is ShieldItem && (
                off.item is BlockItem ||
                    off.has(DataComponents.CONSUMABLE) ||
                    off.useAnimation != ItemUseAnimation.NONE
                )
            val lookingAtBlock = mc.hitResult?.type == HitResult.Type.BLOCK
            if (!offIsUsable && !lookingAtBlock) {
                return true
            }
        }

        return false
    }

    private fun isPlayerStandbyNow(player: LocalPlayer, mode: FpsRenderMode): Boolean {
        val dx = player.x - player.xo
        val dz = player.z - player.zo
        val speed = hypot(dx, dz).toFloat()
        val blocking = isCurrentlyBlocking18(player, player.mainHandItem, mode)
        val miningOrBusy = mc.gameMode?.isDestroying == true ||
            (player.isUsingItem && !blocking)
        val permittedAir = Animation.airInspect && !player.onGround()
        return !blocking && !miningOrBusy && (permittedAir || (!player.isSprinting && player.onGround() && speed <= 0.055f))
    }

    private fun fireCombatAttackAnimation(player: LocalPlayer, mode: FpsRenderMode) {
        val isCrit = !player.onGround() && player.fallDistance > 0.05f && !player.isInWater && !player.onClimbable()
        val isBlocking = isCurrentlyBlocking18(player, player.mainHandItem, mode)
        FpsKnifeAnimator.triggerAttack(
            heavyCritical = isCrit,
            isBlockingNow = isBlocking,
            boxingMode = (mode == FpsRenderMode.EMPTY_HAND_BOXING),
        )
    }

    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { event ->
        if (mc.gui.screen() != null) return@handler
        if (event.action == InputConstants.PRESS && mc.options.keyAttack.matches(event.key)) {
            val p = mc.player ?: return@handler
            val mode = resolveRenderMode(p.mainHandItem) ?: return@handler
            if (attackInput.physical(System.currentTimeMillis())) fireCombatAttackAnimation(p, mode)
        }
        if (event.action == InputConstants.PRESS && event.key == Animation.inspectKey) {
            val p = mc.player ?: return@handler
            val mode = resolveRenderMode(p.mainHandItem) ?: return@handler
            val standby = isPlayerStandbyNow(p, mode)
            FpsKnifeAnimator.triggerInspect(
                onlyWhenStandby = Animation.inspectOnlyWhenIdle,
                isCurrentlyStandby = standby,
            )
        }
    }

    @Suppress("unused")
    private val mouseHandler = handler<MouseButtonEvent> { event ->
        if (event.action != InputConstants.PRESS || event.screen != null || mc.gui.screen() != null) return@handler
        // Explicit LMB also works if attack was rebound; mapped buttons work as well.
        if (event.button != InputConstants.MOUSE_BUTTON_LEFT && !mc.options.keyAttack.matches(event.key)) return@handler
        val p = mc.player ?: return@handler
        val mode = resolveRenderMode(p.mainHandItem) ?: return@handler
        if (attackInput.physical(System.currentTimeMillis())) fireCombatAttackAnimation(p, mode)
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        val p = mc.player ?: return@handler
        if (event.entity is LivingEntity) {
            lastAttackedTargetId = event.entity.id
            lastAttackedTimestampMs = System.currentTimeMillis()
        }
        val mode = resolveRenderMode(p.mainHandItem) ?: return@handler
        if (!mc.options.keyAttack.isDown && attackInput.entity(System.currentTimeMillis())) {
            fireCombatAttackAnimation(p, mode)
        }
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet
        if (packet is ClientboundEntityEventPacket && packet.eventId == EntityEvent.DEATH) {
            val world = mc.level ?: return@handler
            val deadEntity = packet.getEntity(world) ?: return@handler
            if (deadEntity.id == lastAttackedTargetId &&
                System.currentTimeMillis() - lastAttackedTimestampMs <= 6_500L
            ) {
                statTrakKills = (statTrakKills + 1).coerceAtMost(999_999)
                lastAttackedTargetId = -1
            }
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val p = mc.player ?: return@handler
        val stack = p.mainHandItem
        val mode = resolveRenderMode(stack)
        if (mode == null) {
            attackInput.reset()
            return@handler
        }

        FpsKnifeAnimator.checkEquipChange(
            selectedSlot = p.inventory.selectedSlot,
            model = model,
            mode = mode,
            playDrawOnSwitch = Animation.drawOnSwitch,
        )

        // Touch/controllers can update KeyMapping without delivering a desktop mouse event.
        // Hold-to-repeat and fast raw clicks do not require an entity, a hit result, or a damage packet.
        val pressed = mc.gui.screen() == null && mc.options.keyAttack.isDown
        if (attackInput.held(System.currentTimeMillis(), pressed)) fireCombatAttackAnimation(p, mode)

    }

    /**
     * Entry point invoked from `MixinFirstPersonHandsAndItems.injectFpsKnife`.
     * Returns `true` when [ModuleFpsKnife] took over and rendered the FPS knife or empty-hand boxing viewmodel.
     */
    @JvmStatic
    fun renderHandsAndKnife(
        avatar: AbstractClientPlayer,
        mainStack: ItemStack,
        mainArm: HumanoidArm,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        lightCoords: Int,
    ): Boolean {
        val mode = resolveRenderMode(mainStack) ?: return false
        val playerRenderer = mc.entityRenderDispatcher.getRenderer(avatar) as? AvatarRenderer<*> ?: return false
        val localPlayer = mc.player

        val dx = avatar.x - avatar.xo
        val dz = avatar.z - avatar.zo
        val horizSpeed = hypot(dx, dz).toFloat()

        val isBlocking = if (localPlayer != null) {
            isCurrentlyBlocking18(localPlayer, mainStack, mode)
        } else {
            avatar.isUsingItem && avatar.usedItemHand == InteractionHand.MAIN_HAND
        }

        val isBusyNonIdle = mc.gameMode?.isDestroying == true ||
            (avatar.isUsingItem && !isBlocking)

        FpsKnifeAnimator.evaluatePose(
            out = poseSnapshot,
            mode = mode,
            model = model,
            inspectStyle = InspectStyle.AUTO_BY_MODEL,
            block18Style = Animation.block18Style,
            bladeTiltAngleDeg = FpsKnifeChoreography.BLADE_TILT,
            animSpeed = 1f,
            swayIntensity = 1f,
            handSpread = 1f,
            inspectOnlyWhenIdle = Animation.inspectOnlyWhenIdle,
            autoInspectIdleSeconds = Animation.autoInspectIdleSeconds,
            playerYaw = avatar.yRot,
            playerPitch = avatar.xRot,
            horizSpeed = horizSpeed,
            isSprinting = avatar.isSprinting,
            isOnGround = avatar.onGround(),
            isBlocking = isBlocking,
            isBusyNonIdle = isBusyNonIdle,
            allowAirInspect = Animation.airInspect,
            attackPowerMultiplier = 1.15f,
        )

        val side = if (mainArm == HumanoidArm.RIGHT) 1f else -1f
        val offStack = localPlayer?.offhandItem ?: ItemStack.EMPTY
        val offhandFreeForDual = (dualHands || poseSnapshot.forceOffArmVisible) &&
            (offStack.isEmpty || !shouldRenderVanillaOffhand(offStack))
        val slim = avatar.skin.model == PlayerModelType.SLIM
        val thickness = FpsKnifeRig.thickness(FpsKnifeChoreography.HAND_THICKNESS, FpsKnifeChoreography.profile(model).scale, poseSnapshot.knifeVisible)
        val frames = if (poseSnapshot.knifeVisible) FpsKnifeRig.frames(poseSnapshot) else null
        val mainRotation = frames?.hand ?: FpsKnifeRig.armRotation(
            poseSnapshot.mainArmYawY, poseSnapshot.mainArmPitchX, poseSnapshot.mainArmRollZ)
        val mainPalm = Vector3f(poseSnapshot.mainArmX, poseSnapshot.mainArmY, poseSnapshot.mainArmZ)
        val offPalm = Vector3f(poseSnapshot.offArmX, poseSnapshot.offArmY, poseSnapshot.offArmZ)
        val offRotation = FpsKnifeRig.armRotation(poseSnapshot.offArmYawY, poseSnapshot.offArmPitchX, poseSnapshot.offArmRollZ)
        val mainBounds = FpsKnifeRig.armBounds(
            FpsKnifeRig.canonicalArmMatrix(mainPalm, mainRotation, side, slim, thickness), side, slim)
        val offBounds = if (offhandFreeForDual) FpsKnifeRig.armBounds(
            FpsKnifeRig.canonicalArmMatrix(offPalm, offRotation, -side, slim, thickness), -side, slim) else null
        val mesh = if (poseSnapshot.knifeVisible) FpsKnifeMeshes.getMesh(model) else null
        if (mesh != null && frames != null) {
            FpsKnifeRig.clearFlight(poseSnapshot, mesh, frames, FpsKnifeChoreography.profile(model).scale, mainBounds, offBounds)
            // Retract the support hand towards the camera if its sleeve intersects a blade sweep.
            if (offBounds != null) {
                val bladeBounds = FpsKnifeRig.meshBounds(mesh,
                    FpsKnifeRig.bladeMatrix(poseSnapshot, mesh, frames.blade, FpsKnifeChoreography.profile(model).scale),
                    poseSnapshot.safeHandleDeg, poseSnapshot.biteHandleDeg, poseSnapshot.bladeFoldDeg)
                offPalm.add(FpsKnifeRig.supportCorrection(mainBounds, offBounds, bladeBounds))
            }
        }

        poseStack.pushPose()
        // A single reflection mirrors the COMPLETE rig for left-handed players, including its pivots.
        poseStack.scale(side, 1f, 1f)
        if (offhandFreeForDual) {
            submitFpsArm(playerRenderer, avatar, mainArm.opposite, poseStack, collector, lightCoords,
                offPalm, offRotation, -side, slim, thickness)
        }
        submitFpsArm(playerRenderer, avatar, mainArm, poseStack, collector, lightCoords,
            mainPalm, mainRotation, side, slim, thickness)
        if (mesh == null || frames == null) {
            poseStack.popPose()
            return true
        }
        val activeEngraving = engraving
        val textures = FpsKnifeSkins.getOrCreate(skin, activeEngraving, mainStack)
        poseStack.pushPose()
        poseStack.mulPose(FpsKnifeRig.bladeMatrix(poseSnapshot, mesh, frames.blade, FpsKnifeChoreography.profile(model).scale))

        val safeDeg = poseSnapshot.safeHandleDeg
        val biteDeg = poseSnapshot.biteHandleDeg
        val foldDeg = poseSnapshot.bladeFoldDeg

        // Base lit metallic pass
        val boostedLight = boostMinimumBlockLight(lightCoords, minBlockLight = 6)
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(textures.baseId)) { pose, vc ->
            val tmpStack = PoseStack()
            tmpStack.last().pose().set(pose.pose())
            tmpStack.last().normal().set(pose.normal())
            FpsKnifeMeshes.emitMesh(
                mesh = mesh,
                poseStack = tmpStack,
                consumer = vc,
                packedLight = boostedLight,
                argbColor = -1,
                safeHandleDeg = safeDeg,
                biteHandleDeg = biteDeg,
                bladeFoldDeg = foldDeg,
            )
        }

        // Full-bright neon holographic rim / stencil / StatTrak pass
        val glowStrength = Viewmodel.emissiveStrength
        if (glowStrength > 0.01f) {
            val glowAlpha = (glowStrength * 255f).roundToInt().coerceIn(1, 255)
            val glowColor = ARGB.color(glowAlpha, 255, 255, 255)
            val killsSnapshot = statTrakKills
            collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(textures.emissiveId)) { pose, vc ->
                val tmpStack = PoseStack()
                tmpStack.last().pose().set(pose.pose())
                tmpStack.last().normal().set(pose.normal())
                FpsKnifeMeshes.emitMesh(
                    mesh = mesh,
                    poseStack = tmpStack,
                    consumer = vc,
                    packedLight = LightCoordsUtil.FULL_BRIGHT,
                    argbColor = glowColor,
                    safeHandleDeg = safeDeg,
                    biteHandleDeg = biteDeg,
                    bladeFoldDeg = foldDeg,
                )
                if (activeEngraving == BladeEngraving.STATTRAK) {
                    FpsKnifeMeshes.emitStatTrakCounter(
                        mesh = mesh,
                        poseStack = tmpStack,
                        consumer = vc,
                        fullBrightLight = LightCoordsUtil.FULL_BRIGHT,
                        argbColor = glowColor,
                        killCount = killsSnapshot,
                        bladeFoldDeg = foldDeg,
                    )
                }
            }
        }

        poseStack.popPose()
        poseStack.popPose()
        return true
    }

    /** Slim the cross-section in canonical model space without shortening the arm into a stump. */
    private fun submitFpsArm(
        playerRenderer: AvatarRenderer<*>, avatar: AbstractClientPlayer, arm: HumanoidArm,
        poseStack: PoseStack, collector: SubmitNodeCollector, lightCoords: Int,
        palm: Vector3f, rotation: org.joml.Quaternionf, side: Float, slim: Boolean, thickness: Float,
    ) {
        val skinTex = avatar.skin.body.texturePath()
        poseStack.pushPose()
        poseStack.mulPose(FpsKnifeRig.avatarArmMatrix(palm, rotation, side, slim, thickness))
        if (arm == HumanoidArm.RIGHT) {
            playerRenderer.renderRightHand(poseStack, collector, lightCoords, skinTex,
                avatar.isModelPartShown(net.minecraft.world.entity.player.PlayerModelPart.RIGHT_SLEEVE))
        } else {
            playerRenderer.renderLeftHand(poseStack, collector, lightCoords, skinTex,
                avatar.isModelPartShown(net.minecraft.world.entity.player.PlayerModelPart.LEFT_SLEEVE))
        }
        poseStack.popPose()
    }

    private fun boostMinimumBlockLight(packedLight: Int, minBlockLight: Int): Int {
        val block = LightCoordsUtil.block(packedLight)
        val sky = LightCoordsUtil.sky(packedLight)
        return if (block >= minBlockLight) packedLight else LightCoordsUtil.pack(minBlockLight, sky)
    }
}
