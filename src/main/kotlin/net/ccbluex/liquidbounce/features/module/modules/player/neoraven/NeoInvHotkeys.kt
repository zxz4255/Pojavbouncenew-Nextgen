/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.player.neoraven

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.input.InputBind

/** Always-active like MusicHotkeys; externalBind excludes the generic second toggle path. */
object NeoInvHotkeys : EventListener {
    private val latch = NeoInvBindLatch()
    private var lastBind: InputBind? = null
    private fun syncBinding() {
        if (mc.level != null && mc.player != null) ModuleNeoInvManager.migrateTapToggle()
        val bind = ModuleNeoInvManager.bind
        if (bind != lastBind) { latch.clear(); lastBind = bind }
    }
    private fun press(source: String) {
        NeoInvDiagnostics.note("INPUT_PRESS", source)
        val module = ModuleNeoInvManager
        val action = NeoInvBindLatch.Action.valueOf(module.bind.action.name)
        latch.press(action, module.enabled, System.currentTimeMillis())?.let { module.enabled = it }
    }
    private fun release(source: String) {
        NeoInvDiagnostics.note("INPUT_RELEASE", source)
        latch.release(System.currentTimeMillis())?.let { ModuleNeoInvManager.enabled = it }
    }
    @Suppress("unused")
    private val keyboard = handler<KeyboardKeyEvent> { event ->
        syncBinding()
        val bind = ModuleNeoInvManager.bind
        if (bind.matchesKeyRelease(event)) { release("keyboard"); return@handler }
        if (event.isRepeat && bind.matchesKey(event.scanCode)) { latch.repeat(); return@handler }
        // Immutable event-origin screen AND current screen: earlier handlers may have closed a GUI.
        if (event.screen != null || mc.gui.screen() != null || mc.options.keyDebugModifier.isDown) return@handler
        if (mc.level == null || mc.player == null) return@handler
        if (bind.matchesKeyPress(event)) press("keyboard")
    }
    @Suppress("unused")
    private val mouse = handler<MouseButtonEvent> { event ->
        syncBinding()
        val bind = ModuleNeoInvManager.bind
        if (bind.matchesMouseRelease(event)) { release("mouse"); return@handler }
        if (event.screen != null || mc.gui.screen() != null || mc.level == null || mc.player == null) return@handler
        if (bind.matchesMousePress(event)) press("mouse")
    }
    @Suppress("unused")
    private val migration = handler<GameTickEvent> {
        // Defer until a world is present: module configuration has been loaded by this point.
        if (mc.level != null && mc.player != null) syncBinding()
    }
    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> { latch.clear() }
}
