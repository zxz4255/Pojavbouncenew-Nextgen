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
@file:Suppress("LongMethod", "CognitiveComplexMethod", "TooManyFunctions", "MagicNumber")

package net.ccbluex.liquidbounce.ui.clickgui.world3d

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.ui.clickgui.setting.SettingOverlayManager
import net.ccbluex.liquidbounce.ui.clickgui.setting.SettingRenderer
import net.ccbluex.liquidbounce.ui.common.UiAnimation
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.ui.common.UiIcons
import net.ccbluex.liquidbounce.ui.common.UiLocalization
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.ccbluex.liquidbounce.ui.common.UiTextEditState
import net.ccbluex.liquidbounce.ui.common.UiTextUtils
import net.ccbluex.liquidbounce.ui.common.UiTooltipManager
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Brand-new 3D Spatial Orbital Holo-Stage ClickGUI ("World3D").
 *
 * Completely discards the flat 2D vertical-column layout in favor of a cylindrical 3D spatial cockpit
 * floating in front of the live 3D Minecraft world:
 * - Upper 3D Curved Command Crown (mode switches, live holo-search, 3D orbit controls)
 * - 3D Concave Orbital Category Ring (`ALL` + all module categories wrapping in 3D depth)
 * - 3D Cylindrical Spatial Module Matrix (cards curve around the viewer on a 3D cylinder, levitate forward
 *   in `-Z` on hover, and sort back-to-front with authentic perspective foreshortening)
 * - Right 3D Angled Spatial Inspector Wing (`yaw = -16.5°`, full 23-type `SettingRenderer` deck via exact
 *   3D ray-plane mouse picking and first-order perspective Jacobian projection).
 */
