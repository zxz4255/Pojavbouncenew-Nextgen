package net.ccbluex.liquidbounce.features.module.modules.render.samsara

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.mc

object ModuleSamsaraClickGui : ClientModule("SamsaraClickGUI", ModuleCategories.RENDER, disableActivation = true) {
    private val color by enumChoice("Color", OpaiColor.Lavender)
    override fun onEnabled() {
        if (inGame) mc.schedule { if (inGame) mc.gui.setScreen(SamsaraOpaiScreen(color)) }
    }
}
