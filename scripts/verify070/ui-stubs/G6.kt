package net.ccbluex.liquidbounce.ui.clickgui.setting
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
object SettingRenderer {
    fun allOptionChildrenOf(group: ValueGroup, exclude: Value<*>? = null): List<Value<*>> { throw UnsupportedOperationException() }
    fun visibleChildrenOf(group: ValueGroup): List<Value<*>> { throw UnsupportedOperationException() }
    fun hasAnyActiveChildren(group: ValueGroup, exclude: Value<*>? = null): Boolean { throw UnsupportedOperationException() }
    fun nodeFor(value: Value<*>): SettingNode { throw UnsupportedOperationException() }
    fun stepChildrenAnimations(allChildren: List<Value<*>>, dtSeconds: Float) { throw UnsupportedOperationException() }
    fun stepGroupAnimations(group: ValueGroup, dtSeconds: Float, exclude: Value<*>? = null) { throw UnsupportedOperationException() }
    fun measureGroupHeight(
        group: ValueGroup,
        width: Float,
        gapY: Float = 4f,
        exclude: Value<*>? = null,
    ): Float { throw UnsupportedOperationException() }
    fun measureListHeight(values: List<Value<*>>, width: Float, gapY: Float = 4f): Float { throw UnsupportedOperationException() }
    fun renderGroup(
        ctx: GuiGraphicsExtractor,
        group: ValueGroup,
        x: Float,
        y: Float,
        width: Float,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
        canvasWidth: Float,
        gapY: Float = 4f,
        exclude: Value<*>? = null,
    ): Float { throw UnsupportedOperationException() }
    fun renderList(
        ctx: GuiGraphicsExtractor,
        values: List<Value<*>>,
        x: Float,
        y: Float,
        width: Float,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
        canvasWidth: Float,
        gapY: Float = 4f,
    ): Float { throw UnsupportedOperationException() }
    fun mouseClickedList(
        values: List<Value<*>>,
        mouseX: Float,
        mouseY: Float,
        button: Int,
        shiftHeld: Boolean = false,
    ): Boolean { throw UnsupportedOperationException() }
    fun mouseDraggedList(values: List<Value<*>>, mouseX: Float, mouseY: Float, button: Int): Boolean { throw UnsupportedOperationException() }
    fun mouseReleasedList(values: List<Value<*>>, mouseX: Float, mouseY: Float, button: Int) { throw UnsupportedOperationException() }
    fun mouseScrolledList(values: List<Value<*>>, mouseX: Float, mouseY: Float, scrollY: Double): Boolean { throw UnsupportedOperationException() }
}
