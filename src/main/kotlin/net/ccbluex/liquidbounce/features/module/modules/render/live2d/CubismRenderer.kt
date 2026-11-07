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
 * ---------------------------- 裁剪遮罩 ----------------------------
 * 当前 GUI 管线没有 Cubism 专用的离屏 mask pass，所以遮罩在 CPU 端按屏幕空间
 * 几何近似。fix-20 修正了以下导致「移动时脸部穿模」的问题：
 *
 *   1. 多 mask 合并语义：Cubism 把同一 clipping context 的所有 mask drawable
 *      画进同一张 mask buffer，取的是 **并集**，不是交集。旧实现对每个 mask
 *      做 AND，脸部多个 mesh 求交后几乎为空，触发「全灭则不施加 mask」保护，
 *      于是头发完全不被裁 → 盖在脸上。
 *   2. 整三角形取舍：旧实现「任一采样点命中就整块保留」，跨界三角形被整块画出，
 *      移动时不同三角形轮流跨界 → 边缘一闪一闪地穿模。现在改为
 *      覆盖率 + 边界三角形自适应细分 + 逐顶点 alpha，边界平滑移动。
 *   3. 栅格原点：旧实现用 drawable 自身包围盒最小值当原点，网格随网格形变滑动，
 *      采样相位每帧变化。现在锚定到模型画框原点，整体平移时覆盖率图案不变。
 *   4. 每个 drawable 各自一张栅格 → 相邻 drawable 的格子相位不同，边界对不齐。
 *      现在整帧共用一套栅格，并按 mask 集合（clipping context）缓存合成结果。
 *
 * 这仍然是几何近似而非真正的离屏 alpha pass，但边界不再有整块跳变。
 *
 * 另外两处配合修复见 CubismModel.sortRenderOrder（绘制顺序 epsilon 并列判定）
 * 与 Live2DHudRenderer（真实时间基准 + 亚像素定位）。
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

    /** 上一帧实际施加了裁剪的 drawable 数；为 0 且模型带 mask 时说明遮罩没生效。 */
    var lastMaskedDrawables: Int = 0
        private set

    /** 上一帧的 mask 栅格格距（GUI 像素），供调试日志核对精度。 */
    val lastMaskCell: Float get() = gridCell

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
     * 未取整的画框原点。mask 栅格锚定在这里而不是 drawable 自身包围盒，
     * 这样模型整体平移时网格与几何同步移动，覆盖率图案不变，不会因采样相位漂移而闪烁。
     */
    private var frameOriginX = 0f
    private var frameOriginY = 0f
    private var frameWidthHint = 0f
    private var frameHeightHint = 0f

    /**
     * 屏幕空间 mask 覆盖率栅格。coverage ∈ [0,1]，由每格 2×2 超采样得到，
     * 查询时再做双线性插值，因此边界是连续过渡而不是 0/1 跳变。
     */
    private class MaskGrid(
        val originX: Float,
        val originY: Float,
        val cell: Float,
        val cols: Int,
        val rows: Int,
        val coverage: FloatArray,
    ) {
        private fun sample(cx: Int, cy: Int): Float {
            val x = if (cx < 0) 0 else if (cx >= cols) cols - 1 else cx
            val y = if (cy < 0) 0 else if (cy >= rows) rows - 1 else cy
            return coverage[y * cols + x]
        }

        /** 双线性采样格中心处的覆盖率。 */
        fun coverageAt(px: Float, py: Float): Float {
            if (!px.isFinite() || !py.isFinite()) return 0f
            val fx = (px - originX) / cell - 0.5f
            val fy = (py - originY) / cell - 0.5f
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val tx = (fx - x0).coerceIn(0f, 1f)
            val ty = (fy - y0).coerceIn(0f, 1f)
            val c00 = sample(x0, y0)
            val c10 = sample(x0 + 1, y0)
            val c01 = sample(x0, y0 + 1)
            val c11 = sample(x0 + 1, y0 + 1)
            val top = c00 + (c10 - c00) * tx
            val bottom = c01 + (c11 - c01) * tx
            return (top + (bottom - top) * ty).coerceIn(0f, 1f)
        }
    }

    /** 每个 drawable 的 mask 覆盖率栅格；null 表示无 mask 约束。 */
    private var maskGrids: Array<MaskGrid?> = emptyArray()

    /**
     * 整帧共用的栅格几何。所有 mask 与所有被裁 drawable 都采样同一套格子，
     * 因此相邻 drawable 的裁剪边界严格一致，不会各自偏移半格而互相错位。
     */
    private var gridOriginX = 0f
    private var gridOriginY = 0f
    private var gridCell = MASK_CELL
    private var gridCols = 0
    private var gridRows = 0

    /** 每个 mask drawable 每帧只光栅化一次，供所有引用它的 drawable 复用。 */
    private var maskBits: Array<ByteArray?> = emptyArray()

    /** maskBits 的帧戳；不等于当前帧的条目视为未初始化，必须重新光栅化。 */
    private var maskStamp: IntArray = IntArray(0)
    private var frameStamp = 0

    /**
     * 覆盖率缓冲池。同一组 mask（Cubism 的 clipping context）只算一次，
     * 多个 drawable 共享同一张覆盖率图，既省 CPU 也保证边界完全一致。
     */
    private val coveragePools = arrayOf(ArrayList<FloatArray>(), ArrayList<FloatArray>())
    private var coveragePoolUsed = 0
    private var coveragePoolIndex = 0
    private val maskSetCache = HashMap<String, MaskGrid?>()

    /**
     * 单次提交的遮罩 + 顶点预算。
     *
     * 必须是 **每次 submit 独立的对象**：drawCustomElement 的顶点 lambda 是延迟执行的，
     * 若把预算放在 renderer 字段上，所有 drawable 的 lambda 会互相扣减同一个计数器，
     * 后面的 drawable 会被误判为「预算耗尽」而丢失边界细分。
     */
    private class EmitState(val grid: MaskGrid?, var budget: Int)

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
        lastMaskedDrawables = 0
        if (width <= 0f || height <= 0f || textures.isEmpty()) return

        computeTransform(x, y, width, height)

        // bounds 必须非空，否则 GuiRenderState.findAppropriateNode 会直接丢弃该元素。
        frameBounds = toRect(x, y, x + width, y + height)
        if (frameBounds == null) return

        frameOriginX = x
        frameOriginY = y
        frameWidthHint = width
        frameHeightHint = height

        val opacity = (globalOpacity * model.modelOpacity).coerceIn(0f, 1f)
        if (opacity <= 0.001f) return

        // mask 剔除需要屏幕空间三角形，每帧构建一次。
        if (masksEnabled) rebuildMasks(opacity) else clearMasks()

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

            val grid = maskGrids.getOrNull(index)
            submit(graphics, index, setup, alpha, clip, frameBounds!!, grid)
            lastRenderedDrawables++
            if (grid != null) lastMaskedDrawables++
        }
    }

    private fun clearMasks() {
        for (i in maskGrids.indices) maskGrids[i] = null
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

        // 原始顶点数的 16 倍上限：足够细分边界，又能挡住病态模型。
        val state = EmitState(grid, max(MAX_DRAWABLE_VERTICES, indices.size * 16))

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

                // 先变换到屏幕空间，之后的覆盖率采样、细分、发射全部在屏幕空间进行。
                emitTriangle(
                    this, pose,
                    offsetX + positions[a * 2] * scale, offsetY - positions[a * 2 + 1] * scale,
                    uvs[a * 2], uvs[a * 2 + 1],
                    offsetX + positions[b * 2] * scale, offsetY - positions[b * 2 + 1] * scale,
                    uvs[b * 2], uvs[b * 2 + 1],
                    offsetX + positions[c * 2] * scale, offsetY - positions[c * 2 + 1] * scale,
                    uvs[c * 2], uvs[c * 2 + 1],
                    alpha, state, 0,
                )
            }
        }
    }

    /**
     * 提交一个屏幕空间三角形，必要时按 mask 覆盖率细分。
     *
     * - 三个顶点与重心都完全在 mask 内 → 原样提交（内部保持完全不透明）。
     * - 都完全在 mask 外 → 丢弃。
     * - 跨界 → 递归四分裂到 [MIN_LEAF_EDGE] 或 [MAX_SUBDIVIDE]，
     *   叶子按逐顶点覆盖率调制 alpha，得到随几何连续移动的软边，而不是整块跳变。
     */
    private fun emitTriangle(
        consumer: VertexConsumer,
        pose: Matrix3x2f,
        ax: Float, ay: Float, au: Float, av: Float,
        bx: Float, by: Float, bu: Float, bv: Float,
        cx: Float, cy: Float, cu: Float, cv: Float,
        alpha: Int,
        state: EmitState,
        depth: Int,
    ) {
        val grid = state.grid
        if (grid == null) {
            state.budget -= 3
            emitVertex(consumer, pose, ax, ay, au, av, alpha)
            emitVertex(consumer, pose, bx, by, bu, bv, alpha)
            emitVertex(consumer, pose, cx, cy, cu, cv, alpha)
            return
        }

        val ca = grid.coverageAt(ax, ay)
        val cb = grid.coverageAt(bx, by)
        val cc = grid.coverageAt(cx, cy)
        val cg = grid.coverageAt((ax + bx + cx) / 3f, (ay + by + cy) / 3f)

        val lo = min(min(ca, cb), min(cc, cg))
        val hi = max(max(ca, cb), max(cc, cg))

        if (hi <= COVERAGE_EPS) return
        if (lo >= 1f - COVERAGE_EPS) {
            state.budget -= 3
            emitVertex(consumer, pose, ax, ay, au, av, alpha)
            emitVertex(consumer, pose, bx, by, bu, bv, alpha)
            emitVertex(consumer, pose, cx, cy, cu, cv, alpha)
            return
        }

        val longest = max(
            max(abs(ax - bx) + abs(ay - by), abs(bx - cx) + abs(by - cy)),
            abs(cx - ax) + abs(cy - ay),
        )

        if (depth >= MAX_SUBDIVIDE || longest <= MIN_LEAF_EDGE || state.budget < 12) {
            val aa = (alpha * ca).toInt().coerceIn(0, 255)
            val ab = (alpha * cb).toInt().coerceIn(0, 255)
            val ac = (alpha * cc).toInt().coerceIn(0, 255)
            if (aa <= 0 && ab <= 0 && ac <= 0) return
            state.budget -= 3
            emitVertex(consumer, pose, ax, ay, au, av, aa)
            emitVertex(consumer, pose, bx, by, bu, bv, ab)
            emitVertex(consumer, pose, cx, cy, cu, cv, ac)
            return
        }

        val abx = (ax + bx) * 0.5f
        val aby = (ay + by) * 0.5f
        val abu = (au + bu) * 0.5f
        val abv = (av + bv) * 0.5f
        val bcx = (bx + cx) * 0.5f
        val bcy = (by + cy) * 0.5f
        val bcu = (bu + cu) * 0.5f
        val bcv = (bv + cv) * 0.5f
        val cax = (cx + ax) * 0.5f
        val cay = (cy + ay) * 0.5f
        val cau = (cu + au) * 0.5f
        val cav = (cv + av) * 0.5f
        val next = depth + 1

        emitTriangle(consumer, pose, ax, ay, au, av, abx, aby, abu, abv, cax, cay, cau, cav, alpha, state, next)
        emitTriangle(consumer, pose, abx, aby, abu, abv, bx, by, bu, bv, bcx, bcy, bcu, bcv, alpha, state, next)
        emitTriangle(consumer, pose, cax, cay, cau, cav, bcx, bcy, bcu, bcv, cx, cy, cu, cv, alpha, state, next)
        emitTriangle(consumer, pose, abx, aby, abu, abv, bcx, bcy, bcu, bcv, cax, cay, cau, cav, alpha, state, next)
    }

    private fun validVertex(positions: FloatArray, uvs: FloatArray, vertex: Int): Boolean {
        val offset = vertex * 2
        return offset >= 0 && offset + 1 < positions.size && offset + 1 < uvs.size &&
            positions[offset].isFinite() && positions[offset + 1].isFinite() &&
            uvs[offset].isFinite() && uvs[offset + 1].isFinite()
    }

    /** [px] / [py] 已是屏幕坐标；UV 保持 moc3 原值，仅按 flipV 决定是否翻 V。 */
    private fun emitVertex(
        consumer: VertexConsumer,
        pose: Matrix3x2f,
        px: Float,
        py: Float,
        u: Float,
        v: Float,
        alpha: Int,
    ) {
        // 绝不能因为 alpha==0 就跳过顶点：TRIANGLES 拓扑下每 3 个顶点一组，
        // 少发一个会让后面所有三角形错位，整张网格变成乱线。
        // 全透明的三角形在 emitTriangle 里整块（3 个顶点一起）丢弃。
        consumer.addVertexWith2DPose(pose, px, py)
            .setColor(255, 255, 255, alpha.coerceIn(0, 255))
            .setUv(u, if (flipV) 1f - v else v)
    }

    /* ------------------------------ 遮罩 ------------------------------ */

    /**
     * 每帧构建 mask 覆盖率栅格。
     *
     * 对每个带 mask 的 drawable：把它 **所有** mask drawable 的屏幕空间三角形
     * 光栅化到同一张超采样位图（并集，和 Cubism 的 mask buffer 语义一致），
     * 再把每格 4 个子样本折算成 [0,1] 覆盖率；inverted mask 在最后统一取反。
     *
     * 仍然是几何近似而非离屏 alpha pass，但：
     *   - 并集语义正确，脸部多 mesh 遮罩不会互相求交后失效；
     *   - 覆盖率连续，配合三角形细分后边界平滑；
     *   - 栅格锚定画框原点，平移时不抖。
     */
    private fun rebuildMasks(modelOpacity: Float) {
        val count = model.drawableCount
        if (maskGrids.size != count) {
            maskGrids = arrayOfNulls(count)
            maskBits = arrayOfNulls(count)
            maskStamp = IntArray(count)
        }
        frameStamp++
        if (frameStamp == Int.MAX_VALUE) {
            frameStamp = 1
            maskStamp.fill(0)
        }

        // 整帧共用一套栅格：锚定画框原点，格距在需要时自动放大以守住上限。
        val frameWidth = abs(frameWidthHint).coerceAtLeast(1f)
        val frameHeight = abs(frameHeightHint).coerceAtLeast(1f)
        gridCell = max(MASK_CELL, max(frameWidth, frameHeight) / (MAX_MASK_CELLS - 4))
        // 四周各留一格余量：动画可能让网格略微超出静态包围盒。
        gridOriginX = frameOriginX - gridCell
        gridOriginY = frameOriginY - gridCell
        gridCols = (ceil(frameWidth / gridCell).toInt() + 3).coerceIn(1, MAX_MASK_CELLS)
        gridRows = (ceil(frameHeight / gridCell).toInt() + 3).coerceIn(1, MAX_MASK_CELLS)
        val size = gridCols * gridRows

        // 双缓冲：drawCustomElement 的顶点 lambda 是延迟执行的，
        // 下一帧重建时不能覆写上一帧 lambda 仍可能持有的覆盖率数组。
        coveragePoolIndex = 1 - coveragePoolIndex
        coveragePoolUsed = 0
        maskSetCache.clear()
        for (i in 0 until count) maskGrids[i] = null

        val opacity = modelOpacity.coerceIn(0f, 1f)

        // 第一遍：只光栅化真正被引用、且自身有几何的 mask drawable，每个一次。
        for (i in 0 until count) {
            if (!willDraw(i, opacity)) continue
            val maskCount = model.drawableMaskCount(i)
            if (maskCount <= 0) continue
            for (slot in 0 until maskCount) {
                val maskIndex = model.drawableMask(i, slot)
                if (maskIndex !in 0 until count) continue
                if (maskStamp[maskIndex] == frameStamp) continue
                if (model.drawableVertexCount(maskIndex) <= 0) continue

                var bits = maskBits[maskIndex]
                if (bits == null || bits.size != size) {
                    bits = ByteArray(size)
                    maskBits[maskIndex] = bits
                } else {
                    bits.fill(0)
                }
                maskStamp[maskIndex] = frameStamp
                rasterizeCoverage(maskIndex, gridOriginX, gridOriginY, gridCell, gridCols, gridRows, bits)
            }
        }

        // 第二遍：按「mask 集合 + 是否反相」分组，每组只合成一次覆盖率。
        // Cubism 本身就是按 clipping context 分组的，这里保持同样的语义。
        for (i in 0 until count) {
            if (!willDraw(i, opacity)) continue
            val maskCount = model.drawableMaskCount(i)
            if (maskCount <= 0) continue

            val members = ArrayList<Int>(maskCount)
            for (slot in 0 until maskCount) {
                val maskIndex = model.drawableMask(i, slot)
                if (maskIndex !in 0 until count) continue
                if (maskStamp[maskIndex] != frameStamp) continue
                if (maskBits[maskIndex]?.size != size) continue
                if (!members.contains(maskIndex)) members.add(maskIndex)
            }
            if (members.isEmpty()) continue
            members.sort()

            val inverted = model.drawableInvertedMask(i)
            val key = (if (inverted) "i:" else "n:") + members.joinToString(",")
            if (maskSetCache.containsKey(key)) {
                maskGrids[i] = maskSetCache[key]
                continue
            }

            val coverage = obtainCoverage(size)
            var anyVisible = false
            var anyClipped = false
            for (k in 0 until size) {
                var union = 0
                for (m in members) {
                    union = union or (maskBits[m]?.get(k)?.toInt() ?: 0)
                }
                val value = union.countOneBits() * (1f / SAMPLE_COUNT)
                val c = if (inverted) 1f - value else value
                coverage[k] = c
                if (c > COVERAGE_EPS) anyVisible = true
                if (c < 1f - COVERAGE_EPS) anyClipped = true
            }

            // 全不透明 → 无需裁剪。全透明通常是遮罩解析异常，
            // 保守地不施加裁剪，避免整块模型消失（沿用 fix-19 的防「改空」保护）。
            val grid = if (!anyClipped || !anyVisible) {
                null
            } else {
                MaskGrid(gridOriginX, gridOriginY, gridCell, gridCols, gridRows, coverage)
            }
            maskSetCache[key] = grid
            maskGrids[i] = grid
        }
    }

    /** 从池里取一块覆盖率缓冲；每帧从头复用，稳定后不再分配。 */
    private fun obtainCoverage(size: Int): FloatArray {
        val pool = coveragePools[coveragePoolIndex]
        while (pool.size <= coveragePoolUsed) pool.add(FloatArray(size))
        val buffer = pool[coveragePoolUsed++]
        if (buffer.size != size) {
            val fresh = FloatArray(size)
            coveragePools[coveragePoolIndex][coveragePoolUsed - 1] = fresh
            return fresh
        }
        return buffer
    }

    /** 与主绘制循环一致的可见性判定，用来跳过不会提交的 drawable 的遮罩计算。 */
    private fun willDraw(index: Int, modelOpacity: Float): Boolean {
        if (index in hidden) return false
        if (!model.drawableVisible(index)) return false
        if (model.drawableOpacity(index) * modelOpacity <= 0.001f) return false
        val textureIndex = model.drawableTextureIndex(index)
        return textureIndex >= 0 && textureIndex < textures.size
    }

    /**
     * 把 drawable 的所有三角形光栅化到超采样位图。
     * 每格 2×2 个子样本，命中则置对应 bit；重复/重叠三角形自然取并集。
     */
    private fun rasterizeCoverage(
        drawable: Int,
        originX: Float,
        originY: Float,
        cell: Float,
        cols: Int,
        rows: Int,
        bits: ByteArray,
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

            var x0 = floor((minOf(ax, bx, cx) - originX) / cell).toInt()
            var x1 = floor((maxOf(ax, bx, cx) - originX) / cell).toInt()
            var y0 = floor((minOf(ay, by, cy) - originY) / cell).toInt()
            var y1 = floor((maxOf(ay, by, cy) - originY) / cell).toInt()
            if (x0 < 0) x0 = 0
            if (y0 < 0) y0 = 0
            if (x1 >= cols) x1 = cols - 1
            if (y1 >= rows) y1 = rows - 1
            if (x1 < x0 || y1 < y0) continue

            val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (area == 0f) continue
            val inv = 1f / area

            for (gy in y0..y1) {
                val baseY = originY + gy * cell
                for (gx in x0..x1) {
                    val baseX = originX + gx * cell
                    var mask = 0
                    for (sy in 0 until SUBSAMPLES) {
                        val py = baseY + (sy + 0.5f) * (cell / SUBSAMPLES)
                        for (sx in 0 until SUBSAMPLES) {
                            val px = baseX + (sx + 0.5f) * (cell / SUBSAMPLES)
                            val w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) * inv
                            val w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) * inv
                            val w2 = 1f - w0 - w1
                            if (w0 >= 0f && w1 >= 0f && w2 >= 0f) {
                                mask = mask or (1 shl (sy * SUBSAMPLES + sx))
                            }
                        }
                    }
                    if (mask != 0) {
                        val slot = gy * cols + gx
                        bits[slot] = (bits[slot].toInt() or mask).toByte()
                    }
                }
            }
        }
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
         * mask 栅格单元边长（GUI 像素）。每格再做 2×2 超采样，
         * 等效精度 1.5px；查询时双线性插值，边界连续。
         */
        const val MASK_CELL = 3f

        /** 每边超采样数；SAMPLE_COUNT = SUBSAMPLES²，必须 ≤ 8 才能存进一个 byte。 */
        const val SUBSAMPLES = 2
        const val SAMPLE_COUNT = SUBSAMPLES * SUBSAMPLES

        /** 单边最大格数，防止极端缩放时分配过大数组。 */
        const val MAX_MASK_CELLS = 256

        /** 覆盖率视为 0 / 1 的阈值。 */
        const val COVERAGE_EPS = 1f / 255f

        /** 跨界三角形最大细分层数（3 层 = 最多 64 个叶子）。 */
        const val MAX_SUBDIVIDE = 3

        /**
         * 细分到此屏幕尺寸就停止，改用逐顶点 alpha。
         * 取值与 MASK_CELL 同量级：再细也没有更多覆盖率信息，只是白烧顶点。
         */
        const val MIN_LEAF_EDGE = 3.5f

        /** 单 drawable 顶点下限预算。 */
        const val MAX_DRAWABLE_VERTICES = 24_000
    }
}
