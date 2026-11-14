/*
 * Live2DDebugLog —— 把 Live2D 的加载情况与每帧渲染屏幕坐标落盘。
 *
 * 输出文件：<gamedir>/LiquidBounce/live2d-debug.log
 * 每帧都写会拖慢渲染，所以按 MIN_INTERVAL_MS 节流；加载结果/异常用 event() 立即落盘。
 * 所有 IO 异常都被吞掉——调试日志绝不能影响渲染。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/Live2DDebugLog.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import net.ccbluex.liquidbounce.config.ConfigSystem
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object Live2DDebugLog {

    /** 普通行（每帧坐标）的最小写入间隔。 */
    private const val MIN_INTERVAL_MS = 500L

    /** 单文件上限，超过就重建，避免长期运行写爆存储。 */
    private const val MAX_BYTES = 4L * 1024L * 1024L

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    val path: Path
        get() = ConfigSystem.rootFolder.toPath().resolve("live2d-debug.log")

    private var lastWrite = 0L
    private var lastMessage = ""
    private var headerWritten = false

    /** 写一行，受节流约束；相同内容在窗口内重复出现时丢弃。 */
    @Synchronized
    fun line(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastWrite < MIN_INTERVAL_MS) return
        if (message == lastMessage) return
        lastWrite = now
        lastMessage = message
        append(LocalTime.now().format(TIME) + " " + message)
    }

    /** 无条件写一行：加载结果、失败原因、异常。 */
    @Synchronized
    fun event(message: String) {
        lastWrite = System.currentTimeMillis()
        lastMessage = message
        append(LocalTime.now().format(TIME) + " " + message)
    }

    private fun append(text: String) {
        try {
            val p = path
            p.parent?.let { Files.createDirectories(it) }

            if (!headerWritten) {
                headerWritten = true
                if (Files.isRegularFile(p) && Files.size(p) > MAX_BYTES) {
                    Files.delete(p)
                }
                Files.writeString(
                    p,
                    "===== Live2D debug session ${
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    } =====\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }

            Files.writeString(
                p,
                text + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        } catch (_: Throwable) {
            // 忽略：日志失败不影响渲染
        }
    }
}
