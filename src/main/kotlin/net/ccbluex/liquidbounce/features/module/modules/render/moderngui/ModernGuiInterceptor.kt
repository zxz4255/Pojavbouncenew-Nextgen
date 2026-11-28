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
@file:Suppress(
    "TooManyFunctions",
    "LongMethod",
    "LongParameterList",
    "CognitiveComplexMethod",
    "ReturnCount",
    "ComplexCondition",
    "NamedArguments",
    "BracesOnIfStatements",
    "NestedBlockDepth",
    "UnusedParameter",
    "LoopWithTooManyJumpStatements",
    "LargeClass",
    "MaxLineLength",
)

package net.ccbluex.liquidbounce.features.module.modules.render.moderngui

import net.ccbluex.liquidbounce.features.module.modules.misc.music.MusicScreen
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Material
import net.ccbluex.liquidbounce.features.module.modules.render.OpaiChestLoot
import net.ccbluex.liquidbounce.render.copyPosePooled
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.render.gui.element.CssBoxGuiElementRenderState
import net.ccbluex.liquidbounce.ui.clickgui.NativeScreens
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassBackdrop
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassSurface
import net.ccbluex.liquidbounce.ui.clickgui.glass.LightPollutionGlassSurface
import net.ccbluex.liquidbounce.utils.inventory.ViewedInventoryScreen
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractSelectionList
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.DeathScreen
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.LevelLoadingScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.inventory.Slot
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class HudRegion {
    NONE,
    SCOREBOARD,
    TAB_LIST,
    BOSS_BAR,
    STATUS_EFFECTS,
    HOTBAR,
    CHAT,
    SUBTITLES,
}

/**
 * Central render-layer coordinator that intercepts vanilla & HUD GUI draw calls when [ModuleModernGui]
 * is enabled and upgrades them into Liquid Glass + Monet Dynamic Color rounded cards.
 */
object ModernGuiInterceptor {

    private const val VANILLA_SLOT_HOVER_COLOR: Int = -2130706433 // 0x80FFFFFF

    private val reentrancyGuard = ThreadLocal.withInitial { false }
    private val regionStack = ArrayDeque<HudRegion>(8)

    private var frameGlassSetup: TextureSetup? = null
    private var frameMonetPalette: MonetPalette? = null
    private var frameTheme: ResolvedGuiTheme? = null

    private var activeScreen: Screen? = null
    private var screenPosePushed: Boolean = false
    private var containerCardDrawnThisFrame: Boolean = false
    private var inClientHudScope: Boolean = false
    private var clientHudProfile = LightPollutionGlassSurface.Profile.SOFT

    private var lastAnimatedScreenRef: Screen? = null
    private var screenOpenNanos: Long = 0L

    private var lastScoreboardHeaderBottom: Int = -9999
    private var lastScoreboardLeft: Int = -9999
    private var tabListOuterCardDrawn: Boolean = false

    // Live fade-aware HUD chat union bounds (prevents ghost card after messages fade out)
    private var hudChatAccumMinX: Int = Int.MAX_VALUE
    private var hudChatAccumMinY: Int = Int.MAX_VALUE
    private var hudChatAccumMaxX: Int = Int.MIN_VALUE
    private var hudChatAccumMaxY: Int = Int.MIN_VALUE
    private var hudChatAccumMaxAlpha: Int = 0
    private var lastHudChatValid: Boolean = false
    private var lastHudChatX: Float = 0f
    private var lastHudChatY: Float = 0f
    private var lastHudChatW: Float = 0f
    private var lastHudChatH: Float = 0f
    private var lastHudChatFade: Float = 0f
    private var chatCardDrawnThisFrame: Boolean = false

    private val currentRegion: HudRegion
        get() = regionStack.lastOrNull() ?: HudRegion.NONE

    private fun ensureTheme(): ResolvedGuiTheme {
        frameTheme?.let { return it }
        val monet = if (ModuleModernGui.usesMonet) {
            val m = ModuleModernGui.Monet
            MonetColorEngine.updateAndGetPalette(
                source = m.source,
                variant = m.variant,
                brightness = m.brightness,
                surfaceTint = m.surfaceTint,
                chromaBoost = m.chromaBoost,
                contrast = m.contrast,
                transitionSpeed = m.transitionSpeed,
                sampleIntervalTicks = m.sampleIntervalTicks,
                preset = m.preset,
                customSeed = m.customSeed,
            ).also { frameMonetPalette = it }
        } else {
            frameMonetPalette = null
            null
        }
        return ModernGuiSurface.resolveTheme(monet).also { frameTheme = it }
    }

    /**
     * Supplies live Monet accent & surface colours to `HudComponentManager.syncPaletteFromTheme()` when enabled.
     */
    fun resolveClientPaletteOverride(): Pair<Color4b, Color4b>? {
        if (!ModuleModernGui.running || !ModuleModernGui.usesMonet || !ModuleModernGui.Targets.syncClientPalette) {
            return null
        }
        val m = ModuleModernGui.Monet
        val palette = frameMonetPalette ?: MonetColorEngine.updateAndGetPalette(
            source = m.source,
            variant = m.variant,
            brightness = m.brightness,
            surfaceTint = m.surfaceTint,
            chromaBoost = m.chromaBoost,
            contrast = m.contrast,
            transitionSpeed = m.transitionSpeed,
            sampleIntervalTicks = m.sampleIntervalTicks,
            preset = m.preset,
            customSeed = m.customSeed,
        )
        return Color4b(ARGB.opaque(palette.primary)) to Color4b(ARGB.opaque(palette.surface))
    }

    @JvmStatic
    fun onBeginGuiExtract() {
        regionStack.clear()
        lastScoreboardHeaderBottom = -9999
        tabListOuterCardDrawn = false
        containerCardDrawnThisFrame = false
        chatCardDrawnThisFrame = false
        hudChatAccumMinX = Int.MAX_VALUE
        hudChatAccumMinY = Int.MAX_VALUE
        hudChatAccumMaxX = Int.MIN_VALUE
        hudChatAccumMaxY = Int.MIN_VALUE
        hudChatAccumMaxAlpha = 0
        activeScreen = null
        screenPosePushed = false

        if (!ModuleModernGui.running) {
            frameGlassSetup = null
            frameMonetPalette = null
            frameTheme = null
            lastHudChatValid = false
            return
        }

        frameTheme = null
        frameMonetPalette = null
        ensureTheme()

        frameGlassSetup = if (ModuleModernGui.usesGlass) {
            ClickGuiGlassBackdrop.request(
                blurStrength = ModuleModernGui.GlassOptics.blurStrength,
                vibrance = ModuleModernGui.GlassOptics.vibrance,
            )
        } else {
            null
        }
    }

    @JvmStatic
    fun onEndGuiExtract() {
        if (hudChatAccumMaxAlpha > 2 && hudChatAccumMinX < hudChatAccumMaxX && hudChatAccumMinY < hudChatAccumMaxY) {
            lastHudChatValid = true
            lastHudChatX = (hudChatAccumMinX - 4).coerceAtLeast(1).toFloat()
            lastHudChatY = (hudChatAccumMinY - 3).toFloat()
            lastHudChatW = ((hudChatAccumMaxX - hudChatAccumMinX) + 8).toFloat()
            lastHudChatH = ((hudChatAccumMaxY - hudChatAccumMinY) + 6).toFloat()
            lastHudChatFade = (hudChatAccumMaxAlpha / 128f).coerceIn(0.08f, 1f)
        } else {
            lastHudChatValid = false
            lastHudChatFade = 0f
        }
        regionStack.clear()
        activeScreen = null
        screenPosePushed = false
    }

    @JvmStatic
    fun pushRegion(region: HudRegion) {
        if (!ModuleModernGui.running) return
        if (region == HudRegion.SCOREBOARD) {
            lastScoreboardHeaderBottom = -9999
        } else if (region == HudRegion.TAB_LIST) {
            tabListOuterCardDrawn = false
        }
        regionStack.addLast(region)
    }

    @JvmStatic
    fun popRegion() {
        if (regionStack.isNotEmpty()) {
            regionStack.removeLast()
        }
    }

    @JvmStatic
    fun withRegion(region: HudRegion, action: Runnable) {
        if (!ModuleModernGui.running) {
            action.run()
            return
        }
        pushRegion(region)
        try {
            action.run()
        } finally {
            popRegion()
        }
    }

    fun <T> withClientHudScope(
        profile: LightPollutionGlassSurface.Profile = LightPollutionGlassSurface.Profile.SOFT,
        block: () -> T,
    ): T {
        if (!ModuleModernGui.running || !ModuleModernGui.Targets.clientHudCards) {
            return block()
        }
        val prev = inClientHudScope
        val prevProfile = clientHudProfile
        inClientHudScope = true
        clientHudProfile = profile
        return try {
            block()
        } finally {
            inClientHudScope = prev
            clientHudProfile = prevProfile
        }
    }

