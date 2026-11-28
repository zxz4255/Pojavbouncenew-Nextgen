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
 * Visuals & Bezier paw mechanics faithfully adapted from the original open-source BongoCat projects:
 * - kuroni/bongocat-osu (Copyright (c) 2018 Dang Doan Duc Trung & Kuvster, MIT License)
 * - MMmmmoko/Bongo-Cat-Mver (Copyright (c) 2020 Han Zhang, MIT License)
 * - Externalizable/bongo.cat (Copyright (c) 2018 Eric Huber, StrayRogue & DitzyFlama, MIT License)
 */
@file:Suppress("MagicNumber", "LongMethod", "CognitiveComplexMethod")

package net.ccbluex.liquidbounce.features.module.modules.render.bongocat

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.KeyEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.READ_FINAL_STATE
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.ChatScreen
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Authentic HUD BongoCat overlay faithfully reproducing the original GitHub BongoCat visuals
 * (`MMmmmoko/Bongo-Cat-Mver`, `kuroni/bongocat-osu`, and `Externalizable/bongo.cat`) at native
 * `612x354` sprite fidelity with Kuvster's degree-18 smooth Bezier mouse arm and `libbongocat.so`
 * native CPS & spring state tracking.
 */
object ModuleBongoCat : ClientModule("BongoCat", ModuleCategories.RENDER) {

    enum class InstrumentMode(override val tag: String) : Tagged {
        STANDARD("Standard"),
        OSU_RGB("OsuRgb"),
        BONGO_DRUM("BongoDrum"),
        PURE_KEYBOARD("PureKeyboard"),
    }

    enum class ExpressionMode(override val tag: String) : Tagged {
        DEFAULT_FACE("DefaultFace"),
        AUTO("Auto"),
        BLUSH("Blush"),
        COOL_SHADES("CoolShades"),
        SWEAT("Sweat"),
    }

    private const val BASE_WIDGET_W = 204f // Exact 1/3 of 612px native canvas
    private const val BASE_WIDGET_H = 118f // Exact 1/3 of 354px native canvas
    private const val CANVAS_TO_WIDGET = BASE_WIDGET_W / BongoCatSprites.CANVAS_W
    private const val KEY_MIN_HOLD_SEC = 0.048f
    private const val RESTRIKE_LIFT_SEC = 0.026f
    private const val STD_KEY_COUNT = 15
    private const val PURE_KB_KEY_COUNT = 7

    private val mode by enumChoice("Mode", InstrumentMode.STANDARD)
    private var posX by float("PositionX", 78f, 0f..100f, "%")
    private var posY by float("PositionY", 72f, 0f..100f, "%")
    private val scale by float("Scale", 1.0f, 0.45f..2.4f)
    private val deskOpacity by int("DeskOpacity", 100, 0..100, "%")
    private val leftHanded by boolean("LeftHanded", false)
    private val expression by enumChoice("Expression", ExpressionMode.DEFAULT_FACE)
    private val mouseSensitivity by float("MouseSensitivity", 1.0f, 0.35f..2.5f)
    private val reactToSwings by boolean("ReactToSwings", true)
    private val showCps by boolean("ShowCPS", true)
    private val showInScreens by boolean("ShowInScreens", false)
    private val pawColor by color("PawColor", Color4b(255, 255, 255, 255))
    private val pawOutlineColor by color("PawOutlineColor", Color4b(0, 0, 0, 255))

    private val session = NativeBongoCatCore.Session()
    private val pose = FloatArray(NativeBongoCatCore.POSE_FLOATS)
    private val mouseDownButtons = HashSet<Int>()

    // Per-key hold timers & press timestamps for Standard 15-key keyboard
    private val stdKeyHoldSec = FloatArray(STD_KEY_COUNT)
    private val stdKeyStampNs = LongArray(STD_KEY_COUNT)
    private val stdKeyPrevRaw = BooleanArray(STD_KEY_COUNT)
    private var stdRestrikeGapSec = 0f

    // PureKeyboard 7-key state (`0..2` left paw, `3..6` right paw)
    private val kbKeyHoldSec = FloatArray(PURE_KB_KEY_COUNT)
    private val kbKeyStampNs = LongArray(PURE_KB_KEY_COUNT)
    private val kbKeyPrevRaw = BooleanArray(PURE_KB_KEY_COUNT)
    private var kbLeftGapSec = 0f
    private var kbRightGapSec = 0f

    // Osu RGB & Bongo Drum alternating paw state
    private var osuPawToggle = 0 // 0 = left.png, 1 = right.png
    private var osuPawHoldSec = 0f
    private var osuPawGapSec = 0f

    private var drumViewerLeftHoldSec = 0f // rightcentre / rightrim on viewer-left drum
    private var drumViewerRightHoldSec = 0f // leftcentre / leftrim on viewer-right drum
    private var drumViewerLeftGapSec = 0f
    private var drumViewerRightGapSec = 0f
    private var drumViewerLeftRim = false
    private var drumViewerRightRim = false
    private var drumAlternateSide = false

    // Mouse / camera tracking & swing click pulse
    private var lastFrameNs = 0L
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var virtualMouseFx = 0.5f
    private var virtualMouseFy = 0.5f
    private var smoothMouseFx = 0.5f
    private var smoothMouseFy = 0.5f
    private var clickRecoilY = 0f

