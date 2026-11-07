package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.render.drawCircle
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.logger
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.PreeditEvent
import net.minecraft.network.chat.Component
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** Compact MD3-inspired tonal surfaces, top-level destinations, shared measured hit geometry.
 * No fixed 900px rail, decorative blur or glow copies. The panel width/height are independent settings.
 */
class MusicScreen : Screen(Component.literal("Music")) {
    private val module get() = ModuleMusic
    private val engine get() = module.engine
    private val w get() = MusicUi.DESIGN_WIDTH
    private val h get() = MusicUi.DESIGN_HEIGHT
    private val bodyWidth get() = w - 48f
    private val listTop = 236f
    private val playerY get() = h - 100f
    private val rowHeight = 48f
    private val visibleRows get() = ((playerY - 26f - listTop) / rowHeight).toInt().coerceAtLeast(3)
    private val modalW = 456f
    private val modalH = 400f
    private val modalX get() = (w - modalW) / 2f
    private val modalY get() = (h - modalH) / 2f
    private enum class Destination(val label: String) { SEARCH("发现"), HISTORY("最近播放"), FAVORITES("收藏"), PLAYLISTS("歌单") }
    private enum class Dialog { NONE, LOGIN, IMPORT, PLAYLIST }
    private var destination = Destination.SEARCH
    private var dialog = Dialog.NONE
    private var source = MusicSource.NETEASE
    private var loginSource = MusicSource.NETEASE
    private var loginMethod = LoginMethod.QR
    private var tracks = emptyList<MusicTrack>()
    private var cloudLists = emptyList<MusicCloudLibrary.Playlist>()
    private var selectedPlaylist: String? = null
    private var cloud = false
    private var cloudTrackList = false
    private var selected = -1
    private var firstRow = 0
    private var page = 0
    private var loading = false
    private var message = "搜索歌曲，或打开按平台独立保存的本机曲库。"
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "Music-ScreenIO").apply { isDaemon = true } }
    private val generation = AtomicLong()
    private var disposed = false
    private var scale = 1f
    private var ox = 0f
    private var oy = 0f
    private var oldWidth = -1
    private var oldHeight = -1
    private var oldPanelWidth = -1f
    private var oldPanelHeight = -1f
    private var oldUiScale = -1f
    private var pointerX = 0f
    private var pointerY = 0f
    private var dragging: String? = null
    private var reported = false
    private lateinit var search: MusicInput
    private lateinit var path: MusicInput
    private lateinit var phone: MusicInput
    private lateinit var code: MusicInput
    private lateinit var email: MusicInput
    private lateinit var password: MusicInput
    private lateinit var token: MusicInput
    private lateinit var playlistName: MusicInput
    private val fields get() = listOf(search, path, phone, code, email, password, token, playlistName)
    private data class Hit(val id: String, val x: Float, val y: Float, val w: Float, val h: Float) {
        fun contains(x: Float, y: Float) = x >= this.x && y >= this.y && x < this.x + w && y < this.y + h
    }
    private val hits = mutableListOf<Hit>()
    private fun hot(id: String, x: Float, y: Float, w: Float, h: Float) { hits += Hit(id, x, y, w, h) }
    private fun resolve(x: Float, y: Float) = hits.asReversed().firstOrNull {
        it.contains(x, y) && (dialog == Dialog.NONE || it.id.startsWith("dialog:"))
    }?.id

    override fun init() {
        val previous = if (::search.isInitialized) search.value else ""
        clearWidgets()
        module.refreshTheme()
        fun field(label: String, hint: String, length: Int) = MusicInput(label, hint).apply {
            setMaxLength(length); setTextColor(MonetPalette.text.argb)
        }
        search = field("搜索", "歌曲、音乐人、专辑", 120).apply { value = previous }
        path = field("路径", "粘贴 .mp3 / .flac 绝对路径", 1024)
        phone = field("手机号", "中国大陆手机号", 20)
        code = field("验证码", "短信验证码", 8)
        email = field("账号", "网易邮箱", 120)
        password = field("密码", "密码（不保存）", 120).apply { secret = true }
        token = field("Cookie", "仅粘贴当前平台的 Cookie", 8192).apply { secret = true }
        playlistName = field("歌单名", "新歌单名称", 64)
        fields.forEach { addRenderableWidget(it); it.setFocused(false) }
        relayout()
    }
    override fun repositionElements() { super.repositionElements(); if (::search.isInitialized) relayout() }
    private fun relayout() {
        oldWidth = width; oldHeight = height; oldPanelWidth = w; oldPanelHeight = h; oldUiScale = module.uiScale
        scale = MusicUi.fit(width, height, module.uiScale)
        ox = (width - w * scale) / 2f; oy = (height - h * scale) / 2f
        positionFields()
    }
    private fun positionFields() {
        if (!::search.isInitialized) return
        fields.forEach { it.visible = false; it.active = false }
        fun place(box: MusicInput, x: Float, y: Float, bw: Float, bh: Float) {
            box.setWidth((bw * scale).roundToInt().coerceIn(1, max(1, width - 2)))
            box.setHeight((bh * scale).roundToInt().coerceIn(1, max(1, height - 2)))
            box.setX((ox + x * scale).roundToInt().coerceIn(1, max(1, width - box.width - 1)))
            box.setY((oy + y * scale).roundToInt().coerceIn(1, max(1, height - box.height - 1)))
            box.uiScale = scale
            box.visible = box.width > 9 && box.height > 9; box.active = box.visible
        }
        when (dialog) {
            Dialog.NONE -> if (destination == Destination.SEARCH) place(search, 28f, 151f, bodyWidth - 94f, 36f)
            Dialog.IMPORT -> place(path, modalX + 24f, modalY + 126f, modalW - 48f, 40f)
            Dialog.PLAYLIST -> place(playlistName, modalX + 24f, modalY + 126f, modalW - 48f, 40f)
            Dialog.LOGIN -> when (loginMethod) {
                LoginMethod.PHONE -> {
                    place(phone, modalX + 24f, modalY + 164f, modalW - 48f, 36f)
                    place(code, modalX + 24f, modalY + 212f, modalW - 170f, 36f)
                }
                LoginMethod.EMAIL -> {
                    place(email, modalX + 24f, modalY + 164f, modalW - 48f, 36f)
                    place(password, modalX + 24f, modalY + 212f, modalW - 48f, 36f)
                }
                LoginMethod.TOKEN -> place(token, modalX + 24f, modalY + 172f, modalW - 48f, 44f)
                else -> Unit
            }
        }
        fields.filterNot { it.visible }.forEach { it.setFocused(false); it.resetComposition() }
    }
    private fun show(value: Dialog) {
        dragging = null
        fields.forEach { it.setFocused(false); it.resetComposition() }
        dialog = value; positionFields()
    }
    private fun closeDialog() {
        generation.incrementAndGet(); loading = false
        password.value = ""; token.value = ""; code.value = ""
        show(Dialog.NONE)
    }
    override fun extractBackground(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        MusicUi.rect(ctx, 0f, 0f, width.toFloat(), height.toFloat(), 0f, Color4b(5, 9, 13, 174))
    }
    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (width != oldWidth || height != oldHeight || w != oldPanelWidth || h != oldPanelHeight || oldUiScale != module.uiScale) relayout()
        pointerX = (mouseX - ox) / scale; pointerY = (mouseY - oy) / scale
        module.refreshTheme(); hits.clear()
        ctx.pose().pushMatrix()
        try {
            ctx.pose().translate(ox, oy); ctx.pose().scale(scale, scale)
            MusicUi.rect(ctx, 0f, 0f, w, h, module.cornerRadius,
                MonetPalette.alpha(MonetPalette.background, module.panelOpacity / 100f))
            drawAppBar(ctx); drawDestinations(ctx); drawBody(ctx); drawPlayer(ctx)
            if (dialog != Dialog.NONE) drawDialog(ctx)
        } catch (e: Exception) {
            if (!reported) { reported = true; logger.error("[Music] screen extraction failed", e) }
        } finally { ctx.pose().popMatrix() }
        super.extractRenderState(ctx, mouseX, mouseY, delta)
    }
    private fun button(ctx: GuiGraphicsExtractor, id: String, label: String, x: Float, y: Float, bw: Float,
                       bh: Float = 32f, active: Boolean = false, size: Float = 10.5f) {
        val hover = pointerX >= x && pointerX < x + bw && pointerY >= y && pointerY < y + bh &&
            (dialog == Dialog.NONE || id.startsWith("dialog:"))
        val background = if (active) MonetPalette.primaryContainer else MonetPalette.surface
        MusicUi.rect(ctx, x, y, bw, bh, bh / 2f,
            if (hover) MonetPalette.mix(background, MonetPalette.primary, 0.14f) else background)
        MusicUi.text(ctx, label, x + bw / 2, y + (bh - size) / 2 - 1f, size,
            if (active) MonetPalette.primary else MonetPalette.text, true, bw - 12f)
        hot(id, x, y, bw, bh)
    }
    private fun drawAppBar(ctx: GuiGraphicsExtractor) {
        MusicUi.text(ctx, "Music", 24f, 21f, 25f, MonetPalette.text)
        MusicUi.text(ctx, "MONET / PERSONAL SOUNDSPACE", 110f, 31f, 8f, MonetPalette.muted)
        button(ctx, "import", "导入", w - 84f, 20f, 60f)
        val platforms = MusicSource.entries
        var x = 24f
        platforms.forEachIndexed { i, p ->
            button(ctx, "source:$i", p.label, x, 66f, 76f, active = source == p)
            x += 82f
        }
        // Two independent account entries remain visible, regardless of the selected catalogue.
        button(ctx, "account:NETEASE", MusicLogin.session(MusicSource.NETEASE)?.let { "网易 · ${it.nickname}" } ?: "网易云登录",
            w - 238f, 66f, 104f, active = MusicLogin.isLoggedIn(MusicSource.NETEASE), size = 9f)
        button(ctx, "account:KUGOU", MusicLogin.session(MusicSource.KUGOU)?.let { "酷狗 · ${it.nickname}" } ?: "酷狗登录",
            w - 128f, 66f, 104f, active = MusicLogin.isLoggedIn(MusicSource.KUGOU), size = 9f)
    }
    private fun drawDestinations(ctx: GuiGraphicsExtractor) {
        Destination.entries.forEachIndexed { i, d ->
            val bw = bodyWidth / 4f
            button(ctx, "destination:$i", d.label, 24f + i * bw, 108f, bw - 4f, 30f, destination == d)
        }
    }
    private fun drawBody(ctx: GuiGraphicsExtractor) {
        if (destination == Destination.SEARCH) {
            MusicUi.rect(ctx, 24f, 150f, bodyWidth, 38f, 19f, MonetPalette.surfaceHigh)
            button(ctx, "search", if (loading) "…" else "搜索", w - 106f, 153f, 76f, active = true)
            var x = 24f
            val suggestions = MusicUserLibrary.searches(source)
            for ((index, query) in suggestions.withIndex()) {
                val bw = MusicUi.width(query, 9f).coerceIn(36f, 100f) + 18f
                if (x + bw > w - 72f) break
                button(ctx, "recent:$index", query, x, 196f, bw, 24f, size = 9f); x += bw + 6f
            }
            if (suggestions.isNotEmpty()) button(ctx, "clearSearches", "清空", w - 64f, 196f, 40f, 24f, size = 8.5f)
        } else {
            button(ctx, "device", "本机", 24f, 152f, 64f, active = !cloud)
            button(ctx, "cloud", "平台云端", 94f, 152f, 92f, active = cloud)
            MusicUi.text(ctx, if (cloud) "仅展示平台实际返回的数据" else "本机保存 · 按平台 / 账号隔离 · 不同步至平台",
                198f, 163f, 9f, MonetPalette.muted, maxWidth = w - 230f)
            if (destination == Destination.PLAYLISTS && !cloud) {
                button(ctx, "newPlaylist", "+ 新歌单", 24f, 194f, 90f, 26f)
                if (selectedPlaylist != null) {
                    button(ctx, "playlistBack", "所有歌单", 122f, 194f, 88f, 26f)
                    button(ctx, "addCurrent", "加入正在播放", 218f, 194f, 120f, 26f)
                    button(ctx, "deletePlaylist", "删除歌单", 346f, 194f, 88f, 26f)
                }
            } else button(ctx, "refresh", "刷新", 24f, 194f, 70f, 26f)
        }
        if (destination == Destination.PLAYLISTS && selectedPlaylist == null && !cloudTrackList) drawPlaylistRows(ctx)
        else drawTrackRows(ctx)
        val y = playerY - 22f
        MusicUi.text(ctx, message, 24f, y, 9f, MonetPalette.muted, maxWidth = bodyWidth - 120f)
        button(ctx, "pagePrev", "‹", w - 106f, y - 6f, 36f, 24f)
        button(ctx, "pageNext", "›", w - 64f, y - 6f, 40f, 24f)
    }
    private fun drawTrackRows(ctx: GuiGraphicsExtractor) {
        if (tracks.isEmpty()) {
            MusicUi.text(ctx, if (loading) "正在读取…" else "留一点空间给下一首歌", w / 2, listTop + 60f,
                18f, MonetPalette.text, true)
            MusicUi.text(ctx, if (cloud) "云端数据不会用本地条目替代" else "搜索、播放或收藏后，音乐会出现在这里", w / 2,
                listTop + 90f, 10f, MonetPalette.muted, true)
            return
        }
        firstRow = firstRow.coerceIn(0, max(0, tracks.size - visibleRows))
        for (slot in 0 until visibleRows) {
            val i = firstRow + slot; val track = tracks.getOrNull(i) ?: break
            val y = listTop + slot * rowHeight
            val active = i == selected || track.key == engine.state.track?.key
            MusicUi.rect(ctx, 24f, y, bodyWidth, rowHeight - 4f, 14f,
                if (active) MonetPalette.primaryContainer else MonetPalette.surface)
            hot("track:$i", 24f, y, bodyWidth, rowHeight - 4f)
            MusicArtwork.draw(ctx, 30f, y + 4f, 36f, track)
            MusicUi.text(ctx, track.title, 78f, y + 7f, 12f, if (active) MonetPalette.primary else MonetPalette.text,
                maxWidth = bodyWidth - 218f)
            MusicUi.text(ctx, track.subtitle, 78f, y + 25f, 9f, MonetPalette.muted, maxWidth = bodyWidth - 218f)
            button(ctx, "favorite:$i", if (MusicUserLibrary.favorite(track)) "本机✓" else "本机藏", w - 178f, y + 7f, 48f, 28f,
                active = MusicUserLibrary.favorite(track), size = 9f)
            button(ctx, "queueList:$i", if (cloud) { if (destination == Destination.FAVORITES) "取消" else "云藏" } else if (selectedPlaylist != null) "−" else "+", w - 124f, y + 7f, 36f, 28f)
            button(ctx, "play:$i", "播放", w - 82f, y + 7f, 52f, 28f, active = active, size = 9f)
        }
    }
    private fun drawPlaylistRows(ctx: GuiGraphicsExtractor) {
        val local = if (cloud) emptyList() else MusicUserLibrary.playlists(source)
        val names = if (cloud) cloudLists.map { it.name to it.count } else local.map { it.name to it.tracks.size }
        firstRow = firstRow.coerceIn(0, max(0, names.size - visibleRows))
        names.drop(firstRow).take(visibleRows).forEachIndexed { slot, (name, count) ->
            val y = listTop + slot * rowHeight
            button(ctx, "playlist:${firstRow + slot}", "$name · $count 首", 24f, y, bodyWidth, 42f, size = 12f)
        }
        if (names.isEmpty()) MusicUi.text(ctx, if (loading) "正在读取歌单…" else "暂无歌单", w / 2,
            listTop + 72f, 17f, MonetPalette.muted, true)
    }
    private fun drawPlayer(ctx: GuiGraphicsExtractor) {
        val state = engine.state; val track = state.track
        MusicUi.rect(ctx, 16f, playerY, w - 32f, 84f, 24f, MonetPalette.surfaceHigh)
        MusicArtwork.draw(ctx, 28f, playerY + 12f, 56f, track)
        MusicUi.text(ctx, track?.title ?: "尚未播放", 96f, playerY + 12f, 13f, MonetPalette.text, maxWidth = w - 360f)
        val sub = if (state.status == PlaybackStatus.ERROR || state.status == PlaybackStatus.LOADING) state.error else track?.artist.orEmpty()
        MusicUi.text(ctx, sub, 96f, playerY + 31f, 9f, if (state.status == PlaybackStatus.ERROR) MonetPalette.error else MonetPalette.muted,
            maxWidth = w - 360f)
        val bw = w - 344f
        val ratio = if (state.durationMs > 0) (engine.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
        MusicUi.rect(ctx, 96f, playerY + 56f, bw, 4f, 2f, MonetPalette.outline)
        if (ratio > 0) MusicUi.rect(ctx, 96f, playerY + 56f, bw * ratio, 4f, 2f, MonetPalette.primary)
        ctx.drawCircle(96f + bw * ratio, playerY + 58f, 3f) { MonetPalette.primary.argb }
        hot("seek", 92f, playerY + 47f, bw + 8f, 20f)
        MusicUi.text(ctx, "${MusicUi.clock(engine.positionMs)} / ${MusicUi.clock(state.durationMs)}", 96f,
            playerY + 68f, 8f, MonetPalette.muted)
        val x = w - 222f
        button(ctx, "previous", "上一", x, playerY + 12f, 42f, 32f, size = 9f)
        button(ctx, "pause", if (state.status == PlaybackStatus.PLAYING) "暂停" else "播放", x + 48f, playerY + 8f,
            60f, 40f, true)
        button(ctx, "next", "下一", x + 114f, playerY + 12f, 42f, 32f, size = 9f)
        button(ctx, "hud", "HUD", x, playerY + 54f, 48f, 22f, module.showHud, 8f)
        button(ctx, "world", "3D", x + 54f, playerY + 54f, 42f, 22f, module.showWorld, 8f)
        val volumeX = x + 108f
        MusicUi.rect(ctx, volumeX, playerY + 63f, 60f, 3f, 1.5f, MonetPalette.outline)
        if (engine.volume > 0) MusicUi.rect(ctx, volumeX, playerY + 63f, 60f * engine.volume, 3f, 1.5f, MonetPalette.primary)
        hot("volume", volumeX - 3f, playerY + 51f, 66f, 24f)
    }
    private fun drawDialog(ctx: GuiGraphicsExtractor) {
        // The scrim consumes the whole screen via event overrides, not just this rectangle.
        MusicUi.rect(ctx, 0f, 0f, w, h, module.cornerRadius, Color4b(0, 0, 0, 178))
        MusicUi.rect(ctx, modalX, modalY, modalW, modalH, 28f, MonetPalette.surfaceHighest)
        val title = when (dialog) { Dialog.LOGIN -> "账号与认证"; Dialog.IMPORT -> "导入音乐"; else -> "创建本机歌单" }
        MusicUi.text(ctx, title, modalX + 24f, modalY + 22f, 20f, MonetPalette.text)
        button(ctx, "dialog:close", "关闭", modalX + modalW - 78f, modalY + 18f, 54f, 28f)
        if (dialog != Dialog.LOGIN) {
            MusicUi.text(ctx, if (dialog == Dialog.IMPORT) "支持 MP3 / FLAC；同名 LRC 自动载入，不上传文件。" else "仅保存在本机的当前平台 / 当前账号分区。",
                modalX + 24f, modalY + 84f, 10f, MonetPalette.muted, maxWidth = modalW - 48f)
            MusicUi.rect(ctx, modalX + 24f, modalY + 124f, modalW - 48f, 44f, 14f, MonetPalette.surface)
            button(ctx, "dialog:confirm", if (dialog == Dialog.IMPORT) "导入并播放" else "创建歌单", modalX + 24f,
                modalY + 192f, 128f, 36f, true)
            return
        }
        button(ctx, "dialog:platform:NETEASE", "网易云", modalX + 24f, modalY + 64f, 100f, 30f, loginSource == MusicSource.NETEASE)
        button(ctx, "dialog:platform:KUGOU", "酷狗", modalX + 132f, modalY + 64f, 100f, 30f, loginSource == MusicSource.KUGOU)
        MusicUi.text(ctx, MusicLogin.session(loginSource)?.nickname ?: "未认证", modalX + 248f, modalY + 74f, 10f, MonetPalette.muted,
            maxWidth = modalW - 272f)
        LoginMethod.entries.forEachIndexed { i, method ->
            button(ctx, "dialog:method:$i", method.label, modalX + 24f + i * 104f, modalY + 108f, 96f, 28f,
                loginMethod == method, 9f)
        }
        when (loginMethod) {
            LoginMethod.QR -> {
                MusicLogin.tick()
                val qr = MusicLogin.qrState(loginSource)
                MusicUi.rect(ctx, modalX + 24f, modalY + 154f, 172f, 172f, 12f, Color4b.WHITE)
                if (qr.payload.isNotBlank() && qr.phase !in setOf(QrPhase.EXPIRED, QrPhase.FAILED))
                    MusicQr.draw(ctx, modalX + 30f, modalY + 160f, 160f, qr.payload)
                else MusicUi.text(ctx, qr.phase.label, modalX + 110f, modalY + 230f, 10f, MonetPalette.defaultBackground, true)
                MusicUi.text(ctx, qr.phase.label, modalX + 216f, modalY + 160f, 12f, MonetPalette.primary, maxWidth = 212f)
                MusicUi.wrap(qr.message, 208f, 10f).take(5).forEachIndexed { i, text ->
                    MusicUi.text(ctx, text, modalX + 216f, modalY + 190f + i * 15f, 10f, MonetPalette.muted)
                }
                button(ctx, "dialog:qr", "刷新二维码", modalX + 216f, modalY + 282f, 124f, 34f, true)
            }
            else -> {
                if (loginMethod == LoginMethod.PHONE || loginMethod == LoginMethod.EMAIL) {
                    MusicUi.rect(ctx, modalX + 24f, modalY + 162f, modalW - 48f, 40f, 14f, MonetPalette.surface)
                    MusicUi.rect(ctx, modalX + 24f, modalY + 210f,
                        if (loginMethod == LoginMethod.PHONE) modalW - 170f else modalW - 48f, 40f, 14f, MonetPalette.surface)
                } else MusicUi.rect(ctx, modalX + 24f, modalY + 170f, modalW - 48f, 48f, 14f, MonetPalette.surface)
                if (loginMethod == LoginMethod.PHONE) button(ctx, "dialog:send", "获取验证码", modalX + modalW - 138f,
                    modalY + 214f, 114f, 32f)
                button(ctx, "dialog:authenticate", "认证", modalX + 24f, modalY + 270f, 110f, 36f, true)
                val explanation = when {
                    loginMethod == LoginMethod.TOKEN && loginSource == MusicSource.KUGOU -> "r5 缺少酷狗 Cookie 校验接口，不把粘贴凭证标成登录成功。"
                    loginMethod == LoginMethod.EMAIL && loginSource == MusicSource.KUGOU -> "酷狗不支持此邮箱接口，请切换扫码或短信。"
                    else -> "仅向所选平台提交；密码与验证码不写入配置。"
                }
                MusicUi.wrap(explanation, modalW - 180f, 9f).forEachIndexed { i, text ->
                    MusicUi.text(ctx, text, modalX + 150f, modalY + 274f + i * 14f, 9f, MonetPalette.muted)
                }
            }
        }
        if (MusicLogin.isLoggedIn(loginSource)) button(ctx, "dialog:logout", "退出此账号", modalX + 24f, modalY + 340f, 104f, 26f)
        MusicUi.text(ctx, MusicLogin.notice, modalX + 144f, modalY + 348f, 9f, MonetPalette.muted, maxWidth = modalW - 168f)
        MusicUi.text(ctx, "认证失败会保留平台错误，不伪造登录状态。", modalX + 24f, modalY + 378f, 8.5f, MonetPalette.muted)
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val modal = dialog != Dialog.NONE
        if (click.button() != InputConstants.MOUSE_BUTTON_LEFT) return if (modal) true else super.mouseClicked(click, doubled)
        if (fields.any { it.visible && it.active && it.isMouseOver(click.x, click.y) }) return super.mouseClicked(click, doubled)
        fields.forEach { it.setFocused(false) }
        val x = ((click.x - ox) / scale).toFloat(); val y = ((click.y - oy) / scale).toFloat()
        val id = resolve(x, y) ?: return modal
        dispatch(id, doubled)
        if (id == "seek" || id == "volume") { dragging = id; changeSlider(id, x) }
        return true
    }
    override fun mouseDragged(click: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (dialog != Dialog.NONE) {
            if (fields.any { it.visible && it.isFocused }) super.mouseDragged(click, dx, dy)
            return true
        }
        dragging?.let { changeSlider(it, ((click.x - ox) / scale).toFloat()); return true }
        return super.mouseDragged(click, dx, dy)
    }
    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        val consumed = dragging != null || dialog != Dialog.NONE
        if (dragging == "seek" && dialog == Dialog.NONE) seekTo(((click.x - ox) / scale).toFloat())
        dragging = null
        super.mouseReleased(click)
        return consumed
    }
    private fun changeSlider(id: String, x: Float) {
        if (id == "volume") module.setVolumePercent((((x - (w - 114f)) / 60f).coerceIn(0f, 1f) * 100f).roundToInt())
        // Seeking on release avoids restarting the decoder on every drag pixel.
    }
    private fun seekTo(x: Float) {
        val duration = engine.state.durationMs
        if (duration > 0) engine.seek((((x - 96f) / (w - 344f)).coerceIn(0f, 1f) * (duration - 1)).toLong())
    }
    override fun mouseScrolled(mx: Double, my: Double, horizontal: Double, vertical: Double): Boolean {
        if (dialog != Dialog.NONE) return true
        if ((my - oy) / scale in listTop.toDouble()..playerY.toDouble()) {
            val size = if (destination == Destination.PLAYLISTS && selectedPlaylist == null && !cloudTrackList)
                if (cloud) cloudLists.size else MusicUserLibrary.playlists(source).size else tracks.size
            firstRow = (firstRow - vertical.roundToInt()).coerceIn(0, max(0, size - visibleRows))
            return true
        }
        return super.mouseScrolled(mx, my, horizontal, vertical)
    }
    private fun dispatch(id: String, doubled: Boolean = false) {
        when {
            id.startsWith("source:") -> {
                source = MusicSource.entries[id.substringAfter(':').toInt()]
                destination = Destination.SEARCH; page = 0; invalidate(); tracks = emptyList(); cloud = false; selectedPlaylist = null; positionFields()
                if (source == MusicSource.LOCAL) searchNow()
            }
            id.startsWith("account:") -> openLogin(MusicSource.valueOf(id.substringAfter(':')))
            id.startsWith("destination:") -> {
                destination = Destination.entries[id.substringAfter(':').toInt()]
                selectedPlaylist = null; cloudTrackList = false; cloud = false; refresh()
            }
            id == "search" -> { page = 0; searchNow() }
            id.startsWith("recent:") -> {
                search.value = MusicUserLibrary.searches(source).getOrNull(id.substringAfter(':').toInt()).orEmpty()
                page = 0; searchNow()
            }
            id == "clearSearches" -> MusicUserLibrary.clearSearches(source)
            id == "import" -> show(Dialog.IMPORT)
            id == "newPlaylist" -> show(Dialog.PLAYLIST)
            id == "dialog:close" -> closeDialog()
            id == "dialog:confirm" -> confirmDialog()
            id.startsWith("dialog:platform:") -> openLogin(MusicSource.valueOf(id.substringAfterLast(':')))
            id.startsWith("dialog:method:") -> {
                loginMethod = LoginMethod.entries[id.substringAfterLast(':').toInt()]
                fields.forEach { it.setFocused(false) }; positionFields()
            }
            id == "dialog:qr" -> MusicLogin.refreshQr(loginSource)
            id == "dialog:send" -> MusicLogin.sendCode(loginSource, phone.value.trim())
            id == "dialog:authenticate" -> authenticate()
            id == "dialog:logout" -> MusicLogin.logout(loginSource)
            id == "device" -> { cloud = false; selectedPlaylist = null; cloudTrackList = false; refresh() }
            id == "cloud" -> { cloud = true; selectedPlaylist = null; cloudTrackList = false; refresh() }
            id == "refresh" || id == "playlistBack" -> { selectedPlaylist = null; cloudTrackList = false; refresh() }
            id == "deletePlaylist" -> { selectedPlaylist?.let { MusicUserLibrary.deletePlaylist(source, it) }; selectedPlaylist = null; refresh() }
            id == "addCurrent" -> engine.state.track?.let { track ->
                selectedPlaylist?.let { if (track.source == source) MusicUserLibrary.addToPlaylist(source, it, track) }
                refresh()
            }
            id.startsWith("playlist:") -> openPlaylist(id.substringAfter(':').toInt())
            id.startsWith("track:") || id.startsWith("play:") -> {
                selected = id.substringAfter(':').toInt()
                if (doubled || id.startsWith("play:")) tracks.getOrNull(selected)?.let { engine.play(it) }
            }
            id.startsWith("favorite:") -> tracks.getOrNull(id.substringAfter(':').toInt())?.let {
                MusicUserLibrary.toggleFavorite(it)
                message = "本机收藏已更新；不会冒充平台收藏"
                if (destination == Destination.FAVORITES && !cloud) refresh()
            }
            id.startsWith("queueList:") -> tracks.getOrNull(id.substringAfter(':').toInt())?.let { track ->
                if (cloud && MusicLogin.isLoggedIn(source)) cloudFavorite(track)
                else if (selectedPlaylist != null) {
                    MusicUserLibrary.removeFromPlaylist(source, selectedPlaylist!!, track); refresh()
                } else {
                    val playlist = MusicUserLibrary.playlists(track.source).firstOrNull()?.id
                        ?: MusicUserLibrary.createPlaylist(track.source, "稍后再听")
                    MusicUserLibrary.addToPlaylist(track.source, playlist, track)
                    message = "已加入本机歌单：稍后再听 / 首个歌单"
                }
            }
            id == "pause" -> engine.togglePause()
            id == "previous" -> engine.previous()
            id == "next" -> engine.next()
            id == "hud" -> module.toggleHud()
            id == "world" -> module.toggleWorld()
            id == "pagePrev" || id == "pageNext" -> {
                if (destination == Destination.SEARCH && source != MusicSource.LOCAL) {
                    page = max(0, page + if (id == "pageNext") 1 else -1); searchNow()
                } else firstRow = max(0, firstRow + if (id == "pageNext") visibleRows else -visibleRows)
            }
        }
    }
    private fun invalidate() { generation.incrementAndGet(); loading = false; firstRow = 0; selected = -1 }
    private fun refresh() {
        positionFields()
        invalidate(); tracks = emptyList(); cloudLists = emptyList()
        if (destination == Destination.SEARCH) return
        if (cloud) {
            val chosen = source; val dest = destination
            async({
                if (dest == Destination.HISTORY) Pair(MusicCloudLibrary.history(chosen), emptyList<MusicCloudLibrary.Playlist>())
                else if (dest == Destination.FAVORITES) Pair(MusicCloudLibrary.favorites(chosen), emptyList<MusicCloudLibrary.Playlist>())
                else Pair(emptyList<MusicTrack>(), MusicCloudLibrary.playlists(chosen))
            }) { (songs, lists) ->
                tracks = songs; cloudLists = lists
                if (dest == Destination.FAVORITES) {
                    message = "平台实际返回的收藏：${songs.size} 首"
                } else message = "平台实际返回：${songs.size + lists.size} 项"
            }
            return
        }
        tracks = when (destination) {
            Destination.HISTORY -> MusicUserLibrary.history(source)
            Destination.FAVORITES -> MusicUserLibrary.favorites(source)
            Destination.PLAYLISTS -> selectedPlaylist?.let { id -> MusicUserLibrary.playlists(source).firstOrNull { it.id == id }?.tracks }.orEmpty()
            else -> emptyList()
        }
        message = "本机 · ${source.label} · ${tracks.size} 首（云端同步需平台接口支持）"
    }
    private fun openPlaylist(index: Int) {
        if (cloud) {
            val playlist = cloudLists.getOrNull(index) ?: return
            val chosen = source
            async({ MusicCloudLibrary.tracks(chosen, playlist.id) }) {
                tracks = it; cloudTrackList = true; firstRow = 0; message = "平台歌单：${playlist.name} · ${it.size} 首"
            }
        } else {
            selectedPlaylist = MusicUserLibrary.playlists(source).getOrNull(index)?.id
            refresh()
        }
    }
    private fun searchNow() {
        val query = search.value.trim()
        if (query.isBlank() && source != MusicSource.LOCAL) { message = "请输入关键词"; return }
        destination = Destination.SEARCH
        val chosen = source; val chosenPage = page
        MusicUserLibrary.searched(chosen, query)
        async({ MusicProviders.search(chosen, query, engine.library, chosenPage) }) {
            tracks = it.tracks; firstRow = 0; selected = -1
            message = "第 ${chosenPage + 1} 页 · ${tracks.size} 首 · ${it.notice}"
        }
    }
    private fun <T> async(action: () -> T, complete: (T) -> Unit) {
        val revision = generation.incrementAndGet(); loading = true
        io.execute {
            val result = runCatching(action)
            mc.execute {
                if (disposed || generation.get() != revision) return@execute
                loading = false
                result.onSuccess(complete).onFailure { message = "失败：${it.message?.take(160) ?: "请求不可用"}" }
            }
        }
    }
    private fun cloudFavorite(track: MusicTrack) {
        val like = destination != Destination.FAVORITES
        async({ MusicCloudLibrary.favorite(track, like) }) {
            if (!like) refresh()
            message = if (like) "平台已确认收藏成功" else "平台已确认取消收藏"
        }
    }
    private fun openLogin(chosen: MusicSource) {
        loginSource = chosen; loginMethod = LoginMethod.QR; show(Dialog.LOGIN)
        val phase = MusicLogin.qrState(chosen).phase
        if (phase in setOf(QrPhase.IDLE, QrPhase.FAILED, QrPhase.EXPIRED)) MusicLogin.refreshQr(chosen)
    }
    private fun authenticate() {
        when (loginMethod) {
            LoginMethod.PHONE -> MusicLogin.loginPhone(loginSource, phone.value.trim(), code.value.trim())
            LoginMethod.EMAIL -> { MusicLogin.loginEmail(loginSource, email.value.trim(), password.value); password.value = "" }
            LoginMethod.TOKEN -> { MusicLogin.loginToken(loginSource, token.value.trim()); token.value = "" }
            else -> Unit
        }
    }
    private fun confirmDialog() {
        if (dialog == Dialog.PLAYLIST) {
            selectedPlaylist = MusicUserLibrary.createPlaylist(source, playlistName.value)
            destination = Destination.PLAYLISTS; cloud = false; closeDialog(); refresh()
        } else if (dialog == Dialog.IMPORT) {
            val raw = path.value.trim().removeSurrounding("\"")
            async({ engine.library.local(Path.of(raw)) }) {
                closeDialog(); source = MusicSource.LOCAL; destination = Destination.SEARCH
                tracks = listOf(it); selected = 0; engine.play(it); message = "已导入本地文件"
            }
        }
    }
    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key == InputConstants.KEY_ESCAPE) {
            if (dialog != Dialog.NONE) closeDialog() else onClose()
            return true
        }
        val focused = fields.firstOrNull { it.visible && it.isFocused }
        if (focused?.composing == true) return super.keyPressed(event)
        if (event.key == InputConstants.KEY_RETURN && focused != null) {
            when (dialog) { Dialog.LOGIN -> authenticate(); Dialog.IMPORT, Dialog.PLAYLIST -> confirmDialog(); else -> { page = 0; searchNow() } }
            return true
        }
        if (focused != null) return super.keyPressed(event)
        if (dialog != Dialog.NONE) { super.keyPressed(event); return true }
        when (event.key) {
            InputConstants.KEY_SPACE -> { engine.togglePause(); return true }
            InputConstants.KEY_RETURN -> { tracks.getOrNull(selected)?.let { engine.play(it) }; return true }
            InputConstants.KEY_DOWN -> { selected = (selected + 1).coerceIn(0, max(0, tracks.lastIndex)); firstRow = max(firstRow, selected - visibleRows + 1); return true }
            InputConstants.KEY_UP -> { selected = (selected - 1).coerceAtLeast(0); firstRow = min(firstRow, selected); return true }
        }
        return super.keyPressed(event)
    }
    override fun charTyped(event: CharacterEvent): Boolean {
        val result = super.charTyped(event); return result || dialog != Dialog.NONE
    }
    override fun preeditUpdated(event: PreeditEvent?): Boolean {
        val result = super.preeditUpdated(event); return result || dialog != Dialog.NONE
    }
    override fun onClose() { invalidate(); super.onClose() }
    override fun removed() {
        disposed = true; generation.incrementAndGet(); io.shutdownNow()
        password.value = ""; token.value = ""
        MusicQr.clear()
    }
    override fun shouldCloseOnEsc() = true
}
