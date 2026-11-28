/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.TtfFont
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Typeface
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.textureSetup
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure-typographic 2D planar overlay lyrics inspired by Apple Music Sing, Salt Player, and Lyricify.
 *
 * Completely discards all legacy GUI cards/boxes/progress bars and uses strictly ZERO glow/bloom:
 * - [ModuleMusic.ScreenPreset.CASCADE]: 3-line spring-cascading lyric stream with per-syllable wave lift & sub-pixel ink sweep.
 * - [ModuleMusic.ScreenPreset.DUAL]: Classic flagship desktop staggered left/right dual-line karaoke without boxes.
 * - [ModuleMusic.ScreenPreset.KARAOKE]: Studio centered headline + romanisation + translation with liquid syllable fill.
 * - [ModuleMusic.ScreenPreset.EDITORIAL]: Left-anchored kinetic editorial typography with a clean rhythm tick.
 */
object ScreenLyrics {

    private const val BASE_FONT_NOMINAL = 22f

    private var lyricFace: TtfFont? = null
    private var faceAttempted = false

    private var lastSongKey = ""
    private var lastLineIndex = -1
    private var lastFrameNanos = 0L

    // Smooth spring-damped cascade offset in normalized line strides (-1..+1 -> 0).
    private var cascadeOffset = 0f
    private var entryFade = 0f

    fun clear() {
        lastSongKey = ""
        lastLineIndex = -1
        lastFrameNanos = 0L
        cascadeOffset = 0f
        entryFade = 0f
    }

    private fun face(): TtfFont? {
        if (!faceAttempted) {
            faceAttempted = true
            lyricFace = TtfFont.loadOrNull(Typeface.BUNDLED_FONT, BASE_FONT_NOMINAL, 4f, 2048, "screenlyrics100")
                ?: Typeface.fallback()
            if (lyricFace == null) {
                logger.warn("[Music] 2D screen lyrics font unavailable")
            }
        }
        return lyricFace
    }

    fun render(
        ctx: GuiGraphicsExtractor,
        preset: ModuleMusic.ScreenPreset,
        x: Float,
        y: Float,
        size: Float,
        translation: Boolean,
    ) {
        val song = MusicManager.current ?: run {
            clear()
            return
        }
        val lines = MusicManager.lyrics()
        if (MusicManager.lyricsSongId() != song.id || lines.isEmpty()) {
            clear()
            return
        }
        val font = face() ?: return
        val sw = ctx.guiWidth().toFloat()
        val sh = ctx.guiHeight().toFloat()
        if (sw < 48f || sh < 48f) return

        val now = MusicManager.positionMs()
        val nanos = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f else ((nanos - lastFrameNanos) / 1e9f).coerceIn(0.001f, 0.1f)
        lastFrameNanos = nanos

        val songKey = "${song.provider}:${song.id}:${song.providerHash}"
        val idx = LyricLayout.index(lines, now)

        if (songKey != lastSongKey) {
            lastSongKey = songKey
            lastLineIndex = idx
            cascadeOffset = 0f
            entryFade = 0f
        } else if (idx != lastLineIndex) {
            val step = (idx - lastLineIndex).coerceIn(-2, 2)
            cascadeOffset = (cascadeOffset + step.toFloat()).coerceIn(-1.6f, 1.6f)
            lastLineIndex = idx
            entryFade = 0f
        }

        val springK = (1f - exp(-dt * 13.5f)).coerceIn(0f, 1f)
        cascadeOffset *= (1f - springK)
        if (abs(cascadeOffset) < 0.002f) cascadeOffset = 0f
        entryFade = (entryFade + dt * 5.2f).coerceIn(0f, 1f)

        val userScale = (size.coerceIn(0.5f, 2.5f) * MusicManager.lyricScale()).coerceIn(0.35f, 3.8f)
        val palette = resolvePalette()

        // Handle intro / instrumental interlude before the next vocal phrase with clean minimal rhythm dots.
        val activeLine = lines.getOrNull(idx)
        val nextLine = lines.getOrNull(idx + 1)
        val inPostGap = activeLine == null || now > activeLine.endMs + 420L
        if (inPostGap) {
            if (nextLine != null) {
                val untilNext = nextLine.timeMs - now
                if (untilNext in 1L..3200L) {
                    renderInterludeCountIn(ctx, font, nextLine, untilNext, sw, sh, x, y, userScale, palette)
                }
            }
            return
        }

        val line = activeLine ?: return
        if (line.text.isBlank()) return

        when (preset) {
            ModuleMusic.ScreenPreset.CASCADE ->
                renderCascade(ctx, font, lines, idx, now, sw, sh, x, y, userScale, translation, palette)
            ModuleMusic.ScreenPreset.DUAL ->
                renderDualDesktop(ctx, font, lines, idx, now, sw, sh, x, y, userScale, translation, palette)
            ModuleMusic.ScreenPreset.KARAOKE ->
                renderStudioKaraoke(ctx, font, line, now, sw, sh, x, y, userScale, translation, palette)
            ModuleMusic.ScreenPreset.EDITORIAL ->
                renderEditorial(ctx, font, lines, idx, now, sw, sh, x, y, userScale, translation, palette)
        }
    }

