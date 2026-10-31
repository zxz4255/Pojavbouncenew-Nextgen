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

import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.drawHorizontalLine
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawVerticalLine
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironment
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.combat.shouldBeShown
import net.ccbluex.liquidbounce.utils.entity.cameraDistanceSq
import net.ccbluex.liquidbounce.utils.entity.getActualHealth
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.ccbluex.liquidbounce.utils.math.sq
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.animal.squid.Squid
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.npc.villager.AbstractVillager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * GlowESP
 *
 * A port of the Moon client `GlowESP` module: a soft halo around entities plus the matching
 * 2D overlay (box, health and armor bars) and a 3D box.
 *
 * The halo is built from a stack of inflated shells around the entity box instead of a
 * post-process framebuffer, so it needs no render target and no custom shader of its own.
 */
object ModuleGlowESP : ClientModule("GlowESP", ModuleCategories.RENDER, aliases = listOf("Glow", "EspGlow")) {

    override val baseKey: String
        get() = "${ConfigSystem.KEY_PREFIX}.module.glowEsp"

    /**
     * Which entities the module applies to. Mirrors `GlowSettings.Target` of the original.
     */
    private enum class Target(override val tag: String) : Tagged {
        PLAYER("Player"),
        ANIMAL("Animal"),
        MOB("Mob"),
        INVISIBLE("Invisible"),
    }

    private val targets = multiEnumChoice("Targets", Target.PLAYER, Target.MOB, Target.ANIMAL)

    private val maximumDistance by float("MaximumDistance", 128F, 1F..512F)

    private val modes = choices("Mode", GlowMode, arrayOf(GlowMode, BoxMode, TwoDMode))

    /**
     * The halo. `Radius` is its thickness in blocks, `Layers` how many shells build it up and
     * `Opacity` the alpha of the innermost shell.
     */
    private object Glow : ToggleableValueGroup(ModuleGlowESP, "Glow", true) {
        val radius by float("Radius", 0.18F, 0.02F..1F)
        val layers by int("Layers", 8, 1..24)
        val opacity by float("Opacity", 0.42F, 0.01F..1F)
        val hurtColor by color("HurtColor", Color4b(255, 60, 60))
        val playerColor by color("PlayerColor", Color4b(255, 255, 255))
        val animalColor by color("AnimalColor", Color4b(120, 255, 120))
        val mobColor by color("MobColor", Color4b(255, 120, 120))
    }

    /**
     * The 3D box of the original module (`EspModule` box settings).
     */
    private object Box : ToggleableValueGroup(ModuleGlowESP, "Box", false) {
        val outline by boolean("Outline", true)
        val opacity by float("Opacity", 0.3F, 0.01F..1F)
        val widthExpansion by float("WidthExpansion", 0F, 0F..1F)
        val heightExpansion by float("HeightExpansion", 0F, 0F..1F)
        val normalColor by color("NormalColor", Color4b(0, 90, 255))
    }

    /**
     * The 2D overlay of the original module (`EspSettings`).
     */
    private object TwoD : ToggleableValueGroup(ModuleGlowESP, "2D", false) {
        val box by boolean("Box", true)
        val cornered by boolean("Cornered", true)
        val cornerFactor by float("CornerFactor", 2F, 2F..4F)
        val infill by boolean("Infill", false)
        val boxColor by color("BoxColor", Color4b(255, 255, 255))
        val health by boolean("Health", true)
        val healthColor by color("HealthColor", Color4b(255, 60, 60))
        val armor by boolean("Armor", true)
        val armorColor by color("ArmorColor", Color4b(180, 180, 255))
        val spacing by float("Spacing", 2F, 0F..16F, "px")
        val thickness by float("Thickness", 1F, 1F..9F, "px")
    }

    init {
        tree(Glow)
        tree(Box)
        tree(TwoD)
    }

    /**
     * Collected once per tick so the render handlers only walk a small list.
     */
    private class Prepared(
        val entity: LivingEntity,
        val localBox: AABB,
    ) {
        var position: Vec3 = Vec3.ZERO
        var worldBox: AABB = AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        fun update(tickDelta: Float) {
            position = entity.interpolateCurrentPosition(tickDelta)
            worldBox = localBox.move(position)
        }
    }

    private val prepared = ObjectArrayList<Prepared>()

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        prepared.clear()

        val level = mc.level ?: return@handler

