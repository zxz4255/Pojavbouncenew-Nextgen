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
package net.ccbluex.liquidbounce.utils.debug

import net.ccbluex.liquidbounce.config.ConfigSystem
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.ThreadInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Records what the client was doing while it froze.
 *
 * The problem this exists for: the client sometimes hangs for a few minutes and then dies, and the
 * log ends with nothing useful — no exception, no stack, just the last line and then silence. A
 * Throwable-based reporter cannot help there, because a freeze is not an exception: the render thread
 * either blocks on a lock, spins forever, or is waiting on native code that never returns.
 *
 * So this runs a **separate watchdog thread** that does nothing but observe:
 *
 *  1. the render thread bumps [heartbeat] every frame, and the client thread every tick;
 *  2. the watchdog notices when a heartbeat goes stale and, without any cooperation from the frozen
 *     thread, dumps every thread's stack via [ManagementFactory.getThreadMXBean];
 *  3. it also collects lock owners, so "who holds the monitor everyone is waiting for" is answered
 *     directly instead of being inferred;
 *  4. everything goes to a file under the LiquidBounce folder, flushed immediately, so the evidence
 *     survives even when the process is killed right afterwards.
 *
 * The watchdog thread must never touch the game, a lock, or anything that can block. It only reads
 * [AtomicLong]s and the JVM's thread snapshots.
 */
object FreezeWatchdog {

    /** Stale threshold for a frame. At 60 fps a healthy gap is ~16 ms; 5 s is unmistakably broken. */
    private const val RENDER_STALL_MS = 5_000L

    /** A tick every 50 ms; 10 s means several ticks were fully missed. */
    private const val TICK_STALL_MS = 10_000L

    private const val SAMPLE_INTERVAL_MS = 1_000L

    /** How many stall reports to write per run, so a permanently wedged client cannot fill the disk. */
    private const val MAX_REPORTS = 60

    /** While a stall lasts, take another snapshot this often. */
    private const val REPEAT_SAMPLE_MS = 15_000L

    private val started = AtomicBoolean(false)

    private val renderHeartbeat = AtomicLong(System.currentTimeMillis())
    private val clientHeartbeat = AtomicLong(System.currentTimeMillis())

    /** Name of the thread that last bumped the render heartbeat, to make a wrong-thread bug visible. */
    @Volatile
    private var renderThreadName: String = "?"

    @Volatile
    private var clientThreadName: String = "?"

    private val reportsWritten = AtomicLong(0)

    /** Last GC totals, so a report can say whether collection is actively thrashing. */
    @Volatile
    private var lastGcCount = -1L

    @Volatile
    private var lastGcTime = -1L

    /** Rolling description of what the client was doing, newest last. Read by the watchdog. */
    private val recentEvents = ArrayDeque<String>()

    @Volatile
    private var enabled = false