    private data class LyricPalette(
        val restingRgb: Int,
        val activeRgb: Int,
        val subRgb: Int,
        val shadowStrength: Float,
    )

    private fun resolvePalette(): LyricPalette {
        val mode = ModuleMusic.screenLyricColorMode()
        fun role(presetRgb: Int, customArgb: Int): Int = when (mode) {
            ModuleMusic.LyricColorMode.PRESET -> presetRgb and 0xFFFFFF
            ModuleMusic.LyricColorMode.SOLID -> customArgb and 0xFFFFFF
            ModuleMusic.LyricColorMode.BLEND -> ModuleMusic.blendRgb(presetRgb, customArgb and 0xFFFFFF, 0.62f)
        }
        val baseRgb = role(0xF6F8FC, ModuleMusic.screenLyricColor().argb)
        val accentRgb = role(0x9EE2F8, ModuleMusic.screenLyricAccent().argb)
        val subRgb = role(0xBAC3D4, ModuleMusic.screenLyricTranslationColor().argb)
        val shadow = ModuleMusic.screenLyricShadow().coerceIn(0f, 3f)
        return LyricPalette(
            restingRgb = baseRgb,
            activeRgb = if (mode == ModuleMusic.LyricColorMode.SOLID) baseRgb else accentRgb,
            subRgb = subRgb,
            shadowStrength = shadow,
        )
    }

