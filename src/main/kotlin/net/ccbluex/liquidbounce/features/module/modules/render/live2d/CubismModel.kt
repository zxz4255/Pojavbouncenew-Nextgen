/*
 * CubismModel —— 基于官方 Android Live2DCubismCore.so 的原生 MOC3 模型实例与求解器。
 *
 * 旧版纯 Kotlin MOC3 容器解析（CubismMoc3）与纯 Kotlin 变形求解器已移除，
 * 全部交由 libLive2DCubismCore.so（csmReviveMocInPlace / csmInitializeModelInPlace / csmUpdateModel）
 * 在原生层完成，完整支持 Cubism 3.0 ~ 5.3（MOC3 v1 ~ v6，含 Blend Shape、Offscreen、旋转/曲面变形器等）。
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.Pointer
import java.io.Closeable
import java.io.File
import kotlin.math.abs
import kotlin.math.max

class CubismModel private constructor(
    private val core: NativeCubismCore.Handles,
    private var mocBufferAddr: Long,
    private val mocPtr: Long,
    private var modelBufferAddr: Long,
    private val modelPtr: Long,
) : Closeable {

    @Volatile
    private var closed = false

    /* ----------------------------- counts & IDs ----------------------------- */

    val parameterCount: Int = max(0, core.getParameterCount(modelPtr))
    val partCount: Int = max(0, core.getPartCount(modelPtr))
    val drawableCount: Int = max(0, core.getDrawableCount(modelPtr))
    private val offscreenCount: Int = max(0, core.getOffscreenCount(modelPtr))

    val parameterIds: List<String> = readIdList(core.getParameterIds(modelPtr), parameterCount)
    val partIds: List<String> = readIdList(core.getPartIds(modelPtr), partCount)
    val drawableIds: List<String> = readIdList(core.getDrawableIds(modelPtr), drawableCount)

    private val paramIndexById: Map<String, Int> = buildIndexMap(parameterIds)
    private val partIndexById: Map<String, Int> = buildIndexMap(partIds)

    fun indexOfParameter(id: String): Int = paramIndexById[id] ?: -1
    fun indexOfPart(id: String): Int = partIndexById[id] ?: -1

    /* ------------------------------ parameters ------------------------------ */

    private val paramValuesPtr: Long = core.getParameterValues(modelPtr)
    private val paramMin = FloatArray(parameterCount)
    private val paramMax = FloatArray(parameterCount)
    private val paramDefault = FloatArray(parameterCount)
    private val parameterValues = FloatArray(parameterCount)
    private val paramKeyValuesCache: Array<FloatArray>

    init {
        if (parameterCount > 0) {
            val minPtr = core.getParameterMinimumValues(modelPtr)
            val maxPtr = core.getParameterMaximumValues(modelPtr)
            val defPtr = core.getParameterDefaultValues(modelPtr)
            if (minPtr != 0L) MemoryUtil.memCopy(minPtr, paramMin)
            if (maxPtr != 0L) MemoryUtil.memCopy(maxPtr, paramMax)
            if (defPtr != 0L) MemoryUtil.memCopy(defPtr, paramDefault)
            if (paramValuesPtr != 0L) {
                MemoryUtil.memCopy(paramValuesPtr, parameterValues)
            } else {
                paramDefault.copyInto(parameterValues)
            }
        }

        val keyCountsPtr = core.getParameterKeyCounts(modelPtr)
        val keyValuesPtr = core.getParameterKeyValues(modelPtr)
        paramKeyValuesCache = Array(parameterCount) { index ->
            if (keyCountsPtr == 0L || keyValuesPtr == 0L) {
                FloatArray(0)
            } else {
                val count = MemoryUtil.memGetInt(keyCountsPtr + index.toLong() * 4L)
                val valuesAddr = MemoryUtil.memGetAddress(
                    keyValuesPtr + index.toLong() * Pointer.POINTER_SIZE
                )
                if (count <= 0 || valuesAddr == 0L) {
                    FloatArray(0)
                } else {
                    val raw = FloatArray(count)
                    MemoryUtil.memCopy(valuesAddr, raw)
                    raw.sort()
                    dedupeSorted(raw)
                }
            }
        }
    }

    fun parameterMin(index: Int): Float =
        if (index in 0 until parameterCount) paramMin[index] else 0f

    fun parameterMax(index: Int): Float =
        if (index in 0 until parameterCount) paramMax[index] else 0f

    fun parameterDefault(index: Int): Float =
        if (index in 0 until parameterCount) paramDefault[index] else 0f

    fun parameterValueAt(index: Int): Float =
        if (index in 0 until parameterCount) parameterValues[index] else 0f

    fun setParameterValue(index: Int, value: Float) {
        if (index !in 0 until parameterCount || closed) return
        val sanitized = if (value.isFinite()) value else paramDefault[index]
        parameterValues[index] = sanitized
        if (paramValuesPtr != 0L) {
            MemoryUtil.memPutFloat(paramValuesPtr + index.toLong() * 4L, sanitized)
        }
    }

    fun resetParameters() {
        if (closed) return
        for (i in 0 until parameterCount) {
            val def = paramDefault[i]
            parameterValues[i] = def
            if (paramValuesPtr != 0L) {
                MemoryUtil.memPutFloat(paramValuesPtr + i.toLong() * 4L, def)
            }
        }
    }

    fun parameterKeyValues(index: Int): FloatArray =
        if (index in 0 until parameterCount) paramKeyValuesCache[index].copyOf() else FloatArray(0)

    /* -------------------------------- parts -------------------------------- */

    private val partOpacitiesPtr: Long = core.getPartOpacities(modelPtr)
    private val offscreenOpacitiesPtr: Long = core.getOffscreenOpacities(modelPtr)
    private val partParent = IntArray(partCount) { -1 }
    private val partOffscreenIndex = IntArray(partCount) { -1 }

    val partOpacity = FloatArray(partCount) { 1f }
    private val partOpacityInput = FloatArray(partCount) { 1f }
    private val manualPartVisibility = BooleanArray(partCount) { true }

    init {
        if (partCount > 0) {
            val parentPtr = core.getPartParentPartIndices(modelPtr)
            if (parentPtr != 0L) MemoryUtil.memCopy(parentPtr, partParent)
            val offIdxPtr = core.getPartOffscreenIndices(modelPtr)
            if (offIdxPtr != 0L) MemoryUtil.memCopy(offIdxPtr, partOffscreenIndex)
        }
    }

    fun isPartVisibleByUser(index: Int): Boolean =
        index in 0 until partCount && manualPartVisibility[index]

    fun setPartVisibleByUser(index: Int, visible: Boolean) {
        if (index in 0 until partCount) manualPartVisibility[index] = visible
    }

    fun resetPartVisibilityByUser() {
        manualPartVisibility.fill(true)
    }

    fun resetPartOpacityInput() {
        partOpacityInput.fill(1f)
    }

    fun setPartOpacityInput(index: Int, value: Float) {
        if (index in 0 until partCount) partOpacityInput[index] = value
    }

    /* ------------------------------ drawables ------------------------------ */

    private val dynamicFlagsPtr: Long = core.getDrawableDynamicFlags(modelPtr)
    private val renderOrdersPtr: Long = core.getRenderOrders(modelPtr)
    private val drawableOpacitiesPtr: Long = core.getDrawableOpacities(modelPtr)
    private val vertexPositionsTablePtr: Long = core.getDrawableVertexPositions(modelPtr)

    private val amConstantFlags = IntArray(drawableCount)
    private val amTextureIndex = IntArray(drawableCount)
    private val amParentPart = IntArray(drawableCount) { -1 }
    private val amVertexCount = IntArray(drawableCount)
    private val amIdxLen = IntArray(drawableCount)
    private val amMaskLen = IntArray(drawableCount)
    private val amMasks: Array<IntArray>
    private val uvCache: Array<FloatArray>
    private val indexCache: Array<IntArray>
    private val amPositions: Array<FloatArray>
    private val amVisible = BooleanArray(drawableCount)

    val renderOrder = IntArray(drawableCount) { it }
    val drawableOpacity = FloatArray(drawableCount)

    private val totalRenderObjects = drawableCount + offscreenCount
    private val rawRenderOrders = IntArray(max(drawableCount, totalRenderObjects))
    private val sortedRenderSlots = IntArray(max(1, totalRenderObjects)) { -1 }
    private val offscreenOpacityScratch = FloatArray(offscreenCount) { 1f }

    init {
        val constFlagsPtr = core.getDrawableConstantFlags(modelPtr)
        if (constFlagsPtr != 0L) {
            for (d in 0 until drawableCount) {
                amConstantFlags[d] = MemoryUtil.memGetByte(constFlagsPtr + d.toLong()).toInt() and 0xFF
            }
        }
        if (drawableCount > 0) {
            val texPtr = core.getDrawableTextureIndices(modelPtr)
            if (texPtr != 0L) MemoryUtil.memCopy(texPtr, amTextureIndex)

            val parentPtr = core.getDrawableParentPartIndices(modelPtr)
            if (parentPtr != 0L) MemoryUtil.memCopy(parentPtr, amParentPart)

            val vCountPtr = core.getDrawableVertexCounts(modelPtr)
            if (vCountPtr != 0L) MemoryUtil.memCopy(vCountPtr, amVertexCount)

            val iCountPtr = core.getDrawableIndexCounts(modelPtr)
            if (iCountPtr != 0L) MemoryUtil.memCopy(iCountPtr, amIdxLen)

            val mCountPtr = core.getDrawableMaskCounts(modelPtr)
            if (mCountPtr != 0L) MemoryUtil.memCopy(mCountPtr, amMaskLen)
        }

        val masksTablePtr = core.getDrawableMasks(modelPtr)
        amMasks = Array(drawableCount) { d ->
            val count = max(0, amMaskLen[d])
            if (count == 0 || masksTablePtr == 0L) {
                IntArray(0)
            } else {
                val arrAddr = MemoryUtil.memGetAddress(
                    masksTablePtr + d.toLong() * Pointer.POINTER_SIZE
                )
                if (arrAddr == 0L) {
                    amMaskLen[d] = 0
                    IntArray(0)
                } else {
                    val masks = IntArray(count)
                    MemoryUtil.memCopy(arrAddr, masks)
                    masks
                }
            }
        }

        // csmReviveMocInPlace 内部把 UV 原点翻到了左下角（OpenGL 纹理坐标系），
        // 官方着色器 VertShaderSrc.vert 会做 v = 1.0 - a_texCoord.y 还原到 PNG 左上原点。
        // MC NativeImage 同样以左上角为 (0, 0)，在此直接还原为左上原点以匹配 CubismRenderer。
        val uvsTablePtr = core.getDrawableVertexUvs(modelPtr)
        uvCache = Array(drawableCount) { d ->
            val vCount = max(0, amVertexCount[d])
            val out = FloatArray(vCount * 2)
            if (vCount > 0 && uvsTablePtr != 0L) {
                val uvAddr = MemoryUtil.memGetAddress(
                    uvsTablePtr + d.toLong() * Pointer.POINTER_SIZE
                )
                if (uvAddr != 0L) {
                    MemoryUtil.memCopy(uvAddr, out)
                    var k = 1
                    while (k < out.size) {
                        out[k] = 1f - out[k]
                        k += 2
                    }
                }
            }
            out
        }

        val indicesTablePtr = core.getDrawableIndices(modelPtr)
        indexCache = Array(drawableCount) { d ->
            val iCount = max(0, amIdxLen[d])
            val out = IntArray(iCount)
            if (iCount > 0 && indicesTablePtr != 0L) {
                val idxAddr = MemoryUtil.memGetAddress(
                    indicesTablePtr + d.toLong() * Pointer.POINTER_SIZE
                )
                if (idxAddr != 0L) {
                    for (k in 0 until iCount) {
                        out[k] = MemoryUtil.memGetShort(idxAddr + k.toLong() * 2L).toInt() and 0xFFFF
                    }
                }
            }
            out
        }

        amPositions = Array(drawableCount) { d ->
            FloatArray(max(0, amVertexCount[d]) * 2)
        }
    }

    fun drawableUvs(index: Int): FloatArray = uvCache[index]
    fun drawableIndices(index: Int): IntArray = indexCache[index]
    fun drawableVertexCount(index: Int): Int = amVertexCount[index]
    fun drawableIndexCount(index: Int): Int = amIdxLen[index]
    fun drawableTextureIndex(index: Int): Int = amTextureIndex[index]
    fun drawableConstantFlags(index: Int): Int = amConstantFlags[index]
    fun drawablePositions(index: Int): FloatArray = amPositions[index]
    fun drawableParentPartIndex(index: Int): Int = amParentPart[index]
    fun drawableMaskCount(index: Int): Int = amMaskLen[index]

    fun drawableMask(index: Int, slot: Int): Int {
        val masks = amMasks[index]
        return if (slot in masks.indices) masks[slot] else -1
    }

    fun drawableVisible(index: Int): Boolean =
        index in 0 until drawableCount && amVisible[index]

    fun drawableAdditive(index: Int): Boolean =
        (amConstantFlags[index] and NativeCubismCore.CSM_BLEND_ADDITIVE) != 0

    fun drawableMultiplicative(index: Int): Boolean =
        (amConstantFlags[index] and NativeCubismCore.CSM_BLEND_MULTIPLICATIVE) != 0

    fun drawableInvertedMask(index: Int): Boolean =
        (amConstantFlags[index] and NativeCubismCore.CSM_IS_INVERTED_MASK) != 0

    /* -------------------------------- canvas & bounds -------------------------------- */

    val canvasWidth: Float
    val canvasHeight: Float
    val originX: Float
    val originY: Float

    init {
        val scratch = MemoryUtil.nmemAllocChecked(20L)
        try {
            MemoryUtil.memPutFloat(scratch, 0f)
            MemoryUtil.memPutFloat(scratch + 4L, 0f)
            MemoryUtil.memPutFloat(scratch + 8L, 0f)
            MemoryUtil.memPutFloat(scratch + 12L, 0f)
            MemoryUtil.memPutFloat(scratch + 16L, 1f)
            core.readCanvasInfo(modelPtr, scratch, scratch + 8L, scratch + 16L)
            val sizeX = MemoryUtil.memGetFloat(scratch)
            val sizeY = MemoryUtil.memGetFloat(scratch + 4L)
            val origX = MemoryUtil.memGetFloat(scratch + 8L)
            val origY = MemoryUtil.memGetFloat(scratch + 12L)
            val ppu = max(1f, MemoryUtil.memGetFloat(scratch + 16L))
            canvasWidth = sizeX / ppu
            canvasHeight = sizeY / ppu
            originX = origX / ppu
            originY = origY / ppu
        } finally {
            MemoryUtil.nmemFree(scratch)
        }
    }

    val bboxAspect: Float
        get() = if (canvasHeight > 1e-4f) canvasWidth / canvasHeight else 1f

    var boundsMinX = 0f
        private set
    var boundsMinY = 0f
        private set
    var boundsMaxX = 0f
        private set
    var boundsMaxY = 0f
        private set

    private var boundsInitialized = false

    /* ================================= update ================================= */

    fun update() {
        if (closed) return

        // 1. 将本帧部件输入不透明度与用户部件显隐写入 csmModel
        for (i in 0 until partCount) {
            val rawInput = if (manualPartVisibility[i]) partOpacityInput[i].coerceIn(0f, 1f) else 0f
            if (partOpacitiesPtr != 0L) {
                MemoryUtil.memPutFloat(partOpacitiesPtr + i.toLong() * 4L, rawInput)
            }
            var effective = rawInput
            val parent = partParent[i]
            if (parent in 0 until partCount) {
                effective *= partOpacity[parent]
            }
            partOpacity[i] = effective
        }

        // 2. 调用官方原生求解器更新模型
        core.resetDrawableDynamicFlags(modelPtr)
        core.updateModel(modelPtr)

        // 3. 回读原生层钳制/循环后的参数值
        if (parameterCount > 0 && paramValuesPtr != 0L) {
            MemoryUtil.memCopy(paramValuesPtr, parameterValues)
        }

        // 4. 反转渲染顺序：csmGetRenderOrders 返回每个对象的绘制槽位 (0 .. drawableCount + offscreenCount - 1)
        if (drawableCount > 0 && renderOrdersPtr != 0L) {
            MemoryUtil.memCopy(renderOrdersPtr, rawRenderOrders)
            sortedRenderSlots.fill(-1)
            for (d in 0 until drawableCount) {
                val slot = rawRenderOrders[d]
                if (slot in sortedRenderSlots.indices) {
                    sortedRenderSlots[slot] = d
                }
            }
            var cursor = 0
            for (slot in sortedRenderSlots.indices) {
                val d = sortedRenderSlots[slot]
                if (d >= 0 && cursor < drawableCount) {
                    renderOrder[cursor++] = d
                }
            }
            if (cursor < drawableCount) {
                // 防御性兜底：若槽位异常未覆盖全部 drawable，补齐剩余索引保持全排列
                val present = BooleanArray(drawableCount)
                for (k in 0 until cursor) present[renderOrder[k]] = true
                for (d in 0 until drawableCount) {
                    if (!present[d] && cursor < drawableCount) {
                        renderOrder[cursor++] = d
                    }
                }
            }
        }

        // 5. 同步不透明度、动态可见性与变形后顶点坐标（+Y 向上，直接供 CubismRenderer 使用）
        if (drawableCount > 0 && drawableOpacitiesPtr != 0L) {
            MemoryUtil.memCopy(drawableOpacitiesPtr, drawableOpacity)
        }
        val hasOffscreens = offscreenCount > 0 && offscreenOpacitiesPtr != 0L
        if (hasOffscreens) {
            MemoryUtil.memCopy(offscreenOpacitiesPtr, offscreenOpacityScratch)
        }

        for (d in 0 until drawableCount) {
            if (hasOffscreens) {
                var op = drawableOpacity[d]
                var anc = amParentPart[d]
                var depth = 0
                while (anc in 0 until partCount && depth < partCount) {
                    val offIdx = partOffscreenIndex[anc]
                    if (offIdx in 0 until offscreenCount) {
                        op *= offscreenOpacityScratch[offIdx]
                    }
                    anc = partParent[anc]
                    depth++
                }
                drawableOpacity[d] = op
            }

            val dyn = if (dynamicFlagsPtr != 0L) {
                MemoryUtil.memGetByte(dynamicFlagsPtr + d.toLong()).toInt() and 0xFF
            } else {
                NativeCubismCore.CSM_IS_VISIBLE
            }
            amVisible[d] = (dyn and NativeCubismCore.CSM_IS_VISIBLE) != 0 && drawableOpacity[d] > 1e-4f

            val outPos = amPositions[d]
            if (outPos.isNotEmpty() && vertexPositionsTablePtr != 0L) {
                val posAddr = MemoryUtil.memGetAddress(
                    vertexPositionsTablePtr + d.toLong() * Pointer.POINTER_SIZE
                )
                if (posAddr != 0L) {
                    MemoryUtil.memCopy(posAddr, outPos)
                }
            }
        }

        refreshBounds()
    }

    private fun refreshBounds() {
        if (boundsInitialized) return

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (d in 0 until drawableCount) {
            val positions = amPositions[d]
            var k = 0
            while (k + 1 < positions.size) {
                val x = positions[k]
                val y = positions[k + 1]
                if (x.isFinite() && y.isFinite()) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                k += 2
            }
        }

        if (minX == Float.POSITIVE_INFINITY) {
            minX = -canvasWidth * 0.5f
            maxX = canvasWidth * 0.5f
            minY = -canvasHeight * 0.5f
            maxY = canvasHeight * 0.5f
        }

        boundsMinX = minX
        boundsMinY = minY
        boundsMaxX = maxX
        boundsMaxY = maxY
        boundsInitialized = true
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (modelBufferAddr != 0L) {
            MemoryUtil.nmemAlignedFree(modelBufferAddr)
            modelBufferAddr = 0L
        }
        if (mocBufferAddr != 0L) {
            MemoryUtil.nmemAlignedFree(mocBufferAddr)
            mocBufferAddr = 0L
        }
    }

    companion object {
        fun load(mocBytes: ByteArray, fallbackDir: File? = null): CubismModel {
            require(mocBytes.size >= 64) { "MOC3 文件过小: ${mocBytes.size} 字节" }
            require(
                mocBytes[0] == 'M'.code.toByte() &&
                    mocBytes[1] == 'O'.code.toByte() &&
                    mocBytes[2] == 'C'.code.toByte() &&
                    mocBytes[3] == '3'.code.toByte()
            ) { "文件头不是 MOC3" }

            val core = NativeCubismCore.requireLoaded(fallbackDir)
            var mocAddr = 0L
            var modelAddr = 0L
            try {
                mocAddr = MemoryUtil.nmemAlignedAllocChecked(
                    NativeCubismCore.CSM_ALIGNOF_MOC,
                    mocBytes.size.toLong(),
                )
                MemoryUtil.memCopy(mocBytes, mocAddr)

                val mocVersion = core.getMocVersion(mocAddr, mocBytes.size)
                require(mocVersion in 1..core.latestMocVersion) {
                    "不支持的 MOC3 版本: $mocVersion (当前原生核心最高支持 ${core.latestMocVersion})"
                }
                require(core.hasMocConsistency(mocAddr, mocBytes.size)) {
                    "MOC3 完整性校验失败 (csmHasMocConsistency=0)"
                }

                val mocPtr = core.reviveMocInPlace(mocAddr, mocBytes.size)
                check(mocPtr != 0L) { "csmReviveMocInPlace 失败" }

                val modelSize = core.getSizeofModel(mocPtr)
                check(modelSize > 0) { "csmGetSizeofModel 返回无效大小: $modelSize" }

                modelAddr = MemoryUtil.nmemAlignedAllocChecked(
                    NativeCubismCore.CSM_ALIGNOF_MODEL,
                    modelSize.toLong(),
                )
                val modelPtr = core.initializeModelInPlace(mocPtr, modelAddr, modelSize)
                check(modelPtr != 0L) { "csmInitializeModelInPlace 失败" }

                return CubismModel(
                    core = core,
                    mocBufferAddr = mocAddr,
                    mocPtr = mocPtr,
                    modelBufferAddr = modelAddr,
                    modelPtr = modelPtr,
                )
            } catch (error: Throwable) {
                if (modelAddr != 0L) MemoryUtil.nmemAlignedFree(modelAddr)
                if (mocAddr != 0L) MemoryUtil.nmemAlignedFree(mocAddr)
                throw error
            }
        }

        private fun readIdList(tablePtr: Long, count: Int): List<String> {
            if (count <= 0 || tablePtr == 0L) return emptyList()
            val out = ArrayList<String>(count)
            for (i in 0 until count) {
                val strPtr = MemoryUtil.memGetAddress(tablePtr + i.toLong() * Pointer.POINTER_SIZE)
                out.add(MemoryUtil.memUTF8Safe(strPtr) ?: "")
            }
            return out
        }

        private fun buildIndexMap(ids: List<String>): Map<String, Int> {
            if (ids.isEmpty()) return emptyMap()
            val map = HashMap<String, Int>(ids.size * 2)
            for (i in ids.indices) {
                val id = ids[i]
                if (id.isNotEmpty()) map.putIfAbsent(id, i)
            }
            return map
        }

        private fun dedupeSorted(sorted: FloatArray): FloatArray {
            if (sorted.size <= 1) return sorted
            var write = 1
            for (read in 1 until sorted.size) {
                if (abs(sorted[read] - sorted[write - 1]) > 1e-5f) {
                    sorted[write++] = sorted[read]
                }
            }
            return if (write == sorted.size) sorted else sorted.copyOf(write)
        }
    }
}
