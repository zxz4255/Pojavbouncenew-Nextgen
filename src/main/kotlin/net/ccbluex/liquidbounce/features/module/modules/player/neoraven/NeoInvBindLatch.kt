/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.player.neoraven

/** One physical down/up cycle produces one decision. Pure, independently testable input state. */
class NeoInvBindLatch {
    enum class Action { TOGGLE, HOLD, SMART }
    private var down = false
    private var action = Action.TOGGLE
    private var before = false
    private var pressedAt = 0L
    private var held = false

    fun clear() { down = false; held = false }
    fun press(action: Action, enabled: Boolean, nowMs: Long): Boolean? {
        if (down) return null
        down = true; this.action = action; before = enabled; pressedAt = nowMs; held = false
        return if (action == Action.TOGGLE) !enabled else true
    }
    fun repeat() { if (down) held = true }
    fun release(nowMs: Long): Boolean? {
        if (!down) return null
        down = false
        return when (action) {
            Action.TOGGLE -> null
            Action.HOLD -> false
            Action.SMART -> if (held || nowMs - pressedAt >= 200L) false else !before
        }
    }
}