    /**
     * Preset 1: Apple Music / Salt Player 3-line cascading stream.
     * Previous line fades gently above; active line sits sharp in focus with syllable lift + liquid ink sweep;
     * upcoming line previews calmly below. Zero boxes, zero glow.
     */
    private fun renderCascade(
        ctx: GuiGraphicsExtractor,
        font: TtfFont,
        lines: List<LyricLine>,
        idx: Int,
        now: Long,
        sw: Float,
        sh: Float,
        xPct: Float,
        yPct: Float,
        scale: Float,
        showTranslation: Boolean,
        palette: LyricPalette,
    ) {
        val active = lines[idx]
        val prev = lines.getOrNull(idx - 1)?.takeIf { it.text.isNotBlank() }
        val next = lines.getOrNull(idx + 1)?.takeIf { it.text.isNotBlank() }

        val activePx = 20.5f * scale
        val contextPx = 14.5f * scale
        val subPx = 12.5f * scale
        val maxLineW = (sw - 36f).coerceAtMost(620f * scale).coerceAtLeast(120f)

        val activeRuns = wrapMeasured(font, active.text, activePx, maxLineW).take(3)
        val transRuns = if (showTranslation && active.translation.isNotBlank()) {
            wrapMeasured(font, active.translation, subPx, maxLineW * 0.92f).take(2)
        } else {
            emptyList()
        }

        val activeLineStep = activePx * 1.32f
        val subLineStep = subPx * 1.34f
        val activeBlockH = activeRuns.size * activeLineStep +
            (if (transRuns.isNotEmpty()) 3f * scale + transRuns.size * subLineStep else 0f)
        val totalH = activeBlockH + 46f * scale

        val widest = max(
            activeRuns.maxOfOrNull { measure(font, it.text, activePx) } ?: 0f,
            max(
                prev?.let { min(maxLineW, measure(font, it.text, contextPx)) } ?: 0f,
                next?.let { min(maxLineW, measure(font, it.text, contextPx)) } ?: 0f,
            ),
        ).coerceAtLeast(80f)

        val box = LyricLayout.fitBox(sw, sh, widest, totalH, 1f, xPct, yPct) ?: return
        val cx = box.x
        val cy = box.y

        val outroFade = if (now <= active.endMs) {
            1f
        } else {
            1f - LyricLayout.smooth(((now - active.endMs) / 420f).coerceIn(0f, 1f))
        }
        val slidePx = cascadeOffset * (22f * scale)
        val kinetics = LyricLayout.evaluateKinetics(active, now)

        // 1) Previous line (muted context above)
        if (prev != null) {
            val prevText = ellipsize(font, prev.text, contextPx, maxLineW)
            val prevAlpha = (0.24f * (1f - abs(cascadeOffset) * 0.35f) * outroFade).coerceIn(0f, 0.35f)
            val prevY = cy - activeBlockH * 0.5f - 17f * scale + slidePx * 0.85f
            drawPlainString(ctx, font, prevText, cx, prevY, contextPx, palette.subRgb, prevAlpha, Align.CENTER, palette.shadowStrength * 0.7f)
        }

        // 2) Active vocal line (sub-pixel syllable sweep + per-word gentle upward lift)
        var rowY = cy - activeBlockH * 0.5f + activePx * 0.62f + slidePx
        val activeAlpha = (LyricLayout.smooth(entryFade * 1.25f) * 0.18f + 0.82f) * outroFade
        for (run in activeRuns) {
            drawKineticRun(
                ctx = ctx,
                font = font,
                run = run,
                kinetics = kinetics,
                anchorX = cx,
                centerY = rowY,
                sizePx = activePx,
                restingRgb = palette.restingRgb,
                restingAlpha = activeAlpha * 0.48f,
                activeRgb = palette.activeRgb,
                activeAlpha = activeAlpha,
                liftAmplitudePx = 1.85f * scale,
                align = Align.CENTER,
                shadowStrength = palette.shadowStrength,
            )
            rowY += activeLineStep
        }

        // 3) Crisp translation caption directly below active vocal line
        if (transRuns.isNotEmpty()) {
            rowY += 1.5f * scale
            for (tr in transRuns) {
                drawPlainString(
                    ctx, font, tr.text, cx, rowY, subPx,
                    palette.subRgb, (0.82f * activeAlpha).coerceIn(0f, 1f),
                    Align.CENTER, palette.shadowStrength * 0.75f,
                )
                rowY += subLineStep
            }
        }

        // 4) Upcoming next line (calm preview below)
        if (next != null) {
            val nextText = ellipsize(font, next.text, contextPx, maxLineW)
            val leadBoost = if (next.timeMs - now in 0L..900L) 0.14f else 0f
            val nextAlpha = ((0.34f + leadBoost) * outroFade).coerceIn(0f, 0.55f)
            val nextY = cy + activeBlockH * 0.5f + 16f * scale + slidePx * 0.85f
            drawPlainString(ctx, font, nextText, cx, nextY, contextPx, palette.subRgb, nextAlpha, Align.CENTER, palette.shadowStrength * 0.7f)
        }
    }

