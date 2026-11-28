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

package net.ccbluex.liquidbounce.render

import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.ShaderType
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import net.ccbluex.liquidbounce.LiquidBounce
import net.minecraft.resources.Identifier

sealed class ClientShaders(val type: ShaderType) : ShaderSource {

    private val shaders = Object2ObjectOpenHashMap<Identifier, String>()

    protected operator fun String.invoke(path: String): Identifier = newShader("${type.getName()}/${this}", path = path)

    object Vertex : ClientShaders(ShaderType.VERTEX) {

        @JvmField
        val KeybindsFrost = "keybinds_frost"("shaders/keybinds/frost.vsh")

        @JvmField
        val MusicFrost = "music_frost"("shaders/music/frost.vsh")

        @JvmField
        val PlainPosTex = "plain_pos_tex"("shaders/position_tex.vert")

        @JvmField
        val PosRelativeToCamera = "pos_relative_to_camera"("shaders/relative_to_camera/position.vsh")

        @JvmField
        val PosColorRelativeToCamera = "pos_color_relative_to_camera"("shaders/relative_to_camera/position_color.vsh")

        @JvmField
        val Sobel = "sobel"("shaders/sobel.vert")

        @JvmField
        val PlainProjection = "plane_projection"("shaders/plane_projection.vert")

        @JvmField
        val Circle = "circle"("shaders/circle/circle.vsh")

        @JvmField
        val GuiCircleLut = "gui_circle_lut"("shaders/circle/gui_circle_lut.vsh")

        @JvmField
        val GuiRoundedRect = "gui_rounded_rect"("shaders/gui/rounded_rect.vsh")

        @JvmField
        val LowHealthVignette = "low_health_vignette"("shaders/low_health/low_health_vignette.vsh")

        @JvmField
        val OnyxSoftShadow = "onyx_soft_shadow"("shaders/gui/onyx_soft_shadow.vsh")

        @JvmField
        val ArrayListGlow = "arraylist_glow"("shaders/gui/arraylist_glow.vsh")

        /**
         * Shared vertex stage of the arraylist surface shaders (row fill, sheen band, rim light).
         */
        @JvmField
        val ArrayListShape = "arraylist_shape"("shaders/gui/arraylist_shape.vsh")

        @JvmField
        val GradientCircle = "gradient_circle"("shaders/circle/gradient_circle.vsh")

        /** Music panel shadow: analytic rounded-box falloff, one quad per light source. */
        @JvmField
        val MusicPanelShadow = "music_panel_shadow"("shaders/gui/music_panel_shadow.vsh")

        @JvmField
        val GuiCssBox = "gui_css_box"("shaders/gui/css_box.vsh")

        @JvmField
        val GuiRoundedTex = "gui_rounded_tex"("shaders/gui/rounded_tex.vsh")

        @JvmField
        val GuiInkReveal = "gui_ink_reveal"("shaders/gui/ink_reveal.vsh")

        @JvmField
        val WaterGround = "water_ground"("shaders/reflection/water_ground.vsh")

        @JvmField
        val MirrorGround = "mirror_ground"("shaders/reflection/mirror_ground.vsh")

        @JvmField
        val ClickGuiLiquidGlass = "clickgui_liquid_glass"("shaders/clickgui/liquid_glass.vsh")

        @JvmField
        val World3dHolo = "world3d_holo"("shaders/clickgui/world3d_holo.vsh")

    }

    object Fragment : ClientShaders(ShaderType.FRAGMENT) {

        @JvmField
        val CinematicLight = "neo_cinematic_light"("shaders/neoraven/cinematic_light.fsh")
        @JvmField
        val CinematicHigh = "neo_cinematic_high"("shaders/neoraven/cinematic_high.fsh")
        @JvmField
        val CinematicDownsample = "neo_cinematic_mip"("shaders/neoraven/downsample.fsh")
        @JvmField
        val RainTrace = "neo_rain_trace"("shaders/neoraven/rain_trace.fsh")
        @JvmField
        val RainComposite = "neo_rain_composite"("shaders/neoraven/rain_composite.fsh")


        @JvmField
        val KeybindsFrost = "keybinds_frost"("shaders/keybinds/frost.fsh")

        @JvmField
        val MusicFrost = "music_frost"("shaders/music/frost.fsh")

