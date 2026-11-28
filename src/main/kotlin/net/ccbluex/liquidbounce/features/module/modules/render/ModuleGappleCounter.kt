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
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.misc.antibot.ModuleAntiBot
import net.ccbluex.liquidbounce.ui.common.UiLocalization
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.utils.render.Ts2VisualUtils
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 1:1 port of ts2 `GappleCounter` (`render/GappleCounter.kt`), tracking local & nearby players'
 * normal and enchanted golden apple counts via equipment packets and live inventory/equipment scans,
 * and rendering the full frosted HUD leaderboard card.
 */
@Suppress("MagicNumber", "TooManyFunctions")
object ModuleGappleCounter : ClientModule("GappleCounter", ModuleCategories.RENDER) {

    data class PlayerGappleData(
        val name: String,
        val normalCount: Int,
        val enchantedCount: Int,
        val distance: Float,
        val isSelf: Boolean,
    ) {
        val totalCount: Int
            get() = normalCount + enchantedCount
    }

    enum class SortMode(override val tag: String) : Tagged {
        COUNT("Count"),
        NAME("Name"),
        DISTANCE("Distance"),
    }

    private val posX by float("PosX", 0.02f, 0f..1f)
    private val posY by float("PosY", 0.25f, 0f..1f)
    private val scale by float("Scale", 1f, 0.5f..3f)

    private val maxPlayers by int("MaxPlayers", 10, 1..30)
    private val maxRenderDistance by int("MaxRenderDistance", 64, 1..256).onChanged { value ->
        maxRenderDistanceSq = value.toDouble().pow(2.0)
    }
    private var maxRenderDistanceSq = 64.0.pow(2.0)

    private val showSelf by boolean("ShowSelf", true)
    private val showBots by boolean("ShowBots", false)
    private val sortBy by enumChoice("SortBy", SortMode.COUNT)
    private val showZero by boolean("ShowZero", false)
    private val showDistance by boolean("ShowDistance", true)
    private val showIcons by boolean("ShowIcons", true)

    private val backgroundColor by color("BackgroundColor", Color4b(0, 0, 0, 160))
    private val titleBarColor by color("TitleBarColor", Color4b(0, 0, 0, 200))
    private val dividerColor by color("DividerColor", Color4b(255, 255, 255, 30))
    private val accentColor by color("AccentColor", Color4b(255, 200, 50, 255))
    private val selfColor by color("SelfColor", Color4b(85, 255, 255, 255))
    private val otherColor by color("OtherColor", Color4b(255, 255, 255, 255))
    private val zeroColor by color("ZeroColor", Color4b(120, 120, 120, 255))
    private val distanceColor by color("DistanceColor", Color4b(160, 160, 160, 255))
    private val countColor by color("CountColor", Color4b(255, 255, 255, 255))

    private val backgroundRadius by float("BgRadius", 6f, 0f..15f)
    private val titleBarHeight by float("TitleBarHeight", 18f, 12f..26f)
    private val entryHeight by float("EntryHeight", 20f, 16f..28f)
    private val panelWidth by float("PanelWidth", 170f, 120f..300f)
    private val dividerHeight by float("DividerHeight", 1f, 0.5f..3f)
    private val shadow by boolean("Shadow", false)
    private val shadowRadius by float("ShadowRadius", 8f, 1f..20f)
    private val fontShadow by boolean("FontShadow", true)
    private val blur by boolean("Blur", true)
    private val blurStrength by float("BlurStrength", 15f, 5f..30f)

    private var playerDataList: List<PlayerGappleData> = emptyList()
    private var tickCounter = 0
    private val gappleCache = ConcurrentHashMap<String, Pair<Int, Int>>()