    /**
     * Preset 2: Classic flagship desktop overlay staggered dual-line karaoke (no cards, no borders).
     * Even-indexed lines sit on the upper-left lane; odd-indexed lines sit on the lower-right lane,
     * keeping the upcoming line pre-staged so the eye never hunts.
     */
    private fun renderDualDesktop(
        ctx: GuiGraphicsExtractor,
        font: TtfFont,
        lines: List<LyricLine>,
        idx: Int,
        now: Long,
        sw: Float,
        sh: Float,
        xPct: Float,
        yPct: Float,
        scale: Float,
        showTranslation: Boolean,
        palette: LyricPalette,
    ) {
        val active = lines[idx]
        val partner = lines.getOrNull(idx + 1)?.takeIf { it.text.isNotBlank() }

        val mainPx = 19.5f * scale
        val partnerPx = 16.5f * scale
        val subPx = 11.5f * scale
        val laneSpanW = (sw - 44f).coerceAtMost(560f * scale).coerceAtLeast(140f)
        val totalH = (if (showTranslation && active.translation.isNotBlank()) 68f else 52f) * scale

        val box = LyricLayout.fitBox(sw, sh, laneSpanW, totalH, 1f, xPct, yPct) ?: return
        val leftX = box.x - laneSpanW * 0.5f
        val rightX = box.x + laneSpanW * 0.5f
        val upperY = box.y - 13f * scale
        val lowerY = box.y + 14f * scale

        val activeOnTop = (idx and 1) == 0
        val activeY = if (activeOnTop) upperY else lowerY
        val partnerY = if (activeOnTop) lowerY else upperY

        val outroFade = if (now <= active.endMs) 1f else 1f - LyricLayout.smooth(((now - active.endMs) / 400f).coerceIn(0f, 1f))
        val kinetics = LyricLayout.evaluateKinetics(active, now)
        val activeDisplay = ellipsize(font, active.text, mainPx, laneSpanW * 0.92f)
        val activeRun = LyricLayout.Run(activeDisplay, 0, min(activeDisplay.length, active.text.length))

        drawKineticRun(
            ctx = ctx,
            font = font,
            run = activeRun,
            kinetics = kinetics,
            anchorX = if (activeOnTop) leftX else rightX,
            centerY = activeY,
            sizePx = mainPx,
            restingRgb = palette.restingRgb,
            restingAlpha = 0.50f * outroFade,
            activeRgb = palette.activeRgb,
            activeAlpha = outroFade,
            liftAmplitudePx = 1.65f * scale,
            align = if (activeOnTop) Align.LEFT else Align.RIGHT,
            shadowStrength = palette.shadowStrength,
        )

        if (showTranslation && active.translation.isNotBlank()) {
            val transText = ellipsize(font, active.translation, subPx, laneSpanW * 0.85f)
            val ty = activeY + mainPx * 0.82f
            drawPlainString(
                ctx, font, transText,
                if (activeOnTop) leftX else rightX,
                ty, subPx, palette.subRgb, 0.78f * outroFade,
                if (activeOnTop) Align.LEFT else Align.RIGHT,
                palette.shadowStrength * 0.7f,
            )
        }

        if (partner != null) {
            val partnerText = ellipsize(font, partner.text, partnerPx, laneSpanW * 0.88f)
            drawPlainString(
                ctx, font, partnerText,
                if (activeOnTop) rightX else leftX,
                partnerY, partnerPx, palette.restingRgb, 0.40f * outroFade,
                if (activeOnTop) Align.RIGHT else Align.LEFT,
                palette.shadowStrength * 0.75f,
            )
        }
    }

