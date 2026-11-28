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

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.utils.block.anotherChestPartDirection
import net.ccbluex.liquidbounce.utils.block.state
import net.ccbluex.liquidbounce.utils.math.toVec3d
import net.ccbluex.liquidbounce.utils.render.Ts2VisualUtils
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.EnderChestBlock
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 1:1 port of ts2 `ChestView` (`render/ChestView.kt`), projecting a floating frosted 3D->2D
 * chest card with `easeOutBack` open/close zoom, smooth camera sway, glow, shadow, title bar,
 * and expanding wave ripples when items are stolen.
 */
@Suppress("MagicNumber", "TooManyFunctions")
object ModuleChestView : ClientModule("ChestView", ModuleCategories.RENDER) {

    enum class ColorChoice(override val tag: String) : Tagged {
        CUSTOM("Custom"),
        RAINBOW("Rainbow"),
        THEME("Theme"),
        SKY("Sky"),
        MIXER("Mixer"),
    }

    enum class GlowModeChoice(override val tag: String) : Tagged {
        BACKGROUND("Background"),
        BOTH("Both"),
    }

    enum class GlowColorChoice(override val tag: String) : Tagged {
        CUSTOM("Custom"),
        FOLLOW_ACCENT("FollowAccent"),
        RAINBOW("Rainbow"),
        THEME("Theme"),
    }

    enum class TitleColorChoice(override val tag: String) : Tagged {
        ACCENT("Accent"),
        WHITE("White"),
        CUSTOM("Custom"),
    }

    private val colorMode by enumChoice("ColorMode", ColorChoice.THEME)
    private val customColor by color("AccentColor", Color4b(65, 130, 225, 255))
    private val rainbowOffset by float("RainbowOffset", 0f, -2000f..2000f)
    private val skySaturation by float("SkySaturation", 0.9f, 0f..1f)
    private val skyBrightness by float("SkyBrightness", 1f, 0f..1f)
    private val skySpeed by float("SkySpeed", 1f, 0.1f..5f)
    private val mixerSeconds by int("MixerSeconds", 2, 1..10)
    private val mixerColor2 by color("MixerColor2", Color4b(145, 90, 255, 255))

    private val backgroundAlpha by int("BackgroundAlpha", 140, 0..255)
    private val bgRadius by float("BgRadius", 6f, 0f..15f)
    private val useBlur by boolean("UseBlur", true)
    private val blurStrength by float("BlurStrength", 15f, 5f..30f)

    private val shadowCheck by boolean("Shadow", true)
    private val shadowRadius by float("ShadowRadius", 8f, 1f..20f)
    private val shadowColor by color("ShadowColor", Color4b(0, 0, 0, 100))

    private val enableGlow by boolean("EnableGlow", false)
    private val glowMode by enumChoice("GlowMode", GlowModeChoice.BOTH)
    private val glowColorMode by enumChoice("GlowColorMode", GlowColorChoice.FOLLOW_ACCENT)
    private val glowCustomColor by color("GlowColor", Color4b(0, 111, 255, 100))
    private val glowRainbowOffset by float("GlowRainbowOffset", 0f, -2000f..2000f)
    private val glowBlurRadius by int("GlowBlurRadius", 10, 1..30)
    private val glowStrength by float("GlowStrength", 1f, 0.1f..5f)

    private val showTitle by boolean("ShowTitle", true)
    private val titleBarColor by color("TitleBarColor", Color4b(0, 0, 0, 180))
    private val titleBarHeight by float("TitleBarHeight", 14f, 10f..24f)
    private val dividerColor by color("DividerColor", Color4b(255, 255, 255, 30))
    private val dividerHeight by float("DividerHeight", 1f, 0.5f..3f)
    private val titleColorMode by enumChoice("TitleColor", TitleColorChoice.ACCENT)
    private val titleCustomColor by color("TitleCustomColor", Color4b.WHITE)
    private val titleFontSize by float("TitleFontSize", 1f, 0.5f..2f)

