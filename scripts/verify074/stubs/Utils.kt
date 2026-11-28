package net.ccbluex.liquidbounce.utils.render
import com.mojang.renderpearl.api.buffers.*
import com.mojang.renderpearl.api.textures.*
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.buffers.*
import net.ccbluex.liquidbounce.utils.client.gpuDevice
import java.nio.ByteBuffer
import java.util.function.Supplier
@JvmInline
value class KStd140SizeCalculator(val j: Std140SizeCalculator) {
    inline val float: Unit
        get() {
            j.putFloat()
        }
    inline val int: Unit
        get() {
            j.putInt()
        }
    inline val vec2: Unit
        get() {
            j.putVec2()
        }
    inline val ivec2: Unit
        get() {
            j.putIVec2()
        }
    inline val vec3: Unit
        get() {
            j.putVec3()
        }
    inline val ivec3: Unit
        get() {
            j.putIVec3()
        }
    inline val vec4: Unit
        get() {
            j.putVec4()
        }
    inline val ivec4: Unit
        get() {
            j.putIVec4()
        }
    inline val mat4f: Unit
        get() {
            j.putMat4f()
        }

    inline fun align(alignedSize: Int) {
        j.align(alignedSize)
    }

    inline operator fun Unit.plus(other: Unit) = Unit

    inline fun get() = j.get()
}

inline fun std140Size(block: KStd140SizeCalculator.() -> Unit): Int =
    KStd140SizeCalculator(Std140SizeCalculator()).apply(block).get()

inline fun GpuDevice.createUbo(
    labelGetter: Supplier<String>? = null,
    std140Size: KStd140SizeCalculator.() -> Unit,
): GpuBuffer =
    createBuffer(
        labelGetter,
        GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE,
        std140Size(std140Size).toLong()
    )

inline fun ByteBuffer.writeStd140(action: Std140Builder.() -> Unit) {
    Std140Builder.intoBuffer(this).apply(action)
}

inline fun GpuBufferSlice.writeStd140(action: Std140Builder.() -> Unit): GpuBufferSlice =
    this.mapBuffer(read = false, write = true).use {
        it.data().writeStd140(action)

        this
    }


inline fun GpuTexture.write(
    source: NativeImage,
    mipLevel: Int = 0,
    depthOrLayer: Int = 0,
    destX: Int = 0,
    destY: Int = 0,
    width: Int = getWidth(mipLevel),
    height: Int = getHeight(mipLevel),
    sourceX: Int = 0,
    sourceY: Int = 0,
) {
    val commandEncoder = gpuDevice.createCommandEncoder()
    val slice = commandEncoder.transientMemory()
        .uploadStaging(source.pixelBytes, 1L, GpuBuffer.USAGE_COPY_SRC)
    commandEncoder.copyBufferToTexture(
        slice, sourceX, sourceY, source.width, source.height,
        this, destX, destY, width, height,
        mipLevel,depthOrLayer,
    )
}


inline fun GpuBuffer.mapBuffer(read: Boolean = false, write: Boolean = false): GpuBufferSlice.MappedView =
    this.map(read, write)

inline fun GpuBufferSlice.mapBuffer(read: Boolean = false, write: Boolean = false): GpuBufferSlice.MappedView =
    this.map(read, write)


fun RenderTarget.clearColorAndDepth() {
 val c = colorTexture; val d = depthTexture
 if(c != null) gpuDevice.createCommandEncoder().clearColorTexture(c, org.joml.Vector4f(0f))
 if(d != null) gpuDevice.createCommandEncoder().clearDepthTexture(d, 0.0)
}
