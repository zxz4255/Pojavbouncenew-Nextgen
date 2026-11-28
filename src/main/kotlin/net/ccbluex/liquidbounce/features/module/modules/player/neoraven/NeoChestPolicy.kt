/* GPL-3.0-only. Derived from Matr1ze/NeoRaven b483ffc; see licenses/neoraven/CHESTS.md. */
package net.ccbluex.liquidbounce.features.module.modules.player.neoraven

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Data-only rendition of upstream slotsToSteal/isTrash; no shared LB stealer policy. */
object NeoChestPolicy {
    enum class Kind { ARMOR, BLOCK, BOW, ARROW, FOOD, POTION, PEARL, EGG, SNOWBALL, ROD, SWORD, PICKAXE, AXE, SHOVEL, OTHER }
    data class Item(
        val kind: Kind,
        val value: Double = 0.0,
        val armorType: String = "",
        val goldenApple: Boolean = false,
        val placeable: Boolean = false,
    )
    data class Settings(
        val instant: Boolean = false, val ignoreTrash: Boolean = true,
        val armor: Boolean = true, val blocks: Boolean = true, val bow: Boolean = false,
        val food: Boolean = true, val potions: Boolean = false, val projectiles: Boolean = true,
        val sword: Boolean = true, val tools: Boolean = false,
    )

    fun wanted(item: Item, s: Settings) = when (item.kind) {
        Kind.ARMOR -> s.armor
        Kind.BLOCK -> s.blocks && item.placeable
        Kind.BOW, Kind.ARROW -> s.bow
        Kind.FOOD -> s.food
        Kind.POTION -> s.potions
        Kind.PEARL, Kind.EGG, Kind.SNOWBALL, Kind.ROD -> s.projectiles
        Kind.SWORD -> s.sword
        Kind.PICKAXE, Kind.AXE, Kind.SHOVEL -> s.tools
        Kind.OTHER -> false
    }

    private fun best(like: Item, inventory: List<Item?>): Double = inventory.asSequence().filterNotNull()
        .filter { it.kind == like.kind && (like.kind != Kind.ARMOR || it.armorType == like.armorType) }
        .maxOfOrNull { it.value } ?: -1.0

    fun trash(item: Item, chest: List<Item?>, inventory: List<Item?>): Boolean = when (item.kind) {
        Kind.ARMOR, Kind.SWORD, Kind.BOW, Kind.PICKAXE, Kind.AXE, Kind.SHOVEL ->
            item.value <= best(item, inventory) || item.value < best(item, chest)
        Kind.FOOD -> !item.goldenApple && inventory.any { it?.goldenApple == true }
        Kind.ROD -> inventory.any { it?.kind == Kind.ROD }
        else -> false
    }

    fun slots(chest: List<Item?>, inventory: List<Item?>, s: Settings): List<Int> = chest.indices.filter { i ->
        val item = chest[i]
        item != null && (s.instant || (!s.ignoreTrash || !trash(item, chest, inventory)) &&
            (wanted(item, s) || !s.ignoreTrash))
    }

    fun jitter(first: Int, second: Int, unitRandom: Double): Long {
        val low = min(first, second); val high = max(first, second)
        return max(0L, (if (low >= high) low.toDouble() else low + (high - low) * unitRandom).roundToLong())
    }
    fun blockedTitle(title: String): Boolean = listOf("ender", "team", "island", "末影", "队伍")
        .any { it in title.lowercase(java.util.Locale.ROOT) }

    /** 1.8 generic-damage protection modifier, not the modern armor-toughness formula. */
    fun armorScore(base: Double, protection: Int): Double {
        val epf = floor((6.0 + protection * protection) / 3.0 * .75).toInt().takeIf { protection > 0 } ?: 0
        val n = epf.coerceIn(0,25)
        return base + (3 * n + (n and 1)) / 4.0
    }
    fun swordScore(base: Double, sharpness: Int, fireAspect: Int): Double =
        base + sharpness * 1.25 + if (fireAspect > 0) fireAspect * 4 - 1.0 else 0.0

    /** Upstream's two guarded states, projected out of Hypixel.detect() without locraw/chat traffic. */
    fun lobby(hypixel: Boolean, linesTopDown: List<String>): Boolean {
        if (!hypixel || linesTopDown.isEmpty()) return false
        val title = linesTopDown[0].replace(Regex("§."), "")
        // Earlier upstream returns precede its lobby marker branch.
        if (title in listOf("REPLAY", "BED WARS PRACTICE", "THE HYPIXEL PIT")) return false
        if (linesTopDown.getOrNull(1)?.contains("  §8L") == true) return true
        return title == "SKYWARS" && linesTopDown.getOrNull(3)?.startsWith("Player") == true
    }
}

