package net.ccbluex.liquidbounce.ui.clickgui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.gui.render.TextureSetup
import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.group.Value
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.ui.common.*
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.features.module.*
import net.ccbluex.liquidbounce.render.AbstractFontRenderer
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.input.InputBind
class HudEditorTabView {
    fun render(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        guiMouseX: Float,
        guiMouseY: Float,
        dtSeconds: Float,
        shiftHeld: Boolean,
    ) { throw UnsupportedOperationException() }
    fun mouseClicked(guiMouseX: Float, guiMouseY: Float, button: Int, shiftHeld: Boolean): Boolean { throw UnsupportedOperationException() }
    fun mouseDragged(guiMouseX: Float, guiMouseY: Float, button: Int, shiftHeld: Boolean): Boolean { throw UnsupportedOperationException() }
    fun mouseReleased(guiMouseX: Float, guiMouseY: Float, button: Int) { throw UnsupportedOperationException() }
    fun mouseScrolled(guiMouseX: Float, guiMouseY: Float, scrollY: Double): Boolean { throw UnsupportedOperationException() }
}
