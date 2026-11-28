package net.ccbluex.liquidbounce.ui.common
import net.ccbluex.liquidbounce.config.types.list.Tagged
enum class UiLanguageMode(override val tag: String) : Tagged { AUTO("Auto") }
object UiLocalization { fun setLanguageMode(mode: UiLanguageMode) {};fun registerSyncListener(fn: (UiLanguageMode) -> Unit) {} }
