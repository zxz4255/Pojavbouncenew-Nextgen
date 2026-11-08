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

package net.ccbluex.liquidbounce.features.module.modules.render.arraylist

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.minecraft.util.FormattedCharSequence

/**
 * Per module state of the arraylist, the equivalent of Solstice's `Module::mArrayListAnim` plus a
 * small cache for the prepared text so the glyph widths are not measured on every frame.
 *
 * The geometry fields are refilled on every frame by [ModuleArraylist] and are meaningless
 * afterwards - they are kept here (instead of being allocated per frame) so the whole list renders
 * without allocating.
 */
internal class ArraylistRowState(val module: ClientModule) {

    /**
     * `0` = fully hidden, `1` = fully shown. Interpolated towards the module state.
     */
    var animation = 0f

    /** Identifies the currently cached [nameText] / [tagText]. */
    var textSignature: String? = null

    var nameText: FormattedCharSequence? = null
    var tagText: FormattedCharSequence? = null

    var nameWidth = 0f
    var tagWidth = 0f

    /** Themed color of this row, refreshed every frame. */
    var color: Color4b = Color4b.WHITE

    /** Left edge of the animated text. */
    var x = 0f

    /** Top edge of the row. */
    var y = 0f

    /** Row text height in GUI units. */
    var height = 0f

    /** Padded rect of the row, matching `backgroundRects` of the original. */
    var rectLeft = 0f
    var rectTop = 0f
    var rectRight = 0f
    var rectBottom = 0f

    val contentWidth: Float
        get() = nameWidth + tagWidth

}
