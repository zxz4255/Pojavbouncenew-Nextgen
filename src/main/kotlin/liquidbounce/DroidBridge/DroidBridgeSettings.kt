package liquidbounce.DroidBridge

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories

internal object DroidBridgeSettings :
    ClientModule("DroidBridge", ModuleCategories.RENDER, disableActivation = true) {

    private val clickgui by float("ClickGuiScale", 1f, 0.5f..2f).onChanged { apply() }

    private val hud by float("HudScale", 1f, 0.5f..2f).onChanged { apply() }

    private val opaiMenu by float("OpaiMenuScale", 0.5f, 0.25f..2f).onChanged { apply() }

    private val settingsScale by float("SettingsScale", 1f, 0.5f..2f).onChanged { apply() }

    fun apply() {
        if (!Native.isSupported) {
            return
        }
        runCatching { Native.configure(clickgui, hud, settingsScale, opaiMenu) }
    }

}
