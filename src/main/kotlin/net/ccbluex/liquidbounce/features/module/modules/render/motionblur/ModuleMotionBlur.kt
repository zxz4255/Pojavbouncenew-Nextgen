/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.render.motionblur

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories

/** Temporal accumulation of the world/viewmodel before queued HUD/GUI rendering. Default off. */
object ModuleMotionBlur : ClientModule("MotionBlur", ModuleCategories.RENDER,
    aliases = listOf("动态模糊", "运动模糊", "FrameBlend")) {
    val strength by float("Strength", .55f, 0f..0.95f)
    val blurInGui by boolean("BlurInGui", false)
    override val tag: String get() = MotionBlurRenderer.failureReason?.let { "Paused: GPU error" }
        ?: "${(strength * 100).toInt()}%"
    override fun onEnabled() = MotionBlurRenderer.requestReset()
    override fun onDisabled() = MotionBlurRenderer.requestReset()
}
