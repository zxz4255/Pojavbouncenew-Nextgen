package net.ccbluex.liquidbounce.features.command.commands.client

import com.mojang.brigadier.CommandDispatcher
import net.ccbluex.liquidbounce.features.command.CommandRegistrar
import net.ccbluex.liquidbounce.features.command.brigadier.ClientCommandSource
import net.ccbluex.liquidbounce.features.command.brigadier.register
import net.ccbluex.liquidbounce.features.module.modules.misc.music.ModuleMusic

/**
 * Built-in client command: `.music` / `.monet`. The exec body queues the screen so chat can close first.
 *
 * <p>Subcommands mirror the transport the module actually exposes: open, pause, next, previous, stop.
 * The old `place` branch is gone — the reference client (NeoRaven) never had an in-world lyric sign,
 * so there is nothing to port; see `README-Music.md`, "R8 差异" for the note.</p>
 */
object CommandMusic : CommandRegistrar {
    override fun register(dispatcher: CommandDispatcher<ClientCommandSource>) {
        dispatcher.register("music", listOf("monet")) {
            exec {
                ModuleMusic.requestOpen()
                1
            }
            literal("pause") { exec { ModuleMusic.engine.togglePause(); 1 } }
            literal("next") { exec { ModuleMusic.engine.next(); 1 } }
            literal("previous") { exec { ModuleMusic.engine.previous(); 1 } }
            literal("stop") { exec { ModuleMusic.engine.stop(); 1 } }
        }
    }
}
