/*
 * CubismRenderer —— Live2D 网格绘制。
 *
 * 26.3 的 GUI 网格使用真正的 TRIANGLES 拓扑。
 * 不能把每个三角形伪装成带重复顶点的 QUAD，否则边缘的退化三角形和批处理
 * 会造成细缝/拼接伪影。
 *
 * 每个 drawable 独立提交一个 GUI element，因此：
 *   - 多张纹理各自正确绑定（Haru 这类双纹理模型不会串图）；
 *   - 绘制顺序严格按 MOC3 drawOrder 排序后的 renderOrder；
 *   - 逐 drawable 的 alpha 精确生效。
 *
 * 注意：当前 GUI 管线没有 Cubism 专用的离屏 mask pass；不能用矩形 scissor 冒充
 * 真实的 alpha mask（多 drawable mask 尤其会被裁错）。masksEnabled 保留作配置兼容，
 * 真正的 Cubism clipping mask 仍需独立 mask render pass / shader。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/CubismRenderer.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import com.mojang.renderpearl.api.pipeline.BlendFactor
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.blaze3d.vertex.VertexConsumer
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.textureSetup
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Live2D-specific pipelines are declared here instead of requiring edits to the shared
 * ClientRenderPipelines.GUI object. This keeps the module self-contained and avoids unresolved
 * references when only the Live2D Kotlin files are copied into the client.
 * Unique pipeline identifiers prevent collisions with optional shared-pipeline patches.
 */
private object Live2DRenderPipelines {
    val TrianglesNoCull = ClientRenderPipelines.newPipeline("live2d/tex_triangles_no_cull") {
        withSnippet(RenderPipelines.GUI_TEXTURED_SNIPPET)
        withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        withCull(false)
    }

    val TrianglesAdditiveNoCull = ClientRenderPipelines.newPipeline("live2d/tex_triangles_additive_no_cull") {
        withSnippet(RenderPipelines.GUI_TEXTURED_SNIPPET)
        withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        withColorTargetState(ColorTargetState(BlendFunction.ADDITIVE))
        withCull(false)
    }

    val TrianglesMultiplicativeNoCull = ClientRenderPipelines.newPipeline("live2d/tex_triangles_multiplicative_no_cull") {
        withSnippet(RenderPipelines.GUI_TEXTURED_SNIPPET)
        withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        withColorTargetState(
            ColorTargetState(BlendFunction(BlendFactor.DST_COLOR, BlendFactor.ONE_MINUS_SRC_ALPHA))
        )
        withCull(false)
    }
}

