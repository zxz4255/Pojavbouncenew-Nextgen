package net.ccbluex.liquidbounce.features.module.modules.render.targethud

import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Theme
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironment
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.client.mc
import kotlin.math.roundToInt

/**
 * The Material 3 Expressive target card, ported from NeoRaven's `TargetHudModule`.
 *
 * <p>NeoRaven's HUD module was a thin shell around `gui/onyx/hud/TargetHud` (which lives here in
 * [TargetHud], ported 1:1) plus a world-space ESP box around the target and a position editor screen.
 * The same three pieces are here:</p>
 *
 * <ul>
 *   <li>[TargetHud] — the card: avatar, name, distance chip, health bar, armour, follow/linger
 *       behaviour;</li>
 *   <li>[TargetHudEditor] — the drag/scroll editor the `EditPosition` switch opens;</li>
 *   <li>`RenderESP` — the primary-coloured outline box drawn around the target in the world.</li>
 * </ul>
 *
 * <p>Onyx itself is the copy that shipped with the music player port
 * (`…modules.misc.music.onyx`); it is module-agnostic, so the card reuses it instead of carrying a
 * second 3,000-line renderer. That also means the card follows the same palette as the music window,
 * which is what a Material client should do.</p>
 */
object ModuleTargetHud : ClientModule(
    "TargetHud",
    ModuleCategories.RENDER,
    aliases = listOf("TargetHUD", "OnyxTargetHud"),
) {

    private val scaleValue = float("Scale", 1.0F, 0.5F..2.0F)
    private val positionXValue = float("PositionX", 50.0F, 0.0F..100.0F, "%")
    private val positionYValue = float("PositionY", 35.0F, 0.0F..100.0F, "%")
    private val lingerValue = float("Linger", 0.3F, 0.0F..2.0F, "s")

    private val frostedGlass = boolean("FrostedGlass", true)
    private val showHealthValue = boolean("ShowHealth", true)
    private val showDistanceValue = boolean("ShowDistance", true)
    private val showArmorValue = boolean("ShowArmor", true)
    private val renderEsp = boolean("RenderESP", false)

    /**
     * The original's "Edit position" button.
     *
     * <p>LiquidBounce has no button value, so this is a switch that opens [TargetHudEditor] and
     * immediately flips itself back — same one-shot behaviour, no state left behind.</p>
     */
    @Suppress("unused")
    private val editPosition by boolean("EditPosition", false).onChange { value ->
        if (value) {
            mc.execute { mc.gui.setScreen(TargetHudEditor()) }
        }
        false
    }

    /** Keeps [TargetHudLayer] initialised from client start — see the note on that object. */
    @Suppress("unused")
    private val layer = TargetHudLayer

    override fun onEnabled() {
        TargetHud.reset()
        TargetHudLayer.reset()
    }

    override fun onDisabled() {
        // Keep the last frame so the card can fade out; the next enable resets the state.
    }

    // --------------------------------------------------------------- settings

    fun scale(): Float = scaleValue.get()

    fun positionX(): Float = positionXValue.get()

    fun positionY(): Float = positionYValue.get()

    /** How long the card keeps the last target (and its position) before fading out. */
    fun linger(): Float = lingerValue.get()

    fun frosted(): Boolean = frostedGlass.get()

    fun showHealth(): Boolean = showHealthValue.get()

    fun showDistance(): Boolean = showDistanceValue.get()

    fun showArmor(): Boolean = showArmorValue.get()

    fun setPosition(percentX: Float, percentY: Float) {
        positionXValue.set(percentX)
        positionYValue.set(percentY)
    }

    fun setScale(value: Float) {
        scaleValue.set(value)
    }

    // -------------------------------------------------------------------- ESP

    @Suppress("unused")
    private val worldRender = handler<WorldRenderEvent> { event ->
        if (!renderEsp.get()) return@handler
        val target = TargetHudLayer.currentTarget() ?: return@handler
        val interpolated = target.getPosition(event.partialTicks)
        val box = target.boundingBox.move(interpolated.subtract(target.position()))
        val outline = Color4b(Theme.scheme().primary)
        event.renderEnvironment {
            withPositionRelativeToCamera(0.0, 0.0, 0.0) {
                drawBox(box, null, outline, noDepthTest = false)
            }
        }
    }

    /** Percentage of the free space → GUI pixels, used by the editor's readout. */
    fun roundedPosition(): String = "${positionX().roundToInt()}% / ${positionY().roundToInt()}%"

}
