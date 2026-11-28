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
package net.ccbluex.liquidbounce.features.module.modules.misc

import net.ccbluex.liquidbounce.event.events.GameRenderEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.ScreenEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.markAsError
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.client.variable
import net.ccbluex.liquidbounce.utils.debug.FreezeWatchdog
import net.ccbluex.liquidbounce.utils.text.asText

/**
 * Module FreezeTracker
 *
 * Watches for the client locking up and, when it does, writes down why.
 *
 * The reported symptom was "after a few minutes of normal play the client freezes and then dies", with
 * a log that simply stops — no exception, no stack. That kind of hang cannot be caught by listening for
 * exceptions, so this module drives [FreezeWatchdog]: a background thread watches a per-frame and
 * per-tick heartbeat, and the instant they go stale it dumps every thread's stack, the lock owners,
 * memory, and the last operations into `<LiquidBounce folder>/freeze-report-*.txt`.
 *
 * The point is that the evidence is captured *while* the client is frozen, from a thread that is itself
 * still running. If the hang turns into a crash, the report is already on disk.
 *
 * Keep this enabled while playing. It costs one atomic increment per frame and one background thread
 * that samples once a second.
 */
object ModuleFreezeTracker : ClientModule(
    "FreezeTracker", ModuleCategories.MISC, state = true, disableOnQuit = false
) {

    init {
        // A diagnostic tool: it must not end up in every exported config.
        doNotIncludeAlways()
    }

    private val notifyOnStall by boolean("NotifyOnStall", true)

    private val trackScreens by boolean("TrackScreens", true)

    private val trackPackets by boolean("TrackPackets", false)

    override fun onEnabled() {
        FreezeWatchdog.start()
        FreezeWatchdog.record("FreezeTracker 已启用")
        // Single-Component overload used deliberately: the two-argument `chat(text, metadata)` and
        // the vararg form both accept Components, and only the multi-argument call is unambiguous.
        chat(
            regular("FreezeTracker 已启用 — 卡死时会把线程栈写到 ")
                .append(variable("<LiquidBounce>/freeze-report-*.txt"))
                .append(regular("。"))
        )
    }

    override fun onDisabled() {
        FreezeWatchdog.record("FreezeTracker 被关闭")
        FreezeWatchdog.stop()
    }

    /** Every rendered frame: this is the heartbeat that catches a frozen render thread. */
    @Suppress("unused")
    private val renderHandler = handler<GameRenderEvent> {
        FreezeWatchdog.onFrame()
    }

    /** Every tick: catches "render still runs but logic stopped", which is a different bug shape. */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        FreezeWatchdog.onTick()
    }

    /**
     * Screen transitions are one of the strongest freeze candidates on this client: chest/container
     * screens involve the stealer, the island renderer and the GUI mouse grab all at once, and that
     * path has produced both crashes and hangs before.
     */
    @Suppress("unused")
    private val screenHandler = handler<ScreenEvent> { event ->
        if (!trackScreens) {
            return@handler
        }
        FreezeWatchdog.record("屏幕切换 -> ${event.screen?.javaClass?.simpleName ?: "null（回到游戏）"}")
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> { event ->
        // This mapping exposes `identifier()` on ResourceKey (it is `location()` upstream), so the
        // dimension id is read through that. `path` alone is enough for a breadcrumb: "overworld",
        // "the_nether", "end", or a server's custom dimension.
        FreezeWatchdog.record(
            "世界切换 -> ${event.world?.dimension()?.identifier()?.path ?: "null（离开世界）"}"
        )
    }

    /**
     * Off by default: a busy server sends thousands of packets per minute and every breadcrumb is a
     * string allocation. Switch it on when the freeze seems tied to network activity.
     */
    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (!trackPackets) {
            return@handler
        }
        // Only the shape of the traffic, never the payload.
        FreezeWatchdog.record(
            "包 ${event.origin} ${event.packet.javaClass.simpleName}"
        )
    }

    /** Offers the report path in chat so it is easy to find on a phone. */
    fun announceReportPath() {
        chat(
            regular("卡死报告： ")
                .append(variable(FreezeWatchdog.reportPath()))
                .append(regular("（已写入 ${FreezeWatchdog.reportsSoFar()} 份）"))
        )
        if (FreezeWatchdog.reportsSoFar() == 0L) {
            chat(markAsError("目前还没有记录到卡死。"))
        }
    }
}
