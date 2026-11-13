package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Material
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Scheme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Theme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Blur
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Spring
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Typeface
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.UI
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.WavyProgressIndicator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The in-window lyric view.
 *
 * <p>Ported 1:1 from NeoRaven's `music.LyricPanel`. The karaoke model is unchanged: each fragment is
 * filled as playback crosses its start/end, the head interpolates inside the current fragment, the
 * active line is the one whose `endMs` has not passed, the stack spring-scrolls to it, long lines
 * wrap at the panel width and the sung part of each wrapped run is clipped at its measured width.
 * Apple-Music focus (idle 0.34 alpha, active 1.05x) and the 30 px soft edge fade are kept as-is.</p>
 */
internal class LyricPanel(private val screen: MusicScreen) {

    private val scroll = Spring(0.0F)
    private val scale = Spring(1.0F)

    private var lineAlphas = FloatArray(0)

    private var activeIndex = -1
    private var activeChangedAt = 0L
    private var manualOffset = 0
    private var manualAt = 0L
    private var cachedScalePercent = -1
    private var activeStyle: Typeface.Style? = null
    private var idleStyle: Typeface.Style? = null
    private var translationStyle: Typeface.Style? = null

    private var panelX = 0.0F
    private var panelY = 0.0F
    private var panelW = 0.0F
    private var panelH = 0.0F
    private var closeX = 0.0F
    private var closeY = 0.0F
    private val lineHitBoxes = ArrayList<FloatArray>()
    private val lineHitIndices = ArrayList<Int>()

    fun reset() {
        activeIndex = -1
        manualOffset = 0
        scroll.set(0.0F)
        scale.set(1.0F)
    }

    fun nudge(direction: Float) {
        manualOffset = Material.clamp(manualOffset + direction, -3.0F, 3.0F).roundToInt()
        manualAt = System.currentTimeMillis()
    }

    fun tick(delta: Float) {
        val manager = MusicManager
        val lines = manager.lyrics()
        val index = indexAt(lines, manager.positionMs())
        if (index != activeIndex) {
            activeIndex = index
            activeChangedAt = System.currentTimeMillis()
            manualOffset = 0
        }
        if (System.currentTimeMillis() - manualAt > 2600L) manualOffset = 0
        val target = if (activeIndex < 0) 0.0F else (activeIndex + manualOffset).toFloat()
        scroll.target(target)
        scroll.update(delta)
        scale.target(1.0F)
        scale.update(delta)
        updateLineAlphas(lines, delta)
    }

    /** Eases each visible line's focus; rebuilt when the lyric list changes (track switch). */
    private fun updateLineAlphas(lines: List<LyricLine>, delta: Float) {
        if (lineAlphas.size != lines.size) {
            lineAlphas = FloatArray(lines.size)
            for (i in lineAlphas.indices) lineAlphas[i] = if (i == activeIndex) 1.0F else IDLE_LINE_ALPHA
            return
        }
        val rate = 1.0F - exp(-max(1.0F, delta) / 110.0F).toFloat()
        for (i in lineAlphas.indices) {
            if (abs(i - activeIndex) > 8) continue
            val wanted = if (i == activeIndex) 1.0F else IDLE_LINE_ALPHA
            lineAlphas[i] += (wanted - lineAlphas[i]) * rate
        }
    }

    private fun lineAlpha(index: Int): Float =
        if (index >= 0 && index < lineAlphas.size) lineAlphas[index] else 1.0F

    // --------------------------------------------------------------- drawing

