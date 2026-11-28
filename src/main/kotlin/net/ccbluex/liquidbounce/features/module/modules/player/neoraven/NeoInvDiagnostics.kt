/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.player.neoraven

import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc

/** Installed only on NeoInvManager.Enabled, not on inventory values or other modules. */
object NeoInvDiagnostics : EventListener {
    private val writer = NeoInvTraceWriter(
        directory = { mc.gameDirectory.toPath().resolve("logs") },
        fallback = { logger.info(it) },
    )
    @Volatile private var watched: Value<Boolean>? = null
    @Volatile private var hasActivity = false
    private var nextSnapshotAt = 0L
    val lastSummary: String get() = writer.lastSummary
    // Always-active: still reports actual state AFTER the module has been disabled.
    @Suppress("unused")
    private val snapshotHandler = handler<GameTickEvent> {
        if (!hasActivity) return@handler
        val now = System.nanoTime()
        if (now < nextSnapshotAt) return@handler
        nextSnapshotAt = now + 5_000_000_000L
        val value = watched ?: return@handler
        note("STATE_SNAPSHOT", "actual=${value.get()} flow=${value.asStateFlow().value} " +
            "running=${ModuleNeoInvManager.running} ${context()}")
    }
    fun install(value: Value<Boolean>) {
        watched = value
        value.traceWrites { trace ->
            hasActivity = true
            writer.record(trace, context())
        }
    }
    fun note(stage: String, detail: String, includeStack: Boolean = false) {
        try { writer.note(stage, detail, includeStack) }
        catch (_: Exception) { /* Diagnostics cannot veto the lifecycle being diagnosed. */ }
        catch (_: LinkageError) { /* Keep the original lifecycle behavior on unsupported runtimes. */ }
    }
    private fun context(): String = try {
        "screen=${mc.gui.screen()?.javaClass?.simpleName ?: "none"} " +
            "player=${mc.player != null} world=${mc.level != null} bindAction=${ModuleNeoInvManager.bind.action}"
    } catch (error: Exception) { "contextUnavailable=${error.javaClass.simpleName}" }
}