        @JvmField
        val MusicBoxH = "music_box_h"("shaders/music/box_h.fsh")

        @JvmField
        val MusicBoxV = "music_box_v"("shaders/music/box_v.fsh")

        @JvmField
        val KeybindsBoxH = "keybinds_box_h"("shaders/keybinds/box_h.fsh")

        @JvmField
        val KeybindsBoxV = "keybinds_box_v"("shaders/keybinds/box_v.fsh")

        @JvmField
        val BgraPosTex = "bgra_pos_tex_color"("shaders/bgra_position_tex_color.frag")

        @JvmField
        val FontMask = "font_mask"("shaders/font_mask.frag")

        @JvmField
        val PosRelativeToCamera = "pos_relative_to_camera"("shaders/relative_to_camera/position.fsh")

        @JvmField
        val Blit = "blit"("shaders/blit.frag")

        @JvmField
        val Chams = "chams"("shaders/chams.frag")

        @JvmField
        val Blend = "blend"("shaders/blend.frag")

        @JvmField
        val GuiBlurH = "blur_h"("shaders/blur/ui_blur_h.frag")

        @JvmField
        val GuiBlurV = "blur_v"("shaders/blur/ui_blur_v.frag")

        @JvmField
        val Glow = "glow"("shaders/glow/glow.frag")

        @JvmField
        val ItemChamsEffects = "item_chams_effects"("shaders/item_chams/effects.frag")

        @JvmField
        val ScreenshotEspHalo = "screenshot_esp_halo"("shaders/screenshot_esp/halo.frag")

        @JvmField
        val ScreenshotEspRemoveMask = "screenshot_esp_remove_mask"("shaders/screenshot_esp/remove_mask.frag")

        @JvmField
        val ItemChamsRecolor = "item_chams_recolor"("shaders/item_chams/recolor.frag")

        @JvmField
        val ItemChamsTrail = "item_chams_trail"("shaders/item_chams/trail.frag")

        @JvmField
        val EntityOutline = "outline"("shaders/outline/entity_outline.frag")

        @JvmField
        val RoundedRect = "rounded_rect"("shaders/circle/rounded_rect.fsh")

        @JvmField
        val GuiCircleLut = "gui_circle_lut"("shaders/circle/gui_circle_lut.fsh")

        @JvmField
        val GuiRoundedRect = "gui_rounded_rect"("shaders/gui/rounded_rect.fsh")

        @JvmField
        val LowHealthVignette = "low_health_vignette"("shaders/low_health/low_health_vignette.fsh")

        @JvmField
        val OnyxSoftShadow = "onyx_soft_shadow"("shaders/gui/onyx_soft_shadow.fsh")
        val OpaiIslandShadow = "opai_island_shadow"("shaders/gui/opai_island_shadow.fsh")

        @JvmField
        val ArrayListGlow = "arraylist_glow"("shaders/gui/arraylist_glow.fsh")

        @JvmField
        val ArrayListBackdropBlurV = "arraylist_backdrop_blur_v"("shaders/arraylist/backdrop_blur_v.fsh")

        /**
         * Palette glow: chromatic dispersion plus an edge boost on top of the analytic gaussian.
         */
        @JvmField
        val ArrayListGlow2 = "arraylist_glow2"("shaders/gui/arraylist_glow2.fsh")

        @JvmField
        val ArrayListRow = "arraylist_row"("shaders/gui/arraylist_row.fsh")

        @JvmField
        val ArrayListSheen = "arraylist_sheen"("shaders/gui/arraylist_sheen.fsh")

        @JvmField
        val ArrayListRim = "arraylist_rim"("shaders/gui/arraylist_rim.fsh")

        @JvmField
        val GradientCircle = "gradient_circle"("shaders/circle/gradient_circle.fsh")

        @JvmField
        val HeartSDF = "heart_sdf"("shaders/heart/heart.fsh")

        @JvmField
        val MusicPanelShadow = "music_panel_shadow"("shaders/gui/music_panel_shadow.fsh")

        @JvmField
        val GuiCssBox = "gui_css_box"("shaders/gui/css_box.fsh")

        @JvmField
        val GuiRoundedTex = "gui_rounded_tex"("shaders/gui/rounded_tex.fsh")

