/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.player.neoraven

import net.ccbluex.liquidbounce.config.types.ValueWriteTrace
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

/** Bounded diagnostic output. No inventory/packet/account data and no state setters. */
class NeoInvTraceWriter(
    private val directory: () -> Path,
    private val fallback: (String) -> Unit,
) {
    companion object {
        const val FILE_NAME = "neo-invmanager-092.log"
        const val MAX_ENTRIES = 2048
        const val MAX_BYTES = 2L * 1024 * 1024
    }
    private val session = UUID.randomUUID().toString()
    private val started = System.nanoTime()
    private var entries = 0
    private var announced = false
    private var fileFailed = false
    @Volatile var lastSummary = "092-DIAGNOSTIC: waiting for a write"
        private set

    @Synchronized
    fun record(trace: ValueWriteTrace<Boolean>, context: String) {
        val line = "write=${trace.id} stage=${trace.stage} previous=${trace.previous} requested=${trace.requested}" +
            " candidate=${trace.candidate} actual=${trace.actual} flow=${trace.published} $context"
        val source = if (trace.stage == ValueWriteTrace.Stage.REQUEST || trace.stage == ValueWriteTrace.Stage.UNCHANGED)
            Throwable("Activation write origin (diagnostic stack, not an error)") else null
        append(line, trace.failure ?: source)
    }

    @Synchronized
    fun note(stage: String, detail: String, includeStack: Boolean = false) {
        append("$stage $detail", if (includeStack) Throwable("Diagnostic call origin") else null)
    }

    private fun append(line: String, error: Throwable?) {
        lastSummary = line.take(240)
        if (entries > MAX_ENTRIES) return
        val exhausted = entries++ == MAX_ENTRIES
        val message = if (exhausted) "TRACE_LIMIT reached; restart game for another capture" else line
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        val header = "[NeoInvManager/092] session=$session ${Instant.now()} +${elapsed}ms thread=${Thread.currentThread().name} "
        val output = header + message + "\n" + (if (exhausted) "" else error?.stackTraceToString().orEmpty().take(20000)) + "\n"
        // Mirror every record to latest.log, even when the dedicated file is unavailable.
        try { fallback(output) } catch (_: Exception) { /* Diagnostic logging must not alter a toggle. */ }
        catch (_: LinkageError) { /* Preserve the original activation behavior. */ }
        if (fileFailed) return
        try {
            val folder = directory()
            Files.createDirectories(folder)
            val path = folder.resolve(FILE_NAME)
            if (Files.exists(path) && Files.size(path) + output.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
                Files.move(path, folder.resolve("neo-invmanager-092.previous.log"), StandardCopyOption.REPLACE_EXISTING)
                announced = false
            }
            if (!announced) {
                Files.writeString(path, "\n=== 092-DIAGNOSTIC session=$session (NOT a confirmed fix) ===\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                announced = true
            }
            Files.writeString(path, output, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (failure: Exception) {
            fileFailed = true
            try { fallback("[NeoInvManager/092] Dedicated log unavailable; use latest.log. ${failure.javaClass.simpleName}") }
            catch (_: Exception) { /* Preserve activation behavior. */ }
            catch (_: LinkageError) { /* Preserve activation behavior. */ }
        }
    }
}