/** Original normal/Instant queue/deadline state machine. Calls are bounded and synchronous. */
class NeoLootCycle {
    class Session(val identity: Any, val total: Int, var nextSlot: Int) {
        var processed = 0
        var finished = false
        var completed = false
        var replacedScreen = false
        var endedAt = 0L
    }
    var session: Session? = null; private set
    var active: Any? = null; private set
    private var contents: Any? = null
    private var instantDone: Any? = null
    private val queue = ArrayDeque<Int>()
    private var stealing = false
    private var nextAt = 0L

    fun reset(now: Long, forgetHistory: Boolean = false) {
        session?.takeUnless { it.finished }?.let { it.finished = true; it.endedAt = now }
        queue.clear(); stealing = false; nextAt = 0; active = null; contents = null; instantDone = null
        if (forgetHistory) session = null
    }
    fun contents(identity: Any) { contents = identity }
    fun visual(identity: Any, candidates: () -> List<Int>, allowed: Boolean): Session? {
        if (!allowed) return null
        if (session?.identity !== identity) {
            val slots = candidates()
            if (slots.isEmpty()) return null
            session = Session(identity,slots.size,slots.first())
        }
        return session?.takeUnless { it.finished }
    }
    fun remaining(): List<Int> = queue.toList()

    fun tick(
        identity: Any, now: Long, instant: Boolean, allowed: Boolean, start: Int, autoClose: Boolean,
        candidates: () -> List<Int>, stillOpen: () -> Boolean, click: (Int) -> Unit,
        delay: () -> Long, close: () -> Unit, replacementEnabled: Boolean,
    ) {
        if (active !== identity) {
            queue.clear(); stealing = false; nextAt = 0; active = identity
            if (session != null && session?.identity !== identity) {
                session?.let { it.finished = true; it.endedAt = now }; session = null
            }
        }
        fun closeAfter(s: Session?) { if (stillOpen() && (autoClose || s?.replacedScreen == true && replacementEnabled)) close() }
        fun take(slot: Int) {
            click(slot)
            session?.let { it.processed++; it.nextSlot = queue.firstOrNull() ?: slot }
        }
        if (instant) {
            if (instantDone === identity || !allowed) return
            val slots = candidates()
            if (slots.isEmpty() && contents !== identity) return
            instantDone = identity; queue.clear(); queue.addAll(slots); stealing = false
            if (slots.isNotEmpty()) {
                session = Session(identity,slots.size,slots.first())
                while (queue.isNotEmpty() && stillOpen()) take(queue.removeFirst())
                session?.let { it.finished = true; it.completed = queue.isEmpty(); it.endedAt = now }
            }
            queue.clear(); closeAfter(session); return
        }
        instantDone = null
        if (!stealing) {
            if (!allowed) return
            val slots = candidates()
            if (slots.isEmpty()) { if (autoClose) close(); return }
            queue.clear(); queue.addAll(slots)
            if (session == null || session!!.finished) session = Session(identity,slots.size,slots.first())
            stealing = true; nextAt = now + start; return
        }
        if (now < nextAt) return
        if (queue.isEmpty()) {
            session?.let { it.finished = true; it.completed = true; it.endedAt = now }
            closeAfter(session); reset(now); return
        }
        take(queue.removeFirst()); nextAt = now + delay()
    }
}

/** Selection / server-confirmation state. Open history survives off/on in the same world. */
class NeoAuraCycle<P : Any> {
    val opened = HashSet<P>()
    var target: P? = null; private set
    var pending: P? = null; private set
    private var pendingAt = 0L
    private var selectedAt = 0L
    var closedAt = 0L; private set
    private var world: Any? = null
    private var owner: Any? = null
    private var lastContainer: Any? = null
    fun clearTarget() { target = null; selectedAt = 0 }
    fun clearPending() { clearTarget(); pending = null; pendingAt = 0; lastContainer = null }
    fun sync(world: Any?, owner: Any?, now: Long) {
        if (this.world !== world || this.owner !== owner) {
            this.world = world; this.owner = owner; opened.clear(); clearPending(); closedAt = now
        }
    }
    fun enable(now: Long) { clearPending(); closedAt = now }
    fun interacted(pos: P, now: Long) { pending = pos; pendingAt = now; clearTarget() }
    fun confirm(container: Any, mark: (P) -> Unit) {
        pending?.let(mark); pending = null; pendingAt = 0; clearTarget(); lastContainer = container
    }
    /** Returns false even on the timeout frame, matching the original early return. */
    fun ready(container: Any?, now: Long, mark: (P) -> Unit): Boolean {
        if (container != null) { confirm(container,mark); return false }
        if (lastContainer != null) { lastContainer = null; closedAt = now }
        if (pending != null) {
            if (now - pendingAt > 1200) { pending = null; pendingAt = 0 }
            clearTarget(); return false
        }
        return now - closedAt > 100
    }
    fun select(pos: P?, now: Long) {
        if (pos == null) { clearTarget(); return }
        if (pos != target) { target = pos; selectedAt = now }
    }
    fun selectedLongEnough(now: Long) = target != null && now - selectedAt >= 80
}
