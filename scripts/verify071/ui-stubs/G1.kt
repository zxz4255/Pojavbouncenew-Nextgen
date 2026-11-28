package net.ccbluex.liquidbounce.ui.clickgui.glass
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
object ClickGuiGlassSurface {
val isActive=false
    fun beginFrame(): Boolean { throw UnsupportedOperationException() }
    fun endFrame() { throw UnsupportedOperationException() }
    fun drawGlassBox(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rTL: Float,
        rTR: Float = rTL,
        rBR: Float = rTL,
        rBL: Float = rTL,
        cTL: Int,
        cTR: Int = cTL,
        cBR: Int = cTL,
        cBL: Int = cTL,
        layerOpacity: Float = 1f,
        refractionStrength: Float = ModuleClickGui.glassRefractionValue,
        rimColor: Int = 0,
        rimWidth: Float = 1.15f,
        shadowColor: Int = 0,
        shadowSpread: Float = 0f,
    ) { throw UnsupportedOperationException() }
    fun drawElevatedPanel(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rTL: Float,
        rTR: Float = rTL,
        rBR: Float = rTL,
        rBL: Float = rTL,
        densityBoost: Float = 1.0f,
        shadowSpread: Float = 14f,
        shadowAlpha: Float = 0.50f,
        accentRimMix: Float = 0.22f,
        rimAlpha: Float = 0.44f,
        refractionScale: Float = 1.0f,
    ) { throw UnsupportedOperationException() }
    fun drawAccentCapsule(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
        accentMix: Float = 0.85f,
        tintDensity: Float = 0.44f,
        layerOpacity: Float = 1f,
        rimAlpha: Float = 0.72f,
        shadowSpread: Float = 6f,
        shadowAlpha: Float = 0.28f,
    ) { throw UnsupportedOperationException() }
    fun drawSubtleWell(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rTL: Float = 6f,
        rTR: Float = rTL,
        rBR: Float = rTL,
        rBL: Float = rTL,
        darknessAlpha: Float = 0.24f,
        borderAlpha: Float = 0.10f,
    ) { throw UnsupportedOperationException() }
    fun drawGlowingAccentBar(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float = 1.8f,
    ) { throw UnsupportedOperationException() }
}
