/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.copyPosePooled
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.render.gui.element.InkRevealGuiElementRenderState
import net.ccbluex.liquidbounce.utils.client.GitHubBuildInfo
import net.ccbluex.liquidbounce.utils.client.clientIdentifier
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Native single-stroke SDF calligraphy "hello" ink-reveal title overlay.
 *
 * Uses a dual-layer crossing-free SDF + 16-bit monotonic arc-length field evaluated in a
 * single fragment pass (`gui/ink_reveal`) so curves, loops, and crossings write smoothly
 * with uniform pen width and zero alpha/geometry overlap bulges, plus matching rollerball
 * handwritten `#build` calligraphy alongside the author credit signature.
 */
object ModuleHelloScreen : ClientModule("HelloScreen", ModuleCategories.RENDER, state = true, hide = true) {

    val showBuild by boolean("ShowBuild", true)
    private val onlyOnce by boolean("OnlyOnce", false)

    // Read-only, not restored from configuration: a saved value must never impersonate a CI run.
    @Suppress("unused")
    private val build by text("Build", GitHubBuildInfo.label).immutable().notPersistent()

    private const val MAIN_MS = 1650f
    private const val SUBTITLE_MS = 1000f
    private const val HOLD_MS = 250f
    private const val FADE_MS = 350f
    private const val TOTAL_MS = MAIN_MS + SUBTITLE_MS + HOLD_MS + FADE_MS

    private const val VIRTUAL_W = 1062f
    private const val VIRTUAL_H = 405f

    private const val MAIN_ARC_PX = 2812
    private const val MAIN_NOMINAL_RADIUS = 2.60f
    private const val SUB_ARC_PX = 2889
    private const val SUB_NOMINAL_RADIUS = 2.50f

    // Credit pen cutout: 1000x572
    private const val CREDIT_ASPECT = 1000f / 572f

    // Handwritten rollerball build glyph sheet: 8x5 cells of 64x108 (dual-layer 512x1080)
    private const val BUILD_COLS = 8f
    private const val BUILD_ROWS = 5f
    private const val BUILD_CELL_W = 64f
    private const val BUILD_CELL_H = 108f
    private const val BUILD_CAP_H = 56f
    private const val BUILD_BASELINE_Y = 76f
    private const val BUILD_NOMINAL_RADIUS = 1.46f

    private data class BuildGlyph(
        val col: Int,
        val row: Int,
        val advancePx: Float,
        val arcPx: Int,
    )

    private val BUILD_GLYPHS: Map<Char, BuildGlyph> = mapOf(
        '#' to BuildGlyph(0, 0, 27.0f, 183),
        '0' to BuildGlyph(1, 0, 22.0f, 128),
        '1' to BuildGlyph(2, 0, 17.0f, 75),
        '2' to BuildGlyph(3, 0, 22.5f, 100),
        '3' to BuildGlyph(4, 0, 22.0f, 91),
        '4' to BuildGlyph(5, 0, 23.0f, 127),
        '5' to BuildGlyph(6, 0, 22.0f, 94),
        '6' to BuildGlyph(7, 0, 21.5f, 105),
        '7' to BuildGlyph(0, 1, 21.5f, 83),
        '8' to BuildGlyph(1, 1, 22.0f, 133),
        '9' to BuildGlyph(2, 1, 22.0f, 121),
        'a' to BuildGlyph(3, 1, 20.5f, 102),
        'b' to BuildGlyph(4, 1, 20.5f, 132),
        'c' to BuildGlyph(5, 1, 18.5f, 51),
        'd' to BuildGlyph(6, 1, 21.0f, 175),
        'e' to BuildGlyph(7, 1, 19.0f, 75),
        'f' to BuildGlyph(0, 2, 17.5f, 90),
        'g' to BuildGlyph(1, 2, 21.0f, 130),
        'h' to BuildGlyph(2, 2, 21.0f, 133),
        'i' to BuildGlyph(3, 2, 14.0f, 41),
        'j' to BuildGlyph(4, 2, 15.5f, 72),
        'k' to BuildGlyph(5, 2, 20.5f, 113),
        'l' to BuildGlyph(6, 2, 14.5f, 68),
        'm' to BuildGlyph(7, 2, 27.5f, 159),
        'n' to BuildGlyph(0, 3, 21.0f, 107),
        'o' to BuildGlyph(1, 3, 19.5f, 74),
        'p' to BuildGlyph(2, 3, 20.5f, 107),
        'q' to BuildGlyph(3, 3, 21.0f, 120),
        'r' to BuildGlyph(4, 3, 17.5f, 70),
        's' to BuildGlyph(5, 3, 19.0f, 50),
        't' to BuildGlyph(6, 3, 18.0f, 77),
        'u' to BuildGlyph(7, 3, 21.0f, 107),
        'v' to BuildGlyph(0, 4, 19.0f, 67),
        'w' to BuildGlyph(1, 4, 23.5f, 111),
        'x' to BuildGlyph(2, 4, 19.5f, 71),
        'y' to BuildGlyph(3, 4, 20.0f, 122),
        'z' to BuildGlyph(4, 4, 19.5f, 62),
        '.' to BuildGlyph(5, 4, 11.5f, 8),
        '-' to BuildGlyph(6, 4, 19.0f, 15),
        '+' to BuildGlyph(7, 4, 20.5f, 52),
    )

