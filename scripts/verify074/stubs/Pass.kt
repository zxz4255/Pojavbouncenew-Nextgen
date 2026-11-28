package net.ccbluex.liquidbounce.render
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.utils.client.gpuDevice
import org.joml.Vector4fc
import java.util.Optional
import java.util.OptionalDouble
import java.util.function.Supplier
fun RenderPass.setPipeline(pipeline: RenderPipeline) { setPipeline(RenderSystem.getCompiledPipeline(pipeline)) }
private val RENDER_PASS_DEFAULT_LABEL = Supplier { LiquidBounce.CLIENT_NAME + " RenderPass" }

@JvmOverloads
fun RenderTarget.createRenderPass(
    labelGetter: Supplier<String> = RENDER_PASS_DEFAULT_LABEL,
    clearColor: Optional<Vector4fc> = Optional.empty(),
    clearDepth: OptionalDouble = OptionalDouble.empty(),
    useDepthAttachment: Boolean = true,
): RenderPass = newRenderPass(
    labelGetter,
    colorAttachment = this.colorTextureView!!,
    clearColor,
    depthAttachment = depthTextureView.takeIf { this.hasDepth() && useDepthAttachment },
    clearDepth,
)

/**
 * Color-only RenderPass.
 */
@JvmOverloads
fun GpuTextureView.createRenderPass(
    labelGetter: Supplier<String> = RENDER_PASS_DEFAULT_LABEL,
    clearColor: Optional<Vector4fc> = Optional.empty(),
): RenderPass = newRenderPass(
    labelGetter,
    colorAttachment = this,
    clearColor,
)

@Suppress("NOTHING_TO_INLINE")
private inline fun newRenderPass(
    labelGetter: Supplier<String> = RENDER_PASS_DEFAULT_LABEL,
    colorAttachment: GpuTextureView,
    clearColor: Optional<Vector4fc> = Optional.empty(),
    depthAttachment: GpuTextureView? = null,
    clearDepth: OptionalDouble = OptionalDouble.empty(),
): RenderPass = gpuDevice.createCommandEncoder().createRenderPass(
    labelGetter,
    colorAttachment,
    clearColor,
    depthAttachment,
    clearDepth,
)
