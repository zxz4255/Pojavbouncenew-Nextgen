/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.render.cs2mvp

import java.util.UUID
import kotlin.math.abs

private var checks = 0
private fun verify(value: Boolean) { checks++; check(value) { "Assertion $checks" } }
fun main() {
    val a = MvpLife(UUID(1, 1), 10)
    val b = MvpLife(UUID(2, 2), 11)
    val respawn = MvpLife(a.uuid, 12)
    val tracker = Cs2MvpKillTracker()
    verify(!tracker.death(a, 100, 2000, true)) // no evidence, no phantom MVP
    tracker.reset()
    tracker.attack(a, 100)
    verify(!tracker.death(a, 101, 2000, false)) // a swing is not source-confirmed damage
    tracker.reset()
    tracker.attack(a, 100)
    verify(tracker.death(a, 200, 2000, true)) // explicit legacy fallback
    verify(!tracker.death(a, 201, 2000, true)) // health and status duplicates
    tracker.attack(a, 202); tracker.damage(a, true, 203)
    verify(!tracker.death(a, 204, 2000, true)) // consumed life cannot replay
    tracker.damage(respawn, true, 205)
    verify(tracker.death(respawn, 206, 2000, false)) // new entity life, same player UUID
    tracker.reset()
    tracker.damage(a, true, 100)
    tracker.damage(a, false, 101)
    tracker.attack(a, 102)
    verify(!tracker.death(a, 103, 2000, true)) // known other source beats recent-hit heuristic
    tracker.reset()
    tracker.attack(a, 100); tracker.attack(b, 101)
    verify(tracker.death(a, 200, 2000, true))
    verify(tracker.death(b, 201, 2000, true)) // not just a single last-attacked slot
    tracker.reset()
    tracker.damage(a, true, 100); tracker.damage(a, false, 101); tracker.damage(a, true, 102)
    verify(tracker.death(a, 103, 2000, false))
    tracker.reset()
    tracker.damage(a, true, 100); tracker.prune(10_101)
    verify(tracker.size == 0)
    verify(!tracker.death(a, 10_102, 2000, false))
    for (age in 0L..5000L) {
        for (legacy in listOf(false, true)) {
            tracker.reset(); tracker.attack(a, 100)
            verify(tracker.death(a, 100 + age, 2000, legacy) == (legacy && age <= 2000))
            tracker.reset(); tracker.damage(a, true, 100)
            verify(tracker.death(a, 100 + age, 2000, legacy) == (age <= 2000))
            tracker.reset(); tracker.damage(a, false, 100)
            verify(!tracker.death(a, 100 + age, 2000, legacy))
        }
    }
    tracker.reset()
    for (i in 0..1000) {
        tracker.attack(MvpLife(UUID(3, i.toLong()), i), i.toLong())
        verify(tracker.size <= 128)
    }
    tracker.reset(); verify(tracker.size == 0)
    tracker.attack(a, 100); tracker.prune(99); verify(tracker.size == 0)
    val line = Cs2MvpTimeline()
    verify(line.sample(0, 4000) == null)
    line.show("Player", 100)
    verify(line.sample(100, 4000)!!.unfold == 0f)
    verify(abs(line.sample(225, 4000)!!.unfold - .5f) < 0.00001f)
    verify(line.sample(350, 4000)!!.unfold == 1f)
    verify(line.sample(4100, 4000)!!.alpha == 1f)
    verify(line.sample(4400, 4000) == null)
    line.show("Old", 500); line.show("New", 600)
    verify(line.sample(601, 4000)!!.name == "New")
    verify(line.sample(601, 4000)!!.unfold < .01f) // new kill restarts unfold, no concurrent stale cards
    line.reset(); verify(line.sample(602, 4000) == null)
    line.show("WorldBefore", 100); verify(line.sample(99, 4000) == null)
    var last = 0f
    for (i in 0..10_000) {
        val x = i / 10_000f
        val y = cs2Ease(x)
        verify(y in 0f..1f && y >= last)
        verify(abs(y + cs2Ease(1f - x) - 1f) < .00001f)
        last = y
    }
    verify(mvpEllipsis("hello", 5f) { it.length.toFloat() } == "hello")
    verify(mvpEllipsis("hello", 4f) { it.length.toFloat() } == "hel…")
    verify(mvpEllipsis("😀😀😀", 3f) { it.codePointCount(0, it.length).toFloat() } == "😀😀😀")
    verify(mvpEllipsis("😀😀😀", 2f) { it.codePointCount(0, it.length).toFloat() } == "😀…")
    verify(mvpEllipsis("a\nb\tc", 10f) { it.length.toFloat() } == "abc")
    verify(mvpEllipsis("😀".repeat(200), 1000f) { it.length.toFloat() }.codePointCount(0, 256) == 128)
    println("PASS: $checks assertions on actual production kill evidence/dedup/bounds/reset, timeline/CSS easing, Unicode ellipsis")
    println("Pure state-machine tests only: no server attribution guarantee or visual screenshot acceptance")
}