class CubismRenderer(
    private val model: Live2DModel,
    private val textures: List<Identifier>,
) {

    /**
     * moc3 的 UV 原点在左下（V 向上，与顶点 Y 同向）。
     * MC 的 GUI 纹理原点在左上（V 向下），所以必须翻 V，否则贴图在正确轮廓内上下颠倒。
     */
    var flipV: Boolean = false
    var masksEnabled: Boolean = true

    /** 按 drawable id 隐藏。 */
    private val hidden = HashSet<Int>()

    /** 上一帧实际提交的 drawable 数，供状态显示。 */
    var lastRenderedDrawables: Int = 0
        private set

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    /** 调试用：最近一次变换结果。 */
    val lastScale: Float get() = scale
    val lastOffsetX: Float get() = offsetX
    val lastOffsetY: Float get() = offsetY
    val lastBoundsText: String get() = frameBounds?.let {
        "(${it.left()},${it.top()},${it.width}x${it.height})"
    } ?: "null"

    /** 当前帧模型在屏幕上的整体矩形；每次提交都必须带 bounds，否则元素会被 GuiRenderState 丢弃。 */
    private var frameBounds: ScreenRectangle? = null

    /**
     * 屏幕空间 mask 栅格：true 表示该格中心落在 mask 几何内。
     *
     * Noir 的「头发脸后」(Part10: ArtMesh34/35/72/73) 被
     * 「脸」(ArtMesh33/51/71, Part8) 与「头发投影」(Part9) 遮罩。
     * 这些 mesh 的包围盒全部是 (0.03~0.96)，矩形求交收不窄，
     * 所以必须按真实三角形几何做点内测试。
     */
    private class MaskGrid(
        val originX: Float,
        val originY: Float,
        val cell: Float,
        val cols: Int,
        val rows: Int,
        val bits: BooleanArray,
    ) {
        fun contains(px: Float, py: Float): Boolean {
            if (!px.isFinite() || !py.isFinite()) return false
            val cx = ((px - originX) / cell).toInt()
            val cy = ((py - originY) / cell).toInt()
            if (cx < 0 || cy < 0 || cx >= cols || cy >= rows) return false
            return bits[cy * cols + cx]
        }
    }

    /** 每个 drawable 的 mask 交集栅格；null 表示无 mask 约束。 */
    private var maskGrids: Array<MaskGrid?> = emptyArray()

    /** 每个 drawable 的屏幕包围盒 [left, top, right, bottom]。 */
    private var boundsCache: Array<FloatArray?> = emptyArray()

    fun setHiddenDrawables(ids: Collection<String>) {
        hidden.clear()
        if (ids.isEmpty()) return
        for (index in 0 until model.drawableCount) {
            val id = model.core.drawableIds.getOrNull(index) ?: continue
            if (ids.contains(id)) hidden.add(index)
        }
    }

    /**
     * 在 [x, y, width, height] 区域内等比居中绘制。
     *
     * @param globalOpacity 外部整体不透明度（模块配置 × motion 的 Model/Opacity 曲线）
     */
    fun render(
        graphics: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        globalOpacity: Float = 1f,
    ) {
        lastRenderedDrawables = 0
        if (width <= 0f || height <= 0f || textures.isEmpty()) return

        computeTransform(x, y, width, height)

        // bounds 必须非空，否则 GuiRenderState.findAppropriateNode 会直接丢弃该元素。
        frameBounds = toRect(x, y, x + width, y + height)
        if (frameBounds == null) return

        val opacity = (globalOpacity * model.modelOpacity).coerceIn(0f, 1f)
        if (opacity <= 0.001f) return

        // mask 剔除需要屏幕空间三角形，每帧构建一次。
        if (masksEnabled) rebuildMaskTriangles()

        for (rank in model.renderOrder.indices) {
            val index = model.renderOrder[rank]
            if (index in hidden) continue
            if (!model.drawableVisible(index)) continue

            val drawableAlpha = model.drawableOpacity(index) * opacity
            if (drawableAlpha <= 0.001f) continue

            val textureIndex = model.drawableTextureIndex(index)
            if (textureIndex < 0 || textureIndex >= textures.size) continue

            val texture = textures[textureIndex]
            val setup = runCatching { mc.textureManager.getTexture(texture).textureSetup }.getOrElse {
                logger.warn("Live2D 纹理不可用：$texture", it)
                continue
            }

            val clip: ScreenRectangle? = null

            val alpha = (drawableAlpha * 255f).toInt().coerceIn(0, 255)
            if (alpha <= 0) continue

            submit(graphics, index, setup, alpha, clip, frameBounds!!, maskGrids.getOrNull(index))
            lastRenderedDrawables++
        }
    }

    /* --------------------------- 单 drawable --------------------------- */

    private fun submit(
        graphics: GuiGraphicsExtractor,
        index: Int,
        setup: TextureSetup,
        alpha: Int,
        clip: ScreenRectangle?,
        bounds: ScreenRectangle,
        grid: MaskGrid?,
    ) {
        val positions = model.drawablePositions(index)
        val uvs = model.drawableUvs(index)
        val indices = model.drawableIndices(index)
        val vertexCount = model.drawableVertexCount(index)

        if (vertexCount <= 0 || indices.size < 3) return
        if (positions.size < vertexCount * 2 || uvs.size < vertexCount * 2) return

        graphics.drawCustomElement(
            // 使用真正的三角形拓扑并关闭背面剔除，避免退化 QUAD 引起拼接伪影。
            pipeline = when {
                model.drawableAdditive(index) -> Live2DRenderPipelines.TrianglesAdditiveNoCull
                model.drawableMultiplicative(index) -> Live2DRenderPipelines.TrianglesMultiplicativeNoCull
                else -> Live2DRenderPipelines.TrianglesNoCull
            },
            textureSetup = setup,
            scissorArea = clip,
            bounds = bounds,
        ) { pose ->
            var i = 0
            while (i + 2 < indices.size) {
                val a = indices[i]
                val b = indices[i + 1]
                val c = indices[i + 2]
                i += 3
                if (a >= vertexCount || b >= vertexCount || c >= vertexCount) continue

                if (a < 0 || b < 0 || c < 0) continue
                if (!validVertex(positions, uvs, a) ||
                    !validVertex(positions, uvs, b) ||
                    !validVertex(positions, uvs, c)) continue

                // 三角形重心落在 mask 之外 → 整块跳过（等价于 alpha mask 剔除）。
                if (grid != null && !triangleInMask(grid, positions, a, b, c)) continue

                emit(this, pose, positions, uvs, a, alpha)
                emit(this, pose, positions, uvs, b, alpha)
                emit(this, pose, positions, uvs, c, alpha)
            }
        }
    }

    /**
     * 三角形是否落在 mask 内。
     *
     * 旧逻辑「任一顶点命中即保留」在转头/移动时会让大三角头发片
     * 仅擦到脸部 mask 边缘就整片画出，造成脸部小穿模。
     * 改为多点采样 + 多数通过，并要求重心必须在 mask 内：
     * 既挡住穿模，又避免边界完全碎裂成洞。
     */
    private fun triangleInMask(
        grid: MaskGrid,
        positions: FloatArray,
        a: Int,
        b: Int,
        c: Int,
    ): Boolean {
        val ax = offsetX + positions[a * 2] * scale
        val ay = offsetY - positions[a * 2 + 1] * scale
        val bx = offsetX + positions[b * 2] * scale
        val by = offsetY - positions[b * 2 + 1] * scale
        val cx = offsetX + positions[c * 2] * scale
        val cy = offsetY - positions[c * 2 + 1] * scale
        val mx = (ax + bx + cx) / 3f
        val my = (ay + by + cy) / 3f
        // 重心不在 mask 内：整三角视为穿出，直接丢弃
        if (!grid.contains(mx, my)) return false

        var hits = 1 // centroid already inside
        if (grid.contains(ax, ay)) hits++
        if (grid.contains(bx, by)) hits++
        if (grid.contains(cx, cy)) hits++
        // 边中点，收紧跨边界大三角
        if (grid.contains((ax + bx) * 0.5f, (ay + by) * 0.5f)) hits++
        if (grid.contains((bx + cx) * 0.5f, (by + cy) * 0.5f)) hits++
        if (grid.contains((cx + ax) * 0.5f, (cy + ay) * 0.5f)) hits++
        // 7 点中至少 4 点在 mask 内才保留
        return hits >= 4
    }

    private fun validVertex(positions: FloatArray, uvs: FloatArray, vertex: Int): Boolean {
        val offset = vertex * 2
        return offset >= 0 && offset + 1 < positions.size && offset + 1 < uvs.size &&
            positions[offset].isFinite() && positions[offset + 1].isFinite() &&
            uvs[offset].isFinite() && uvs[offset + 1].isFinite()
    }

    private fun emit(
        consumer: VertexConsumer,
        pose: Matrix3x2f,
        positions: FloatArray,
        uvs: FloatArray,
        vertex: Int,
        alpha: Int,
    ) {
        val px = offsetX + positions[vertex * 2] * scale
        // Cubism vertex Y is model-space (positive upward), while Minecraft GUI Y is
        // screen-space (positive downward). Invert only the final geometry mapping;
        // keep UVs untouched so mesh/texture correspondence and stitching stay intact.
        val py = offsetY - positions[vertex * 2 + 1] * scale

        var u = uvs[vertex * 2]
        var v = uvs[vertex * 2 + 1]
        if (flipV) v = 1f - v

        consumer.addVertexWith2DPose(pose, px, py)
            .setColor(255, 255, 255, alpha)
            .setUv(u, v)
    }

    /* ------------------------------ 遮罩 ------------------------------ */

    /**
     * 每帧构建 mask 栅格。对每个带 mask 的 drawable：
     * 把它所有 mask drawable 的屏幕空间三角形光栅化成位图，再逐格取交集。
     *
     * 这是「几何近似」而非离屏 alpha pass：mask 的 alpha 曲线被栅格化，
     * 精度由 MASK_CELL 决定。好处是能真正贴合脸部轮廓裁掉越界的头发，
     * 而不是像矩形 scissor 那样整块保留或整块丢弃。
     */
    private fun rebuildMaskTriangles() {
        val count = model.drawableCount
        if (maskGrids.size != count) maskGrids = arrayOfNulls(count)
        if (boundsCache.size != count) boundsCache = arrayOfNulls(count)

        for (i in 0 until count) boundsCache[i] = screenBounds(i)

        for (i in 0 until count) {
            val maskCount = model.drawableMaskCount(i)
            if (maskCount <= 0) {
                maskGrids[i] = null
                continue
            }
            val self = boundsCache[i]
            if (self == null) {
                maskGrids[i] = null
                continue
            }

            val cell = MASK_CELL
            val cols = (((self[2] - self[0]) / cell).toInt() + 1).coerceIn(1, MAX_MASK_CELLS)
            val rows = (((self[3] - self[1]) / cell).toInt() + 1).coerceIn(1, MAX_MASK_CELLS)
            val bits = BooleanArray(cols * rows) { true } // 从「全允许」开始取交集

            var anyMask = false
            for (slot in 0 until maskCount) {
                val maskIndex = model.drawableMask(i, slot)
                if (maskIndex !in 0 until count) continue
                anyMask = true
                val maskBits = BooleanArray(cols * rows)
                rasterize(maskIndex, self[0], self[1], cell, cols, rows, maskBits)
                for (k in bits.indices) if (!maskBits[k]) bits[k] = false
            }

            if (!anyMask) {
                maskGrids[i] = null
                continue
            }

            var anyFalse = false
            for (k in bits.indices) {
                if (!bits[k]) {
                    anyFalse = true
                    break
                }
            }
            if (anyFalse) {
                // 1 格膨胀：减少严格判定后脸缘细缝，同时不把大片头发放回来
                dilateMaskBits(bits, cols, rows)
                maskGrids[i] = MaskGrid(self[0], self[1], cell, cols, rows, bits)
            } else {
                maskGrids[i] = null
            }
        }
    }

    /** 对 true 区域做一次 4-邻域膨胀。 */
    private fun dilateMaskBits(bits: BooleanArray, cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        val src = bits.copyOf()
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val i = y * cols + x
                if (src[i]) continue
                val left = x > 0 && src[i - 1]
                val right = x + 1 < cols && src[i + 1]
                val up = y > 0 && src[i - cols]
                val down = y + 1 < rows && src[i + cols]
                if (left || right || up || down) bits[i] = true
            }
        }
    }

    /** 把 drawable 的所有三角形光栅化到 bits（格中心落在任一三角形内 → true）。 */
    private fun rasterize(
        drawable: Int,
        originX: Float,
        originY: Float,
        cell: Float,
        cols: Int,
        rows: Int,
        bits: BooleanArray,
    ) {
        val positions = model.drawablePositions(drawable)
        val indices = model.drawableIndices(drawable)
        val vertexCount = model.drawableVertexCount(drawable)
        if (vertexCount <= 0 || indices.size < 3) return
        if (positions.size < vertexCount * 2) return

        var i = 0
        while (i + 2 < indices.size) {
            val a = indices[i]
            val b = indices[i + 1]
            val c = indices[i + 2]
            i += 3
            if (a < 0 || b < 0 || c < 0) continue
            if (a >= vertexCount || b >= vertexCount || c >= vertexCount) continue

            val ax = offsetX + positions[a * 2] * scale
            val ay = offsetY - positions[a * 2 + 1] * scale
            val bx = offsetX + positions[b * 2] * scale
            val by = offsetY - positions[b * 2 + 1] * scale
            val cx = offsetX + positions[c * 2] * scale
            val cy = offsetY - positions[c * 2 + 1] * scale
            if (!ax.isFinite() || !ay.isFinite()) continue
            if (!bx.isFinite() || !by.isFinite()) continue
            if (!cx.isFinite() || !cy.isFinite()) continue

            var x0 = ((minOf(ax, bx, cx) - originX) / cell).toInt() - 1
            var x1 = ((maxOf(ax, bx, cx) - originX) / cell).toInt() + 1
            var y0 = ((minOf(ay, by, cy) - originY) / cell).toInt() - 1
            var y1 = ((maxOf(ay, by, cy) - originY) / cell).toInt() + 1
            if (x0 < 0) x0 = 0
            if (y0 < 0) y0 = 0
            if (x1 >= cols) x1 = cols - 1
            if (y1 >= rows) y1 = rows - 1
            if (x1 < x0 || y1 < y0) continue

            val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (area == 0f) continue
            val inv = 1f / area

            for (gy in y0..y1) {
                val py = originY + (gy + 0.5f) * cell
                for (gx in x0..x1) {
                    val px = originX + (gx + 0.5f) * cell
                    val w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) * inv
                    val w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) * inv
                    val w2 = 1f - w0 - w1
                    if (w0 >= 0f && w1 >= 0f && w2 >= 0f) bits[gy * cols + gx] = true
                }
            }
        }
    }

    /** 屏幕空间包围盒 [left, top, right, bottom]。 */
    private fun screenBounds(index: Int): FloatArray? {
        val positions = model.drawablePositions(index)
        val vertexCount = model.drawableVertexCount(index)
        if (vertexCount <= 0 || positions.size < vertexCount * 2) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (v in 0 until vertexCount) {
            val px = offsetX + positions[v * 2] * scale
            val py = offsetY - positions[v * 2 + 1] * scale
            if (!px.isFinite() || !py.isFinite()) continue
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
        }
        return if (minX == Float.POSITIVE_INFINITY) null else floatArrayOf(minX, minY, maxX, maxY)
    }

    /**
     * 转成 ScreenRectangle，宽高至少 1 —— GuiRenderer 遇到 0 尺寸 scissor/bounds 会直接抛异常。
     */
    private fun toRect(left: Float, top: Float, right: Float, bottom: Float): ScreenRectangle? {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        val l = floor(minOf(left, right)).toInt()
        val t = floor(minOf(top, bottom)).toInt()
        val w = ceil(abs(right - left)).toInt().coerceAtLeast(1)
        val h = ceil(abs(bottom - top)).toInt().coerceAtLeast(1)
        return ScreenRectangle(l, t, w, h)
    }

    /* ---------------------------- 坐标变换 ---------------------------- */

    private fun computeTransform(x: Float, y: Float, width: Float, height: Float) {
        val modelWidth = max(0.001f, model.boundsMaxX - model.boundsMinX)
        val modelHeight = max(0.001f, model.boundsMaxY - model.boundsMinY)

        scale = min(width / modelWidth, height / modelHeight)
        val drawnWidth = modelWidth * scale
        val drawnHeight = modelHeight * scale

        offsetX = x + (width - drawnWidth) * 0.5f - model.boundsMinX * scale
        // Map the model's maximum Y to the top edge and minimum Y to the bottom edge.
        // This corrects an upside-down model without changing mesh order, UVs or deformers.
        offsetY = y + (height - drawnHeight) * 0.5f + model.boundsMaxY * scale
    }

    private companion object {
        /**
         * mask 栅格单元边长（GUI 像素）。
         *
         * 3px 在 260px 高的模型上是 ~1.2% 精度：足够贴合脸部轮廓，
         * 单 drawable 栅格规模约 (90×90) 格，每帧光栅化成本可接受。
         */
        const val MASK_CELL = 2f

        /** 单边最大格数，防止极端缩放时分配过大数组。 */
        const val MAX_MASK_CELLS = 256
    }
}