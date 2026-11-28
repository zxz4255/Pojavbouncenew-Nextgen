/* GPL-3.0-only. Port of Matr1ze/NeoRaven b483ffc; see licenses/neoraven/NOTICE.md. */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.DisconnectEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.neoraven.NeoRainRenderer

/** Upstream has no effect sliders. Rain/exposure drive the complete puddle/reflection animation. */
object ModuleRainReflections : ClientModule(
    "RainReflections", ModuleCategories.RENDER,
    aliases = listOf("Rain Reflections", "雨天反射", "雨水反射"),
) {
    override fun onEnabled() = NeoRainRenderer.reset()
    override fun onDisabled() = NeoRainRenderer.dispose()
    @Suppress("unused")
    private val worldChange = handler<WorldChangeEvent> { NeoRainRenderer.dispose() }
    @Suppress("unused")
    private val disconnect = handler<DisconnectEvent> { NeoRainRenderer.dispose() }

}
