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
@file:Suppress("LongMethod", "CognitiveComplexMethod")

package net.ccbluex.liquidbounce.ui.hud.components

import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.ClosedCaptionDirection
import net.ccbluex.liquidbounce.event.events.ClosedCaptionEntry
import net.ccbluex.liquidbounce.event.events.ClosedCaptionsEvent
import net.ccbluex.liquidbounce.event.events.OverlayMessageEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleSwordBlock
import net.ccbluex.liquidbounce.injection.mixins.minecraft.gui.MixinHudAccessor
import net.ccbluex.liquidbounce.ui.common.UiAnimation
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.ui.common.UiIcons
import net.ccbluex.liquidbounce.ui.common.UiLocalization
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.ccbluex.liquidbounce.ui.hud.HudComponent
import net.ccbluex.liquidbounce.ui.hud.HudComponentTweak
import net.ccbluex.liquidbounce.ui.hud.HudSize
import net.ccbluex.liquidbounce.utils.inventory.EnderChestInventoryTracker
import net.ccbluex.liquidbounce.utils.render.Alignment
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.Hud
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.numbers.NumberFormat
import net.minecraft.network.chat.numbers.StyledFormat
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 10. Hotbar (`HotBar.svelte` + `Status.svelte`: 9x45px slots, offhand at -65px, status bars, held tooltip & actionbar)
 */
