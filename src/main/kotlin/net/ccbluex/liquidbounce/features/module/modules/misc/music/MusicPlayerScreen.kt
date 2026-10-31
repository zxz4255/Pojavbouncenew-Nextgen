package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * MD3 播放器界面。
 * 26.3：KeyEvent / MouseButtonEvent / CharacterEvent；不用 GLFW（安卓无 org.lwjgl.glfw）。
 */
class MusicPlayerScreen(
    private val engine: MusicPlayerEngine,
    private val onCloseCallback: () -> Unit,
) : Screen(Component.literal("Music")) {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lb-music-ui").apply { isDaemon = true }
    }

    private var query: String = ""
    private var results: List<MusicTrack> = emptyList()
    private var selected = 0
    private var scroll = 0
    private var searching = false
    private var statusHint = "搜索网易云（无需登录）或播放本地音乐"

    private var panelX = 0
    private var panelY = 0
    private var panelW = 0
    private var panelH = 0
    private var listTop = 0
    private var listBottom = 0
    private var rowH = 28
    private var visibleRows = 8

    // GLFW key codes（数值常量，避免依赖 org.lwjgl.glfw）
    private object Key {
        const val ESCAPE = 256
        const val ENTER = 257
        const val KP_ENTER = 335
        const val UP = 265
        const val DOWN = 264
        const val SPACE = 32
        const val BACKSPACE = 259
        const val DELETE = 261
    }

    override fun init() {
        recomputeLayout()
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        recomputeLayout()
    }

    private fun recomputeLayout() {
        panelW = min(520, (width * 0.86f).toInt())
        panelH = min(340, (height * 0.82f).toInt())
        panelX = (width - panelW) / 2
        panelY = (height - panelH) / 2
        listTop = panelY + 72
        listBottom = panelY + panelH - 96
        rowH = 28
        visibleRows = max(3, (listBottom - listTop) / rowH)
        scroll = scroll.coerceIn(0, max(0, results.size - visibleRows))
        selected = selected.coerceIn(0, max(0, results.size - 1))
    }

    // 26.3 部分映射下 Screen.render 签名与 GuiGraphicsExtractor 不一致时，用 renderBackground 画 UI
    override fun renderBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        recomputeLayout()
        graphics.fill(0, 0, width, height, Md3Theme.withAlpha(0xFF000000.toInt(), 140))

        val x = panelX.toFloat()
        val y = panelY.toFloat()
        val w = panelW.toFloat()
        val h = panelH.toFloat()

        Md3Theme.fillRound(graphics, x, y, w, h, 20f, Md3Theme.SURFACE)
        Md3Theme.fillRound(graphics, x + 12, y + 12, w - 24, 40f, 14f, Md3Theme.SURFACE_CONTAINER)

        graphics.text(font, "Music", panelX + 28, panelY + 24, Md3Theme.PRIMARY, false)
        val searchLabel = if (query.isEmpty()) "输入关键词，回车搜索..." else query
        graphics.text(font, searchLabel, panelX + 90, panelY + 24, Md3Theme.ON_SURFACE_VARIANT, false)

        Md3Theme.fillRound(
            graphics,
            x + 12, listTop.toFloat(),
            w - 24, (listBottom - listTop).toFloat(),
            14f, Md3Theme.SURFACE_CONTAINER,
        )

        if (searching) {
            graphics.text(font, "搜索中...", panelX + 28, listTop + 16, Md3Theme.ON_SURFACE_VARIANT, false)
        } else if (results.isEmpty()) {
            graphics.text(font, statusHint, panelX + 28, listTop + 16, Md3Theme.ON_SURFACE_VARIANT, false)
        } else {
            val start = scroll
            val end = min(results.size, scroll + visibleRows)
            for (i in start until end) {
                val row = i - start
                val ry = listTop + row * rowH
                if (i == selected) {
                    Md3Theme.fillRound(
                        graphics,
                        x + 16, ry.toFloat() + 2,
                        w - 32, (rowH - 4).toFloat(),
                        10f, Md3Theme.PRIMARY_CONTAINER,
                    )
                }
                val track = results[i]
                val label = track.title + "  -  " + track.artist
                val color = if (i == selected) Md3Theme.ON_SURFACE else Md3Theme.ON_SURFACE_VARIANT
                graphics.text(font, trim(label, panelW - 56), panelX + 28, ry + 8, color, false)
            }
        }

        Md3Theme.fillRound(graphics, x + 12, y + h - 84, w - 24, 68f, 16f, Md3Theme.SURFACE_HIGH)
        val cur = engine.current
        val title = if (cur != null) cur.title + " - " + cur.artist else "未播放"
        graphics.text(font, trim(title, panelW - 48), panelX + 28, panelY + panelH - 72, Md3Theme.ON_SURFACE, false)

        val pos = engine.positionMs()
        val dur = engine.durationMs().coerceAtLeast(1)
        val progress = (pos.toFloat() / dur).coerceIn(0f, 1f)
        val barX = panelX + 28
        val barY = panelY + panelH - 42
        val barW = panelW - 56
        graphics.fill(barX, barY, barX + barW, barY + 4, Md3Theme.withAlpha(Md3Theme.OUTLINE, 120))
        graphics.fill(barX, barY, barX + (barW * progress).toInt(), barY + 4, Md3Theme.PRIMARY)

        val lyric = engine.lyrics.lineAt(pos)?.text ?: engine.status
        graphics.text(font, trim(lyric, panelW - 48), panelX + 28, panelY + panelH - 28, Md3Theme.PRIMARY, false)

        engine.error?.let {
            graphics.text(font, trim(it, panelW - 48), panelX + 28, panelY + panelH - 96, Md3Theme.ERROR, false)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val button = runCatching { event.button() }.getOrElse {
            runCatching { event.javaClass.getMethod("button").invoke(event) as Int }.getOrDefault(0)
        }
        if (button != 0) return super.mouseClicked(event, doubleClick)

        val mx = runCatching { event.x().toInt() }.getOrElse {
            runCatching { (event.javaClass.getMethod("x").invoke(event) as Number).toInt() }.getOrDefault(0)
        }
        val my = runCatching { event.y().toInt() }.getOrElse {
            runCatching { (event.javaClass.getMethod("y").invoke(event) as Number).toInt() }.getOrDefault(0)
        }

        if (mx in panelX..(panelX + panelW) && my in listTop until listBottom && results.isNotEmpty()) {
            val row = (my - listTop) / rowH
            val index = scroll + row
            if (index in results.indices) {
                selected = index
                playSelected()
                return true
            }
        }
        if (my in (panelY + panelH - 90)..(panelY + panelH - 20) &&
            mx in panelX..(panelX + panelW)
        ) {
            engine.togglePause()
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        if (results.isEmpty()) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical)
        scroll = (scroll - vertical.toInt()).coerceIn(0, max(0, results.size - visibleRows))
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val key = runCatching { event.key() }.getOrElse {
            runCatching { event.javaClass.getMethod("key").invoke(event) as Int }.getOrDefault(-1)
        }
        when (key) {
            Key.ESCAPE -> {
                onCloseCallback()
                return true
            }
            Key.ENTER, Key.KP_ENTER -> {
                if (query.isNotBlank() && !searching) search()
                else if (results.isNotEmpty()) playSelected()
                return true
            }
            Key.UP -> {
                selected = (selected - 1).coerceAtLeast(0)
                ensureVisible()
                return true
            }
            Key.DOWN -> {
                selected = (selected + 1).coerceAtMost(max(0, results.size - 1))
                ensureVisible()
                return true
            }
            Key.SPACE -> {
                engine.togglePause()
                return true
            }
            Key.BACKSPACE, Key.DELETE -> {
                if (query.isNotEmpty()) query = query.dropLast(1)
                return true
            }
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val codePoint = runCatching { event.codepoint() }.getOrElse {
            runCatching { event.javaClass.getMethod("codepoint").invoke(event) as Int }.getOrDefault(0)
        }
        if (codePoint > 0 && !Character.isISOControl(codePoint)) {
            query += codePoint.toChar()
            return true
        }
        return super.charTyped(event)
    }

    private fun ensureVisible() {
        if (selected < scroll) scroll = selected
        if (selected >= scroll + visibleRows) scroll = selected - visibleRows + 1
        scroll = scroll.coerceIn(0, max(0, results.size - visibleRows))
    }

    private fun search() {
        searching = true
        statusHint = "搜索中..."
        val q = query
        io.execute {
            val list = runCatching { NeteaseMusicApi.search(q) }.getOrDefault(emptyList())
            mc.execute {
                results = list
                selected = 0
                scroll = 0
                searching = false
                statusHint = if (list.isEmpty()) {
                    "无结果（网关可能不可用，可放本地文件到 music 目录）"
                } else {
                    "共 ${list.size} 首，回车播放"
                }
            }
        }
    }

    private fun playSelected() {
        val track = results.getOrNull(selected) ?: return
        val cache = mc.gameDirectory.toPath().resolve("LiquidBounce/music-cache")
        engine.playRemote(track, cache)
    }

    override fun onClose() {
        io.shutdownNow()
        onCloseCallback()
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    private fun trim(text: String, maxPx: Int): String {
        if (font.width(text) <= maxPx) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t...") > maxPx) t = t.dropLast(1)
        return "$t..."
    }
}
