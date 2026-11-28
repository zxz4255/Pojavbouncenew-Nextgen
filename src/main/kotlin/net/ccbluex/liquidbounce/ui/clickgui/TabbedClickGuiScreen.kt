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
package net.ccbluex.liquidbounce.ui.clickgui

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.features.global.GlobalManager
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHud
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassSurface
import net.ccbluex.liquidbounce.ui.clickgui.setting.SettingOverlayManager
import net.ccbluex.liquidbounce.ui.clickgui.world3d.World3dClickGuiStage
import net.ccbluex.liquidbounce.ui.common.UiAnimation
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.ui.common.UiLocalization
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.ccbluex.liquidbounce.ui.common.UiTooltipManager
import net.ccbluex.liquidbounce.ui.hud.HudComponentManager
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.PreeditEvent
import net.minecraft.network.chat.Component

/**
 * Helper queried by `MixinMinecraft`, `MixinLocalPlayer`, `ModuleHud`, and `ModuleAutoQueue`.
 */
object NativeScreens {
    @JvmStatic
    fun isClientScreen(screen: Screen?): Boolean =
        screen is TabbedClickGuiScreen
}

/**
 * Unified native `TabbedClickGui.svelte` screen hosting:
 * - Tab 0: `ClickGUI` ([ClickGuiTabView])
 * - Tab 1: `HUD Editor` ([HudEditorTabView])
 * - Tab 2: `Settings` ([GlobalSettingsTabView])
 * plus the floating top pill `Tabs.svelte` bar (`ClickGUI`, `HUD Editor`, `Settings`).
 */
