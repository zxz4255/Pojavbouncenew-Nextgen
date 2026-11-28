/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
@file:Suppress("MagicNumber")

package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.DisconnectEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.engine.type.Color4b

/**
 * Smooth Polished Mirror Ground (`平滑镜面地面`):
 * Turns ground blocks into a smooth, zero-undulation glossy surface (`无起伏平滑光面`)
 * that faintly reflects upside-down mirror images (`隐约显周围实体、方块的倒像`) of nearby entities and blocks.
 */
object ModuleMirrorGround : ClientModule(
    name = "MirrorGround",
    category = ModuleCategories.RENDER,
    aliases = listOf("SmoothMirror", "GroundMirror", "BlockMirror", "平滑镜面", "镜面地面"),
) {

    private val radius by int("Radius", 18, 6..32, "blocks")
    private val reflectionOpacity by int("ReflectionOpacity", 36, 5..85, "%")
    private val surfacePolish by float("SurfacePolish", 0.62f, 0.0f..1.5f)
    private val fresnel by float("Fresnel", 0.78f, 0.0f..1.0f)
    private val mirrorSoftness by float("MirrorSoftness", 0.65f, 0.0f..2.0f)
    private val maxRayDistance by float("MaxRayDistance", 22f, 6f..40f)
    private val includeWater by boolean("IncludeWater", true)
    private val includeSlabs by boolean("IncludeSlabs", true)
    private val glazeColor by color("GlazeColor", Color4b(232, 242, 255, 38))

    override fun onEnabled() {
        MirrorGroundRenderer.resetState()
    }

    override fun onDisabled() {
        if (!ModuleWaterReflections.isUsingMirrorMode) {
            MirrorGroundRenderer.clearCache()
        }
    }

    @Suppress("unused")
    private val disconnectHandler = handler<DisconnectEvent> {
        MirrorGroundRenderer.clearCache()
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        MirrorGroundRenderer.clearCache()
    }

    @Suppress("unused")
    private val worldRenderHandler = handler<WorldRenderEvent> { event ->
        MirrorGroundRenderer.render(
            event = event,
            radius = radius,
            mirrorOpacity = reflectionOpacity / 100f,
            glossStrength = surfacePolish,
            fresnelStrength = fresnel,
            mirrorSoftness = mirrorSoftness,
            maxRayDistance = maxRayDistance,
            includeWater = includeWater,
            includeSlabs = includeSlabs,
            glazeColor = glazeColor,
        )
    }
}