    fun draw(ui: UI, scheme: Scheme, x: Float, y: Float, w: Float, h: Float) {
        panelX = x
        panelY = y
        panelW = w
        panelH = h
        lineHitBoxes.clear()
        lineHitIndices.clear()

        val manager = MusicManager
        val song = manager.current

        // Same treatment as the rest of the window: frosted glass over the blurred background,
        // falling back to a translucent surface when the frost pipeline is unavailable.
        if (Theme.frost() && Blur.ready()) {
            ui.rounded(x, y, w, h, 22.0F, Material.withAlpha(scheme.surfaceContainer, 0.55F))
            ui.frost(x, y, w, h, 22.0F, scheme.surfaceContainer, 0.82F)
        } else {
            ui.rounded(x, y, w, h, 22.0F, Material.withAlpha(scheme.surfaceContainer, 0.93F))
        }
        // A soft wash of the palette at the top keeps the panel from reading as a black hole.
        ui.gradient4(
            x, y, w, 120.0F, 22.0F,
            Material.withAlpha(scheme.primary, 0.10F), 0x00000000, 0x00000000,
            Material.withAlpha(scheme.primary, 0.02F),
        )

        ui.text(
            MusicTypeface.HEADLINE,
            if (song == null) "未在播放" else ui.truncate(MusicTypeface.HEADLINE, song.name, w - 120.0F),
            x + 24.0F, y + 26.0F, 0xFFF2F3F7.toInt(), UI.ALIGN_LEFT,
        )
        ui.text(
            MusicTypeface.CAPTION,
            if (song == null) "" else ui.truncate(MusicTypeface.CAPTION, song.artist, w - 120.0F),
            x + 24.0F, y + 42.0F, Material.withAlpha(0xFFD6D8E0.toInt(), 0.72F), UI.ALIGN_LEFT,
        )

        val closeSize = 32.0F
        closeX = x + w - closeSize - 12.0F
        closeY = y + 10.0F
        if (screen.mouseIn(closeX, closeY, closeSize, closeSize)) {
            ui.circle(
                closeX + closeSize / 2.0F, closeY + closeSize / 2.0F, closeSize / 2.0F,
                Material.withAlpha(0xFFFFFFFF.toInt(), 0.10F),
            )
        }
        ui.icon("\ue5cd", closeX + closeSize / 2.0F, closeY + closeSize / 2.0F, 17.0F, Material.withAlpha(0xFFFFFFFF.toInt(), 0.82F))

        val lines = manager.lyrics()
        if (lines.isEmpty()) {
            val message = if (manager.lyricsLoading()) "正在加载歌词…" else "暂无歌词"
            ui.text(
                MusicTypeface.BODY, message, x + w / 2.0F, y + h / 2.0F,
                Material.withAlpha(0xFFD6D8E0.toInt(), 0.7F), UI.ALIGN_CENTER,
            )
            if (manager.lyricsLoading()) {
                WavyProgressIndicator.drawCircularIndeterminate(ui, x + w / 2.0F, y + h / 2.0F - 30.0F, 22.0F)
            }
            return
        }

        prepareStyles(manager.lyricScale())
        val pitch = PITCH_BASE * manager.lyricScale()
        val centerY = y + h * 0.47F
        val position = manager.positionMs()
        val scrollValue = scroll.value()
        val top = y + 56.0F
        val bottom = y + h - 8.0F

        ui.scissorPush(x + 4.0F, top, w - 8.0F, bottom - top)
        for (i in lines.indices) {
            val distance = i - scrollValue
            if (distance < -FADE_LINES - 0.6F || distance > FADE_LINES + 0.6F) continue
            val line = lines[i]
            val lineY = centerY + distance * pitch
            val fade = 1.0F - min(1.0F, abs(distance) / FADE_LINES)
            // Dissolve into the top/bottom edges by pixel distance instead of painting a black
            // gradient over the frosted panel: the lines fade out before the scissor cuts them.
            val edge = min(edgeFade(lineY, top, bottom, EDGE_FADE), fade)
            var alpha = max(0.0F, min(fade, edge))
            if (alpha <= 0.01F) continue
            val active = i == activeIndex
            val focus = max(0.0F, min(1.0F, lineAlpha(i)))
            alpha *= focus
            if (alpha <= 0.01F) continue
            if (active) {
                // Apple Music draws the focused line slightly larger, pivoting on its own centre.
                ui.push()
                ui.scale(ACTIVE_LINE_SCALE, x + w / 2.0F, lineY)
                drawLine(ui, scheme, line, lineY, alpha, true, position, x, w)
                ui.pop()
            } else {
                drawLine(ui, scheme, line, lineY, alpha, false, position, x, w)
            }
            lineHitBoxes.add(floatArrayOf(x + 12.0F, lineY - pitch * 0.5F, w - 24.0F, pitch))
            lineHitIndices.add(i)
        }
        ui.scissorPop()
    }