class HotbarHudComponent : HudComponent(
    name = "Hotbar",
    enabledByDefault = false,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.CENTER_TRANSLATED, 0, Alignment.ScreenAxisY.BOTTOM, 15),
    componentDescription = "Replaces the vanilla hotbar and status display.",
    tweaks = arrayOf(
        HudComponentTweak.TWEAK_HOTBAR,
        HudComponentTweak.DISABLE_STATUS_BAR,
        HudComponentTweak.DISABLE_EXP_BAR,
        HudComponentTweak.DISABLE_HELD_ITEM_TOOL_TIP,
        HudComponentTweak.DISABLE_OVERLAY_MESSAGE,
        HudComponentTweak.DISABLE_LOCATOR_BAR,
    ),
) {
    private var lastSelectedSlot = -1
    private var heldItemText: Component? = null
    private var heldItemShownMs = 0L
    private var overlayText: Component? = null
    private var overlayShownMs = 0L
    private var maxSeenAbsorption = 0f
    private var sliderAnimX = 0f

    @Suppress("unused")
    private val overlayMsgHandler = handler<OverlayMessageEvent> { event ->
        overlayText = event.text
        overlayShownMs = System.currentTimeMillis()
    }

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val p = mc.player ?: return HudSize(405f, 95f)
        val gm = mc.gameMode?.playerMode ?: GameType.SURVIVAL
        if (gm == GameType.SPECTATOR && !inEditor) return HudSize(405f, 45f)

        val now = System.currentTimeMillis()
        val selectedSlot = p.inventory.selectedSlot.coerceIn(0, 8)
        if (selectedSlot != lastSelectedSlot) {
            lastSelectedSlot = selectedSlot
            val mainStack = p.mainHandItem
            if (!mainStack.isEmpty) {
                heldItemText = mainStack.hoverName
                heldItemShownMs = now
            } else {
                heldItemText = null
            }
        }

        val absorption = p.absorptionAmount.coerceAtLeast(0f)
        if (absorption <= 0f) maxSeenAbsorption = 0f
        if (absorption > maxSeenAbsorption) maxSeenAbsorption = absorption

        val slotSize = 45f
        val barWidth = slotSize * 9f // 405f
        val barH = 20f
        val rowGap = 5f
        val pairColGap = 25f
        val halfW = (barWidth - pairColGap) * 0.5f

        // Compute status rows dynamically from top to bottom
        val showOverlay = overlayText != null && now - overlayShownMs < 3200L
        val showHeld = heldItemText != null && now - heldItemShownMs < 2200L
        val hasArmorOrAir = p.armorValue > 0 || p.airSupply < p.maxAirSupply
        val isSurvivalLike = gm != GameType.CREATIVE && gm != GameType.SPECTATOR
        val hasAbsorption = isSurvivalLike && absorption > 0f
        val hasXp = p.experienceLevel > 0 || p.experienceProgress > 0f

        var cursorY = 0f
        if (showOverlay) {
            val fade = (1f - ((now - overlayShownMs - 3000L) / 200f).coerceIn(0f, 1f))
            val comp = overlayText!!
            val tw = UiDraw.measureComponent(comp, 14f, UiFontWeight.MEDIUM)
            UiDraw.drawComponent(
                ctx, comp,
                (barWidth - tw) * 0.5f, cursorY,
                14f, UiPalette.withAlphaFactor(UiPalette.TEXT_COLOR, fade), UiFontWeight.MEDIUM,
            )
            cursorY += UiDraw.lineHeight(14f, UiFontWeight.MEDIUM) + 12f
        }

        if (showHeld) {
            val fade = (1f - ((now - heldItemShownMs - 2000L) / 200f).coerceIn(0f, 1f))
            val comp = heldItemText!!
            val tw = UiDraw.measureComponent(comp, 14f, UiFontWeight.MEDIUM)
            val lh = UiDraw.lineHeight(14f, UiFontWeight.MEDIUM)
            val pillW = tw + 16f
            val pillH = lh + 10f
            val pillX = (barWidth - pillW) * 0.5f
            UiDraw.fillBox(ctx, pillX, cursorY, pillW, pillH, UiPalette.withAlphaFactor(UiPalette.surface(0.68f), fade), radius = 5f)
            UiDraw.drawComponent(
                ctx, comp,
                pillX + 8f, cursorY + 5f,
                14f, UiPalette.withAlphaFactor(UiPalette.TEXT_COLOR, fade), UiFontWeight.MEDIUM,
            )
            cursorY += pillH + 10f
        }

        if (hasArmorOrAir) {
            if (p.armorValue > 0) {
                drawStatusBar(ctx, 0f, cursorY, halfW, barH, p.armorValue.toFloat(), 20f, UiPalette.HOTBAR_ARMOR_COLOR, false, null, UiIcons.HOTBAR_SHIELD)
            }
            if (p.airSupply < p.maxAirSupply) {
                drawStatusBar(ctx, halfW + pairColGap, cursorY, halfW, barH, p.airSupply.coerceAtLeast(0).toFloat(), max(1, p.maxAirSupply).toFloat(), UiPalette.HOTBAR_AIR_COLOR, true, null, null)
            }
            cursorY += barH + rowGap
        }

        if (hasAbsorption) {
            drawStatusBar(ctx, 0f, cursorY, halfW, barH, absorption, max(1f, maxSeenAbsorption), UiPalette.HOTBAR_ABSORPTION_COLOR, false, null, null)
            cursorY += barH + rowGap
        }

        if (isSurvivalLike) {
            drawStatusBar(ctx, 0f, cursorY, halfW, barH, p.health.coerceAtLeast(0f), max(1f, p.maxHealth), UiPalette.HOTBAR_HEALTH_COLOR, false, null, UiIcons.HOTBAR_HEART)
            drawStatusBar(ctx, halfW + pairColGap, cursorY, halfW, barH, p.foodData.foodLevel.toFloat(), 20f, UiPalette.HOTBAR_HUNGER_COLOR, true, null, UiIcons.HOTBAR_FOOD)
            cursorY += barH + rowGap
        }

        if (hasXp) {
            drawStatusBar(ctx, 0f, cursorY, barWidth, barH, p.experienceProgress * 100f, 100f, UiPalette.HOTBAR_EXPERIENCE_COLOR, false, p.experienceLevel.toString(), null)
            cursorY += barH + rowGap
        }

        // Main 9-slot hotbar row
        val slotsY = cursorY
        UiDraw.fillBox(ctx, 0f, slotsY, barWidth, slotSize, UiPalette.surface(0.68f), radius = 5f)

        val targetSliderX = selectedSlot * slotSize
        sliderAnimX = UiAnimation.expLerp(sliderAnimX, targetSliderX, 22f, dtSeconds)
        UiDraw.fillBox(
            ctx, sliderAnimX, slotsY, slotSize, slotSize,
            0, radius = 5f,
            borderColor = UiPalette.accent(1f), borderWidth = 2f,
        )

        val items = p.inventory.nonEquipmentItems
        for (slot in 0 until Inventory.SELECTION_SIZE) {
            val stack = items.getOrNull(slot) ?: ItemStack.EMPTY
            if (!stack.isEmpty) {
                drawHotbarSlotStack(ctx, p, stack, slot * slotSize, slotsY, slotSize, tickDelta, slot + 1)
            }
        }

        // Offhand slot (`left: -65px; bottom: 0; width: 45px; height: 45px`)
        val offhand = p.offhandItem
        val hideOffhand = ModuleSwordBlock.shouldHideOffhand() && ModuleSwordBlock.hideShieldSlot
        if (!offhand.isEmpty && !hideOffhand) {
            UiDraw.fillBox(ctx, -65f, slotsY, slotSize, slotSize, UiPalette.surface(0.68f), radius = 5f)
            drawHotbarSlotStack(ctx, p, offhand, -65f, slotsY, slotSize, tickDelta, 10)
        }

        return HudSize(barWidth, slotsY + slotSize)
    }

    private fun drawStatusBar(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        value: Float,
        maxVal: Float,
        fillArgb: Int,
        alignRight: Boolean,
        label: String?,
        icon: Identifier?,
    ) {
        UiDraw.fillBox(ctx, x, y, w, h, UiPalette.surface(0.68f), radius = 5f)
        val ratio = (value / max(0.001f, maxVal)).coerceIn(0f, 1f)
        if (ratio > 0.002f) {
            val fw = w * ratio
            val fx = if (alignRight) (x + w - fw) else x
            UiDraw.fillBox(ctx, fx, y, fw, h, fillArgb, radius = 5f)
        }
        if (icon != null) {
            val ix = if (alignRight) (x + w - 5f - 12f) else (x + 5f)
            UiDraw.drawIcon(ctx, icon, ix, y + (h - 12f) * 0.5f, 12f, 12f, UiPalette.TEXT_COLOR)
        }
        if (label != null) {
            val lw = UiDraw.measureText(label, 13f, UiFontWeight.MEDIUM)
            val lh = UiDraw.lineHeight(13f, UiFontWeight.MEDIUM)
            val lx = if (alignRight) (x + 5f) else (x + w - 5f - lw)
            UiDraw.drawText(ctx, label, lx, y + (h - lh) * 0.5f, 13f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
        }
    }

    private fun drawHotbarSlotStack(
        ctx: GuiGraphicsExtractor,
        player: Player,
        stack: ItemStack,
        slotX: Float,
        slotY: Float,
        slotSize: Float,
        partialTick: Float,
        seed: Int,
    ) {
        // 16x16 MC GUI item scaled by 2f = 32x32 HUD CSS px, centered in 45x45 slot -> +6.5f
        val itemBox = 32f
        val ox = slotX + (slotSize - itemBox) * 0.5f
        val oy = slotY + (slotSize - itemBox) * 0.5f

        ctx.pose().pushMatrix()
        ctx.pose().translate(ox, oy)
        ctx.pose().scale(2f, 2f)

        val pop = stack.popTime.toFloat() - partialTick
        if (pop > 0f) {
            val squeeze = 1f + pop / 5f
            ctx.pose().pushMatrix()
            ctx.pose().translate(8f, 12f)
            ctx.pose().scale(1f / squeeze, (squeeze + 1f) * 0.5f)
            ctx.pose().translate(-8f, -12f)
            ctx.item(player, stack, 0, 0, seed)
            ctx.pose().popMatrix()
        } else {
            ctx.item(player, stack, 0, 0, seed)
        }
        ctx.itemDecorations(mc.font, stack, 0, 0)
        ctx.pose().popMatrix()
    }
}

