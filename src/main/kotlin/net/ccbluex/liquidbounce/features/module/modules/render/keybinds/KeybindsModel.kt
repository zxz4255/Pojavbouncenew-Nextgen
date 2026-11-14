package net.ccbluex.liquidbounce.features.module.modules.render.keybinds

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Anim
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Ease
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Spring
import java.util.Locale

/** Input snapshot; no Minecraft state is read by this animation model. */
internal data class BoundModule(val id: Any, val name: String, val label: String, val enabled: Boolean, val down: Boolean)

/** NeoRaven Keybinds.Entry and updateEntries port; milliseconds and delayed row entrance are unchanged. */
internal class KeybindsModel {
    class Entry(val id: Any, val name: String, var label: String, enabled: Boolean, var delay: Float) {
        val state = Anim(if (enabled) 1f else 0f)
        val show = Anim(0f)
        val flash = Anim(0f)
        val pop = Spring(1f)
        val press = Spring(0f)
        var wanted = false
        var enabled = enabled
        private var lastEnabled = enabled
        val measured get() = wanted || show.value() > .002f

        fun toggle(next: Boolean) {
            enabled = next
            if (next != lastEnabled) {
                lastEnabled = next
                state.to(if (next) 1f else 0f, 300f, Ease.EMPHASIZED_DECELERATE)
                flash.set(1f)
                flash.to(0f, 520f, Ease.DECELERATE)
                pop.impulse(if (next) 2.6f else -1.8f)
            }
        }
        fun update(delta: Float) {
            if (delay > 0f) delay -= delta else show.update(delta)
            state.update(delta); flash.update(delta); pop.update(delta)
        }
    }

    private val cache = LinkedHashMap<Any, Entry>()
    var entries: List<Entry> = emptyList()
        private set
    val visible = Anim(0f)
    val cardWidth = Anim(132f)
    val cardHeight = Anim(92f)

    fun reset() {
        cache.clear(); entries = emptyList(); visible.set(0f); cardWidth.set(132f); cardHeight.set(92f)
    }
    fun update(wantedInput: List<BoundModule>, delta: Float) {
        val wanted = wantedInput.sortedBy { it.name.lowercase(Locale.ROOT) }
        cache.values.forEach { it.wanted = false }
        wanted.forEachIndexed { i, candidate ->
            val entry = cache.getOrPut(candidate.id) { Entry(candidate.id, candidate.name, candidate.label, candidate.enabled, i * 35f) }
            entry.wanted = true
            entry.label = candidate.label
            entry.show.to(1f, 240f, Ease.EMPHASIZED_DECELERATE)
            entry.toggle(candidate.enabled)
            entry.press.target(if (candidate.down && candidate.enabled) 1f else 0f)
            entry.press.update(delta)
            entry.update(delta)
        }
        val iterator = cache.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.wanted) {
                entry.show.to(0f, 180f, Ease.DECELERATE)
                entry.update(delta)
                if (entry.show.value() <= .002f && entry.show.isDone()) iterator.remove()
            }
        }
        entries = cache.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }
    fun animateVisibility(active: Boolean, delta: Float): Float {
        visible.to(if (active) 1f else 0f, if (active) 300f else 200f,
            if (active) Ease.EMPHASIZED else Ease.DECELERATE)
        visible.update(delta)
        return visible.value()
    }
}
