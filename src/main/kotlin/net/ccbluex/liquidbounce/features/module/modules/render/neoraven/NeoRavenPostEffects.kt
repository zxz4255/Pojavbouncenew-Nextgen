/* GPL-3.0-only. Port integration for MC 26.3; see licenses/neoraven/NOTICE.md. */
package net.ccbluex.liquidbounce.features.module.modules.render.neoraven

import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.utils.client.mc

/** Explicit ordering mirrors upstream HIGHEST Rain / LOWEST Cinematic world callbacks. */
object NeoRavenPostEffects {
    private var world: Any? = null

    fun beforeWorldOverlays(event: WorldRenderEvent) {
        if (world !== mc.level) {
            dispose()
            world = mc.level
        }
        NeoRainRenderer.render(event)
    }

    fun afterWorldOverlays(event: WorldRenderEvent) = NeoCinematicRenderer.render(event)

    fun dispose() {
        NeoRainRenderer.dispose()
        NeoCinematicRenderer.dispose()
        world = null
    }
}
