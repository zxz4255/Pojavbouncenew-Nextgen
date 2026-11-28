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
package net.ccbluex.liquidbounce.ui.hud.components

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.BlockCountChangeEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.ui.common.UiAnimation
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.ui.common.UiIcons
import net.ccbluex.liquidbounce.ui.common.UiLocalization
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.ccbluex.liquidbounce.ui.hud.HudComponent
import net.ccbluex.liquidbounce.ui.hud.HudSize
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.render.Alignment
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.util.ARGB
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import java.awt.Color
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 6. Notifications (`Notifications.svelte` + `Notification.svelte`: `300px` cards, animated toggle knob & severity icons)
 */
class NotificationsHudComponent : HudComponent(
    name = "Notifications",
    enabledByDefault = true,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.RIGHT, 15, Alignment.ScreenAxisY.BOTTOM, 5),
    componentDescription = "Displays client notifications.",
) {
    enum class SeverityChoice(override val tag: String, val raw: NotificationEvent.Severity) : Tagged {
        INFO("INFO", NotificationEvent.Severity.INFO),
        SUCCESS("SUCCESS", NotificationEvent.Severity.SUCCESS),
        ERROR("ERROR", NotificationEvent.Severity.ERROR),
        ENABLED("ENABLED", NotificationEvent.Severity.ENABLED),
        DISABLED("DISABLED", NotificationEvent.Severity.DISABLED),
    }

    private val severities = multiEnumChoice(
        "Severities",
        SeverityChoice.entries,
        canBeNone = false,
    )

    private class LiveNotification(
        var title: String,
        val message: String,
        var severity: NotificationEvent.Severity,
        var createdMs: Long,
        var yOffset: Float = 0f,
        var slideX: Float = 30f,
        var knobRatio: Float = if (severity == NotificationEvent.Severity.ENABLED) 0.62f else 0.38f,
    )

    private val activeList = CopyOnWriteArrayList<LiveNotification>()

    @Suppress("unused")
    private val notifHandler = handler<NotificationEvent> { event ->
        val allowed = severities.get().any { it.raw == event.severity }
        if (!allowed) return@handler

        val now = System.currentTimeMillis()
        if (event.severity == NotificationEvent.Severity.ENABLED || event.severity == NotificationEvent.Severity.DISABLED) {
            val existing = activeList.find {
                (it.severity == NotificationEvent.Severity.ENABLED || it.severity == NotificationEvent.Severity.DISABLED) &&
                    it.message == event.message
            }
            if (existing != null) {
                existing.title = event.title
                existing.severity = event.severity
                existing.createdMs = now
                activeList.remove(existing)
                activeList.add(0, existing)
                return@handler
            }
        }
        activeList.add(0, LiveNotification(event.title, event.message, event.severity, now))
        while (activeList.size > 12) {
            activeList.removeAt(activeList.lastIndex)
        }
    }

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val now = System.currentTimeMillis()
        activeList.removeIf { now - it.createdMs > 3200L }

        val cardW = 300f
        val cardH = 60f
        val gap = 10f

        if (activeList.isEmpty()) {
            if (inEditor) {
                drawCard(
                    ctx, 0f, 0f, cardW, cardH,
                    UiLocalization.text("Notifications", "状态通知"),
                    UiLocalization.text("Client notifications appear here", "模块开关与客户端通知将显示在此处"),
                    NotificationEvent.Severity.INFO, 0.5f, 1f,
                )
                return HudSize(cardW, cardH)
            }
            return HudSize(cardW, cardH)
        }

        val totalH = activeList.size * cardH + max(0, activeList.size - 1) * gap
        activeList.forEachIndexed { idx, item ->
            val ageMs = now - item.createdMs
            val targetY = idx * (cardH + gap)
            item.yOffset = UiAnimation.expLerp(item.yOffset, targetY, 15f, dtSeconds)

            val targetSlide = if (ageMs > 3000L) 35f else 0f
            item.slideX = UiAnimation.expLerp(item.slideX, targetSlide, 16f, dtSeconds)

            val targetKnob = if (item.severity == NotificationEvent.Severity.ENABLED) 0.62f else 0.38f
            item.knobRatio = UiAnimation.expLerp(item.knobRatio, targetKnob, 16f, dtSeconds)

            val fadeAlpha = if (ageMs > 3000L) (1f - (ageMs - 3000L) / 200f).coerceIn(0f, 1f) else 1f
            val isToggle = item.severity == NotificationEvent.Severity.ENABLED ||
                item.severity == NotificationEvent.Severity.DISABLED
            val dispTitle = if (isToggle && UiLocalization.isChinese) {
                if (item.severity == NotificationEvent.Severity.ENABLED) "模块已启用" else "模块已关闭"
            } else {
                UiLocalization.displayName(item.title)
            }
            val dispMessage = if (isToggle) {
                if (UiLocalization.isChinese) {
                    val mn = UiLocalization.moduleName(item.message)
                    if (item.severity == NotificationEvent.Severity.ENABLED) "已开启 $mn" else "已关闭 $mn"
                } else {
                    item.message
                }
            } else {
                item.message
            }
            drawCard(ctx, item.slideX, item.yOffset, cardW, cardH, dispTitle, dispMessage, item.severity, item.knobRatio, fadeAlpha)
        }

        return HudSize(cardW, totalH)
    }

    private fun drawCard(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        title: String,
        message: String,
        severity: NotificationEvent.Severity,
        knobRatio: Float,
        alpha: Float,
    ) {
        UiDraw.fillBox(ctx, x, y, w, h, UiPalette.withAlphaFactor(UiPalette.surface(0.68f), alpha), radius = 5f)

        val iconBoxX = x + 10f
        val iconBoxY = y + 10f
        val iconSize = 40f
        val badgeColor = when (severity) {
            NotificationEvent.Severity.SUCCESS, NotificationEvent.Severity.ENABLED -> UiPalette.SUCCESS_COLOR
            NotificationEvent.Severity.ERROR, NotificationEvent.Severity.DISABLED -> UiPalette.ERROR_COLOR
            NotificationEvent.Severity.INFO -> UiPalette.accent(1f)
        }
        UiDraw.fillBox(ctx, iconBoxX, iconBoxY, iconSize, iconSize, UiPalette.withAlphaFactor(badgeColor, alpha), radius = 4f)

        val iconId = when (severity) {
            NotificationEvent.Severity.SUCCESS -> UiIcons.NOTIFY_SUCCESS
            NotificationEvent.Severity.ERROR -> UiIcons.NOTIFY_ERROR
            NotificationEvent.Severity.INFO -> UiIcons.NOTIFY_INFO
            NotificationEvent.Severity.ENABLED, NotificationEvent.Severity.DISABLED -> UiIcons.NOTIFY_TOGGLE
        }
        UiDraw.drawIcon(
            ctx, iconId,
            iconBoxX + 9f, iconBoxY + 9f, 22f, 22f,
            UiPalette.withAlphaFactor(UiPalette.TEXT_COLOR, alpha),
        )
        if (severity == NotificationEvent.Severity.ENABLED || severity == NotificationEvent.Severity.DISABLED) {
            val kx = iconBoxX + iconSize * knobRatio - 5f
            val ky = iconBoxY + iconSize * 0.5f - 5f
            UiDraw.fillBox(ctx, kx, ky, 10f, 10f, UiPalette.withAlphaFactor(UiPalette.TEXT_COLOR, alpha), radius = 5f)
        }

        val textX = iconBoxX + iconSize + 10f
        val maxTextW = w - (textX - x) - 10f
        val clippedTitle = UiDraw.ellipsize(title, maxTextW, 14f, UiFontWeight.SEMIBOLD)
        val clippedMsg = UiDraw.ellipsize(message, maxTextW, 12f, UiFontWeight.REGULAR)
        UiDraw.drawText(ctx, clippedTitle, textX, y + 12f, 14f, UiPalette.withAlphaFactor(UiPalette.TEXT_COLOR, alpha), UiFontWeight.SEMIBOLD)
        UiDraw.drawText(ctx, clippedMsg, textX, y + 31f, 12f, UiPalette.withAlphaFactor(UiPalette.TEXT_DIMMED_COLOR, alpha), UiFontWeight.REGULAR)
    }
}