        for (entity in level.entitiesForRendering()) {
            if (entity !is LivingEntity) continue
            if (!entity.shouldBeShown()) continue
            if (!shouldRender(entity)) continue

            val dimensions = entity.getDimensions(entity.pose)
            val halfWidth = dimensions.width.toDouble() / 2.0
            val localBox = AABB(
                -halfWidth, 0.0, -halfWidth,
                halfWidth, dimensions.height.toDouble(), halfWidth,
            ).inflate(0.05)

            prepared.add(Prepared(entity, localBox))
        }
    }

    private fun shouldRender(entity: Entity): Boolean {
        if (entity.position().cameraDistanceSq() > maximumDistance.sq()) {
            return false
        }

        if (entity.isInvisible && Target.INVISIBLE !in targets) {
            return false
        }

        return when (entity) {
            is Player -> Target.PLAYER in targets
            is Monster -> Target.MOB in targets
            is Animal, is AbstractVillager, is Squid -> Target.ANIMAL in targets
            else -> false
        }
    }

    private fun colorOf(entity: LivingEntity): Color4b = when {
        entity.hurtTime > 0 -> Glow.hurtColor
        entity is Player -> Glow.playerColor
        entity is Monster -> Glow.mobColor
        else -> Glow.animalColor
    }

    /**
     * Draws the halo of one entity. The outermost shell is drawn first and every shell above it is
     * a little smaller and a little stronger, which reads as a gradient.
     */
    private fun WorldRenderEnvironment.drawHalo(box: AABB, color: Color4b) {
        val layers = Glow.layers
        val radius = Glow.radius.toDouble()

        withPositionRelativeToCamera(Vec3.ZERO) {
            for (layer in layers downTo 1) {
                val step = layer.toDouble() / layers
                val alpha = (Glow.opacity * 255.0 * (1.0 - step + 1.0 / layers)).toInt().coerceIn(0, 255)

                if (alpha <= 0) continue

                drawBox(box.inflate(radius * step), color.with(a = alpha), null)
            }
        }
    }

    private object GlowMode : Mode("Glow") {
        override val parent: ModeValueGroup<Mode>
            get() = modes

        @Suppress("unused")
        private val renderHandler = handler<WorldRenderEvent> { event ->
            if (prepared.isEmpty()) return@handler

            for (state in prepared) {
                state.update(event.partialTicks)
            }

            event.renderEnvironment {
                for (state in prepared) {
                    drawHalo(state.worldBox, colorOf(state.entity))
                }
            }
        }
    }

    private object BoxMode : Mode("Box") {
        override val parent: ModeValueGroup<Mode>
            get() = modes

        @Suppress("unused")
        private val renderHandler = handler<WorldRenderEvent> { event ->
            if (prepared.isEmpty()) return@handler

            for (state in prepared) {
                state.update(event.partialTicks)
            }

            val widthExpansion = Box.widthExpansion.toDouble()
            val heightExpansion = Box.heightExpansion.toDouble()
            val faceColor = Box.normalColor.with(a = (Box.opacity * 255F).toInt().coerceIn(0, 255))
            val outlineColor = Box.normalColor.takeIf { Box.outline }

            event.renderEnvironment {
                for (state in prepared) {
                    val box = state.localBox.inflate(widthExpansion).let {
                        AABB(
                            it.minX, it.minY - heightExpansion, it.minZ,
                            it.maxX, it.maxY + heightExpansion, it.maxZ,
                        )
                    }

                    withPositionRelativeToCamera(state.position) {
                        drawBox(box, faceColor, outlineColor)
                    }
                }

                if (Glow.enabled) {
                    for (state in prepared) {
                        drawHalo(state.worldBox, colorOf(state.entity))
                    }
                }
            }
        }
    }

    private object TwoDMode : Mode("2D") {
        override val parent: ModeValueGroup<Mode>
            get() = modes

        @Suppress("unused")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (prepared.isEmpty()) return@handler

            val guiScale = mc.options.guiScale().get().coerceAtLeast(1).toFloat()
            val thickness = TwoD.thickness / guiScale
            val spacing = TwoD.spacing / guiScale

            for (state in prepared) {
                state.update(event.tickDelta)
            }

            with(event.context) {
                for (state in prepared) {
                    val rect = WorldToScreen.calculateScreenRect(state.worldBox) ?: continue
                    val entity = state.entity
                    val color = TwoD.boxColor

                    if (TwoD.infill) {
                        drawQuad(rect.x1, rect.y1, rect.x2, rect.y2, fillColor = color.with(a = 40))
                    }

                    if (TwoD.box) {
                        if (TwoD.cornered) {
                            val gap = (TwoD.cornerFactor / 4F).coerceIn(0F, 1F)
                            val cw = rect.w * (1F - gap) / 2F
                            val ch = rect.h * (1F - gap) / 2F

                            drawHorizontalLine(rect.x1, rect.x1 + cw, rect.y1, thickness, color)
                            drawVerticalLine(rect.x1, rect.y1, rect.y1 + ch, thickness, color)
                            drawHorizontalLine(rect.x2 - cw, rect.x2, rect.y1, thickness, color)
                            drawVerticalLine(rect.x2 - thickness, rect.y1, rect.y1 + ch, thickness, color)
                            drawHorizontalLine(rect.x1, rect.x1 + cw, rect.y2 - thickness, thickness, color)
                            drawVerticalLine(rect.x1, rect.y2 - ch, rect.y2, thickness, color)
                            drawHorizontalLine(rect.x2 - cw, rect.x2, rect.y2 - thickness, thickness, color)
                            drawVerticalLine(rect.x2 - thickness, rect.y2 - ch, rect.y2, thickness, color)
                        } else {
                            drawQuad(rect.x1, rect.y1, rect.x2, rect.y2, outlineColor = color)
                        }
                    }

                    if (TwoD.health) {
                        val maxHealth = entity.maxHealth.coerceAtLeast(1F)
                        val health = entity.getActualHealth().coerceIn(0F, maxHealth)
                        val ratio = health / maxHealth
                        val barX = rect.x1 - spacing - thickness

                        drawVerticalLine(barX, rect.y1, rect.y2, thickness, Color4b(0, 0, 0, 160))
                        drawVerticalLine(barX, rect.y2 - rect.h * ratio, rect.y2, thickness, TwoD.healthColor)
                    }

                    if (TwoD.armor) {
                        val armor = entity.armorValue
                        if (armor > 0) {
                            val ratio = (armor / 20F).coerceIn(0F, 1F)
                            val barX = rect.x2 + spacing

                            drawVerticalLine(barX, rect.y1, rect.y2, thickness, Color4b(0, 0, 0, 160))
                            drawVerticalLine(barX, rect.y2 - rect.h * ratio, rect.y2, thickness, TwoD.armorColor)
                        }
                    }
                }
            }
        }
    }

    override fun onDisabled() {
        prepared.clear()
    }

}