    /** Soft ramp so glyphs reach zero alpha right at the panel edge, with no visible cut. */
    private fun edgeFade(lineY: Float, top: Float, bottom: Float, height: Float): Float {
        val above = (lineY - top) / height
        val below = (bottom - lineY) / height
        val t = Material.clamp(min(above, below), 0.0F, 1.0F)
        return t * t * (3.0F - 2.0F * t)
    }

    private fun prepareStyles(lyricScale: Float) {
        val percent = (lyricScale * 100.0F).roundToInt()
        if (percent == cachedScalePercent && activeStyle != null) return
        cachedScalePercent = percent
        val active = 25.0F * lyricScale
        val idle = 20.0F * lyricScale
        val translation = 12.0F * lyricScale
        activeStyle = MusicTypeface.style(active, active * 1.3F, true)
        idleStyle = MusicTypeface.style(idle, idle * 1.3F, false)
        translationStyle = MusicTypeface.style(translation, translation * 1.35F, false)
    }

    private fun drawLine(
        ui: UI, scheme: Scheme, line: LyricLine, lineY: Float, alpha: Float,
        active: Boolean, position: Long, panelLeft: Float, panelWidth: Float,
    ) {
        val layoutWidth = panelWidth - 48.0F
        val style = (if (active) activeStyle else idleStyle)!!
        val words = line.words

        // Long lines are wrapped at the panel width; the sweep then runs over the wrapped runs.
        val parts = ArrayList<String>()
        val partStart = ArrayList<Int>()
        if (style.width(line.text) <= layoutWidth) {
            parts.add(line.text)
            partStart.add(0)
        } else {
            var current = StringBuilder()
            var start = 0
            for (index in words.indices) {
                val word = words[index]
                val candidate = if (current.isEmpty()) word.text else current.toString() + word.text
                if (style.width(candidate) > layoutWidth && current.isNotEmpty()) {
                    parts.add(current.toString())
                    partStart.add(start)
                    current = StringBuilder()
                    start = index
                    current.append(word.text)
                } else {
                    current = StringBuilder()
                    current.append(candidate)
                }
            }
            if (current.isNotEmpty()) {
                parts.add(current.toString())
                partStart.add(start)
            }
        }
        if (parts.isEmpty()) {
            parts.add(line.text)
            partStart.add(0)
        }

        val wrapLineHeight = style.lineHeight * 0.62F
        val blockHeight = parts.size * wrapLineHeight
        val left = panelLeft + 24.0F

        val focusAlpha = Material.clamp(alpha, 0.0F, 1.0F)
        if (active) {
            val highlight = focusAlpha * 0.06F
            ui.gradient4(
                panelLeft + 8.0F, lineY - blockHeight / 2.0F - 8.0F, panelWidth - 16.0F,
                blockHeight + 16.0F, 12.0F,
                Material.withAlpha(scheme.primary, highlight), Material.withAlpha(scheme.primary, highlight),
                Material.withAlpha(scheme.primary, highlight), Material.withAlpha(scheme.primary, highlight),
            )
        }

        // Apple Music keeps the focused line's unsung text dim and its sung part bright; other lines
        // sit at a uniform low alpha (their per-line focus is already folded into alpha).
        val baseColor = if (active) Material.withAlpha(0xFFF4F5F8.toInt(), 0.42F * focusAlpha)
        else Material.withAlpha(0xFFE8E9EF.toInt(), 0.55F * focusAlpha)
        val sungColor = if (active) Material.withAlpha(Material.mix(scheme.primary, 0xFFFFFFFF.toInt(), 0.25F), focusAlpha)
        else Material.withAlpha(0xFFE8E9EF.toInt(), 0.55F * focusAlpha)

        for (p in parts.indices) {
            val text = parts[p]
            val lineTop = lineY - blockHeight / 2.0F + p * wrapLineHeight + wrapLineHeight / 2.0F
            ui.text(style, text, left, lineTop, baseColor, UI.ALIGN_LEFT)
            val sungWidth = sungWidthFor(words, partStart[p], text, style, position, active)
            if (sungWidth > 0.5F) {
                ui.scissorPush(left - 2.0F, lineTop - wrapLineHeight, sungWidth + 2.0F, wrapLineHeight * 2.0F)
                ui.text(style, text, left, lineTop, sungColor, UI.ALIGN_LEFT)
                ui.scissorPop()
            }
        }

        val translation = if (line.translation.isEmpty()) line.romanisation else line.translation
        val subStyle = translationStyle
        if (translation.isNotEmpty() && subStyle != null) {
            ui.text(
                subStyle, ui.truncate(subStyle, translation, layoutWidth),
                left, lineY - blockHeight / 2.0F + blockHeight + 10.0F,
                Material.withAlpha(0xFFC9CCD8.toInt(), alpha * (if (active) 0.85F else 0.55F)), UI.ALIGN_LEFT,
            )
        }
    }

