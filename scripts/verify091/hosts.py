from pathlib import Path
import sys
R=Path(__file__).resolve().parents[2];W=Path(sys.argv[1]);W.mkdir(exist_ok=True)
for p in (R/'scripts/verify076/stubs').glob('*.kt'):
 s=p.read_text()
 if p.name=='S6.kt': s=s.replace('    var enabled:', '    val bind = net.ccbluex.liquidbounce.utils.input.InputBind()\n    open val externalBind: Boolean get() = false\n    var enabled:')
 if p.name in ['Cleaner.kt','ArmorModule.kt']:
  s=s.replace(' { val running = false }', ' : net.ccbluex.liquidbounce.features.module.ClientModule("Host", net.ccbluex.liquidbounce.features.module.ModuleCategories.PLAYER)')
 if p.name=='S4.kt': s=s.replace('interface Listenable','interface EventListener : Listenable\ninterface Listenable')
 (W/p.name).write_text(s)
(W/'Events.kt').write_text((W/'Events.kt').read_text()+'''
class KeyboardKeyEvent(val screen: net.minecraft.client.gui.screens.Screen?=null, val scanCode:Int=0, val isRepeat:Boolean=false) : Event
class MouseButtonEvent(val screen: net.minecraft.client.gui.screens.Screen?=null) : Event
''')
(W/'Input.kt').write_text('''package net.ccbluex.liquidbounce.utils.input
import net.ccbluex.liquidbounce.event.events.*
class InputBind {
 enum class BindAction { TOGGLE,HOLD,SMART };val action=BindAction.TOGGLE
 fun matchesKeyRelease(e:KeyboardKeyEvent)=false
 fun matchesKeyPress(e:KeyboardKeyEvent)=false
 fun matchesKey(i:Int)=false
 fun matchesMouseRelease(e:MouseButtonEvent)=false
 fun matchesMousePress(e:MouseButtonEvent)=false
}
''')
