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

            submit(graphics, index, setup, alpha, clip, frameBounds!!)
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

                emit(this, pose, positions, uvs, a, alpha)
                emit(this, pose, positions, uvs, b, alpha)
                emit(this, pose, positions, uvs, c, alpha)
            }
        }
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
}