class TabbedClickGuiScreen(initialTab: Int? = null) :
    Screen(Component.literal("ClickGUI")),
    World3dClickGuiStage.TabbedScreenBridge {

    companion object {
        private const val TAB_COUNT = 3
        private fun tabTitle(index: Int): String = when (index) {
            0 -> UiLocalization.text("ClickGUI", "模块界面")
            1 -> UiLocalization.text("HUD Editor", "HUD 编辑器")
            else -> UiLocalization.text("Settings", "全局设置")
        }
    }

    private val clickGuiTab = ClickGuiTabView()
    private val hudEditorTab = HudEditorTabView()
    private val globalSettingsTab = GlobalSettingsTabView()
    private val world3dStage = World3dClickGuiStage(
        onSwitchTab = { nextTab -> switchTab(nextTab) },
        onLanguageToggled = {
            UiLocalization.toggleChineseMode()
            val s = contentScale()
            clickGuiTab.onLanguageOrFormatChanged(width / s, height / s)
            world3dStageOnLang()
            ConfigSystem.store(ModuleManager.modulesConfig)
            ConfigSystem.store(GlobalManager)
        },
    )
    private var wasWorld3dActive: Boolean = false
    private val dtTracker = UiAnimation.DeltaTracker()
    private val tabRects = Array(TAB_COUNT) { FloatArray(4) }
    private val langBtnRect = FloatArray(4)

    override fun asMinecraftScreen(): Screen = this

    private fun world3dStageOnLang() {
        world3dStage.onLanguageChanged()
    }

    init {
        ClickGuiLayoutStore.ensureLoaded()
        if (initialTab != null) {
            ClickGuiLayoutStore.activeTabIndex = initialTab.coerceIn(0, 2)
        }
        ModuleHud.hudEditorSelected = (ClickGuiLayoutStore.activeTabIndex == 1)
    }

    override fun init() {
        super.init()
        val activeTab = ClickGuiLayoutStore.activeTabIndex.coerceIn(0, 2)
        if (ModuleClickGui.isWorld3dStyle) {
            wasWorld3dActive = true
            world3dStage.onOpen()
        } else if (activeTab == 0 && ModuleClickGui.searchBarAutoFocusValue && SettingOverlayManager.activeTextInput == null) {
            SettingOverlayManager.focusTextInput(clickGuiTab.searchEditor, selectAll = false)
        }
        SettingOverlayManager.syncNativeTextInputFocus(this)
    }

    fun isTypingIntoSetting(): Boolean = SettingOverlayManager.isCapturingKeyboard

    override fun isPauseScreen(): Boolean = false

    /**
     * Suppress vanilla dark gradient/menu blur: we draw our own clean `043` backdrop darken
     * (disabled on `HUD Editor` tab just like `$darken = false` in `HudEditor.svelte`).
     */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        // Intentionally empty; handled inside `extractRenderState`
    }

    /**
     * Exact 043 `ScaledClickGuiContent.svelte` scale in MC GUI units:
     * `guiUnitsPerThemePx = 0.5f * ModuleClickGui.scaleValue`.
     */
    private fun contentScale(): Float = (0.5f * ModuleClickGui.scaleValue).coerceIn(0.2f, 2f)

    /**
     * Top `Tabs.svelte` bar is outside `<ScaledClickGuiContent>` in 043, rendered at HUD base scale (`0.5f` GUI units/px).
     */
    private fun tabsScale(): Float = 0.5f

    private fun isShiftCurrentlyDown(): Boolean {
        return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) ||
            InputConstants.isKeyDown(InputConstants.KEY_RSHIFT)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        HudComponentManager.syncPaletteFromTheme()
        val dt = dtTracker.nextDeltaSeconds()
        val shiftHeld = isShiftCurrentlyDown()
        val activeTab = ClickGuiLayoutStore.activeTabIndex.coerceIn(0, 2)
        ModuleHud.hudEditorSelected = (activeTab == 1)

        val glassMode = ClickGuiGlassSurface.beginFrame()
        try {
            val useWorld3d = activeTab == 0 && ModuleClickGui.isWorld3dStyle
            if (useWorld3d && !wasWorld3dActive) {
                wasWorld3dActive = true
                world3dStage.onOpen()
            } else if (!useWorld3d && wasWorld3dActive) {
                wasWorld3dActive = false
            }

            if (useWorld3d) {
                UiDraw.currentCanvasScale = 1f
                UiTooltipManager.beginFrame()
                world3dStage.render(graphics, width, height, mouseX.toFloat(), mouseY.toFloat(), dt)
                return
            }

            // Subtle backdrop glaze when not in HUD Editor (`TabbedClickGui.svelte` `$darken`)
            if (activeTab != 1) {
                UiDraw.currentCanvasScale = 1f
                val backdropCol = when {
                    ModuleClickGui.isLightPollutionGlass || ModuleClickGui.isGaussianGlass ->
                        UiPalette.packArgb(16, 0, 0, 0)
                    glassMode -> UiPalette.packArgb(32, 6, 10, 18)
                    else -> UiPalette.black(0.32f)
                }
                UiDraw.fillBox(graphics, 0f, 0f, width.toFloat(), height.toFloat(), backdropCol)
            }

            UiTooltipManager.beginFrame()

            when (activeTab) {
                0 -> {
                    val s = contentScale()
                    val canvasW = width / s
                    val canvasH = height / s
                    val cmx = mouseX / s
                    val cmy = mouseY / s

                    UiDraw.currentCanvasScale = s
                    graphics.pose().pushMatrix()
                    graphics.pose().scale(s, s)

                    clickGuiTab.render(graphics, canvasW, canvasH, cmx, cmy, dt, shiftHeld)
                    SettingOverlayManager.renderDropdownOverlay(graphics, cmx, cmy, canvasW, canvasH, dt)
                    UiTooltipManager.render(graphics)

                    graphics.pose().popMatrix()
                    UiDraw.currentCanvasScale = 1f
                }
                1 -> {
                    run {
                        hudEditorTab.render(graphics, a, mouseX.toFloat(), mouseY.toFloat(), dt, shiftHeld)
                    }
                    val hs = tabsScale()
                    val hudW = width / hs
                    val hudH = height / hs
                    val hmx = mouseX / hs
                    val hmy = mouseY / hs
                    UiDraw.currentCanvasScale = hs
                    graphics.pose().pushMatrix()
                    graphics.pose().scale(hs, hs)
                    SettingOverlayManager.renderDropdownOverlay(graphics, hmx, hmy, hudW, hudH, dt)
                    UiTooltipManager.render(graphics)
                    graphics.pose().popMatrix()
                    UiDraw.currentCanvasScale = 1f
                }
                2 -> {
                    val s = contentScale()
                    val canvasW = width / s
                    val canvasH = height / s
                    val cmx = mouseX / s
                    val cmy = mouseY / s

                    UiDraw.currentCanvasScale = s
                    graphics.pose().pushMatrix()
                    graphics.pose().scale(s, s)

                    globalSettingsTab.render(graphics, canvasW, canvasH, cmx, cmy, dt)
                    SettingOverlayManager.renderDropdownOverlay(graphics, cmx, cmy, canvasW, canvasH, dt)
                    UiTooltipManager.render(graphics)

                    graphics.pose().popMatrix()
                    UiDraw.currentCanvasScale = 1f
                }
            }

            // Render floating top pill `Tabs.svelte` (`top: 15px; padding: 6px; gap: 5px; border-radius: 999px`)
            renderTopTabsPill(graphics, mouseX.toFloat(), mouseY.toFloat(), activeTab)
        } finally {
            ClickGuiGlassSurface.endFrame()
        }
    }

    private fun renderTopTabsPill(
        ctx: GuiGraphicsExtractor,
        guiMouseX: Float,
        guiMouseY: Float,
        activeTab: Int,
    ) {
        val ts = tabsScale()
        val vw = width / ts
        val mx = guiMouseX / ts
        val my = guiMouseY / ts

        UiDraw.currentCanvasScale = ts
        ctx.pose().pushMatrix()
        ctx.pose().scale(ts, ts)

        val padOuter = 6f
        val gap = 5f
        val btnPadX = 14f
        val btnH = 30f
        val pillH = btnH + padOuter * 2f
        val pillY = 15f

        val titles = Array(TAB_COUNT) { idx -> tabTitle(idx) }
        val langLabel = if (UiLocalization.isChinese) "中文" else "EN"
        val btnWidths = FloatArray(TAB_COUNT) { idx ->
            UiDraw.measureText(titles[idx], 14f, UiFontWeight.SEMIBOLD) + btnPadX * 2f
        }
        val langBtnW = UiDraw.measureText(langLabel, 13f, UiFontWeight.SEMIBOLD) + 22f
        val sepGap = 9f
        val pillW = padOuter * 2f + btnWidths.sum() + gap * (TAB_COUNT - 1) + sepGap + langBtnW
        val pillX = (vw - pillW) * 0.5f

        val glassMode = ClickGuiGlassSurface.isActive
        if (glassMode) {
            ClickGuiGlassSurface.drawElevatedPanel(
                ctx = ctx,
                x = pillX, y = pillY, w = pillW, h = pillH,
                rTL = pillH * 0.5f,
                densityBoost = 1.05f,
                shadowSpread = 14f,
                shadowAlpha = 0.52f,
                accentRimMix = 0.25f,
                rimAlpha = 0.54f,
                refractionScale = 1.1f,
            )
        } else {
            UiDraw.drawSoftShadow(ctx, pillX, pillY, pillW, pillH, radius = pillH * 0.5f, spread = 8f, maxAlpha = 0.42f)
            UiDraw.fillBox(ctx, pillX, pillY, pillW, pillH, UiPalette.tabsPillBg, radius = pillH * 0.5f)
        }

        var cursorX = pillX + padOuter
        val btnY = pillY + padOuter
        val lh = UiDraw.lineHeight(14f, UiFontWeight.SEMIBOLD)

        for (i in 0 until TAB_COUNT) {
            val bw = btnWidths[i]
            tabRects[i][0] = cursorX
            tabRects[i][1] = btnY
            tabRects[i][2] = bw
            tabRects[i][3] = btnH

            val hovered = mx in cursorX..(cursorX + bw) && my in btnY..(btnY + btnH)
            val isActive = i == activeTab
            if (glassMode) {
                if (isActive) {
                    ClickGuiGlassSurface.drawAccentCapsule(
                        ctx = ctx,
                        x = cursorX, y = btnY, w = bw, h = btnH,
                        radius = btnH * 0.5f,
                        accentMix = 0.86f,
                        tintDensity = 0.48f,
                        rimAlpha = 0.84f,
                        shadowSpread = 6f,
                        shadowAlpha = 0.34f,
                    )
                } else if (hovered) {
                    ClickGuiGlassSurface.drawAccentCapsule(
                        ctx = ctx,
                        x = cursorX, y = btnY, w = bw, h = btnH,
                        radius = btnH * 0.5f,
                        accentMix = 0.12f,
                        tintDensity = 0.28f,
                        layerOpacity = 0.75f,
                        rimAlpha = 0.42f,
                        shadowSpread = 0f,
                    )
                }
            } else {
                if (isActive) {
                    UiDraw.fillBox(
                        ctx, cursorX, btnY, bw, btnH,
                        UiPalette.tabActiveBg,
                        radius = btnH * 0.5f,
                        borderColor = UiPalette.tabActiveBorder,
                        borderWidth = 1f,
                    )
                } else if (hovered) {
                    UiDraw.fillBox(ctx, cursorX, btnY, bw, btnH, UiPalette.tabHoverBg, radius = btnH * 0.5f)
                }
            }

            val textCol = when {
                isActive || hovered -> UiPalette.TEXT_COLOR
                else -> UiPalette.TEXT_DIMMED_COLOR
            }
            UiDraw.drawText(
                ctx, titles[i],
                cursorX + btnPadX,
                btnY + (btnH - lh) * 0.5f,
                14f, textCol, UiFontWeight.SEMIBOLD,
                shadow = glassMode,
            )
            cursorX += bw + gap
        }

        // Subtle divider + quick Chinese/English toggle pill
        val divX = cursorX - gap + sepGap * 0.5f - 0.5f
        UiDraw.fillBox(ctx, divX, btnY + 6f, 1f, btnH - 12f, UiPalette.white(0.14f))
        val langX = cursorX - gap + sepGap
        langBtnRect[0] = langX
        langBtnRect[1] = btnY
        langBtnRect[2] = langBtnW
        langBtnRect[3] = btnH
        val langHovered = mx in langX..(langX + langBtnW) && my in btnY..(btnY + btnH)
        val zhActive = UiLocalization.isChinese
        if (glassMode) {
            if (zhActive || langHovered) {
                ClickGuiGlassSurface.drawAccentCapsule(
                    ctx = ctx,
                    x = langX, y = btnY, w = langBtnW, h = btnH,
                    radius = btnH * 0.5f,
                    accentMix = if (zhActive) 0.56f else 0.16f,
                    tintDensity = if (zhActive) 0.36f else 0.26f,
                    rimAlpha = if (zhActive) 0.68f else 0.42f,
                    shadowSpread = if (zhActive) 4f else 0f,
                )
            }
        } else if (zhActive) {
            UiDraw.fillBox(
                ctx, langX, btnY, langBtnW, btnH,
                UiPalette.accent(0.22f),
                radius = btnH * 0.5f,
                borderColor = UiPalette.accent(0.55f),
                borderWidth = 1f,
            )
        } else if (langHovered) {
            UiDraw.fillBox(ctx, langX, btnY, langBtnW, btnH, UiPalette.tabHoverBg, radius = btnH * 0.5f)
        }
        val langLh = UiDraw.lineHeight(13f, UiFontWeight.SEMIBOLD)
        val langTw = UiDraw.measureText(langLabel, 13f, UiFontWeight.SEMIBOLD)
        val langTextCol = if (zhActive || langHovered) UiPalette.TEXT_COLOR else UiPalette.TEXT_DIMMED_COLOR
        UiDraw.drawText(
            ctx, langLabel,
            langX + (langBtnW - langTw) * 0.5f,
            btnY + (btnH - langLh) * 0.5f,
            13f, langTextCol, UiFontWeight.SEMIBOLD,
            shadow = glassMode,
        )

        ctx.pose().popMatrix()
        UiDraw.currentCanvasScale = 1f
    }

    private fun switchTab(nextTab: Int) {
        val clamped = nextTab.coerceIn(0, 2)
        if (clamped == ClickGuiLayoutStore.activeTabIndex) return
        SettingOverlayManager.resetAll()
        ClickGuiLayoutStore.activeTabIndex = clamped
        ModuleHud.hudEditorSelected = (clamped == 1)
        ClickGuiLayoutStore.save()
        if (clamped == 0 && ModuleClickGui.searchBarAutoFocusValue) {
            val editor = if (ModuleClickGui.isWorld3dStyle) world3dStage.searchEditor else clickGuiTab.searchEditor
            SettingOverlayManager.focusTextInput(editor, selectAll = false)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val gx = event.x().toFloat()
        val gy = event.y().toFloat()
        val button = event.button()
        val shiftHeld = event.hasShiftDown()

        // 0. Active key/mouse bind capture
        if (SettingOverlayManager.handleMouseCapture(button)) {
            return true
        }

        val activeTab = ClickGuiLayoutStore.activeTabIndex.coerceIn(0, 2)
        if (activeTab == 0 && ModuleClickGui.isWorld3dStyle) {
            return world3dStage.mouseClicked(gx, gy, button, shiftHeld, this)
        }

        // 1. Top `Tabs` pill check
        val ts = tabsScale()
        val tmx = gx / ts
        val tmy = gy / ts
        for (i in tabRects.indices) {
            val r = tabRects[i]
            if (tmx in r[0]..(r[0] + r[2]) && tmy in r[1]..(r[1] + r[3])) {
                if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                    switchTab(i)
                }
                return true
            }
        }
        if (tmx in langBtnRect[0]..(langBtnRect[0] + langBtnRect[2]) &&
            tmy in langBtnRect[1]..(langBtnRect[1] + langBtnRect[3])
        ) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                UiLocalization.toggleChineseMode()
                val s = contentScale()
                clickGuiTab.onLanguageOrFormatChanged(width / s, height / s)
                ConfigSystem.store(ModuleManager.modulesConfig)
                ConfigSystem.store(GlobalManager)
            }
            return true
        }

        val activeScale = if (activeTab == 1) tabsScale() else contentScale()
        val cmx = gx / activeScale
        val cmy = gy / activeScale
        val canvasW = width / activeScale
        val canvasH = height / activeScale

        // 2. Top-level Dropdown portal check
        if (SettingOverlayManager.mouseClickedDropdown(cmx, cmy, button, canvasH)) {
            ConfigSystem.store(ModuleManager.modulesConfig)
            return true
        }

        // Unfocus any active text field before routing click (re-focused if user clicked inside it)
        SettingOverlayManager.clearTextInputFocus(commit = true, screen = this)

        return when (activeTab) {
            0 -> clickGuiTab.mouseClicked(cmx, cmy, button, shiftHeld, canvasW, canvasH)
            1 -> hudEditorTab.mouseClicked(gx, gy, button, shiftHeld)
            2 -> globalSettingsTab.mouseClicked(cmx, cmy, button, shiftHeld, canvasW, canvasH)
            else -> false
        }
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val gx = event.x().toFloat()
        val gy = event.y().toFloat()
        val button = event.button()
        val shiftHeld = event.hasShiftDown()
        val activeTab = ClickGuiLayoutStore.activeTabIndex.coerceIn(0, 2)
        if (activeTab == 0 && ModuleClickGui.isWorld3dStyle) {
            return world3dStage.mouseDragged(gx, gy, button)
        }
        val s = contentScale()

        return when (activeTab) {
            0 -> clickGuiTab.mouseDragged(gx / s, gy / s, button, shiftHeld, width / s, height / s)
            1 -> hudEditorTab.mouseDragged(gx, gy, button, shiftHeld)
            2 -> globalSettingsTab.mouseDragged(gx / s, gy / s, button)
            else -> false
        }
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val gx = event.x().toFloat()
        val gy = event.y().toFloat()
        val button = event.button()
        world3dStage.mouseReleased(gx, gy, button)
        val s = contentScale()
        clickGuiTab.mouseReleased(gx / s, gy / s, button)
        hudEditorTab.mouseReleased(gx, gy, button)
        globalSettingsTab.mouseReleased(gx / s, gy / s, button)
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        val activeTab = ClickGuiLayoutStore.activeTabIndex.coerceIn(0, 2)
        if (activeTab == 0 && ModuleClickGui.isWorld3dStyle) {
            return world3dStage.mouseScrolled(x.toFloat(), y.toFloat(), scrollY)
        }
        val activeScale = if (activeTab == 1) tabsScale() else contentScale()
        val cmx = (x / activeScale).toFloat()
        val cmy = (y / activeScale).toFloat()
        val canvasW = width / activeScale
        val canvasH = height / activeScale

        if (SettingOverlayManager.mouseScrolledDropdown(cmx, cmy, scrollY, canvasH)) {
            return true
        }

        return when (activeTab) {
            0 -> clickGuiTab.mouseScrolled(cmx, cmy, scrollY, canvasW, canvasH)
            1 -> hudEditorTab.mouseScrolled(x.toFloat(), y.toFloat(), scrollY)
            2 -> globalSettingsTab.mouseScrolled(cmx, cmy, scrollY, canvasW, canvasH)
            else -> false
        }
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        // 1. Keybind / KeySetting capture has highest priority (including ESC to unbind!)
        if (SettingOverlayManager.activeKeyCapture != null) {
            return SettingOverlayManager.handleKeyPressed(event)
        }

        val inWorld3d = ClickGuiLayoutStore.activeTabIndex == 0 && ModuleClickGui.isWorld3dStyle
        if (inWorld3d) {
            if (event.key() == InputConstants.KEY_ESCAPE && SettingOverlayManager.hasOpenDropdown) {
                SettingOverlayManager.closeDropdown()
                return true
            }
            if (world3dStage.handleKeyPressed(event)) {
                return true
            }
            if (event.key() == InputConstants.KEY_ESCAPE) {
                this.onClose()
                return true
            }
            if (SettingOverlayManager.handleKeyPressed(event)) {
                return true
            }
            return super.keyPressed(event)
        }

        // 2. Ctrl+F focuses ClickGUI search bar immediately
        if (ClickGuiLayoutStore.activeTabIndex == 0 &&
            event.key() == InputConstants.KEY_F &&
            event.hasControlDownWithQuirk()
        ) {
            SettingOverlayManager.focusTextInput(clickGuiTab.searchEditor, selectAll = true)
            return true
        }

        // 3. ESC closes open dropdown first, then unfocuses non-empty search, then closes screen
        if (event.key() == InputConstants.KEY_ESCAPE) {
            if (SettingOverlayManager.hasOpenDropdown) {
                SettingOverlayManager.closeDropdown()
                return true
            }
            if (ClickGuiLayoutStore.activeTabIndex == 0 && clickGuiTab.searchEditor.text.isNotEmpty()) {
                clickGuiTab.searchEditor.forceText("")
                clickGuiTab.searchEditor.onChanged?.invoke("")
                return true
            }
            this.onClose()
            return true
        }

        // 4. Search navigation hotkeys (Up/Down/Enter/Tab) when on ClickGUI tab with active search results
        if (ClickGuiLayoutStore.activeTabIndex == 0 && clickGuiTab.handleSearchHotkeys(event)) {
            return true
        }

        // 5. Focused text field editing
        if (SettingOverlayManager.handleKeyPressed(event)) {
            return true
        }

        return super.keyPressed(event)
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        if (SettingOverlayManager.handleKeyReleased(event)) {
            return true
        }
        return super.keyReleased(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (SettingOverlayManager.handleCharTyped(event)) {
            return true
        }
        return super.charTyped(event)
    }

    override fun preeditUpdated(event: PreeditEvent?): Boolean {
        if (SettingOverlayManager.handlePreeditUpdated(event)) {
            return true
        }
        return super.preeditUpdated(event)
    }

    override fun removed() {
        SettingOverlayManager.clearTextInputFocus(commit = true, screen = this)
        SettingOverlayManager.resetAll()
        mc.onTextInputFocusChange(this, false)
        ModuleHud.hudEditorSelected = false
        ClickGuiLayoutStore.save()
        ConfigSystem.store(ModuleManager.modulesConfig)
        ModuleClickGui.onScreenClosed()
        super.removed()
    }
}