    /**
     * Upgrades `UiDraw.fillCssBox` calls inside native Client HUD components (`TargetHUD`, `Notifications`,
     * `Hotbar`, `Scoreboard`, `Effects`, `Keystrokes`, `Inventory`, etc.) into Liquid Glass / Monet cards.
     */
    fun tryUpgradeClientHudBox(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rTL: Float,
        rTR: Float,
        rBR: Float,
        rBL: Float,
        cTL: Int,
        cTR: Int,
        cBR: Int,
        cBL: Int,
        borderColor: Int,
        borderWidth: Float,
        shadowColor: Int,
        shadowBlur: Float,
    ): Boolean {
        if (!inClientHudScope || !ModuleModernGui.running || !ModuleModernGui.Targets.clientHudCards) {
            return false
        }
        if (ClickGuiGlassSurface.isActive || reentrancyGuard.get()) {
            return false
        }
        // Only upgrade card/slot surfaces (>= 24x16 CSS px -> >= 12x8 GUI px), leaving tiny bars/dots/knobs crisp.
        if (w < 24f || h < 16f) {
            return false
        }
        val aAvg = (((cTL ushr 24) + (cTR ushr 24) + (cBR ushr 24) + (cBL ushr 24)) * 0.25f) / 255f
        if (aAvg < 0.18f || aAvg > 0.96f) {
            return false
        }

        val theme = ensureTheme()
        // CSS HUD space uses 2x coordinates (`cssToGui = 0.5f`), so scale GUI radius/border/shadow by 2x
        val targetR = ModuleModernGui.CardDesign.hudCardRadius * 2f
        val uRTL = if (rTL > 0.5f) max(rTL, min(targetR, min(w, h) * 0.5f)) else min(targetR * 0.5f, min(w, h) * 0.5f)
        val uRTR = if (rTR > 0.5f) max(rTR, min(targetR, min(w, h) * 0.5f)) else min(targetR * 0.5f, min(w, h) * 0.5f)
        val uRBR = if (rBR > 0.5f) max(rBR, min(targetR, min(w, h) * 0.5f)) else min(targetR * 0.5f, min(w, h) * 0.5f)
        val uRBL = if (rBL > 0.5f) max(rBL, min(targetR, min(w, h) * 0.5f)) else min(targetR * 0.5f, min(w, h) * 0.5f)

        val cardOp = (ModuleModernGui.CardDesign.cardOpacity * (aAvg / 0.68f)).coerceIn(0.10f, 0.92f)
        val topCol = ModernGuiSurface.withAlpha(
            Material.mix(theme.surfaceHigh, cTL or (0xFF shl 24), 0.35f),
            cardOp,
        )
        val botCol = ModernGuiSurface.withAlpha(
            Material.mix(theme.surfaceLow, cBR or (0xFF shl 24), 0.30f),
            (cardOp * 1.08f).coerceAtMost(0.95f),
        )
        val rimRgb = if ((borderColor ushr 24) > 20) {
            borderColor or (0xFF shl 24)
        } else {
            Material.mix(0xFFFFFFFF.toInt(), theme.primary, ModuleModernGui.GlassOptics.accentRimMix)
        }
        val bWidth = if (borderWidth > 0.1f) borderWidth else ModuleModernGui.CardDesign.borderWidth * 1.8f
        val sSpread = if (shadowBlur > 0.5f) shadowBlur else ModuleModernGui.CardDesign.shadowSpread * 1.5f
        val sCol = if ((shadowColor ushr 24) > 8) {
            shadowColor
        } else {
            ModernGuiSurface.withAlpha(0xFF000000.toInt(), ModuleModernGui.CardDesign.shadowOpacity * 0.72f)
        }

        reentrancyGuard.set(true)
        try {
            ModernGuiSurface.emitSurface(
                ctx = ctx,
                x = x, y = y, w = w, h = h,
                rTL = uRTL, rTR = uRTR, rBR = uRBR, rBL = uRBL,
                cTL = topCol, cTR = topCol, cBR = botCol, cBL = botCol,
                borderColor = ModernGuiSurface.withAlpha(rimRgb, ModuleModernGui.CardDesign.borderOpacity),
                borderWidth = bWidth,
                shadowColor = sCol,
                shadowSpread = sSpread,
                refractionScale = 0.85f,
                layerOpacity = 1f,
                glassSetup = frameGlassSetup,
                advanceStratum = w >= 64f && h >= 32f,
                referenceProfile = clientHudProfile,
            )
        } finally {
            reentrancyGuard.set(false)
        }
        return true
    }

    private data class DialogCardBounds(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val drawHeaderLine: Boolean,
        val headerOffsetY: Float,
    )

    private fun isExcludedScreen(screen: Screen?): Boolean {
        if (screen == null) return false
        if (NativeScreens.isClientScreen(screen) || screen is MusicScreen) {
            return true
        }
        if (screen is LevelLoadingScreen || OpaiChestLoot.isReplacing(screen)) {
            return true
        }
        if (screen is TitleScreen && !ModuleModernGui.Targets.includeTitleScreen) {
            return true
        }
        return false
    }

    private fun isScreenStyled(screen: Screen?): Boolean {
        if (!ModuleModernGui.running || screen == null || isExcludedScreen(screen)) return false
        return when (screen) {
            is AbstractContainerScreen<*>, is ViewedInventoryScreen -> ModuleModernGui.Targets.containers
            is ChatScreen -> ModuleModernGui.Targets.chatAndSuggestions
            else -> ModuleModernGui.Targets.screensAndMenus
        }
    }

    /**
     * Remaps Mojang's hardcoded container charcoal label color (`0x404040`, designed for `#C6C6C6` stone sheets)
     * to `theme.textPrimary` when rendering a styled dark-surface container screen so titles like `"战斗用品"` /
     * `"物品栏"` / `"合成"` remain crisp and legible.
     */
    @JvmStatic
    fun remapPreparedTextColor(color: Int): Int {
        if (!ModuleModernGui.running || !ModuleModernGui.Targets.containers) return color
        val screen = activeScreen ?: return color
        if ((screen !is AbstractContainerScreen<*> && screen !is ViewedInventoryScreen) || isExcludedScreen(screen)) {
            return color
        }
        val rgb = color and 0x00FFFFFF
        if (rgb != 0x404040 && rgb != 0x3F3F3F && rgb != 0x373737) {
            return color
        }
        val theme = ensureTheme()
        if (theme.isLight) return color
        val alpha = color and (0xFF shl 24)
        val outAlpha = if (alpha == 0) (0xFF shl 24) else alpha
        return outAlpha or (theme.textPrimary and 0x00FFFFFF)
    }

    @JvmStatic
    fun onBeforeScreenExtract(ctx: GuiGraphicsExtractor, screen: Screen) {
        screenPosePushed = false
        containerCardDrawnThisFrame = false
        if (!ModuleModernGui.running || isExcludedScreen(screen)) {
            activeScreen = null
            return
        }
        activeScreen = screen

        if (screen !== lastAnimatedScreenRef) {
            lastAnimatedScreenRef = screen
            screenOpenNanos = System.nanoTime()
        }

        // Smooth cubic-out entrance scale on containers & menu screens (never on ChatScreen)
        if (ModuleModernGui.CardDesign.openAnimation && screen !is ChatScreen && isScreenStyled(screen)) {
            val elapsedSec = ((System.nanoTime() - screenOpenNanos) * 1e-9f).coerceAtLeast(0f)
            val duration = (0.19f / ModuleModernGui.CardDesign.animationSpeed.coerceIn(0.25f, 3f))
            if (elapsedSec < duration) {
                val t = (elapsedSec / duration).coerceIn(0f, 1f)
                val eased = 1f - (1f - t).pow(3)
                val scale = 0.952f + 0.048f * eased
                val cx = screen.width * 0.5f
                val cy = screen.height * 0.5f
                ctx.pose().pushMatrix()
                ctx.pose().translate(cx, cy)
                ctx.pose().scale(scale, scale)
                ctx.pose().translate(-cx, -cy)
                screenPosePushed = true
            }
        }
    }

    @JvmStatic
    fun onAfterScreenExtract(ctx: GuiGraphicsExtractor) {
        if (screenPosePushed) {
            ctx.pose().popMatrix()
            screenPosePushed = false
        }
        activeScreen = null
    }

