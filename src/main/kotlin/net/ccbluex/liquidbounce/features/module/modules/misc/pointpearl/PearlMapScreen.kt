/* This file is part of LiquidBounce. GPL-3.0-or-later, without warranty. */
package net.ccbluex.liquidbounce.features.module.modules.misc.pointpearl

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.features.module.modules.misc.ModulePointPearl
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Snapshot of the highest world-surface block, not a cave or an unloaded-chunk map. */
class PearlMapScreen : Screen(Component.literal("PointPearl")) {
    private val side = 65
    private val half = side / 2
    private val originX = floor(player.x).toInt()
    private val originZ = floor(player.z).toInt()
    private val capturedWorld = mc.level
    private val targets = arrayOfNulls<BlockPos>(side * side)
    private val colors = IntArray(side * side) { 0xff252a31.toInt() }
    private var selected: BlockPos? = null
    private var message = "Click ground to select; click CONFIRM to start. ESC cancels."

    init {
        val level = capturedWorld
        if (level != null) {
            for (z in 0 until side) for (x in 0 until side) {
                val wx = originX + x - half; val wz = originZ + z - half
                if (!level.hasChunkAt(wx, wz)) continue
                val y = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1
                if (y < level.minY) continue
                val pos = BlockPos(wx, y, wz)
                val state = level.getBlockState(pos)
                val color = state.getMapColor(level, pos).col
                colors[z * side + x] = if (color == 0) 0xff3b4148.toInt() else 0xff000000.toInt() or color
                if (ModulePointPearl.safeColumn(wx, y, wz)) targets[z * side + x] = pos
            }
        }
    }

    private val pixel: Int get() = max(1, min((width - 36) / side, (height - 90) / side))
    private val left: Int get() = (width - side * pixel) / 2
    private val top: Int get() = (height - side * pixel) / 2

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        ctx.fill(0, 0, width, height, 0xeE0c1118.toInt())
        ctx.centeredText(font, "PointPearl  |  top-down loaded terrain", width / 2, max(4, top - 30), 0xfff5f7fa.toInt())
        ctx.centeredText(font, "North = up   |   1 cell = 1 block   |   gray = unavailable", width / 2,
            max(16, top - 18), 0xffa9bdc8.toInt())
        for (z in 0 until side) for (x in 0 until side) {
            val x0 = left + x * pixel; val z0 = top + z * pixel
            ctx.fill(x0, z0, x0 + pixel, z0 + pixel, colors[z * side + x])
        }
        val px = left + half * pixel; val pz = top + half * pixel
        ctx.fill(px, pz, px + pixel, pz + pixel, 0xff48aaff.toInt())
        selected?.let {
            val sx = left + (it.x - originX + half) * pixel
            val sz = top + (it.z - originZ + half) * pixel
            ctx.fill(sx, sz, sx + pixel, sz + pixel, 0xffffd54f.toInt())
        }
        ctx.centeredText(font, message, width / 2, top + side * pixel + 7, 0xffe5e7eb.toInt())
        val bx = width / 2 - 52; val by = top + side * pixel + 20
        ctx.fill(bx, by, bx + 104, by + 18, if (selected == null) 0xff343b44.toInt() else 0xff246c6d.toInt())
        ctx.centeredText(font, "CONFIRM", width / 2, by + 5, 0xffffffff.toInt())
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(click, doubled)
        if (mc.level !== capturedWorld) { onClose(); return true }
        val bx = width / 2 - 52; val by = top + side * pixel + 20
        if (click.x >= bx && click.x < bx + 104 && click.y >= by && click.y < by + 18) {
            val pos = selected
            if (pos != null) ModulePointPearl.begin(pos) else message = "Select a safe, loaded surface first."
            return true
        }
        val x = floor((click.x - left) / pixel).toInt(); val z = floor((click.y - top) / pixel).toInt()
        if (x in 0 until side && z in 0 until side) {
            selected = targets[z * side + x]
            val p = selected
            message = if (p == null) "Not a safe full-block surface, or not loaded."
                else "Selected: ${p.x} ${p.y + 1} ${p.z}  |  CONFIRM to start"
            return true
        }
        return super.mouseClicked(click, doubled)
    }

    override fun isPauseScreen() = false
    override fun onClose() { mc.gui.setScreen(null); ModulePointPearl.cancelMap() }
    override fun removed() { super.removed(); ModulePointPearl.cancelMap() }
}
