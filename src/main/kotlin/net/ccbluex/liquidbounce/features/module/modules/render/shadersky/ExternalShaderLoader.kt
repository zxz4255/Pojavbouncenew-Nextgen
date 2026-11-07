package net.ccbluex.liquidbounce.features.module.modules.render.shadersky

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.render.utils.LiteralShaderSource
import net.ccbluex.liquidbounce.utils.client.clientLogger
import net.ccbluex.liquidbounce.utils.client.gpuDevice
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.resources.Identifier
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Loads a self-contained GLSL vertex/fragment pair through RenderPearl, never raw OpenGL.
 * Source reading/compilation is asynchronous; finishCompile runs on Minecraft's render executor.
 * Bad/incomplete saves keep the last successfully compiled program alive.
 * The pipeline factory owns the vertex layout, bindings and render-state contract.
 */
class ExternalShaderLoader(
    private val directory: Path,
    private val pipelineFactory: (Identifier, Identifier, Identifier) -> RenderPipeline,
) {
    private val logger = clientLogger("ExternalShaderLoader")
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "external-sky-shaders").apply { isDaemon = true }
    }
    private val renderExecutor = Executor { task -> mc.execute(task) }
    private val generation = AtomicLong()
    @Volatile private var inFlight = false
    @Volatile private var observedHash: String? = null
    @Volatile var pipeline: CompiledRenderPipeline? = null
        private set
    @Volatile var error: String? = null
        private set
    private var lastPoll = 0L

    /** Called on the client thread, at most once per second unless forced (F8 by default). */
    fun poll(name: String, force: Boolean = false) {
        if (inFlight) return
        val now = System.nanoTime()
        if (!force && now - lastPoll < 1_000_000_000L) return
        lastPoll = now
        if (!name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            report("Shader name must be a basename containing only letters, digits, _ or -")
            return
        }
        inFlight = true
        val ticket = generation.incrementAndGet()
        worker.execute {
            try {
                val vertex = read(directory.resolve("$name.vsh"))
                val fragment = read(directory.resolve("$name.fsh"))
                val hash = digest(name + "\u0000" + vertex + "\u0000" + fragment)
                if (!force && hash == observedHash) {
                    inFlight = false
                    return@execute
                }
                observedHash = hash
                val vertexId = LiquidBounce.identifier("shader/external-sky/$ticket/vertex")
                val fragmentId = LiquidBounce.identifier("shader/external-sky/$ticket/fragment")
                val pipelineId = LiquidBounce.identifier("pipeline/external-sky/$ticket")
                val source = LiteralShaderSource(mapOf(vertexId to vertex, fragmentId to fragment), false)
                val specification = pipelineFactory(pipelineId, vertexId, fragmentId)
                gpuDevice.compilePipeline(specification, source, worker)
                    .thenAcceptAsync({ pending ->
                        try {
                            if (generation.get() == ticket) {
                                val compiled = pending.finishCompile()
                                if (compiled != null) {
                                    pipeline = compiled
                                    error = null
                                    logger.info("Loaded external shaders: $name.vsh / $name.fsh")
                                } else {
                                    report("Shader compilation failed for $name; keeping last good pipeline. See renderer log.")
                                }
                            }
                        } catch (exception: Exception) {
                            report("Cannot finalize $name: ${exception.message}")
                        } finally {
                            source.close()
                            inFlight = false
                        }
                    }, renderExecutor)
                    .exceptionally { exception ->
                        source.close()
                        inFlight = false
                        report("Cannot compile $name: ${exception.message}")
                        null
                    }
            } catch (exception: Exception) {
                inFlight = false
                report("Cannot read sky shader files: ${exception.message}")
            }
        }
    }

    private fun read(path: Path): String {
        require(Files.size(path) in 1L..2_097_152L) { "Shader must be between 1 byte and 2 MiB: $path" }
        return Files.readString(path, Charsets.UTF_8).removePrefix("\uFEFF")
    }
    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun report(message: String) {
        if (error != message) logger.error(message)
        error = message
    }
}