    /**
     * Replaces vanilla's harsh dark screen gradient with a smooth Monet-tinted ambient scrim + dialog card on menus.
     */
    @JvmStatic
    fun onExtractTransparentBackground(ctx: GuiGraphicsExtractor, screen: Screen): Boolean {
        if (!ModuleModernGui.running || isExcludedScreen(screen) || !isScreenStyled(screen)) {
            return false
        }

        val theme = ensureTheme()
        val scrimAlpha = ModuleModernGui.CardDesign.screenScrimOpacity
        if (scrimAlpha > 0.005f) {
            val topCol = ModernGuiSurface.withAlpha(theme.scrim, (scrimAlpha * 0.82f).coerceIn(0f, 0.92f))
            val botCol = ModernGuiSurface.withAlpha(
                Material.mix(theme.scrim, theme.primaryContainer, 0.14f),
                (scrimAlpha * 1.12f).coerceIn(0f, 0.95f),
            )
            val w = ctx.guiWidth().toFloat()
            val h = ctx.guiHeight().toFloat()
            val fullScreenBounds = ScreenRectangle(0, 0, ctx.guiWidth(), ctx.guiHeight())
            ctx.pose().pushMatrix()
            ctx.pose().identity()
            try {
                ctx.guiRenderState.addGuiElement(
                    CssBoxGuiElementRenderState(
                        0f, 0f, w, h,
                        0f, 0f, 0f, 0f,
                        topCol, topCol, botCol, botCol,
                        0, 0f,
                        0, 0f,
                        ctx.copyPosePooled(),
                        null,
                        fullScreenBounds,
                    )
                )
            } finally {
                ctx.pose().popMatrix()
            }
        }

        // Dynamically frame floating centered menu dialogs (`PauseScreen`, `DisconnectedScreen`, `DeathScreen`, `ConnectScreen`)
        // around their live widget bounds instead of hardcoded centered rectangles.
        if (ModuleModernGui.Targets.screensAndMenus &&
            (screen is PauseScreen || screen is DisconnectedScreen || screen is DeathScreen || screen is ConnectScreen)
        ) {
            val dialog = computeDialogCardBounds(screen)
            if (dialog != null && dialog.w > 64f && dialog.h > 36f) {
                ModernGuiSurface.drawElevatedCard(
                    ctx = ctx,
                    theme = theme,
                    glassSetup = frameGlassSetup,
                    x = dialog.x,
                    y = dialog.y,
                    w = dialog.w,
                    h = dialog.h,
                    radius = ModuleModernGui.CardDesign.panelRadius,
                    drawHeaderLine = dialog.drawHeaderLine,
                    headerOffsetY = dialog.headerOffsetY,
                    advanceStratum = true,
                )
            } else {
                ctx.nextStratum()
            }
        } else {
            ctx.nextStratum()
        }

        return true
    }

    /**
     * Computes exact card bounds from the screen's live widget hierarchy (`screen.children()`),
     * excluding corner utility buttons (e.g. `MixinDisconnectedScreen` corner quit button or mod corner icons)
     * and keeping far-away top screen headers (`PauseScreen`'s `"游戏菜单"` at `y ≈ 16`) cleanly outside the card.
     */
    private fun computeDialogCardBounds(screen: Screen): DialogCardBounds? {
        val sw = screen.width.toFloat()
        val sh = screen.height.toFloat()
        if (sw < 80f || sh < 60f) return null

        val cx = sw * 0.5f
        val primaryWidgets = ArrayList<AbstractWidget>(12)
        val labelWidgets = ArrayList<AbstractWidget>(6)

        for (child in screen.children()) {
            if (child is AbstractSelectionList<*>) continue
            if (child !is AbstractWidget) continue
            if (!child.visible || child.width <= 0 || child.height <= 0) continue

            val wx = child.x.toFloat()
            val wy = child.y.toFloat()
            val ww = child.width.toFloat()
            val wh = child.height.toFloat()
            val wCenterX = wx + ww * 0.5f

            // Exclude corner-pinned utility widgets (e.g. bottom-right anti-stuck quit button or top-corner mod buttons)
            val nearVerticalEdge = wy <= 28f || (wy + wh) >= sh - 34f
            if (nearVerticalEdge && abs(wCenterX - cx) > 105f) continue
            if (wx > cx + 175f || (wx + ww) < cx - 175f) continue

            if (child is Button || child is EditBox || wh >= 15f) {
                primaryWidgets.add(child)
            } else {
                labelWidgets.add(child)
            }
        }

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        val seedList = if (primaryWidgets.isNotEmpty()) primaryWidgets else labelWidgets
        for (w in seedList) {
            minX = min(minX, w.x.toFloat())
            minY = min(minY, w.y.toFloat())
            maxX = max(maxX, (w.x + w.width).toFloat())
            maxY = max(maxY, (w.y + w.height).toFloat())
        }

        val primaryMinY = minY

        // Include ConnectScreen's status & MixinConnectScreen's connection trace lines (`height / 2 - 80` .. `height / 2 - 50`)
        if (screen is ConnectScreen) {
            minX = min(minX, cx - 118f)
            maxX = max(maxX, cx + 118f)
            minY = min(minY, sh * 0.5f - 86f)
            maxY = max(maxY, sh * 0.5f - 44f)
        }

        if (!minX.isFinite() || !minY.isFinite() || minX >= maxX || minY >= maxY) {
            return null
        }

        // Iteratively absorb nearby title/reason label widgets within 28px of the cluster (e.g. DisconnectedScreen
        // LinearLayout title + reason), while leaving distant floating screen banners (PauseScreen's top "游戏菜单") outside.
        var absorbedHeaderBottom = Float.NEGATIVE_INFINITY
        if (primaryWidgets.isNotEmpty() && labelWidgets.isNotEmpty()) {
            val absorbed = BooleanArray(labelWidgets.size)
            for (pass in 0 until 3) {
                var changed = false
                for (i in labelWidgets.indices) {
                    if (absorbed[i]) continue
                    val lw = labelWidgets[i]
                    val ly0 = lw.y.toFloat()
                    val ly1 = (lw.y + lw.height).toFloat()
                    val verticalGap = when {
                        ly1 < minY -> minY - ly1
                        ly0 > maxY -> ly0 - maxY
                        else -> 0f
                    }
                    if (verticalGap <= 28f) {
                        absorbed[i] = true
                        changed = true
                        minX = min(minX, lw.x.toFloat())
                        minY = min(minY, ly0)
                        maxX = max(maxX, (lw.x + lw.width).toFloat())
                        maxY = max(maxY, ly1)
                        if (ly1 <= primaryMinY) {
                            absorbedHeaderBottom = max(absorbedHeaderBottom, ly1)
                        }
                    }
                }
                if (!changed) break
            }
        }

        // Keep the dialog card horizontally symmetric around screen center when the widget cluster is centered
        val clusterCenter = (minX + maxX) * 0.5f
        if (abs(clusterCenter - cx) <= 24f) {
            val halfW = max(cx - minX, maxX - cx)
            minX = cx - halfW
            maxX = cx + halfW
        }

        val extraPad = min(4f, ModuleModernGui.CardDesign.containerPadding * 0.35f)
        val padX = 12f + extraPad
        val padY = 10f + extraPad

        val left = max(6f, minX - padX)
        val top = max(6f, minY - padY)
        val right = min(sw - 6f, maxX + padX)
        val bottom = min(sh - 6f, maxY + padY)

        val hasCleanHeaderGap = ModuleModernGui.CardDesign.headerAccentLine &&
            absorbedHeaderBottom.isFinite() &&
            (primaryMinY - absorbedHeaderBottom) >= 8f
        val headerOffset = if (hasCleanHeaderGap) {
            ((absorbedHeaderBottom + primaryMinY) * 0.5f) - top
        } else {
            0f
        }

        return DialogCardBounds(
            x = left,
            y = top,
            w = max(0f, right - left),
            h = max(0f, bottom - top),
            drawHeaderLine = hasCleanHeaderGap,
            headerOffsetY = headerOffset,
        )
    }

    /**
     * Renders rounded slot wells + fallback container card for modded/custom [AbstractContainerScreen]s
     * immediately before `extractSlots` runs.
     */
    @JvmStatic
    fun onBeforeContainerSlots(
        ctx: GuiGraphicsExtractor,
        screen: AbstractContainerScreen<*>,
        hoveredSlot: Slot?,
    ) {
        if (!ModuleModernGui.running || isExcludedScreen(screen)) return
        val theme = ensureTheme()
        val slots = screen.menu.slots

        // Fallback card for custom/modded containers that did not blit a standard `textures/gui/container/<sheet>` sheet
        if (ModuleModernGui.Targets.containers && !containerCardDrawnThisFrame && slots.isNotEmpty()) {
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            for (slot in slots) {
                if (!slot.isActive) continue
                minX = min(minX, slot.x)
                minY = min(minY, slot.y)
                maxX = max(maxX, slot.x + 16)
                maxY = max(maxY, slot.y + 16)
            }
            if (minX <= maxX && minY <= maxY) {
                val extraPad = min(4f, ModuleModernGui.CardDesign.containerPadding * 0.4f)
                val cx = minX - 8f - extraPad
                val cy = minY - 18f - extraPad
                val cw = (maxX - minX) + 16f + extraPad * 2f
                val ch = (maxY - minY) + 26f + extraPad * 2f
                ModernGuiSurface.drawElevatedCard(
                    ctx = ctx,
                    theme = theme,
                    glassSetup = frameGlassSetup,
                    x = cx,
                    y = cy,
                    w = cw,
                    h = ch,
                    radius = ModuleModernGui.CardDesign.panelRadius,
                    drawHeaderLine = false,
                    advanceStratum = true,
                )
                containerCardDrawnThisFrame = true
            }
        }

        if (!ModuleModernGui.Targets.containerSlots) return

        drawSlotWellsForList(ctx, slots, hoveredSlot)
    }

    fun drawSlotWellsForList(
        ctx: GuiGraphicsExtractor,
        slots: List<Slot>,
        hoveredSlot: Slot?,
    ) {
        if (!ModuleModernGui.running || !ModuleModernGui.Targets.containerSlots) return
        val theme = ensureTheme()
        for (slot in slots) {
            if (!slot.isActive) continue
            val isHovered = slot === hoveredSlot && slot.isHighlightable
            ModernGuiSurface.drawSlotWell(
                ctx = ctx,
                theme = theme,
                x = slot.x - 1f,
                y = slot.y - 1f,
                size = 18f,
                hovered = isHovered,
                hasItem = !slot.item.isEmpty,
            )
        }
        ctx.nextStratum()
    }