    /**
     * Preset 3: Studio Cinema Karaoke — single ultra-clean focal vocal line with optional romanisation
     * caption above and translation subtitle below.
     */
    private fun renderStudioKaraoke(
        ctx: GuiGraphicsExtractor,
        font: TtfFont,
        line: LyricLine,
        now: Long,
        sw: Float,
        sh: Float,
        xPct: Float,
        yPct: Float,
        scale: Float,
        showTranslation: Boolean,
        palette: LyricPalette,
    ) {
        val mainPx = 22f * scale
        val romaPx = 11f * scale
        val subPx = 13f * scale
        val maxW = (sw - 36f).coerceAtMost(650f * scale).coerceAtLeast(120f)

        val mainRuns = wrapMeasured(font, line.text, mainPx, maxW).take(3)
        val hasRoma = line.romanisation.isNotBlank()
        val transRuns = if (showTranslation && line.translation.isNotBlank()) {
            wrapMeasured(font, line.translation, subPx, maxW * 0.9f).take(2)
        } else {
            emptyList()
        }

        val mainStep = mainPx * 1.30f
        val subStep = subPx * 1.32f
        val totalH = (if (hasRoma) romaPx * 1.4f else 0f) +
            mainRuns.size * mainStep +
            (if (transRuns.isNotEmpty()) 4f * scale + transRuns.size * subStep else 0f)
        val widest = max(
            mainRuns.maxOfOrNull { measure(font, it.text, mainPx) } ?: 0f,
            transRuns.maxOfOrNull { measure(font, it.text, subPx) } ?: 0f,
        ).coerceAtLeast(80f)

        val box = LyricLayout.fitBox(sw, sh, widest, totalH, 1f, xPct, yPct) ?: return
        val fadeIn = LyricLayout.smooth(entryFade)
        val fadeOut = if (now <= line.endMs) 1f else 1f - LyricLayout.smooth(((now - line.endMs) / 400f).coerceIn(0f, 1f))
        val alpha = (fadeIn * fadeOut).coerceIn(0f, 1f)
        val liftIn = (1f - fadeIn) * 4.5f * scale

        var cursorY = box.y - totalH * 0.5f + liftIn
        if (hasRoma) {
            cursorY += romaPx * 0.6f
            val roma = ellipsize(font, line.romanisation, romaPx, maxW * 0.92f)
            drawPlainString(ctx, font, roma, box.x, cursorY, romaPx, palette.subRgb, alpha * 0.66f, Align.CENTER, palette.shadowStrength * 0.65f)
            cursorY += romaPx * 0.85f
        }

        val kinetics = LyricLayout.evaluateKinetics(line, now)
        cursorY += mainPx * 0.6f
        for (run in mainRuns) {
            drawKineticRun(
                ctx = ctx,
                font = font,
                run = run,
                kinetics = kinetics,
                anchorX = box.x,
                centerY = cursorY,
                sizePx = mainPx,
                restingRgb = palette.restingRgb,
                restingAlpha = alpha * 0.46f,
                activeRgb = palette.activeRgb,
                activeAlpha = alpha,
                liftAmplitudePx = 2.1f * scale,
                align = Align.CENTER,
                shadowStrength = palette.shadowStrength,
            )
            cursorY += mainStep
        }

        if (transRuns.isNotEmpty()) {
            cursorY += 2f * scale
            for (tr in transRuns) {
                drawPlainString(ctx, font, tr.text, box.x, cursorY, subPx, palette.subRgb, alpha * 0.84f, Align.CENTER, palette.shadowStrength * 0.75f)
                cursorY += subStep
            }
        }
    }

    /**
     * Preset 4: Lyricify Editorial — crisp left-aligned kinetic stack with a razor-thin vertical accent tick
     * (pure typographic punctuation mark, zero card background, zero glow).
     */
    private fun renderEditorial(
        ctx: GuiGraphicsExtractor,
        font: TtfFont,
        lines: List<LyricLine>,
        idx: Int,
        now: Long,
        sw: Float,
        sh: Float,
        xPct: Float,
        yPct: Float,
        scale: Float,
        showTranslation: Boolean,
        palette: LyricPalette,
    ) {
        val active = lines[idx]
        val next = lines.getOrNull(idx + 1)?.takeIf { it.text.isNotBlank() }

        val mainPx = 19.5f * scale
        val subPx = 12.2f * scale
        val nextPx = 13.5f * scale
        val maxW = (sw - 48f).coerceAtMost(500f * scale).coerceAtLeast(120f)

        val mainRuns = wrapMeasured(font, active.text, mainPx, maxW).take(2)
        val transText = if (showTranslation && active.translation.isNotBlank()) {
            ellipsize(font, active.translation, subPx, maxW)
        } else {
            ""
        }
        val mainStep = mainPx * 1.28f
        val activeH = mainRuns.size * mainStep + (if (transText.isNotEmpty()) subPx * 1.45f else 0f)
        val totalH = activeH + (if (next != null) nextPx * 1.65f else 0f)
        val widest = max(
            mainRuns.maxOfOrNull { measure(font, it.text, mainPx) } ?: 0f,
            max(
                if (transText.isNotEmpty()) measure(font, transText, subPx) else 0f,
                next?.let { min(maxW, measure(font, it.text, nextPx)) } ?: 0f,
            ),
        ).coerceAtLeast(100f) + 12f * scale

        val box = LyricLayout.fitBox(sw, sh, widest, totalH, 1f, xPct, yPct) ?: return
        val leftX = box.x - widest * 0.5f + 9f * scale
        val topY = box.y - totalH * 0.5f
        val outroFade = if (now <= active.endMs) 1f else 1f - LyricLayout.smooth(((now - active.endMs) / 400f).coerceIn(0f, 1f))
        val kinetics = LyricLayout.evaluateKinetics(active, now)

        // Minimal 2px vertical rhythm bar tracking line progress — crisp solid quad, zero glow.
        val barX = box.x - widest * 0.5f
        val barH = activeH.coerceAtLeast(mainPx)
        drawCrispTick(ctx, barX, topY + 2f * scale, 2f * scale, barH - 4f * scale, palette.restingRgb, 0.22f * outroFade)
        val filledBarH = (barH - 4f * scale) * kinetics.lineProgress.coerceIn(0f, 1f)
        if (filledBarH > 0.5f) {
            drawCrispTick(ctx, barX, topY + 2f * scale, 2f * scale, filledBarH, palette.activeRgb, 0.92f * outroFade)
        }

        var cursorY = topY + mainPx * 0.58f + cascadeOffset * 14f * scale
        for (run in mainRuns) {
            drawKineticRun(
                ctx = ctx,
                font = font,
                run = run,
                kinetics = kinetics,
                anchorX = leftX,
                centerY = cursorY,
                sizePx = mainPx,
                restingRgb = palette.restingRgb,
                restingAlpha = 0.48f * outroFade,
                activeRgb = palette.activeRgb,
                activeAlpha = outroFade,
                liftAmplitudePx = 1.6f * scale,
                align = Align.LEFT,
                shadowStrength = palette.shadowStrength,
            )
            cursorY += mainStep
        }

        if (transText.isNotEmpty()) {
            drawPlainString(ctx, font, transText, leftX, cursorY + 1f * scale, subPx, palette.subRgb, 0.80f * outroFade, Align.LEFT, palette.shadowStrength * 0.7f)
            cursorY += subPx * 1.45f
        }

        if (next != null) {
            val nextStr = ellipsize(font, next.text, nextPx, maxW)
            drawPlainString(ctx, font, nextStr, leftX, cursorY + 4f * scale, nextPx, palette.subRgb, 0.34f * outroFade, Align.LEFT, palette.shadowStrength * 0.65f)
        }
    }