/**
 * 7. TargetHud (`TargetHud.svelte` + `ArmorStatus.svelte` + `HealthProgress.svelte`)
 */
class TargetHudComponent : HudComponent(
    name = "TargetHud",
    enabledByDefault = true,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.CENTER, 20, Alignment.ScreenAxisY.CENTER_TRANSLATED, 0),
    componentDescription = "Shows combat information about your current target.",
) {
    private var lastTarget: LivingEntity? = null
    private var lastTargetMs: Long = 0L
    private var animHealthRatio: Float = 1f

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        val ent = event.entity
        if (ent is LivingEntity && ent.shouldBeAttacked()) {
            lastTarget = ent
            lastTargetMs = System.currentTimeMillis()
        }
    }

    private fun resolveTarget(inEditor: Boolean): LivingEntity? {
        val now = System.currentTimeMillis()
        val auraTarget = when {
            ModuleKillAura.running -> ModuleKillAura.targetTracker.target
            else -> null
        }
        if (auraTarget != null && auraTarget.isAlive) {
            lastTarget = auraTarget
            lastTargetMs = now
            return auraTarget
        }
        val cached = lastTarget
        if (cached != null && cached.isAlive && now - lastTargetMs <= 1200L) {
            return cached
        }
        return if (inEditor) mc.player else null
    }

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val target = resolveTarget(inEditor) ?: return HudSize(240f, 78f)

        val name = target.name.string
        val hp = target.health.coerceAtLeast(0f)
        val absorption = target.absorptionAmount.coerceAtLeast(0f)
        val maxHp = max(1f, target.maxHealth + absorption)
        val armorVal = target.armorValue

        val armorSlots = arrayOf(
            target.getItemBySlot(EquipmentSlot.HEAD),
            target.getItemBySlot(EquipmentSlot.CHEST),
            target.getItemBySlot(EquipmentSlot.LEGS),
            target.getItemBySlot(EquipmentSlot.FEET),
        ).filter { !it.isEmpty }

        val nameW = UiDraw.measureText(name, 15f, UiFontWeight.MEDIUM)
        val statsCount = if (absorption > 0f) 3 else 2
        val statsW = statsCount * 42f
        val armorSecW = if (armorSlots.isEmpty()) 0f else (5f + armorSlots.size * 40f + max(0, armorSlots.size - 1) * 10f)
        val midW = max(nameW, statsW)
        val cardW = 15f + 50f + 10f + midW + (if (armorSecW > 0f) 10f + armorSecW else 0f) + 15f
        val mainH = 70f
        val barH = 8f
        val cardH = mainH + barH

        UiDraw.fillBox(ctx, 0f, 0f, cardW, cardH, UiPalette.surface(0.68f), radius = 5f)

        // Avatar (50x50 rounded skin face + hurt red flash)
        val skinTex = when (target) {
            is AbstractClientPlayer -> target.skin.body().texturePath()
            is Player -> DefaultPlayerSkin.get(target.uuid).body().texturePath()
            else -> DefaultPlayerSkin.getDefaultTexture()
        }
        val hurtFactor = (target.hurtTime / 10f).coerceIn(0f, 1f)
        val faceTint = ARGB.color(
            255,
            255,
            (255 * (1f - 0.45f * hurtFactor)).toInt(),
            (255 * (1f - 0.45f * hurtFactor)).toInt(),
        )
        UiDraw.drawRoundedTexture(ctx, skinTex, 15f, 10f, 50f, 50f, radius = 5f, u0 = 8f / 64f, v0 = 8f / 64f, u1 = 16f / 64f, v1 = 16f / 64f, argb = faceTint)
        UiDraw.drawRoundedTexture(ctx, skinTex, 15f, 10f, 50f, 50f, radius = 5f, u0 = 40f / 64f, v0 = 8f / 64f, u1 = 48f / 64f, v1 = 16f / 64f, argb = faceTint)

        val infoX = 75f
        UiDraw.drawText(ctx, name, infoX, 14f, 15f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)

        // Health / Absorption / Armor stats row
        var sx = infoX
        val sy = 40f
        sx += drawStat(ctx, sx, sy, floor(hp).toInt().toString(), UiIcons.TARGET_HEALTH) + 10f
        if (absorption > 0f) {
            sx += drawStat(ctx, sx, sy, floor(absorption).toInt().toString(), UiIcons.TARGET_ABSORPTION) + 10f
        }
        drawStat(ctx, sx, sy, armorVal.toString(), UiIcons.TARGET_ARMOR)

        // Equipped armor items + 10-segment durability column (`ArmorStatus.svelte`)
        if (armorSlots.isNotEmpty()) {
            var ax = infoX + midW + 15f
            for (stack in armorSlots) {
                drawArmorStatus(ctx, stack, ax, 20f)
                ax += 50f
            }
        }

        // Bottom 8px health progress bar (`HealthProgress.svelte`)
        val targetRatio = ((hp + absorption) / maxHp).coerceIn(0f, 1f)
        animHealthRatio = UiAnimation.expLerp(animHealthRatio, targetRatio, 10f, dtSeconds)
        if (animHealthRatio > 0.002f) {
            UiDraw.fillCssBox(
                ctx, 0f, mainH, cardW * animHealthRatio, barH,
                0f, 0f, if (animHealthRatio > 0.98f) 5f else 0f, 5f,
                UiPalette.HOTBAR_HEALTH_COLOR,
            )
        }

        return HudSize(cardW, cardH)
    }

    private fun drawStat(ctx: GuiGraphicsExtractor, x: Float, y: Float, valText: String, icon: net.minecraft.resources.Identifier): Float {
        val tw = max(18f, UiDraw.measureText(valText, 14f, UiFontWeight.REGULAR))
        UiDraw.drawText(ctx, valText, x, y, 14f, UiPalette.TEXT_DIMMED_COLOR, UiFontWeight.REGULAR)
        UiDraw.drawIcon(ctx, icon, x + tw + 3f, y + 1.5f, 14f, 14f, UiPalette.TEXT_COLOR)
        return tw + 17f
    }

    private fun drawArmorStatus(ctx: GuiGraphicsExtractor, stack: ItemStack, x: Float, y: Float) {
        ctx.pose().pushMatrix()
        ctx.pose().translate(x, y + 3f)
        val itemScale = 30f / 16f
        ctx.pose().scale(itemScale, itemScale)
        ctx.item(stack, 0, 0)
        ctx.pose().popMatrix()

        val activePoints = if (stack.maxDamage > 0) {
            ceil(10.0 - (stack.damageValue.toDouble() / stack.maxDamage.toDouble() * 10.0)).toInt().coerceIn(0, 10)
        } else {
            10
        }
        val dotX = x + 35f
        for (i in 0 until 10) {
            val pointIndex = 10 - i
            val col = if (pointIndex <= activePoints) UiPalette.accent(1f) else UiPalette.surface(0.30f)
            UiDraw.fillBox(ctx, dotX, y - 3f + i * 4f, 5f, 3f, col, radius = 1f)
        }
    }
}

