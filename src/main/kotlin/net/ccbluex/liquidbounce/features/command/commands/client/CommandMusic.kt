package net.ccbluex.liquidbounce.features.command.commands.client

import com.mojang.brigadier.CommandDispatcher
import net.ccbluex.liquidbounce.features.command.CommandRegistrar
import net.ccbluex.liquidbounce.features.command.brigadier.ClientCommandSource
import net.ccbluex.liquidbounce.features.command.brigadier.register
import net.ccbluex.liquidbounce.features.module.modules.misc.music.ModuleMusic
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.mc

/** Built-in client command: .music / .monet. The exec body queues the screen so chat can close first. */
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
            literal("place") {
                exec {
                    mc.execute {
                        if (ModuleMusic.placeInFront()) chat("歌词牌已放置在视线前方。")
                        else chat("请先进入世界，再放置歌词牌。")
                    }
                    1
                }
            }
        }
    }
}