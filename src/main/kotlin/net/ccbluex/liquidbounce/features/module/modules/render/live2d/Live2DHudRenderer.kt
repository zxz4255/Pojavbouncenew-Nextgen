/*
 * Live2DHudRenderer —— 模型生命周期、每帧驱动与 HUD 绘制。
 *
 * 每帧顺序与原实现一致：
 *   ensureModelLoaded -> updateTracking -> applyIdleAnimation -> applyExpressions
 *   -> updateTransientAnimations -> update -> 提交网格
 *
 * 模型名不写死：默认取 config/model 或扫描到的第一个模型。
 *
 * fix-20：修正时间基准。tickDelta 是「当前 tick 内的插值系数」(0..1)，
 * 不是帧间隔；旧代码把它当 dt 用来做角速度除法和动画推进，导致玩家转头时
 * 角度/物理每帧跳变，网格形变抖动，进而让脸部遮罩边界看起来在穿模。
 * 现在统一用真实经过时间（nanoTime）驱动，并读取插值后的视角。
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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

    /**
     * 上一帧的真实时间戳（纳秒）。0 表示需要在下一帧重新起算，
     * 用于加载模型 / 重新开启模块后避免一个巨大的 dt 造成瞬间抽帧。
     */
    private var lastFrameNanos = 0L

    /** 本帧真实间隔（秒），已钳制，供跟踪、动作、物理共用同一个时间基准。 */
    private var frameDelta = 1f / 60f

    /**
     * 摆动相位累积器（弧度）。
     *
     * 必须是累积而不是 `now * speed`：后者在用户拖动速度滑块时会让相位瞬间跳变，
     * 模型会「闪」一下。累积相位保证改速度只改变之后的角速度，位置连续。
     */
    private var bobPhase = 0.0
    private var breathPhase = 0.0
    private var hairPhase = 0.0
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
    private var earAccumulator = 0f

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
    /** Access the currently loaded model, not a copy; UI runs on the client thread. */
    fun getModel(): Live2DModel? = loaded?.model
    fun playMotion(name: String) {
        val model = loaded?.model ?: return
        if (model.hasMotion(name)) model.playMotion(name)
    }


    fun getStatus(): String = buildString {
        append("模型 ").append(loadedName ?: config.model.ifBlank { "(未选择)" })
        append(" | ").append(config.size).append("px")
        append(" | ").append(config.posX).append(',').append(config.posY)
        append(" | 表情 ").append(getEffectiveExpression() ?: "normal")
        renderer?.let {
            append(" | drawables ").append(it.lastRenderedDrawables)
            append(" | masked ").append(it.lastMaskedDrawables)
        }
        loadError?.let { append(" | 错误 ").append(it) }
    }

    /* ------------------------------ 操作 ------------------------------ */

    fun toggleEnabled() {
        config.enabled = !config.enabled
        if (config.enabled) resetTiming()
        saveConfigNow()
    }

    fun setEnabled(value: Boolean) {
        config.enabled = value
        if (value) resetTiming()
        saveConfigNow()
    }

    /**
     * 重置时间基准与弹簧历史。模块重新开启、切换模型、切世界后调用，
     * 否则第一帧会拿到「上次渲染到现在」的巨大 dt，模型瞬间抽一下。
     */
    private fun resetTiming() {
        lastFrameNanos = 0L
        firstFrame = true
        bobPhase = 0.0
        breathPhase = 0.0
        hairPhase = 0.0
        springAccumulatorX = 0f
        springAccumulatorY = 0f
        earAccumulator = 0f
        springVX = 0f
        springVY = 0f
        earVelocity = 0f
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
        val options = listOf<String?>(null) + getAvailableExpressions()
        if (options.size <= 1) return
        val next = (options.indexOf(activeExpression).coerceAtLeast(0) + 1) % options.size
        setActiveExpression(options[next])
    }

    /** null returns to automatic detection; all non-null names are actual loaded keys. */
    fun setActiveExpression(name: String?) {
        if (name != null && loaded?.model?.hasExpression(name) != true) return
        activeExpression = name
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
        if (!config.enabled) {
            lastFrameNanos = 0L
            return
        }
        if (mc.player == null || mc.level == null) {
            lastFrameNanos = 0L
            Live2DDebugLog.line("render: 跳过，player=${mc.player != null} level=${mc.level != null}")
            return
        }

        ensureModelLoaded()

        // 真实帧间隔。tickDelta 只是 tick 内插值系数，不能当 dt。
        val now = System.nanoTime()
        val previous = lastFrameNanos
        lastFrameNanos = now
        frameDelta = if (previous == 0L) 1f / 60f
        else ((now - previous) / 1_000_000_000f).coerceIn(1f / 240f, 0.1f)

        advanceSwayPhases(frameDelta)
        updateTracking(tickDelta)

        val model = loaded?.model
        if (model != null) {
            try {
                model.beginExpressionFrame()
                applyIdleAnimation(model)
                applyIdleMotion(model)
                updateExpressionDetect(model)
                applyExpressions(model)
                model.updateTransientAnimations(frameDelta)
                model.physics?.apply(model.core, frameDelta)
                model.applyManualParameters()
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

        // 保留亚像素位置：整数截断会让模型每帧整体跳 1px，
        // 遮罩栅格与几何一起抖动，边缘就会一闪一闪。
        val drawX = config.posX + springX * config.spring.screenX
        val drawY = config.posY + springY * config.spring.screenY + idleBob()
        val x = floor(drawX).toInt()
        val y = floor(drawY).toInt()

        lastElementBounds = net.minecraft.client.gui.navigation.ScreenRectangle(x, y, width, height)

        val current = renderer
        if (current != null) {
            try {
                current.masksEnabled = config.masksEnabled
                current.render(context, drawX, drawY, width.toFloat(), height.toFloat())
            } catch (error: Throwable) {
                loadError = "render: ${error.javaClass.simpleName}: ${error.message}"
                logger.error("Live2D 渲染失败", error)
                Live2DDebugLog.event("render 失败: ${error.javaClass.name}: ${error.message}")
            }

            // 每帧坐标 + 提交结果落盘（内部节流）。
            Live2DDebugLog.line(
                "frame model=$loadedName screen=(${"%.2f".format(drawX)},${"%.2f".format(drawY)}) " +
                    "size=${width}x$height dt=${"%.4f".format(frameDelta)} " +
                    "pos=(${config.posX},${config.posY}) drawables=${current.lastRenderedDrawables}/${
                        model?.drawableCount ?: 0
                    } visible=${countVisible(model)} masked=${current.lastMaskedDrawables} " +
                    "maskCell=${"%.2f".format(current.lastMaskCell)} " +
                    "opacity=${"%.3f".format(model?.modelOpacity ?: 0f)} " +
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

    fun ensureModelLoaded() {
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

            resetTiming()
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
        resetTiming()
    }

    fun close() {
        unload()
        loader.releaseTextures()
    }

    /* ---------------------------- 头部跟随 ---------------------------- */

    /**
     * 推进三个待机摆动相位。基准角速度取自 fix-20：
     *   bob    = now/1200ms      → 1/1.2 rad/s
     *   breath = t * 2.1         → 2.1  rad/s
     *   hair   = t * 2.8         → 2.8  rad/s
     * 乘以各自的 Speed 倍率，默认 1.0 时与 fix-20 完全一致。
     *
     * 相位对 2π 取模，长时间挂机也不会丢浮点精度。
     */
    private fun advanceSwayPhases(dt: Float) {
        val idle = config.idleSway
        val step = dt.toDouble()
        if (idle.enabled) {
            bobPhase = wrapPhase(bobPhase + step * (1.0 / 1.2) * idle.bobSpeed)
            hairPhase = wrapPhase(hairPhase + step * 2.8 * idle.hairSpeed)
        }
        breathPhase = wrapPhase(breathPhase + step * 2.1 * config.breathSpeed)
    }

    private fun wrapPhase(value: Double): Double {
        val tau = Math.PI * 2.0
        val wrapped = value % tau
        return if (wrapped < 0) wrapped + tau else wrapped
    }

    /**
     * 头部跟随。
     *
     * - 读 **插值后** 的视角（getViewYRot/getViewXRot），否则同一 tick 内的多帧
     *   拿到同一个 yRot，delta 在 0 和「整个 tick 的转动量」之间来回跳。
     * - 用真实帧间隔 [frameDelta] 求角速度，而不是 tickDelta。
     * - 平滑与弹簧都按时间归一化，低帧率不会变慢、高帧率不会变快。
     */
    private fun updateTracking(tickDelta: Float) {
        val player = mc.player ?: return
        val yaw = player.getViewYRot(tickDelta)
        val pitch = player.getViewXRot(tickDelta)

        if (firstFrame) {
            lastYaw = yaw
            lastPitch = pitch
            firstFrame = false
            return
        }

        val dt = frameDelta.coerceAtLeast(1f / 240f)
        val deltaYaw = Mth.wrapDegrees(yaw - lastYaw)
        val deltaPitch = pitch - lastPitch
        lastYaw = yaw
        lastPitch = pitch

        // 角速度（度/秒）。上限可调，防止瞬移/甩头把参数打飞。
        val head = config.headSway
        val limit = head.rateLimit
        val yawRate = (deltaYaw / dt).coerceIn(-limit, limit)
        val pitchRate = (deltaPitch / dt).coerceIn(-limit, limit)

        val strength = config.swayStrength
        // 基准系数来自 fix-20（0.018 * 20 = 0.36 折算），各轴 Gain 默认 1.0 即原表现。
        // head.enabled = false 时目标恒为 0，角度会平滑归零而不是硬切。
        val active = head.enabled
        val targetX = if (active) {
            (-yawRate * BASE_YAW_GAIN * strength * head.yawGain).coerceIn(-head.maxYaw, head.maxYaw)
        } else 0f
        val targetY = if (active && config.followPitch) {
            (-pitchRate * BASE_PITCH_GAIN * strength * head.pitchGain).coerceIn(-head.maxPitch, head.maxPitch)
        } else 0f
        val targetZ = if (active) {
            (yawRate * BASE_ROLL_GAIN * strength * head.rollGain).coerceIn(-head.maxRoll, head.maxRoll)
        } else 0f

        val smooth = timeFactor(config.smoothing.coerceIn(0.02f, 0.6f), dt)
        angleX = lerp(angleX, targetX, smooth)
        angleY = lerp(angleY, targetY, smooth)
        angleZ = lerp(angleZ, targetZ, smooth * head.rollSmoothing)
        bodyAngleX = lerp(bodyAngleX, angleX * head.bodyFollow, smooth * head.bodySmoothing)

        val spring = config.spring
        if (spring.enabled) {
            springX = updateSpring(springX, angleX + bodyAngleX, spring.stiffness, spring.damping, true, dt)
            springY = updateSpring(springY, angleY, spring.verticalStiffness, spring.verticalDamping, false, dt)
        } else {
            // 关掉弹簧时平滑收回原位，不瞬移。
            val settle = timeFactor(0.15f, dt)
            springX = lerp(springX, 0f, settle)
            springY = lerp(springY, 0f, settle)
            springVX = 0f
            springVY = 0f
        }
    }

    /**
     * 把「每 60fps 帧的系数」换算成当前帧间隔下的等效系数：
     * 1 - (1 - perFrame)^(dt * 60)。这样 30fps 与 144fps 的收敛速度一致。
     */
    private fun timeFactor(perFrame: Float, dt: Float): Float {
        val p = perFrame.coerceIn(0.0001f, 0.9999f)
        return (1f - (1f - p).pow(dt * 60f)).coerceIn(0f, 1f)
    }

    /**
     * 弹簧按真实时间积分。固定 1/120s 子步，剩余时间留在累加器里，
     * 保证不同帧率下轨迹一致，也不会因为一帧很长而炸开。
     */
    private var springAccumulatorX = 0f
    private var springAccumulatorY = 0f

    private fun updateSpring(
        value: Float,
        target: Float,
        stiffness: Float,
        damping: Float,
        horizontal: Boolean,
        dt: Float,
    ): Float {
        var current = value
        var accumulator = (if (horizontal) springAccumulatorX else springAccumulatorY) + dt
        accumulator = accumulator.coerceAtMost(SPRING_MAX_BACKLOG)
        val step = 1f / 120f
        // 原来的系数是按 60fps 一帧调的，这里一个子步取半量。
        // 阻尼按子步开方，保证每秒衰减率与帧率无关。
        val k = stiffness * 0.5f
        val d = damping.pow(0.5f)
        while (accumulator >= step) {
            accumulator -= step
            var velocity = (if (horizontal) springVX else springVY) + (target - current) * k
            velocity *= d
            if (horizontal) springVX = velocity else springVY = velocity
            current += velocity
        }
        if (horizontal) springAccumulatorX = accumulator else springAccumulatorY = accumulator
        return current
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

        val head = config.headSway
        val idle = config.idleSway
        val idleOn = idle.enabled

        // 呼吸：速度可调，0 = 停在中间位。用累积相位，改速度不跳相位。
        val breath = if (config.breathSpeed > 0f) ((sin(breathPhase) + 1.0) * 0.5).toFloat() else 0.5f
        model.setParameter("ParamBreath", breath)

        model.setParameter("ParamAngleX", angleX)
        model.setParameter("ParamAngleY", angleY)
        model.setParameter("ParamAngleZ", angleZ)
        model.setParameter("ParamBodyAngleX", bodyAngleX)
        // 先算比例再乘：bodyFollow 为默认 0.35 时比值精确等于 1.0f，
        // 保证与 fix-20 逐位一致（先乘后除会引入最后一位的舍入差）。
        val bodyScale = head.bodyFollow / BODY_FOLLOW_BASE
        model.setParameter("ParamBodyAngleY", angleY * 0.2f * bodyScale)
        model.setParameter("ParamBodyAngleZ", angleZ * 0.3f * bodyScale)

        // 眼球：按各轴实际上限归一化，改了 MaxYaw 之后眼神不会提前打满。
        val eye = head.eyeFollow
        val eyeRangeX = head.maxYaw.coerceAtLeast(1f)
        val eyeRangeY = head.maxPitch.coerceAtLeast(1f)
        model.setParameter("ParamEyeBallX", (angleX / eyeRangeX * eye).coerceIn(-1f, 1f))
        model.setParameter("ParamEyeBallY", (angleY / eyeRangeY * eye).coerceIn(-1f, 1f))

        // 头发 = 跟随头部 + 自主飘动，两部分独立可调。
        val follow = if (idleOn) idle.hairFollow else 0f
        val wave = if (idleOn) idle.hairWave else 0f
        val hairX = angleX * 0.09f * follow + sin(hairPhase).toFloat() * 1.2f * wave
        val hairY = angleY * 0.08f * follow + sin(hairPhase * 0.75 + 1.0).toFloat() * 0.8f * wave
        model.setParameter("ParamHairFront", hairX)
        model.setParameter("ParamHairSide", hairX)
        model.setParameter("ParamHairBack", hairX)
        model.setParameter("ParamHairAhoge", hairY)

        // 耳朵弹簧：固定 1/120s 子步，参数可调，与帧率无关。
        val earTarget = if (idleOn) (angleZ * 0.55f + angleX * 0.18f) * idle.earFollow else 0f
        earAccumulator = (earAccumulator + frameDelta).coerceAtMost(SPRING_MAX_BACKLOG)
        val earSubStep = 1f / 120f
        val earK = idle.earStiffness
        val earD = idle.earDamping
        while (earAccumulator >= earSubStep) {
            earAccumulator -= earSubStep
            earVelocity += (earTarget - earSpring) * earK
            earVelocity *= earD
            earSpring += earVelocity
        }
        val earRange = idle.earLimit
        val ear = earSpring.coerceIn(-earRange, earRange)
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
            lastAppliedExpression = null
            return
        }
        lastAppliedExpression = target
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

    /** 待机上下浮动。幅度/速度可调；IdleSway 关闭或幅度为 0 时完全静止。 */
    private fun idleBob(): Float {
        val idle = config.idleSway
        if (!idle.enabled || idle.bobAmount <= 0f || idle.bobSpeed <= 0f) return 0f
        // 用累积相位而不是 now * speed：改速度时不会整块跳相位。
        return (sin(bobPhase) * idle.bobAmount).toFloat()
    }

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
        /** 弹簧最多补 0.25s 的历史，防止切窗口回来后一次性补几百步。 */
        const val SPRING_MAX_BACKLOG = 0.25f

        /**
         * 角速度（度/秒）→ Live2D 参数度 的基准系数，取自 fix-20。
         * 各轴 Gain 设置是乘在这上面的倍率，默认 1.0 = 原表现。
         */
        const val BASE_YAW_GAIN = 0.0009f
        const val BASE_PITCH_GAIN = 0.0007f
        const val BASE_ROLL_GAIN = 0.0005f

        /** bodyFollow 的默认值，用于把身体 Y/Z 的联动按同一比例缩放。 */
        const val BODY_FOLLOW_BASE = 0.35f

        /** 表情候选名（按优先级排列，命中第一个模型实际拥有的）。 */
        val EXPRESSIONS_HURT = listOf("F01", "F02", "Hurt", "hurt", "Pain", "pain", "Sad", "sad")
        val EXPRESSIONS_HAPPY = listOf("F02", "F03", "Happy", "happy", "Smile", "smile", "Joy")
        val EXPRESSIONS_NORMAL = listOf("F01", "Normal", "normal", "Neutral", "neutral", "Idle")
        val EXPRESSIONS_COMBAT = listOf("F04", "Angry", "angry", "Serious", "serious", "Battle")
        val EXPRESSIONS_WORRIED = listOf("F05", "Worried", "worried", "Fear", "fear", "Surprise")
        val EXPRESSIONS_SLEEPY = listOf("F06", "Sleepy", "sleepy", "Sleep", "sleep", "Tired")
    }

}
