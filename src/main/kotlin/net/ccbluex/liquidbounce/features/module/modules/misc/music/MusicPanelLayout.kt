/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.misc.music

/**
 * 101 "Glass Archipelago" design system, shared by painting and hit testing.
 *
 * Four floating 098 Gaussian-glass panes replace the old shell/rail/header/mini-bar layout:
 * - Stage (left, full height): cover, spectrum, wave progress, transport and utility row.
 * - Dock (top right): provider segmented switch, IME search field, account and close.
 * - Tabs (below the dock): page tabs and library quick chips.
 * - Deck (bottom right): scrollable page content, playlists and the in-window lyrics.
 */
internal object MusicPanelLayout {
    const val WIDTH = 960f
    const val HEIGHT = 600f
    const val RADIUS = 3f

    const val STAGE_X = 16f
    const val STAGE_Y = 16f
    const val STAGE_W = 300f
    const val STAGE_H = 568f
    const val COVER_X = 36f
    const val COVER_Y = 62f
    const val COVER_SIZE = 260f
    const val TITLE_Y = 360f
    const val TRACK_X = 36f
    const val TRACK_Y = 412f
    const val TRACK_W = 260f
    const val TRANSPORT_Y = 474f
    const val UTILITY_Y = 532f
    const val VOLUME_X = 70f
    const val VOLUME_Y = 532f
    const val VOLUME_W = 112f

    const val DOCK_X = 332f
    const val DOCK_Y = 16f
    const val DOCK_W = 612f
    const val DOCK_H = 56f

    const val TABS_X = 332f
    const val TABS_Y = 84f
    const val TABS_W = 612f
    const val TABS_H = 40f
    const val TAB_W = 82f
    const val TAB_H = 30f
    const val TAB_PITCH = 86f

    const val DECK_X = 332f
    const val DECK_Y = 136f
    const val DECK_W = 612f
    const val DECK_H = 448f
    const val CONTENT_X = 340f
    const val CONTENT_Y = 144f
    const val CONTENT_W = 596f
    const val CONTENT_H = 432f

    /** Queue rows per page in the stage queue sheet. */
    const val QUEUE_PAGE = 10

    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
        fun contains(px: Float, py: Float) = px >= x && py >= y && px < x + w && py < y + h
        fun intersects(other: Rect) = x < other.x + other.w && other.x < x + w &&
            y < other.y + other.h && other.y < y + h
    }

    val stage = Rect(STAGE_X, STAGE_Y, STAGE_W, STAGE_H)
    val dock = Rect(DOCK_X, DOCK_Y, DOCK_W, DOCK_H)
    val tabs = Rect(TABS_X, TABS_Y, TABS_W, TABS_H)
    val deck = Rect(DECK_X, DECK_Y, DECK_W, DECK_H)
    val content = Rect(CONTENT_X, CONTENT_Y, CONTENT_W, CONTENT_H)
    val cover = Rect(COVER_X, COVER_Y, COVER_SIZE, COVER_SIZE)
    val progressHit = Rect(TRACK_X, TRACK_Y - 12f, TRACK_W, 24f)
    val volumeHit = Rect(VOLUME_X, VOLUME_Y - 12f, VOLUME_W, 24f)

    fun tab(index: Int) = Rect(TABS_X + 8f + index * TAB_PITCH, TABS_Y + (TABS_H - TAB_H) / 2f, TAB_W, TAB_H)

    fun fraction(x: Float, start: Float, width: Float): Float =
        if (!x.isFinite() || !start.isFinite() || !width.isFinite() || width <= 0f) 0f
        else ((x - start) / width).coerceIn(0f, 1f)
}
