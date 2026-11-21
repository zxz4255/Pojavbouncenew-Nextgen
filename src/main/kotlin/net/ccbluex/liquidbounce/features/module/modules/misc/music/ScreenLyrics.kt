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
        // Colours are resolved once per frame, so switching a colour picker takes effect immediately
        // without reloading lyrics or touching a cached style.
        val colorMode = ModuleMusic.screenLyricColorMode()
        val mainRgb = resolveScreenColor(colorMode, 0xF8F8FF)
        val subRgb = resolveScreenColor(colorMode, 0xB5BCD0)
        val accentRgb = resolveScreenColor(colorMode, 0x91DAF5)
        val cardRgb = resolveScreenColor(colorMode, 0x1C2636)
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
                    drawShadowText(ui, font, text, slide, -5f * (1f - ease), mainRgb)
                    if (translation && line.translation.isNotBlank()) {
                        val sub = MusicTypeface.style(10f, 14f, false)
                        drawShadowText(ui, sub, ui.truncate(sub, line.translation, maxWidth) ?: "", slide, 18f, subRgb)
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
                    drawShadowText(ui, font, text, 0f, 0f, blend(mainRgb, subRgb, 0.55f))
                    if (sweep > .01f && width > .01f) {
                        ui.scissorPush(-width / 2f - 1f, -22f, sweep + 1f, 44f)
                        ui.text(font, text, 0f, 0f, accentRgb, UI.ALIGN_CENTER)
                        ui.scissorPop()
                    }
                    ui.pop()
                    if (translation && line.translation.isNotBlank()) {
                        val sub = MusicTypeface.style(10f, 14f, false)
                        drawShadowText(ui, sub, ui.truncate(sub, line.translation, maxWidth) ?: "", 0f, 21f, subRgb)
                    }
                }
                ModuleMusic.ScreenPreset.STACK -> {
                    val font = MusicTypeface.style(16f, 20f, true)
                    val small = MusicTypeface.style(10f, 14f, false)
                    val lift = (1f - ease) * 13f
                    if (index > 0 && lines[index - 1].text.isNotBlank()) {
                        ui.alphaPush(.4f)
                        drawShadowText(ui, small, ui.truncate(small, lines[index - 1].text, maxWidth) ?: "",
                            0f, -20f + lift, blend(subRgb, mainRgb, 0.25f))
                        ui.alphaPop()
                    }
                    drawShadowText(ui, font, ui.truncate(font, line.text, maxWidth) ?: "", 0f, lift, mainRgb)
                    val next = lines.getOrNull(index + 1)?.text ?: ""
                    if (next.isNotBlank()) {
                        ui.alphaPush(.5f)
                        drawShadowText(ui, small, ui.truncate(small, next, maxWidth) ?: "", 0f, 20f + lift,
                            blend(subRgb, mainRgb, 0.25f))
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
                    ui.rounded(-cardWidth / 2f, -cardHeight / 2f, cardWidth, cardHeight, 10f, withAlpha(cardRgb, 0.84f))
                    ui.rounded(-cardWidth / 2f + 6f, -cardHeight / 2f + 8f, 2f, 28f, 1f, accentRgb)
                    ui.text(font, text, 1f, -7f, mainRgb, UI.ALIGN_CENTER)
                    ui.text(sub, secondText, 1f, 10f, subRgb, UI.ALIGN_CENTER)
                    val progress = line.progress(position).coerceIn(0f, 1f)
                    ui.rounded(-cardWidth / 2f + 12f, 17f, (cardWidth - 24f) * progress, 1.5f, .75f, accentRgb)
                    ui.pop()
                }
            }
            ui.alphaPop()
        } finally {
            ui.pop()
            ui.end()
        }
    }

    /**
     * Applies the configured colour mode to a preset's palette colour.
     *
     * <p>`Preset` keeps the stock palette, `Solid` replaces it and `Blend` tints towards the custom
     * colour so the tuned highlights of a preset survive a recolour.</p>
     */
    private fun resolveScreenColor(mode: ModuleMusic.LyricColorMode, presetRgb: Int): Int = when (mode) {
        ModuleMusic.LyricColorMode.PRESET -> 0xFF000000.toInt() or (presetRgb and 0xFFFFFF)
        ModuleMusic.LyricColorMode.SOLID -> 0xFF000000.toInt() or (ModuleMusic.screenLyricColor().argb and 0xFFFFFF)
        ModuleMusic.LyricColorMode.BLEND -> 0xFF000000.toInt() or
            ModuleMusic.blendRgb(presetRgb and 0xFFFFFF, ModuleMusic.screenLyricColor().argb and 0xFFFFFF, 0.60F)
    }

    /** Per-channel linear blend of two 0xRRGGBB colours. */
    private fun blend(from: Int, to: Int, t: Float): Int =
        0xFF000000.toInt() or ModuleMusic.blendRgb(from and 0xFFFFFF, to and 0xFFFFFF, t)

    /** Replaces the alpha of an 0xAARRGGBB colour, keeping its RGB. */
    private fun withAlpha(argb: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0xFFFFFF)
    }

    private fun drawShadowText(ui: UI, font: Typeface.Style, text: String, x: Float, y: Float, color: Int) {
        // The shadow offset scales with the setting, so a dark background can turn it off entirely.
        val strength = ModuleMusic.screenLyricShadow().coerceIn(0f, 3f)
        if (strength > 0.01f) {
            ui.text(font, text, x + strength, y + strength, withAlpha(0x000000, 0.35f + 0.20f * strength), UI.ALIGN_CENTER)
        }
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
