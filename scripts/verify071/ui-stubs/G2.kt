package net.ccbluex.liquidbounce.ui.clickgui.neumorph
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
object ClickGuiNeumorphSurface {
val isActive=false; val bevelRimDark=0; val bevelRimLight=0; val backdropColor=0
 fun <T> withHudPassSuspended(block: () -> T): T = block()
    fun beginFrame(): Boolean { throw UnsupportedOperationException() }
    fun endFrame() { throw UnsupportedOperationException() }
    fun drawExtrudedPanel(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rTL: Float,
        rTR: Float = rTL,
        rBR: Float = rTL,
        rBL: Float = rTL,
        depthScale: Float = 1f,
    ) { throw UnsupportedOperationException() }
    fun drawInsetWell(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rTL: Float,
        rTR: Float = rTL,
        rBR: Float = rTL,
        rBL: Float = rTL,
    ) { throw UnsupportedOperationException() }
    fun drawTactilePill(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
        enabledMix: Float,
        hoverMix: Float,
    ) { throw UnsupportedOperationException() }
    fun drawRaisedKnob(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        size: Float,
        accentMix: Float = 0f,
    ) { throw UnsupportedOperationException() }
}