    private var wasPlayerSwinging = false
    private var lastSwingAnim = 0f
    private var swingLmbPulseSec = 0f
    private var rmbPulseSec = 0f
    private var prevLmbActive = false
    private var prevRmbActive = false
    private var lastHotbarSlot = -1

    private var dragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    override val tag: String?
        get() = if (NativeBongoCatCore.isNativeLoaded()) mode.tag else "${mode.tag}*"

    override fun onEnabled() {
        session.reset()
        lastFrameNs = System.nanoTime()
        resetTransientState()
        mc.player?.let { p ->
            lastYaw = p.yRot
            lastPitch = p.xRot
            lastHotbarSlot = p.inventory.selectedSlot
        }
    }

    override fun onDisabled() {
        resetTransientState()
    }

    private fun resetTransientState() {
        dragging = false
        mouseDownButtons.clear()
        stdKeyHoldSec.fill(0f)
        stdKeyStampNs.fill(0L)
        stdKeyPrevRaw.fill(false)
        kbKeyHoldSec.fill(0f)
        kbKeyStampNs.fill(0L)
        kbKeyPrevRaw.fill(false)
        stdRestrikeGapSec = 0f
        kbLeftGapSec = 0f
        kbRightGapSec = 0f
        osuPawHoldSec = 0f
        osuPawGapSec = 0f
        drumViewerLeftHoldSec = 0f
        drumViewerRightHoldSec = 0f
        drumViewerLeftGapSec = 0f
        drumViewerRightGapSec = 0f
        virtualMouseFx = 0.5f
        virtualMouseFy = 0.5f
        smoothMouseFx = 0.5f
        smoothMouseFy = 0.5f
        clickRecoilY = 0f
        wasPlayerSwinging = false
        lastSwingAnim = 0f
        swingLmbPulseSec = 0f
        rmbPulseSec = 0f
        prevLmbActive = false
        prevRmbActive = false
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        resetTransientState()
    }

    @Suppress("unused")
    private val attackEntityHandler = handler<AttackEntityEvent> {
        if (reactToSwings) {
            swingLmbPulseSec = 0.060f
        }
    }

    @Suppress("unused")
    private val mouseHandler = handler<MouseButtonEvent> { event ->
        if (event.isPressed) {
            mouseDownButtons.add(event.button)
            if (event.button == InputConstants.MOUSE_BUTTON_LEFT) {
                swingLmbPulseSec = 0.050f
            } else if (event.button == InputConstants.MOUSE_BUTTON_RIGHT) {
                rmbPulseSec = 0.050f
            }
        } else if (event.isReleased) {
            mouseDownButtons.remove(event.button)
            if (event.button == InputConstants.MOUSE_BUTTON_LEFT) {
                dragging = false
            }
        }
    }

    @Suppress("unused")
    private val keyHandler = handler<KeyEvent> { event ->
        if (event.action != InputConstants.PRESS) return@handler
        val now = System.nanoTime()
        val code = event.key.value

        val stdIdx = mapKeyCodeToStandardIndex(code)
        if (stdIdx in 0 until STD_KEY_COUNT) {
            triggerStdKey(stdIdx, now)
        }

        val kbIdx = mapKeyCodeToPureKbIndex(code)
        if (kbIdx in 0 until PURE_KB_KEY_COUNT) {
            triggerPureKbKey(kbIdx, now)
        }

        triggerOsuKeyTap()
        if (isLeftSideKey(code)) {
            triggerDrumHit(viewerLeft = true, highEnergy = (pose[14] + pose[15]) >= 7f)
        } else {
            triggerDrumHit(viewerLeft = false, highEnergy = (pose[14] + pose[15]) >= 7f)
        }
    }

