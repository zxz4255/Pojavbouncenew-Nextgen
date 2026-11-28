/* SPDX-License-Identifier: GPL-3.0-or-later */
package verify089
import net.ccbluex.liquidbounce.features.module.modules.render.moderngui.ModernGuiInterceptor as Scope
import net.ccbluex.liquidbounce.features.module.modules.render.moderngui.ModuleModernGui as Module
import net.ccbluex.liquidbounce.ui.clickgui.glass.LightPollutionGlassSurface.Profile

fun main() {
    var checks = 0
    fun verify(value: Boolean) { check(value) { "scope check ${checks+1}" }; checks++ }
    verify(!Scope.observedScope()); verify(Scope.observedProfile()==Profile.SOFT)
    for (n in 0 until 1000) {
        val outer = Profile.entries[n % 4]
        Scope.withClientHudScope(outer) {
            verify(Scope.observedScope()); verify(Scope.observedProfile()==outer)
            for (inner in Profile.entries) {
                val result = Scope.withClientHudScope(inner) {
                    verify(Scope.observedScope()); verify(Scope.observedProfile()==inner); 37
                }
                verify(result==37); verify(Scope.observedProfile()==outer)
                try {
                    Scope.withClientHudScope(inner) { throw IllegalArgumentException("expected") }
                } catch (_: IllegalArgumentException) { verify(Scope.observedProfile()==outer) }
            }
        }
        verify(!Scope.observedScope()); verify(Scope.observedProfile()==Profile.SOFT)
        verify(select(TargetHudComponent())==Profile.TARGET)
        verify(select(BlockCounterHudComponent())==Profile.COUNTER)
        verify(select(HudComponent())==Profile.SOFT)
    }
    Module.running=false
    Scope.withClientHudScope(Profile.TARGET) { verify(!Scope.observedScope()); verify(Scope.observedProfile()==Profile.SOFT) }
    Module.running=true; Module.Targets.clientHudCards=false
    Scope.withClientHudScope(Profile.COUNTER) { verify(!Scope.observedScope()); verify(Scope.observedProfile()==Profile.SOFT) }
    println("PASS: $checks exact-production HUD scope/selection assertions, nested restoration and exception/disabled paths; project config/component services are hosts")
}
