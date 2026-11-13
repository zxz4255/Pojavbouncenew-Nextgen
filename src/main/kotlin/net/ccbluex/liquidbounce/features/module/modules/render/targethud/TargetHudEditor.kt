package net.ccbluex.liquidbounce.features.module.modules.render.targethud

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Material
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Scheme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Theme
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Typeface
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.UI
import net.ccbluex.liquidbounce.utils.client.mc
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.util.Locale
import kotlin.math.max

/**
 * Drag editor for the Material target card — a port of NeoRaven's `TargetHudPositionEditor`.
 *
 * <p>The card follows the target in game, so this screen pins it to the configured home position
 * (what the card uses when the target is off screen, and where dragging in chat puts it).
 * Drag to move, scroll to resize, `Esc` finishes.</p>
 */
class TargetHudEditor : Screen(Component.literal("TargetHUD")) {

    private companion object {
        private const val SIZE_STEP = 0.05F
        private const val MIN_SCALE = 0.5F
        private const val MAX_SCALE = 2.0F
    }

    private var dragging = false
    private var offsetX = 0.0F
    private var offsetY = 0.0F

    override fun isPauseScreen(): Boolean = false

    override fun init() {}

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val ui = UI(ctx)
        ui.begin()
        try {
            // Same dim scrim the original's `drawRect(0, 0, width, height, 0x77000000)` painted.
            ui.rect(0.0F, 0.0F, width.toFloat(), height.toFloat(), 0x77000000)
        } finally {
            ui.end()
        }

        TargetHud.render(ctx, ModuleTargetHud, true, delta)

        val scheme: Scheme = Theme.scheme()
        val overlay = UI(ctx)
        overlay.begin()
        try {
            overlay.text(
                Typeface.HEADLINE, "TargetHUD position", width / 2.0F, 26.0F,
                scheme.onSurface, UI.ALIGN_CENTER,
            )
            overlay.text(
                Typeface.BODY, "Drag to move  ·  Scroll to resize  ·  Esc when done",
                width / 2.0F, 46.0F, scheme.onSurfaceVariant, UI.ALIGN_CENTER,
            )

            val position = "Position " + ModuleTargetHud.roundedPosition() +
                "   ·   Size " + String.format(Locale.ROOT, "%.2fx", ModuleTargetHud.scale())
            val boxWidth = max(
                Typeface.BODY.width(position) + 40.0F,
                Typeface.BODY.width("Drag to move  ·  Scroll to resize  ·  Esc when done") + 40.0F,
            )
            overlay.rounded(
                width / 2.0F - boxWidth / 2.0F, height - 46.0F, boxWidth, 26.0F, 13.0F,
                scheme.surfaceContainerHigh,
            )
            overlay.text(
                Typeface.BODY, position, width / 2.0F, height - 33.0F,
                scheme.onSurfaceVariant, UI.ALIGN_CENTER,
            )
        } finally {
            overlay.end()
        }
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() != InputConstants.MOUSE_BUTTON_LEFT) return false
        val mouseX = click.x.toFloat()
        val mouseY = click.y.toFloat()
        if (TargetHud.inside(mouseX, mouseY)) {
            dragging = true
            offsetX = mouseX - TargetHud.left()
            offsetY = mouseY - TargetHud.top()
            return true
        }
        return false
    }

    override fun mouseDragged(click: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (!dragging) return false
        TargetHud.dragTo(click.x.toFloat() - offsetX, click.y.toFloat() - offsetY)
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            dragging = false
        }
        return false
    }

    override fun mouseScrolled(mx: Double, my: Double, horizontal: Double, vertical: Double): Boolean {
        if (vertical == 0.0) return false
        val next = Material.clamp(
            ModuleTargetHud.scale() + (if (vertical > 0.0) SIZE_STEP else -SIZE_STEP),
            MIN_SCALE,
            MAX_SCALE,
        )
        ModuleTargetHud.setScale(next)
        return true
    }

    override fun onClose() {
        mc.gui.setScreen(null)
    }

}