/**
 * 11. Effects (`Effects.svelte`: active potion effects with 16x16 sprite, amplifier, and monospace duration)
 */
class EffectsHudComponent : HudComponent(
    name = "Effects",
    enabledByDefault = true,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.RIGHT, 15, Alignment.ScreenAxisY.BOTTOM, 15),
    componentDescription = "Shows your active status effects.",
    tweaks = arrayOf(HudComponentTweak.DISABLE_STATUS_EFFECT_OVERLAY),
) {
    private fun formatDuration(ticks: Int): String {
        if (ticks < 0) return "*:*"
        val totalSeconds = floor(ticks / 20.0).toInt().coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val effects = mc.player?.activeEffects?.toList().orEmpty()
        if (effects.isEmpty()) {
            if (inEditor) {
                val w = 156f
                val h = 28f
                UiDraw.fillBox(ctx, 0f, 0f, w, h, UiPalette.surface(0.50f), radius = 5f)
                UiDraw.drawText(ctx, UiLocalization.text("Speed 2", "速度 2"), 8f, 5f, 14f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
                UiDraw.drawText(ctx, "1:30", w - 36f, 6.5f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MONOSPACE)
                return HudSize(w, h)
            }
            return HudSize(150f, 28f)
        }

        data class EffectRow(
            val sprite: Identifier,
            val name: String,
            val amp: String,
            val dur: String,
            val rowW: Float,
        )

        val rows = effects.map { inst ->
            val sprite = Hud.getMobEffectSprite(inst.effect)
            val name = UiLocalization.effectName(inst.effect.value().displayName.string)
            val amp = (inst.amplifier + 1).toString()
            val dur = formatDuration(inst.duration)
            val nw = UiDraw.measureText("$name  ", 14f, UiFontWeight.MEDIUM)
            val aw = UiDraw.measureText(amp, 14f, UiFontWeight.MEDIUM)
            val dw = UiDraw.measureText(dur, 12f, UiFontWeight.MONOSPACE)
            EffectRow(sprite, name, amp, dur, 12f + 16f + 8f + nw + aw + 12f + dw)
        }

        val boxW = rows.maxOf { it.rowW }
        val rowH = 20f
        val gap = 4f
        val boxH = 8f + rows.size * rowH + max(0, rows.size - 1) * gap

        UiDraw.fillBox(ctx, 0f, 0f, boxW, boxH, UiPalette.surface(0.50f), radius = 5f)

        rows.forEachIndexed { idx, r ->
            val ry = 4f + idx * (rowH + gap)
            ctx.pose().pushMatrix()
            ctx.pose().translate(6f, ry + 2f)
            ctx.blitSprite(RenderPipelines.GUI_TEXTURED, r.sprite, 0, 0, 16, 16)
            ctx.pose().popMatrix()

            val tx = 30f
            val nw = UiDraw.drawText(ctx, "${r.name}  ", tx, ry + 1f, 14f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
            UiDraw.drawText(ctx, r.amp, tx + nw, ry + 1f, 14f, UiPalette.ERROR_COLOR, UiFontWeight.MEDIUM)

            val dw = UiDraw.measureText(r.dur, 12f, UiFontWeight.MONOSPACE)
            UiDraw.drawText(ctx, r.dur, boxW - 6f - dw, ry + 2.5f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MONOSPACE)
        }

        return HudSize(boxW, boxH)
    }
}

/**
 * 12. Scoreboard (`Scoreboard.svelte`: vanilla sidebar with `Show` multi-choice + regex replace)
 */
class ScoreboardHudComponent : HudComponent(
    name = "Scoreboard",
    enabledByDefault = true,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.LEFT, 15, Alignment.ScreenAxisY.CENTER_TRANSLATED, 0),
    componentDescription = "Displays the current server scoreboard.",
    tweaks = arrayOf(HudComponentTweak.DISABLE_SCOREBOARD),
) {
    enum class ShowPart(override val tag: String) : Tagged {
        HEADER("Header"),
        NAME("Name"),
        SCORE("Score"),
    }

    private val show = multiEnumChoice("Show", ShowPart.entries, canBeNone = false)
    private val replaceRegex by text("ReplaceRegex", "")
    private val replaceWith by text("ReplaceWith", "")

    private var cachedPatternStr = ""
    private var cachedRegex: Regex? = null

    private fun currentRegex(): Regex? {
        val raw = replaceRegex
        if (raw.isEmpty()) return null
        if (raw == cachedPatternStr) return cachedRegex
        cachedPatternStr = raw
        cachedRegex = runCatching { Regex(raw) }.getOrNull()
        return cachedRegex
    }

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val level = mc.level ?: return HudSize(150f, 90f)
        val sb = level.scoreboard
        val team = mc.player?.let { sb.getPlayersTeam(it.scoreboardName) }
        val objective = team?.color?.orElse(null)?.let { sb.getDisplayObjective(it.displaySlot()) }
            ?: sb.getDisplayObjective(DisplaySlot.SIDEBAR)

        if (objective == null) {
            if (inEditor) {
                val w = 150f
                val h = 72f
                UiDraw.fillCssBox(ctx, 0f, 0f, w, 28f, 5f, 5f, 0f, 0f, UiPalette.surface(0.68f))
                val sbTitle = UiLocalization.text("Scoreboard", "计分板")
                val stw = UiDraw.measureText(sbTitle, 14f, UiFontWeight.SEMIBOLD)
                UiDraw.drawText(ctx, sbTitle, (w - stw) * 0.5f, 5f, 14f, UiPalette.TEXT_COLOR, UiFontWeight.SEMIBOLD)
                UiDraw.fillCssBox(ctx, 0f, 28f, w, 44f, 0f, 0f, 5f, 5f, UiPalette.surface(0.50f))
                UiDraw.drawText(ctx, UiLocalization.text("Player", "玩家"), 10f, 36f, 14f, UiPalette.TEXT_COLOR)
                UiDraw.drawText(ctx, "1", w - 18f, 36f, 14f, UiPalette.ERROR_COLOR)
                return HudSize(w, h)
            }
            return HudSize(150f, 72f)
        }

        val showSet = show.get()
        val showHeader = ShowPart.HEADER in showSet
        val showName = ShowPart.NAME in showSet
        val showScore = ShowPart.SCORE in showSet

        val numberFormat: NumberFormat = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT)
        val entries = sb.listPlayerScores(objective)
            .filter { !it.isHidden }
            .sortedWith(MixinHudAccessor.getScoreboardEntryComparator())
            .take(15)

        val regex = currentRegex()
        val repl = replaceWith

        val headerComp = objective.displayName
        val headerOverride = regex?.let { headerComp.string.replace(it, repl) }

        data class SbRow(val nameComp: Component, val nameStrOverride: String?, val scoreComp: Component, val scoreStrOverride: String?)

        val rows = entries.map { entry: PlayerScoreEntry ->
            val pTeam = sb.getPlayersTeam(entry.owner())
            val decoratedName = PlayerTeam.formatNameForTeam(pTeam, entry.ownerName())
            val formattedScore = entry.formatValue(numberFormat)
            SbRow(
                nameComp = decoratedName,
                nameStrOverride = regex?.let { decoratedName.string.replace(it, repl) },
                scoreComp = formattedScore,
                scoreStrOverride = regex?.let { formattedScore.string.replace(it, repl) },
            )
        }

        val lh = UiDraw.lineHeight(14f, UiFontWeight.REGULAR)
        var maxRowW = 80f
        if (showHeader) {
            val hw = if (headerOverride != null) {
                UiDraw.measureText(headerOverride, 14f, UiFontWeight.MEDIUM)
            } else {
                UiDraw.measureComponent(headerComp, 14f, UiFontWeight.MEDIUM)
            }
            maxRowW = max(maxRowW, hw + 20f)
        }
        for (r in rows) {
            val nw = if (!showName) 0f else if (r.nameStrOverride != null) {
                UiDraw.measureText(r.nameStrOverride, 14f)
            } else {
                UiDraw.measureComponent(r.nameComp, 14f)
            }
            val sw = if (!showScore) 0f else if (r.scoreStrOverride != null) {
                UiDraw.measureText(r.scoreStrOverride, 14f)
            } else {
                UiDraw.measureComponent(r.scoreComp, 14f)
            }
            val gap = if (showName && showScore) 15f else 0f
            maxRowW = max(maxRowW, nw + gap + sw + 20f)
        }

        val headerH = if (showHeader) (lh + 14f) else 0f
        val rowStep = max(14f, lh)
        val bodyH = 20f + rows.size * rowStep
        val totalH = headerH + bodyH

        if (showHeader) {
            UiDraw.fillCssBox(ctx, 0f, 0f, maxRowW, headerH, 5f, 5f, 0f, 0f, UiPalette.surface(0.68f))
            val hw = if (headerOverride != null) {
                UiDraw.measureText(headerOverride, 14f, UiFontWeight.MEDIUM)
            } else {
                UiDraw.measureComponent(headerComp, 14f, UiFontWeight.MEDIUM)
            }
            val hx = (maxRowW - hw) * 0.5f
            if (headerOverride != null) {
                UiDraw.drawText(ctx, headerOverride, hx, 7f, 14f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
            } else {
                UiDraw.drawComponent(ctx, headerComp, hx, 7f, 14f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
            }
        }

        val rTop = if (showHeader) 0f else 5f
        UiDraw.fillCssBox(ctx, 0f, headerH, maxRowW, bodyH, rTop, rTop, 5f, 5f, UiPalette.surface(0.50f))

        rows.forEachIndexed { idx, r ->
            val ry = headerH + 10f + idx * rowStep
            if (showName) {
                if (r.nameStrOverride != null) {
                    UiDraw.drawText(ctx, r.nameStrOverride, 10f, ry, 14f, UiPalette.TEXT_COLOR)
                } else {
                    UiDraw.drawComponent(ctx, r.nameComp, 10f, ry, 14f, UiPalette.TEXT_COLOR)
                }
            }
            if (showScore) {
                if (r.scoreStrOverride != null) {
                    val sw = UiDraw.measureText(r.scoreStrOverride, 14f)
                    UiDraw.drawText(ctx, r.scoreStrOverride, maxRowW - 10f - sw, ry, 14f, UiPalette.ERROR_COLOR)
                } else {
                    val sw = UiDraw.measureComponent(r.scoreComp, 14f)
                    UiDraw.drawComponent(ctx, r.scoreComp, maxRowW - 10f - sw, ry, 14f, UiPalette.ERROR_COLOR)
                }
            }
        }

        return HudSize(maxRowW, totalH)
    }
}

/**
 * 13. ClosedCaptions (`ClosedCaptions.svelte`: vanilla subtitle stream captured by `SubtitleOverlayCapture`)
 */
class ClosedCaptionsHudComponent : HudComponent(
    name = "ClosedCaptions",
    enabledByDefault = false,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.RIGHT, 2, Alignment.ScreenAxisY.BOTTOM, 40),
    componentDescription = "Displays Minecraft's closed captions (Settings -> Accessibility Settings -> Closed Captions).",
    tweaks = arrayOf(HudComponentTweak.DISABLE_SUBTITLE_OVERLAY),
) {
    @Volatile
    private var entries: List<ClosedCaptionEntry> = emptyList()

    @Suppress("unused")
    private val captionsHandler = handler<ClosedCaptionsEvent> { event ->
        entries = event.entries.toList()
    }

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val current = entries
        if (current.isEmpty()) {
            if (inEditor) {
                val w = 148f
                val h = 24f
                UiDraw.fillBox(ctx, 0f, 0f, w, h, UiPalette.surface(0.68f), radius = 2f)
                UiDraw.drawText(ctx, UiLocalization.text("< Footsteps >", "< 脚步声 >"), 12f, 3f, 14f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
                return HudSize(w, h)
            }
            return HudSize(148f, 24f)
        }

        val arrowW = UiDraw.measureText("<", 14f, UiFontWeight.BOLD)
        val lh = UiDraw.lineHeight(14f, UiFontWeight.MEDIUM)
        val rowH = lh + 4f
        val gap = 2f

        val widths = current.map { e ->
            12f + arrowW * 2f + 8f + UiDraw.measureComponent(e.text, 14f, UiFontWeight.MEDIUM)
        }
        val maxW = widths.maxOrNull() ?: 120f
        val totalH = current.size * rowH + max(0, current.size - 1) * gap

        // `flex-direction: column-reverse; align-items: flex-end;`
        current.forEachIndexed { idx, entry ->
            val rw = widths[idx]
            val rx = maxW - rw
            val ry = totalH - (idx + 1) * rowH - idx * gap
            UiDraw.fillBox(ctx, rx, ry, rw, rowH, entry.backgroundColor, radius = 2f)

            if (entry.direction == ClosedCaptionDirection.LEFT) {
                UiDraw.drawText(ctx, "<", rx + 6f, ry + 2f, 14f, entry.textColor, UiFontWeight.BOLD)
            }
            UiDraw.drawComponent(ctx, entry.text, rx + 6f + arrowW + 4f, ry + 2f, 14f, entry.textColor, UiFontWeight.MEDIUM)
            if (entry.direction == ClosedCaptionDirection.RIGHT) {
                UiDraw.drawText(ctx, ">", rx + rw - 6f - arrowW, ry + 2f, 14f, entry.textColor, UiFontWeight.BOLD)
            }
        }

        return HudSize(maxW, totalH)
    }
}

