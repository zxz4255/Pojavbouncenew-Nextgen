from pathlib import Path
import sys
R=Path(__file__).resolve().parents[2];s=(R/'src/main/kotlin/net/ccbluex/liquidbounce/features/module/ClientModule.kt').read_text()
def block(needle):
 a=s.index(needle);i=s.index('{',a)+1;n=1
 while n:n+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[a:i]
Path(sys.argv[1]).write_text('''package verify091.activation
var inGame=true
var loadingNow=false
var errors=0
var failure=""
var notifications=0
object NotificationEvent {enum class Severity {ENABLED,DISABLED}}
class ModuleActivationEvent(val name:String)
class ModuleToggleEvent(val name:String,val hidden:Boolean,val state:Boolean)
object EventManager {fun callEvent(e:Any) { if(failure=="events")throw IllegalStateException("injected observer failure");if(failure=="linkage")throw NoClassDefFoundError("injected");if(failure=="fatal")throw OutOfMemoryError("injected") }}
fun translation(s:String):String {if(failure=="translation")throw IllegalStateException("injected translation");return s}
fun notification(s:String,name:String,severity:NotificationEvent.Severity){notifications++;if(failure=="notification")throw IllegalStateException("injected notification")}
object Log {fun error(message:String,e:Throwable){errors++}}
open class Parent {var lifecycleCalls=0;var lifecycleFailure=false;open fun onToggled(state:Boolean):Boolean { lifecycleCalls++;if(lifecycleFailure)throw IllegalStateException("real lifecycle failure");return state }}
class Host:Parent(){val name="NeoInvManager";val hidden=false;val logger=Log;var calledSinceStartup=false;var disableActivation=false
'''+block('    final override fun onToggled(state: Boolean)')+'\n'+block('    private inline fun activationFeedback(')+'''
}
fun main(){var checks=0;fun test(b:Boolean){checks++;check(b)}
for(reason in listOf("","events","linkage","translation","notification")) for(state in listOf(true,false)){
 failure=reason;val h=Host();test(h.onToggled(state)==state);test(h.lifecycleCalls==1);test(h.calledSinceStartup)
}
failure="";val h=Host();h.disableActivation=true;test(!h.onToggled(true))
inGame=false;val menu=Host();test(menu.onToggled(true));test(menu.lifecycleCalls==0);inGame=true
val life=Host();life.lifecycleFailure=true;var thrown=false;try{life.onToggled(true)}catch(e:IllegalStateException){thrown=true};test(thrown)
failure="fatal";thrown=false;try{Host().onToggled(true)}catch(e:OutOfMemoryError){thrown=true};test(thrown)
failure="notification";loadingNow=true;val before=notifications;test(Host().onToggled(true));test(notifications==before)
println("PASS: $checks exact ClientModule onToggled/helper assertions; injected feedback/translation/linkage failures cannot veto toggle; genuine lifecycle/fatal errors still propagate ($errors logged failures).")
}
''')
