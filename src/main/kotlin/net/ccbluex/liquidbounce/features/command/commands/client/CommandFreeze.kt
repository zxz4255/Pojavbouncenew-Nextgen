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
package net.ccbluex.liquidbounce.features.command.commands.client

import com.mojang.brigadier.CommandDispatcher
import net.ccbluex.liquidbounce.features.command.CommandRegistrar
import net.ccbluex.liquidbounce.features.command.brigadier.ClientCommandSource
import net.ccbluex.liquidbounce.features.command.brigadier.register
import net.ccbluex.liquidbounce.features.module.modules.misc.ModuleFreezeTracker
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.client.variable
import net.ccbluex.liquidbounce.utils.debug.FreezeWatchdog

/**
 * Freeze Command
 *
 * Shows where the freeze report is, and takes a snapshot on demand.
 *
 * The automatic report only fires once the client has been stuck for several seconds, which is right
 * for a hard hang but useless in the far more common case of "it becomes unplayable for a second every
 * now and then". `.freeze snapshot` grabs the same information at the moment the user still has control.
 *
 * Module: [ModuleFreezeTracker]
 */
object CommandFreeze : CommandRegistrar {

    override fun register(dispatcher: CommandDispatcher<ClientCommandSource>) {
        dispatcher.register("freeze") {
            exec {
                ModuleFreezeTracker.announceReportPath()
                1
            }
            literal("snapshot") {
                exec {
                    FreezeWatchdog.record("用户手动请求快照 (.freeze snapshot)")
                    val path = FreezeWatchdog.snapshotNow("手动快照")
                    chat(
                        regular("已写入快照： ").append(variable(path))
                    )
                    1
                }
            }
        }
    }
}