    private val TEX_MAIN_INK: Identifier = clientIdentifier("textures/gui/hello_main_ink.png")
    private val TEX_SUB_INK: Identifier = clientIdentifier("textures/gui/hello_subtitle_ink.png")
    private val TEX_BUILD_INK: Identifier = clientIdentifier("textures/gui/hello_build_ink.png")
    private val TEX_CREDIT: Identifier = clientIdentifier("textures/gui/hello_credit.png")

    private var armed = true
    private var playedOnce = false
    private var wasInWorld = false
    private var animStartNs = 0L

    init {
        enabledValue.onChanged { isEnabled ->
            if (isEnabled) {
                armed = true
                animStartNs = 0L
            } else {
                animStartNs = 0L
            }
        }
    }

    private fun ease(t: Float): Float {
        val p = t.coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    private fun textureSetup(id: Identifier, filter: FilterMode): TextureSetup? = runCatching {
        val tex = mc.textureManager.getTexture(id)
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(filter)
        TextureSetup.singleTexture(tex.textureView, sampler)
    }.getOrNull()

    @JvmStatic
    fun renderTitleOverlay(ctx: GuiGraphicsExtractor) {
        if (mc.level != null) {
            wasInWorld = true
            animStartNs = 0L
            return
        }

        if (wasInWorld) {
            wasInWorld = false
            if (!onlyOnce) {
                armed = true
                animStartNs = 0L
            }
        }

        if (!enabled) {
            animStartNs = 0L
            return
        }

        val screen = mc.gui.screen()
        if (screen !is TitleScreen) {
            return
        }

        // Wait until Minecraft's initial LoadingOverlay finishes fading out before starting t = 0
        if (mc.gui.overlay() != null) {
            return
        }

        if (onlyOnce && playedOnce && animStartNs == 0L) {
            return
        }

        if (!armed && animStartNs == 0L) {
            return
        }

        val now = System.nanoTime()
        if (animStartNs == 0L) {
            animStartNs = now
            armed = false
            playedOnce = true
        }

        val elapsedMs = ((now - animStartNs) / 1_000_000.0).toFloat().coerceAtLeast(0f)
        if (elapsedMs >= TOTAL_MS) {
            animStartNs = 0L
            return
        }

        val mainProgress = ease(elapsedMs / MAIN_MS)
        val subtitleProgress = ease((elapsedMs - MAIN_MS) / SUBTITLE_MS)
        val fadeProgress = ease((elapsedMs - MAIN_MS - SUBTITLE_MS - HOLD_MS) / FADE_MS)
        val overlayOpacity = (1f - fadeProgress).coerceIn(0f, 1f)
        if (overlayOpacity <= 0.004f) {
            return
        }

        val sw = ctx.guiWidth().toFloat()
        val sh = ctx.guiHeight().toFloat()
        if (sw <= 0f || sh <= 0f) {
            return
        }

        // Virtual 1062x405 stage: width = min(82vw, 139vh), max-height = 53vh
        val maxStageWByHeight = sh * 0.53f * (VIRTUAL_W / VIRTUAL_H)
        val stageW = min(min(sw * 0.82f, sh * 1.39f), maxStageWByHeight)
        val stageH = stageW * (VIRTUAL_H / VIRTUAL_W)
        val stageX = (sw - stageW) * 0.5f
        val stageY = (sh - stageH) * 0.5f

        // Layer 1: Main "LiquidBounce" single-stroke calligraphy
        drawInkFieldLayer(
            ctx = ctx,
            inkId = TEX_MAIN_INK,
            progress = mainProgress,
            overlayOpacity = overlayOpacity,
            totalArcPx = MAIN_ARC_PX,
            nominalRadius = MAIN_NOMINAL_RADIUS,
            x0 = stageX,
            y0 = stageY,
            x1 = stageX + stageW,
            y1 = stageY + stageH,
        )

        // Layer 2: Subtitle "nextgen for Android" single-stroke calligraphy
        if (subtitleProgress > 0f) {
            drawInkFieldLayer(
                ctx = ctx,
                inkId = TEX_SUB_INK,
                progress = subtitleProgress,
                overlayOpacity = overlayOpacity,
                totalArcPx = SUB_ARC_PX,
                nominalRadius = SUB_NOMINAL_RADIUS,
                x0 = stageX,
                y0 = stageY,
                x1 = stageX + stageW,
                y1 = stageY + stageH,
            )
        }

        // Layer 3: Bottom credit pen + matching rollerball handwritten #version label
        val creditReveal = ease((elapsedMs - 800f) / 420f)
        val creditOpacity = (0.85f * creditReveal * overlayOpacity).coerceIn(0f, 1f)
        if (creditOpacity > 0.005f) {
            val creditH = (sw * 0.048f).coerceIn(18f, 36f)
            val creditW = creditH * CREDIT_ASPECT
            val buildLabel = GitHubBuildInfo.label
            val drawBuild = showBuild && buildLabel.isNotBlank()
            val glyphScale = (creditH * 0.39f) / BUILD_CAP_H
            val gap = if (drawBuild) creditH * 0.12f else 0f
            val textW = if (drawBuild) measureHandwrittenBuild(buildLabel, glyphScale) else 0f
            val totalW = creditW + gap + textW
            val startX = (sw - totalW) * 0.5f
            val bottomMargin = (sh * 0.048f).coerceIn(12f, 28f)
            val creditY = sh - bottomMargin - creditH

            val creditAlpha = (creditOpacity * 255f).roundToInt().coerceIn(0, 255)
            val creditShadowArgb = ARGB.color((creditOpacity * 110f).roundToInt().coerceIn(0, 255), 0, 0, 0)
            val creditArgb = ARGB.color(creditAlpha, 255, 255, 255)
            textureSetup(TEX_CREDIT, FilterMode.LINEAR)?.let { setup ->
                ctx.drawTexQuad(
                    textureSetup = setup,
                    x0 = startX + 0.8f,
                    y0 = creditY + 0.8f,
                    x1 = startX + creditW + 0.8f,
                    y1 = creditY + creditH + 0.8f,
                    u1 = 0f,
                    v1 = 0f,
                    u2 = 1f,
                    v2 = 1f,
                    argb = creditShadowArgb,
                    pipeline = ClientRenderPipelines.GUI.SmoothTexture,
                )
                ctx.drawTexQuad(
                    textureSetup = setup,
                    x0 = startX,
                    y0 = creditY,
                    x1 = startX + creditW,
                    y1 = creditY + creditH,
                    u1 = 0f,
                    v1 = 0f,
                    u2 = 1f,
                    v2 = 1f,
                    argb = creditArgb,
                    pipeline = ClientRenderPipelines.GUI.SmoothTexture,
                )
            }

            if (drawBuild) {
                val writeProgress = ease((elapsedMs - 920f) / 560f)
                if (writeProgress > 0.001f) {
                    drawHandwrittenBuild(
                        ctx = ctx,
                        text = buildLabel,
                        startX = startX + creditW + gap,
                        creditY = creditY,
                        creditH = creditH,
                        glyphScale = glyphScale,
                        writeProgress = writeProgress,
                        inkArgb = creditArgb,
                        shadowArgb = creditShadowArgb,
                    )
                }
            }
        }
    }

    private fun measureHandwrittenBuild(text: String, glyphScale: Float): Float {
        var width = 0f
        for (rawCh in text) {
            val glyph = BUILD_GLYPHS[rawCh.lowercaseChar()]
            width += (glyph?.advancePx ?: 13f) * glyphScale
        }
        // Account for right-slanted overhang of final handwritten stroke inside its 64px cell
        return if (width > 0f) width + 8f * glyphScale else 0f
    }

    @Suppress("LongParameterList")
    private fun drawHandwrittenBuild(
        ctx: GuiGraphicsExtractor,
        text: String,
        startX: Float,
        creditY: Float,
        creditH: Float,
        glyphScale: Float,
        writeProgress: Float,
        inkArgb: Int,
        shadowArgb: Int,
    ) {
        val setup = textureSetup(TEX_BUILD_INK, FilterMode.NEAREST) ?: return

        var totalArc = 0f
        for (rawCh in text) {
            val glyph = BUILD_GLYPHS[rawCh.lowercaseChar()] ?: continue
            totalArc += glyph.arcPx.toFloat() + 8f
        }
        if (totalArc <= 0f) {
            return
        }

        val targetArc = totalArc * writeProgress.coerceIn(0f, 1f)
        val cellDrawW = BUILD_CELL_W * glyphScale
        val cellDrawH = BUILD_CELL_H * glyphScale
        val baseDrawY = creditY + creditH * 0.560f - BUILD_BASELINE_Y * glyphScale

        // Pass 1: subtle drop shadow matching TEX_CREDIT (+0.8px, +0.8px)
        // Pass 2: crisp white rollerball ink
        for (pass in 0..1) {
            val argb = if (pass == 0) shadowArgb else inkArgb
            if ((argb ushr 24) == 0) continue
            val ox = if (pass == 0) 0.8f else 0f
            val oy = if (pass == 0) 0.8f else 0f

            var cursorX = startX
            var arcCursor = 0f
            for ((idx, rawCh) in text.withIndex()) {
                val glyph = BUILD_GLYPHS[rawCh.lowercaseChar()]
                if (glyph == null) {
                    cursorX += 13f * glyphScale
                    continue
                }
                val charProgress = ((targetArc - arcCursor) / glyph.arcPx.toFloat()).coerceIn(0f, 1f)
                if (charProgress > 0.002f) {
                    val jitterY = sin(idx * 2.1f) * 0.35f * glyphScale
                    val x0 = cursorX + ox
                    val y0 = baseDrawY + jitterY + oy
                    val x1 = x0 + cellDrawW
                    val y1 = y0 + cellDrawH
                    val u0 = glyph.col.toFloat() / BUILD_COLS
                    val v0 = glyph.row.toFloat() / BUILD_ROWS
                    val u1 = (glyph.col + 1).toFloat() / BUILD_COLS
                    val v1 = (glyph.row + 1).toFloat() / BUILD_ROWS

                    ctx.guiRenderState.addGuiElement(
                        InkRevealGuiElementRenderState(
                            x0,
                            y0,
                            x1,
                            y1,
                            u0,
                            v0,
                            u1,
                            v1,
                            charProgress,
                            glyph.arcPx,
                            BUILD_NOMINAL_RADIUS,
                            1,
                            argb,
                            setup,
                            ctx.copyPosePooled(),
                            ctx.scissorStack.peek(),
                            ctx.getBounds(x0, y0, x1, y1),
                        )
                    )
                }
                cursorX += glyph.advancePx * glyphScale
                arcCursor += glyph.arcPx.toFloat() + 8f
                if (arcCursor >= targetArc) {
                    break
                }
            }
        }
    }

    @Suppress("LongParameterList")
    private fun drawInkFieldLayer(
        ctx: GuiGraphicsExtractor,
        inkId: Identifier,
        progress: Float,
        overlayOpacity: Float,
        totalArcPx: Int,
        nominalRadius: Float,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ) {
        val clamped = progress.coerceIn(0f, 1f)
        if (clamped <= 0.0005f || x1 <= x0 || y1 <= y0) {
            return
        }
        val alpha = (overlayOpacity * 255f).roundToInt().coerceIn(0, 255)
        if (alpha == 0) {
            return
        }
        val setup = textureSetup(inkId, FilterMode.NEAREST) ?: return
        val argb = ARGB.color(alpha, 255, 255, 255)

        ctx.guiRenderState.addGuiElement(
            InkRevealGuiElementRenderState(
                x0,
                y0,
                x1,
                y1,
                0f,
                0f,
                1f,
                1f,
                clamped,
                totalArcPx,
                nominalRadius,
                0,
                argb,
                setup,
                ctx.copyPosePooled(),
                ctx.scissorStack.peek(),
                ctx.getBounds(x0, y0, x1, y1),
            )
        )
    }
}