    @Suppress("unused")
    private val overlayHandler = handler<OverlayRenderEvent>(priority = READ_FINAL_STATE) { event ->
        val player = mc.player ?: return@handler
        if (mc.level == null || mc.gui.hud.isHidden || mc.debugOverlay.showDebugScreen()) return@handler

        val screen = mc.gui.screen()
        val inChat = screen is ChatScreen
        if (screen != null && !inChat && !showInScreens) {
            dragging = false
            return@handler
        }

        val atlasSetup = BongoCatSprites.resolveAtlasSetup() ?: return@handler

        val nowNs = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f else ((nowNs - lastFrameNs) * 1.0e-9f).coerceIn(0.0005f, 0.05f)
        lastFrameNs = nowNs

        val ctx = event.context
        val guiW = ctx.guiWidth().toFloat().coerceAtLeast(1f)
        val guiH = ctx.guiHeight().toFloat().coerceAtLeast(1f)
        val boxW = BASE_WIDGET_W * scale
        val boxH = BASE_WIDGET_H * scale

        // Drag repositioning inside ChatScreen
        val winW = mc.window.width.toFloat().coerceAtLeast(1f)
        val winH = mc.window.height.toFloat().coerceAtLeast(1f)
        val rawCursorX = (mc.mouseHandler.xpos().toFloat() / winW).coerceIn(0f, 1f)
        val rawCursorY = (mc.mouseHandler.ypos().toFloat() / winH).coerceIn(0f, 1f)
        val cursorGuiX = rawCursorX * guiW
        val cursorGuiY = rawCursorY * guiH

        var drawX = ((guiW - boxW).coerceAtLeast(0f)) * (posX / 100f)
        var drawY = ((guiH - boxH).coerceAtLeast(0f)) * (posY / 100f)

        if (inChat) {
            val lmb = mouseDownButtons.contains(InputConstants.MOUSE_BUTTON_LEFT)
            val hovered = cursorGuiX in drawX..(drawX + boxW) && cursorGuiY in drawY..(drawY + boxH)
            if (lmb && !dragging && hovered) {
                dragging = true
                dragOffsetX = cursorGuiX - drawX
                dragOffsetY = cursorGuiY - drawY
            }
            if (dragging && lmb) {
                val maxX = (guiW - boxW).coerceAtLeast(1f)
                val maxY = (guiH - boxH).coerceAtLeast(1f)
                drawX = (cursorGuiX - dragOffsetX).coerceIn(0f, maxX)
                drawY = (cursorGuiY - dragOffsetY).coerceIn(0f, maxY)
                posX = ((drawX / maxX) * 100f).coerceIn(0f, 100f)
                posY = ((drawY / maxY) * 100f).coerceIn(0f, 100f)
            }
        } else {
            dragging = false
        }

        // Detect arm swings (e.g. KillAura / AutoClicker / touch tap)
        if (reactToSwings) {
            val swinging = player.isSwinging
            val swingAnim = player.getSwingAnimation(0f)
            val newSwing = (swinging && !wasPlayerSwinging) || (swinging && swingAnim < lastSwingAnim - 0.25f)
            wasPlayerSwinging = swinging
            lastSwingAnim = swingAnim
            if (newSwing) {
                swingLmbPulseSec = 0.055f
            }
        } else {
            wasPlayerSwinging = false
            lastSwingAnim = 0f
        }

        // Detect hotbar slot switches (scroll wheel or PojavLauncher touch bar)
        val curSlot = player.inventory.selectedSlot
        if (lastHotbarSlot != -1 && curSlot != lastHotbarSlot && curSlot in 0..8) {
            triggerStdKey(curSlot.coerceAtMost(6), nowNs)
        }
        lastHotbarSlot = curSlot

        // Decay timers
        swingLmbPulseSec = (swingLmbPulseSec - dt).coerceAtLeast(0f)
        rmbPulseSec = (rmbPulseSec - dt).coerceAtLeast(0f)
        stdRestrikeGapSec = (stdRestrikeGapSec - dt).coerceAtLeast(0f)
        kbLeftGapSec = (kbLeftGapSec - dt).coerceAtLeast(0f)
        kbRightGapSec = (kbRightGapSec - dt).coerceAtLeast(0f)
        osuPawHoldSec = (osuPawHoldSec - dt).coerceAtLeast(0f)
        osuPawGapSec = (osuPawGapSec - dt).coerceAtLeast(0f)
        drumViewerLeftHoldSec = (drumViewerLeftHoldSec - dt).coerceAtLeast(0f)
        drumViewerRightHoldSec = (drumViewerRightHoldSec - dt).coerceAtLeast(0f)
        drumViewerLeftGapSec = (drumViewerLeftGapSec - dt).coerceAtLeast(0f)
        drumViewerRightGapSec = (drumViewerRightGapSec - dt).coerceAtLeast(0f)
        clickRecoilY *= exp(-dt * 26f)

        val opts = mc.options
        val lmbActive = mouseDownButtons.contains(InputConstants.MOUSE_BUTTON_LEFT)
            || opts.keyAttack.isDown
            || swingLmbPulseSec > 0f
        val rmbActive = mouseDownButtons.contains(InputConstants.MOUSE_BUTTON_RIGHT)
            || opts.keyUse.isDown
            || rmbPulseSec > 0f
        val sideMouseActive = mouseDownButtons.contains(InputConstants.MOUSE_BUTTON_MIDDLE)
            || mouseDownButtons.contains(3)
            || mouseDownButtons.contains(4)
            || opts.keyPickItem.isDown

        val lmbRising = lmbActive && !prevLmbActive
        val rmbRising = rmbActive && !prevRmbActive
        prevLmbActive = lmbActive
        prevRmbActive = rmbActive

        if (lmbRising || rmbRising) {
            clickRecoilY = 0.025f
        }

        // Poll live gameplay keys so PojavLauncher on-screen controls & held keys stay illuminated
        updatePolledKeyboardStates(dt, nowNs, screen != null, lmbActive, rmbActive, lmbRising, rmbRising)

        // Update normalized mouse coordinates `(fx, fy)` in `[0, 1]`
        val targetFx: Float
        val targetFy: Float
        if (screen != null) {
            targetFx = if (leftHanded) (1f - rawCursorX) else rawCursorX
            targetFy = rawCursorY
            lastYaw = player.yRot
            lastPitch = player.xRot
        } else {
            var dyaw = player.yRot - lastYaw
            while (dyaw > 180f) dyaw -= 360f
            while (dyaw < -180f) dyaw += 360f
            val dpitch = player.xRot - lastPitch
            lastYaw = player.yRot
            lastPitch = player.xRot

            val sens = mouseSensitivity
            val signedYaw = if (leftHanded) -dyaw else dyaw
            virtualMouseFx = (virtualMouseFx + signedYaw * 0.0115f * sens).coerceIn(0.03f, 0.97f)
            virtualMouseFy = (virtualMouseFy + dpitch * 0.0125f * sens).coerceIn(0.04f, 0.96f)

            // Natural mousepad recentering drift when camera stops turning
            val recenter = 1f - exp(-dt * 2.1f)
            virtualMouseFx += (0.5f - virtualMouseFx) * recenter
            virtualMouseFy += (0.5f - virtualMouseFy) * recenter

            targetFx = virtualMouseFx
            targetFy = (virtualMouseFy + clickRecoilY).coerceIn(0f, 1f)
        }

        val mouseSmooth = 1f - exp(-dt * 30f)
        smoothMouseFx += (targetFx - smoothMouseFx) * mouseSmooth
        smoothMouseFy += (targetFy - smoothMouseFy) * mouseSmooth

        // Step native `libbongocat.so` session for rolling 1.0s CPS & spring state
        var mask = 0
        if (lmbActive) mask = mask or NativeBongoCatCore.IN_LEFT_CLICK
        if (rmbActive) mask = mask or NativeBongoCatCore.IN_RIGHT_CLICK
        if (stdKeyHoldSec[14] > 0f) mask = mask or NativeBongoCatCore.IN_KEY_W
        if (stdKeyHoldSec[11] > 0f) mask = mask or NativeBongoCatCore.IN_KEY_A
        if (stdKeyHoldSec[13] > 0f) mask = mask or NativeBongoCatCore.IN_KEY_S
        if (stdKeyHoldSec[12] > 0f) mask = mask or NativeBongoCatCore.IN_KEY_D
        if (stdKeyHoldSec[10] > 0f) mask = mask or NativeBongoCatCore.IN_KEY_SPACE
        if (opts.keyShift.isDown || opts.keySprint.isDown) mask = mask or NativeBongoCatCore.IN_MODIFIER

        session.step(
            dt = dt,
            inputMask = mask,
            mouseNx = smoothMouseFx * 2f - 1f,
            mouseNy = smoothMouseFy * 2f - 1f,
            outPose = pose,
        )

        val leftCps = pose[14].roundToInt()
        val rightCps = pose[15].roundToInt()
        val activeFace = resolveExpression(player.health + player.absorptionAmount, leftCps + rightCps)

        ctx.pose().withPush {
            translate(drawX, drawY)
            scale(scale, scale)

            if (inChat) {
                UiDraw.fillBox(
                    ctx, 0f, 0f, BASE_WIDGET_W, BASE_WIDGET_H,
                    color = 0x16FFFFFF,
                    radius = 8f,
                    borderColor = if (dragging) 0xFF38BDF8.toInt() else 0x66FFFFFF,
                    borderWidth = 1.1f,
                )
            }

            // Native `612x354` BongoCat canvas block (supports horizontal mirror when LeftHanded)
            ctx.pose().withPush {
                if (leftHanded) {
                    translate(BASE_WIDGET_W, 0f)
                    scale(-CANVAS_TO_WIDGET, CANVAS_TO_WIDGET)
                } else {
                    scale(CANVAS_TO_WIDGET, CANVAS_TO_WIDGET)
                }

                renderBongoCatCanvas(
                    ctx = ctx,
                    setup = atlasSetup,
                    lmbActive = lmbActive,
                    rmbActive = rmbActive,
                    sideActive = sideMouseActive,
                    faceRegion = activeFace,
                )
            }

            if (showCps) {
                renderCpsBadge(ctx, leftCps, rightCps)
            }
        }
    }

