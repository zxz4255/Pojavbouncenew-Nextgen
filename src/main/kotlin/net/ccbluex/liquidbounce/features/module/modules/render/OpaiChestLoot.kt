/* NeoRaven ChestLoot animation adapted for Opai Dynamic Island. GPL-3.0-or-later. */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Material
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Scheme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Theme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Anim
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Ease
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.UI
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.WavyProgressIndicator
import net.ccbluex.liquidbounce.features.module.modules.player.cheststealer.ModuleChestStealer
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.ItemStack
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Render-only observer: never clicks, moves, fabricates or changes items. */
object OpaiChestLoot {
    const val WIDTH = 256f
    const val HEIGHT = 152f
    private const val CELL = 24f
    private const val PITCH = 26f
    private const val GRID_Y = 36f
    private const val RIPPLE_MS = 460L

    private class Transfer(val pageSlot: Int, val stack: ItemStack, val time: Long)
    private class Session(val screen: AbstractContainerScreen<*>, val slotIds: List<Int>, candidates: List<Int>) {
        val total = candidates.size
        val initialCandidates = candidates.toSet()
        var remaining = candidates.toList()
        val previous = slotIds.associateWith { screen.menu.slots[it].item.copy() }.toMutableMap()
        val transfers = ArrayList<Transfer>()
        var transferCount = 0
        var complete = false
        var endedAt = 0L
        val title = screen.title.string
    }

    private var session: Session? = null
    private val expansion = Anim(0f)
    private val pageReveal = Anim(1f)
    private val progress = Anim(0f)
    private var page = 0
    private var processed = 0
    private var lastProgressAt = 0L
    private var lastFrame = 0L

    /** Called after LB has selected eligible slots. No changes to scheduling or item filtering. */
    fun onCandidates(screen: AbstractContainerScreen<*>, candidates: List<Int>) {
        if (!ModuleOpaiDynamicIsland.running || !ModuleChestStealer.running || mc.player == null || mc.level == null) return
        val now = now()
        var current = session
        if (current?.screen !== screen) {
            if (candidates.isEmpty()) return
            val inventory = mc.player?.inventory ?: return
            val ids = screen.menu.slots.filter { it.container !== inventory }.map { it.index }
            if (ids.isEmpty()) return
            val valid = candidates.filter { it in ids }.distinct()
            if (valid.isEmpty()) return
            current = Session(screen, ids, valid)
            session = current
            page = max(0, ids.indexOf(valid.first())) / 27
            pageReveal.set(1f)
            progress.set(0f)
            processed = 0
            lastProgressAt = 0L
        }
        observe(current, now)
        current.remaining = candidates.filter { it in current.slotIds }.distinct()
        val newProcessed = (current.total - current.remaining.count { it in current.initialCandidates }).coerceIn(0, current.total)
        if (newProcessed != processed) {
            val duration = if (lastProgressAt == 0L) 300f else ((now - lastProgressAt) * .9f).coerceIn(180f, 600f)
            progress.to(newProcessed.toFloat() / max(1, current.total), duration, Ease.STANDARD)
            processed = newProcessed
            lastProgressAt = now
        }
        if (candidates.isEmpty() && !current.complete) {
            current.complete = true
            current.endedAt = now
        }
    }

    private fun observe(s: Session, now: Long) {
        for ((displayIndex, slotId) in s.slotIds.withIndex()) {
            if (slotId !in s.screen.menu.slots.indices) continue
            val before = s.previous[slotId] ?: ItemStack.EMPTY
            val after = s.screen.menu.slots[slotId].item
            // Observe actual container changes. A partial removal shows only the removed amount.
            val afterCount = if (!after.isEmpty && ItemStack.isSameItemSameComponents(before, after)) after.count else 0
            val removed = if (before.isEmpty) 0 else (before.count - afterCount).coerceAtLeast(0)
            if (removed > 0 && slotId in s.initialCandidates) {
                s.transfers.add(Transfer(displayIndex, before.copyWithCount(removed), now))
                s.transferCount++
            }
            s.previous[slotId] = after.copy()
        }
        // Animated transfers are transient; keep session memory bounded during long inventories.
        s.transfers.removeAll { now - it.time >= RIPPLE_MS }
    }

    /** Only hide the exact container being processed, and only while the island can render. */
    fun isReplacing(screen: Screen?): Boolean {
        val s = session ?: return false
        return screen === s.screen && mc.gui.screen() === screen && ModuleOpaiDynamicIsland.running &&
            ModuleChestStealer.running && mc.player != null && mc.level != null && !mc.gui.hud.isHidden &&
            mc.gui.overlay() == null
    }