    @JvmStatic
    fun shouldSuppressListSeparators(): Boolean =
        ModuleModernGui.running && ModuleModernGui.Targets.screensAndMenus && !isExcludedScreen(activeScreen)

    fun tryUpgradeOverlayCard(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ): Boolean {
        if (!ModuleModernGui.running || !ModuleModernGui.Targets.tooltips || reentrancyGuard.get()) {
            return false
        }
        val theme = ensureTheme()
        reentrancyGuard.set(true)
        try {
            ModernGuiSurface.drawElevatedCard(
                ctx = ctx,
                theme = theme,
                glassSetup = frameGlassSetup,
                x = x,
                y = y,
                w = w,
                h = h,
                radius = ModuleModernGui.CardDesign.tooltipRadius,
                opacityMultiplier = 1.10f,
                shadowMultiplier = 0.88f,
                advanceStratum = false,
            )
        } finally {
            reentrancyGuard.set(false)
        }
        return true
    }

    /**
     * Draws a single unified floating rounded Liquid Glass / Monet card behind visible chat history lines.
     */
    @JvmStatic
    fun onRenderChatHistoryCard(
        ctx: GuiGraphicsExtractor,
        chatWidth: Int,
        lineHeight: Int,
        visibleLineCount: Int,
        chatScale: Double,
        isChatScreenOpen: Boolean,
    ) {
        if (!ModuleModernGui.running || !ModuleModernGui.Targets.chatAndSuggestions || chatCardDrawnThisFrame) return
        if (visibleLineCount <= 0 || chatWidth <= 0 || lineHeight <= 0 || chatScale <= 0.0) return

        val theme = ensureTheme()
        reentrancyGuard.set(true)
        try {
            if (isChatScreenOpen) {
                val scaleF = chatScale.toFloat()
                val totalH = visibleLineCount * lineHeight * scaleF
                val guiH = ctx.guiHeight().toFloat()
                val bottomY = floor((guiH - 40f) / scaleF) * scaleF
                val topY = bottomY - totalH
                val padX = 5f
                val padY = 4f
                val leftEdge = 4f * scaleF
                val x = max(1f, leftEdge - padX)
                val y = topY - padY
                // Note: `ChatComponent.getWidth()` (`chatWidth`) is already in scaled GUI pixels
                val w = chatWidth.toFloat() + 4f * scaleF + padX * 2f
                val h = totalH + padY * 2f

                ModernGuiSurface.drawElevatedCard(
                    ctx = ctx,
                    theme = theme,
                    glassSetup = frameGlassSetup,
                    x = x,
                    y = y,
                    w = w,
                    h = h,
                    radius = ModuleModernGui.CardDesign.hudCardRadius,
                    opacityMultiplier = 1.05f,
                    shadowMultiplier = 0.90f,
                    advanceStratum = true,
                )
                chatCardDrawnThisFrame = true
            } else if (lastHudChatValid && lastHudChatFade > 0.02f) {
                ctx.pose().pushMatrix()
                ctx.pose().identity()
                try {
                    ModernGuiSurface.drawElevatedCard(
                        ctx = ctx,
                        theme = theme,
                        glassSetup = frameGlassSetup,
                        x = lastHudChatX,
                        y = lastHudChatY,
                        w = lastHudChatW,
                        h = lastHudChatH,
                        radius = ModuleModernGui.CardDesign.hudCardRadius,
                        opacityMultiplier = 0.90f * lastHudChatFade,
                        shadowMultiplier = 0.72f * lastHudChatFade,
                        advanceStratum = true,
                    )
                } finally {
                    ctx.pose().popMatrix()
                }
                chatCardDrawnThisFrame = true
            }
        } finally {
            reentrancyGuard.set(false)
        }
    }

    // Intercepts GuiGraphicsExtractor.blit for full container, recipe_book, creative pagination, and menu_list_background textures.
    @JvmStatic
    fun onBlitTexture(
        ctx: GuiGraphicsExtractor,
        texture: Identifier,
        x: Int,
        y: Int,
        u: Float,
        v: Float,
        w: Int,
        h: Int,
        color: Int,
    ): Boolean {
        if (!ModuleModernGui.running || reentrancyGuard.get() || w <= 0 || h <= 0) return false
        if (isExcludedScreen(activeScreen)) return false

        val path = texture.path

        // 0. Fabric API creative tab group pagination buttons (`fabric-item-group-api-v1:textures/gui/creative_buttons.png`)
        if (ModuleModernGui.Targets.widgets && path.endsWith("creative_buttons.png") && w in 8..16 && h in 8..16) {
            val theme = ensureTheme()
            val isDisabled = v >= 12f
            val isHovered = u >= 22f
            val isNextArrow = (u.toInt() % 22) >= 10
            reentrancyGuard.set(true)
            try {
                ModernGuiSurface.drawWidgetPill(
                    ctx = ctx,
                    theme = theme,
                    glassSetup = frameGlassSetup,
                    x = x.toFloat(),
                    y = y.toFloat(),
                    w = w.toFloat(),
                    h = h.toFloat(),
                    radius = min(3.5f, ModuleModernGui.CardDesign.widgetRadius * 0.5f),
                    hoveredOrFocused = isHovered && !isDisabled,
                    selected = false,
                    disabled = isDisabled,
                    advanceStratum = false,
                )
                drawMiniChevron(ctx, theme, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), rightArrow = isNextArrow, disabled = isDisabled)
                ctx.nextStratum()
            } finally {
                reentrancyGuard.set(false)
            }
            return true
        }

        // 1. Container & RecipeBook main background sheets
        if (ModuleModernGui.Targets.containers &&
            (path.startsWith("textures/gui/container/") || path.endsWith("recipe_book.png"))
        ) {
            // Never swallow atlas sub-sprites (`u >= 176` or `v >= 166`) such as furnace flames, arrows, bubbles, or mod gauges
            if (u >= 176f || v >= 166f) {
                return false
            }

            val isGenericChest = path.endsWith("/generic_54.png")
            if (isGenericChest) {
                // `ContainerScreen` blits two slices: top chest rows (`u = 0, v = 0, h = rows * 18 + 17`)
                // followed by player inventory (`u = 0, v = 126, h = 96`). Merge both into one seamless card!
                if (u <= 0.5f && v <= 0.5f && w >= 160 && h >= 32) {
                    val theme = ensureTheme()
                    reentrancyGuard.set(true)
                    try {
                        ModernGuiSurface.drawElevatedCard(
                            ctx = ctx,
                            theme = theme,
                            glassSetup = frameGlassSetup,
                            x = x.toFloat(),
                            y = y.toFloat(),
                            w = w.toFloat(),
                            h = (h + 96).toFloat(),
                            radius = ModuleModernGui.CardDesign.panelRadius,
                            drawHeaderLine = false,
                            advanceStratum = true,
                        )
                        containerCardDrawnThisFrame = true
                    } finally {
                        reentrancyGuard.set(false)
                    }
                    return true
                }
                if (u <= 0.5f && v >= 120f && w >= 160 && h in 88..104 && containerCardDrawnThisFrame) {
                    return true
                }
                return false
            }

            // Standard full container panels (`inventory.png`, `tab_items.png`, `crafting_table.png`, `furnace.png`, `recipe_book.png`, etc.)
            // Vanilla already bakes 7–8px of internal padding inside `(x, y, w, h)` — never expand outward into Creative tabs or RecipeBook!
            if (u <= 0.5f && v <= 0.5f && w >= 120 && h >= 75) {
                val theme = ensureTheme()
                val isRecipeBook = path.contains("recipe_book")
                val isCreativeSheet = path.contains("creative_inventory/")
                val cardRadius = if (isCreativeSheet) {
                    // Keep outer corners crisp under leftmost (`x = leftPos`) and rightmost (`x = leftPos + 169`) creative tabs
                    min(ModuleModernGui.CardDesign.panelRadius, 6f)
                } else {
                    ModuleModernGui.CardDesign.panelRadius
                }

                reentrancyGuard.set(true)
                try {
                    ModernGuiSurface.drawElevatedCard(
                        ctx = ctx,
                        theme = theme,
                        glassSetup = frameGlassSetup,
                        x = x.toFloat(),
                        y = y.toFloat(),
                        w = w.toFloat(),
                        h = h.toFloat(),
                        radius = cardRadius,
                        drawHeaderLine = false,
                        advanceStratum = false,
                    )

                    // Recessed 3D entity showcase wells & Creative scrollbar groove at exact vanilla coordinates
                    when {
                        path.endsWith("/inventory.png") -> {
                            ModernGuiSurface.drawRecessedWell(
                                ctx = ctx,
                                theme = theme,
                                x = x + 26f,
                                y = y + 8f,
                                w = 49f,
                                h = 70f,
                                radius = min(ModuleModernGui.CardDesign.widgetRadius, 6f),
                            )
                        }
                        path.endsWith("/tab_inventory.png") -> {
                            ModernGuiSurface.drawRecessedWell(
                                ctx = ctx,
                                theme = theme,
                                x = x + 73f,
                                y = y + 6f,
                                w = 32f,
                                h = 43f,
                                radius = min(ModuleModernGui.CardDesign.widgetRadius, 5f),
                            )
                        }
                        path.endsWith("/horse.png") -> {
                            ModernGuiSurface.drawRecessedWell(
                                ctx = ctx,
                                theme = theme,
                                x = x + 26f,
                                y = y + 18f,
                                w = 52f,
                                h = 52f,
                                radius = min(ModuleModernGui.CardDesign.widgetRadius, 6f),
                            )
                        }
                        path.endsWith("/tab_items.png") || path.endsWith("/tab_item_search.png") -> {
                            ModernGuiSurface.drawRecessedWell(
                                ctx = ctx,
                                theme = theme,
                                x = x + 174f,
                                y = y + 17f,
                                w = 14f,
                                h = 112f,
                                radius = 5f,
                                opacityScale = 0.85f,
                            )
                        }
                    }

                    ctx.nextStratum()
                    if (!isRecipeBook) {
                        containerCardDrawnThisFrame = true
                    }
                } finally {
                    reentrancyGuard.set(false)
                }
                return true
            }
            return false
        }