    private fun updatePolledKeyboardStates(
        dt: Float,
        nowNs: Long,
        inScreen: Boolean,
        lmbActive: Boolean,
        rmbActive: Boolean,
        lmbRising: Boolean,
        rmbRising: Boolean,
    ) {
        val opts = mc.options

        // 1. Standard 15-key layout (`0..6` = `1..7`, `7`=`Q`, `8`=`E`, `9`=`R`, `10`=`Space`, `11`=`A`, `12`=`D`, `13`=`S`, `14`=`W`)
        for (i in 0 until STD_KEY_COUNT) {
            stdKeyHoldSec[i] = (stdKeyHoldSec[i] - dt).coerceAtLeast(0f)
            val rawDown = if (!inScreen) isStandardKeyDownGameplay(i) else isStandardKeyDownRaw(i)
            if (rawDown && !stdKeyPrevRaw[i]) {
                triggerStdKey(i, nowNs)
            } else if (rawDown) {
                stdKeyHoldSec[i] = maxOf(stdKeyHoldSec[i], 0.016f)
            }
            stdKeyPrevRaw[i] = rawDown
        }

        // 2. PureKeyboard 7-key layout (`0`=`Ctrl`, `1`=`Shift`, `2`=`R/Space`, `3`=`Down/S`, `4`=`Left/A/LMB`, `5`=`Right/D/RMB`, `6`=`Up/W`)
        for (i in 0 until PURE_KB_KEY_COUNT) {
            kbKeyHoldSec[i] = (kbKeyHoldSec[i] - dt).coerceAtLeast(0f)
            val rawDown = when (i) {
                0 -> (!inScreen && opts.keySprint.isDown) || isKeyDownSafe(InputConstants.KEY_LCONTROL) || isKeyDownSafe(InputConstants.KEY_RCONTROL)
                1 -> (!inScreen && opts.keyShift.isDown) || isKeyDownSafe(InputConstants.KEY_LSHIFT) || isKeyDownSafe(InputConstants.KEY_RSHIFT)
                2 -> (!inScreen && opts.keyJump.isDown) || isKeyDownSafe(InputConstants.KEY_SPACE) || isKeyDownSafe(InputConstants.KEY_R)
                3 -> (!inScreen && opts.keyDown.isDown) || isKeyDownSafe(InputConstants.KEY_S) || isKeyDownSafe(InputConstants.KEY_DOWN)
                4 -> (!inScreen && opts.keyLeft.isDown) || isKeyDownSafe(InputConstants.KEY_A) || isKeyDownSafe(InputConstants.KEY_LEFT) || lmbActive
                5 -> (!inScreen && opts.keyRight.isDown) || isKeyDownSafe(InputConstants.KEY_D) || isKeyDownSafe(InputConstants.KEY_RIGHT) || rmbActive
                6 -> (!inScreen && opts.keyUp.isDown) || isKeyDownSafe(InputConstants.KEY_W) || isKeyDownSafe(InputConstants.KEY_UP)
                else -> false
            }
            if (rawDown && !kbKeyPrevRaw[i]) {
                triggerPureKbKey(i, nowNs)
            } else if (rawDown) {
                kbKeyHoldSec[i] = maxOf(kbKeyHoldSec[i], 0.016f)
            }
            kbKeyPrevRaw[i] = rawDown
        }

        // 3. Osu RGB & Bongo Drum mouse-click triggers
        val totalCps = pose[14] + pose[15]
        if (lmbRising) {
            if (mode == InstrumentMode.OSU_RGB && osuPawHoldSec <= 0f) {
                // Keep osu keyboard paw responsive even for touch/mouse-only players if no key is held
            }
            if (totalCps >= 4f && !rmbActive) {
                // Rapid single-button clicking in PvP alternates both bongo drums like a real bongo roll!
                drumAlternateSide = !drumAlternateSide
                triggerDrumHit(viewerLeft = drumAlternateSide, highEnergy = totalCps >= 7f)
            } else {
                triggerDrumHit(viewerLeft = true, highEnergy = totalCps >= 7f)
            }
        }
        if (rmbRising) {
            triggerDrumHit(viewerLeft = false, highEnergy = totalCps >= 7f)
        }
        if (lmbActive && totalCps < 4f && drumViewerLeftGapSec <= 0f && !drumAlternateSide) {
            drumViewerLeftHoldSec = maxOf(drumViewerLeftHoldSec, 0.016f)
        }
        if (rmbActive && drumViewerRightGapSec <= 0f) {
            drumViewerRightHoldSec = maxOf(drumViewerRightHoldSec, 0.016f)
        }
    }