    private val normalGappleStack by lazy { ItemStack(Items.GOLDEN_APPLE, 1) }
    private val enchantedGappleStack by lazy { ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1) }

    override fun onDisabled() {
        playerDataList = emptyList()
        tickCounter = 0
        gappleCache.clear()
    }

    @Suppress("unused")
    private val onWorld = handler<WorldChangeEvent> {
        gappleCache.clear()
        playerDataList = emptyList()
    }

    @Suppress("unused")
    private val onPacket = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING || event.isCancelled) return@handler
        val packet = event.packet as? ClientboundSetEquipmentPacket ?: return@handler

        val level = mc.level ?: return@handler
        val entity = level.getEntity(packet.entity) as? Player ?: return@handler
        if (entity is LocalPlayer) return@handler

        val name = entity.gameProfile.name
        for (pair in packet.slots) {
            val stack = pair.second ?: continue
            if (stack.isEmpty) continue
            when {
                stack.`is`(Items.ENCHANTED_GOLDEN_APPLE) -> {
                    val cached = gappleCache[name] ?: (0 to 0)
                    gappleCache[name] = cached.first to stack.count
                }
                stack.`is`(Items.GOLDEN_APPLE) -> {
                    val cached = gappleCache[name] ?: (0 to 0)
                    gappleCache[name] = stack.count to cached.second
                }
            }
        }
    }

    @Suppress("unused")
    private val onTick = handler<GameTickEvent> {
        tickCounter++
        if (tickCounter % 5 != 0) return@handler
        updatePlayerData()
    }

    private fun updatePlayerData() {
        val localPlayer = mc.player ?: return
        val level = mc.level ?: return
        val list = ArrayList<PlayerGappleData>()

        val players = level.players()
        val onlineNames = players.mapTo(HashSet()) { it.gameProfile.name }
        gappleCache.keys.removeAll { it !in onlineNames }

        for (target in players) {
            if (!target.isAlive || target.isRemoved) continue

            val isSelf = target === localPlayer
            if (isSelf && !showSelf) continue
            if (!isSelf && !showBots && ModuleAntiBot.isBot(target)) continue

            val distSq = localPlayer.distanceToSqr(target)
            if (!isSelf && distSq > maxRenderDistanceSq) continue

            var normalCount = 0
            var enchantedCount = 0

            if (isSelf) {
                val inv = target.inventory
                for (i in 0 until 36) {
                    val stack = inv.getItem(i)
                    if (stack.isEmpty) continue
                    when {
                        stack.`is`(Items.ENCHANTED_GOLDEN_APPLE) -> enchantedCount += stack.count
                        stack.`is`(Items.GOLDEN_APPLE) -> normalCount += stack.count
                    }
                }
                val offhand = target.offhandItem
                if (!offhand.isEmpty) {
                    when {
                        offhand.`is`(Items.ENCHANTED_GOLDEN_APPLE) -> enchantedCount += offhand.count
                        offhand.`is`(Items.GOLDEN_APPLE) -> normalCount += offhand.count
                    }
                }
            } else {
                var detectedNormal = 0
                var detectedEnchanted = 0
                for (slot in EquipmentSlot.entries) {
                    val stack = target.getItemBySlot(slot)
                    if (stack.isEmpty) continue
                    when {
                        stack.`is`(Items.ENCHANTED_GOLDEN_APPLE) -> detectedEnchanted += stack.count
                        stack.`is`(Items.GOLDEN_APPLE) -> detectedNormal += stack.count
                    }
                }

                val profileName = target.gameProfile.name
                if (detectedNormal > 0 || detectedEnchanted > 0) {
                    gappleCache[profileName] = detectedNormal to detectedEnchanted
                    normalCount = detectedNormal
                    enchantedCount = detectedEnchanted
                } else {
                    val cached = gappleCache[profileName]
                    if (cached != null) {
                        normalCount = cached.first
                        enchantedCount = cached.second
                    }
                }
            }

            val totalCount = normalCount + enchantedCount
            if (!showZero && totalCount == 0) continue

            val cleanDisplay = Ts2VisualUtils.stripColor(target.displayName?.string ?: target.gameProfile.name)
            list.add(
                PlayerGappleData(
                    name = if (isSelf) "§b$cleanDisplay" else cleanDisplay,
                    normalCount = normalCount,
                    enchantedCount = enchantedCount,
                    distance = localPlayer.distanceTo(target),
                    isSelf = isSelf,
                )
            )
        }

        when (sortBy) {
            SortMode.COUNT -> list.sortByDescending { it.totalCount }
            SortMode.NAME -> list.sortBy { Ts2VisualUtils.stripColor(it.name).lowercase() }
            SortMode.DISTANCE -> list.sortBy { it.distance }
        }

        playerDataList = list.take(maxPlayers)
    }

    @Suppress("unused")
    private val onRender2D = handler<OverlayRenderEvent> { event ->
        if (playerDataList.isEmpty()) return@handler

        val ctx = event.context
        val baseX = ctx.guiWidth() * posX
        val baseY = ctx.guiHeight() * posY

        val w = panelWidth
        val titleH = titleBarHeight
        val divH = dividerHeight
        val entryH = entryHeight
        val pad = 6f
        val visibleCount = playerDataList.size
        val totalH = titleH + divH + entryH * visibleCount + pad * 2f

        ctx.pose().withPush {
            scale(scale, scale)

            val drawX = baseX / scale
            val drawY = baseY / scale

            // Outer shadow
            if (shadow) {
                Ts2VisualUtils.drawOuterShadow(
                    ctx, drawX, drawY, w, totalH, backgroundRadius, shadowRadius, Color4b(0, 0, 0, 120)
                )
            }

            // Frosted background
            val bgArgb = if (blur) {
                backgroundColor.with(
                    a = backgroundColor.a.coerceIn((blurStrength * 3f).toInt().coerceAtMost(210), 255)
                ).argb
            } else {
                backgroundColor.argb
            }
            UiDraw.fillBox(ctx, drawX, drawY, w, totalH, bgArgb, radius = backgroundRadius)

            // Title bar
            Ts2VisualUtils.drawTopRoundedRect(
                ctx, drawX, drawY, w, titleH, backgroundRadius, titleBarColor.argb
            )

            // Title icon (Golden Apple)
            val iconScale = 14f / 16f
            val iconY = drawY + (titleH - 14f) * 0.5f
            ctx.pose().withPush {
                translate(drawX + pad, iconY)
                scale(iconScale, iconScale)
                ctx.item(normalGappleStack, 0, 0)
            }

            // Title text & total summary
            val titleFontPx = 8.5f
            val titleLh = UiDraw.lineHeight(titleFontPx, UiFontWeight.SEMIBOLD)
            val titleTextY = drawY + (titleH - titleLh) * 0.5f
            UiDraw.drawText(
                ctx = ctx,
                text = UiLocalization.text("Gapple Counter", "金苹果统计"),
                x = drawX + pad + 14f + 6f,
                y = titleTextY,
                fontSizePx = titleFontPx,
                argb = accentColor.argb,
                weight = UiFontWeight.SEMIBOLD,
                shadow = fontShadow,
            )

            val totalN = playerDataList.sumOf { it.normalCount }
            val totalE = playerDataList.sumOf { it.enchantedCount }
            val totalT = buildTotalString(totalN, totalE)
            val totalW = Ts2VisualUtils.measureFormattedText(totalT, titleFontPx, UiFontWeight.SEMIBOLD)
            Ts2VisualUtils.drawFormattedText(
                ctx = ctx,
                text = totalT,
                x = drawX + w - pad - totalW,
                y = titleTextY,
                fontSizePx = titleFontPx,
                defaultArgb = Color4b.WHITE.argb,
                weight = UiFontWeight.SEMIBOLD,
                shadow = fontShadow,
            )

            // Divider line
            UiDraw.fillBox(ctx, drawX, drawY + titleH, w, divH, dividerColor.argb, radius = 0f)

            // Player rows
            val entryFontPx = 8f
            val entryLh = UiDraw.lineHeight(entryFontPx, UiFontWeight.REGULAR)
            val startY = drawY + titleH + divH + pad

            for ((index, data) in playerDataList.withIndex()) {
                val ey = startY + index * entryH

                if (index % 2 == 0) {
                    UiDraw.fillBox(
                        ctx, drawX + 4f, ey, w - 8f, entryH, Color4b(255, 255, 255, 10).argb, radius = 3f
                    )
                }

                val itemIconS = (entryH - 6f).coerceAtLeast(10f)
                val itemIconY = ey + (entryH - itemIconS) * 0.5f

                if (showIcons && data.totalCount > 0) {
                    val stackToDraw = if (data.enchantedCount > 0) enchantedGappleStack else normalGappleStack
                    val s = itemIconS / 16f
                    ctx.pose().withPush {
                        translate(drawX + pad + 2f, itemIconY)
                        scale(s, s)
                        ctx.item(stackToDraw, 0, 0)
                    }
                }

                val nameX = drawX + pad + 2f + if (showIcons && data.totalCount > 0) itemIconS + 4f else 0f
                val maxNameW = w * 0.4f
                val displayName = trimFormattedText(data.name, maxNameW, entryFontPx)
                val rowTextY = ey + (entryH - entryLh) * 0.5f

                Ts2VisualUtils.drawFormattedText(
                    ctx = ctx,
                    text = displayName,
                    x = nameX,
                    y = rowTextY,
                    fontSizePx = entryFontPx,
                    defaultArgb = if (data.isSelf) selfColor.argb else otherColor.argb,
                    weight = UiFontWeight.REGULAR,
                    shadow = fontShadow,
                )

                if (showDistance && !data.isSelf) {
                    val dt = "${data.distance.roundToInt()}m"
                    val dw = UiDraw.measureText(dt, entryFontPx, UiFontWeight.REGULAR)
                    UiDraw.drawText(
                        ctx = ctx,
                        text = dt,
                        x = drawX + w * 0.5f - dw * 0.5f,
                        y = rowTextY,
                        fontSizePx = entryFontPx,
                        argb = distanceColor.argb,
                        weight = UiFontWeight.REGULAR,
                        shadow = fontShadow,
                    )
                }

                val ct = buildCountString(data)
                val cw = Ts2VisualUtils.measureFormattedText(ct, entryFontPx, UiFontWeight.MEDIUM)
                Ts2VisualUtils.drawFormattedText(
                    ctx = ctx,
                    text = ct,
                    x = drawX + w - pad - cw,
                    y = rowTextY,
                    fontSizePx = entryFontPx,
                    defaultArgb = if (data.totalCount > 0) countColor.argb else zeroColor.argb,
                    weight = UiFontWeight.MEDIUM,
                    shadow = fontShadow,
                )
            }
        }
    }

    private fun buildTotalString(normal: Int, enchanted: Int): String {
        val parts = ArrayList<String>(2)
        if (normal > 0) parts.add("${normal}§6NG")
        if (enchanted > 0) parts.add("${enchanted}§5EG")
        return if (parts.isEmpty()) "0" else parts.joinToString(" ")
    }

    private fun buildCountString(data: PlayerGappleData): String {
        val parts = ArrayList<String>(2)
        if (data.normalCount > 0) parts.add("${data.normalCount}§6N")
        if (data.enchantedCount > 0) parts.add("${data.enchantedCount}§5E")
        return if (parts.isEmpty()) "0" else parts.joinToString(" §7| ")
    }

    private fun trimFormattedText(text: String, maxWidth: Float, fontSizePx: Float): String {
        if (Ts2VisualUtils.measureFormattedText(text, fontSizePx, UiFontWeight.REGULAR) <= maxWidth) return text
        var trimmed = text
        while (
            Ts2VisualUtils.measureFormattedText("$trimmed..", fontSizePx, UiFontWeight.REGULAR) > maxWidth &&
            trimmed.length > 1
        ) {
            trimmed = trimmed.substring(0, trimmed.length - 1)
            if (trimmed.endsWith('§')) {
                trimmed = trimmed.dropLast(1)
            }
        }
        return "$trimmed.."
    }
}