    private var currentContainerPos: BlockPos? = null
    private var viewScale = 0.0f
    private var targetScale = 0.0f
    private var animationStartTime = 0L
    private var wasChestOpen = false

    private val clickAnimations = HashMap<Int, ClickAnimation>()
    private val lastItems = HashMap<Int, Boolean>()
    private var lastRows = 3
    private var smoothX = 0.0f
    private var smoothZ = 0.0f

    private const val ANIMATION_DURATION = 500f
    private const val CAMERA_SMOOTH_SPEED = 0.15f

    private class ClickAnimation {
        val startTime = System.currentTimeMillis()
        val alpha: Float
            get() {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= DURATION) return 0f
                return 1f - elapsed.toFloat() / DURATION.toFloat()
            }

        fun isFinished(): Boolean = System.currentTimeMillis() - startTime >= DURATION

        companion object {
            private const val DURATION = 600L
        }
    }

    override fun onDisabled() {
        currentContainerPos = null
        viewScale = 0f
        targetScale = 0f
        wasChestOpen = false
        clickAnimations.clear()
        lastItems.clear()
    }

    @Suppress("unused")
    private val onWorld = handler<WorldChangeEvent> {
        onDisabled()
    }

    private fun getAccentColor(): Color4b = when (colorMode) {
        ColorChoice.CUSTOM -> customColor
        ColorChoice.RAINBOW -> Ts2VisualUtils.rainbow(
            System.currentTimeMillis() % 10000L + (rainbowOffset * 1000f).toLong()
        )
        ColorChoice.THEME -> Ts2VisualUtils.themeColor(0)
        ColorChoice.SKY -> Ts2VisualUtils.skyRainbow(0, skySaturation, skyBrightness, skySpeed)
        ColorChoice.MIXER -> Ts2VisualUtils.mixerColor(customColor, mixerColor2, 0, mixerSeconds)
    }

    private fun getTitleColor(): Color4b = when (titleColorMode) {
        TitleColorChoice.WHITE -> Color4b.WHITE
        TitleColorChoice.CUSTOM -> titleCustomColor
        TitleColorChoice.ACCENT -> getAccentColor()
    }

    private fun getGlowColor(baseColor: Color4b): Color4b {
        val raw = when (glowColorMode) {
            GlowColorChoice.CUSTOM -> glowCustomColor
            GlowColorChoice.FOLLOW_ACCENT -> baseColor.with(a = glowCustomColor.a)
            GlowColorChoice.RAINBOW -> Ts2VisualUtils.rainbow(
                System.currentTimeMillis() % 10000L + (glowRainbowOffset * 1000f).toLong(),
                glowCustomColor.a / 255f,
            )
            GlowColorChoice.THEME -> Ts2VisualUtils.themeColor(0).with(a = glowCustomColor.a)
        }
        val adjustedAlpha = (raw.a * glowStrength).toInt().coerceIn(0, 255)
        return raw.with(a = adjustedAlpha)
    }

    @Suppress("unused")
    private val onPacket = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.OUTGOING || event.isCancelled) return@handler
        val pkt = event.packet
        if (pkt is ServerboundUseItemOnPacket && pkt.hitResult.type == HitResult.Type.BLOCK) {
            val pos = pkt.hitResult.blockPos
            val block = mc.level?.getBlockState(pos)?.block
            if (block is ChestBlock || block is EnderChestBlock || block is ShulkerBoxBlock) {
                currentContainerPos = pos
            }
        }
    }

    @Suppress("unused")
    private val onRender2D = handler<OverlayRenderEvent> { event ->
        val p = mc.player ?: return@handler
        val screen = mc.gui.screen() as? AbstractContainerScreen<*>
        val chestOpen = screen != null && (screen.menu is net.minecraft.world.inventory.ChestMenu ||
            screen.menu is net.minecraft.world.inventory.ShulkerBoxMenu)

        if (chestOpen) {
            if (currentContainerPos == null) {
                currentContainerPos = findChestPosition()
            }
            if (!wasChestOpen) {
                animationStartTime = System.currentTimeMillis()
                targetScale = 1.0f
                wasChestOpen = true
                lastItems.clear()
            }
        } else if (wasChestOpen) {
            animationStartTime = System.currentTimeMillis()
            targetScale = 0.0f
            wasChestOpen = false
        }

        updateAnimation()
        updateCameraSmooth()
        clickAnimations.entries.removeAll { it.value.isFinished() }

        if (viewScale < 0.01f) {
            if (!chestOpen) {
                currentContainerPos = null
                lastItems.clear()
            }
            return@handler
        }

        val pos = currentContainerPos ?: return@handler
        val state = pos.state
        val otherDir = state?.anotherChestPartDirection()
        val worldCenter = if (otherDir != null) {
            pos.toVec3d(0.5 + otherDir.stepX * 0.5, 0.5, 0.5 + otherDir.stepZ * 0.5)
        } else {
            Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        }

        val projection = WorldToScreen.calculateScreenPos(worldCenter) ?: return@handler
        val menu: AbstractContainerMenu? = screen?.menu
        if (menu != null) {
            detectItemChanges(menu, p)
        }

        val chestSlots = menu?.slots?.filter { it.container !== p.inventory } ?: emptyList()
        val rows = if (chestSlots.isNotEmpty()) {
            ((chestSlots.size + 8) / 9).coerceAtLeast(1).also { lastRows = it }
        } else {
            lastRows
        }

        val ctx = event.context
        val roundX = projection.x - 82f + smoothX
        val roundY = projection.y / 1.5f + smoothZ

        val totalW = 164f
        val titleH = if (showTitle) titleBarHeight + dividerHeight else 0f
        val totalH = (rows * 18 + 6).toFloat() + titleH

        val accentColor = getAccentColor()
        val titleColor = getTitleColor()
        val bgAlpha = if (useBlur) {
            backgroundAlpha.coerceIn((blurStrength * 3f).toInt().coerceAtMost(200), 255)
        } else {
            backgroundAlpha
        }
        val bgColor = Color4b(0, 0, 0, bgAlpha)

        val centerX = roundX + totalW * 0.5f
        val centerY = roundY + totalH * 0.5f

        ctx.pose().withPush {
            translate(centerX, centerY)
            scale(viewScale, viewScale)
            translate(-centerX, -centerY)

            // Phase 0: Background Glow
            if (enableGlow && (glowMode == GlowModeChoice.BACKGROUND || glowMode == GlowModeChoice.BOTH)) {
                val glowColor = getGlowColor(accentColor)
                Ts2VisualUtils.drawOuterShadow(
                    ctx, roundX, roundY, totalW, totalH, bgRadius,
                    (glowBlurRadius * glowStrength).coerceAtLeast(1f), glowColor,
                )
            }

            // Phase 1: Outer Shadow
            if (shadowCheck) {
                Ts2VisualUtils.drawOuterShadow(
                    ctx, roundX, roundY, totalW, totalH, bgRadius, shadowRadius, shadowColor
                )
            }

            // Phase 2 & 3: Frosted Background
            UiDraw.fillBox(ctx, roundX, roundY, totalW, totalH, bgColor.argb, radius = bgRadius)

            // Phase 4: Title Bar
            var contentStartY = roundY
            if (showTitle) {
                Ts2VisualUtils.drawTopRoundedRect(
                    ctx, roundX, roundY, totalW, titleBarHeight, bgRadius, titleBarColor.argb
                )
                val fontPx = 8f * titleFontSize
                val titleText = screen?.title?.string?.takeIf { it.isNotBlank() } ?: "Chest"
                val tw = UiDraw.measureText(titleText, fontPx, UiFontWeight.SEMIBOLD)
                val lh = UiDraw.lineHeight(fontPx, UiFontWeight.SEMIBOLD)
                UiDraw.drawText(
                    ctx = ctx,
                    text = titleText,
                    x = roundX + (totalW - tw) * 0.5f,
                    y = roundY + (titleBarHeight - lh) * 0.5f,
                    fontSizePx = fontPx,
                    argb = titleColor.argb,
                    weight = UiFontWeight.SEMIBOLD,
                )
                val tb = roundY + titleBarHeight
                UiDraw.fillBox(ctx, roundX, tb, totalW, dividerHeight, dividerColor.argb, radius = 0f)
                contentStartY = tb + dividerHeight
            }

            // Phase 5: Items & Click Wave Ripples
            val startX = roundX + 5f
            val startY = contentStartY + 3f
            for ((idx, slot) in chestSlots.withIndex()) {
                val x = (startX + (idx % 9) * 18f).toInt()
                val y = (startY + (idx / 9) * 18f).toInt()

                clickAnimations[slot.index]?.let { anim ->
                    drawWaveAnimation(ctx, x, y, anim, accentColor)
                }

                val stack: ItemStack = slot.item
                if (!stack.isEmpty) {
                    ctx.item(stack, x, y)
                    ctx.itemDecorations(mc.font, stack, x, y)
                }
            }
        }
    }

    private fun detectItemChanges(menu: AbstractContainerMenu, player: net.minecraft.client.player.LocalPlayer) {
        for (slot in menu.slots) {
            if (slot.container === player.inventory) continue
            val hasNow = slot.hasItem()
            val hadBefore = lastItems[slot.index] == true
            if (hadBefore && !hasNow) {
                clickAnimations[slot.index] = ClickAnimation()
            }
            lastItems[slot.index] = hasNow
        }
    }

    private fun drawWaveAnimation(
        ctx: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        animation: ClickAnimation,
        accentColor: Color4b,
    ) {
        val alpha = animation.alpha
        if (alpha <= 0f) return
        val progress = min(1.0f, (System.currentTimeMillis() - animation.startTime) / 600f)
        val waveSize = 18f * (1f + progress * 1.5f)
        val waveColor = accentColor.with(a = (alpha * 180f).toInt().coerceIn(0, 255))
        val cx = x + 8f
        val cy = y + 8f
        val hs = waveSize * 0.5f
        UiDraw.fillBox(ctx, cx - hs, cy - hs, waveSize, waveSize, waveColor.argb, radius = hs)
    }

    private fun updateCameraSmooth() {
        val tx = sin(System.currentTimeMillis() / 2000.0).toFloat() * 2f
        val tz = cos(System.currentTimeMillis() / 2000.0).toFloat() * 2f
        smoothX += (tx - smoothX) * CAMERA_SMOOTH_SPEED
        smoothZ += (tz - smoothZ) * CAMERA_SMOOTH_SPEED
    }

    private fun updateAnimation() {
        val elapsed = System.currentTimeMillis() - animationStartTime
        val progress = min(1.0f, elapsed / ANIMATION_DURATION)
        val easing = easeOutBack(progress)
        viewScale = if (targetScale > viewScale) {
            easing * targetScale
        } else {
            targetScale + (1f - easing) * (viewScale - targetScale)
        }
        if (abs(viewScale - targetScale) < 0.01f) {
            viewScale = targetScale
        }
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
    }

    private fun findChestPosition(): BlockPos? {
        val p = mc.player ?: return null
        val level = mc.level ?: return null
        val hit = mc.hitResult
        if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
            val pos = hit.blockPos
            val block = level.getBlockState(pos).block
            if (block is ChestBlock || block is EnderChestBlock || block is ShulkerBoxBlock) {
                return pos
            }
        }
        val base = p.blockPosition()
        for (x in -3..3) {
            for (y in -2..2) {
                for (z in -3..3) {
                    val pos = base.offset(x, y, z)
                    val block = level.getBlockState(pos).block
                    if (block is ChestBlock || block is EnderChestBlock || block is ShulkerBoxBlock) {
                        return pos
                    }
                }
            }
        }
        return null
    }
}