/**
 * Shared grid renderer for `GenericPlayerInventory.svelte` (`ArmorItems`, `Inventory`, `CraftingInventory`,
 * `EnderChestInventory`, `InventoryStatistics`).
 */
internal object InventoryGridDrawer {
    fun drawGrid(
        ctx: GuiGraphicsExtractor,
        stacks: List<ItemStack>,
        rowLength: Int,
        gapPx: Float,
        backgroundArgb: Int,
        showZeroCounts: Boolean = false,
    ): HudSize {
        val cols = max(1, rowLength)
        val count = max(1, stacks.size)
        val rows = ceil(count.toDouble() / cols.toDouble()).toInt().coerceAtLeast(1)
        val cellSize = 32f
        val pad = 4f
        val totalW = pad * 2f + cols * cellSize + max(0, cols - 1) * gapPx
        val totalH = pad * 2f + rows * cellSize + max(0, rows - 1) * gapPx

        if ((backgroundArgb ushr 24) != 0) {
            UiDraw.fillBox(ctx, 0f, 0f, totalW, totalH, backgroundArgb, radius = 5f)
        }

        val font = net.ccbluex.liquidbounce.utils.client.mc.font
        stacks.forEachIndexed { index, stack ->
            val c = index % cols
            val r = index / cols
            val cx = pad + c * (cellSize + gapPx)
            val cy = pad + r * (cellSize + gapPx)
            if (!stack.isEmpty) {
                ctx.pose().pushMatrix()
                ctx.pose().translate(cx, cy)
                ctx.pose().scale(2f, 2f)
                ctx.item(stack, 0, 0)
                if (showZeroCounts && stack.count <= 1) {
                    val label = if (stack.count <= 0) "0" else stack.count.toString()
                    ctx.itemDecorations(font, stack, 0, 0, label)
                } else {
                    ctx.itemDecorations(font, stack, 0, 0)
                }
                ctx.pose().popMatrix()
            }
        }

        return HudSize(totalW, totalH)
    }
}

