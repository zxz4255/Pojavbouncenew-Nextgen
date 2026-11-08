package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.handler

/**
 * The window key (`M` by default) — deliberately **outside** the module's own handler table.
 *
 * <p>LiquidBounce only dispatches an [EventListener]'s handlers while it is `running`, and
 * `ClientModule.running` is `enabled && inGame`. A player who never toggled the Music module would
 * therefore never see the window, no matter which key was bound. A bare [EventListener] has no parent,
 * so its `running` is simply "not self-destructed" — the key works from a cold start, from the title
 * screen and in game, whether or not the module is currently on.</p>
 *
 * <p>It is initialised by `ModuleMusic.hotkeys`, and `ModuleMusic` is constructed by
 * `ModuleManager`'s module list at client start, so the handler is registered before the first
 * key press. Pressing the key also switches the module on (through
 * [ModuleMusic.requestOpen]), which is what actually starts playback, the island and the caches.</p>
 */
object MusicHotkeys : EventListener {

    @Suppress("unused")
    private val keyboard = handler<KeyboardKeyEvent> { event ->
        if (!event.isPressed) return@handler
        // Any screen (chat, click GUI, another mod's) keeps the key for itself.
        if (event.screen != null) return@handler
        // SDL scancode — the same space `InputBind` and `InputConstants.KEY_*` live in.
        if (!ModuleMusic.matchesWindowKey(event.scanCode)) return@handler
        ModuleMusic.requestOpen()
    }

}
