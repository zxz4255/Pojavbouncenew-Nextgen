package net.ccbluex.liquidbounce.ui.common
object UiPalette {
 fun surfaceLightened(deltaRgb:Int, alpha:Float=1f): Int=0
 val switchTrackUnchecked=0; val switchTrackChecked=0; val switchThumbUnchecked=0; val switchThumbChecked=0;
fun black(a: Float=1f): Int=0
fun accent(a: Float=1f): Int=0
fun withAlpha(color: Int, a: Float): Int=0
fun white(a: Float=1f): Int=0
fun surface(a: Float=1f): Int=0
fun lerpArgb(a: Int,b: Int,t: Float): Int=0
fun packArgb(a: Int,r: Int,g: Int,b: Int): Int=0
fun mapToColor(a: Float=1f): Int=0
fun withAlphaFactor(color: Int,a: Float): Int=0
val moduleSettingsBg: Int=0
val dropdownBg: Int=0
val panelBodyBg: Int=0
val MODULE_HIGHLIGHT_BORDER: Int=0
val dropdownTriggerBg: Int=0
val tabActiveBorder: Int=0
val searchBg: Int=0
val ERROR_COLOR: Int=0
val inputBg: Int=0
val dropdownBorder: Int=0
val GRID_LINE_COLOR: Int=0
val tabHoverBg: Int=0
val TEXT_COLOR: Int=0
val tabsPillBg: Int=0
val inputBorder: Int=0
val tabActiveBg: Int=0
val moduleHoverBg: Int=0
val panelHeaderBg: Int=0
val SEARCH_ALIAS_COLOR: Int=0
val TEXT_MUTED_COLOR: Int=0
val TEXT_DIMMED_COLOR: Int=0
}
object UiIcons {
val CLIENT=net.minecraft.resources.Identifier.fromNamespaceAndPath("test","client")
val STEVE_FACE=net.minecraft.resources.Identifier.fromNamespaceAndPath("test","icon")
val CROSS=net.minecraft.resources.Identifier.fromNamespaceAndPath("test","icon")
fun categoryIcon(tag: String)=net.minecraft.resources.Identifier.fromNamespaceAndPath("test","icon")
val nearestSetup=net.minecraft.resources.Identifier.fromNamespaceAndPath("test","icon")
val SETTINGS_EXPAND=net.minecraft.resources.Identifier.fromNamespaceAndPath("test","icon")
val TICK=net.minecraft.resources.Identifier.fromNamespaceAndPath("test","icon")
val RESET=net.minecraft.resources.Identifier.fromNamespaceAndPath("test","icon")
val DRAG=net.minecraft.resources.Identifier.fromNamespaceAndPath("test","icon")
val aspectRatio=net.minecraft.resources.Identifier.fromNamespaceAndPath("test","icon")
val linearSetup=net.minecraft.resources.Identifier.fromNamespaceAndPath("test","icon")
}
