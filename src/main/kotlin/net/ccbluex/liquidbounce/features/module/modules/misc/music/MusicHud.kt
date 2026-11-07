package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Minimal now-playing card.
 *
 * Deliberately reduced to what the request asked for: **song title + current lyric line only**.
 * No cover, no progress bar, no status pill, no transport controls — those live in the screen.
 * The card sizes itself to the text, so it stays small, and the surface is intentionally
 * translucent so the world stays readable behind it.
 */
internal object MusicHud {
    private const val PADDING_X = 13f
    private const val PADDING_Y = 9f
    private const val BAR_WIDTH = 2.4f
    private const val BAR_GAP = 6f
    private const val TITLE_SIZE = 10.5f
    private const val LYRIC_SIZE = 14f
    private const val GAP = 4.5f

    fun draw(
        ctx: GuiGraphicsExtractor,
        state: PlaybackSnapshot,
        line: LyricLine?,
        notice: String,
        requestedScale: Float,
        bottom: Int,
        accent: Color4b,
        style: ModuleMusic.HudStyle,
        backgroundOpacity: Int,
        maxWidth: Float,
    ) {
        val track = state.track ?: return
        val width = ctx.guiWidth().toFloat()
        val height = ctx.guiHeight().toFloat()
        if (width < 40f || height < 30f) return

        val lyric = line?.text
            ?: if (state.status == PlaybackStatus.PLAYING) notice else state.error.ifBlank { notice }
        val textLeft = PADDING_X + BAR_WIDTH + BAR_GAP
        val available = (maxWidth - textLeft - PADDING_X).coerceAtLeast(48f)
        val title = MusicUi.ellipsize(track.title, available, TITLE_SIZE)
        val shownLyric = MusicUi.ellipsize(lyric, available, LYRIC_SIZE)
        val contentWidth = maxOf(MusicUi.width(title, TITLE_SIZE), MusicUi.width(shownLyric, LYRIC_SIZE))
        val cardWidth = (contentWidth + textLeft + PADDING_X).coerceIn(120f, maxWidth)
        val cardHeight = PADDING_Y * 2f + TITLE_SIZE + GAP + LYRIC_SIZE

        // Fit into the window even on very small GUI scales.
        val scale = minOf(requestedScale, (width - 8f) / cardWidth, (height - 8f) / cardHeight)
        if (!scale.isFinite() || scale <= 0f) return

        val x = (width - cardWidth * scale) * 0.5f
        val y = (height - bottom - cardHeight * scale)
            .coerceIn(4f, (height - cardHeight * scale - 4f).coerceAtLeast(4f))

        val backgroundAlpha = (backgroundOpacity.coerceIn(0, 100) / 100f * 255f).toInt()
        val surface = Color4b(
            MonetPalette.background.r,
            MonetPalette.background.g,
            MonetPalette.background.b,
            backgroundAlpha,
        )
        val border = MonetPalette.alpha(accent, 0.16f)
        val radius = cardHeight / 2.8f

        ctx.pose().pushMatrix()
        try {
            ctx.pose().translate(x, y)
            ctx.pose().scale(scale, scale)
            MusicUi.gradientRect(
                ctx, 0f, 0f, cardWidth, cardHeight, radius,
                surface,
                Color4b(surface.r, surface.g, surface.b, (backgroundAlpha * 0.70f).toInt()),
                if (style == ModuleMusic.HudStyle.GLASS) border else null,
            )
            MusicUi.rect(
                ctx, PADDING_X, cardHeight * 0.24f, BAR_WIDTH, cardHeight * 0.52f, BAR_WIDTH / 2f,
                if (style == ModuleMusic.HudStyle.GLASS) accent else MonetPalette.alpha(accent, 0.75f),
            )
            MusicUi.text(ctx, title, textLeft, PADDING_Y - 1f, TITLE_SIZE, MonetPalette.muted, maxWidth = available)
            MusicUi.text(
                ctx, shownLyric, textLeft, PADDING_Y + TITLE_SIZE + GAP, LYRIC_SIZE,
                MonetPalette.text, maxWidth = available,
            )
        } finally {
            ctx.pose().popMatrix()
        }
    }
}
