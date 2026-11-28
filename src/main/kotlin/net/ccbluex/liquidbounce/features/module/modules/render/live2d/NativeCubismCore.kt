/*
 * NativeCubismCore —— Android Live2D Cubism Core 原生库 (.so) 加载与 C API 绑定。
 *
 * 从 JAR 内置资源 /natives/android-<arch>/libLive2DCubismCore.so（arm64-v8a / x86_64）
 * 按 SHA-256 内容寻址解压到可执行缓存目录，并通过 LWJGL SharedLibrary + JNI 直接绑定
 * 官方 Live2DCubismCore C 符号（csm*），完全替代旧版纯 Kotlin MOC3 解析与求解器。
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import org.lwjgl.system.APIUtil
import org.lwjgl.system.JNI
import org.lwjgl.system.SharedLibrary
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

object NativeCubismCore {

    const val CSM_ALIGNOF_MOC: Long = 64L
    const val CSM_ALIGNOF_MODEL: Long = 16L

    // Constant drawable flags (csmConstantDrawableFlags)
    const val CSM_BLEND_ADDITIVE: Int = 1 shl 0
    const val CSM_BLEND_MULTIPLICATIVE: Int = 1 shl 1
    const val CSM_IS_DOUBLE_SIDED: Int = 1 shl 2
    const val CSM_IS_INVERTED_MASK: Int = 1 shl 3

    // Dynamic drawable flags (csmDynamicDrawableFlags)
    const val CSM_IS_VISIBLE: Int = 1 shl 0
    const val CSM_VISIBILITY_DID_CHANGE: Int = 1 shl 1
    const val CSM_OPACITY_DID_CHANGE: Int = 1 shl 2
    const val CSM_DRAW_ORDER_DID_CHANGE: Int = 1 shl 3
    const val CSM_RENDER_ORDER_DID_CHANGE: Int = 1 shl 4
    const val CSM_VERTEX_POSITIONS_DID_CHANGE: Int = 1 shl 5
    const val CSM_BLEND_COLOR_DID_CHANGE: Int = 1 shl 6

    private const val LIBRARY_FILE = "libLive2DCubismCore.so"
    private const val OVERRIDE_PATH_PROPERTY = "liquidbounce.live2d.core.path"

    class Handles internal constructor(
        val library: SharedLibrary,
        val platform: String,
        val libraryPath: String,
    ) {
        private val fnGetVersion = req("csmGetVersion")
        private val fnGetLatestMocVersion = req("csmGetLatestMocVersion")
        private val fnGetMocVersion = req("csmGetMocVersion")
        private val fnHasMocConsistency = req("csmHasMocConsistency")
        private val fnReviveMocInPlace = req("csmReviveMocInPlace")
        private val fnGetSizeofModel = req("csmGetSizeofModel")
        private val fnInitializeModelInPlace = req("csmInitializeModelInPlace")
        private val fnUpdateModel = req("csmUpdateModel")
        private val fnResetDrawableDynamicFlags = req("csmResetDrawableDynamicFlags")
        private val fnReadCanvasInfo = req("csmReadCanvasInfo")

        private val fnGetParameterCount = req("csmGetParameterCount")
        private val fnGetParameterIds = req("csmGetParameterIds")
        private val fnGetParameterTypes = req("csmGetParameterTypes")
        private val fnGetParameterMinimumValues = req("csmGetParameterMinimumValues")
        private val fnGetParameterMaximumValues = req("csmGetParameterMaximumValues")
        private val fnGetParameterDefaultValues = req("csmGetParameterDefaultValues")
        private val fnGetParameterValues = req("csmGetParameterValues")
        private val fnGetParameterRepeats = opt("csmGetParameterRepeats")
        private val fnGetParameterKeyCounts = req("csmGetParameterKeyCounts")
        private val fnGetParameterKeyValues = req("csmGetParameterKeyValues")

        private val fnGetPartCount = req("csmGetPartCount")
        private val fnGetPartIds = req("csmGetPartIds")
        private val fnGetPartOpacities = req("csmGetPartOpacities")
        private val fnGetPartParentPartIndices = req("csmGetPartParentPartIndices")
        private val fnGetPartOffscreenIndices = opt("csmGetPartOffscreenIndices")

        private val fnGetOffscreenCount = opt("csmGetOffscreenCount")
        private val fnGetOffscreenOpacities = opt("csmGetOffscreenOpacities")

        private val fnGetDrawableCount = req("csmGetDrawableCount")
        private val fnGetDrawableIds = req("csmGetDrawableIds")
        private val fnGetDrawableConstantFlags = req("csmGetDrawableConstantFlags")
        private val fnGetDrawableDynamicFlags = req("csmGetDrawableDynamicFlags")
        private val fnGetDrawableTextureIndices = req("csmGetDrawableTextureIndices")
        private val fnGetDrawableDrawOrders = req("csmGetDrawableDrawOrders")
        private val fnGetRenderOrders = req("csmGetRenderOrders")
        private val fnGetDrawableOpacities = req("csmGetDrawableOpacities")
        private val fnGetDrawableMaskCounts = req("csmGetDrawableMaskCounts")
        private val fnGetDrawableMasks = req("csmGetDrawableMasks")
        private val fnGetDrawableVertexCounts = req("csmGetDrawableVertexCounts")
        private val fnGetDrawableVertexPositions = req("csmGetDrawableVertexPositions")
        private val fnGetDrawableVertexUvs = req("csmGetDrawableVertexUvs")
        private val fnGetDrawableIndexCounts = req("csmGetDrawableIndexCounts")
        private val fnGetDrawableIndices = req("csmGetDrawableIndices")
        private val fnGetDrawableParentPartIndices = req("csmGetDrawableParentPartIndices")

        val versionRaw: Int = JNI.invokeI(fnGetVersion)
        val latestMocVersion: Int = JNI.invokeI(fnGetLatestMocVersion)
        val versionText: String = formatVersion(versionRaw)

        private fun req(symbol: String): Long =
            APIUtil.apiGetFunctionAddress(library, symbol)

        private fun opt(symbol: String): Long =
            APIUtil.apiGetFunctionAddressOptional(library, symbol)

        fun getMocVersion(address: Long, size: Int): Int =
            JNI.invokePI(address, size, fnGetMocVersion)

        fun hasMocConsistency(address: Long, size: Int): Boolean =
            JNI.invokePI(address, size, fnHasMocConsistency) != 0

        fun reviveMocInPlace(address: Long, size: Int): Long =
            JNI.invokePP(address, size, fnReviveMocInPlace)

        fun getSizeofModel(mocPtr: Long): Int =
            JNI.invokePI(mocPtr, fnGetSizeofModel)

        fun initializeModelInPlace(mocPtr: Long, address: Long, size: Int): Long =
            JNI.invokePPP(mocPtr, address, size, fnInitializeModelInPlace)

        fun updateModel(modelPtr: Long) {
            JNI.invokePV(modelPtr, fnUpdateModel)
        }

        fun resetDrawableDynamicFlags(modelPtr: Long) {
            JNI.invokePV(modelPtr, fnResetDrawableDynamicFlags)
        }

        fun readCanvasInfo(modelPtr: Long, outSizeVec2: Long, outOriginVec2: Long, outPixelsPerUnit: Long) {
            JNI.invokePPPPV(modelPtr, outSizeVec2, outOriginVec2, outPixelsPerUnit, fnReadCanvasInfo)
        }

        fun getParameterCount(modelPtr: Long): Int = JNI.invokePI(modelPtr, fnGetParameterCount)
        fun getParameterIds(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetParameterIds)
        fun getParameterTypes(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetParameterTypes)
        fun getParameterMinimumValues(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetParameterMinimumValues)
        fun getParameterMaximumValues(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetParameterMaximumValues)
        fun getParameterDefaultValues(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetParameterDefaultValues)
        fun getParameterValues(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetParameterValues)
        fun getParameterRepeats(modelPtr: Long): Long =
            if (fnGetParameterRepeats != 0L) JNI.invokePP(modelPtr, fnGetParameterRepeats) else 0L
        fun getParameterKeyCounts(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetParameterKeyCounts)
        fun getParameterKeyValues(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetParameterKeyValues)

        fun getPartCount(modelPtr: Long): Int = JNI.invokePI(modelPtr, fnGetPartCount)
        fun getPartIds(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetPartIds)
        fun getPartOpacities(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetPartOpacities)
        fun getPartParentPartIndices(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetPartParentPartIndices)
        fun getPartOffscreenIndices(modelPtr: Long): Long =
            if (fnGetPartOffscreenIndices != 0L) JNI.invokePP(modelPtr, fnGetPartOffscreenIndices) else 0L

        fun getOffscreenCount(modelPtr: Long): Int =
            if (fnGetOffscreenCount != 0L) JNI.invokePI(modelPtr, fnGetOffscreenCount) else 0
        fun getOffscreenOpacities(modelPtr: Long): Long =
            if (fnGetOffscreenOpacities != 0L) JNI.invokePP(modelPtr, fnGetOffscreenOpacities) else 0L

        fun getDrawableCount(modelPtr: Long): Int = JNI.invokePI(modelPtr, fnGetDrawableCount)
        fun getDrawableIds(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetDrawableIds)
        fun getDrawableConstantFlags(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetDrawableConstantFlags)
        fun getDrawableDynamicFlags(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetDrawableDynamicFlags)
        fun getDrawableTextureIndices(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetDrawableTextureIndices)
        fun getDrawableDrawOrders(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetDrawableDrawOrders)
        fun getRenderOrders(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetRenderOrders)
        fun getDrawableOpacities(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetDrawableOpacities)
        fun getDrawableMaskCounts(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetDrawableMaskCounts)
        fun getDrawableMasks(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetDrawableMasks)
        fun getDrawableVertexCounts(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetDrawableVertexCounts)
        fun getDrawableVertexPositions(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetDrawableVertexPositions)
        fun getDrawableVertexUvs(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetDrawableVertexUvs)
        fun getDrawableIndexCounts(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetDrawableIndexCounts)
        fun getDrawableIndices(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetDrawableIndices)
        fun getDrawableParentPartIndices(modelPtr: Long): Long = JNI.invokePP(modelPtr, fnGetDrawableParentPartIndices)
    }

    @Volatile
    private var handles: Handles? = null

    @Volatile
    private var loadFailure: Throwable? = null

    fun isLoaded(): Boolean = handles != null

    fun describeBackend(): String {
        val h = handles
        if (h != null) return "CubismCore ${h.versionText} (${h.platform})"
        val err = loadFailure
        return if (err != null) {
            "不可用 (${err.javaClass.simpleName}: ${err.message ?: ""})"
        } else {
            "未加载"
        }
    }

    @Synchronized
    fun ensureLoaded(fallbackDir: File? = null): Boolean {
        if (handles != null) return true
        if (loadFailure != null) return false
        return try {
            handles = loadInternal(fallbackDir)
            true
        } catch (error: Throwable) {
            loadFailure = error
            false
        }
    }

    @Synchronized
    fun requireLoaded(fallbackDir: File? = null): Handles {
        handles?.let { return it }
        loadFailure?.let { prior ->
            throw IllegalStateException("Live2D Android 原生库加载失败: ${prior.message}", prior)
        }
        try {
            val loaded = loadInternal(fallbackDir)
            handles = loaded
            return loaded
        } catch (error: Throwable) {
            loadFailure = error
            throw IllegalStateException("Live2D Android 原生库加载失败: ${error.message}", error)
        }
    }

    private fun loadInternal(fallbackDir: File?): Handles {
        val explicitPath = System.getProperty(OVERRIDE_PATH_PROPERTY, "").trim()
        if (explicitPath.isNotEmpty()) {
            val overrideFile = File(explicitPath)
            if (!overrideFile.isFile) {
                throw IOException("指定的 Live2D 原生库不存在: ${overrideFile.absolutePath}")
            }
            val shared = APIUtil.apiCreateLibrary(overrideFile.absolutePath)
            return Handles(shared, "override", overrideFile.absolutePath)
        }

        val platform = platformFolder()
            ?: throw UnsupportedOperationException(
                "当前架构 ${System.getProperty("os.arch")} 没有对应的 Android Live2D 原生库"
            )

        val resourcePath = "/natives/$platform/$LIBRARY_FILE"
        val bytes = NativeCubismCore::class.java.getResourceAsStream(resourcePath)?.use(::readAll)
            ?: throw IOException("JAR 内缺少 Live2D Android 原生库资源: $resourcePath")

        val hashPrefix = sha256Hex(bytes).substring(0, 16)
        val candidates = candidateDirectories(fallbackDir)
        var lastError: Throwable? = null

        for (base in candidates) {
            try {
                val targetDir = File(File(base, "liquidbounce-live2d"), hashPrefix)
                val libraryFile = File(targetDir, LIBRARY_FILE)
                unpackIfNeeded(targetDir, libraryFile, bytes)
                val shared = APIUtil.apiCreateLibrary(libraryFile.absolutePath)
                return Handles(shared, platform, libraryFile.absolutePath)
            } catch (error: Throwable) {
                lastError = error
            }
        }

        throw IOException(
            "无法加载 $resourcePath（已尝试目录: ${candidates.joinToString { it.absolutePath }}）: ${lastError?.message}",
            lastError,
        )
    }

    private fun unpackIfNeeded(targetDir: File, libraryFile: File, bytes: ByteArray) {
        if (!libraryFile.isFile || libraryFile.length() != bytes.size.toLong()) {
            if (!targetDir.isDirectory && !targetDir.mkdirs() && !targetDir.isDirectory) {
                throw IOException("无法创建原生库缓存目录: ${targetDir.absolutePath}")
            }
            val tempFile = File(targetDir, "$LIBRARY_FILE.tmp")
            Files.write(tempFile.toPath(), bytes)
            try {
                Files.move(
                    tempFile.toPath(),
                    libraryFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: IOException) {
                tempFile.delete()
                throw error
            }
        }
        libraryFile.setReadable(true, false)
        libraryFile.setExecutable(true, false)
    }

    private fun candidateDirectories(fallbackDir: File?): List<File> {
        val raw = buildList {
            System.getenv("POJAV_NATIVEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { add(File(it)) }
            System.getenv("TMPDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { add(File(it)) }
            System.getProperty("java.io.tmpdir")?.trim()?.takeIf { it.isNotEmpty() }?.let { add(File(it)) }
            System.getProperty("user.home")?.trim()?.takeIf { it.isNotEmpty() }?.let { add(File(it, ".cache")) }
            if (fallbackDir != null) add(fallbackDir)
        }
        val seen = LinkedHashSet<String>()
        val result = ArrayList<File>(raw.size)
        for (dir in raw) {
            val key = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
            if (seen.add(key)) result.add(dir)
        }
        if (result.isEmpty()) result.add(File("."))
        return result
    }

    private fun platformFolder(): String? {
        val arch = System.getProperty("os.arch", "").lowercase(Locale.ROOT)
        return when (arch) {
            "aarch64", "arm64", "arm64-v8a" -> "android-arm64"
            "x86_64", "amd64" -> "android-x64"
            else -> null
        }
    }

    private fun formatVersion(version: Int): String {
        val major = (version ushr 24) and 0xFF
        val minor = (version ushr 16) and 0xFF
        val patch = version and 0xFFFF
        return "$major.$minor.$patch"
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(1 shl 18)
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val chars = CharArray(digest.size * 2)
        val hex = "0123456789abcdef"
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            chars[i * 2] = hex[v ushr 4]
            chars[i * 2 + 1] = hex[v and 0x0F]
        }
        return String(chars)
    }
}