        // 2. Menu scrollable list backgrounds & separators (`Options`, `Multiplayer`, `Singleplayer`, `Controls`, `PackSelection`, etc.)
        if (ModuleModernGui.Targets.screensAndMenus && activeScreen != null) {
            if (path.contains("menu_list_background")) {
                val theme = ensureTheme()
                val screenW = activeScreen?.width ?: w
                // Only inset full-width lists on wide screens; never shrink narrow or two-column (`PackSelectionScreen`) lists!
                val insetX = if (w >= screenW - 4 && w > 340) {
                    min(12f, (w - 340) * 0.25f)
                } else {
                    0f
                }
                reentrancyGuard.set(true)
                try {
                    ModernGuiSurface.drawElevatedCard(
                        ctx = ctx,
                        theme = theme,
                        glassSetup = frameGlassSetup,
                        x = x + insetX,
                        y = y + 1f,
                        w = max(20f, w - insetX * 2f),
                        h = max(16f, h - 2f),
                        radius = min(ModuleModernGui.CardDesign.panelRadius, 10f),
                        opacityMultiplier = 0.88f,
                        shadowMultiplier = 0.72f,
                        advanceStratum = true,
                    )
                } finally {
                    reentrancyGuard.set(false)
                }
                return true
            }

            if (path.contains("header_separator") || path.contains("footer_separator")) {
                val theme = ensureTheme()
                val screenW = activeScreen?.width ?: w
                val insetX = if (w >= screenW - 4 && w > 340) min(12f, (w - 340) * 0.25f) else 0f
                val lx0 = x + insetX
                val lx1 = max(lx0 + 8f, x + w - insetX)
                val lineCenter = ModernGuiSurface.withAlpha(theme.primary, 0.42f)
                val lineEdge = ModernGuiSurface.withAlpha(theme.borderSubtle, 0.04f)
                val bounds = ctx.getBounds(lx0, y.toFloat(), lx1, y + 1.1f)
                ctx.guiRenderState.addGuiElement(
                    CssBoxGuiElementRenderState(
                        lx0, y.toFloat(), lx1, y + 1.0f,
                        0f, 0f, 0f, 0f,
                        lineEdge, lineCenter, lineEdge, lineCenter,
                        0, 0f, 0, 0f,
                        ctx.copyPosePooled(),
                        ctx.scissorStack.peek(),
                        bounds,
                    )
                )
                return true
            }

            if (path.endsWith("menu_background.png") || path.endsWith("inworld_menu_background.png")) {
                return onExtractTransparentBackground(ctx, activeScreen!!)
            }
        }

