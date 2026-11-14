/* GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.math.Axis
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawGradientCircle
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironment
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.render.withPush
import net.minecraft.client.Camera
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.fog.FogData
import net.minecraft.world.level.material.FogType
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** White distance fog plus a restrained pre-HUD white veil. Not a bloom or motion-blur shader. */
object ModuleWhiteAtmosphere : ClientModule("WhiteAtmosphere", ModuleCategories.RENDER) {
    private enum class FogStyle(override val tag: String) : Tagged { WHITE("White"), BLUE_GREY("BlueGrey"), SCATTERED("Scattered") }
    private val style by enumChoice("Style", FogStyle.WHITE)
    private val start by float("FogStart", 2f, 0f..96f)
    private val end by float("FogEnd", 38f, 4f..256f)
    private val brightness by float("Brightness", 0.14f, 0f..0.6f)
    private val scatterDensity by float("ScatterDensity", 0.7f, 0f..1.5f)

    /** Preserve vanilla water/lava/powder-snow fog rather than making hazards invisible. */
    fun applies(camera: Camera): Boolean = running && mc.level != null && camera.fluidInCamera == FogType.NONE
    fun appliesNow(): Boolean = applies(mc.gameRenderer.mainCamera())

    fun modifyFog(fog: FogData, camera: Camera) {
        if (!applies(camera)) return
        val viewFar = fog.renderDistanceEnd.coerceAtLeast(2f)
        // Scattered mode retains a faint distant haze instead of a solid white wall.
        // The original White and BlueGrey paths and defaults remain unchanged.
        val far = min(if (style == FogStyle.SCATTERED) max(end * 1.8f, 32f) else end, viewFar)
            .coerceAtLeast(2f)
        val near = if (style == FogStyle.SCATTERED) max(start, far * 0.65f).coerceIn(0f, far - 1f)
            else start.coerceIn(0f, far - 1f)
        if (style == FogStyle.BLUE_GREY) fog.color.set(0.35f, 0.44f, 0.53f, 1f)
        else fog.color.set(1f, 1f, 1f, 1f)
        fog.environmentalStart = near
        fog.environmentalEnd = far
        fog.renderDistanceStart = near
        fog.renderDistanceEnd = far
        fog.skyEnd = far
        fog.cloudEnd = far
    }

    // One deterministic, jittered fog patch in some world-space cells; no per-frame spawning
    // or central cluster. 3D cells keep patches spread horizontally and vertically as you move.
    // This uses the existing translucent, depth-tested gradient-circle world pipeline; it is
    // an approximation with camera-facing ellipses, not volumetric lighting or a blur effect.
    private fun cellRandom(x: Int, y: Int, z: Int, salt: Int): Float {
        var value = x * 73428767 xor y * 912931 xor z * 4382891 xor salt * 19349663
        value = (value xor (value ushr 16)) * 0x45d9f3b
        value = (value xor (value ushr 16)) * 0x45d9f3b
        value = value xor (value ushr 16)
        return (value ushr 8) / 16777215f
    }

    @Suppress("unused")
    private val scatteredFogRender = handler<WorldRenderEvent> { event ->
        if (style != FogStyle.SCATTERED || !applies(event.camera) || scatterDensity <= 0f) return@handler
        val pos = event.camera.position()
        val cellWidth = 20.0
        val cellHeight = 15.0
        val centerX = floor(pos.x / cellWidth).toInt()
        val centerY = floor(pos.y / cellHeight).toInt()
        val centerZ = floor(pos.z / cellWidth).toInt()
        event.renderEnvironment {
            for (x in centerX - 3..centerX + 3) for (y in centerY - 4..centerY + 4) {
                for (z in centerZ - 3..centerZ + 3) {
                    if (cellRandom(x, y, z, 0) > 0.45f) continue
                    val px = (x + 0.15 + cellRandom(x, y, z, 1) * 0.7) * cellWidth
                    val py = (y + 0.15 + cellRandom(x, y, z, 2) * 0.7) * cellHeight
                    val pz = (z + 0.15 + cellRandom(x, y, z, 3) * 0.7) * cellWidth
                    val dx = px - pos.x; val dy = py - pos.y; val dz = pz - pos.z
                    val distance = sqrt(dx * dx + dy * dy + dz * dz).toFloat()
                    // Fade at both ends so cells don't suddenly appear at the scan boundary.
                    val fadeIn = ((distance - 5f) / 8f).coerceIn(0f, 1f)
                    val fadeOut = ((52f - distance) / 12f).coerceIn(0f, 1f)
                    val alpha = (48f * scatterDensity * fadeIn * fadeOut).roundToInt().coerceIn(0, 96)
                    if (alpha <= 0) continue
                    val radius = 5f + cellRandom(x, y, z, 4) * 3.5f
                    withPositionRelativeToCamera(px, py, pz) {
                        poseStack.withPush {
                            rotate(event.camera.rotation())
                            // drawGradientCircle is an XZ plane; turn it into a camera-facing XY plane.
                            rotate(Axis.XP.rotationDegrees(90f))
                            scale(1.5f, 1f, 0.55f)
                            drawGradientCircle(radius, 0f, Color4b(255, 255, 255, 0),
                                Color4b(255, 255, 255, alpha), noDepthTest = false)
                        }
                    }
                }
            }
        }
    }

    /** Called at the start of HUD extraction, before camera overlays, crosshair and HUD. */
    fun extractVeil(context: GuiGraphicsExtractor) {
        if (!appliesNow()) return
        val alpha = (brightness.coerceIn(0f, 0.6f) * 255f).roundToInt()
        if (alpha == 0) return
        val tint = if (style == FogStyle.BLUE_GREY) 0x00597087 else 0x00ffffff
        context.fill(0, 0, context.guiWidth(), context.guiHeight(), (alpha shl 24) or tint)
        // Keep it below every vanilla/custom HUD element despite GUI batching.
        context.nextStratum()
    }
}