    private fun renderBongoCatCanvas(
        ctx: GuiGraphicsExtractor,
        setup: TextureSetup,
        lmbActive: Boolean,
        rmbActive: Boolean,
        sideActive: Boolean,
        faceRegion: BongoCatSprites.Region?,
    ) {
        val deskAlpha = ((deskOpacity.coerceIn(0, 100) / 100f) * 255f).roundToInt().coerceIn(0, 255)
        val deskArgb = (deskAlpha shl 24) or 0x00FFFFFF
        val pawFillArgb = pawColor.argb
        val pawEdgeArgb = pawOutlineColor.argb

        when (mode) {
            InstrumentMode.STANDARD -> {
                if (deskAlpha > 0) {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.STD_DESK, argb = deskArgb)
                }
                BongoCatSprites.draw(ctx, setup, BongoCatSprites.STD_FG)

                // Solve Kuvster's smooth Bezier mouse arm (`offsetX = +10, offsetY = -10` from Mver config)
                BongoCatBezierArm.solve(smoothMouseFx, smoothMouseFy)
                val mx = BongoCatBezierArm.mouseBaseX + 10f
                val my = BongoCatBezierArm.mouseBaseY - 10f

                BongoCatSprites.draw(ctx, setup, BongoCatSprites.STD_MOUSE, baseX = mx, baseY = my)
                if (lmbActive) {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.STD_MOUSE_LEFT, baseX = mx, baseY = my)
                }
                if (rmbActive) {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.STD_MOUSE_RIGHT, baseX = mx, baseY = my)
                }
                if (sideActive) {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.STD_MOUSE_SIDE, baseX = mx, baseY = my)
                }

                BongoCatBezierArm.draw(ctx, fillArgb = pawFillArgb, edgeArgb = pawEdgeArgb)

                // Draw blush/sweat under raised paw so pink toe-beans stay crisp
                if (faceRegion != null && faceRegion !== BongoCatSprites.FACE_COOL) {
                    BongoCatSprites.draw(ctx, setup, faceRegion)
                }

                // Illuminate ALL currently held keys on the 15-key perspective keyboard
                var latestKey = -1
                var latestStamp = Long.MIN_VALUE
                for (i in 0 until STD_KEY_COUNT) {
                    if (stdKeyHoldSec[i] > 0f) {
                        BongoCatSprites.draw(ctx, setup, BongoCatSprites.STD_KEYS[i])
                        if (stdKeyStampNs[i] >= latestStamp) {
                            latestStamp = stdKeyStampNs[i]
                            latestKey = i
                        }
                    }
                }

                // Left paw: slap down onto most recently pressed active key (with brief lift on re-tap)
                if (latestKey != -1 && stdRestrikeGapSec <= 0f) {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.STD_HANDS[latestKey])
                } else {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.STD_UP)
                }

                if (faceRegion === BongoCatSprites.FACE_COOL) {
                    BongoCatSprites.draw(ctx, setup, faceRegion)
                }
            }

            InstrumentMode.OSU_RGB -> {
                if (deskAlpha > 0) {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.OSU_DESK, argb = deskArgb)
                }
                BongoCatSprites.draw(ctx, setup, BongoCatSprites.OSU_FG)

                BongoCatBezierArm.solve(smoothMouseFx, smoothMouseFy)
                BongoCatSprites.draw(
                    ctx, setup, BongoCatSprites.OSU_MOUSE,
                    baseX = BongoCatBezierArm.mouseBaseX,
                    baseY = BongoCatBezierArm.mouseBaseY,
                )
                BongoCatBezierArm.draw(ctx, fillArgb = pawFillArgb, edgeArgb = pawEdgeArgb)

                if (faceRegion != null && faceRegion !== BongoCatSprites.FACE_COOL) {
                    BongoCatSprites.draw(ctx, setup, faceRegion)
                }

                val anyStdHeld = (0 until STD_KEY_COUNT).any { stdKeyHoldSec[it] > 0f }
                val activeTap = (osuPawHoldSec > 0f || anyStdHeld) && osuPawGapSec <= 0f
                if (activeTap) {
                    val pawSprite = if (osuPawToggle == 0) BongoCatSprites.OSU_LEFT else BongoCatSprites.OSU_RIGHT
                    BongoCatSprites.draw(ctx, setup, pawSprite)
                } else {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.OSU_UP)
                }

                if (faceRegion === BongoCatSprites.FACE_COOL) {
                    BongoCatSprites.draw(ctx, setup, faceRegion)
                }
            }

            InstrumentMode.BONGO_DRUM -> {
                if (deskAlpha > 0) {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.DRUM_DESK, argb = deskArgb)
                }
                BongoCatSprites.draw(ctx, setup, BongoCatSprites.DRUM_FG)

                if (faceRegion != null && faceRegion !== BongoCatSprites.FACE_COOL) {
                    BongoCatSprites.draw(ctx, setup, faceRegion)
                }

                // Viewer-left paw (`DRUM_RIGHT_*`)
                if (drumViewerLeftHoldSec > 0f && drumViewerLeftGapSec <= 0f) {
                    val hit = if (drumViewerLeftRim) BongoCatSprites.DRUM_RIGHT_RIM else BongoCatSprites.DRUM_RIGHT_CENTRE
                    BongoCatSprites.draw(ctx, setup, hit)
                } else {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.DRUM_RIGHT_UP)
                }

                // Viewer-right paw (`DRUM_LEFT_*`)
                if (drumViewerRightHoldSec > 0f && drumViewerRightGapSec <= 0f) {
                    val hit = if (drumViewerRightRim) BongoCatSprites.DRUM_LEFT_RIM else BongoCatSprites.DRUM_LEFT_CENTRE
                    BongoCatSprites.draw(ctx, setup, hit)
                } else {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.DRUM_LEFT_UP)
                }

                if (faceRegion === BongoCatSprites.FACE_COOL) {
                    BongoCatSprites.draw(ctx, setup, faceRegion)
                }
            }

            InstrumentMode.PURE_KEYBOARD -> {
                if (deskAlpha > 0) {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.KB_DESK, argb = deskArgb)
                }
                BongoCatSprites.draw(ctx, setup, BongoCatSprites.KB_FG)

                if (faceRegion != null && faceRegion !== BongoCatSprites.FACE_COOL) {
                    BongoCatSprites.draw(ctx, setup, faceRegion)
                }

                var latestLeft = -1
                var latestLeftNs = Long.MIN_VALUE
                var latestRight = -1
                var latestRightNs = Long.MIN_VALUE

                for (i in 0 until PURE_KB_KEY_COUNT) {
                    if (kbKeyHoldSec[i] > 0f) {
                        BongoCatSprites.draw(ctx, setup, BongoCatSprites.KB_KEYS[i])
                        if (i in 0..2) {
                            if (kbKeyStampNs[i] >= latestLeftNs) {
                                latestLeftNs = kbKeyStampNs[i]
                                latestLeft = i
                            }
                        } else {
                            val rIdx = when (i) {
                                3 -> 0 // Down
                                4 -> 1 // Left
                                5 -> 2 // Right
                                else -> 3 // Up
                            }
                            if (kbKeyStampNs[i] >= latestRightNs) {
                                latestRightNs = kbKeyStampNs[i]
                                latestRight = rIdx
                            }
                        }
                    }
                }

                if (latestRight != -1 && kbRightGapSec <= 0f) {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.KB_RIGHT_HANDS[latestRight])
                } else {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.KB_RIGHT_UP)
                }

                if (latestLeft != -1 && kbLeftGapSec <= 0f) {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.KB_LEFT_HANDS[latestLeft])
                } else {
                    BongoCatSprites.draw(ctx, setup, BongoCatSprites.KB_LEFT_UP)
                }

                if (faceRegion === BongoCatSprites.FACE_COOL) {
                    BongoCatSprites.draw(ctx, setup, faceRegion)
                }
            }
        }
    }

    private fun renderCpsBadge(ctx: GuiGraphicsExtractor, leftCps: Int, rightCps: Int) {
        val cpsText = "$leftCps | $rightCps CPS"
        val tw = UiDraw.measureText(cpsText, 8.5f, UiFontWeight.SEMIBOLD)
        val pillW = tw + 11f
        val pillH = 13.5f
        val pillX = if (leftHanded) 4f else (BASE_WIDGET_W - pillW - 4f)
        val pillY = 4f

        UiDraw.fillBox(
            ctx, pillX, pillY, pillW, pillH,
            color = 0xB8181A24.toInt(),
            radius = pillH * 0.5f,
            borderColor = UiPalette.withAlphaFactor(0xFF38BDF8.toInt(), 0.55f),
            borderWidth = 0.9f,
        )
        val lh = UiDraw.lineHeight(8.5f, UiFontWeight.SEMIBOLD)
        UiDraw.drawText(
            ctx = ctx,
            text = cpsText,
            x = pillX + 5.5f,
            y = pillY + (pillH - lh) * 0.5f,
            fontSizePx = 8.5f,
            argb = 0xFFF8FAFC.toInt(),
            weight = UiFontWeight.SEMIBOLD,
        )
    }

    private fun resolveExpression(effectiveHp: Float, totalCps: Int): BongoCatSprites.Region? =
        when (expression) {
            ExpressionMode.DEFAULT_FACE -> null
            ExpressionMode.BLUSH -> BongoCatSprites.FACE_BLUSH
            ExpressionMode.COOL_SHADES -> BongoCatSprites.FACE_COOL
            ExpressionMode.SWEAT -> BongoCatSprites.FACE_SWEAT
            ExpressionMode.AUTO -> when {
                effectiveHp in 0.01f..6.5f -> BongoCatSprites.FACE_SWEAT
                totalCps >= 12 -> BongoCatSprites.FACE_COOL
                totalCps >= 6 -> BongoCatSprites.FACE_BLUSH
                else -> null
            }
        }

    private fun triggerStdKey(idx: Int, nowNs: Long) {
        if (idx !in 0 until STD_KEY_COUNT) return
        if (stdKeyHoldSec[idx] > 0f) {
            stdRestrikeGapSec = RESTRIKE_LIFT_SEC
        }
        stdKeyHoldSec[idx] = KEY_MIN_HOLD_SEC
        stdKeyStampNs[idx] = nowNs
    }

    private fun triggerPureKbKey(idx: Int, nowNs: Long) {
        if (idx !in 0 until PURE_KB_KEY_COUNT) return
        if (idx in 0..2 && kbKeyHoldSec[idx] > 0f) {
            kbLeftGapSec = RESTRIKE_LIFT_SEC
        } else if (idx in 3..6 && kbKeyHoldSec[idx] > 0f) {
            kbRightGapSec = RESTRIKE_LIFT_SEC
        }
        kbKeyHoldSec[idx] = KEY_MIN_HOLD_SEC
        kbKeyStampNs[idx] = nowNs
    }

    private fun triggerOsuKeyTap() {
        if (osuPawHoldSec > 0f) {
            osuPawGapSec = RESTRIKE_LIFT_SEC
        }
        osuPawToggle = osuPawToggle xor 1
        osuPawHoldSec = KEY_MIN_HOLD_SEC
    }

    private fun triggerDrumHit(viewerLeft: Boolean, highEnergy: Boolean) {
        if (viewerLeft) {
            if (drumViewerLeftHoldSec > 0f) {
                drumViewerLeftGapSec = RESTRIKE_LIFT_SEC
            }
            drumViewerLeftRim = if (highEnergy) !drumViewerLeftRim else false
            drumViewerLeftHoldSec = 0.062f
        } else {
            if (drumViewerRightHoldSec > 0f) {
                drumViewerRightGapSec = RESTRIKE_LIFT_SEC
            }
            drumViewerRightRim = if (highEnergy) !drumViewerRightRim else false
            drumViewerRightHoldSec = 0.062f
        }
    }

    private fun isStandardKeyDownGameplay(idx: Int): Boolean {
        val opts = mc.options
        return when (idx) {
            0 -> opts.keyHotbarSlots.getOrNull(0)?.isDown == true || isKeyDownSafe(InputConstants.KEY_1)
            1 -> opts.keyHotbarSlots.getOrNull(1)?.isDown == true || isKeyDownSafe(InputConstants.KEY_2)
            2 -> opts.keyHotbarSlots.getOrNull(2)?.isDown == true || isKeyDownSafe(InputConstants.KEY_3)
            3 -> opts.keyHotbarSlots.getOrNull(3)?.isDown == true || isKeyDownSafe(InputConstants.KEY_4)
            4 -> opts.keyHotbarSlots.getOrNull(4)?.isDown == true || isKeyDownSafe(InputConstants.KEY_5)
            5 -> opts.keyHotbarSlots.getOrNull(5)?.isDown == true || isKeyDownSafe(InputConstants.KEY_6)
            6 -> opts.keyHotbarSlots.getOrNull(6)?.isDown == true
                || opts.keyHotbarSlots.getOrNull(7)?.isDown == true
                || opts.keyHotbarSlots.getOrNull(8)?.isDown == true
                || isKeyDownSafe(InputConstants.KEY_7)
                || isKeyDownSafe(InputConstants.KEY_8)
                || isKeyDownSafe(InputConstants.KEY_9)
            7 -> opts.keyDrop.isDown || isKeyDownSafe(InputConstants.KEY_Q)
            8 -> opts.keyInventory.isDown || isKeyDownSafe(InputConstants.KEY_E)
            9 -> opts.keySprint.isDown || isKeyDownSafe(InputConstants.KEY_R)
            10 -> opts.keyJump.isDown || isKeyDownSafe(InputConstants.KEY_SPACE)
            11 -> opts.keyLeft.isDown || isKeyDownSafe(InputConstants.KEY_A)
            12 -> opts.keyRight.isDown || isKeyDownSafe(InputConstants.KEY_D)
            13 -> opts.keyDown.isDown || isKeyDownSafe(InputConstants.KEY_S)
            14 -> opts.keyUp.isDown || isKeyDownSafe(InputConstants.KEY_W)
            else -> false
        }
    }

    private fun isStandardKeyDownRaw(idx: Int): Boolean = when (idx) {
        0 -> isKeyDownSafe(InputConstants.KEY_1)
        1 -> isKeyDownSafe(InputConstants.KEY_2)
        2 -> isKeyDownSafe(InputConstants.KEY_3)
        3 -> isKeyDownSafe(InputConstants.KEY_4)
        4 -> isKeyDownSafe(InputConstants.KEY_5)
        5 -> isKeyDownSafe(InputConstants.KEY_6)
        6 -> isKeyDownSafe(InputConstants.KEY_7) || isKeyDownSafe(InputConstants.KEY_8) || isKeyDownSafe(InputConstants.KEY_9)
        7 -> isKeyDownSafe(InputConstants.KEY_Q)
        8 -> isKeyDownSafe(InputConstants.KEY_E)
        9 -> isKeyDownSafe(InputConstants.KEY_R)
        10 -> isKeyDownSafe(InputConstants.KEY_SPACE)
        11 -> isKeyDownSafe(InputConstants.KEY_A)
        12 -> isKeyDownSafe(InputConstants.KEY_D)
        13 -> isKeyDownSafe(InputConstants.KEY_S)
        14 -> isKeyDownSafe(InputConstants.KEY_W)
        else -> false
    }

    private fun isKeyDownSafe(keyCode: Int): Boolean =
        keyCode in 1..511 && runCatching { InputConstants.isKeyDown(keyCode) }.getOrDefault(false)

    private fun mapKeyCodeToStandardIndex(code: Int): Int = when (code) {
        InputConstants.KEY_1 -> 0
        InputConstants.KEY_2 -> 1
        InputConstants.KEY_3 -> 2
        InputConstants.KEY_4 -> 3
        InputConstants.KEY_5 -> 4
        InputConstants.KEY_6 -> 5
        InputConstants.KEY_7, InputConstants.KEY_8, InputConstants.KEY_9, InputConstants.KEY_0 -> 6
        InputConstants.KEY_Q, InputConstants.KEY_TAB -> 7
        InputConstants.KEY_E -> 8
        InputConstants.KEY_R, InputConstants.KEY_T, InputConstants.KEY_Y,
        InputConstants.KEY_U, InputConstants.KEY_I, InputConstants.KEY_O, InputConstants.KEY_P -> 9
        InputConstants.KEY_SPACE, InputConstants.KEY_RETURN, InputConstants.KEY_BACKSPACE -> 10
        InputConstants.KEY_A, InputConstants.KEY_CAPSLOCK, InputConstants.KEY_LSHIFT -> 11
        InputConstants.KEY_D, InputConstants.KEY_F, InputConstants.KEY_G, InputConstants.KEY_H,
        InputConstants.KEY_J, InputConstants.KEY_K, InputConstants.KEY_L,
        InputConstants.KEY_C, InputConstants.KEY_V, InputConstants.KEY_B, InputConstants.KEY_N, InputConstants.KEY_M -> 12
        InputConstants.KEY_S, InputConstants.KEY_Z, InputConstants.KEY_X -> 13
        InputConstants.KEY_W -> 14
        else -> -1
    }

    private fun mapKeyCodeToPureKbIndex(code: Int): Int = when (code) {
        InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL -> 0
        InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT -> 1
        InputConstants.KEY_SPACE, InputConstants.KEY_R, InputConstants.KEY_RETURN -> 2
        InputConstants.KEY_S, InputConstants.KEY_DOWN -> 3
        InputConstants.KEY_A, InputConstants.KEY_LEFT, InputConstants.KEY_Q -> 4
        InputConstants.KEY_D, InputConstants.KEY_RIGHT, InputConstants.KEY_E -> 5
        InputConstants.KEY_W, InputConstants.KEY_UP -> 6
        else -> -1
    }

    private fun isLeftSideKey(code: Int): Boolean = when (code) {
        InputConstants.KEY_Q, InputConstants.KEY_W, InputConstants.KEY_E,
        InputConstants.KEY_A, InputConstants.KEY_S, InputConstants.KEY_Z, InputConstants.KEY_X,
        InputConstants.KEY_1, InputConstants.KEY_2, InputConstants.KEY_3, InputConstants.KEY_4,
        InputConstants.KEY_TAB, InputConstants.KEY_LSHIFT, InputConstants.KEY_LCONTROL, InputConstants.KEY_LEFT -> true
        else -> false
    }
}