        return false
    }

    private fun drawMiniChevron(
        ctx: GuiGraphicsExtractor,
        theme: ResolvedGuiTheme,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rightArrow: Boolean,
        disabled: Boolean,
    ) {
        val glyphCol = ModernGuiSurface.withAlpha(
            if (disabled) theme.textSecondary else theme.textPrimary,
            if (disabled) 0.42f else 0.92f,
        )
        val cx = x + w * 0.5f
        val cy = y + h * 0.5f
        val dir = if (rightArrow) 1f else -1f
        val scissor = ctx.scissorStack.peek()
        // 5 crisp stepped micro-pixels forming `<` or `>`, each borrowing its own pooled pose for safe recycling
        for (step in -2..2) {
            val absStep = abs(step)
            val px = cx + dir * (1.1f - absStep * 0.95f) - 0.65f
            val py = cy + step * 0.95f - 0.55f
            val bounds = ctx.getBounds(px, py, px + 1.3f, py + 1.1f)
            ctx.guiRenderState.addGuiElement(
                CssBoxGuiElementRenderState(
                    px, py, px + 1.3f, py + 1.1f,
                    0.3f, 0.3f, 0.3f, 0.3f,
                    glyphCol, glyphCol, glyphCol, glyphCol,
                    0, 0f, 0, 0f,
                    ctx.copyPosePooled(),
                    scissor,
                    bounds,
                )
            )
        }
    }

    /**
     * Intercepts `GuiGraphicsExtractor.blitSprite` for widgets, slots, tooltips, hotbar, bossbars, and effect badges.
     */
    @JvmStatic
    fun onBlitSprite(
        ctx: GuiGraphicsExtractor,
        sprite: Identifier,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        color: Int,
    ): Boolean {
        if (!ModuleModernGui.running || reentrancyGuard.get() || w <= 0 || h <= 0) return false
        if (isExcludedScreen(activeScreen)) return false

        val alphaNorm = ((color ushr 24) and 0xFF) / 255f
        if (alphaNorm <= 0.01f) return false

        val path = sprite.path
        val theme = ensureTheme()

        reentrancyGuard.set(true)
        try {
            // 1. Tooltips (`tooltip/background` & `tooltip/frame`)
            if (ModuleModernGui.Targets.tooltips && path.startsWith("tooltip/")) {
                if (path.endsWith("frame")) {
                    // Suppress vanilla's 9-slice border frame; our SDF Fresnel/Monet rim already frames the card
                    return true
                }
                ModernGuiSurface.drawElevatedCard(
                    ctx = ctx,
                    theme = theme,
                    glassSetup = frameGlassSetup,
                    x = x - 1f,
                    y = y - 1f,
                    w = w + 2f,
                    h = h + 2f,
                    radius = ModuleModernGui.CardDesign.tooltipRadius,
                    opacityMultiplier = 1.24f,
                    shadowMultiplier = 1.05f,
                    accentMix = 0.22f,
                    refractionScale = 1.10f,
                    advanceStratum = true,
                )
                return true
            }

            // 2. Vanilla HUD Sprites (`hud/hotbar...`, `hud/effect_background...`, `boss_bar/...`)
            if (ModuleModernGui.Targets.vanillaHud) {
                when {
                    path == "hud/hotbar" -> {
                        ModernGuiSurface.drawElevatedCard(
                            ctx = ctx,
                            theme = theme,
                            glassSetup = frameGlassSetup,
                            x = x - 2f,
                            y = y - 1f,
                            w = w + 4f,
                            h = h + 2f,
                            radius = ModuleModernGui.CardDesign.widgetRadius + 2f,
                            opacityMultiplier = 1.02f,
                            shadowMultiplier = 0.85f,
                            advanceStratum = false,
                        )
                        // Subtle individual slot wells inside the hotbar dock
                        if (ModuleModernGui.Targets.containerSlots && w >= 180) {
                            for (i in 0 until 9) {
                                ModernGuiSurface.drawSlotWell(
                                    ctx = ctx,
                                    theme = theme,
                                    x = x + 2f + i * 20f,
                                    y = y + 2f,
                                    size = 18f,
                                    hovered = false,
                                    hasItem = false,
                                )
                            }
                        }
                        ctx.nextStratum()
                        return true
                    }
                    path == "hud/hotbar_selection" -> {
                        ModernGuiSurface.drawWidgetPill(
                            ctx = ctx,
                            theme = theme,
                            glassSetup = frameGlassSetup,
                            x = x + 2f,
                            y = y + 2f,
                            w = (w - 4).coerceAtLeast(16).toFloat(),
                            h = (h - 3).coerceAtLeast(16).toFloat(),
                            radius = ModuleModernGui.CardDesign.slotRadius + 1.5f,
                            hoveredOrFocused = true,
                            selected = true,
                            alphaScale = alphaNorm,
                            advanceStratum = true,
                        )
                        return true
                    }
                    path.startsWith("hud/hotbar_offhand") -> {
                        val isRight = path.endsWith("_right")
                        // Vanilla offhand sprite is 29x24 with 7px transparent spacer on the side facing the main hotbar;
                        // the 16x16 offhand item renders at `(x + 3, y + 4)` (left) or `(x + 10, y + 4)` (right).
                        val dockX = if (isRight && w >= 27) x + 7f else x.toFloat()
                        val dockW = if (w >= 27) 22f else (w - 4).coerceAtLeast(20).toFloat()
                        val dockH = (h - 2).coerceAtLeast(20).toFloat()
                        ModernGuiSurface.drawElevatedCard(
                            ctx = ctx,
                            theme = theme,
                            glassSetup = frameGlassSetup,
                            x = dockX,
                            y = y + 1f,
                            w = dockW,
                            h = dockH,
                            radius = ModuleModernGui.CardDesign.widgetRadius + 1f,
                            opacityMultiplier = 1.02f,
                            shadowMultiplier = 0.80f,
                            advanceStratum = false,
                        )
                        if (ModuleModernGui.Targets.containerSlots && dockW >= 20f && dockH >= 20f) {
                            ModernGuiSurface.drawSlotWell(
                                ctx = ctx,
                                theme = theme,
                                x = dockX + 2f,
                                y = y + 3f,
                                size = 18f,
                                hovered = false,
                                hasItem = false,
                            )
                        }
                        ctx.nextStratum()
                        return true
                    }
                    path.startsWith("hud/effect_background") -> {
                        val isAmbient = path.contains("ambient")
                        ModernGuiSurface.drawWidgetPill(
                            ctx = ctx,
                            theme = theme,
                            glassSetup = frameGlassSetup,
                            x = x.toFloat(),
                            y = y.toFloat(),
                            w = w.toFloat(),
                            h = h.toFloat(),
                            radius = ModuleModernGui.CardDesign.widgetRadius,
                            selected = isAmbient,
                            alphaScale = alphaNorm,
                            advanceStratum = true,
                        )
                        return true
                    }
                    path.startsWith("boss_bar/") -> {
                        val isBackground = path.endsWith("_background")
                        val r = h * 0.5f
                        if (isBackground) {
                            ModernGuiSurface.emitSurface(
                                ctx = ctx,
                                x = x.toFloat(), y = y - 1f, w = w.toFloat(), h = h + 2f,
                                rTL = r + 1f,
                                cTL = ModernGuiSurface.withAlpha(theme.surfaceLowest, ModuleModernGui.CardDesign.cardOpacity * 1.15f),
                                cBR = ModernGuiSurface.withAlpha(theme.surfaceLow, ModuleModernGui.CardDesign.cardOpacity),
                                borderColor = ModernGuiSurface.withAlpha(theme.borderSubtle, ModuleModernGui.CardDesign.borderOpacity * 0.7f),
                                borderWidth = 0.9f,
                                shadowColor = ModernGuiSurface.withAlpha(0xFF000000.toInt(), ModuleModernGui.CardDesign.shadowOpacity * 0.45f),
                                shadowSpread = 5f,
                                glassSetup = frameGlassSetup,
                                advanceStratum = false,
                            )
                        } else if (w > 1) {
                            val barColor = resolveBossBarColor(path, theme.primary)
                            val topFill = ModernGuiSurface.withAlpha(Material.mix(barColor, 0xFFFFFFFF.toInt(), 0.22f), 0.92f)
                            val botFill = ModernGuiSurface.withAlpha(barColor, 0.95f)
                            ModernGuiSurface.emitSurface(
                                ctx = ctx,
                                x = x.toFloat(), y = y - 0.5f, w = w.toFloat(), h = h + 1f,
                                rTL = r,
                                cTL = topFill, cTR = topFill, cBR = botFill, cBL = botFill,
                                borderColor = ModernGuiSurface.withAlpha(0xFFFFFFFF.toInt(), 0.45f),
                                borderWidth = 0.75f,
                                glassSetup = null,
                                advanceStratum = true,
                            )
                        }
                        return true
                    }
                }
            }

            // 3. Exact single slot background (`container/slot`, NOT empty-armor icons `container/slot/helmet` etc.)
            if (ModuleModernGui.Targets.containerSlots && path == "container/slot") {
                ModernGuiSurface.drawSlotWell(
                    ctx = ctx,
                    theme = theme,
                    x = x.toFloat(),
                    y = y.toFloat(),
                    size = w.toFloat(),
                    hovered = false,
                    hasItem = false,
                )
                ctx.nextStratum()
                return true
            }

            // 4. Interactive Widgets (`widget/...`, container interactive tiles, creative/recipe tabs & scrollers)
            if (ModuleModernGui.Targets.widgets) {
                if (handleWidgetSprite(ctx, path, x, y, w, h, alphaNorm, theme)) {
                    return true
                }
            }
        } finally {
            reentrancyGuard.set(false)
        }

        return false
    }

    private fun handleWidgetSprite(
        ctx: GuiGraphicsExtractor,
        path: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        alphaNorm: Float,
        theme: ResolvedGuiTheme,
    ): Boolean {
        val isHighlighted = path.contains("highlighted") || path.contains("focused")
        val isSelected = path.contains("selected")
        val isDisabled = path.contains("disabled")

        return when {
            // Standard Buttons, RecipeBook buttons, Beacon buttons, Enchantment rows, Stonecutter/Loom/Villager tiles
            path == "widget/button" ||
                path == "widget/button_highlighted" ||
                path == "widget/button_disabled" ||
                path.startsWith("recipe_book/button") ||
                path.startsWith("container/beacon/button") ||
                path.startsWith("container/enchanting_table/enchantment_slot") ||
                path.startsWith("container/stonecutter/recipe") ||
                path.startsWith("container/loom/pattern") ||
                path.startsWith("container/villager/trade") -> {
                ModernGuiSurface.drawWidgetPill(
                    ctx = ctx,
                    theme = theme,
                    glassSetup = frameGlassSetup,
                    x = x.toFloat(),
                    y = y.toFloat(),
                    w = w.toFloat(),
                    h = h.toFloat(),
                    radius = ModuleModernGui.CardDesign.widgetRadius,
                    hoveredOrFocused = isHighlighted,
                    selected = isSelected,
                    disabled = isDisabled,
                    alphaScale = alphaNorm,
                    advanceStratum = true,
                )
                true
            }

            // Slider track
            path == "widget/slider" || path == "widget/slider_highlighted" -> {
                ModernGuiSurface.emitSurface(
                    ctx = ctx,
                    x = x.toFloat(), y = y.toFloat(), w = w.toFloat(), h = h.toFloat(),
                    rTL = ModuleModernGui.CardDesign.widgetRadius,
                    cTL = ModernGuiSurface.withAlpha(theme.surfaceLowest, ModuleModernGui.CardDesign.cardOpacity * 0.95f * alphaNorm),
                    cBR = ModernGuiSurface.withAlpha(theme.surfaceLow, ModuleModernGui.CardDesign.cardOpacity * 1.05f * alphaNorm),
                    borderColor = ModernGuiSurface.withAlpha(
                        if (isHighlighted) theme.primary else theme.borderSubtle,
                        ModuleModernGui.CardDesign.borderOpacity * (if (isHighlighted) 1.15f else 0.7f) * alphaNorm,
                    ),
                    borderWidth = ModuleModernGui.CardDesign.borderWidth * 0.9f,
                    glassSetup = frameGlassSetup,
                    refractionScale = 0.55f,
                    layerOpacity = alphaNorm,
                    advanceStratum = false,
                )
                true
            }

            // Slider handle thumb
            path == "widget/slider_handle" || path == "widget/slider_handle_highlighted" -> {
                val pillR = min(ModuleModernGui.CardDesign.widgetRadius, w * 0.5f)
                ModernGuiSurface.drawWidgetPill(
                    ctx = ctx,
                    theme = theme,
                    glassSetup = frameGlassSetup,
                    x = x.toFloat(),
                    y = y + 1f,
                    w = w.toFloat(),
                    h = max(4, h - 2).toFloat(),
                    radius = pillR,
                    hoveredOrFocused = isHighlighted,
                    selected = true,
                    alphaScale = alphaNorm,
                    advanceStratum = true,
                )
                true
            }

            // EditBox text fields
            path == "widget/text_field" || path == "widget/text_field_highlighted" -> {
                ModernGuiSurface.drawWidgetPill(
                    ctx = ctx,
                    theme = theme,
                    glassSetup = frameGlassSetup,
                    x = x.toFloat(),
                    y = y.toFloat(),
                    w = w.toFloat(),
                    h = h.toFloat(),
                    radius = ModuleModernGui.CardDesign.widgetRadius,
                    hoveredOrFocused = isHighlighted,
                    selected = false,
                    alphaScale = alphaNorm,
                    advanceStratum = true,
                )
                true
            }

            // Tabs (`Options`/`CreateWorld` top tabs, `Creative` top/bottom tabs, `RecipeBook` left tabs, `Advancements` tabs)
            path.startsWith("widget/tab") ||
                path.startsWith("container/creative_inventory/tab_") ||
                path.startsWith("recipe_book/tab") ||
                path.startsWith("advancements/tab_") -> {
                val selectedTab = isSelected || path.endsWith("_selected")
                val tabEdge: ModernGuiSurface.TabEdge
                val tx: Float
                val ty: Float
                val tw: Float
                val th: Float

                when {
                    path.startsWith("container/creative_inventory/tab_top") -> {
                        // Vanilla top creative tab sprite (`26x32` at `y = topPos - 28`) overlaps `topPos` by 4px (`topPos - 28 .. topPos + 4`).
                        // Trim those 4 bottom overlap pixels so the tab ends flush at `topPos` without colliding with the container card!
                        tabEdge = ModernGuiSurface.TabEdge.TOP
                        tx = x + 1f
                        ty = if (selectedTab) y.toFloat() else y + 2f
                        tw = max(8, w - 2).toFloat()
                        th = max(8, if (selectedTab) h - 4 else h - 6).toFloat()
                    }
                    path.startsWith("container/creative_inventory/tab_bottom") -> {
                        // Vanilla bottom creative tab sprite (`26x32` at `y = topPos + 132`) overlaps `topPos + 136` by 4px (`topPos + 132 .. topPos + 164`).
                        // Shift down by 4px so the tab starts flush at `topPos + 136` with flat top corners and rounded bottom corners!
                        tabEdge = ModernGuiSurface.TabEdge.BOTTOM
                        tx = x + 1f
                        ty = y + 4f
                        tw = max(8, w - 2).toFloat()
                        th = max(8, if (selectedTab) h - 4 else h - 6).toFloat()
                    }
                    path.startsWith("recipe_book/tab") -> {
                        // RecipeBook category tabs (`35x27`) protrude from the LEFT edge of `recipe_book.png` and overlap `rbX` by 3..5px.
                        tabEdge = ModernGuiSurface.TabEdge.LEFT
                        tx = x + 1f
                        ty = y + 1f
                        tw = max(8, w - if (selectedTab) 4 else 6).toFloat()
                        th = max(8, h - 2).toFloat()
                    }
                    path.startsWith("advancements/tab_") -> {
                        tabEdge = when {
                            path.contains("below") -> ModernGuiSurface.TabEdge.BOTTOM
                            path.contains("left") -> ModernGuiSurface.TabEdge.LEFT
                            path.contains("right") -> ModernGuiSurface.TabEdge.RIGHT
                            else -> ModernGuiSurface.TabEdge.TOP
                        }
                        tx = x + 1f
                        ty = y + 1f
                        tw = max(8, w - 2).toFloat()
                        th = max(8, h - 2).toFloat()
                    }
                    else -> {
                        // `widget/tab*` (`TabNavigationBar` at top of Options / CreateWorld)
                        tabEdge = ModernGuiSurface.TabEdge.TOP
                        tx = x + 1f
                        ty = y + 1f
                        tw = max(8, w - 2).toFloat()
                        th = max(8, h - 1).toFloat()
                    }
                }

                ModernGuiSurface.drawWidgetPill(
                    ctx = ctx,
                    theme = theme,
                    glassSetup = frameGlassSetup,
                    x = tx,
                    y = ty,
                    w = tw,
                    h = th,
                    radius = min(ModuleModernGui.CardDesign.widgetRadius, 7f),
                    hoveredOrFocused = isHighlighted,
                    selected = selectedTab,
                    alphaScale = alphaNorm,
                    tabEdge = tabEdge,
                    advanceStratum = true,
                )
                true
            }

            // Scrollbar tracks & thumbs
            path.contains("scroller") -> {
                if (path.endsWith("scroller_background")) {
                    ModernGuiSurface.emitSurface(
                        ctx = ctx,
                        x = x + 1f, y = y.toFloat(), w = max(2f, w - 2f), h = h.toFloat(),
                        rTL = w * 0.5f,
                        cTL = ModernGuiSurface.withAlpha(theme.surfaceLowest, 0.35f * alphaNorm),
                        borderWidth = 0f,
                        glassSetup = null,
                        advanceStratum = false,
                    )
                } else {
                    val thumbCol = if (isDisabled) theme.borderSubtle else theme.primary
                    ModernGuiSurface.emitSurface(
                        ctx = ctx,
                        x = x + 1f, y = y.toFloat(), w = max(3f, w - 2f), h = h.toFloat(),
                        rTL = w * 0.5f,
                        cTL = ModernGuiSurface.withAlpha(thumbCol, (if (isDisabled) 0.35f else 0.82f) * alphaNorm),
                        borderColor = ModernGuiSurface.withAlpha(0xFFFFFFFF.toInt(), 0.35f * alphaNorm),
                        borderWidth = 0.6f,
                        glassSetup = null,
                        advanceStratum = true,
                    )
                }
                true
            }

            // RecipeBook popup overlay card
            path.startsWith("recipe_book/overlay_recipe") -> {
                ModernGuiSurface.drawElevatedCard(
                    ctx = ctx,
                    theme = theme,
                    glassSetup = frameGlassSetup,
                    x = x.toFloat(),
                    y = y.toFloat(),
                    w = w.toFloat(),
                    h = h.toFloat(),
                    radius = ModuleModernGui.CardDesign.tooltipRadius,
                    opacityMultiplier = 1.2f,
                    advanceStratum = true,
                )
                true
            }

            else -> false
        }
    }

    /**
     * Intercepts `GuiGraphicsExtractor.fill(x0, y0, x1, y1, color)` for Scoreboard, TabList, Chat,
     * ChatScreen input bar & CommandSuggestions, and Subtitles.
     */
    @JvmStatic
    fun onFillRect(
        ctx: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        color: Int,
    ): Boolean {
        if (!ModuleModernGui.running || reentrancyGuard.get()) return false
        val left = min(x0, x1)
        val top = min(y0, y1)
        val right = max(x0, x1)
        val bottom = max(y0, y1)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return false

        val alpha = (color ushr 24) and 0xFF
        if (alpha == 0) return false

        // 1. Slot hover 16x16 white highlight
        if (w == 16 && h == 16 && color == VANILLA_SLOT_HOVER_COLOR && ModuleModernGui.Targets.containerSlots &&
            !isExcludedScreen(activeScreen)
        ) {
            val theme = ensureTheme()
            val r = ModuleModernGui.CardDesign.slotRadius
            reentrancyGuard.set(true)
            try {
                ModernGuiSurface.emitSurface(
                    ctx = ctx,
                    x = left.toFloat(), y = top.toFloat(), w = 16f, h = 16f,
                    rTL = r,
                    cTL = ModernGuiSurface.withAlpha(theme.primary, 0.24f),
                    cBR = ModernGuiSurface.withAlpha(theme.primaryContainer, 0.14f),
                    borderColor = ModernGuiSurface.withAlpha(theme.primary, 0.72f),
                    borderWidth = 1.0f,
                    glassSetup = null,
                    advanceStratum = false,
                )
            } finally {
                reentrancyGuard.set(false)
            }
            return true
        }

        val region = currentRegion
        val theme = ensureTheme()

        reentrancyGuard.set(true)
        try {
            when (region) {
                HudRegion.SCOREBOARD -> {
                    if (!ModuleModernGui.Targets.vanillaHud) return false
                    val r = ModuleModernGui.CardDesign.hudCardRadius
                    val isHeader = lastScoreboardHeaderBottom == -9999 && h in 7..18
                    if (isHeader) {
                        lastScoreboardHeaderBottom = bottom
                        lastScoreboardLeft = left
                        ModernGuiSurface.emitSurface(
                            ctx = ctx,
                            x = left - 3f, y = top - 2f, w = w + 6f, h = h + 2f,
                            rTL = r, rTR = r, rBR = 0f, rBL = 0f,
                            cTL = ModernGuiSurface.withAlpha(
                                Material.mix(theme.surfaceHigh, theme.primaryContainer, 0.45f),
                                (ModuleModernGui.CardDesign.cardOpacity * 1.15f).coerceIn(0.14f, 0.94f),
                            ),
                            cBR = ModernGuiSurface.withAlpha(
                                Material.mix(theme.surface, theme.primaryContainer, 0.28f),
                                ModuleModernGui.CardDesign.cardOpacity,
                            ),
                            borderColor = ModernGuiSurface.withAlpha(theme.primary, ModuleModernGui.CardDesign.borderOpacity * 0.75f),
                            borderWidth = ModuleModernGui.CardDesign.borderWidth * 0.9f,
                            shadowColor = ModernGuiSurface.withAlpha(0xFF000000.toInt(), ModuleModernGui.CardDesign.shadowOpacity * 0.65f),
                            shadowSpread = ModuleModernGui.CardDesign.shadowSpread * 0.75f,
                            glassSetup = frameGlassSetup,
                            advanceStratum = true,
                        )
                    } else {
                        val attachedToHeader = absDelta(top, lastScoreboardHeaderBottom) <= 2 &&
                            absDelta(left, lastScoreboardLeft) <= 2
                        val topR = if (attachedToHeader) 0f else r
                        ModernGuiSurface.emitSurface(
                            ctx = ctx,
                            x = left - 3f,
                            y = top.toFloat(),
                            w = w + 6f,
                            h = h + 3f,
                            rTL = topR, rTR = topR, rBR = r, rBL = r,
                            cTL = ModernGuiSurface.withAlpha(theme.surface, ModuleModernGui.CardDesign.cardOpacity),
                            cBR = ModernGuiSurface.withAlpha(theme.surfaceLow, (ModuleModernGui.CardDesign.cardOpacity * 1.08f).coerceAtMost(0.95f)),
                            borderColor = ModernGuiSurface.withAlpha(theme.borderSubtle, ModuleModernGui.CardDesign.borderOpacity * 0.70f),
                            borderWidth = ModuleModernGui.CardDesign.borderWidth * 0.9f,
                            shadowColor = ModernGuiSurface.withAlpha(0xFF000000.toInt(), ModuleModernGui.CardDesign.shadowOpacity * 0.70f),
                            shadowSpread = ModuleModernGui.CardDesign.shadowSpread * 0.80f,
                            glassSetup = frameGlassSetup,
                            advanceStratum = true,
                        )
                    }
                    return true
                }

                HudRegion.TAB_LIST -> {
                    if (!ModuleModernGui.Targets.vanillaHud) return false
                    // Outer TabList frame vs inner header/grid/footer wells (`0x80000000`); per-player rows use `onRenderTabPlayerRow`
                    if (w >= 48 && h >= 8 && (color and 0x00FFFFFF) == 0) {
                        if (!tabListOuterCardDrawn) {
                            tabListOuterCardDrawn = true
                            ModernGuiSurface.drawElevatedCard(
                                ctx = ctx,
                                theme = theme,
                                glassSetup = frameGlassSetup,
                                x = left - 3f,
                                y = top - 2f,
                                w = w + 6f,
                                h = h + 4f,
                                radius = ModuleModernGui.CardDesign.panelRadius,
                                opacityMultiplier = 1.05f,
                                shadowMultiplier = 0.85f,
                                advanceStratum = true,
                            )
                        } else {
                            // Inner header/body/footer section inside the outer TabList card — exact bounds so sections never overlap
                            ModernGuiSurface.drawRecessedWell(
                                ctx = ctx,
                                theme = theme,
                                x = left.toFloat(),
                                y = top.toFloat(),
                                w = w.toFloat(),
                                h = h.toFloat(),
                                radius = min(ModuleModernGui.CardDesign.widgetRadius, 5f),
                                opacityScale = 0.70f,
                            )
                            ctx.nextStratum()
                        }
                        return true
                    }
                }

                HudRegion.CHAT -> {
                    if (!ModuleModernGui.Targets.chatAndSuggestions) return false
                    // Suppress vanilla per-line dark background strips (`w >= 40`) while recording live fade bounds;
                    // keep 2px message-tag indicator bars untouched!
                    if (w >= 40 && (color and 0x00FFFFFF) == 0) {
                        val screenBounds = ctx.getBounds(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                        if (screenBounds.width > 0 && screenBounds.height > 0) {
                            hudChatAccumMinX = min(hudChatAccumMinX, screenBounds.left())
                            hudChatAccumMinY = min(hudChatAccumMinY, screenBounds.top())
                            hudChatAccumMaxX = max(hudChatAccumMaxX, screenBounds.left() + screenBounds.width)
                            hudChatAccumMaxY = max(hudChatAccumMaxY, screenBounds.top() + screenBounds.height)
                            hudChatAccumMaxAlpha = max(hudChatAccumMaxAlpha, alpha)
                        }
                        return true
                    }
                }

                HudRegion.SUBTITLES -> {
                    if (!ModuleModernGui.Targets.vanillaHud) return false
                    if (w >= 24 && h >= 8) {
                        ModernGuiSurface.drawElevatedCard(
                            ctx = ctx,
                            theme = theme,
                            glassSetup = frameGlassSetup,
                            x = left - 3f,
                            y = top - 2f,
                            w = w + 6f,
                            h = h + 4f,
                            radius = ModuleModernGui.CardDesign.hudCardRadius,
                            opacityMultiplier = 1.0f,
                            shadowMultiplier = 0.70f,
                            advanceStratum = true,
                        )
                        return true
                    }
                }

                else -> {}
            }

            // ChatScreen bottom input bar & CommandSuggestions popup cards (only outside HUD regions so chat scrollbars/tags stay intact)
            if (region == HudRegion.NONE && activeScreen is ChatScreen && ModuleModernGui.Targets.chatAndSuggestions) {
                val guiW = ctx.guiWidth()
                val guiH = ctx.guiHeight()

                // Bottom Chat input bar (`fill(2, height - 14, width - 2, height - 2, ...)`)
                if (top >= guiH - 20 && w >= guiW - 24 && h in 10..20) {
                    ModernGuiSurface.drawWidgetPill(
                        ctx = ctx,
                        theme = theme,
                        glassSetup = frameGlassSetup,
                        x = left.toFloat(),
                        y = top - 1f,
                        w = w.coerceAtLeast(40).toFloat(),
                        h = h + 2f,
                        radius = ModuleModernGui.CardDesign.widgetRadius,
                        hoveredOrFocused = true,
                        selected = false,
                        advanceStratum = true,
                    )
                    return true
                }

                // Suppress 1px Brigadier CommandSuggestions dashed border segments (`0xFFD0D0D0`, i.e. `-3092272`)
                if ((w == 1 || h == 1) && top < guiH - 18 && (color == -3092272 || (color and 0x00FFFFFF) == 0xD0D0D0)) {
                    return true
                }

                // CommandSuggestions autocomplete list card / usage tooltip (`rgb == 0`) or selected suggestion row (`rgb != 0 && h <= 13`).
                // Preserve translucent colored overlays such as `ModuleBetterChat` copy-hover highlight (`0x4422AAFF`, `rgb != 0 && h > 13`)!
                if (w >= 20 && h >= 9 && top < guiH - 16) {
                    val rgb = color and 0x00FFFFFF
                    if (rgb != 0 && h > 13) {
                        return false
                    }
                    if (rgb != 0) {
                        ModernGuiSurface.emitSurface(
                            ctx = ctx,
                            x = left.toFloat(), y = top.toFloat(), w = w.toFloat(), h = h.toFloat(),
                            rTL = min(4f, ModuleModernGui.CardDesign.tooltipRadius * 0.5f),
                            cTL = ModernGuiSurface.withAlpha(theme.primary, 0.30f),
                            borderColor = ModernGuiSurface.withAlpha(theme.primary, 0.65f),
                            borderWidth = 0.8f,
                            glassSetup = null,
                            advanceStratum = true,
                        )
                    } else {
                        ModernGuiSurface.drawElevatedCard(
                            ctx = ctx,
                            theme = theme,
                            glassSetup = frameGlassSetup,
                            x = left - 3f,
                            y = top - 2f,
                            w = w + 6f,
                            h = h + 4f,
                            radius = ModuleModernGui.CardDesign.tooltipRadius,
                            opacityMultiplier = 1.18f,
                            shadowMultiplier = 0.90f,
                            advanceStratum = true,
                        )
                    }
                    return true
                }
            }
        } finally {
            reentrancyGuard.set(false)
        }

        return false
    }

    /**
     * Intercepts `GuiGraphicsExtractor.fillGradient(x0, y0, x1, y1, colorFrom, colorTo)`.
     */
    @JvmStatic
    fun onFillGradient(
        ctx: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        colorFrom: Int,
        colorTo: Int,
    ): Boolean {
        if (!ModuleModernGui.running || reentrancyGuard.get()) return false
        val w = kotlin.math.abs(x1 - x0)
        val h = kotlin.math.abs(y1 - y0)

        // Vanilla 16x16 white slot-hover gradient (`AbstractContainerScreen.renderSlotHighlightFront` / `ViewedInventoryScreen`)
        if (w == 16 && h == 16 && colorFrom == VANILLA_SLOT_HOVER_COLOR && colorTo == VANILLA_SLOT_HOVER_COLOR) {
            return onFillRect(ctx, x0, y0, x1, y1, colorFrom)
        }

        return false
    }

    /**
     * Upgrades individual player rows inside `PlayerTabOverlay` into rounded Monet/Glass pills while preserving
     * `ModuleBetterTab` custom self/friend/staff highlight colours.
     */
    @JvmStatic
    fun onRenderTabPlayerRow(
        ctx: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        drawColor: Int,
    ): Boolean {
        if (!ModuleModernGui.running || !ModuleModernGui.Targets.vanillaHud || reentrancyGuard.get()) {
            return false
        }
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 2 || h <= 2) return false

        val theme = ensureTheme()
        val hasCustomHighlight = (drawColor and 0x00FFFFFF) != 0x00FFFFFF && (drawColor ushr 24) > 0x24
        val fillRgb = if (hasCustomHighlight) {
            Material.mix(theme.surfaceHigh, drawColor or (0xFF shl 24), 0.65f)
        } else {
            theme.surfaceHigh
        }
        val fillAlpha = if (hasCustomHighlight) 0.52f else (ModuleModernGui.CardDesign.slotOpacity * 0.72f).coerceIn(0.12f, 0.55f)
        val borderCol = if (hasCustomHighlight) {
            ModernGuiSurface.withAlpha(drawColor or (0xFF shl 24), 0.70f)
        } else {
            ModernGuiSurface.withAlpha(theme.borderSubtle, 0.28f)
        }

        reentrancyGuard.set(true)
        try {
            ModernGuiSurface.emitSurface(
                ctx = ctx,
                x = x0.toFloat(),
                y = y0.toFloat(),
                w = w.toFloat(),
                h = h.toFloat(),
                rTL = min(3.5f, h * 0.45f),
                cTL = ModernGuiSurface.withAlpha(fillRgb, fillAlpha),
                borderColor = borderCol,
                borderWidth = 0.65f,
                glassSetup = null,
                advanceStratum = true,
            )
        } finally {
            reentrancyGuard.set(false)
        }
        return true
    }

    private fun resolveBossBarColor(path: String, fallbackPrimary: Int): Int = when {
        "pink" in path -> 0xFFF472B6.toInt()
        "blue" in path -> 0xFF60A5FA.toInt()
        "red" in path -> 0xFFF87171.toInt()
        "green" in path -> 0xFF4ADE80.toInt()
        "yellow" in path -> 0xFFFACC15.toInt()
        "purple" in path -> 0xFFC084FC.toInt()
        "white" in path -> 0xFFF1F5F9.toInt()
        else -> fallbackPrimary
    }

    private fun absDelta(a: Int, b: Int): Int = kotlin.math.abs(a - b)
}