    /** Calm 3-dot count-in during instrumental breaks right before the next lyric enters. */
    private fun renderInterludeCountIn(
        ctx: GuiGraphicsExtractor,
        font: TtfFont,
        nextLine: LyricLine,
        untilNextMs: Long,
        sw: Float,
        sh: Float,
        xPct: Float,
        yPct: Float,
        scale: Float,
        palette: LyricPalette,
    ) {
        val progress = (1f - untilNextMs.toFloat() / 3200f).coerceIn(0f, 1f)
        val previewPx = 14f * scale
        val maxW = (sw - 40f).coerceAtMost(480f * scale)
        val preview = ellipsize(font, nextLine.text, previewPx, maxW)
        val box = LyricLayout.fitBox(sw, sh, max(80f, measure(font, preview, previewPx)), 36f * scale, 1f, xPct, yPct) ?: return

        val dotFontPx = 16f * scale
        val spacing = 11f * scale
        for (i in 0 until 3) {
            val threshold = (i + 1) / 3.2f
            val lit = ((progress - (threshold - 0.28f)) / 0.28f).coerceIn(0f, 1f)
            val lift = -sin(lit * PI.toFloat()) * 1.6f * scale
            val rgb = if (lit > 0.5f) palette.activeRgb else palette.restingRgb
            val a = 0.28f + 0.64f * lit
            drawPlainString(
                ctx, font, "•",
                box.x + (i - 1) * spacing,
                box.y - 7f * scale + lift,
                dotFontPx, rgb, a, Align.CENTER, palette.shadowStrength * 0.6f,
            )
        }
        drawPlainString(
            ctx, font, preview,
            box.x, box.y + 10f * scale,
            previewPx, palette.subRgb, 0.36f * LyricLayout.smooth(progress * 1.4f),
            Align.CENTER, palette.shadowStrength * 0.65f,
        )
    }

    // --------------------------------------------------------- glyph rendering

    private enum class Align { LEFT, CENTER, RIGHT }

    private fun unitFor(font: TtfFont, sizePx: Float): Float = sizePx / max(1f, font.size)

    private fun measure(font: TtfFont, text: String, sizePx: Float): Float =
        font.width(text) * unitFor(font, sizePx)

    private fun wrapMeasured(font: TtfFont, text: String, sizePx: Float, maxWidth: Float): List<LyricLayout.Run> =
        LyricLayout.wrap(text, maxWidth) { measure(font, it, sizePx) }

