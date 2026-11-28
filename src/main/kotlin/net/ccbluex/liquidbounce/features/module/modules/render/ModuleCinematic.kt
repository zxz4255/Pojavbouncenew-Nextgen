/* GPL-3.0-only. Port of Matr1ze/NeoRaven b483ffc; see licenses/neoraven/NOTICE.md. */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.DisconnectEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.neoraven.NeoCinematicRenderer

/** All five upstream settings, with the upstream defaults. No added camera movement or filters. */
object ModuleCinematic : ClientModule("Cinematic", ModuleCategories.RENDER, aliases = listOf("电影画质", "NeoCinematic")) {
    enum class Quality(override val tag: String) : Tagged {
        LIGHTWEIGHT("Lightweight"), HIGH("High"),
    }
    val quality by enumChoice("Quality", Quality.HIGH)
    val ambientOcclusion by float("AmbientOcclusion", 0.75f, 0f..1f).onChange { kotlin.math.round(it * 20f) / 20f }
    val bloom by float("Bloom", 0.75f, 0f..1f).onChange { kotlin.math.round(it * 20f) / 20f }
    val lensDistortion by float("LensDistortion", 0.75f, 0f..1f).onChange { kotlin.math.round(it * 20f) / 20f }
    val eyeAdaptation by float("EyeAdaptation", 0.75f, 0f..1f).onChange { kotlin.math.round(it * 20f) / 20f }

    override fun onEnabled() = NeoCinematicRenderer.reset()
    override fun onDisabled() = NeoCinematicRenderer.dispose()
    @Suppress("unused")
    private val worldChange = handler<WorldChangeEvent> { NeoCinematicRenderer.dispose() }
    @Suppress("unused")
    private val disconnect = handler<DisconnectEvent> { NeoCinematicRenderer.dispose() }

}