/**
 * 14. ArmorItems (`armoritems.json` + `GenericPlayerInventory.svelte`)
 */
class ArmorItemsHudComponent : HudComponent(
    name = "ArmorItems",
    enabledByDefault = false,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.CENTER_TRANSLATED, 240, Alignment.ScreenAxisY.BOTTOM, 15),
    componentDescription = "Displays your equipped armor.",
) {
    enum class LayoutMode(override val tag: String) : Tagged {
        VERTICAL("Vertical"),
        HORIZONTAL("Horizontal"),
    }

    private val layout by enumChoice("Layout", LayoutMode.VERTICAL)

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val p = mc.player
        val stacks = if (p != null) {
            listOf(
                p.getItemBySlot(EquipmentSlot.HEAD),
                p.getItemBySlot(EquipmentSlot.CHEST),
                p.getItemBySlot(EquipmentSlot.LEGS),
                p.getItemBySlot(EquipmentSlot.FEET),
            )
        } else {
            List(4) { ItemStack.EMPTY }
        }
        val rowLen = if (layout == LayoutMode.HORIZONTAL) 4 else 1
        return InventoryGridDrawer.drawGrid(ctx, stacks, rowLen, gapPx = 2f, backgroundArgb = 0)
    }
}

/**
 * 15. Inventory (`inventory.json`: main 9x3 player inventory `slots 9..35`)
 */