    /** True when LB should close a finished replaced screen, like the reference. */
    fun closeWhenFinished(screen: AbstractContainerScreen<*>): Boolean = isReplacing(screen)

    /** Update once per overlay frame. Retain the completed panel long enough for the final ripple. */
    internal fun update(reducedMotion: Boolean): Float {
        val now = now()
        val delta = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(0L, 100L).toFloat()
        lastFrame = now
        val s = session
        val active = s != null && isReplacing(s.screen)
        if (active && s != null) observe(s, now)
        val lingering = s != null && s.complete && (now - s.endedAt < RIPPLE_MS || !progress.isDone()) &&
            ModuleChestStealer.running
        val visible = active || lingering
        if (reducedMotion) expansion.set(if (visible) 1f else 0f)
        else {
            expansion.to(if (visible) 1f else 0f, if (visible) 420f else 280f,
                if (visible) Ease.EMPHASIZED else Ease.STANDARD)
            expansion.update(delta)
        }
        if (s != null && (active || lingering)) {
            val last = s.transfers.lastOrNull()
            val targetId = s.remaining.firstOrNull()
            val target = if (last != null && now - last.time < RIPPLE_MS) last.pageSlot / 27
                else max(0, s.slotIds.indexOf(targetId)) / 27
            if (target != page) {
                page = target
                pageReveal.set(0f)
                pageReveal.to(1f, 250f, Ease.EMPHASIZED_DECELERATE)
            }
        }
        pageReveal.update(delta)
        progress.update(delta)
        val t = expansion.value().coerceIn(0f, 1f)
        if (!visible && t <= .001f) session = null
        return t
    }

    internal fun reset() {
        session = null
        expansion.set(0f); pageReveal.set(1f); progress.set(0f)
        page = 0; processed = 0; lastProgressAt = 0L; lastFrame = 0L
    }

    /** Opai shell is drawn by its own painter; content follows ChestLoot's 256×152 design. */
    internal fun draw(ctx: GuiGraphicsExtractor, left: Float, top: Float, scale: Float, shellW: Float, shellH: Float, t: Float) {
        val s = session ?: return
        if (t <= .01f || scale <= 0f || shellW <= 12f * scale || shellH <= 12f * scale) return
        val scheme = Theme.scheme()
        val ui = UI(ctx)
        val alpha = ((t - .15f) / .65f).coerceIn(0f, 1f)
        if (alpha <= 0f) return
        ui.begin()
        try {
            ui.scissorPush(left + 6f * scale, top + 6f * scale, shellW - 12f * scale, shellH - 12f * scale)
            // Raw vanilla item/text draws bypass UI's own visibility guard.
            if (!ui.clipVisible()) return
            ui.alphaPush(alpha)
            ui.translate(left + (shellW - WIDTH * scale) * .5f, top - (1f - t) * 8f * scale)
            ui.scale(scale, 0f, 0f)
            val contentX = left + (shellW - WIDTH * scale) * .5f
            val contentY = top - (1f - t) * 8f * scale
            drawContent(ui, ctx, scheme, s, contentX, contentY, scale, t)
            ui.alphaPop()
            ui.scissorPop()
        } finally { ui.end() }
    }

