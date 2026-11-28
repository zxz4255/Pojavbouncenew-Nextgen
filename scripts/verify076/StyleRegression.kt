import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui.ClickGuiStyle
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassSurface
fun main() {
 check(ClickGuiStyle.entries.map { it.tag }==listOf("Modern","Glass"))
 val aliases=java.util.TreeMap<String,ClickGuiStyle>(String.CASE_INSENSITIVE_ORDER)
 for(style in ClickGuiStyle.entries) { check(aliases.put(style.tag,style)==null);for(alias in style.tagAliases)check(aliases.put(alias,style)==null) }
 check(aliases["Neumorphism"]==ClickGuiStyle.MODERN)
 check(aliases["NEUMORPHISM"]==ClickGuiStyle.MODERN)
 check(aliases["Glass"]==ClickGuiStyle.GLASS)
 check(UiPalette.TEXT_COLOR==0xFFFFFFFF.toInt())
 check(UiPalette.TEXT_DIMMED_COLOR==0xFFCBD5E1.toInt())
 check(UiPalette.panelBodyBg==UiPalette.surface(.8f))
 ClickGuiGlassSurface.isActive=true
 check(UiPalette.inputBg==UiPalette.white(.08f));check(UiPalette.switchTrackChecked==UiPalette.accent(.62f))
 ClickGuiGlassSurface.isActive=false
 check(UiPalette.inputBg==UiPalette.surface(.55f))
 println("PASS: actual ClickGUI enum has Modern/Glass only; legacy Neumorphism aliases migrate to Modern; actual palette retains Modern and Glass colors (host/UI fixtures).")
}