    private val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())

    private val reportFile: File by lazy {
        File(ConfigSystem.rootFolder, "freeze-report-$stamp.txt")
    }

    private var watchdog: Thread? = null

    /**
     * Starts the watchdog. Safe to call repeatedly; only the first call has an effect.
     */
    fun start() {
        enabled = true
        val now = System.currentTimeMillis()
        renderHeartbeat.set(now)
        clientHeartbeat.set(now)

        if (!started.compareAndSet(false, true)) {
            return
        }

        val thread = Thread({ loop() }, "liquidbounce-freeze-watchdog")
        thread.isDaemon = true
        // Lowest priority: if the process is starved we want the game to win, not the observer.
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
        watchdog = thread

        record("看门狗已启动，报告将写入 ${reportFile.absolutePath}")
    }

    fun stop() {
        enabled = false
        watchdog?.interrupt()
        watchdog = null
        started.set(false)
    }

    /**
     * Called once per rendered frame, from the render thread.
     *
     * Deliberately allocation-free apart from the name lookup on the first call: this is on the hot
     * path of every frame.
     */
    fun onFrame() {
        renderHeartbeat.set(System.currentTimeMillis())
        if (renderThreadName == "?") {
            renderThreadName = Thread.currentThread().name
        }
    }

    /** Called once per client tick. */
    fun onTick() {
        clientHeartbeat.set(System.currentTimeMillis())
        if (clientThreadName == "?") {
            clientThreadName = Thread.currentThread().name
        }
    }

    /**
     * Appends a breadcrumb describing what just happened.
     *
     * Breadcrumbs are what turn a stack dump into a story: a stack says "blocked in `Screen.init`",
     * while the last few breadcrumbs say "the user just closed a chest screen while a chest stealer
     * was replacing it". Keep them short and cheap.
     */
    fun record(event: String) {
        if (!enabled) {
            return
        }
        val line = String.format(Locale.ROOT, "%s  %s", timeOfDay(), event)
        synchronized(recentEvents) {
            recentEvents.addLast(line)
            while (recentEvents.size > 200) {
                recentEvents.removeFirst()
            }
        }
    }

    private fun loop() {
        var nextRenderSample = 0L
        var nextTickSample = 0L

        while (enabled) {
            try {
                Thread.sleep(SAMPLE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (!enabled) {
                return
            }

            val now = System.currentTimeMillis()
            val renderAge = now - renderHeartbeat.get()
            val clientAge = now - clientHeartbeat.get()

            val renderStalled = renderAge >= RENDER_STALL_MS
            val clientStalled = clientAge >= TICK_STALL_MS

            // Recovery ends the episode, so the next freeze is reported immediately again.
            if (!renderStalled) {
                nextRenderSample = 0L
            }
            if (!clientStalled) {
                nextTickSample = 0L
            }
            if (!renderStalled && !clientStalled) {
                continue
            }
            if (reportsWritten.get() >= MAX_REPORTS) {
                continue
            }

            /*
             * Repeat while the stall lasts, rather than writing once and going quiet.
             *
             * A frozen client frequently moves through several states — blocked on a monitor, then
             * past it and stuck in native code, then in a loop — and a single snapshot at the moment
             * the threshold tripped can catch an innocent stage. Sampling every
             * REPEAT_SAMPLE_MS shows which frames are common to all of them, which is the actual
             * culprit.
             */
            val dueRender = renderStalled && now >= nextRenderSample
            val dueTick = clientStalled && now >= nextTickSample
            if (!dueRender && !dueTick) {
                continue
            }

            if (dueRender) {
                nextRenderSample = now + REPEAT_SAMPLE_MS
            }
            if (dueTick) {
                nextTickSample = now + REPEAT_SAMPLE_MS
            }

            writeStallReport(renderAge, clientAge, dueRender, dueTick)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun writeStallReport(
        renderAge: Long,
        clientAge: Long,
        renderStalled: Boolean,
        clientStalled: Boolean,
        reason: String? = null
    ) {
        val count = reportsWritten.incrementAndGet()
        val builder = StringBuilder(16 * 1024)

        builder.appendLine("================================================================================")
        builder.appendLine("卡死报告 #$count    ${timeOfDay()}")
        builder.appendLine("================================================================================")
        builder.appendLine()
        builder.appendLine("渲染线程静默 ${renderAge} ms  (阈值 $RENDER_STALL_MS ms)")
        builder.appendLine("客户端 tick 静默 ${clientAge} ms  (阈值 $TICK_STALL_MS ms)")
        builder.appendLine(
            "触发的来源： ${reason ?: sourceText(renderStalled, clientStalled)}"
        )
        builder.appendLine("渲染心跳来自线程： $renderThreadName")
        builder.appendLine("tick 心跳来自线程： $clientThreadName")
        builder.appendLine()

        appendMemory(builder)
        builder.appendLine()

        appendGc(builder)
        builder.appendLine()

        appendEvents(builder)
        builder.appendLine()

        appendThreads(builder)

        val text = builder.toString()
        try {
            reportFile.parentFile?.mkdirs()
            reportFile.appendText(text)
        } catch (_: Throwable) {
            // Never let the reporter be the thing that breaks the client.
        }

        // Main log too: on a launcher the console is often the only place the user can copy from.
        for (line in text.lineSequence()) {
            println("[FreezeWatchdog] $line")
        }
    }

    private fun sourceText(renderStalled: Boolean, clientStalled: Boolean): String = when {
        renderStalled && clientStalled -> "渲染线程与 tick 都不动了（整个主线程卡住）"
        renderStalled -> "只有渲染线程不动（帧不再产出，大概率阻塞在渲染或 GUI 路径）"
        else -> "只有 tick 不动（渲染仍在走，逻辑线程被阻塞）"
    }

    private fun appendMemory(builder: StringBuilder) {
        val runtime = Runtime.getRuntime()
        val total = runtime.totalMemory()
        val free = runtime.freeMemory()
        val max = runtime.maxMemory()
        builder.appendLine("-- 内存 --")
        builder.appendLine(
            String.format(
                Locale.ROOT,
                "堆: 已用 %.1f MB / 上限 %.1f MB (总量 %.1f MB, 空闲 %.1f MB)",
                (total - free) / 1048576.0,
                max / 1048576.0,
                total / 1048576.0,
                free / 1048576.0
            )
        )
        builder.appendLine("可用处理器: ${runtime.availableProcessors()}")
    }

    /**
     * GC state, because "the client freezes for a few seconds" is very often a stop-the-world
     * collection rather than a deadlock — and a pure stack dump looks identical in both cases (every
     * thread parked, nothing obviously wrong).
     *
     * A rising collection count with long total time, or a heap sitting at its ceiling, points at
     * memory pressure instead of a lock.
     */
    private fun appendGc(builder: StringBuilder) {
        builder.appendLine("-- GC --")
        try {
            val beans = ManagementFactory.getGarbageCollectorMXBeans()
            if (beans.isEmpty()) {
                builder.appendLine("(无 GC bean)")
                return
            }
            var totalCount = 0L
            var totalTime = 0L
            for (bean in beans) {
                val count = bean.collectionCount
                val time = bean.collectionTime
                if (count < 0) {
                    continue
                }
                totalCount += count
                totalTime += if (time > 0) time else 0L
                builder.appendLine("  ${bean.name}: ${count} 次 / 累计 ${time} ms")
            }
            builder.appendLine("  合计: $totalCount 次 / 累计 $totalTime ms")

            // Compare against the previous sample to show whether GC is currently thrashing.
            if (lastGcTime < 0L) {
                builder.appendLine("  (首次采样，无对比基线)")
            } else {
                val deltaCount = totalCount - lastGcCount
                val deltaTime = totalTime - lastGcTime
                builder.appendLine("  自上次采样以来: $deltaCount 次 / $deltaTime ms")
                if (deltaTime > 500L || deltaCount > 20L) {
                    builder.appendLine("  !!! GC 活动明显偏高，卡顿很可能来自内存压力而非锁 !!!")
                }
            }
            lastGcCount = totalCount
            lastGcTime = totalTime
        } catch (_: Throwable) {
            builder.appendLine("(无法读取 GC 统计)")
        }
    }

    private fun appendEvents(builder: StringBuilder) {
        builder.appendLine("-- 卡死前最后 60 条操作 --")
        val snapshot = synchronized(recentEvents) { recentEvents.toList() }
        if (snapshot.isEmpty()) {
            builder.appendLine("(无记录)")
        } else {
            snapshot.takeLast(60).forEach { builder.appendLine(it) }
        }
    }

    /**
     * The important part: every thread's stack, plus who owns each lock.
     *
     * Ordered so the threads that usually cause a freeze come first — a blocked render thread is the
     * symptom, and the culprit is whoever holds the monitor it wants.
     */
    private fun appendThreads(builder: StringBuilder) {
        val bean = ManagementFactory.getThreadMXBean()

        // Detect the classic case directly instead of making the reader cross-reference stacks.
        val deadlocked = try {
            bean.findDeadlockedThreads()
        } catch (_: Throwable) {
            null
        }
        if (deadlocked != null && deadlocked.isNotEmpty()) {
            builder.appendLine("!!! 检测到 JVM 级死锁，涉及 ${deadlocked.size} 个线程 !!!")
            deadlocked.forEach { builder.appendLine("  死锁线程: $it") }
            builder.appendLine()
        }

        val infos: Array<ThreadInfo> = try {
            bean.dumpAllThreads(true, true)
        } catch (_: Throwable) {
            builder.appendLine("(无法获取线程快照)")
            return
        }

        // Render/client first, then the music worker, then whatever holds locks, then the rest.
        val priority = { info: ThreadInfo ->
            val name = info.threadName.orEmpty()
            when {
                name.contains("Render thread", ignoreCase = true) -> 0
                name.contains("Client thread", ignoreCase = true) -> 1
                name.contains("liquidbounce-music", ignoreCase = true) -> 2
                name.contains("droidbridge", ignoreCase = true) -> 3
                info.lockOwnerId >= 0 -> 4
                else -> 5
            }
        }
        val sorted = infos.sortedBy(priority)

        builder.appendLine("-- 线程快照 (${sorted.size} 个) --")
        for (info in sorted) {
            builder.appendLine()
            builder.append("[${info.threadState}] ${info.threadName}  id=${info.threadId}")

            if (info.lockOwnerId >= 0) {
                builder.appendLine("  阻塞在锁上，持有者线程 id=${info.lockOwnerId}")
            }

            // Blocked/waiting threads: say precisely what they are stuck on.
            if (info.blockedTime >= 0 && info.blockedCount > 0) {
                builder.appendLine("  累计阻塞 ${info.blockedTime} ms / ${info.blockedCount} 次")
            }
            if (info.waitedTime >= 0 && info.waitedCount > 0 && info.threadState == Thread.State.WAITING) {
                builder.appendLine("  累计等待 ${info.waitedTime} ms / ${info.waitedCount} 次")
            }

            val stack = info.stackTrace
            if (stack.isEmpty()) {
                builder.appendLine("  (无栈)")
                continue
            }
            val heldMonitors = HashSet<StackTraceElement>()
            info.lockedMonitors?.forEach { monitor ->
                monitor.lockedStackFrame?.let(heldMonitors::add)
            }
            // Synchronizers (ownable synchronizers) carry no stack frame of their own, only a class
            // name, so they are reported separately rather than matched against frames.
            val synchronizers = info.lockedSynchronizers
                ?.mapNotNull { it.className }
                .orEmpty()
            for (frame in stack) {
                val mark = if (frame in heldMonitors) "  [持有锁]" else ""
                builder.appendLine("    at $frame$mark")
            }
            if (synchronizers.isNotEmpty()) {
                builder.appendLine("  持有同步器: ${synchronizers.joinToString()}")
            }
        }
    }

    private fun timeOfDay(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())

    /**
     * Dumps the current state right now, whether or not anything looks stalled.
     *
     * Meant for the "it stutters badly but recovers" case, where the automatic watchdog never fires
     * because no heartbeat ever goes stale for long enough. The user triggers it while the symptom is
     * present, and the dump shows which thread is on the hot path at that instant.
     *
     * Runs synchronously on the calling thread: it is a deliberate, one-off action, and the caller
     * wants the answer before the moment passes.
     */
    fun snapshotNow(reason: String): String {
        val now = System.currentTimeMillis()
        writeStallReport(
            renderAge = now - renderHeartbeat.get(),
            clientAge = now - clientHeartbeat.get(),
            renderStalled = false,
            clientStalled = false,
            reason = reason
        )
        return reportFile.absolutePath
    }

    /** Path of the report file, for a command or a notification to point at. */
    fun reportPath(): String = reportFile.absolutePath

    fun reportsSoFar(): Long = reportsWritten.get()
}