/**
 * 8. BlockCounter (`BlockCounter.svelte`: Scaffold block count with `IconPosition` & `mapToColor(count)`)
 */
class BlockCounterHudComponent : HudComponent(
    name = "BlockCounter",
    enabledByDefault = true,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.CENTER, -20, Alignment.ScreenAxisY.CENTER_TRANSLATED, 0),
    componentDescription = "Shows the number of blocks available for bridging.",
) {
    override val translateMinusFullWidth: Boolean get() = true

    enum class IconPos(override val tag: String) : Tagged {
        NONE("None"),
        LEFT("Left"),
        RIGHT("Right"),
        TOP("Top"),
        BOTTOM("Bottom"),
    }

    private val iconPosition by enumChoice("IconPosition", IconPos.LEFT)
    private var currentBlock: Block? = null
    private var currentCount: Int? = null

    @Suppress("unused")
    private val blockCountHandler = handler<BlockCountChangeEvent> { event ->
        currentBlock = event.nextBlock
        currentCount = event.count
    }

    /** Matches `043/src/util/color_utils.ts` `mapToColor(value)` (0..64 -> red..green HSL). */
    private fun mapToColor(count: Int): Int {
        val ratio = (count.coerceIn(0, 64) / 64f) * (120f / 360f)
        return Color.HSBtoRGB(ratio, 1f, 0.85f) or (0xFF shl 24)
    }

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val count = currentCount ?: if (inEditor) 64 else return HudSize(64f, 34f)
        val stack = currentBlock?.asItem()?.defaultInstance?.takeIf { !it.isEmpty }
        val showIcon = stack != null && iconPosition != IconPos.NONE

        val countStr = count.toString()
        val tw = UiDraw.measureText(countStr, 15f, UiFontWeight.MEDIUM)
        val lh = UiDraw.lineHeight(15f, UiFontWeight.MEDIUM)

        val padX = 8f
        val padY = 5f
        val iconSz = 24f
        val gap = 5f

        val isVertical = iconPosition == IconPos.TOP || iconPosition == IconPos.BOTTOM
        val boxW = if (!showIcon) {
            padX * 2f + tw
        } else if (isVertical) {
            padX * 2f + max(iconSz, tw)
        } else {
            padX * 2f + iconSz + gap + tw
        }
        val boxH = if (!showIcon) {
            padY * 2f + lh
        } else if (isVertical) {
            padY * 2f + iconSz + gap + lh
        } else {
            padY * 2f + max(iconSz, lh)
        }

        UiDraw.fillBox(ctx, 0f, 0f, boxW, boxH, UiPalette.surface(0.68f), radius = 5f)
        val textCol = mapToColor(count)

        if (!showIcon || stack == null) {
            UiDraw.drawText(ctx, countStr, (boxW - tw) * 0.5f, (boxH - lh) * 0.5f, 15f, textCol, UiFontWeight.MEDIUM)
        } else {
            val ix: Float
            val iy: Float
            val tx: Float
            val ty: Float
            when (iconPosition) {
                IconPos.LEFT -> {
                    ix = padX; iy = (boxH - iconSz) * 0.5f
                    tx = padX + iconSz + gap; ty = (boxH - lh) * 0.5f
                }
                IconPos.RIGHT -> {
                    tx = padX; ty = (boxH - lh) * 0.5f
                    ix = padX + tw + gap; iy = (boxH - iconSz) * 0.5f
                }
                IconPos.TOP -> {
                    ix = (boxW - iconSz) * 0.5f; iy = padY
                    tx = (boxW - tw) * 0.5f; ty = padY + iconSz + gap
                }
                IconPos.BOTTOM, IconPos.NONE -> {
                    tx = (boxW - tw) * 0.5f; ty = padY
                    ix = (boxW - iconSz) * 0.5f; iy = padY + lh + gap
                }
            }
            ctx.pose().pushMatrix()
            ctx.pose().translate(ix, iy)
            val s = iconSz / 16f
            ctx.pose().scale(s, s)
            ctx.item(stack, 0, 0)
            ctx.pose().popMatrix()

            UiDraw.drawText(ctx, countStr, tx, ty, 15f, textCol, UiFontWeight.MEDIUM)
        }

        return HudSize(boxW, boxH)
    }
}

