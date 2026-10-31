/*
 * Live2DHudRenderer —— 模型生命周期、每帧驱动与 HUD 绘制。
 *
 * 每帧顺序与原实现一致：
 *   ensureModelLoaded -> updateTracking -> applyIdleAnimation -> applyExpressions
 *   -> updateTransientAnimations -> update -> 提交网格
 *
 * 模型名不写死：默认取 config/model 或扫描到的第一个模型。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/Live2DHudRenderer.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.util.Mth
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class Live2DHudRenderer(
    private val config: Live2DConfig,
    private val configPath: Path,
) {

    private val loader = Live2DModelLoader()
    private val eventSystem = Live2DEventSystem(this, config)

    private var loaded: Live2DModelLoader.LoadedModel? = null
    private var renderer: CubismRenderer? = null
    private var loadedName: String? = null
    private var loadError: String? = null
    private var loadAttempted = false

    /* ---------------------------- 动画状态 ---------------------------- */

    private var firstFrame = true
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var angleX = 0f
    private var angleY = 0f
    private var angleZ = 0f
    private var bodyAngleX = 0f
    private var springX = 0f
    private var springVX = 0f
    private var springY = 0f
    private var springVY = 0f
    private var earSpring = 0f
    private var earVelocity = 0f

    private var nextBlinkAt = System.currentTimeMillis() + 1800L
    private var blinkStartedAt = -1L

    private var activeExpression: String? = null
    private var lastAppliedExpression: String? = null

    /** 自动检测出的表情（优先级低于手动 activeExpression）。 */
    private var detectedExpression: String? = null
    private var lastDetectAt = 0L

    private var idleMotionStartedAt = 0L
    private var idleMotionCooldown = 0L

    private var lastConfigSave = 0L

    /** 上一帧模型在屏幕上的实际矩形，供鼠标命中测试。 */
    var lastElementBounds: net.minecraft.client.gui.navigation.ScreenRectangle? = null
        private set

    /* --------------------------- 鼠标命中测试 --------------------------- */

    /** 当前模型矩形（没有渲染过时按配置推算）。 */
    fun elementBounds(): net.minecraft.client.gui.navigation.ScreenRectangle {
        lastElementBounds?.let { return it }
        val height = config.size.coerceIn(40, 800)
        val width = (height * (loaded?.model?.getBboxAspect() ?: 1f)).toInt().coerceAtLeast(1)
        return net.minecraft.client.gui.navigation.ScreenRectangle(
            config.posX, config.posY, width, height,
        )
    }

    /* ------------------------------ 查询 ------------------------------ */

    fun isEnabled(): Boolean = config.enabled
    fun getLoadError(): String? = loadError
    fun getLoadedModelName(): String? = loadedName
    fun getAvailableModels(): List<String> = loader.availableModels()
    fun getAvailableMotions(): List<String> = loaded?.model?.motions?.keys?.toList() ?: emptyList()
    fun getAvailableExpressions(): List<String> = loaded?.model?.expressions?.keys?.toList() ?: emptyList()
    fun getConfig(): Live2DConfig = config
    fun getEventSystem(): Live2DEventSystem = eventSystem
    fun getActiveExpression(): String? = activeExpression
    fun getRenderer(): CubismRenderer? = renderer

    fun getStatus(): String = buildString {
        append("模型 ").append(loadedName ?: config.model.ifBlank { "(未选择)" })
        append(" | ").append(config.size).append("px")
        append(" | ").append(config.posX).append(',').append(config.posY)
        append(" | 表情 ").append(getEffectiveExpression() ?: "normal")
        renderer?.let { append(" | drawables ").append(it.lastRenderedDrawables) }
        loadError?.let { append(" | 错误 ").append(it) }
    }

    /* ------------------------------ 操作 ------------------------------ */

    fun toggleEnabled() {
        config.enabled = !config.enabled
        saveConfigNow()
    }

    fun setEnabled(value: Boolean) {
        config.enabled = value
        saveConfigNow()
    }

    fun cycleModel() {
        val models = getAvailableModels()
        if (models.isEmpty()) return
        val index = models.indexOf(config.model)
        setModel(models[(index + 1) % models.size])
    }

    fun setModel(name: String) {
        if (name.isBlank()) return
        config.model = name
        loadAttempted = false
        unload()
        saveConfigNow()
    }

    fun cycleExpression() {
        val expressions = getAvailableExpressions()
        if (expressions.isEmpty()) return
        val options = listOf("normal") + expressions
        val current = activeExpression ?: "normal"
        val index = options.indexOf(current).coerceAtLeast(0)
        val next = options[(index + 1) % options.size]
        activeExpression = if (next == "normal") null else next
        saveConfigNow()
    }

    fun setActiveExpression(name: String?) {
        activeExpression = if (name.isNullOrBlank() || name.equals("normal", true)) null else name
        saveConfigNow()
    }

    fun setSize(height: Int) {
        config.size = Mth.clamp(height, 40, 800)
        saveConfig()
    }

    fun movePosition(dx: Int, dy: Int) {
        config.posX += dx
        config.posY += dy
        saveConfig()
    }

    fun triggerAction(action: Live2DConfig.EventAction) {
        val model = loaded?.model ?: return
        if (action.target.isEmpty()) return

        when (action.type) {
            "motion" -> {
                val key = model.motions.keys.firstOrNull { it == action.target || it.endsWith(action.target) }
                if (key != null) model.playMotion(key)
            }
            "expression" -> {
                if (!model.hasExpression(action.target)) return
                if (action.duration > 0f) {
                    model.playExpression(action.target, action.duration, action.fade)
                } else {
                    activeExpression = action.target
                }
            }
            "param" -> {
                if (!model.hasParameter(action.target)) return
                if (action.duration > 0f) {
                    model.setParameterOverride(action.target, action.value, action.duration, action.fade)
                } else {
                    model.setParameter(action.target, action.value)
                }
            }
        }
    }

    fun handleTick() {
        eventSystem.tick()
    }

    /* ------------------------------ 每帧 ------------------------------ */

    fun render(context: GuiGraphicsExtractor, tickDelta: Float) {
        if (!config.enabled) return
        if (mc.player == null || mc.level == null) {
            Live2DDebugLog.line("render: 跳过，player=${mc.player != null} level=${mc.level != null}")
            return
        }

        ensureModelLoaded()
        updateTracking(tickDelta)

        val model = loaded?.model
        if (model != null) {
            try {
                applyIdleAnimation(model)
                applyIdleMotion(model)
                updateExpressionDetect(model)
                applyExpressions(model)
                model.updateTransientAnimations(tickDelta / 20f)
                model.physics?.apply(model.core, tickDelta / 20f)
                model.update()
            } catch (error: Throwable) {
                loadError = "update: ${error.javaClass.simpleName}: ${error.message}"
                logger.error("Live2D 更新失败", error)
                Live2DDebugLog.event("update 失败: ${error.javaClass.name}: ${error.message}")
            }
        }

        val height = config.size.coerceIn(40, 800)
        val aspect = model?.getBboxAspect() ?: 1f
        val width = (height * aspect).toInt().coerceAtLeast(1)

        val x = config.posX + (springX * 0.55f).toInt()
        val y = config.posY + (springY * 0.3f + idleBob()).toInt()

        lastElementBounds = net.minecraft.client.gui.navigation.ScreenRectangle(x, y, width, height)

        val current = renderer
        if (current != null) {
            try {
                current.masksEnabled = config.masksEnabled
                current.render(context, x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat())
            } catch (error: Throwable) {
                loadError = "render: ${error.javaClass.simpleName}: ${error.message}"
                logger.error("Live2D 渲染失败", error)
                Live2DDebugLog.event("render 失败: ${error.javaClass.name}: ${error.message}")
            }

            // 每帧坐标 + 提交结果落盘（内部节流）。
            Live2DDebugLog.line(
                "frame model=$loadedName screen=($x,$y) size=${width}x$height " +
                    "pos=(${config.posX},${config.posY}) drawables=${current.lastRenderedDrawables}/${
                        model?.drawableCount ?: 0
                    } visible=${countVisible(model)} opacity=${"%.3f".format(model?.modelOpacity ?: 0f)} " +
                    "scale=${"%.4f".format(current.lastScale)} off=(${
                        "%.2f".format(current.lastOffsetX)
                    },${"%.2f".format(current.lastOffsetY)}) bounds=${current.lastBoundsText}" +
                    (loadError?.let { " error=$it" } ?: "")
            )

            if (loadError != null || current.lastRenderedDrawables == 0) {
                // 模型在但一帧都没提交任何 drawable：画诊断框 + 原因文字。
                val diagColor = if (loadError != null) Color4b(0xFF, 0x3B, 0x3B, 0x50) else Color4b(0xFF, 0xD5, 0x4F, 0x50)
                context.drawQuad(
                    x.toFloat(), y.toFloat(), x + width.toFloat(), y + height.toFloat(),
                    diagColor,
                )
                val reason = loadError
                    ?: ("0 drawable 可见（可见 drawable=${countVisible(model)}）")
                context.text(mc.font, reason, x, y + height + 2, 0xFFFF5555.toInt(), true)
            }
        } else {
            Live2DDebugLog.line(
                "frame(no renderer) screen=($x,$y) size=${width}x$height " +
                    "loadAttempted=$loadAttempted error=${loadError ?: "none"}"
            )
            // 没有模型时画占位框并把错误直接画到屏幕上，便于定位问题。
            context.drawQuad(
                x.toFloat(), y.toFloat(), x + width.toFloat(), y + height.toFloat(),
                Color4b(0x30, 0x20, 0x28, 0x90),
            )
            val message = loadError ?: "Live2D: 模型未加载"
            context.text(mc.font, message, x, y + height + 2, 0xFFFF5555.toInt(), true)
        }
    }

    private fun countVisible(model: Live2DModel?): Int {
        if (model == null) return 0
        var n = 0
        for (i in 0 until model.drawableCount) if (model.drawableVisible(i)) n++
        return n
    }

    /* ---------------------------- 模型加载 ---------------------------- */

    private fun ensureModelLoaded() {
        if (loadAttempted || loaded != null) return
        loadAttempted = true

        val name = config.model.ifBlank { getAvailableModels().firstOrNull() ?: "" }
        if (name.isBlank()) {
            loadError = "config/live2d/ 下没有可用模型"
            Live2DDebugLog.event("加载中止: $loadError")
            return
        }
        config.model = name
        Live2DDebugLog.event("开始加载模型: $name (目录 ${loader.modelsRoot})")

        try {
            val result = loader.load(name)
            unload()
            loaded = result
            renderer = CubismRenderer(result.model, result.textures).apply {
                setHiddenDrawables(config.hiddenDrawables)
                masksEnabled = config.masksEnabled
            }
            loadedName = name
            loadError = null

            firstFrame = true
            activeExpression = null
            lastAppliedExpression = null
            detectedExpression = null
            lastDetectAt = 0L
            nextBlinkAt = System.currentTimeMillis() + 1800L
            blinkStartedAt = -1L
            idleMotionStartedAt = 0L
            idleMotionCooldown = 0L
            result.model.update()

            Live2DDebugLog.event(
                "加载成功: $name drawable=${result.model.drawableCount} " +
                    "可见=${countVisible(result.model)} 参数=${result.model.core.parameterCount} " +
                    "画布=${result.model.canvasWidth}x${result.model.canvasHeight} " +
                    "bboxAspect=${"%.4f".format(result.model.getBboxAspect())} " +
                    "bounds=x[${"%.3f".format(result.model.boundsMinX)},${"%.3f".format(result.model.boundsMaxX)}] " +
                    "y[${"%.3f".format(result.model.boundsMinY)},${"%.3f".format(result.model.boundsMaxY)}] " +
                    "纹理=${result.textures.size} 动作=${result.model.motions.size} 表情=${result.model.expressions.size}"
            )
        } catch (error: Throwable) {
            loadError = "${error.javaClass.simpleName}: ${error.message}"
            logger.error("加载 Live2D 模型 $name 失败", error)
            Live2DDebugLog.event("加载失败: $name -> ${error.javaClass.name}: ${error.message}")
            unload()
        }
    }

    private fun unload() {
        loaded?.close()
        loaded = null
        renderer = null
        loadedName = null
    }

    fun close() {
        unload()
        loader.releaseTextures()
    }

    /* ---------------------------- 头部跟随 ---------------------------- */

    private fun updateTracking(tickDelta: Float) {
        val player = mc.player ?: return
        val yaw = player.yRot
        val pitch = player.xRot

        if (firstFrame) {
            lastYaw = yaw
            lastPitch = pitch
            firstFrame = false
            return
        }

        val dt = (tickDelta / 20f).coerceAtLeast(0.01f)
        val deltaYaw = Mth.wrapDegrees(yaw - lastYaw)
        val deltaPitch = pitch - lastPitch
        lastYaw = yaw
        lastPitch = pitch

        val strength = config.swayStrength
        val targetX = (-deltaYaw / dt * 0.018f * strength).coerceIn(-30f, 30f)
        val targetY = if (config.followPitch) (-deltaPitch / dt * 0.014f * strength).coerceIn(-20f, 20f) else 0f
        val targetZ = (deltaYaw / dt * 0.01f * strength).coerceIn(-18f, 18f)

        val smooth = config.smoothing.coerceIn(0.02f, 0.6f)
        angleX = lerp(angleX, targetX, smooth)
        angleY = lerp(angleY, targetY, smooth)
        angleZ = lerp(angleZ, targetZ, smooth * 0.75f)
        bodyAngleX = lerp(bodyAngleX, angleX * 0.35f, smooth * 0.55f)

        springX = updateSpring(springX, angleX + bodyAngleX, 0.12f, 0.78f, true)
        springY = updateSpring(springY, angleY, 0.08f, 0.82f, false)
    }

    private fun updateSpring(value: Float, target: Float, stiffness: Float, damping: Float, horizontal: Boolean): Float {
        var velocity = (if (horizontal) springVX else springVY) + (target - value) * stiffness
        velocity *= damping
        if (horizontal) springVX = velocity else springVY = velocity
        return value + velocity
    }

    /* ---------------------------- 待机动画 ---------------------------- */

    private fun applyIdleAnimation(model: Live2DModel) {
        val now = System.currentTimeMillis()
        val t = now / 1000.0

        var eyeOpen = 1f
        if (config.blinkEnabled) {
            if (blinkStartedAt < 0L && now >= nextBlinkAt) blinkStartedAt = now
            if (blinkStartedAt >= 0L) {
                val progress = ((now - blinkStartedAt) / 180f).coerceIn(0f, 1f)
                eyeOpen = if (progress < 0.5f) 1f - progress * 2f else (progress - 0.5f) * 2f
                if (progress >= 1f) {
                    blinkStartedAt = -1L
                    nextBlinkAt = now + 2200L + (sin(t * 1.7) * 500.0 + 650.0).toLong()
                }
            }
        }
        model.setParameter("ParamEyeLOpen", eyeOpen)
        model.setParameter("ParamEyeROpen", eyeOpen)

        val breath = ((sin(t * 2.1) + 1.0) * 0.5).toFloat()
        model.setParameter("ParamBreath", breath)

        model.setParameter("ParamAngleX", angleX)
        model.setParameter("ParamAngleY", angleY)
        model.setParameter("ParamAngleZ", angleZ)
        model.setParameter("ParamBodyAngleX", bodyAngleX)
        model.setParameter("ParamBodyAngleY", angleY * 0.2f)
        model.setParameter("ParamBodyAngleZ", angleZ * 0.3f)

        model.setParameter("ParamEyeBallX", (angleX / 30f).coerceIn(-1f, 1f))
        model.setParameter("ParamEyeBallY", (angleY / 20f).coerceIn(-1f, 1f))

        val hairX = angleX * 0.09f + sin(t * 2.8).toFloat() * 1.2f
        val hairY = angleY * 0.08f + sin(t * 2.1 + 1.0).toFloat() * 0.8f
        model.setParameter("ParamHairFront", hairX)
        model.setParameter("ParamHairSide", hairX)
        model.setParameter("ParamHairBack", hairX)
        model.setParameter("ParamHairAhoge", hairY)

        earVelocity += (angleZ * 0.55f + angleX * 0.18f - earSpring) * 0.16f
        earVelocity *= 0.74f
        earSpring += earVelocity
        val ear = earSpring.coerceIn(-12f, 12f)
        model.setParameter("Param10", ear)
        model.setParameter("Param14", -ear)

        if (config.soundBlinkEnabled && soundActivity >= config.soundBlinkThreshold) {
            model.setParameter("ParamEyeLOpen", 0f)
            model.setParameter("ParamEyeROpen", 0f)
        }
    }

    /** 待机动作：若模型带 Idle 组，则按冷却循环播放其中一条。 */
    private fun applyIdleMotion(model: Live2DModel) {
        if (!config.autoIdleMotion) return
        if (model.motions.isEmpty()) return
        if (model.getPlayingMotion() != null) return

        val now = System.currentTimeMillis()
        if (now < idleMotionCooldown) return

        val candidates = model.motions.keys
            .filter { it.contains(config.idleMotionGroup, ignoreCase = true) }
            .ifEmpty { model.motions.keys.toList() }
        if (candidates.isEmpty()) return

        val pick = candidates[(now / 1000L % candidates.size).toInt()]
        model.playMotion(pick)
        idleMotionStartedAt = now
        idleMotionCooldown = now + 5_000L
    }

    private fun applyExpressions(model: Live2DModel) {
        val target = activeExpression ?: detectedExpression
        if (!config.expressionsEnabled || target == null || !model.hasExpression(target)) {
            if (lastAppliedExpression != null) {
                model.resetParametersToDefault()
                lastAppliedExpression = null
            }
            return
        }
        if (target != lastAppliedExpression) {
            model.resetParametersToDefault()
            lastAppliedExpression = target
        }
        model.applyExpression(target, 1f)
    }

    /* -------------------------- 表情自动检测 -------------------------- */

    /**
     * 按 [Live2DConfig.expressionDetect] 选择表情。
     *
     * 只在模型确实带对应表情时才写入；模型没有该表情则回落到 null，
     * 不会强行套用不存在的名字。检测每 250ms 一次，避免每帧抖动。
     */
    private fun updateExpressionDetect(model: Live2DModel) {
        val mode = config.expressionDetect
        if (mode.equals("Off", true) || !config.expressionsEnabled) {
            detectedExpression = null
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastDetectAt < 250L) return
        lastDetectAt = now

        val player = mc.player
        val level = mc.level
        if (player == null || level == null) {
            detectedExpression = null
            return
        }

        val healthRatio = player.health / player.maxHealth.coerceAtLeast(0.001f)
        val combat = player.hurtTime > 0 || player.isSwinging
        val night = level.defaultClockTime % 24000L !in 0..12000L
        val weather = level.isRaining || level.isThundering

        val wantsAll = mode.equals("All", true)
        val wantsHealth = wantsAll || mode.equals("Health", true)
        val wantsCombat = wantsAll || mode.equals("Combat", true)
        val wantsEnv = wantsAll || mode.equals("Environment", true)

        val candidates = ArrayList<String>(3)
        if (wantsCombat && combat) candidates.addAll(EXPRESSIONS_COMBAT)
        if (wantsHealth) {
            when {
                healthRatio < 0.3f -> candidates.addAll(EXPRESSIONS_HURT)
                healthRatio > 0.9f -> candidates.addAll(EXPRESSIONS_HAPPY)
                else -> candidates.addAll(EXPRESSIONS_NORMAL)
            }
        }
        if (wantsEnv) {
            if (weather) candidates.addAll(EXPRESSIONS_WORRIED)
            if (night) candidates.addAll(EXPRESSIONS_SLEEPY)
        }

        detectedExpression = candidates.firstOrNull { model.hasExpression(it) }
    }

    /** 供外部（事件系统 / 命令）查询当前生效的表情名。 */
    fun getEffectiveExpression(): String? = activeExpression ?: detectedExpression

    /** 声音活动度，由 ModuleLive2D 的声音监听写入。 */
    var soundActivity: Float = 0f

    private fun lerp(from: Float, to: Float, factor: Float): Float =
        from + (to - from) * factor.coerceIn(0f, 1f)

    private fun idleBob(): Float = (sin(System.currentTimeMillis() / 1200.0) * 1.2).toFloat()

    /* ---------------------------- 配置写盘 ---------------------------- */

    private fun saveConfig() {
        val now = System.currentTimeMillis()
        if (now - lastConfigSave < 300L) return
        lastConfigSave = now
        config.save(configPath)
    }

    /** 立即写盘（模块设置变更、拖拽结束时用）。 */
    fun saveConfigNow() {
        lastConfigSave = System.currentTimeMillis()
        config.save(configPath)
    }

    private companion object {
        /** 表情候选名（按优先级排列，命中第一个模型实际拥有的）。 */
        val EXPRESSIONS_HURT = listOf("F01", "F02", "Hurt", "hurt", "Pain", "pain", "Sad", "sad")
        val EXPRESSIONS_HAPPY = listOf("F02", "F03", "Happy", "happy", "Smile", "smile", "Joy")
        val EXPRESSIONS_NORMAL = listOf("F01", "Normal", "normal", "Neutral", "neutral", "Idle")
        val EXPRESSIONS_COMBAT = listOf("F04", "Angry", "angry", "Serious", "serious", "Battle")
        val EXPRESSIONS_WORRIED = listOf("F05", "Worried", "worried", "Fear", "fear", "Surprise")
        val EXPRESSIONS_SLEEPY = listOf("F06", "Sleepy", "sleepy", "Sleep", "sleep", "Tired")
    }
}