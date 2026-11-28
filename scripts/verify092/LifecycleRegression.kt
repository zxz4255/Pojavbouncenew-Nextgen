package verify092
import net.ccbluex.liquidbounce.features.module.modules.player.neoraven.ModuleNeoInvManager
import net.ccbluex.liquidbounce.features.module.modules.player.neoraven.NeoInvTraceWriter
import net.ccbluex.liquidbounce.features.module.modules.player.neoraven.NeoInvDiagnostics
import net.ccbluex.liquidbounce.event.EventHook
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.config.types.*
import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.events.ValueChangedEvent
import net.ccbluex.liquidbounce.utils.client.inGame
import java.nio.file.Files
import kotlin.random.Random

private var assertions=0
private fun test(value:Boolean) {assertions++;check(value) {"Assertion $assertions"}}
private fun fresh()=Value("Enabled",defaultValue=false,valueType=ValueType.BOOLEAN)
fun main() {
 val m=ModuleNeoInvManager
 test(!m.enabled);test(!m.disableActivation);test(!m.notActivatable)
 var calls=0;var beforeCommitEvents=0
 EventManager.callbacks.add {e->if(e is ModuleToggleEvent && e.moduleName==m.name) {
  calls++;if(m.enabled!=e.enabled)beforeCommitEvents++
 }}
 m.enabled=true
 test(m.enabled);test(m.enabledValue.get());test(m.enabledValue.asStateFlow().value);test(calls==1)
 repeat(50) {m.enabled=false;test(!m.enabled);m.enabled=true;test(m.enabled)}
 inGame=false;m.enabled=false;m.enabled=true;test(m.enabled);inGame=true
 test(beforeCommitEvents==101) // Diagnostic build must NOT silently change 091 callback ordering.
 test(m.description.get()!!.contains("092-DIAGNOSTIC"))
 EventManager.callbacks.clear()
 println("PASS: complete actual Value/ToggleableValueGroup/ClientModule/Neo/Shortcuts chain enables, disables and retains state; GUI description has 092 marker. Game/bind/DSL/services are hosts, not a game test.")

 val events=mutableListOf<ValueWriteTrace<Boolean>>()
 val v=fresh().traceWrites {events+=it}
 v.set(true)
 test(events.map {it.stage}==listOf(ValueWriteTrace.Stage.REQUEST,ValueWriteTrace.Stage.TRANSFORMED,ValueWriteTrace.Stage.COMMITTED,ValueWriteTrace.Stage.COMPLETED))
 test(events[0].actual==false && events[2].actual && !events[2].published && events[3].published)
 test(events.map {it.id}.distinct().size==1)
 events.clear();v.set(true);test(events.single().stage==ValueWriteTrace.Stage.UNCHANGED)
 events.clear();v.immutable();v.set(false);test(v.get());test(events.last().stage==ValueWriteTrace.Stage.IMMUTABLE)
 events.clear();val rejected=fresh().traceWrites {events+=it}.onChange {throw IllegalStateException("injected lifecycle reject")}
 rejected.set(true);test(!rejected.get());test(events.last().stage==ValueWriteTrace.Stage.REJECTED);test(events.last().failure is IllegalStateException)
 events.clear();val transformed=fresh().traceWrites {events+=it}.onChange {false};transformed.set(true)
 test(!transformed.get());test(events.last().requested && !events.last().candidate && !events.last().actual)
 events.clear();val post=fresh().traceWrites {events+=it}.onChanged {throw IllegalStateException("injected observer failure")}
 var thrown=false;try {post.set(true)}catch(e:IllegalStateException){thrown=true}
 test(thrown);test(post.get());test(!post.asStateFlow().value);test(events.last().stage==ValueWriteTrace.Stage.OBSERVER_FAILED)
 events.clear();val applying=fresh().traceWrites {events+=it}
 thrown=false;try {applying.set(true) {throw IllegalStateException("injected apply failure")}}catch(e:IllegalStateException){thrown=true}
 test(thrown);test(!applying.get());test(events.last().stage==ValueWriteTrace.Stage.APPLY_FAILED)
 val random=Random(92)
 repeat(100) {
  val plain=fresh();val traced=fresh().traceWrites {if(it.id%2L==0L)throw IllegalStateException("broken trace sink")}
  repeat(30) {val target=random.nextBoolean();plain.set(target);traced.set(target);test(plain.get()==traced.get());test(plain.asStateFlow().value==traced.asStateFlow().value)}
 }
 println("PASS: opt-in traces distinguish request/no-op/transform/immutable/reject/apply/post-commit failures; broken diagnostic consumers do not veto real writes; traced/untraced states and flows match.")

 val capture=mutableListOf<ValueWriteTrace<Boolean>>()
 m.enabled=false;m.enabledValue.traceWrites {capture+=it}
 var reject=false;m.enabledValue.onChange {if(reject && it)throw IllegalStateException("injected Neo rejection");it}
 reject=true;m.enabled=true;test(!m.enabled);test(capture.last().stage==ValueWriteTrace.Stage.REJECTED)
 reject=false;capture.clear();m.enabled=true;test(m.enabled)
 fun simulatedExternalWriter(){m.enabled=false}
 simulatedExternalWriter();test(!m.enabled)
 test(capture.any {it.stage==ValueWriteTrace.Stage.COMMITTED && !it.actual && !it.requested})
 @Suppress("UNCHECKED_CAST")
 val snapshot = NeoInvDiagnostics.javaClass.getDeclaredField("snapshotHandler").let {it.isAccessible=true;it.get(null)} as EventHook<GameTickEvent>
 snapshot.fn(GameTickEvent())
 test(NeoInvDiagnostics.lastSummary.contains("STATE_SNAPSHOT actual=false flow=false running=false"))
 println("PASS: injected Neo enable rejection and a subsequent external disable are distinguished on the complete actual setter/lifecycle chain; external disable is HONORED, never forced back on.")

 val folder=Files.createTempDirectory("neoinv092")
 try {
  val mirror=mutableListOf<String>();val writer=NeoInvTraceWriter({folder},{mirror+=it})
  capture.forEach {writer.record(it,"screen=TestScreen bindAction=TOGGLE")}
  val file=folder.resolve(NeoInvTraceWriter.FILE_NAME);val text=Files.readString(file)
  test(text.contains("092-DIAGNOSTIC"));test(text.contains("stage=REQUEST"));test(text.contains("stage=COMMITTED"))
  test(text.contains("requested=false"));test(text.contains("Activation write origin"));test(text.contains("LifecycleRegression"))
  test(mirror.any {it.contains("requested=false")})
  // Real rotation and bounded output checks.
  Files.writeString(file,"x".repeat(NeoInvTraceWriter.MAX_BYTES.toInt()))
  writer.note("ROTATION","test")
  test(Files.exists(folder.resolve("neo-invmanager-092.previous.log")))
  test(Files.size(file)<NeoInvTraceWriter.MAX_BYTES)
  repeat(NeoInvTraceWriter.MAX_ENTRIES+10) {writer.note("LIMIT","test")}
  test(Files.readString(file).contains("TRACE_LIMIT reached"))
  test(mirror.size<=NeoInvTraceWriter.MAX_ENTRIES+1)
  val size=Files.size(file);writer.note("AFTER_LIMIT","test");test(Files.size(file)==size)
  var fallback=0;val inaccessible=NeoInvTraceWriter({throw java.io.IOException("injected unwritable log directory")},{fallback++})
  inaccessible.note("TEST","no file");inaccessible.note("TEST","still mirrored");test(fallback==3)
  val badLogger=NeoInvTraceWriter({folder},{throw IllegalStateException("injected logger failure")})
  badLogger.note("TEST","file survives logger failure");test(Files.readString(file).contains("file survives logger failure"))
 } finally {Files.walk(folder).use {paths->paths.sorted(Comparator.reverseOrder()).forEach {Files.deleteIfExists(it)}}}
 println("PASS: dedicated file + latest-log mirror, full origin stack, session/build/thread/state fields, 2MiB rotation, 2048-entry cap and unwritable-directory/logger-failure behavior.")
 println("PASS: $assertions assertions. NOT a reproduction of the user's device failure; no root-cause/final-fix claim.")
}