/**
 * 9. Keystrokes (`Keystrokes.svelte` + `Key.svelte`: 3x3 `50px` grid WASD + Space bar with smooth inset fill)
 */
class KeystrokesHudComponent : HudComponent(
    name = "Keystrokes",
    enabledByDefault = false,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.LEFT, 15, Alignment.ScreenAxisY.BOTTOM, 15),
    componentDescription = "Visualizes your movement key inputs.",
) {
    private val animProgress = FloatArray(5)

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val opts = mc.options
        val keys: Array<KeyMapping> = arrayOf(
            opts.keyUp,
            opts.keyLeft,
            opts.keyDown,
            opts.keyRight,
            opts.keyJump,
        )

        val cell = 50f
        val gap = 5f
        val totalW = cell * 3f + gap * 2f
        val totalH = cell * 3f + gap * 2f

        val rects = arrayOf(
            floatArrayOf(cell + gap, 0f, cell, cell),
            floatArrayOf(0f, cell + gap, cell, cell),
            floatArrayOf(cell + gap, cell + gap, cell, cell),
            floatArrayOf((cell + gap) * 2f, cell + gap, cell, cell),
            floatArrayOf(0f, (cell + gap) * 2f, totalW, cell),
        )

        keys.forEachIndexed { idx, mapping ->
            val down = mapping.isDown
            animProgress[idx] = UiAnimation.expLerp(animProgress[idx], if (down) 1f else 0f, 16f, dtSeconds)

            val r = rects[idx]
            val rx = r[0]; val ry = r[1]; val rw = r[2]; val rh = r[3]
            UiDraw.fillBox(ctx, rx, ry, rw, rh, UiPalette.surface(0.68f), radius = 5f)

            val p = animProgress[idx]
            if (p > 0.01f) {
                // Smooth inset box-shadow fill (`inset 0 0 0 25px var(--keystrokes-active-color)`)
                val borderW = (rh * 0.5f) * p
                UiDraw.fillCssBox(
                    ctx, rx, ry, rw, rh,
                    5f, 5f, 5f, 5f,
                    cTL = 0,
                    borderColor = UiPalette.accent(1f),
                    borderWidth = borderW,
                )
            }

            val rawKeyLabel = mapping.translatedKeyMessage.string.uppercase()
            val label = if (UiLocalization.isChinese && idx == 4 && rawKeyLabel.equals("SPACE", ignoreCase = true)) {
                "空格"
            } else {
                rawKeyLabel
            }
            val tw = UiDraw.measureText(label, 14f, UiFontWeight.MEDIUM)
            val lh = UiDraw.lineHeight(14f, UiFontWeight.MEDIUM)
            UiDraw.drawText(ctx, label, rx + (rw - tw) * 0.5f, ry + (rh - lh) * 0.5f, 14f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
        }

        return HudSize(totalW, totalH)
    }
}