    private fun drawContent(ui: UI, ctx: GuiGraphicsExtractor, scheme: Scheme, s: Session,
                            ox: Float, oy: Float, scale: Float, expansion: Float) {
        val now = now()
        val pages = max(1, (s.slotIds.size + 26) / 27)
        ui.icon("\ue8b1", 20f, 19f, 16f, scheme.primary)
        // Counts are actual observed transfers, not speculative queued clicks.
        val count = "${s.transferCount} / ${s.total}"
        val badgeWidth = max(44f, IslandFonts.measure(count, 10f) + 18f)
        val title = truncate(s.title, WIDTH - badgeWidth - 59f, 11f)
        text(ctx, ui, title, ox, oy, scale, 35f, 19f, 11f, scheme.onSurface)
        ui.rounded(WIDTH - badgeWidth - 12f, 10f, badgeWidth, 18f, 9f, scheme.primaryContainer)
        text(ctx, ui, count, ox, oy, scale, WIDTH - 12f - badgeWidth * .5f, 19f, 10f, scheme.onPrimaryContainer, true)
        val reveal = pageReveal.value()
        for (i in 0 until 27) {
            val slot = page * 27 + i
            val x = 12f + (i % 9) * PITCH
            val entry = ((expansion - (i / 9) * .035f) / .72f).coerceIn(0f, 1f)
            val y = GRID_Y + (i / 9) * PITCH + (1f - reveal) * 7f
            val next = !s.complete && slot < s.slotIds.size && s.slotIds[slot] == s.remaining.firstOrNull()
            ui.alphaPush(entry * reveal)
            val surface = Material.mix(scheme.surfaceContainerHigh, scheme.onSurface, .045f)
            ui.rounded(x, y, CELL, CELL, if (next) 9f else 6f,
                Material.withAlpha(if (next) scheme.primaryContainer else surface, if (next) .85f else .38f))
            if (next) ui.outline(x, y, CELL, CELL, 9f, 1f, Material.withAlpha(scheme.primary, .55f))
            val stack = s.slotIds.getOrNull(slot)?.let { id -> s.previous[id] }
            val pop = Ease.EMPHASIZED_DECELERATE.get(entry) * reveal
            if (stack != null && !stack.isEmpty && pop > .05f) {
                item(ctx, ui, stack, ox, oy, scale, x + CELL * .5f, y + CELL * .5f, pop)
                if (stack.count > 1) text(ctx, ui, stack.count.toString(), ox, oy, scale, x + CELL * .5f + 9f,
                    y + CELL * .5f + 7f, 9f, scheme.onSurface, right = true)
            }
            ui.alphaPop()
        }
        for (transfer in s.transfers) {
            val age = now - transfer.time
            val t = age.toFloat() / RIPPLE_MS
            if (transfer.pageSlot / 27 != page || t !in 0f..<1f) continue
            val cell = transfer.pageSlot % 27
            val x = 24f + (cell % 9) * PITCH
            val y = GRID_Y + 12f + (cell / 9) * PITCH + (1f - reveal) * 7f
            val itemFade = (1f - age / 180f).coerceIn(0f, 1f)
            val id = s.slotIds.getOrNull(transfer.pageSlot)
            if (itemFade > .01f && (id == null || s.previous[id]?.isEmpty != false)) {
                ui.alphaPush(itemFade * reveal)
                item(ctx, ui, transfer.stack, ox, oy, scale, x, y, 1f)
                ui.alphaPop()
            }
            val radius = 3f + (CELL * .5f - 4f) * Ease.DECELERATE.get(t)
            val opacity = (1f - t) * (1f - t) * reveal
            ui.circle(x, y, radius, Material.withAlpha(scheme.primary, opacity * .12f))
            ui.outline(x - radius, y - radius, radius * 2f, radius * 2f, radius,
                1.4f - t * .6f, Material.withAlpha(scheme.primary, opacity * .95f))
        }
        text(ctx, ui, if (s.complete) "Finished" else "Taking items", ox, oy, scale, 14f, 128f, 10f, scheme.onSurfaceVariant)
        val hint = if (pages > 1) "${page + 1} / $pages" else "ChestStealer"
        text(ctx, ui, hint, ox, oy, scale, WIDTH - 14f, 128f, 9f, scheme.onSurfaceVariant, right = true)
        WavyProgressIndicator.drawLinear(ui, 14f, 138f, WIDTH - 28f, 5f, progress.value(),
            WavyProgressIndicator.Style(16f, .65f, 22f, scheme.primary, scheme.surfaceContainerHighest))
    }

    private fun text(ctx: GuiGraphicsExtractor, ui: UI, text: String, ox: Float, oy: Float, scale: Float,
                     x: Float, y: Float, size: Float, color: Int, center: Boolean = false, right: Boolean = false) {
        val width = IslandFonts.measure(text, size)
        val offset = if (center) width * .5f else if (right) width else 0f
        IslandFonts.draw(ctx, text, ox + (x - offset) * scale, oy + y * scale, size * scale,
            Material.scaleAlpha(color, ui.alpha()))
    }

    private fun item(ctx: GuiGraphicsExtractor, ui: UI, stack: ItemStack, ox: Float, oy: Float,
                     scale: Float, x: Float, y: Float, pop: Float) {
        // Like NeoRaven, shrink instead of relying on item-render alpha, which vanilla resets.
        val size = pop * sqrt(ui.alpha().coerceIn(0f, 1f))
        if (size <= .02f) return
        ctx.pose().withPush {
            translate(ox + x * scale, oy + y * scale)
            scale(scale * size, scale * size)
            ctx.item(stack, -8, -8)
        }
    }

    private fun truncate(text: String, maxWidth: Float, size: Float): String {
        if (IslandFonts.measure(text, size) <= maxWidth) return text
        var end = text.length
        while (end > 0 && IslandFonts.measure(text.take(end) + "…", size) > maxWidth) {
            end = text.offsetByCodePoints(end, -1)
        }
        return text.take(end) + "…"
    }
    private fun now() = System.nanoTime() / 1_000_000L
}