class InventoryHudComponent : HudComponent(
    name = "Inventory",
    enabledByDefault = false,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.RIGHT, 35, Alignment.ScreenAxisY.BOTTOM, 35),
    componentDescription = "Displays your main inventory contents.",
) {
    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val nonEquip = mc.player?.inventory?.nonEquipmentItems
        val stacks = if (nonEquip != null && nonEquip.size >= 36) {
            nonEquip.subList(9, 36)
        } else {
            List(27) { ItemStack.EMPTY }
        }
        return InventoryGridDrawer.drawGrid(ctx, stacks, rowLength = 9, gapPx = 8f, backgroundArgb = UiPalette.surface(0.50f))
    }
}

/**
 * 16. CraftingInventory (`craftinginventory.json`: 2x2 player crafting grid)
 */
class CraftingInventoryHudComponent : HudComponent(
    name = "CraftingInventory",
    enabledByDefault = false,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.RIGHT, 35, Alignment.ScreenAxisY.BOTTOM, 305),
    componentDescription = "Displays the contents of your crafting grid.",
) {
    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val craft = mc.player?.inventoryMenu?.craftSlots?.items
        val stacks = if (craft != null && craft.isNotEmpty()) craft.toList() else List(4) { ItemStack.EMPTY }
        return InventoryGridDrawer.drawGrid(ctx, stacks, rowLength = 2, gapPx = 8f, backgroundArgb = UiPalette.surface(0.50f))
    }
}

