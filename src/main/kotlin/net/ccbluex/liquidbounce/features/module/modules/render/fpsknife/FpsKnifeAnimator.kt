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
@file:Suppress("MagicNumber", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions")

package net.ccbluex.liquidbounce.features.module.modules.render.fpsknife

import net.ccbluex.liquidbounce.config.types.list.Tagged
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

enum class InspectStyle(override val tag: String) : Tagged {
    AUTO_BY_MODEL("AutoByModel"),
    RING_SPIN("RingSpin"),
    BALISONG_FAN("BalisongFan"),
    BAYONET_TOSS("BayonetToss"),
    DUAL_SHOWCASE("DualShowcase"),
}

enum class Block18Style(override val tag: String) : Tagged {
    TACTICAL_PARRY("TacticalParry"),
    CLASSIC_1_8("Classic1_8"),
    SPIN_DEFLECT("SpinDeflect"),
}

enum class FpsRenderMode {
    KNIFE_VIEWMODEL,
    EMPTY_HAND_BOXING,
}

/**
 * Full 6-DOF pose snapshot evaluated per frame at full refresh rate (`120-240Hz+`).
 *
 * FpsKnifeRig shares a rigid grip frame between the blade and the skinned arm. Inspection
 * trajectories may deliberately release the knife, with free rotation confined to a clear plane.
 */
class FpsKnifePose {
    // 072: gripped animation basis, captured before action layering.
    var gripArmYawY = 0f
    var gripArmPitchX = 0f
    var gripArmRollZ = 0f
    var gripKnifeYawY = 168f
    var gripKnifeRollX = 38f
    var gripKnifeViewRollZ = 0f
    // 073: held fingertip ring pivot / held safe-handle regrip, NOT release flags.
    var palmFrontSpinBlend = 0f
    var heldBalisongBlend = 0f
    var knifeRelease = 0f
    var knifeFreePoseBlend = 0f
    var knifeVisible: Boolean = true
    var forceOffArmVisible: Boolean = false

    // Main (weapon / rear boxing) hand pose in view space (assuming right-handed; mirrored for left-handed)
    var mainArmX: Float = 0f
    var mainArmY: Float = 0f
    var mainArmZ: Float = 0f
    var mainArmYawY: Float = 0f
    var mainArmPitchX: Float = 0f
    var mainArmRollZ: Float = 0f

    // Support (offhand / lead boxing) hand pose in view space
    var offArmX: Float = 0f
    var offArmY: Float = 0f
    var offArmZ: Float = 0f
    var offArmYawY: Float = 0f
    var offArmPitchX: Float = 0f
    var offArmRollZ: Float = 0f

    // Additional offset from the palm socket ONLY used during mid-air aerial tosses (0,0,0 when gripped!)
    var knifePalmOffsetX: Float = 0f
    var knifePalmOffsetY: Float = 0f
    var knifePalmOffsetZ: Float = 0f

    // Knife 3D view-space orientation (horizontal line = 0 deg baseline, rightward tilt <= 30 deg)
    var knifeYawY: Float = 0f
    var knifeRollX: Float = 0f
    var knifeViewRollZ: Float = 0f

    // Spin around the physical finger ring (`0..1` blend between palm grip pivot and finger ring pivot)
    var ringSpinDeg: Float = 0f
    var ringPivotBlend: Float = 0f

    // Articulated Balisong handles
    var safeHandleDeg: Float = 0f
    var bladeFoldDeg: Float = 0f
    var biteHandleDeg: Float = 0f
}

/**
 * Smooth, frame-rate-independent procedural animation state machine for:
 * 1. 3D FPS Knife (Draw, Idle/Standby, Walk/Sprint, Attack-only Combos, Standby-only Inspect, 1.8 Sword Block & Block-Hit)
 * 2. Empty-Hand Dual-Fist FPS Boxing (Guard, Footwork Weave, Jab/Cross/Hook/Uppercut Combos, Peek-a-Boo Block, Standby Fist-Tap)
 */
object FpsKnifeAnimator {

    private enum class ActionKind {
        NONE,
        DRAW,
        ATTACK,
        INSPECT,
        RECOVER,
    }

    private const val DRAW_DURATION_SEC = 0.56f
    private const val LIGHT_ATTACK_SEC = 0.36f
    private const val HEAVY_ATTACK_SEC = 0.52f
    private const val BOXING_JAB_SEC = 0.30f
    private const val BOXING_HEAVY_SEC = 0.44f
    private const val INSPECT_DURATION_SEC = 2.45f

    private var lastFrameNanos: Long = 0L
    private var currentAction: ActionKind = ActionKind.NONE
    private var recoveryFrom = ActionKind.NONE
    private var recoveryStartPhase = 0f
    private var actionElapsedSec: Float = 0f
    private var actionDurationSec: Float = 1f

    private var attackComboStep: Int = 0
    private var currentAttackHeavy: Boolean = false
    private var currentAttackBlockHit: Boolean = false
    private var lastAttackEndTimeMs: Long = 0L
    private var queuedAttacks = 0
    private var queuedHeavy = false
    private var queuedBlock = false
    private var queuedBoxing = false
    private var attackPower = 1.15f

    private var inspectManualRequest: Boolean = false
    private var inspectVariant: Int = -1
    private var idleTimeSec: Float = 0f
    private var standbyIdleTimerSec: Float = 0f

    // Inertial camera sway & movement spring states
    private var lastPlayerYaw: Float = Float.NaN
    private var lastPlayerPitch: Float = Float.NaN
    private var swayX: Float = 0f
    private var swayY: Float = 0f
    private var swayRoll: Float = 0f
    private var moveBlend: Float = 0f
    private var sprintBlend: Float = 0f
    private var blockBlend: Float = 0f
    private var walkCycleRad: Float = 0f

    private var lastSlot: Int = -1
    private var lastModel: KnifeModel? = null
    private var lastRenderMode: FpsRenderMode? = null

    fun reset() {
        lastFrameNanos = 0L
        currentAction = ActionKind.NONE
        recoveryFrom = ActionKind.NONE
        recoveryStartPhase = 0f
        actionElapsedSec = 0f
        attackComboStep = 0
        currentAttackHeavy = false
        currentAttackBlockHit = false
        inspectManualRequest = false
        inspectVariant = -1
        idleTimeSec = 0f
        walkCycleRad = 0f
        lastAttackEndTimeMs = 0L
        queuedAttacks = 0
        standbyIdleTimerSec = 0f
        swayX = 0f
        swayY = 0f
        swayRoll = 0f
        moveBlend = 0f
        sprintBlend = 0f
        blockBlend = 0f
        lastPlayerYaw = Float.NaN
        lastPlayerPitch = Float.NaN
        lastSlot = -1
        lastModel = null
        lastRenderMode = null
    }

    fun triggerDraw() {
        currentAction = ActionKind.DRAW
        queuedAttacks = 0
        actionElapsedSec = 0f
        actionDurationSec = if (lastRenderMode == FpsRenderMode.EMPTY_HAND_BOXING) DRAW_DURATION_SEC else lastModel?.let { FpsKnifeChoreography.profile(it).drawSeconds } ?: DRAW_DURATION_SEC
        inspectManualRequest = false
        standbyIdleTimerSec = 0f
    }

    /**
     * Physical attack input (including empty/block-facing clicks), held touch input or entity fallback.
     * Visual only: this does not alter Minecraft attack, damage or block-breaking behaviour.
     */
    fun triggerAttack(
        heavyCritical: Boolean,
        isBlockingNow: Boolean = false,
        boxingMode: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        if (!boxingMode && (currentAction == ActionKind.INSPECT || currentAction == ActionKind.DRAW || currentAction == ActionKind.RECOVER)) {
            queuedAttacks = (queuedAttacks + 1).coerceAtMost(3)
            queuedHeavy = heavyCritical
            queuedBlock = isBlockingNow
            queuedBoxing = false
            beginRecovery()
            return
        }
        if (currentAction == ActionKind.ATTACK) {
            // Buffer quick physical clicks instead of discarding them during the power stroke.
            queuedAttacks = (queuedAttacks + 1).coerceAtMost(3)
            queuedHeavy = heavyCritical
            queuedBlock = isBlockingNow
            queuedBoxing = boxingMode
            return
        }
        if (now - lastAttackEndTimeMs > 1250L) {
            attackComboStep = 0
        } else {
            val modulo = if (boxingMode) 4 else 3
            attackComboStep = (attackComboStep + 1) % modulo
        }
        currentAttackHeavy = heavyCritical
        currentAttackBlockHit = isBlockingNow
        currentAction = ActionKind.ATTACK
        actionElapsedSec = 0f
        actionDurationSec = if (boxingMode) {
            if (heavyCritical || attackComboStep == 3) BOXING_HEAVY_SEC else BOXING_JAB_SEC
        } else if (isBlockingNow) {
            0.32f
        } else if (heavyCritical) {
            lastModel?.let { FpsKnifeChoreography.profile(it).heavySeconds } ?: HEAVY_ATTACK_SEC
        } else {
            lastModel?.let { FpsKnifeChoreography.profile(it).lightSeconds } ?: LIGHT_ATTACK_SEC
        }
        inspectManualRequest = false
        standbyIdleTimerSec = 0f
    }

    fun triggerInspect(onlyWhenStandby: Boolean = true, isCurrentlyStandby: Boolean = true) {
        if (currentAction == ActionKind.ATTACK || currentAction == ActionKind.DRAW || currentAction == ActionKind.RECOVER) {
            return
        }
        if (onlyWhenStandby && !isCurrentlyStandby) {
            return
        }
        if (currentAction == ActionKind.INSPECT) {
            // Repeated V must not teleport a released knife back into the palm.
            return
        }
        currentAction = ActionKind.INSPECT
        actionElapsedSec = 0f
        actionDurationSec = if (lastRenderMode == FpsRenderMode.EMPTY_HAND_BOXING) INSPECT_DURATION_SEC else lastModel?.let { FpsKnifeChoreography.profile(it).inspectSeconds } ?: INSPECT_DURATION_SEC
        inspectManualRequest = true
        inspectVariant = (inspectVariant + 1) % 2
    }

    private fun beginRecovery() {
        if (currentAction == ActionKind.RECOVER) return
        recoveryFrom = currentAction
        recoveryStartPhase = (actionElapsedSec / actionDurationSec).coerceIn(0f, 1f)
        actionDurationSec = ((1f - recoveryStartPhase) * actionDurationSec).coerceIn(.12f, .65f)
        actionElapsedSec = 0f
        currentAction = ActionKind.RECOVER
        inspectManualRequest = false
        standbyIdleTimerSec = 0f
    }

    fun checkEquipChange(selectedSlot: Int, model: KnifeModel, mode: FpsRenderMode, playDrawOnSwitch: Boolean) {
        val changed = (lastSlot != -1 && lastSlot != selectedSlot) ||
            (lastModel != null && lastModel != model) ||
            (lastRenderMode != null && lastRenderMode != mode)
        lastSlot = selectedSlot
        lastModel = model
        lastRenderMode = mode
        if (changed) {
            // Never carry a detached knife/queued strike into a different model or boxing mode,
            // even if the user disabled the cosmetic draw-on-switch animation.
            currentAction = ActionKind.NONE
            recoveryFrom = ActionKind.NONE
            actionElapsedSec = 0f
            queuedAttacks = 0
            inspectManualRequest = false
            standbyIdleTimerSec = 0f
            if (playDrawOnSwitch) triggerDraw()
        } else if (lastFrameNanos == 0L && playDrawOnSwitch && currentAction == ActionKind.NONE) {
            triggerDraw()
        }
    }

    fun evaluatePose(
        out: FpsKnifePose,
        mode: FpsRenderMode,
        model: KnifeModel,
        inspectStyle: InspectStyle,
        block18Style: Block18Style,
        bladeTiltAngleDeg: Float,
        animSpeed: Float,
        swayIntensity: Float,
        handSpread: Float,
        inspectOnlyWhenIdle: Boolean,
        autoInspectIdleSeconds: Float,
        playerYaw: Float,
        playerPitch: Float,
        horizSpeed: Float,
        isSprinting: Boolean,
        isOnGround: Boolean,
        isBlocking: Boolean,
        isBusyNonIdle: Boolean,
        allowAirInspect: Boolean = true,
        attackPowerMultiplier: Float = 1.15f,
        frameTimeNanos: Long = System.nanoTime(),
    ) {
        attackPower = attackPowerMultiplier.coerceIn(0.6f, 1.6f)
        val nowNanos = frameTimeNanos
        val rawDt = if (lastFrameNanos == 0L) {
            1f / 120f
        } else {
            ((nowNanos - lastFrameNanos) * 1e-9f).coerceIn(0.0005f, 0.08f)
        }
        lastFrameNanos = nowNanos

        val speedMul = animSpeed.coerceIn(0.35f, 2.5f)
        val dt = rawDt * speedMul
        idleTimeSec += rawDt

        // Camera angular velocity inertial sway
        if (lastPlayerYaw.isNaN()) {
            lastPlayerYaw = playerYaw
            lastPlayerPitch = playerPitch
        }
        var dYaw = playerYaw - lastPlayerYaw
        while (dYaw > 180f) dYaw -= 360f
        while (dYaw < -180f) dYaw += 360f
        val dPitch = (playerPitch - lastPlayerPitch).coerceIn(-60f, 60f)
        lastPlayerYaw = playerYaw
        lastPlayerPitch = playerPitch

        val targetSwayX = (-dYaw / rawDt * 0.000025f * swayIntensity).coerceIn(-0.065f, 0.065f)
        val targetSwayY = (dPitch / rawDt * 0.0000217f * swayIntensity).coerceIn(-0.055f, 0.055f)
        val targetSwayRoll = (-dYaw / rawDt * 0.0035f * swayIntensity).coerceIn(-5.5f, 5.5f)
        val swayAlpha = 1f - exp(-14f * rawDt)
        swayX += (targetSwayX - swayX) * swayAlpha
        swayY += (targetSwayY - swayY) * swayAlpha
        swayRoll += (targetSwayRoll - swayRoll) * swayAlpha

        // Movement, sprint & 1.8 sword-block / block-hit blends
        val movingTarget = if (isOnGround && horizSpeed > 0.035f) min(1f, horizSpeed / 0.22f) else 0f
        val sprintTarget = if (isSprinting && horizSpeed > 0.12f) 1f else 0f
        // Keep blockBlend active BOTH during 1.8 guard AND during 1.8 RMB+LMB block-hit!
        val blockTarget = if (isBlocking && currentAction != ActionKind.DRAW) 1f else 0f

        val blendAlpha = 1f - exp(-12f * rawDt)
        val blockAlpha = 1f - exp(-18f * rawDt)
        moveBlend += (movingTarget - moveBlend) * blendAlpha
        sprintBlend += (sprintTarget - sprintBlend) * blendAlpha
        blockBlend += (blockTarget - blockBlend) * blockAlpha

        if (moveBlend > 0.01f) {
            val freq = if (isSprinting) 11.5f else 8.2f
            walkCycleRad = (walkCycleRad + rawDt * freq * (0.45f + 0.55f * moveBlend)) % (2f * PI.toFloat())
        }

        // True Standby (`待机`) detection: standing still, not attacking, not drawing, not blocking, not mining/using!
        val airborneManual = allowAirInspect && inspectManualRequest && !isOnGround
        val isMovingOrAirborne = (horizSpeed > 0.055f || isSprinting || !isOnGround) && !airborneManual
        val isTrulyStandby = !isBlocking && !isBusyNonIdle && !isMovingOrAirborne &&
            currentAction != ActionKind.ATTACK && currentAction != ActionKind.DRAW

        // Cancel inspect immediately if blocking/mining, or on movement when `inspectOnlyWhenIdle` is enabled
        if (currentAction == ActionKind.INSPECT) {
            if (isBlocking || isBusyNonIdle || (!isOnGround && !allowAirInspect) || (inspectOnlyWhenIdle && isMovingOrAirborne)) {
                beginRecovery()
            }
        }

        // Standby auto-inspect timer (`待机时才检视`)
        if (currentAction == ActionKind.NONE && isTrulyStandby && isOnGround) {
            standbyIdleTimerSec += rawDt
            if (autoInspectIdleSeconds > 0.2f && standbyIdleTimerSec >= autoInspectIdleSeconds) {
                standbyIdleTimerSec = 0f
                triggerInspect(onlyWhenStandby = true, isCurrentlyStandby = true)
                inspectManualRequest = false
            }
        } else {
            standbyIdleTimerSec = 0f
        }

        // Advance active action timeline
        if (currentAction != ActionKind.NONE) {
            actionElapsedSec += dt
            if (actionElapsedSec >= actionDurationSec) {
                if (currentAction == ActionKind.ATTACK) {
                    lastAttackEndTimeMs = System.currentTimeMillis()
                }
                currentAction = ActionKind.NONE
                inspectManualRequest = false
                actionElapsedSec = 0f
                if (queuedAttacks > 0) {
                    queuedAttacks--
                    triggerAttack(queuedHeavy, queuedBlock, queuedBoxing)
                }
            }
        }

        if (mode == FpsRenderMode.EMPTY_HAND_BOXING) {
            evaluateBoxingPose(out, handSpread, blockBlend)
            return
        }

        evaluateKnifeViewmodelPose(
            out = out,
            model = model,
            inspectStyle = inspectStyle,
            block18Style = block18Style,
            bladeTiltAngleDeg = bladeTiltAngleDeg.coerceIn(0f, 30f),
            handSpread = handSpread,
        )
    }

    // =========================================================================
    // Mode A: 3D FPS Knife + Dual Hands + 1.8 Block / Block-Hit
    // =========================================================================

    private fun evaluateKnifeViewmodelPose(
        out: FpsKnifePose,
        model: KnifeModel,
        inspectStyle: InspectStyle,
        block18Style: Block18Style,
        bladeTiltAngleDeg: Float,
        handSpread: Float,
    ) {
        out.knifeVisible = true
        out.forceOffArmVisible = false
        out.knifePalmOffsetX = 0f
        out.knifePalmOffsetY = 0f
        out.knifePalmOffsetZ = 0f
        out.ringSpinDeg = 0f
        out.ringPivotBlend = 0f
        out.knifeRelease = 0f
        out.palmFrontSpinBlend = 0f
        out.heldBalisongBlend = 0f
        out.knifeFreePoseBlend = 0f
        out.safeHandleDeg = 0f
        out.biteHandleDeg = 0f
        out.bladeFoldDeg = 0f

        // 1. Base FPS Tactical Grip:
        // Right hand sits cleanly in lower-right; knife grip pivot is locked directly inside the fist socket!
        val spreadOffset = (handSpread - 1f) * 0.14f
        out.mainArmX = 0.325f + spreadOffset
        out.mainArmY = -0.335f
        out.mainArmZ = -0.735f
        out.mainArmYawY = -10f
        out.mainArmPitchX = -6f
        out.mainArmRollZ = -28f

        // Left tactical support hand framed in lower-left
        out.offArmX = -0.335f - spreadOffset
        out.offArmY = -0.385f
        out.offArmZ = -0.745f
        out.offArmYawY = 10f
        out.offArmPitchX = -6f
        out.offArmRollZ = 28f

        // Exact horizontal baseline (`0°` = horizontal line `————`), rightward tilt strictly `<= 30°`!
        val baseYawY = 168f
        val baseRollX = 38f
        val baseViewRollZ = solveBladeRollForScreenTilt(
            palmX = out.mainArmX - 0.04f,
            palmY = out.mainArmY - 0.05f,
            palmZ = out.mainArmZ + 0.08f,
            yawDeg = baseYawY,
            targetScreenTiltDeg = bladeTiltAngleDeg,
        )
        out.knifeYawY = baseYawY
        out.knifeRollX = baseRollX
        out.knifeViewRollZ = baseViewRollZ

        // 2. Subtle breathing, walk bob & inertial sway (hand + knife move TOGETHER!)
        val actionSuppression = if (currentAction == ActionKind.NONE) 1f else 0.25f
        val breathY = sin(idleTimeSec * 2.0f) * 0.0055f * actionSuppression
        val breathRoll = cos(idleTimeSec * 1.6f) * 0.65f * actionSuppression

        val handlingMass = FpsKnifeChoreography.profile(model).mass
        val locomotionWeight = (.70f + .30f * actionSuppression) / handlingMass
        val bobX = sin(walkCycleRad) * .014f * moveBlend * (1f + .20f * sprintBlend) * locomotionWeight
        val bobY = -abs(cos(walkCycleRad)) * .012f * moveBlend * (1f + .25f * sprintBlend) * locomotionWeight

        out.mainArmX += bobX + swayX * 0.85f
        out.mainArmY += breathY + bobY + swayY * 0.85f - 0.025f * sprintBlend
        out.mainArmPitchX -= 5f * sprintBlend

        out.offArmX += bobX * 0.9f + swayX * 0.65f + 0.03f * sprintBlend
        out.offArmY += -breathY * 0.7f + bobY * 0.9f + swayY * 0.65f - 0.035f * sprintBlend
        out.offArmPitchX -= 8f * sprintBlend

        // Clamp idle/walk/sprint rightward screen tilt so it NEVER exceeds 30 degrees!
        val maxAllowedIdleRollZ = solveBladeRollForScreenTilt(
            palmX = out.mainArmX - 0.04f,
            palmY = out.mainArmY - 0.05f,
            palmZ = out.mainArmZ + 0.08f,
            yawDeg = out.knifeYawY,
            targetScreenTiltDeg = 30.0f,
        )
        val desiredIdleRollZ = baseViewRollZ + breathRoll + swayRoll * 0.45f - 2.5f * sprintBlend
        // Note: more negative `knifeViewRollZ` = steeper rightward tilt; keep >= maxAllowedIdleRollZ (`<= 30°`!)
        out.knifeViewRollZ = maxOf(desiredIdleRollZ, maxAllowedIdleRollZ)

        // 3. 1.8 Right-Click Sword Block Stance (`右键防砍格挡姿态`)
        if (blockBlend > 0.005f) {
            val b = smoothStep01(blockBlend)
            when (block18Style) {
                Block18Style.TACTICAL_PARRY -> {
                    // Two-hand tactical FPS parry guard: knife raised across chest (`<= 26°` tilt), offhand bracing forearm!
                    val guardRollZ = solveBladeRollForScreenTilt(0.20f, -0.22f, -0.66f, 166f, min(26f, bladeTiltAngleDeg + 4f))
                    out.mainArmX = lerp(out.mainArmX, 0.235f, b)
                    out.mainArmY = lerp(out.mainArmY, -0.235f, b)
                    out.mainArmZ = lerp(out.mainArmZ, -0.665f, b)
                    out.mainArmYawY = lerp(out.mainArmYawY, 26f, b)
                    out.mainArmPitchX = lerp(out.mainArmPitchX, 12f, b)
                    out.mainArmRollZ = lerp(out.mainArmRollZ, -18f, b)

                    out.offArmX = lerp(out.offArmX, -0.195f, b)
                    out.offArmY = lerp(out.offArmY, -0.265f, b)
                    out.offArmZ = lerp(out.offArmZ, -0.685f, b)
                    out.offArmYawY = lerp(out.offArmYawY, -34f, b)
                    out.offArmPitchX = lerp(out.offArmPitchX, 16f, b)
                    out.offArmRollZ = lerp(out.offArmRollZ, 22f, b)

                    out.knifeYawY = lerp(out.knifeYawY, 166f, b)
                    out.knifeRollX = lerp(out.knifeRollX, 58f, b)
                    out.knifeViewRollZ = lerp(out.knifeViewRollZ, guardRollZ, b)
                }

                Block18Style.CLASSIC_1_8 -> {
                    // Classic Minecraft 1.7 / 1.8 right-click sword block posture adapted to 3D knife + fist!
                    val guardRollZ = solveBladeRollForScreenTilt(0.26f, -0.25f, -0.68f, 158f, min(28f, bladeTiltAngleDeg + 5f))
                    out.mainArmX = lerp(out.mainArmX, 0.285f, b)
                    out.mainArmY = lerp(out.mainArmY, -0.255f, b)
                    out.mainArmZ = lerp(out.mainArmZ, -0.675f, b)
                    out.mainArmYawY = lerp(out.mainArmYawY, 32f, b)
                    out.mainArmPitchX = lerp(out.mainArmPitchX, 18f, b)
                    out.mainArmRollZ = lerp(out.mainArmRollZ, -24f, b)

                    out.offArmX = lerp(out.offArmX, -0.345f, b)
                    out.offArmY = lerp(out.offArmY, -0.365f, b)
                    out.offArmZ = lerp(out.offArmZ, -0.725f, b)
                    out.offArmPitchX = lerp(out.offArmPitchX, 8f, b)

                    out.knifeYawY = lerp(out.knifeYawY, 158f, b)
                    out.knifeRollX = lerp(out.knifeRollX, 68f, b)
                    out.knifeViewRollZ = lerp(out.knifeViewRollZ, guardRollZ, b)
                }

                Block18Style.SPIN_DEFLECT -> {
                    // Raised reverse-angle deflect guard with subtle rhythmic defensive poise
                    val pulse = sin(idleTimeSec * 5.0f) * 2.0f
                    val guardRollZ = solveBladeRollForScreenTilt(0.22f, -0.23f, -0.66f, 170f, min(25f, bladeTiltAngleDeg + 3f))
                    out.mainArmX = lerp(out.mainArmX, 0.245f, b)
                    out.mainArmY = lerp(out.mainArmY, -0.225f, b)
                    out.mainArmZ = lerp(out.mainArmZ, -0.655f, b)
                    out.mainArmYawY = lerp(out.mainArmYawY, 22f, b)
                    out.mainArmPitchX = lerp(out.mainArmPitchX, 14f, b)
                    out.mainArmRollZ = lerp(out.mainArmRollZ, -16f, b)

                    out.offArmX = lerp(out.offArmX, -0.175f, b)
                    out.offArmY = lerp(out.offArmY, -0.255f, b)
                    out.offArmZ = lerp(out.offArmZ, -0.675f, b)
                    out.offArmYawY = lerp(out.offArmYawY, -38f, b)
                    out.offArmPitchX = lerp(out.offArmPitchX, 18f, b)

                    out.knifeYawY = lerp(out.knifeYawY, 170f + pulse, b)
                    out.knifeRollX = lerp(out.knifeRollX, 52f, b)
                    out.knifeViewRollZ = lerp(out.knifeViewRollZ, guardRollZ, b)
                }
            }
        }

        FpsKnifeChoreography.idle(out, model)
        out.gripArmYawY = out.mainArmYawY
        out.gripArmPitchX = out.mainArmPitchX
        out.gripArmRollZ = out.mainArmRollZ
        out.gripKnifeYawY = out.knifeYawY
        out.gripKnifeRollX = out.knifeRollX
        out.gripKnifeViewRollZ = out.knifeViewRollZ

        // 4. Active Action Layer (Draw / Attack / 1.8 Block-Hit / Standby Inspect)
        val p = if (actionDurationSec > 1e-4f) (actionElapsedSec / actionDurationSec).coerceIn(0f, 1f) else 0f
        when (currentAction) {
            ActionKind.NONE -> Unit
            ActionKind.DRAW -> FpsKnifeChoreography.draw(out, model, p)
            ActionKind.ATTACK -> {
                if (currentAttackBlockHit || blockBlend > 0.35f) {
                    applyBlockHit18Animation(out, model, block18Style, p)
                } else {
                    FpsKnifeChoreography.attack(out, model, p, currentAttackHeavy, attackComboStep)
                }
            }
            ActionKind.INSPECT -> FpsKnifeChoreography.inspect(out, model, p, inspectVariant)
            ActionKind.RECOVER -> {
                val phase = recoveryStartPhase + (1f - recoveryStartPhase) * p
                if (recoveryFrom == ActionKind.DRAW) FpsKnifeChoreography.draw(out, model, phase)
                else FpsKnifeChoreography.inspect(out, model, phase, inspectVariant)
            }
        }
    }

    // =========================================================================
    // 1.8 Block-Hit (`右键防砍`: RMB Guard + LMB Attack Simultaneously!)
    // =========================================================================

    private fun applyBlockHit18Animation(
        out: FpsKnifePose,
        model: KnifeModel,
        style: Block18Style,
        p: Float,
    ) {
        val swing = powerStroke(p)
        val wind = windupPulse(p)
        val kick = impactPulse(p)
        val stylePower = when (style) {
            Block18Style.TACTICAL_PARRY -> 1f
            Block18Style.CLASSIC_1_8 -> 1.12f
            Block18Style.SPIN_DEFLECT -> 0.92f
        } * attackPower
        out.mainArmX += (0.035f * wind - 0.12f * swing) * stylePower
        out.mainArmY += (0.055f * wind - 0.10f * swing + 0.012f * kick) * stylePower
        out.mainArmZ += (0.04f * wind - 0.19f * swing + 0.025f * kick) * stylePower
        out.mainArmPitchX += (-9f * wind + 26f * swing - 4f * kick) * stylePower
        out.mainArmYawY += 13f * swing * stylePower
        out.knifeViewRollZ += (10f * wind - 34f * swing) * stylePower
        out.offArmX -= 0.045f * swing
        out.offArmY -= 0.045f * swing
        // Deflect is a braced wrist chop, NOT a blade spinning through a closed fist.
    }

    // =========================================================================
    // Mode B: Empty-Hand Dual-Fist FPS Boxing (`空手拳击动画`)
    // =========================================================================

    private fun evaluateBoxingPose(
        out: FpsKnifePose,
        handSpread: Float,
        currentBlockBlend: Float,
    ) {
        out.knifeVisible = false
        out.forceOffArmVisible = true
        out.knifePalmOffsetX = 0f
        out.knifePalmOffsetY = 0f
        out.knifePalmOffsetZ = 0f

        val spread = (handSpread - 1f) * 0.10f
        // Rhythmic boxer footwork bounce & head-slip weave
        val bounceY = abs(sin(idleTimeSec * 3.4f)) * 0.012f + (-abs(cos(walkCycleRad)) * 0.022f * moveBlend)
        val weaveX = sin(idleTimeSec * 1.7f) * 0.014f + sin(walkCycleRad) * 0.022f * moveBlend

        // Lead Fist (Offhand / Left): held slightly forward and higher in classic orthodox boxing stance
        out.offArmX = -0.265f - spread + weaveX + swayX * 0.75f
        out.offArmY = -0.295f + bounceY + swayY * 0.75f - 0.02f * sprintBlend
        out.offArmZ = -0.665f
        out.offArmYawY = 10f
        out.offArmPitchX = 18f
        out.offArmRollZ = 28f

        // Rear Power Fist (Main Hand / Right): tucked closer guarding right cheekbone/jaw
        out.mainArmX = 0.285f + spread + weaveX + swayX * 0.80f
        out.mainArmY = -0.325f + bounceY * 0.85f + swayY * 0.80f - 0.02f * sprintBlend
        out.mainArmZ = -0.635f
        out.mainArmYawY = -10f
        out.mainArmPitchX = 16f
        out.mainArmRollZ = -28f

        // High Peek-a-Boo Double-Forearm Guard when blocking!
        if (currentBlockBlend > 0.005f) {
            val b = smoothStep01(currentBlockBlend)
            out.offArmX = lerp(out.offArmX, -0.135f, b)
            out.offArmY = lerp(out.offArmY, -0.185f, b)
            out.offArmZ = lerp(out.offArmZ, -0.585f, b)
            out.offArmYawY = lerp(out.offArmYawY, -38f, b)
            out.offArmPitchX = lerp(out.offArmPitchX, 28f, b)
            out.offArmRollZ = lerp(out.offArmRollZ, 26f, b)

            out.mainArmX = lerp(out.mainArmX, 0.135f, b)
            out.mainArmY = lerp(out.mainArmY, -0.185f, b)
            out.mainArmZ = lerp(out.mainArmZ, -0.585f, b)
            out.mainArmYawY = lerp(out.mainArmYawY, 38f, b)
            out.mainArmPitchX = lerp(out.mainArmPitchX, 28f, b)
            out.mainArmRollZ = lerp(out.mainArmRollZ, -26f, b)
        }

        val neutral = floatArrayOf(out.mainArmX, out.mainArmY, out.mainArmZ,
            out.mainArmYawY, out.mainArmPitchX, out.mainArmRollZ,
            out.offArmX, out.offArmY, out.offArmZ, out.offArmYawY, out.offArmPitchX, out.offArmRollZ)
        val p = if (actionDurationSec > 1e-4f) (actionElapsedSec / actionDurationSec).coerceIn(0f, 1f) else 0f
        when (currentAction) {
            ActionKind.NONE, ActionKind.RECOVER -> Unit

            ActionKind.DRAW -> {
                // Raise both fists from waist + quick boxer glove double-tap ready check!
                val rise = 1f - easeOutBack(min(1f, p / 0.60f))
                val tap = bellCurve(p, 0.62f, 0.94f)
                out.offArmY -= 0.32f * rise
                out.mainArmY -= 0.32f * rise
                out.offArmX += 0.145f * tap
                out.mainArmX -= 0.145f * tap
                out.offArmZ -= 0.04f * tap
                out.mainArmZ -= 0.04f * tap
            }

            ActionKind.ATTACK -> {
                // Fast ballistic punch curve: explosive extension (0..0.34), impact snap, crisp retraction (0.34..1.0)
                val punch = if (p < 0.32f) {
                    easeOutCubic(p / 0.32f)
                } else {
                    1f - easeInOutCubic((p - 0.32f) / 0.68f)
                }
                val combo = if (currentAttackHeavy) 3 else (attackComboStep % 4)
                when (combo) {
                    0 -> {
                        // 1. Snappy Left Jab (`左刺拳`) straight into crosshair!
                        out.offArmX += 0.215f * punch
                        out.offArmY += 0.115f * punch
                        out.offArmZ -= 0.345f * punch
                        out.offArmPitchX -= 22f * punch
                        out.offArmYawY += 18f * punch
                        out.offArmRollZ += 34f * punch
                        // Rear hand tucks tighter to guard chin
                        out.mainArmX += 0.035f * punch
                        out.mainArmZ += 0.045f * punch
                    }

                    1 -> {
                        // 2. Heavy Right Cross (`右直拳`) driving straight through crosshair!
                        out.mainArmX -= 0.245f * punch
                        out.mainArmY += 0.135f * punch
                        out.mainArmZ -= 0.395f * punch
                        out.mainArmPitchX -= 24f * punch
                        out.mainArmYawY -= 20f * punch
                        out.mainArmRollZ -= 38f * punch
                        // Lead hand retracts to guard cheek
                        out.offArmX -= 0.045f * punch
                        out.offArmZ += 0.055f * punch
                    }

                    2 -> {
                        // 3. Sweeping Left Hook (`左摆拳`) arcing from wide left across center!
                        val windup = bellCurve(p, 0.0f, 0.22f)
                        val hook = bellCurve(p, 0.14f, 0.88f)
                        out.offArmX += -0.11f * windup + 0.34f * hook
                        out.offArmY += 0.125f * hook
                        out.offArmZ -= 0.285f * hook
                        out.offArmYawY += 46f * hook - 14f * windup
                        out.offArmRollZ += 48f * hook
                        out.mainArmZ += 0.04f * hook
                    }

                    else -> {
                        // 4. Crushing Right Uppercut (`右上勾拳`) dipping low & exploding upward!
                        val dip = bellCurve(p, 0.0f, 0.28f)
                        val drive = bellCurve(p, 0.18f, 0.92f)
                        out.mainArmX -= 0.215f * drive
                        out.mainArmY += -0.145f * dip + 0.245f * drive
                        out.mainArmZ -= 0.335f * drive
                        out.mainArmPitchX += -12f * dip + 34f * drive
                        out.mainArmYawY -= 18f * drive
                        out.mainArmRollZ += 22f * drive
                        out.offArmY += 0.04f * drive
                    }
                }
            }

            ActionKind.INSPECT -> {
                // Standby Boxer Warmup: double fist-bump (`碰拳`) + alternating wrist roll & shoulder loosen!
                val bump1 = bellCurve(p, 0.08f, 0.30f)
                val bump2 = bellCurve(p, 0.30f, 0.52f)
                val rollWave = bellCurve(p, 0.52f, 0.96f)
                val bump = bump1 + bump2 * 0.85f
                out.offArmX += 0.155f * bump
                out.mainArmX -= 0.155f * bump
                out.offArmY += 0.065f * bump + sin(p * 4f * PI.toFloat()) * 0.025f * rollWave
                out.mainArmY += 0.065f * bump - sin(p * 4f * PI.toFloat()) * 0.025f * rollWave
                out.offArmRollZ += sin(p * 6f * PI.toFloat()) * 24f * rollWave
                out.mainArmRollZ -= sin(p * 6f * PI.toFloat()) * 24f * rollWave
            }
        }
        if (currentAction == ActionKind.ATTACK) {
            out.mainArmX = lerp(neutral[0], out.mainArmX, attackPower)
            out.mainArmY = lerp(neutral[1], out.mainArmY, attackPower)
            out.mainArmZ = lerp(neutral[2], out.mainArmZ, attackPower)
            out.mainArmYawY = lerp(neutral[3], out.mainArmYawY, attackPower)
            out.mainArmPitchX = lerp(neutral[4], out.mainArmPitchX, attackPower)
            out.mainArmRollZ = lerp(neutral[5], out.mainArmRollZ, attackPower)
            out.offArmX = lerp(neutral[6], out.offArmX, attackPower)
            out.offArmY = lerp(neutral[7], out.offArmY, attackPower)
            out.offArmZ = lerp(neutral[8], out.offArmZ, attackPower)
            out.offArmYawY = lerp(neutral[9], out.offArmYawY, attackPower)
            out.offArmPitchX = lerp(neutral[10], out.offArmPitchX, attackPower)
            out.offArmRollZ = lerp(neutral[11], out.offArmRollZ, attackPower)
        }
    }

    // =========================================================================
    // Knife Draw, Attack-Only Slashes & Standby-Only Inspects (Hand-Locked!)
    // =========================================================================

    private fun windupPulse(p: Float): Float =
        smoothStep(0f, 0.18f, p) * (1f - smoothStep(0.19f, 0.34f, p))

    private fun powerStroke(p: Float): Float = when {
        p < 0.20f -> 0f
        p < 0.34f -> easeOutCubic((p - 0.20f) / 0.14f)
        p < 0.41f -> 1f
        else -> 1f - easeInOutCubic((p - 0.41f) / 0.59f)
    }

    private fun impactPulse(p: Float): Float = bellCurve(p, 0.33f, 0.46f)

    /**
     * Exact perspective-aware solver computing `knifeViewRollZ` so the 3D blade projects onto
     * the 2D screen at `targetScreenTiltDeg` (`0°` = exact horizontal reference line `————`,
     * `30°` = max `30°` downward-right tilt into the right hand).
     */
    private fun solveBladeRollForScreenTilt(
        palmX: Float,
        palmY: Float,
        palmZ: Float,
        yawDeg: Float,
        targetScreenTiltDeg: Float,
    ): Float {
        val clampedDeg = targetScreenTiltDeg.coerceIn(0f, 30f)
        val yawDeltaRad = (180f - yawDeg) * (PI.toFloat() / 180f)
        val cYaw = cos(yawDeltaRad)
        val sYaw = sin(yawDeltaRad)
        val perspBiasDeg = atan2(-palmY * sYaw, -palmZ * cYaw) * (180f / PI.toFloat())
        return -(clampedDeg - perspBiasDeg * 0.94f)
    }

    // =========================================================================
    // Smooth Easing Utilities
    // =========================================================================

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun sqrt01(t: Float): Float = kotlin.math.sqrt(t.coerceIn(0f, 1f))

    private fun smoothStep01(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun bellCurve(x: Float, start: Float, end: Float): Float {
        if (x <= start || x >= end) return 0f
        val mid = (start + end) * 0.5f
        return if (x < mid) {
            smoothStep(start, mid, x)
        } else {
            1f - smoothStep(mid, end, x)
        }
    }

    private fun easeOutCubic(t: Float): Float {
        val inv = 1f - t.coerceIn(0f, 1f)
        return 1f - inv * inv * inv
    }

    private fun easeInOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) {
            4f * x * x * x
        } else {
            val f = -2f * x + 2f
            1f - (f * f * f) * 0.5f
        }
    }

    private fun easeOutBack(t: Float): Float {
        val x = t.coerceIn(0f, 1f) - 1f
        val c1 = 1.55f
        val c3 = c1 + 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }
}
