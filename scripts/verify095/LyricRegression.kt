package net.ccbluex.liquidbounce.features.module.modules.misc.music

import org.joml.Vector3f
import org.joml.Quaternionf
import kotlin.math.*
import kotlin.random.Random

fun main(){
 var checks=0
 fun ok(b:Boolean){checks++;check(b){"lyric assertion $checks"}}
 val measure:(String)->Float={it.codePointCount(0,it.length)*10f}
 val texts=listOf("你好世界，这是逐字歌词的自动换行。","A long sentence with several words and punctuation.","😀𠮷音乐🎵 abc 你好","甲\n乙\r\n丙"," " )
 for(t in texts) for(w in listOf(1f,10f,40f,100f,300f)) {
  val runs=LyricLayout.wrap(t,w,measure)
  for(r in runs){ok(r.text==t.substring(r.start,r.end));ok(r.text.isNotEmpty());ok(!Character.isLowSurrogate(r.text.first()));ok(!Character.isHighSurrogate(r.text.last()));ok(measure(r.text)<=w || r.text.codePointCount(0,r.text.length)==1)}
  if(t.isNotBlank())ok(runs.joinToString(""){it.text}==t.replace("\n","").replace("\r",""))
 }
 for(sw in listOf(32f,64f,320f,1920f))for(sh in listOf(32f,180f,1080f))
 for(w in listOf(8f,100f,540f))for(h in listOf(26f,130f,400f))for(size in listOf(.25f,1f,4f))for(x in listOf(0f,50f,100f))for(y in listOf(0f,50f,100f)) {
  val b=LyricLayout.fitBox(sw,sh,w,h,size,x,y)!!
  ok(b.x-w*b.scale/2>=7.999f && b.x+w*b.scale/2<=sw-7.999f)
  ok(b.y-h*b.scale/2>=7.999f && b.y+h*b.scale/2<=sh-7.999f)
 }
 ok(LyricLayout.fitBox(10f,10f,50f,50f,1f,50f,50f)==null)
 ok(LyricLayout.fitBox(Float.NaN,100f,50f,50f,1f,50f,50f)==null)
 val line=LyricLine(1000,3000,"你好世界",listOf(LyricWord(1000,2000,"你好"),LyricWord(2000,3000,"世界")))
 ok(LyricLayout.sungWidth(line,500,measure)==0f)
 ok(LyricLayout.sungWidth(line,1500,measure)==10f)
 ok(LyricLayout.sungWidth(line,2000,measure)==20f)
 ok(LyricLayout.sungWidth(line,2500,measure)==30f)
 ok(LyricLayout.sungWidth(line,4000,measure)==40f)
 var prev=0f
 for(t in 0L..4000L step 5){val v=LyricLayout.sungWidth(line,t,measure);ok(v>=prev && v in 0f..40f);prev=v;ok(LyricLayout.alpha(line,t) in 0f..1f)}
 ok(LyricLayout.alpha(line,999)==0f);ok(LyricLayout.alpha(line,3000)==0f)
 val lines=listOf(line,LyricLine(3500,5000,"next",emptyList()))
 for(t in 0L..5500L step 13)ok(LyricLayout.index(lines,t)==lines.indexOfLast{it.timeMs<=t})
 val fallback=LyricLine(1000,3000,"你好世界",emptyList());ok(abs(LyricLayout.sungWidth(fallback,2000,measure)-20f)<.001f)
 val scene=FrontLyricScene();val random=Random(95)
 var quadrants=mutableSetOf<Pair<Boolean,Boolean>>()
 repeat(100){id->
  scene.clear()
  val first=scene.frame("song",id,4,1200,1_000_000_000,60f)
  repeat(30){f->
   val pos=scene.frame("song",id,4,1200+f*16L,1_000_000_000+f*16_000_000L,60f)
   ok(pos.size==4)
   pos.forEachIndexed{i,p->ok(p.x==first[i].x);ok(p.depth==first[i].depth);ok(abs(p.x)<=.30f && abs(p.y)<=.17f && p.depth in .95f..1.05f);quadrants+=Pair(p.x>0,p.y>0)}
   for(i in pos.indices)for(j in i+1 until pos.size){val a=pos[i];val b=pos[j];ok(abs(a.x-b.x)>.32f || abs(a.y-b.y)>.20f)}
  }
 }
 ok(quadrants.size==4)
 // Actual JOML camera rotation math: all phrase corners remain forward on abrupt turns / poles.
 for(count in 1..4)for(yaw in -360..360 step 30)for(pitch in -90..90 step 15){
  val placements=scene.frame("turn",4,count,1700,System.nanoTime(),30f,yaw.toFloat(),pitch.toFloat())
  val rotation=Quaternionf().rotationYXZ(Math.toRadians((-yaw-180).toDouble()).toFloat(),Math.toRadians((-pitch).toDouble()).toFloat(),0f)
  val right=Vector3f(1f,0f,0f).rotate(rotation);val up=Vector3f(0f,1f,0f).rotate(rotation);val forward=Vector3f(0f,0f,-1f).rotate(rotation)
  for(p in placements){
   val world=Vector3f(forward).mul(p.depth).fma(p.x,right).fma(p.y,up)
   ok(world.dot(forward)>.94f);ok(abs(world.dot(right))<.33f);ok(abs(world.dot(up))<.20f)
  }
 }
 repeat(100){
  val i=random.nextInt(200);val one=FrontLyricScene().frame("key",i,3,1200,1234,20f)
  val two=FrontLyricScene().frame("key",i,3,1200,1234,20f);ok(one==two)
 }
 scene.clear();val before=scene.frame("a",1,1,10000,100_000_000,20f)
 scene.frame("a",1,1,2000,116_000_000,20f);val after=scene.frame("b",1,1,2000,132_000_000,20f)
 ok(before!=after);scene.clear();ok(scene.frame("a",1,0,0,0,0f).isEmpty())
 println("PASS: $checks lyric assertions; Unicode wrap/timing/gaps/seek/deterministic placement/all quadrants/non-overlap/real JOML front-basis math. Not game framebuffer or device input acceptance.")
}