    /**
     * Width of the sung part of one wrapped run: fully sung fragments contribute their measured
     * width, the fragment being sung contributes a linear fraction, everything after is zero.
     */
    private fun sungWidthFor(
        words: List<LyricWord>, firstWord: Int, text: String,
        style: Typeface.Style, position: Long, active: Boolean,
    ): Float {
        if (words.isEmpty()) return 0.0F
        if (!active) {
            // Passed lines are fully sung; upcoming lines are not.
            return if (words[0].startMs <= position) style.width(text) else 0.0F
        }
        var width = 0.0F
        for (i in firstWord until words.size) {
            val word = words[i]
            val part = style.width(word.text)
            if (position >= word.endMs) {
                width += part
                continue
            }
            if (position > word.startMs) {
                val fraction = (position - word.startMs).toFloat() / word.duration().toFloat()
                width += part * Material.clamp(fraction, 0.0F, 1.0F)
            }
            break
        }
        return width
    }

    // ----------------------------------------------------------------- input

    fun mouseClicked(mouseX: Float, mouseY: Float): Boolean {
        if (mouseX >= closeX && mouseX < closeX + 32.0F && mouseY >= closeY && mouseY < closeY + 32.0F) {
            screen.setLyricOpen(false)
            return true
        }
        val lines = MusicManager.lyrics()
        for (i in lineHitBoxes.indices) {
            if (i >= lineHitIndices.size) break
            val box = lineHitBoxes[i]
            if (mouseX >= box[0] && mouseX < box[0] + box[2] && mouseY >= box[1] && mouseY < box[1] + box[3]) {
                val index = lineHitIndices[i]
                if (index >= 0 && index < lines.size) {
                    MusicManager.seek(lines[index].timeMs)
                    screen.setLyricOpen(true)
                }
                return true
            }
        }
        return false
    }

    /** Line whose end has not passed yet, matching Melodify's `findCurrentLine`. */
    companion object {
        private const val PITCH_BASE = 46.0F
        private const val FADE_LINES = 3.2F

        /** Pixel height of the soft dissolve at the panel's top and bottom edges. */
        private const val EDGE_FADE = 30.0F

        /**
         * Apple Music style focus: every line eases towards "active" (1) or "idle" (0.34) alpha
         * instead of only being dimmed by its distance, and the active line is drawn 5% larger.
         */
        private const val IDLE_LINE_ALPHA = 0.34F
        private const val ACTIVE_LINE_SCALE = 1.05F

        fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
            if (lines.isEmpty()) return -1
            for (i in lines.indices) {
                if (positionMs < lines[i].endMs) return i
            }
            return lines.size - 1
        }
    }
}