        @JvmField
        val GuiInkReveal = "gui_ink_reveal"("shaders/gui/ink_reveal.fsh")

        @JvmField
        val WaterGround = "water_ground"("shaders/reflection/water_ground.fsh")

        @JvmField
        val MirrorGround = "mirror_ground"("shaders/reflection/mirror_ground.fsh")

        @JvmField
        val MirrorComposite = "mirror_composite"("shaders/reflection/mirror_composite.fsh")

        @JvmField
        val FloatingFogVolume = "floating_fog_volume"("shaders/floating_fog/floating_fog.fsh")

        @JvmField
        val FloatingFogComposite = "floating_fog_composite"("shaders/floating_fog/floating_fog_composite.fsh")

        @JvmField
        val FloatingFogParticle = "floating_fog_particle"("shaders/floating_fog/floating_fog_particle.fsh")

        @JvmField
        val HitAfterimageComposite = "hit_afterimage_composite"("shaders/afterimage/hit_afterimage_composite.fsh")

        @JvmField
        val ClickGuiGlassBlurH = "clickgui_glass_blur_h"("shaders/clickgui/glass_blur_h.fsh")

        @JvmField
        val ClickGuiGlassBlurV = "clickgui_glass_blur_v"("shaders/clickgui/glass_blur_v.fsh")

        @JvmField
        val ClickGuiLiquidGlass = "clickgui_liquid_glass"("shaders/clickgui/liquid_glass.fsh")

        @JvmField
        val LightPollutionGlass = "light_pollution_glass"("shaders/clickgui/light_pollution_glass.fsh")

        @JvmField
        val World3dHolo = "world3d_holo"("shaders/clickgui/world3d_holo.fsh")

        @JvmField
        val World3dHoloSolid = "world3d_holo_solid"("shaders/clickgui/world3d_holo_solid.fsh")

        @JvmField
        val AtmospherePost = "atmosphere_post"("shaders/atmosphere/atmosphere_post.fsh")

        @JvmField
        val MotionBlur = "motion_blur"("shaders/motionblur/accumulate.fsh")

        @JvmField
        val AtmosphereBlit = "atmosphere_blit"("shaders/atmosphere/atmosphere_blit.fsh")

    }

    private fun newShader(id: String, path: String): Identifier {
        val k = LiquidBounce.identifier("shader/$id")
        shaders.put(
            k,
            loadShaderSource(path),
        )?.let { error("Duplicated shader: $k") }
        return k
    }

    override fun getShader(identifier: Identifier, type: ShaderType): String? {
        if (type != this.type) return null
        return shaders[identifier]
    }

    override fun getInclude(id: Identifier): ShaderSource.CachedIncludeSource? = null

    override fun close() = Unit

    companion object Source : ShaderSource {
        override fun getShader(identifier: Identifier, shaderType: ShaderType): String? = when (shaderType) {
            ShaderType.VERTEX -> Vertex.getShader(identifier, shaderType)
            ShaderType.FRAGMENT -> Fragment.getShader(identifier, shaderType)
        }

        override fun getInclude(id: Identifier): ShaderSource.CachedIncludeSource? = null

        override fun close() = Unit
    }

}

private val LIQUIDBOUNCE_INCLUDE_REGEX = Regex("""^\s*#include\s+[<"]liquidbounce:([^>"]+)[>"]\s*$""")

private fun loadShaderSource(path: String, visited: Set<String> = emptySet()): String {
    check(path !in visited) { "Circular shader #include detected: $path" }
    val raw = LiquidBounce.resourceToString(path)
    if (!raw.contains("liquidbounce:")) {
        return raw
    }
    val nextVisited = visited + path
    return buildString(raw.length + 512) {
        val lines = raw.lines()
        lines.forEachIndexed { index, line ->
            val match = LIQUIDBOUNCE_INCLUDE_REGEX.matchEntire(line)
            if (match != null) {
                val subPath = match.groupValues[1].trimStart('/')
                val resolvedPath = if (subPath.startsWith("shaders/")) subPath else "shaders/$subPath"
                append(loadShaderSource(resolvedPath, nextVisited))
            } else {
                append(line)
            }
            if (index < lines.lastIndex) {
                append('\n')
            }
        }
    }
}
