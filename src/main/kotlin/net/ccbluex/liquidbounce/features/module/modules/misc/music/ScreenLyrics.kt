/* GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.UI
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Typeface
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Independent HUD overlay. The music player's window and island do not own its visibility. */
object ScreenLyrics {
    private var lastKey: String? = null
    fun clear() { lastKey = null }

    fun render(ctx: GuiGraphicsExtractor, preset: ModuleMusic.ScreenPreset, x: Float, y: Float,
               size: Float, translation: Boolean) {
        val song = MusicManager.current ?: run { clear(); return }
        val lines = MusicManager.lyrics()
        if (MusicManager.lyricsSongId() != song.id || lines.isEmpty()) { clear(); return }
        val key = "${song.provider}:${song.id}"
        lastKey = key
        val position = MusicManager.positionMs()
        // Binary search is stateless, so seeking backwards and changing tracks cannot retain a line.
        var low = 0; var high = lines.lastIndex; var index = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= position) { index = mid; low = mid + 1 } else high = mid - 1
        }
        if (index < 0) return
        val line = lines[index]
        if (line.text.isBlank()) return
        val entry = ((position - line.timeMs).coerceAtLeast(0L) / 550f).coerceIn(0f, 1f)
        val ease = 1f - (1f - entry) * (1f - entry) * (1f - entry)
        val screenW = mc.window.guiScaledWidth.toFloat()
        val screenH = mc.window.guiScaledHeight.toFloat()
        val originX = screenW * x.coerceIn(0f, 100f) / 100f
        val originY = screenH * y.coerceIn(0f, 100f) / 100f
        // Local HUD scale composes with the global Music lyric font size.
        val scale = size.coerceIn(.5f, 2.5f) * MusicManager.lyricScale()
        val maxWidth = (screenW / scale - 28f).coerceAtLeast(20f)
        val ui = UI(ctx)
        ui.begin()
        ui.push()
        try {
            ui.translate(originX, originY)
            ui.scale(scale, 0f, 0f)
            ui.alphaPush(ease)
            when (preset) {
                ModuleMusic.ScreenPreset.FLOAT -> {
                    val font = MusicTypeface.style(17f, 22f, true)
                    val text = ui.truncate(font, line.text, maxWidth) ?: ""
                    val slide = (1f - ease) * 22f
                    drawShadowText(ui, font, text, slide, -5f * (1f - ease), 0xfff8f8ff.toInt())
                    if (translation && line.translation.isNotBlank()) {
                        val sub = MusicTypeface.style(10f, 14f, false)
                        drawShadowText(ui, sub, ui.truncate(sub, line.translation, maxWidth) ?: "", slide, 18f,
                            0xffb5bcd0.toInt())
                    }
                }
                ModuleMusic.ScreenPreset.KARAOKE -> {
                    val font = MusicTypeface.style(21f, 26f, true)
                    val text = ui.truncate(font, line.text, maxWidth) ?: ""
                    val width = ui.width(font, text)
                    val sweep = sungWidth(ui, font, line, position, width)
                    // Small pop-in, then a timing-driven colored sweep rather than a color-only preset.
                    ui.push()
                    ui.scale(.88f + .12f * ease, 0f, 0f)
                    drawShadowText(ui, font, text, 0f, 0f, 0xffa0aabb.toInt())
                    if (sweep > .01f && width > .01f) {
                        ui.scissorPush(-width / 2f - 1f, -22f, sweep + 1f, 44f)
                        ui.text(font, text, 0f, 0f, 0xffffe9b5.toInt(), UI.ALIGN_CENTER)
                        ui.scissorPop()
                    }
                    ui.pop()
                    if (translation && line.translation.isNotBlank()) {
                        val sub = MusicTypeface.style(10f, 14f, false)
                        drawShadowText(ui, sub, ui.truncate(sub, line.translation, maxWidth) ?: "", 0f, 21f,
                            0xffd7d8df.toInt())
                    }
                }
                ModuleMusic.ScreenPreset.STACK -> {
                    val font = MusicTypeface.style(16f, 20f, true)
                    val small = MusicTypeface.style(10f, 14f, false)
                    val lift = (1f - ease) * 13f
                    if (index > 0 && lines[index - 1].text.isNotBlank()) {
                        ui.alphaPush(.4f)
                        drawShadowText(ui, small, ui.truncate(small, lines[index - 1].text, maxWidth) ?: "",
                            0f, -20f + lift, 0xffb5c7d5.toInt())
                        ui.alphaPop()
                    }
                    drawShadowText(ui, font, ui.truncate(font, line.text, maxWidth) ?: "", 0f, lift,
                        0xffeefaff.toInt())
                    val next = lines.getOrNull(index + 1)?.text ?: ""
                    if (next.isNotBlank()) {
                        ui.alphaPush(.5f)
                        drawShadowText(ui, small, ui.truncate(small, next, maxWidth) ?: "", 0f, 20f + lift,
                            0xffb5c7d5.toInt())
                        ui.alphaPop()
                    }
                }
                ModuleMusic.ScreenPreset.CARD -> {
                    val font = MusicTypeface.style(14f, 18f, true)
                    val sub = MusicTypeface.style(10f, 14f, false)
                    val text = ui.truncate(font, line.text, maxWidth - 24f) ?: ""
                    val second = if (translation && line.translation.isNotBlank()) line.translation else song.name
                    val secondText = ui.truncate(sub, second, maxWidth - 24f) ?: ""
                    val cardWidth = maxOf(ui.width(font, text), ui.width(sub, secondText), 100f) + 24f
                    val cardHeight = 44f
                    val grow = .90f + .10f * ease
                    ui.push()
                    ui.translate(0f, 9f * (1f - ease))
                    ui.scale(grow, 0f, 0f)
                    ui.smoothShadow(-cardWidth / 2f, -cardHeight / 2f, cardWidth, cardHeight, 10f, 3)
                    ui.rounded(-cardWidth / 2f, -cardHeight / 2f, cardWidth, cardHeight, 10f, 0xd51c2636.toInt())
                    ui.rounded(-cardWidth / 2f + 6f, -cardHeight / 2f + 8f, 2f, 28f, 1f, 0xff91daf5.toInt())
                    ui.text(font, text, 1f, -7f, 0xfff4fcff.toInt(), UI.ALIGN_CENTER)
                    ui.text(sub, secondText, 1f, 10f, 0xffb7cbd7.toInt(), UI.ALIGN_CENTER)
                    val progress = line.progress(position).coerceIn(0f, 1f)
                    ui.rounded(-cardWidth / 2f + 12f, 17f, (cardWidth - 24f) * progress, 1.5f, .75f,
                        0xff91daf5.toInt())
                    ui.pop()
                }
            }
            ui.alphaPop()
        } finally {
            ui.pop()
            ui.end()
        }
    }

    private fun drawShadowText(ui: UI, font: Typeface.Style, text: String, x: Float, y: Float, color: Int) {
        ui.text(font, text, x + 1f, y + 1f, 0x99000000.toInt(), UI.ALIGN_CENTER)
        ui.text(font, text, x, y, color, UI.ALIGN_CENTER)
    }

    /** Exact word-width sweep when the source supplies matching fragments; line fallback otherwise. */
    private fun sungWidth(ui: UI, font: Typeface.Style, line: LyricLine, now: Long, visibleWidth: Float): Float {
        val words = line.words
        if (words.isEmpty() || words.joinToString("") { it.text } != line.text) {
            return visibleWidth * line.progress(now)
        }
        var width = 0f
        for (word in words) width += ui.width(font, word.text) * word.progress(now)
        return width.coerceIn(0f, visibleWidth)
    }
}
