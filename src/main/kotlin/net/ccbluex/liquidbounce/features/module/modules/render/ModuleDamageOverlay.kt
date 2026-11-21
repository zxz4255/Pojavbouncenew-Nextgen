/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.getBounds
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** FPS-style red edge vignette: a short hit pulse plus a persistent, health-scaled warning. */
object ModuleDamageOverlay : ClientModule("DamageOverlay", ModuleCategories.RENDER, state = true) {
    private val warningHealth by float("WarningHealth", 50f, 15f..85f, "%")
    private val criticalHealth by float("CriticalHealth", 25f, 5f..45f, "%")
    private val edgeWidth by float("EdgeWidth", 34f, 12f..48f, "%")
    private val maxOpacity by float("MaxOpacity", 62f, 10f..85f, "%")
    private val hitPulse by float("HitPulse", 50f, 0f..100f, "%")
    private val criticalFlash by boolean("CriticalFlash", true)
    private val flashSpeed by float("FlashSpeed", 2f, 0.5f..4f, "Hz")

    /** Called from the beginning of HUD extraction so all HUD content stays above the vignette. */
    fun extract(context: GuiGraphicsExtractor, tickDelta: Float) {
        if (!running) return
        val player = mc.player ?: return
        if (!player.isAlive) return

        val healthRatio = (player.health / player.maxHealth.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val critical = (criticalHealth / 100f).coerceIn(0.05f, 0.45f)
        val warning = max(warningHealth / 100f, critical + 0.05f).coerceAtMost(0.9f)
        val lowHealthAmount = 1f - smoothStep(critical, warning, healthRatio)
        val healthOpacity = lowHealthAmount * (maxOpacity / 100f).coerceIn(0f, 0.85f)

        // Vanilla's hurtTime is ten ticks; partial ticks keep the pulse smooth between game ticks.
        val hurtAmount = (player.hurtTime.toFloat() - tickDelta.coerceIn(0f, 1f))
            .coerceIn(0f, 10f) / 10f
        val damageOpacity = hurtAmount * (hitPulse / 100f).coerceIn(0f, 1f)
        var opacity = 1f - (1f - healthOpacity) * (1f - damageOpacity)

        if (criticalFlash) {
            // Fade the blinking in just above the critical threshold instead of popping on abruptly.
            val flashAmount = 1f - smoothStep(critical, (critical + 0.12f).coerceAtMost(1f), healthRatio)
            if (flashAmount > 0f) {
                val phase = System.nanoTime() / 1_000_000_000.0 * flashSpeed.coerceIn(0.5f, 4f) * 2.0 * PI
                val pulse = ((sin(phase) + 1.0) * 0.5).toFloat()
                // Retain a visible baseline while still producing a clear low-health blink.
                opacity *= 1f - 0.72f * flashAmount * (1f - pulse)
            }
        }

        if (opacity <= 0.005f) return
        val width = context.guiWidth().toFloat()
        val height = context.guiHeight().toFloat()
        if (width <= 0f || height <= 0f) return

        // Pack strength into alpha and the edge width into green; the shader uses both.
        val alphaByte = (opacity.coerceIn(0f, 1f) * 255f).roundToInt()
        val widthByte = ((edgeWidth / 100f).coerceIn(0.08f, 0.6f) * 255f).roundToInt()
        val shaderParameters = (alphaByte shl 24) or (0xFF shl 16) or (widthByte shl 8)

        context.drawCustomElement(
            pipeline = ClientRenderPipelines.LowHealthVignette,
            bounds = context.getBounds(0f, 0f, width, height),
        ) { pose ->
            addVertexWith2DPose(pose, 0f, 0f).setUv(0f, 0f).setColor(shaderParameters)
            addVertexWith2DPose(pose, 0f, height).setUv(0f, 1f).setColor(shaderParameters)
            addVertexWith2DPose(pose, width, height).setUv(1f, 1f).setColor(shaderParameters)
            addVertexWith2DPose(pose, width, 0f).setUv(1f, 0f).setColor(shaderParameters)
        }
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