    private fun ellipsize(font: TtfFont, text: String, sizePx: Float, maxWidth: Float): String {
        val clean = text.replace('\n', ' ').trim()
        if (measure(font, clean, sizePx) <= maxWidth) return clean
        val ellipsis = "…"
        val budget = (maxWidth - measure(font, ellipsis, sizePx)).coerceAtLeast(0f)
        var lo = 0
        var hi = clean.length
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (measure(font, clean.substring(0, mid), sizePx) <= budget) lo = mid else hi = mid - 1
        }
        if (lo in 1 until clean.length && Character.isHighSurrogate(clean[lo - 1])) lo--
        return clean.substring(0, lo).trimEnd() + ellipsis
    }

    private fun prepareTexture(font: TtfFont, text: String): TextureSetup? {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = if (cp > 0xFFFF) '\u25A1' else cp.toChar()
            font.glyph(ch)
            i += Character.charCount(cp)
        }
        font.flush()
        val id = font.textureId() ?: return null
        return mc.textureManager.getTexture(id).textureSetup
    }

    private fun drawPlainString(
        ctx: GuiGraphicsExtractor,
        font: TtfFont,
        text: String,
        anchorX: Float,
        centerY: Float,
        sizePx: Float,
        rgb: Int,
        alpha: Float,
        align: Align,
        shadowStrength: Float,
    ) {
        if (text.isEmpty() || alpha <= 0.004f) return
        val tex = prepareTexture(font, text) ?: return
        val unit = unitFor(font, sizePx)
        val totalW = font.width(text) * unit
        val startX = when (align) {
            Align.LEFT -> anchorX
            Align.CENTER -> anchorX - totalW * 0.5f
            Align.RIGHT -> anchorX - totalW
        }
        val baselineY = centerY + (font.ascent - font.descent) * 0.5f * unit - TtfFont.GLYPH_TOP_OFFSET * unit
        val mainArgb = packArgb(rgb, alpha)
        val shadowArgb = if (shadowStrength > 0.02f) packArgb(0x000000, alpha * (0.48f * min(1.35f, shadowStrength)).coerceIn(0f, 0.72f)) else 0
        val sdx = 0.72f * shadowStrength
        val sdy = 0.98f * shadowStrength

        var caret = 0f
        var idx = 0
        while (idx < text.length) {
            val cp = text.codePointAt(idx)
            val ch = if (cp > 0xFFFF) '\u25A1' else cp.toChar()
            val g = font.glyph(ch)
            if (!g.isEmpty()) {
                val x0 = startX + (caret + g.bearingX) * unit
                val y0 = baselineY + (TtfFont.GLYPH_TOP_OFFSET + g.bearingY) * unit
                val x1 = x0 + g.width * unit
                val y1 = y0 + g.height * unit
                if (shadowArgb ushr 24 != 0) {
                    ctx.drawTexQuad(tex, x0 + sdx, y0 + sdy, x1 + sdx, y1 + sdy, g.u0, g.v0, g.u1, g.v1, shadowArgb)
                }
                ctx.drawTexQuad(tex, x0, y0, x1, y1, g.u0, g.v0, g.u1, g.v1, mainArgb)
            }
            caret += g.advance
            idx += Character.charCount(cp)
        }
    }

    /**
     * Sub-pixel UV-clipped syllable sweep + per-character kinetic lift.
     * Zero integer scissor snapping and zero halo/glow passes.
     */
    private fun drawKineticRun(
        ctx: GuiGraphicsExtractor,
        font: TtfFont,
        run: LyricLayout.Run,
        kinetics: LyricLayout.LineKinetics,
        anchorX: Float,
        centerY: Float,
        sizePx: Float,
        restingRgb: Int,
        restingAlpha: Float,
        activeRgb: Int,
        activeAlpha: Float,
        liftAmplitudePx: Float,
        align: Align,
        shadowStrength: Float,
    ) {
        val text = run.text
        if (text.isEmpty() || max(restingAlpha, activeAlpha) <= 0.004f) return
        val tex = prepareTexture(font, text) ?: return
        val unit = unitFor(font, sizePx)
        val totalW = font.width(text) * unit
        val startX = when (align) {
            Align.LEFT -> anchorX
            Align.CENTER -> anchorX - totalW * 0.5f
            Align.RIGHT -> anchorX - totalW
        }
        val baselineY = centerY + (font.ascent - font.descent) * 0.5f * unit - TtfFont.GLYPH_TOP_OFFSET * unit

        val restArgb = packArgb(restingRgb, restingAlpha)
        val actArgb = packArgb(activeRgb, activeAlpha)
        val sdx = 0.75f * shadowStrength
        val sdy = 1.02f * shadowStrength

        var caret = 0f
        var localIdx = 0
        while (localIdx < text.length) {
            val cp = text.codePointAt(localIdx)
            val ch = if (cp > 0xFFFF) '\u25A1' else cp.toChar()
            val g = font.glyph(ch)
            val globalCharIdx = run.start + localIdx
            val fill = kinetics.fillAt(globalCharIdx).coerceIn(0f, 1f)
            val lift = -kinetics.liftAt(globalCharIdx) * liftAmplitudePx

            if (!g.isEmpty()) {
                val x0 = startX + (caret + g.bearingX) * unit
                val y0 = baselineY + (TtfFont.GLYPH_TOP_OFFSET + g.bearingY) * unit + lift
                val gw = g.width * unit
                val x1 = x0 + gw
                val y1 = y0 + g.height * unit

                // 1) Single-pass crisp dark offset shadow (zero blur/glow).
                if (shadowStrength > 0.02f) {
                    val shAlpha = max(restingAlpha, activeAlpha * fill) * (0.50f * min(1.35f, shadowStrength)).coerceIn(0f, 0.74f)
                    val shArgb = packArgb(0x000000, shAlpha)
                    if (shArgb ushr 24 != 0) {
                        ctx.drawTexQuad(tex, x0 + sdx, y0 + sdy, x1 + sdx, y1 + sdy, g.u0, g.v0, g.u1, g.v1, shArgb)
                    }
                }

                // 2) Unsung / resting portion of glyph.
                if (fill < 0.999f && (restArgb ushr 24) != 0) {
                    ctx.drawTexQuad(tex, x0, y0, x1, y1, g.u0, g.v0, g.u1, g.v1, restArgb)
                }

                // 3) Sub-pixel UV-clipped sung portion across the glyph's advance/ink box.
                if (fill > 0.001f && (actArgb ushr 24) != 0) {
                    if (fill >= 0.999f) {
                        ctx.drawTexQuad(tex, x0, y0, x1, y1, g.u0, g.v0, g.u1, g.v1, actArgb)
                    } else {
                        // Map fill across visible ink bounds [bearingX .. bearingX + advance] for natural stroke sweep.
                        val inkLeft = startX + caret * unit
                        val inkRight = startX + (caret + max(g.advance, g.bearingX + g.width * 0.75f)) * unit
                        val frontierX = inkLeft + (inkRight - inkLeft) * fill
                        val clippedRight = frontierX.coerceIn(x0, x1)
                        if (clippedRight > x0 + 0.02f && gw > 1e-4f) {
                            val frac = ((clippedRight - x0) / gw).coerceIn(0f, 1f)
                            val uMid = g.u0 + (g.u1 - g.u0) * frac
                            ctx.drawTexQuad(tex, x0, y0, clippedRight, y1, g.u0, g.v0, uMid, g.v1, actArgb)
                        }
                    }
                }
            }
            caret += g.advance
            localIdx += Character.charCount(cp)
        }
    }

    private fun drawCrispTick(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rgb: Int,
        alpha: Float,
    ) {
        val argb = packArgb(rgb, alpha)
        if ((argb ushr 24) == 0 || w <= 0f || h <= 0f) return
        val x1 = x + w
        val y1 = y + h
        ctx.drawCustomElement(
            pipeline = RenderPipelines.GUI,
            bounds = ctx.getBounds(x, y, x1, y1),
        ) { pose ->
            addVertexWith2DPose(pose, x, y).setColor(argb)
            addVertexWith2DPose(pose, x, y1).setColor(argb)
            addVertexWith2DPose(pose, x1, y1).setColor(argb)
            addVertexWith2DPose(pose, x1, y).setColor(argb)
        }
    }

    private fun packArgb(rgb: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)
}
