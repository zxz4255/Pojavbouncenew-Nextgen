package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.mc

/**
 * The player's window binding is the Music module's normal Bind (M by default).
 * This listener stays active while the module is disabled, so the first press can open the
 * window. ModuleMusic.externalBind prevents ModuleManager from toggling it a second time.
 * Opening is a press action, regardless of the bind's toggle/hold mode.
 */
object MusicHotkeys : EventListener {

    @Suppress("unused")
    private val keyboard = handler<KeyboardKeyEvent> { event ->
        if (event.screen != null || mc.gui.screen() != null) return@handler
        if (mc.options.keyDebugModifier.isDown) return@handler
        if (!ModuleMusic.bind.matchesKeyPress(event)) return@handler
        ModuleMusic.requestOpen()
    }

    @Suppress("unused")
    private val mouse = handler<MouseButtonEvent> { event ->
        if (event.screen != null || mc.gui.screen() != null) return@handler
        if (!ModuleMusic.bind.matchesMousePress(event)) return@handler
        ModuleMusic.requestOpen()
    }
}