/**
 * 17. EnderChestInventory (`enderchestinventory.json`: 9x3 tracked Ender Chest contents)
 */
class EnderChestInventoryHudComponent : HudComponent(
    name = "EnderChestInventory",
    enabledByDefault = false,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.RIGHT, 35, Alignment.ScreenAxisY.BOTTOM, 170),
    componentDescription = "Displays your Ender Chest contents.",
) {
    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        return InventoryGridDrawer.drawGrid(
            ctx,
            EnderChestInventoryTracker.stacks,
            rowLength = 9,
            gapPx = 8f,
            backgroundArgb = UiPalette.surface(0.50f),
        )
    }
}

/**
 * 18. InventoryStatistics (`inventorystatistics.json` + `InventoryStatistics.svelte`)
 */
class InventoryStatisticsHudComponent : HudComponent(
    name = "InventoryStatistics",
    enabledByDefault = false,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.CENTER_TRANSLATED, 220, Alignment.ScreenAxisY.BOTTOM, 15),
    componentDescription = "Tracks configured items in your inventory.",
) {
    private val trackedItems = registryList(
        "Items",
        LinkedHashSet(
            listOf(
                Items.EMERALD,
                Items.DIAMOND,
                Items.GOLD_INGOT,
                Items.IRON_INGOT,
                Items.COPPER_INGOT,
            )
        ),
        ValueType.ITEM,
    )
    private val showEmpty by boolean("ShowEmpty", true)
    private val rowLength by int("RowLength", 1, 1..9)

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val selected: Set<Item> = trackedItems.get()
        if (selected.isEmpty()) return HudSize(40f, 40f)

        val inv = mc.player?.inventory?.nonEquipmentItems.orEmpty()
        val totals = HashMap<Item, Int>(selected.size * 2)
        for (stack in inv) {
            if (!stack.isEmpty && stack.item in selected) {
                totals[stack.item] = (totals[stack.item] ?: 0) + stack.count
            }
        }

        val displayStacks = ArrayList<ItemStack>(selected.size)
        for (item in selected) {
            val total = totals[item] ?: 0
            if (total > 0 || showEmpty) {
                val st = ItemStack(item, max(1, total))
                // Store real 0-flag via count if >0, else render "0" decoration
                displayStacks.add(if (total == 0) ItemStack(item, 1) else st)
            }
        }

        if (displayStacks.isEmpty()) {
            return if (inEditor) InventoryGridDrawer.drawGrid(ctx, listOf(ItemStack(Items.DIAMOND, 1)), 1, 2f, 0) else HudSize(40f, 40f)
        }

        val cols = max(1, rowLength)
        val rows = ceil(displayStacks.size.toDouble() / cols.toDouble()).toInt().coerceAtLeast(1)
        val cell = 32f
        val gap = 2f
        val pad = 4f
        val totalW = pad * 2f + cols * cell + max(0, cols - 1) * gap
        val totalH = pad * 2f + rows * cell + max(0, rows - 1) * gap

        val selectedList = selected.filter { (totals[it] ?: 0) > 0 || showEmpty }
        selectedList.forEachIndexed { idx, item ->
            val count = totals[item] ?: 0
            val c = idx % cols
            val r = idx / cols
            val cx = pad + c * (cell + gap)
            val cy = pad + r * (cell + gap)
            val st = ItemStack(item, 1)
            ctx.pose().pushMatrix()
            ctx.pose().translate(cx, cy)
            ctx.pose().scale(2f, 2f)
            ctx.item(st, 0, 0)
            ctx.itemDecorations(mc.font, st, 0, 0, count.toString())
            ctx.pose().popMatrix()
        }

        return HudSize(totalW, totalH)
    }
}
