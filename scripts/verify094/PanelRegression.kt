package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Anim
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Ease
import kotlin.math.abs

fun main() {
    var checks = 0
    fun expect(value: Boolean) { checks++; check(value) { "assertion $checks" } }
    val l = MusicPanelLayout
    val parts = listOf(l.sidebar, l.content, l.player)
    for ((i, a) in parts.withIndex()) {
        expect(a.x >= 0 && a.y >= 0 && a.x + a.w <= l.WIDTH && a.y + a.h <= l.HEIGHT)
        for (b in parts.drop(i + 1)) expect(!a.intersects(b))
    }
    val controls = listOf(
        l.progressHit, l.volumeHit,
        MusicPanelLayout.Rect(36f,512f,52f,52f), MusicPanelLayout.Rect(294f,511f,32f,32f),
        MusicPanelLayout.Rect(406f,509f,36f,36f), MusicPanelLayout.Rect(458f,509f,44f,36f), MusicPanelLayout.Rect(518f,509f,36f,36f),
        MusicPanelLayout.Rect(634f,511f,32f,32f),MusicPanelLayout.Rect(670f,511f,32f,32f),MusicPanelLayout.Rect(706f,519f,32f,32f),
        MusicPanelLayout.Rect(868f,519f,32f,32f),MusicPanelLayout.Rect(900f,519f,32f,32f))
    for ((i,a) in controls.withIndex()) {
        expect(l.player.contains(a.x,a.y) && l.player.contains(a.x+a.w-.01f,a.y+a.h-.01f))
        for (b in controls.drop(i+1)) expect(!a.intersects(b))
    }
    for (i in 0..3) {
        val a=l.nav(i);expect(l.sidebar.contains(a.x,a.y));expect(l.sidebar.contains(a.x+a.w-.01f,a.y+a.h-.01f))
        for (j in i+1..3) expect(!a.intersects(l.nav(j)))
    }
    // Production Hit uses half-open edges, so adjacent buttons have exactly one owner.
    for (rect in controls) {
        val h=MusicScreen.Hit(rect.x,rect.y,rect.w,rect.h,null)
        for (dx in -2..25) for (dy in -2..25) {
            val x=rect.x+rect.w*dx/24f;val y=rect.y+rect.h*dy/24f
            expect(h.contains(x,y)==rect.contains(x,y))
        }
    }
    for (s in listOf(.1f,.25f,.5f,.92f,1f,1.5f)) for (ox in listOf(0f,37f,410f)) {
        for (r in controls) {
            val x=r.x+r.w*.5f;val y=r.y+r.h*.5f
            val gx=ox+x*s;val gy=ox+y*s
            expect(r.contains((gx-ox)/s,(gy-ox)/s))
        }
    }
    for (r in listOf(l.progressHit,l.volumeHit)) for (i in -200..1200) {
        val x=r.x+r.w*i/1000f
        val v=l.fraction(x,r.x,r.w)
        expect(v in 0f..1f);expect(abs(v-(i/1000f).coerceIn(0f,1f))<.00001f)
    }
    for (x in listOf(Float.NaN,Float.NEGATIVE_INFINITY,Float.POSITIVE_INFINITY)) expect(l.fraction(x,0f,100f)==0f)
    expect(l.fraction(50f,0f,0f)==0f);expect(l.fraction(50f,0f,-1f)==0f)
    // Reverse-order modal dispatch stops at the shield, not at any underlying playback control.
    val hits=mutableListOf(MusicScreen.Hit(0f,0f,960f,600f) {error("click-through")},MusicScreen.Hit(0f,0f,960f,600f) {})
    for (x in 0 until 960 step 16) for(y in 0 until 600 step 16) expect(hits.asReversed().first{it.contains(x.toFloat(),y.toFloat())}===hits[1])
    for (duration in listOf(100f,140f,170f,180f,200f,240f,260f)) {
        val a=Anim(0f);a.to(1f,duration,Ease.STANDARD)
        var prev=0f
        repeat(100){a.to(1f,duration,Ease.STANDARD);a.update(duration/100f);val v=a.value();expect(v>=prev-.00001f && v in 0f..1f);prev=v}
        a.update(1f);expect(a.isDone());expect(abs(a.value()-1f)<.0001f)
        a.to(0f,duration,Ease.STANDARD);a.update(duration*.4f);val mid=a.value();a.to(1f,duration,Ease.STANDARD);expect(abs(a.value()-mid)<.00001f)
        a.update(duration);expect(a.isDone() && abs(a.value()-1f)<.0001f)
    }
    val text=SearchTextBuffer(48)
    text.replace("音乐");text.updateComposition("你好😀",3);expect(text.text=="音乐" && text.compositionCaret==2)
    expect(text.appendCodePoint(0x1f600));expect(text.backspace());expect(text.text=="音乐")
    text.replace("a".repeat(100));expect(text.text.length==48);expect(!text.appendCodePoint('b'.code))
    text.replace("甲\n乙");expect(text.text=="甲 乙")
    println("PASS: $checks assertions on production layout/Hit/Anim/SearchTextBuffer; geometry, scaling, clamped seek/volume, shield model, reversible transitions, codepoints/IME. Not live input/rendering.")
}
