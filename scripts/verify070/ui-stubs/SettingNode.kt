package net.ccbluex.liquidbounce.ui.clickgui.setting
import net.ccbluex.liquidbounce.config.types.group.Value
import net.minecraft.client.gui.GuiGraphicsExtractor
abstract class SettingNode(open val value: Value<*>) {
    var lastX: Float = 0f
    var lastY: Float = 0f
    var lastWidth: Float = 0f
    var lastHeight: Float = 0f

    var visibilityInitialized: Boolean = false
    var visibilityAnim: Float = 1f

    open fun stepAnimations(dtSeconds: Float) {}

    abstract fun measureHeight(width: Float): Float

    abstract fun render(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
        canvasWidth: Float,
    ): Float

    open fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean = false): Boolean = false
    open fun mouseDragged(mouseX: Float, mouseY: Float, button: Int): Boolean = false
    open fun mouseReleased(mouseX: Float, mouseY: Float, button: Int) {}
    open fun mouseScrolled(mouseX: Float, mouseY: Float, scrollY: Double): Boolean = false

    protected fun maybeShowTooltip(
        mouseX: Float,
        mouseY: Float,
        headerX: Float,
        headerY: Float,
        headerW: Float,
        headerH: Float,
        canvasWidth: Float,
    ) {
    }

    protected fun recordBounds(x: Float, y: Float, w: Float, h: Float) {
        lastX = x
        lastY = y
        lastWidth = w
        lastHeight = h
    }
}