class World3dClickGuiStage(
    private val onSwitchTab: (Int) -> Unit,
    private val onLanguageToggled: () -> Unit,
) {

    private class TileState(val module: ClientModule) {
        var hoverAnim: Float = 0f
        var enabledAnim: Float = if (module.enabled) 1f else 0f
        var selectedAnim: Float = 0f
    }

    private data class RenderedTile(
        val state: TileState,
        val surface: Surface3D,
    )

    private data class CategoryOrbitChip(
        val index: Int, // -1 = ALL, >= 0 = categories[index]
        val category: ModuleCategory?,
        val label: String,
        val surface: Surface3D,
    )

    private val categories = ArrayList<ModuleCategory>()
    private val tileStates = HashMap<ClientModule, TileState>()

    // -1 = ALL categories, 0..categories.lastIndex = single category
    private var selectedCategoryIdx: Int = 0
    private var categoryOrbitAnim: Float = 0f

    // Horizontal cylindrical carousel scroll (in column units)
    private var targetCarouselCol: Float = 0f
    private var smoothCarouselCol: Float = 0f
    private var maxCarouselCol: Float = 0f

    // Interactive user camera orbit offset (Right-Drag or Orbit slider)
    private var userOrbitYaw: Float = 0f
    private var userOrbitPitch: Float = 0f
    private var smoothCamYaw: Float = 0f
    private var smoothCamPitch: Float = 0f
    private var openIntroAnim: Float = 0f

    // Right 3D Inspector Wing state
    private var inspectedModule: ClientModule? = null
    private var inspectorTargetScroll: Float = 0f
    private var inspectorSmoothScroll: Float = 0f
    private var inspectorContentHeight: Float = 0f
    private var inspectorBodyHeight: Float = 0f

    // Search state
    val searchEditor = UiTextEditState(
        initialText = "",
        maxLength = 128,
        onChanged = {
            targetCarouselCol = 0f
        },
    )

    // Live per-frame 3D surfaces for exact raycast picking
    private var lastCrownSurface: Surface3D? = null
    private var lastInspectorSurface: Surface3D? = null
    private val lastCategoryChips = ArrayList<CategoryOrbitChip>()
    private val lastTilesFrontToBack = ArrayList<RenderedTile>()
    private var lastPrevPageSurface: Surface3D? = null
    private var lastNextPageSurface: Surface3D? = null

    // Crown local button rects `[u, v, w, h]`
    private val crownHudBtn = FloatArray(4)
    private val crownGlobalBtn = FloatArray(4)
    private val crownLangBtn = FloatArray(4)
    private val crownStyleBtn = FloatArray(4)
    private val crownResetOrbitBtn = FloatArray(4)
    private val crownSearchBox = FloatArray(4)

    // Inspector local header rects `[u, v, w, h]`
    private val inspectorToggleBtn = FloatArray(4)
    private var inspectorBodyTopV: Float = 72f
    private var inspectorInnerPadX: Float = 14f

    // Drag tracking
    private var draggingCarousel = false
    private var carouselDragMoved = false
    private var carouselDragStartX = 0f
    private var carouselDragStartCol = 0f
    private var orbitingStage = false
    private var orbitStartX = 0f
    private var orbitStartY = 0f
    private var orbitStartYaw = 0f
    private var orbitStartPitch = 0f
    private var draggingInspectorSettings = false

    val currentOrbitPhaseRad: Float
        get() = smoothCarouselCol * 0.32f + Math.toRadians(smoothCamYaw.toDouble()).toFloat()

    fun onOpen() {
        SettingRenderer.prepareForOpen()
        categories.clear()
        for (cat in ModuleCategories.entries) {
            if (ModuleManager.any { it.category == cat }) {
                categories.add(cat)
            }
        }
        for (mod in ModuleManager) {
            val st = tileStates.getOrPut(mod) { TileState(mod) }
            st.enabledAnim = if (mod.enabled) 1f else 0f
        }
        if (inspectedModule == null) {
            inspectedModule = ModuleManager.firstOrNull { it.category == categories.getOrNull(selectedCategoryIdx) }
                ?: ModuleClickGui
        }
        openIntroAnim = 0f
        if (ModuleClickGui.searchBarAutoFocusValue) {
            SettingOverlayManager.focusTextInput(searchEditor, selectAll = false)
        }
    }

    fun onLanguageChanged() {
        // Labels resolve dynamically each frame via UiLocalization.
    }

    private fun activeModules(): List<ClientModule> {
        val rawQ = searchEditor.text.trim()
        val list = if (rawQ.isNotEmpty()) {
            val lowerQ = rawQ.lowercase(Locale.ROOT)
            val compactQ = lowerQ.replace(" ", "")
            ModuleManager.filter { m ->
                val clean = m.name.lowercase(Locale.ROOT).replace(" ", "")
                clean.contains(compactQ) ||
                    UiLocalization.matchesQuery(rawQ, m.name) ||
                    matchesAcronym(compactQ, m.name) ||
                    m.aliases.any {
                        it.lowercase(Locale.ROOT).replace(" ", "").contains(compactQ) ||
                            UiLocalization.matchesQuery(rawQ, it)
                    }
            }
        } else if (selectedCategoryIdx < 0 || selectedCategoryIdx >= categories.size) {
            ModuleManager.toList()
        } else {
            val cat = categories[selectedCategoryIdx]
            ModuleManager.filter { it.category == cat }
        }

        return list.sortedWith { a, b ->
            String.CASE_INSENSITIVE_ORDER.compare(
                UiLocalization.moduleName(a.name),
                UiLocalization.moduleName(b.name),
            )
        }
    }

    private fun matchesAcronym(queryLower: String, rawCamelName: String): Boolean {
        if (queryLower.length < 2) return false
        val initials = buildString {
            rawCamelName.forEachIndexed { i, c ->
                if (i == 0 || c.isUpperCase() || c.isDigit()) append(c.lowercaseChar())
            }
        }
        return initials.contains(queryLower)
    }

    fun render(
        ctx: GuiGraphicsExtractor,
        screenW: Int,
        screenH: Int,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
    ) {
        val vw = screenW.toFloat().coerceAtLeast(320f)
        val vh = screenH.toFloat().coerceAtLeast(240f)
        val uiScale = ModuleClickGui.scaleValue.coerceIn(0.65f, 1.45f)

        openIntroAnim = UiAnimation.expLerp(openIntroAnim, 1f, 9.5f, dtSeconds)

        // Subtle atmospheric edge vignette that keeps the live 3D Minecraft world crystal clear in the center.
        drawCockpitAtmosphere(ctx, vw, vh, openIntroAnim)

        // Camera parallax from normalized mouse position + user right-drag orbit.
        val normMx = ((mouseX / vw) * 2f - 1f).coerceIn(-1f, 1f)
        val normMy = ((mouseY / vh) * 2f - 1f).coerceIn(-1f, 1f)
        val parallax = ModuleClickGui.world3dParallaxValue
        val targetYaw = userOrbitYaw + normMx * 4.2f * parallax
        val targetPitch = userOrbitPitch - normMy * 2.8f * parallax
        smoothCamYaw = UiAnimation.expLerp(smoothCamYaw, targetYaw, 10f, dtSeconds)
        smoothCamPitch = UiAnimation.expLerp(smoothCamPitch, targetPitch, 10f, dtSeconds)

        val camera = World3dCamera(
            viewportW = vw,
            viewportH = vh,
            fovDegrees = 53f,
            camYawDeg = smoothCamYaw,
            camPitchDeg = smoothCamPitch,
        )

        val introDepthPush = (1f - openIntroAnim) * 210f
        val accentFull = UiPalette.accent(0.95f * openIntroAnim)
        val accentSoft = UiPalette.accent(0.75f * openIntroAnim)

        // Compact responsive layout scaling so both 854x480 and 1920x1080 fit comfortably in 3D space.
        val layoutK = (min(vw / 960f, vh / 540f) * uiScale).coerceIn(0.58f, 1.38f)
        val baseStageZ = (680f / layoutK) + introDepthPush

        // ------------------------------------------------- 1) Top 3D Command Crown
        val crownW = 650f
        val crownH = 44f
        val crownSurface = Surface3D(
            worldCenterX = 0f,
            worldCenterY = -212f,
            worldCenterZ = baseStageZ * 0.94f,
            width = crownW,
            height = crownH,
            yawDeg = 0f,
            pitchDeg = 9.5f,
            rollDeg = 0f,
            camera = camera,
        )
        lastCrownSurface = crownSurface
        renderCommandCrown(ctx, crownSurface, mouseX, mouseY, accentFull, openIntroAnim)

        // --------------------------------------- 2) 3D Concave Orbital Category Ring
        lastCategoryChips.clear()
        val totalCats = categories.size + 1 // slot 0 = ALL, slots 1..N = categories
        val activeSlot = (selectedCategoryIdx + 1).coerceIn(0, totalCats - 1)
        categoryOrbitAnim = UiAnimation.expLerp(categoryOrbitAnim, activeSlot.toFloat(), 12f, dtSeconds)

        val catArcRadius = 560f
        val catStepDeg = 10.6f * ModuleClickGui.world3dCurvatureValue.coerceIn(0.5f, 1.45f)
        for (slot in 0 until totalCats) {
            val catIdx = slot - 1
            val cat = if (catIdx >= 0) categories[catIdx] else null
            val label = if (cat == null) UiLocalization.text("All", "全部") else UiLocalization.categoryName(cat.tag)
            val rel = slot.toFloat() - categoryOrbitAnim
            if (abs(rel) > 5.4f) continue

            val angleDeg = rel * catStepDeg
            val angleRad = Math.toRadians(angleDeg.toDouble()).toFloat()
            val chipX = -56f + sin(angleRad) * catArcRadius
            val chipZ = baseStageZ * 0.95f + (1f - cos(angleRad)) * catArcRadius * 0.85f
            val chipW = max(68f, UiDraw.measureText(label, 12f, UiFontWeight.SEMIBOLD) + (if (cat != null) 34f else 24f))
            val chipH = 27f

            val surface = Surface3D(
                worldCenterX = chipX,
                worldCenterY = -158f,
                worldCenterZ = chipZ,
                width = chipW,
                height = chipH,
                yawDeg = -angleDeg * 0.85f,
                pitchDeg = 6.5f,
                camera = camera,
            )
            lastCategoryChips.add(CategoryOrbitChip(catIdx, cat, label, surface))
        }
        // Sort category chips back-to-front by depth.
        lastCategoryChips.sortByDescending { it.surface.depthKey }
        for (chip in lastCategoryChips) {
            val isSel = chip.index == selectedCategoryIdx && searchEditor.text.isBlank()
            val hovered = chip.surface.raycast(mouseX, mouseY) != null && !SettingOverlayManager.hasOpenDropdown
            World3dHoloDraw.drawSurface(
                ctx = ctx,
                surface = chip.surface,
                radius = chip.surface.height * 0.5f,
                accentArgb = if (isSel) accentFull else accentSoft,
                kind = if (isSel) World3dHoloDraw.KIND_TILE_ACTIVE else World3dHoloDraw.KIND_CHIP,
                hover = if (hovered) 1f else 0f,
                selected = if (isSel) 1f else 0f,
                shadowMargin = 10f,
            )
            ctx.pose().pushMatrix()
            ctx.pose().mul(chip.surface.tangentPoseAt())
            var textX = 12f
            if (chip.category != null) {
                UiDraw.drawIcon(
                    ctx = ctx,
                    id = UiIcons.categoryIcon(chip.category.tag),
                    x = 10f,
                    y = (chip.surface.height - 13f) * 0.5f,
                    boxW = 13f,
                    boxH = 13f,
                    argb = if (isSel || hovered) UiPalette.TEXT_COLOR else UiPalette.TEXT_DIMMED_COLOR,
                )
                textX = 27f
            }
            val lh = UiDraw.lineHeight(12f, UiFontWeight.SEMIBOLD)
            UiDraw.drawText(
                ctx = ctx,
                text = chip.label,
                x = if (chip.category != null) textX else (chip.surface.width - UiDraw.measureText(chip.label, 12f, UiFontWeight.SEMIBOLD)) * 0.5f,
                y = (chip.surface.height - lh) * 0.5f,
                fontSizePx = 12f,
                argb = if (isSel || hovered) UiPalette.TEXT_COLOR else UiPalette.TEXT_DIMMED_COLOR,
                weight = UiFontWeight.SEMIBOLD,
                shadow = true,
            )
            ctx.pose().popMatrix()
        }
        // Reverse chip order in-place for front-to-back click picking!
        lastCategoryChips.reverse()

        // ----------------------------- 3) 3D Concave Cylindrical Spatial Module Matrix
        val mods = activeModules()
        val rows = 4
        val totalCols = max(1, (mods.size + rows - 1) / rows)
        val visibleColSpan = 3.0f
        maxCarouselCol = max(0f, totalCols.toFloat() - visibleColSpan)
        targetCarouselCol = targetCarouselCol.coerceIn(0f, maxCarouselCol)
        smoothCarouselCol = UiAnimation.expLerp(smoothCarouselCol, targetCarouselCol, 14f, dtSeconds)

        val curvature = ModuleClickGui.world3dCurvatureValue.coerceIn(0.35f, 1.65f)
        val cylRadius = 740f / max(0.55f, curvature)
        val colStepDeg = 14.8f * curvature
        val cardW = 172f
        val cardH = 62f
        val rowPitchY = 72f
        val stageCenterX = -152f
        val stageCenterY = 18f

        val visibleTiles = ArrayList<RenderedTile>(mods.size)
        for ((idx, mod) in mods.withIndex()) {
            val col = idx / rows
            val row = idx % rows
            val relCol = col.toFloat() - smoothCarouselCol - 1.0f // center column around col = smooth + 1
            if (abs(relCol) > 2.85f) continue

            val st = tileStates.getOrPut(mod) { TileState(mod) }
            val isInspected = inspectedModule === mod
            st.enabledAnim = UiAnimation.expLerp(st.enabledAnim, if (mod.enabled) 1f else 0f, 16f, dtSeconds)
            st.selectedAnim = UiAnimation.expLerp(st.selectedAnim, if (isInspected) 1f else 0f, 16f, dtSeconds)

            val thetaDeg = relCol * colStepDeg
            val thetaRad = Math.toRadians(thetaDeg.toDouble()).toFloat()
            val worldX = stageCenterX + sin(thetaRad) * cylRadius
            val worldY = stageCenterY + (row - (rows - 1) * 0.5f) * rowPitchY
            val popForward = st.hoverAnim * 30f + st.selectedAnim * 12f + st.enabledAnim * 6f
            val worldZ = baseStageZ + (1f - cos(thetaRad)) * cylRadius * 0.92f - popForward

            val surface = Surface3D(
                worldCenterX = worldX,
                worldCenterY = worldY,
                worldCenterZ = worldZ,
                width = cardW,
                height = cardH,
                yawDeg = -thetaDeg * 0.90f,
                pitchDeg = -(row - (rows - 1) * 0.5f) * 2.4f,
                camera = camera,
            )
            visibleTiles.add(RenderedTile(st, surface))
        }

        // Front-to-back order for accurate 3D raycast hover so nearer overlapping tiles win!
        visibleTiles.sortBy { it.surface.depthKey }
        lastTilesFrontToBack.clear()
        lastTilesFrontToBack.addAll(visibleTiles)

        // Check if cursor hits the Inspector Wing first (since it's on the right cockpit flank).
        val inspectorSurface = Surface3D(
            worldCenterX = 278f,
            worldCenterY = 26f,
            worldCenterZ = baseStageZ * 0.93f,
            width = 304f,
            height = 358f,
            yawDeg = -16.5f * curvature.coerceIn(0.6f, 1.3f),
            pitchDeg = 1.5f,
            camera = camera,
        )
        lastInspectorSurface = inspectorSurface
        val cursorOverInspector = inspectorSurface.raycast(mouseX, mouseY) != null
        val cursorOverDropdown = SettingOverlayManager.hasOpenDropdown

        var hoveredTile: RenderedTile? = null
        if (!cursorOverInspector && !cursorOverDropdown) {
            for (rt in lastTilesFrontToBack) {
                if (rt.surface.raycast(mouseX, mouseY) != null) {
                    hoveredTile = rt
                    break
                }
            }
        }

        for (rt in lastTilesFrontToBack) {
            val isHov = rt === hoveredTile
            rt.state.hoverAnim = UiAnimation.expLerp(rt.state.hoverAnim, if (isHov) 1f else 0f, 18f, dtSeconds)
        }

        // Render tiles back-to-front (painter's order in 3D!).
        for (i in visibleTiles.indices.reversed()) {
            renderModuleTile3D(ctx, visibleTiles[i], accentFull, accentSoft, vw)
        }

        // Bottom 3D Carousel Navigation Rail (`‹` | page indicator | `›`)
        renderCarouselFooter(ctx, camera, stageCenterX, baseStageZ, totalCols, mods.size, mouseX, mouseY, accentSoft)

        // ------------------------------------- 4) Right 3D Spatial Inspector Wing
        renderInspectorWing3D(ctx, inspectorSurface, mouseX, mouseY, dtSeconds, accentFull, accentSoft)

        // Tooltips on top
        UiTooltipManager.render(ctx)
    }

    private fun drawCockpitAtmosphere(ctx: GuiGraphicsExtractor, vw: Float, vh: Float, alpha: Float) {
        // Light peripheral frame tint (center stays open so the live 3D world shines through!).
        val topAlpha = (0.28f * alpha).coerceIn(0f, 1f)
        val topH = vh * 0.16f
        UiDraw.fillBox(ctx, 0f, 0f, vw, topH, UiPalette.black(topAlpha * 0.45f))
        UiDraw.fillBox(ctx, 0f, vh - topH * 0.7f, vw, topH * 0.7f, UiPalette.black(topAlpha * 0.35f))
    }

    private fun renderCommandCrown(
        ctx: GuiGraphicsExtractor,
        surface: Surface3D,
        mouseX: Float,
        mouseY: Float,
        accentFull: Int,
        openAlpha: Float,
    ) {
        val hit = if (!SettingOverlayManager.hasOpenDropdown) surface.raycast(mouseX, mouseY) else null
        World3dHoloDraw.drawSurface(
            ctx = ctx,
            surface = surface,
            radius = 14f,
            accentArgb = accentFull,
            kind = World3dHoloDraw.KIND_DECK,
            hover = if (hit != null) 0.35f else 0f,
            selected = 0.35f,
            shadowMargin = 16f,
        )

        ctx.pose().pushMatrix()
        ctx.pose().mul(surface.tangentPoseAt())

        val padY = 7f
        val btnH = surface.height - padY * 2f
        var cursorX = 10f

        // Title badge "3D DECK"
        val badgeTitle = UiLocalization.text("3D WORLD", "3D 空间")
        val badgeW = UiDraw.measureText(badgeTitle, 11.5f, UiFontWeight.BOLD) + 18f
        UiDraw.fillBox(ctx, cursorX, padY, badgeW, btnH, UiPalette.accent(0.26f * openAlpha), radius = btnH * 0.5f, borderColor = UiPalette.accent(0.75f), borderWidth = 1f)
        val badgeLh = UiDraw.lineHeight(11.5f, UiFontWeight.BOLD)
        UiDraw.drawText(ctx, badgeTitle, cursorX + 9f, padY + (btnH - badgeLh) * 0.5f, 11.5f, UiPalette.TEXT_COLOR, UiFontWeight.BOLD, shadow = true)
        cursorX += badgeW + 6f

        // Search input box in center-left of crown
        val searchW = 228f
        crownSearchBox[0] = cursorX
        crownSearchBox[1] = padY
        crownSearchBox[2] = searchW
        crownSearchBox[3] = btnH
        val searchFocused = searchEditor.focused
        val searchHovered = hit != null && hit.u in cursorX..(cursorX + searchW) && hit.v in padY..(padY + btnH)
        UiDraw.fillBox(
            ctx, cursorX, padY, searchW, btnH,
            UiPalette.black(if (searchFocused) 0.48f else 0.32f),
            radius = btnH * 0.5f,
            borderColor = if (searchFocused) UiPalette.accent(0.85f) else UiPalette.white(if (searchHovered) 0.28f else 0.14f),
            borderWidth = 1f,
        )
        UiDraw.drawIcon(ctx, UiIcons.SEARCH, cursorX + 9f, padY + (btnH - 13f) * 0.5f, 13f, 13f, UiPalette.TEXT_DIMMED_COLOR)
        UiDraw.drawTextFieldContent(
            ctx = ctx,
            state = searchEditor,
            x = cursorX + 22f,
            y = padY,
            w = searchW - 26f,
            h = btnH,
            fontSizePx = 12f,
            placeholder = UiLocalization.text("Search modules (Ctrl+F)...", "搜索模块 (Ctrl+F)..."),
            weight = UiFontWeight.MEDIUM,
            paddingX = 5f,
        )
        cursorX += searchW + 8f

        fun crownChip(rect: FloatArray, label: String, highlighted: Boolean = false) {
            val w = UiDraw.measureText(label, 11.5f, UiFontWeight.SEMIBOLD) + 18f
            rect[0] = cursorX
            rect[1] = padY
            rect[2] = w
            rect[3] = btnH
            val hov = hit != null && hit.u in cursorX..(cursorX + w) && hit.v in padY..(padY + btnH)
            UiDraw.fillBox(
                ctx, cursorX, padY, w, btnH,
                if (highlighted) UiPalette.accent(0.24f) else UiPalette.white(if (hov) 0.14f else 0.06f),
                radius = btnH * 0.5f,
                borderColor = if (highlighted || hov) UiPalette.accent(0.55f) else UiPalette.white(0.12f),
                borderWidth = 1f,
            )
            val lh = UiDraw.lineHeight(11.5f, UiFontWeight.SEMIBOLD)
            UiDraw.drawText(
                ctx, label,
                cursorX + 9f, padY + (btnH - lh) * 0.5f,
                11.5f,
                if (highlighted || hov) UiPalette.TEXT_COLOR else UiPalette.TEXT_DIMMED_COLOR,
                UiFontWeight.SEMIBOLD,
                shadow = true,
            )
            cursorX += w + 5f
        }

        crownChip(crownHudBtn, UiLocalization.text("HUD", "HUD编辑"))
        crownChip(crownGlobalBtn, UiLocalization.text("Global", "全局"))
        crownChip(crownLangBtn, if (UiLocalization.isChinese) "中文" else "EN", highlighted = UiLocalization.isChinese)
        crownChip(crownResetOrbitBtn, UiLocalization.text("Center", "视角归位"), highlighted = abs(userOrbitYaw) > 0.5f || abs(userOrbitPitch) > 0.5f)
        crownChip(crownStyleBtn, UiLocalization.text("2D Style", "切回2D"))

        ctx.pose().popMatrix()
    }

    private fun renderModuleTile3D(
        ctx: GuiGraphicsExtractor,
        tile: RenderedTile,
        accentFull: Int,
        accentSoft: Int,
        canvasW: Float,
    ) {
        val mod = tile.state.module
        val surface = tile.surface
        val enabled = mod.enabled
        val hov = tile.state.hoverAnim
        val sel = tile.state.selectedAnim

        World3dHoloDraw.drawSurface(
            ctx = ctx,
            surface = surface,
            radius = 11f,
            accentArgb = if (enabled || sel > 0.2f) accentFull else accentSoft,
            kind = if (enabled) World3dHoloDraw.KIND_TILE_ACTIVE else World3dHoloDraw.KIND_TILE,
            hover = hov,
            selected = sel,
            shadowMargin = 14f,
        )

        ctx.pose().pushMatrix()
        ctx.pose().mul(surface.tangentPoseAt())

        val w = surface.width
        val h = surface.height
        val title = UiDraw.ellipsize(UiLocalization.moduleName(mod.name), w - 48f, 13f, UiFontWeight.SEMIBOLD)
        UiDraw.drawText(
            ctx = ctx,
            text = title,
            x = 12f,
            y = 10f,
            fontSizePx = 13f,
            argb = if (enabled || hov > 0.25f) UiPalette.TEXT_COLOR else UiPalette.withAlphaFactor(UiPalette.TEXT_COLOR, 0.86f),
            weight = UiFontWeight.SEMIBOLD,
            shadow = true,
        )

        // Active status dot in top-right
        val dotCol = if (enabled) UiPalette.accent(1f) else UiPalette.white(0.22f)
        UiDraw.fillBox(ctx, w - 18f, 13f, 7f, 7f, dotCol, radius = 3.5f)

        // Bottom row: category/subtitle + keybind badge + settings gear badge
        val subLabel = if (UiLocalization.isChinese) {
            UiDraw.ellipsize(UiTextUtils.formatEnglishName(mod.name), 88f, 10f, UiFontWeight.REGULAR)
        } else {
            UiLocalization.categoryName(mod.category.tag)
        }
        UiDraw.drawText(
            ctx = ctx,
            text = subLabel,
            x = 12f,
            y = h - 20f,
            fontSizePx = 10f,
            argb = UiPalette.TEXT_DIMMED_COLOR,
            weight = UiFontWeight.REGULAR,
            shadow = true,
        )

        var rightCursor = w - 10f
        val optionCount = SettingRenderer.allOptionChildrenOf(mod).count { it.visibleCondition.asBoolean }
        if (optionCount > 0) {
            val cfgLabel = "⚙ $optionCount"
            val cfgW = UiDraw.measureText(cfgLabel, 10f, UiFontWeight.SEMIBOLD) + 10f
            rightCursor -= cfgW
            UiDraw.fillBox(
                ctx, rightCursor, h - 23f, cfgW, 15f,
                if (sel > 0.3f) UiPalette.accent(0.32f) else UiPalette.white(0.09f),
                radius = 7.5f,
                borderColor = if (sel > 0.3f) UiPalette.accent(0.7f) else UiPalette.white(0.14f),
                borderWidth = 0.8f,
            )
            UiDraw.drawText(ctx, cfgLabel, rightCursor + 5f, h - 20.5f, 10f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
            rightCursor -= 4f
        }

        if (!mod.bind.isUnbound) {
            val keyStr = UiTextUtils.formatKeyName(mod.bind.boundKey)
            val kw = UiDraw.measureText(keyStr, 9.5f, UiFontWeight.SEMIBOLD) + 9f
            rightCursor -= kw
            if (rightCursor > 68f) {
                UiDraw.fillBox(ctx, rightCursor, h - 23f, kw, 15f, UiPalette.accent(0.18f), radius = 5f)
                UiDraw.drawText(ctx, keyStr, rightCursor + 4.5f, h - 20.5f, 9.5f, UiPalette.TEXT_COLOR, UiFontWeight.SEMIBOLD)
            }
        }

        ctx.pose().popMatrix()

        if (hov > 0.65f) {
            val desc = runCatching { mod.description.get() }.getOrNull()
            if (!desc.isNullOrBlank() && !desc.startsWith("liquidbounce.")) {
                val pCenter = surface.project(w, h * 0.5f)
                val pLeft = surface.project(0f, h * 0.5f)
                UiTooltipManager.request(
                    text = desc,
                    anchorRightX = pCenter.sx + 8f,
                    centerY = pCenter.sy,
                    fallbackLeftX = pLeft.sx - 8f,
                    canvasWidth = canvasW,
                )
            }
        }
    }

    private fun renderCarouselFooter(
        ctx: GuiGraphicsExtractor,
        camera: World3dCamera,
        stageCenterX: Float,
        baseStageZ: Float,
        totalCols: Int,
        totalMods: Int,
        mouseX: Float,
        mouseY: Float,
        accentSoft: Int,
    ) {
        val footerSurface = Surface3D(
            worldCenterX = stageCenterX,
            worldCenterY = 186f,
            worldCenterZ = baseStageZ * 0.96f,
            width = 310f,
            height = 24f,
            yawDeg = 3.5f,
            pitchDeg = -7f,
            camera = camera,
        )
        val prevBtn = Surface3D(stageCenterX - 182f, 186f, baseStageZ * 0.96f, 28f, 24f, 6f, -7f, camera = camera)
        val nextBtn = Surface3D(stageCenterX + 182f, 186f, baseStageZ * 0.96f, 28f, 24f, -1f, -7f, camera = camera)
        lastPrevPageSurface = prevBtn
        lastNextPageSurface = nextBtn

        World3dHoloDraw.drawSurface(ctx, footerSurface, 12f, accentSoft, World3dHoloDraw.KIND_CHIP, shadowMargin = 8f)
        ctx.pose().pushMatrix()
        ctx.pose().mul(footerSurface.tangentPoseAt())

        val status = if (UiLocalization.isChinese) {
            "模块 $totalMods  ·  滚轮/右键拖动旋转 3D 环舱  ·  右键模块打开右侧 3D 面板"
        } else {
            "$totalMods Modules  ·  Wheel / RMB-Drag Orbit  ·  Right-Click to Inspect"
        }
        val clippedStatus = UiDraw.ellipsize(status, footerSurface.width - 20f, 10f, UiFontWeight.MEDIUM)
        val sw = UiDraw.measureText(clippedStatus, 10f, UiFontWeight.MEDIUM)
        UiDraw.drawText(ctx, clippedStatus, (footerSurface.width - sw) * 0.5f, 4f, 10f, UiPalette.TEXT_DIMMED_COLOR, UiFontWeight.MEDIUM, shadow = true)

        // Scroll progress bar along bottom inside footer
        val trackX = 18f
        val trackW = footerSurface.width - 36f
        UiDraw.fillBox(ctx, trackX, footerSurface.height - 5f, trackW, 2.2f, UiPalette.white(0.14f), radius = 1.1f)
        val thumbFrac = if (totalCols <= 3) 1f else (3f / totalCols.toFloat()).coerceIn(0.12f, 1f)
        val thumbW = trackW * thumbFrac
        val scrollRatio = if (maxCarouselCol <= 0.001f) 0f else (smoothCarouselCol / maxCarouselCol).coerceIn(0f, 1f)
        UiDraw.fillBox(ctx, trackX + (trackW - thumbW) * scrollRatio, footerSurface.height - 5f, thumbW, 2.2f, UiPalette.accent(0.9f), radius = 1.1f)
        ctx.pose().popMatrix()

        // Prev / Next 3D arrow pills
        for ((surf, sym, canClick) in listOf(
            Triple(prevBtn, "‹", targetCarouselCol > 0.05f),
            Triple(nextBtn, "›", targetCarouselCol < maxCarouselCol - 0.05f),
        )) {
            val hov = canClick && surf.raycast(mouseX, mouseY) != null
            World3dHoloDraw.drawSurface(ctx, surf, 12f, accentSoft, World3dHoloDraw.KIND_CHIP, hover = if (hov) 1f else 0f, shadowMargin = 8f)
            ctx.pose().pushMatrix()
            ctx.pose().mul(surf.tangentPoseAt())
            val tw = UiDraw.measureText(sym, 14f, UiFontWeight.BOLD)
            val lh = UiDraw.lineHeight(14f, UiFontWeight.BOLD)
            UiDraw.drawText(
                ctx, sym,
                (surf.width - tw) * 0.5f, (surf.height - lh) * 0.5f - 1f,
                14f,
                if (canClick) UiPalette.TEXT_COLOR else UiPalette.withAlphaFactor(UiPalette.TEXT_DIMMED_COLOR, 0.45f),
                UiFontWeight.BOLD,
            )
            ctx.pose().popMatrix()
        }
    }

    private fun renderInspectorWing3D(
        ctx: GuiGraphicsExtractor,
        surface: Surface3D,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
        accentFull: Int,
        accentSoft: Int,
    ) {
        val mod = inspectedModule ?: ModuleClickGui
        val hit = surface.raycast(mouseX, mouseY, requireInside = false)
        val insidePanel = hit != null && hit.u in 0f..surface.width && hit.v in 0f..surface.height

        World3dHoloDraw.drawSurface(
            ctx = ctx,
            surface = surface,
            radius = 14f,
            accentArgb = if (mod.enabled) accentFull else accentSoft,
            kind = World3dHoloDraw.KIND_DECK,
            hover = if (insidePanel) 0.30f else 0f,
            selected = 0.65f,
            shadowMargin = 18f,
        )

        val w = surface.width
        val h = surface.height
        val headerH = 62f
        inspectorBodyTopV = headerH + 6f
        inspectorBodyHeight = (h - inspectorBodyTopV - 10f).coerceAtLeast(40f)
        val innerW = w - inspectorInnerPadX * 2f

        SettingRenderer.stepGroupAnimations(mod, dtSeconds)
        inspectorContentHeight = SettingRenderer.measureGroupHeight(mod, innerW)
        val maxScroll = max(0f, inspectorContentHeight - inspectorBodyHeight)
        inspectorTargetScroll = inspectorTargetScroll.coerceIn(0f, maxScroll)
        inspectorSmoothScroll = UiAnimation.expLerp(inspectorSmoothScroll, inspectorTargetScroll, 17f, dtSeconds)
            .coerceIn(0f, maxScroll)

        ctx.pose().pushMatrix()
        ctx.pose().mul(surface.tangentPoseAt())

        // Wing header
        UiDraw.drawIcon(
            ctx = ctx,
            id = UiIcons.categoryIcon(mod.category.tag),
            x = 14f,
            y = 14f,
            boxW = 16f,
            boxH = 16f,
            argb = UiPalette.TEXT_COLOR,
        )
        val modTitle = UiDraw.ellipsize(UiLocalization.moduleName(mod.name), w - 122f, 14.5f, UiFontWeight.BOLD)
        UiDraw.drawText(ctx, modTitle, 36f, 12f, 14.5f, UiPalette.TEXT_COLOR, UiFontWeight.BOLD, shadow = true)

        val catSub = "${UiLocalization.categoryName(mod.category.tag)}  ·  ${mod.name}"
        UiDraw.drawText(
            ctx,
            UiDraw.ellipsize(catSub, w - 120f, 10.5f, UiFontWeight.REGULAR),
            36f, 32f, 10.5f, UiPalette.TEXT_DIMMED_COLOR,
        )

        // Enable / Disable pill button in top-right of Inspector
        val toggleLabel = when {
            mod.disableActivation -> UiLocalization.text("SYSTEM", "常驻")
            mod.enabled -> UiLocalization.text("ENABLED", "已开启")
            else -> UiLocalization.text("DISABLED", "已关闭")
        }
        val toggleW = max(66f, UiDraw.measureText(toggleLabel, 10.5f, UiFontWeight.BOLD) + 18f)
        val toggleH = 24f
        val toggleX = w - toggleW - 14f
        val toggleY = 16f
        inspectorToggleBtn[0] = toggleX
        inspectorToggleBtn[1] = toggleY
        inspectorToggleBtn[2] = toggleW
        inspectorToggleBtn[3] = toggleH

        val toggleHov = insidePanel &&
            hit.u in toggleX..(toggleX + toggleW) && hit.v in toggleY..(toggleY + toggleH)
        UiDraw.fillBox(
            ctx, toggleX, toggleY, toggleW, toggleH,
            if (mod.enabled) UiPalette.accent(0.42f) else UiPalette.white(if (toggleHov) 0.16f else 0.08f),
            radius = toggleH * 0.5f,
            borderColor = if (mod.enabled) UiPalette.accent(0.90f) else UiPalette.white(0.20f),
            borderWidth = 1f,
        )
        val tlh = UiDraw.lineHeight(10.5f, UiFontWeight.BOLD)
        val ttw = UiDraw.measureText(toggleLabel, 10.5f, UiFontWeight.BOLD)
        UiDraw.drawText(
            ctx, toggleLabel,
            toggleX + (toggleW - ttw) * 0.5f,
            toggleY + (toggleH - tlh) * 0.5f,
            10.5f, UiPalette.TEXT_COLOR, UiFontWeight.BOLD,
        )

        // Header divider line
        UiDraw.fillBox(ctx, 14f, headerH - 1f, w - 28f, 1.2f, UiPalette.accent(0.45f))

        // Scrollable 3D-projected SettingRenderer body
        val localMx = if (insidePanel && !SettingOverlayManager.hasOpenDropdown && hit.v >= inspectorBodyTopV) {
            hit.u
        } else {
            -10000f
        }
        val localMy = if (insidePanel && !SettingOverlayManager.hasOpenDropdown && hit.v >= inspectorBodyTopV) {
            hit.v
        } else {
            -10000f
        }

        if (inspectorContentHeight <= 0.5f) {
            val emptyLabel = UiLocalization.text("No configurable settings", "该模块暂无可调参数")
            val ew = UiDraw.measureText(emptyLabel, 12f, UiFontWeight.MEDIUM)
            UiDraw.drawText(
                ctx, emptyLabel,
                (w - ew) * 0.5f,
                inspectorBodyTopV + inspectorBodyHeight * 0.42f,
                12f, UiPalette.TEXT_DIMMED_COLOR, UiFontWeight.MEDIUM,
            )
        } else {
            UiDraw.withCssScissor(ctx, 4f, inspectorBodyTopV, w - 8f, inspectorBodyHeight) {
                SettingRenderer.renderGroup(
                    ctx = ctx,
                    group = mod,
                    x = inspectorInnerPadX,
                    y = inspectorBodyTopV - inspectorSmoothScroll,
                    width = innerW,
                    mouseX = localMx,
                    mouseY = localMy,
                    dtSeconds = dtSeconds,
                    canvasWidth = w,
                )
            }

            // Sleek 3D scrollbar rail along right edge when content overflows
            if (maxScroll > 1f) {
                val railX = w - 6f
                val railY = inspectorBodyTopV + 2f
                val railH = inspectorBodyHeight - 4f
                UiDraw.fillBox(ctx, railX, railY, 2.5f, railH, UiPalette.white(0.10f), radius = 1.25f)
                val thumbH = (railH * (inspectorBodyHeight / inspectorContentHeight)).coerceIn(22f, railH)
                val thumbY = railY + (railH - thumbH) * (inspectorSmoothScroll / maxScroll).coerceIn(0f, 1f)
                UiDraw.fillBox(ctx, railX, thumbY, 2.5f, thumbH, UiPalette.accent(0.85f), radius = 1.25f)
            }
        }

        // Render any open dropdown directly on the 3D Inspector Wing plane so coordinates align 1:1!
        val ddMx = hit?.u ?: -10000f
        val ddMy = hit?.v ?: -10000f
        SettingOverlayManager.renderDropdownOverlay(ctx, ddMx, ddMy, w, h, dtSeconds)

        ctx.pose().popMatrix()
    }

    // ========================================================= Input Handling

    fun mouseClicked(gx: Float, gy: Float, button: Int, shiftHeld: Boolean, screen: TabbedScreenBridge): Boolean {
        val inspector = lastInspectorSurface

        // 1. Open dropdown popup on the 3D Inspector Wing has top priority.
        if (SettingOverlayManager.hasOpenDropdown && inspector != null) {
            val hit = inspector.raycast(gx, gy, requireInside = false)
            if (hit != null && SettingOverlayManager.mouseClickedDropdown(hit.u, hit.v, button, inspector.height)) {
                ConfigSystem.store(ModuleManager.modulesConfig)
                return true
            }
            SettingOverlayManager.closeDropdown()
            return true
        }

        SettingOverlayManager.clearTextInputFocus(commit = true, screen = screen.asMinecraftScreen())

        // 2. Top 3D Command Crown
        lastCrownSurface?.raycast(gx, gy)?.let { hit ->
            if (inRect(hit.u, hit.v, crownSearchBox)) {
                if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                    searchEditor.forceText("")
                    searchEditor.onChanged?.invoke("")
                }
                SettingOverlayManager.focusTextInput(searchEditor, selectAll = false)
                return true
            }
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                if (inRect(hit.u, hit.v, crownHudBtn)) {
                    onSwitchTab(1)
                    return true
                }
                if (inRect(hit.u, hit.v, crownGlobalBtn)) {
                    onSwitchTab(2)
                    return true
                }
                if (inRect(hit.u, hit.v, crownLangBtn)) {
                    onLanguageToggled()
                    return true
                }
                if (inRect(hit.u, hit.v, crownResetOrbitBtn)) {
                    userOrbitYaw = 0f
                    userOrbitPitch = 0f
                    targetCarouselCol = 0f
                    return true
                }
                if (inRect(hit.u, hit.v, crownStyleBtn)) {
                    ModuleClickGui.cycleStyleFromWorld3d()
                    return true
                }
            }
            return true
        }

        // 3. Right 3D Spatial Inspector Wing
        inspector?.raycast(gx, gy)?.let { hit ->
            val mod = inspectedModule ?: ModuleClickGui
            if (button == InputConstants.MOUSE_BUTTON_LEFT && inRect(hit.u, hit.v, inspectorToggleBtn)) {
                if (!mod.disableActivation) {
                    mod.enabled = !mod.enabled
                    ConfigSystem.store(ModuleManager.modulesConfig)
                }
                return true
            }
            if (hit.v >= inspectorBodyTopV && hit.v <= inspectorBodyTopV + inspectorBodyHeight) {
                val values = SettingRenderer.allOptionChildrenOf(mod)
                if (SettingRenderer.mouseClickedList(values, hit.u, hit.v, button, shiftHeld)) {
                    draggingInspectorSettings = true
                    ConfigSystem.store(ModuleManager.modulesConfig)
                    return true
                }
            }
            return true
        }

        // 4. 3D Orbital Category Chips
        for (chip in lastCategoryChips) {
            if (chip.surface.raycast(gx, gy) != null) {
                if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                    selectedCategoryIdx = chip.index
                    targetCarouselCol = 0f
                    if (searchEditor.text.isNotEmpty()) {
                        searchEditor.forceText("")
                        searchEditor.onChanged?.invoke("")
                    }
                    // Auto-select first module of that category if none inspected yet
                    val firstInCat = if (chip.category != null) {
                        ModuleManager.firstOrNull { it.category == chip.category }
                    } else {
                        ModuleManager.firstOrNull()
                    }
                    if (firstInCat != null && inspectedModule == null) {
                        inspectedModule = firstInCat
                        inspectorTargetScroll = 0f
                    }
                }
                return true
            }
        }

        // 5. Carousel Prev / Next 3D pills
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            if (lastPrevPageSurface?.raycast(gx, gy) != null) {
                targetCarouselCol = (targetCarouselCol - 2f).coerceIn(0f, maxCarouselCol)
                return true
            }
            if (lastNextPageSurface?.raycast(gx, gy) != null) {
                targetCarouselCol = (targetCarouselCol + 2f).coerceIn(0f, maxCarouselCol)
                return true
            }
        }

        // 6. 3D Cylindrical Module Cards (front-to-back order)
        for (rt in lastTilesFrontToBack) {
            val hit = rt.surface.raycast(gx, gy) ?: continue
            val mod = rt.state.module
            val clickedGearCorner = hit.u >= rt.surface.width - 54f && hit.v >= rt.surface.height - 26f
            if (button == InputConstants.MOUSE_BUTTON_RIGHT || (button == InputConstants.MOUSE_BUTTON_LEFT && (clickedGearCorner || mod.disableActivation))) {
                selectModuleForInspection(mod)
                return true
            }
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                if (shiftHeld) {
                    selectModuleForInspection(mod)
                } else {
                    mod.enabled = !mod.enabled
                    ConfigSystem.store(ModuleManager.modulesConfig)
                }
                return true
            }
            return true
        }

        // 7. Empty cockpit space: Left-drag scrolls the 3D cylinder; Right-drag orbits the 3D camera!
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            draggingCarousel = true
            carouselDragMoved = false
            carouselDragStartX = gx
            carouselDragStartCol = targetCarouselCol
            return true
        }
        if (button == InputConstants.MOUSE_BUTTON_RIGHT || button == InputConstants.MOUSE_BUTTON_MIDDLE) {
            orbitingStage = true
            orbitStartX = gx
            orbitStartY = gy
            orbitStartYaw = userOrbitYaw
            orbitStartPitch = userOrbitPitch
            return true
        }
        return false
    }

    fun mouseDragged(gx: Float, gy: Float, button: Int): Boolean {
        if (draggingInspectorSettings) {
            val inspector = lastInspectorSurface ?: return false
            val hit = inspector.raycast(gx, gy, requireInside = false) ?: return false
            val mod = inspectedModule ?: ModuleClickGui
            val handled = SettingRenderer.mouseDraggedList(SettingRenderer.allOptionChildrenOf(mod), hit.u, hit.v, button)
            if (handled) ConfigSystem.store(ModuleManager.modulesConfig)
            return handled
        }
        if (draggingCarousel && button == InputConstants.MOUSE_BUTTON_LEFT) {
            val dx = gx - carouselDragStartX
            if (abs(dx) > 3f) carouselDragMoved = true
            if (carouselDragMoved) {
                targetCarouselCol = (carouselDragStartCol - dx / 115f).coerceIn(0f, maxCarouselCol)
            }
            return true
        }
        if (orbitingStage) {
            val dx = gx - orbitStartX
            val dy = gy - orbitStartY
            userOrbitYaw = (orbitStartYaw + dx * 0.085f).coerceIn(-18f, 18f)
            userOrbitPitch = (orbitStartPitch - dy * 0.065f).coerceIn(-12f, 12f)
            return true
        }
        return false
    }

    fun mouseReleased(gx: Float, gy: Float, button: Int) {
        if (draggingInspectorSettings) {
            val mod = inspectedModule ?: ModuleClickGui
            val hit = lastInspectorSurface?.raycast(gx, gy, requireInside = false)
            SettingRenderer.mouseReleasedList(
                SettingRenderer.allOptionChildrenOf(mod),
                hit?.u ?: -10000f,
                hit?.v ?: -10000f,
                button,
            )
            ConfigSystem.store(ModuleManager.modulesConfig)
            draggingInspectorSettings = false
        }
        draggingCarousel = false
        orbitingStage = false
    }

    fun mouseScrolled(gx: Float, gy: Float, scrollY: Double): Boolean {
        val inspector = lastInspectorSurface
        if (SettingOverlayManager.hasOpenDropdown && inspector != null) {
            val hit = inspector.raycast(gx, gy, requireInside = false)
            if (hit != null && SettingOverlayManager.mouseScrolledDropdown(hit.u, hit.v, scrollY, inspector.height)) {
                return true
            }
        }
        if (inspector != null) {
            val hit = inspector.raycast(gx, gy)
            if (hit != null) {
                val mod = inspectedModule ?: ModuleClickGui
                if (SettingRenderer.mouseScrolledList(SettingRenderer.allOptionChildrenOf(mod), hit.u, hit.v, scrollY)) {
                    return true
                }
                val maxScroll = max(0f, inspectorContentHeight - inspectorBodyHeight)
                inspectorTargetScroll = (inspectorTargetScroll - scrollY.toFloat() * 28f).coerceIn(0f, maxScroll)
                return true
            }
        }

        // Scroll anywhere else smoothly rotates the 3D Cylindrical Module Matrix!
        targetCarouselCol = (targetCarouselCol - scrollY.toFloat() * 0.85f).coerceIn(0f, maxCarouselCol)
        return true
    }

    fun handleKeyPressed(event: KeyEvent): Boolean {
        if (event.key() == InputConstants.KEY_F && event.hasControlDownWithQuirk()) {
            SettingOverlayManager.focusTextInput(searchEditor, selectAll = true)
            return true
        }
        if (event.key() == InputConstants.KEY_ESCAPE && searchEditor.text.isNotEmpty()) {
            searchEditor.forceText("")
            searchEditor.onChanged?.invoke("")
            return true
        }
        if (!SettingOverlayManager.isCapturingKeyboard) {
            when (event.key()) {
                InputConstants.KEY_LEFT, InputConstants.KEY_A -> if (event.hasAltDown()) {
                    targetCarouselCol = (targetCarouselCol - 1f).coerceIn(0f, maxCarouselCol)
                    return true
                }
                InputConstants.KEY_RIGHT, InputConstants.KEY_D -> if (event.hasAltDown()) {
                    targetCarouselCol = (targetCarouselCol + 1f).coerceIn(0f, maxCarouselCol)
                    return true
                }
                InputConstants.KEY_Q -> if (event.hasControlDownWithQuirk()) {
                    selectedCategoryIdx = (selectedCategoryIdx - 1).coerceAtLeast(-1)
                    targetCarouselCol = 0f
                    return true
                }
                InputConstants.KEY_E -> if (event.hasControlDownWithQuirk()) {
                    selectedCategoryIdx = (selectedCategoryIdx + 1).coerceAtMost(categories.lastIndex)
                    targetCarouselCol = 0f
                    return true
                }
            }
        }
        return false
    }

    private fun selectModuleForInspection(mod: ClientModule) {
        if (inspectedModule !== mod) {
            SettingOverlayManager.closeDropdown()
            inspectedModule = mod
            inspectorTargetScroll = 0f
            inspectorSmoothScroll = 0f
        }
    }

    private fun inRect(u: Float, v: Float, r: FloatArray): Boolean =
        u in r[0]..(r[0] + r[2]) && v in r[1]..(r[1] + r[3])

    interface TabbedScreenBridge {
        fun asMinecraftScreen(): net.minecraft.client.gui.screens.Screen
    }
}
