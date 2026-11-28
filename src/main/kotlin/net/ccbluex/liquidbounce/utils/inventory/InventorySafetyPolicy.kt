/* GPL-3.0-or-later. 076 inventory safety: pure, deterministic, no game mutations. */
package net.ccbluex.liquidbounce.utils.inventory

/** Exact component-equivalence IDs are assigned by the runtime adapter, never item/damage hashes. */
object InventorySafetyPolicy {
    data class Stack(val key: Int, val count: Int, val max: Int = 64) {
        init { require(key >= 0 && count > 0 && max > 0 && count <= max) }
    }
    data class Image(val slots: List<Stack?>, val cursor: Stack? = null) {
        init { require(slots.size == 46) }
        fun contents(): Map<Int, Int> = buildMap {
            (slots + cursor).filterNotNull().forEach { put(it.key, getOrDefault(it.key, 0) + it.count) }
        }
    }
    enum class Mode { SWAP, PICKUP, QUICK_MOVE, THROW }
    data class Click(val slot: Int, val button: Int, val mode: Mode)

    /** Predict only the narrow native operations used here. Unsupported/ambiguous actions fail closed. */
    fun predict(before: Image, click: Click): Image? {
        val s = before.slots.toMutableList()
        val id = click.slot
        if (id !in 5..44) return null // no output, crafting, offhand or outside-window actions
        val source = s[id]
        var cursor = before.cursor
        when (click.mode) {
            Mode.SWAP -> {
                if (cursor != null || id !in 5..44 || click.button !in 0..8) return null
                val target = 36 + click.button
                if (id == target) return null
                s[id] = s[target]; s[target] = source
            }
            Mode.QUICK_MOVE -> {
                // Armor removal only, into an explicitly known empty storage slot.
                // Armor equip is sent by PICKUP staging instead, avoiding unknown quick-move destinations.
                if (cursor != null || id !in 5..8 || click.button != 0 || source == null) return null
                val target = (9..44).firstOrNull { s[it] == null } ?: return null
                s[target] = source; s[id] = null
            }
            Mode.PICKUP -> {
                if (click.button != 0) return null
                when {
                    cursor == null -> { if (source == null) return null; cursor = source; s[id] = null }
                    source == null -> { s[id] = cursor; cursor = null }
                    source.key == cursor.key && source.count < source.max -> {
                        val move = minOf(cursor.count, source.max - source.count)
                        s[id] = source.copy(count = source.count + move)
                        cursor = (cursor.count - move).takeIf { it > 0 }?.let { cursor.copy(count = it) }
                    }
                    else -> { s[id] = cursor; cursor = source }
                }
            }
            Mode.THROW -> {
                if (cursor != null || id !in 9..44 || source == null || click.button != 1) return null
                s[id] = null
            }
        }
        val after = Image(s, cursor)
        if (after == before) return null
        if (click.mode != Mode.THROW && before.contents() != after.contents()) return null
        return after
    }

    /** Armor exchange with an exactly restored scratch slot; omit ONLY a proven no-op tail. */
    fun armorSwapPlan(before: Image, source: Int, armor: Int, scratch: Int): List<Click>? {
        if (source !in 9..44 || armor !in 5..8 || scratch !in 0..8 || before.cursor != null || before.slots[source] == null) return null
        val plan = if (source >= 36) listOf(Click(armor, source - 36, Mode.SWAP)) else
            listOf(Click(source, scratch, Mode.SWAP), Click(armor, scratch, Mode.SWAP), Click(source, scratch, Mode.SWAP))
        var image = before
        val result = ArrayList<Click>()
        for ((index, click) in plan.withIndex()) {
            val next = predict(image, click)
            if (next == null) {
                // Empty armor + empty scratch made the historical third SWAP empty↔empty.
                // Equal component/count stacks can also make the restore redundant. Never skip a real rejection.
                val redundantTail = index == 2 && click.mode == Mode.SWAP &&
                    image.slots[click.slot] == image.slots[36 + click.button]
                if (!redundantTail) return null
            } else { result.add(click); image = next }
        }
        if (image.cursor != null || image.contents() != before.contents() || image.slots[armor] != before.slots[source] ||
            image.slots[source] != before.slots[armor] ||
            (source < 36 && image.slots[36 + scratch] != before.slots[36 + scratch])) return null
        return result.takeIf { it.isNotEmpty() }
    }

    fun discardAllowed(explicit: Boolean, modified: Boolean, enchanted: Boolean, damageable: Boolean,
        container: Boolean, pinned: Boolean): Boolean = explicit && !modified && !enchanted && !damageable && !container && !pinned

    /** Stable global target allocation; duplicate targets/categories cannot steal an earlier winner. */
    data class Candidate(val slot: Int, val score: Double)
    data class Target(val hotbar: Int, val candidates: List<Candidate>)
    fun nextSwap(targets: List<Target>, ignored: Set<Int> = emptySet()): Click? {
        val reserved = ignored.toMutableSet()
        val destinations = HashSet<Int>()
        for (t in targets) {
            if (t.hotbar !in 0..8) continue
            val dest = 36 + t.hotbar
            if (!destinations.add(dest) || dest in ignored) continue // first category owns a duplicate target
            val best = t.candidates.asSequence().filter { it.slot in 9..44 && it.slot !in reserved && it.score.isFinite() }
                .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { if (it.slot == dest) 0 else 1 }.thenBy { it.slot })
                .firstOrNull()
            if (best == null) continue
            if (best.slot != dest) return Click(best.slot, t.hotbar, Mode.SWAP)
            reserved.add(dest)
        }
        return null
    }
}

/**
 * Local prediction/settlement gate, NOT a server acknowledgement emulator.
 * Any rollback, divergence, no-op or repeated before-image latches until the session is reopened.
 */
class InventorySafetyGate {
    var fault: String? = null
        private set
    private var expected: InventorySafetyPolicy.Image? = null
    private var settleAt = 0L
    private var sentTick = Int.MIN_VALUE
    private var checks = 0
    private var lastCheckTick = Int.MIN_VALUE
    private val visited = HashSet<InventorySafetyPolicy.Image>()
    fun fail(reason: String) { if (fault == null) fault = reason }
    fun reset() { fault = null; expected = null; settleAt = 0; sentTick = Int.MIN_VALUE; checks = 0; visited.clear() }
    fun ready(image: InventorySafetyPolicy.Image, now: Long, tick: Int): Boolean {
        if (fault != null) return false
        val predicted = expected ?: return true
        if (predicted != image) { fail("Inventory changed or server corrected a pending click; reopen inventory"); return false }
        if (tick != sentTick && tick != lastCheckTick) { checks++; lastCheckTick = tick }
        if (now < settleAt || tick == sentTick || checks < 2) return false
        // Retain the last prediction even after settling: late corrections/manual edits also stop this pass.
        return true
    }
    fun begin(before: InventorySafetyPolicy.Image, after: InventorySafetyPolicy.Image, now: Long, tick: Int, delay: Long): Boolean {
        if (!ready(before, now, tick)) return false
        if (before == after || !visited.add(before) || visited.size > 128) {
            fail("Repeated/no-progress inventory state; reopen inventory"); return false
        }
        expected = after; sentTick = tick; checks = 0; lastCheckTick = tick; settleAt = now + maxOf(250L, delay)
        return true
    }
    fun verify(after: InventorySafetyPolicy.Image) {
        if (after != expected) fail("Native click did not match prediction; automation paused, no retry")
    }
}
