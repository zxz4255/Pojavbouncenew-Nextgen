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
package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.additions.realSelectedSlot
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerMoveEvent
import net.ccbluex.liquidbounce.event.events.PlayerNetworkMovementTickEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.isOlderThanOrEqual1_8
import net.ccbluex.liquidbounce.utils.client.usesViaFabricPlus
import net.ccbluex.liquidbounce.utils.client.vfp.VfpCompatibility1_8
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.ccbluex.liquidbounce.utils.render.Ts2VisualUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import kotlin.math.sin

/**
 * 1:1 port of ts2 `Gapple` (`object Gapple : Module("AutoGapple", Category.PLAYER)`),
 * emitting raw 1.8 C09/C08/C05/C03 packets via ViaFabricPlus on 1.8 targets and cross-version
 * offhand+status bursts on >= 1.9 targets, with 100% restored visual HUD progress card.
 */
@Suppress("TooManyFunctions", "MagicNumber")
object ModuleGapple : ClientModule(
    "AutoGapple",
    ModuleCategories.PLAYER,
    aliases = listOf("Gapple", "BlinkGapple"),
    disableOnQuit = true,
) {

    enum class AccentColorMode(override val tag: String) : Tagged {
        CUSTOM("Custom"),
        RAINBOW("Rainbow"),
        THEME("Theme"),
        SKY("Sky"),
        MIXER("Mixer"),
    }

    enum class BarColorChoice(override val tag: String) : Tagged {
        FOLLOW_ACCENT("FollowAccent"),
        CUSTOM("Custom"),
        FADE("Fade"),
        RAINBOW("Rainbow"),
        THEME("Theme"),
        SKY("Sky"),
        MIXER("Mixer"),
    }

    enum class TextColorChoice(override val tag: String) : Tagged {
        CUSTOM("Custom"),
        WHITE("White"),
        THEME("Theme"),
        FOLLOW_ACCENT("FollowAccent"),
    }

    enum class ShadowColorChoice(override val tag: String) : Tagged {
        CUSTOM("Custom"),
        THEME("Theme"),
        RAINBOW("Rainbow"),
        FADE("Fade"),
    }

    private val c by int("C03PacketPlayer", 32, 32..40)
    private val progressBar2 by boolean("ProgressBar", true)
    private val chatMessage by boolean("ChatMessage", true)

    private val sizeScale by float("SizeScale", 1f, 0.5f..3f)
    private val blur by boolean("Blur", true)
    private val blurStrength by float("BlurStrength", 15f, 5f..30f)

    private val colorMode by enumChoice("ColorMode", AccentColorMode.CUSTOM)
    private val customColor by color("AccentColor", Color4b(65, 130, 225, 255))
    private val rainbowOffset by float("RainbowOffset", 0f, -2000f..2000f)
    private val skySaturation by float("SkySaturation", 0.9f, 0f..1f)
    private val skyBrightness by float("SkyBrightness", 1f, 0f..1f)
    private val skySpeed by float("SkySpeed", 1f, 0.1f..5f)
    private val mixerSeconds by int("MixerSeconds", 2, 1..10)
    private val mixerColor2 by color("MixerColor2", Color4b(145, 90, 255, 255))

    private val barColorMode by enumChoice("BarColorMode", BarColorChoice.FADE)
    private val barCustomColor by color("BarColor", Color4b(76, 200, 120, 255))
    private val barFadeColor by color("BarFadeColor", Color4b(76, 200, 120, 255))
    private val barFadeDistance by int("BarFadeDistance", 50, 0..100)
    private val barRainbowOffset by float("BarRainbowOffset", 500f, -2000f..2000f)
    private val barSkySaturation by float("BarSkySaturation", 0.9f, 0f..1f)
    private val barSkyBrightness by float("BarSkyBrightness", 1f, 0f..1f)
    private val barSkySpeed by float("BarSkySpeed", 1f, 0.1f..5f)
    private val barMixerSeconds by int("BarMixerSeconds", 2, 1..10)

    private val textColorMode by enumChoice("TextColorMode", TextColorChoice.WHITE)
    private val textCustomColor by color("TextColor", Color4b.WHITE)

    private val titleBarColor by color("TitleBarColor", Color4b(0, 0, 0, 180))
    private val titleBarHeight by float("TitleBarHeight", 14f, 10f..24f)
    private val dividerColor by color("DividerColor", Color4b(255, 255, 255, 30))
    private val dividerHeight by float("DividerHeight", 1f, 0.5f..3f)

    private val backgroundAlpha by int("BackgroundAlpha", 140, 0..255)
    private val bgRadius by float("BgRadius", 6f, 0f..15f)

    private val shadowCheck by boolean("Shadow", false)
    private val shadowRadius by float("ShadowRadius", 8f, 1f..20f)
    private val shadowColorMode by enumChoice("ShadowColorMode", ShadowColorChoice.CUSTOM)
    private val shadowColorSetting by color("ShadowColor", Color4b(0, 0, 0, 100))
    private val shadowColor2 by color("ShadowColor2", Color4b(50, 50, 50, 80))

    private val barWidth by float("BarWidth", 140f, 80f..250f)
    private val barHeight by float("BarHeight", 6f, 2f..12f)
    private val barRadius by float("BarRadius", 3f, 0f..8f)
    private val barAnimSpeed by float("BarAnimSpeed", 0.2f, 0.02f..0.5f)

    private var px = 0.0
    private var py = 0.0
    private var pz = 0.0
    @Volatile
    private var cancelMove = false
    private var stuckFlag = false

    var ticks = 0
        private set
    private var pauseTicks = 0
    private var yaw = 0f
    private var pitch = 0f

    @Volatile
    var isEating = false
        private set

    @Volatile
    var isSwitching = false
        private set

    @Volatile
    private var isReleasing = false

    private var slot = -1
    private var smoothProgress = 0f

    override val tag: String
        get() = "$ticks/$c"

    fun shouldCancelTravel(): Boolean = running && cancelMove

    fun getEatingProgress(): Float =
        if (isEating && c > 0) (ticks.toFloat() / c.toFloat()).coerceIn(0f, 1f) else 0f

    private fun getAccentColor(): Color4b = when (colorMode) {
        AccentColorMode.CUSTOM -> customColor
        AccentColorMode.RAINBOW -> Ts2VisualUtils.rainbow(
            System.currentTimeMillis() % 10000L + (rainbowOffset * 1000f).toLong()
        )
        AccentColorMode.THEME -> Ts2VisualUtils.themeColor(0)
        AccentColorMode.SKY -> Ts2VisualUtils.skyRainbow(0, skySaturation, skyBrightness, skySpeed)
        AccentColorMode.MIXER -> Ts2VisualUtils.mixerColor(customColor, mixerColor2, 0, mixerSeconds)
    }

    private fun getBarColor(progress: Float): Color4b = when (barColorMode) {
        BarColorChoice.FOLLOW_ACCENT -> getAccentColor()
        BarColorChoice.CUSTOM -> barCustomColor
        BarColorChoice.FADE -> Ts2VisualUtils.fade(barFadeColor, (progress * barFadeDistance).toInt(), 100)
        BarColorChoice.RAINBOW -> Ts2VisualUtils.rainbow(
            System.currentTimeMillis() % 10000L + (barRainbowOffset * 1000f).toLong()
        )
        BarColorChoice.THEME -> Ts2VisualUtils.themeColor(2)
        BarColorChoice.SKY -> Ts2VisualUtils.skyRainbow(
            (progress * 10f).toInt(), barSkySaturation, barSkyBrightness, barSkySpeed
        )
        BarColorChoice.MIXER -> Ts2VisualUtils.mixerColor(
            barCustomColor, mixerColor2, (progress * 10f).toInt(), barMixerSeconds
        )
    }

    private fun getTextColor(): Color4b = when (textColorMode) {
        TextColorChoice.CUSTOM -> textCustomColor
        TextColorChoice.WHITE -> Color4b.WHITE
        TextColorChoice.THEME -> Ts2VisualUtils.themeColor(1)
        TextColorChoice.FOLLOW_ACCENT -> getAccentColor()
    }

    private fun computeShadowColor(): Color4b = when (shadowColorMode) {
        ShadowColorChoice.THEME -> Ts2VisualUtils.themeColor(0).with(a = shadowColorSetting.a)
        ShadowColorChoice.RAINBOW -> Ts2VisualUtils.rainbow(System.currentTimeMillis() % 10000L, 0.8f)
        ShadowColorChoice.FADE -> {
            val fadeVal = ((sin(System.currentTimeMillis() / 1000.0 * 0.5) + 1.0) * 0.5).toFloat()
            Ts2VisualUtils.blendColors(shadowColorSetting, shadowColor2, fadeVal)
        }
        ShadowColorChoice.CUSTOM -> shadowColorSetting
    }

    override fun onEnabled() {
        isEating = false
        ticks = 0
        pauseTicks = 0
        smoothProgress = 0f
        isSwitching = false
        isReleasing = false
        stopStuck()

        val p = mc.player
        if (p == null) {
            enabled = false
            return
        }

        yaw = p.yRot
        pitch = p.xRot
        slot = getGApple()
        if (slot < 0) {
            if (chatMessage) {
                chat("宝宝，你好像没有苹果了耶！")
            }
            enabled = false
            return
        }
        isEating = true
    }

    override fun onDisabled() {
        ticks = 0
        pauseTicks = 0
        isEating = false
        smoothProgress = 0f
        isSwitching = false
        isReleasing = false
        stopStuck()
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        stopStuck()
        ticks = 0
        pauseTicks = 0
        isEating = false
        isSwitching = false
        isReleasing = false
        enabled = false
    }

    /**
     * Cancels local movement in `LocalPlayer.move` while stuck, matching 1.8 `MoveEvent.cancelEvent()`.
     */
    @Suppress("unused")
    private val onMove = handler<PlayerMoveEvent> { event ->
        if (cancelMove) {
            event.movement = Vec3.ZERO
        }
    }

    /**
     * Cancels `LocalPlayer.sendPosition()` once per tick while accumulating `c` C03 ticks,
     * guaranteeing exact 20-TPS tick counting even when standing motionless on MC 26.3.
     */
    @Suppress("unused")
    private val onMotionTick = handler<PlayerNetworkMovementTickEvent> { event ->
        if (event.state != EventState.PRE) return@handler
        if (cancelMove && ticks < c) {
            val p = mc.player ?: return@handler
            val rot = RotationManager.currentRotation
            yaw = rot?.yaw ?: p.yRot
            pitch = rot?.pitch ?: p.xRot
            ticks++
            event.cancelEvent()
        }
    }

    /**
     * Also intercepts any direct outgoing `ServerboundMovePlayerPacket` emitted outside `sendPosition()`
     * while stuck, capturing its look angles just like ts2's `C05PacketPlayerLook` check.
     */
    @Suppress("unused")
    private val onPacket = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.OUTGOING || event.isCancelled || isReleasing) return@handler
        val pkt = event.packet
        if (pkt !is ServerboundMovePlayerPacket) return@handler
        if (cancelMove && ticks < c) {
            if (pkt.hasRotation()) {
                yaw = pkt.getYRot(yaw)
                pitch = pkt.getXRot(pitch)
            }
            event.cancelEvent()
        }
    }

    @Suppress("unused")
    private val onTick = handler<GameTickEvent> {
        val p = mc.player ?: run {
            enabled = false
            return@handler
        }

        slot = getGApple()
        if (slot < 0) {
            enabled = false
            return@handler
        }

        if (pauseTicks == 0) {
            doStuck()
        } else {
            stopStuck()
            pauseTicks--
        }

        if (ticks >= c) {
            isSwitching = true
            try {
                val originalSlot = p.inventory.realSelectedSlot.coerceIn(0, 8)
                val appleStack = p.inventory.getItem(slot)
                val originalStack = p.inventory.getItem(originalSlot)

                if (usesViaFabricPlus && isOlderThanOrEqual1_8 && VfpCompatibility1_8.INSTANCE.is1_8ConnectionActive()) {
                    // Pure 1.8 wire burst: C09(slot) -> C08(apple) -> C05(yaw,pitch,ground)
                    // -> ticks * C03(ground) -> stopStuck -> C09(orig) -> 2 * C08(orig)
                    VfpCompatibility1_8.INSTANCE.sendHeldItemChangeSafe(slot)
                    VfpCompatibility1_8.INSTANCE.sendUseItemInAir(appleStack)

                    release1_8(p.onGround())
                    stopStuck()

                    VfpCompatibility1_8.INSTANCE.sendHeldItemChangeSafe(originalSlot)
                    VfpCompatibility1_8.INSTANCE.sendUseItemInAir(originalStack)
                    VfpCompatibility1_8.INSTANCE.sendUseItemInAir(originalStack)
                } else {
                    // >= 1.9 cross-version burst (matches ts2 + ViaLoadingBase + ViaPacket.sendOffHandUseItem())
                    sendPacketSilently(ServerboundSetCarriedItemPacket(slot))
                    sendSequencedUseItem(InteractionHand.MAIN_HAND)
                    sendSequencedUseItem(InteractionHand.OFF_HAND)

                    releaseModern(p.onGround(), p.horizontalCollision)
                    stopStuck()

                    sendPacketSilently(ServerboundSetCarriedItemPacket(originalSlot))
                    sendSequencedUseItem(InteractionHand.MAIN_HAND)
                    sendSequencedUseItem(InteractionHand.MAIN_HAND)
                }
            } finally {
                pauseTicks++
                ticks = 0
                isSwitching = false
            }
        }
    }

    private fun sendSequencedUseItem(hand: InteractionHand) {
        val level = mc.level
        val gameMode = mc.gameMode
        if (level != null && gameMode != null) {
            gameMode.startPrediction(level) { sequence ->
                ServerboundUseItemPacket(hand, sequence, yaw, pitch)
            }
        } else {
            sendPacketSilently(ServerboundUseItemPacket(hand, 0, yaw, pitch))
        }
    }

    private fun release1_8(onGround: Boolean) {
        VfpCompatibility1_8.INSTANCE.sendMovePlayerRot(yaw, pitch, onGround)
        for (i in 1..ticks) {
            VfpCompatibility1_8.INSTANCE.sendMoveStatusOnly(onGround)
        }
    }

    private fun releaseModern(onGround: Boolean, horizontalCollision: Boolean) {
        isReleasing = true
        try {
            sendPacketSilently(ServerboundMovePlayerPacket.Rot(yaw, pitch, onGround, horizontalCollision))
            for (i in 1..ticks) {
                sendPacketSilently(ServerboundMovePlayerPacket.StatusOnly(onGround, horizontalCollision))
            }
        } finally {
            isReleasing = false
        }
    }

    private fun doStuck() {
        val p = mc.player ?: return
        if (!stuckFlag) {
            val vel = p.deltaMovement
            px = vel.x
            py = vel.y
            pz = vel.z
            stuckFlag = true
        }
        p.setDeltaMovement(0.0, 0.0, 0.0)
        cancelMove = true
    }

    private fun stopStuck() {
        cancelMove = false
        val p = mc.player
        if (stuckFlag) {
            p?.setDeltaMovement(px, py, pz)
            stuckFlag = false
        }
    }

    private fun getGApple(): Int {
        val inv = mc.player?.inventory ?: return -1
        for (i in 0..8) {
            val stack = inv.getItem(i)
            if (stack.isGoldenApple()) return i
        }
        return -1
    }

    private fun ItemStack.isGoldenApple(): Boolean =
        !isEmpty && (`is`(Items.GOLDEN_APPLE) || `is`(Items.ENCHANTED_GOLDEN_APPLE))

    @Suppress("unused")
    private val onRender2D = handler<OverlayRenderEvent> { event ->
        if (isEating && progressBar2) {
            val ctx = event.context
            drawProgressBar(ctx, ctx.guiWidth().toFloat(), ctx.guiHeight().toFloat())
        }
    }

    private fun drawProgressBar(ctx: GuiGraphicsExtractor, sw: Float, sh: Float) {
        val progress = getEatingProgress()
        val accentColor = getAccentColor()
        val barColor = getBarColor(progress)
        val textColor = getTextColor()
        val effectiveAlpha = if (blur) {
            backgroundAlpha.coerceIn((blurStrength * 3f).toInt().coerceAtMost(200), 255)
        } else {
            backgroundAlpha
        }
        val bgColor = Color4b(0, 0, 0, effectiveAlpha)
        val shadowColorFinal = computeShadowColor()

        smoothProgress = Ts2VisualUtils.animBase(smoothProgress, progress, barAnimSpeed)
        val dp = smoothProgress.coerceIn(0f, 1f)

        val totalW = barWidth
        val totalH = titleBarHeight + dividerHeight + barHeight + 8f
        val sx = sw / 2f - totalW / 2f
        val sy = sh * 0.75f
        val cx = sx + totalW / 2f
        val cy = sy + totalH / 2f

        ctx.pose().withPush {
            translate(cx, cy)
            scale(sizeScale, sizeScale)
            translate(-cx, -cy)

            // Outer drop shadow
            if (shadowCheck) {
                Ts2VisualUtils.drawOuterShadow(
                    ctx, sx, sy, totalW, totalH, bgRadius, shadowRadius, shadowColorFinal
                )
            }

            // Frosted card background (participates in HUD Gaussian blur pass when semi-transparent)
            UiDraw.fillBox(ctx, sx, sy, totalW, totalH, bgColor.argb, radius = bgRadius)

            // Top-rounded title bar
            Ts2VisualUtils.drawTopRoundedRect(
                ctx, sx, sy, totalW, titleBarHeight, bgRadius, titleBarColor.argb
            )

            // Title text: "Eating Gapple - XX%"
            val percent = (dp * 100f).toInt()
            val titleText = "Eating Gapple - ${percent}%"
            val fontSize = 8.5f
            val lh = UiDraw.lineHeight(fontSize, UiFontWeight.SEMIBOLD)
            val titleColorArgb = if (textColorMode == TextColorChoice.WHITE) accentColor.argb else textColor.argb
            UiDraw.drawText(
                ctx = ctx,
                text = titleText,
                x = sx + 8f,
                y = sy + (titleBarHeight - lh) * 0.5f,
                fontSizePx = fontSize,
                argb = titleColorArgb,
                weight = UiFontWeight.SEMIBOLD,
                shadow = false,
            )

            // Divider line
            val tb = sy + titleBarHeight
            UiDraw.fillBox(ctx, sx, tb, totalW, dividerHeight, dividerColor.argb, radius = 0f)

            // Progress track & animated fill bar
            val bx = sx + 4f
            val by = tb + dividerHeight + 4f
            val bw = totalW - 8f
            val cw = bw * dp

            UiDraw.fillBox(ctx, bx, by, bw, barHeight, Color4b(0, 0, 0, 200).argb, radius = barRadius)
            if (cw > 0.5f) {
                UiDraw.fillBox(
                    ctx, bx, by, cw.coerceAtMost(bw), barHeight, barColor.argb, radius = barRadius.coerceAtMost(cw * 0.5f)
                )
            }
        }
    }
}
