/*
 * Live2DHudRenderer —— 模型生命周期、玩家实时互动、标准 Live2D 管线驱动与 HUD 绘制。
 *
 * 标准 Cubism 每帧管线顺序：
 *   resetParametersToDefault -> Motion (Idle / Tap / Event) -> Blink & Breath
 *   -> LookAt (Mouse / Player Camera + Movement) & Player Action Impulses
 *   -> Expressions -> Physics -> Pose & Core Update -> Render
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/Live2DHudRenderer.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.MouseHandler
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.util.Mth
import java.nio.file.Path
import kotlin.math.floor
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

    /* ---------------------------- 动画与互动状态 ---------------------------- */

    private var firstFrame = true
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var lastFrameNanos = 0L
    private var frameDelta = 1f / 60f

    private var bobPhase = 0.0
    private var breathPhase = 0.0
    private var hairPhase = 0.0
    private var chewPhase = 0.0

    private var angleX = 0f
    private var angleY = 0f
    private var angleZ = 0f
    private var bodyAngleX = 0f
    private var eyeBallX = 0f
    private var eyeBallY = 0f

    private var springX = 0f
    private var springVX = 0f
    private var springY = 0f
    private var springVY = 0f
    private var earSpring = 0f
    private var earVelocity = 0f
    private var earAccumulator = 0f
    private var springAccumulatorX = 0f
    private var springAccumulatorY = 0f

    // Player & tap interaction transient timers / impulses
    private var headPatTimer = 0f
    private var tapBodyTimer = 0f
    private var hurtWinceTimer = 0f
    private var reactionYawImpulse = 0f
    private var reactionPitchImpulse = 0f

    private var nextBlinkAt = System.currentTimeMillis() + 1800L
    private var blinkStartedAt = -1L

    private var activeExpression: String? = null
    private var lastAppliedExpression: String? = null
    private var detectedExpression: String? = null
    private var lastDetectAt = 0L

    private var idleMotionStartedAt = 0L
    private var idleMotionCooldown = 0L
    private var lastConfigSave = 0L

    var lastElementBounds: ScreenRectangle? = null
        private set
    private var lastBoundsLeft = 16
    private var lastBoundsTop = 110
    private var lastBoundsWidth = 200
    private var lastBoundsHeight = 260

    var soundActivity: Float = 0f

    /* --------------------------- 鼠标命中与点击互动 --------------------------- */

    fun elementBounds(): ScreenRectangle {
        lastElementBounds?.let { return it }
        val height = config.size.coerceIn(40, 800)
        val width = (height * (loaded?.model?.getBboxAspect() ?: 1f)).toInt().coerceAtLeast(1)
        lastBoundsLeft = config.posX
        lastBoundsTop = config.posY
        lastBoundsWidth = width
        lastBoundsHeight = height
        return ScreenRectangle(config.posX, config.posY, width, height)
    }

    /**
     * 点击互动（摸头 / 点身体）：根据点击在模型包围盒内的相对高度触发动作、表情与物理反馈。
     */
    fun onModelTapped(screenX: Double, screenY: Double): Boolean {
        if (!config.tapInteraction) return false
        val bounds = elementBounds()
        if (!bounds.containsPoint(screenX.toInt(), screenY.toInt())) return false

        val relX = ((screenX - lastBoundsLeft).toFloat() / lastBoundsWidth.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        val relY = ((screenY - lastBoundsTop).toFloat() / lastBoundsHeight.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        val model = loaded?.model

        if (relY < 0.38f) {
            // 摸头互动 (Head Pat)
            headPatTimer = 1.45f
            springVY -= 4.2f
            reactionPitchImpulse = -7.5f
            reactionYawImpulse = (relX - 0.5f) * 14f
            if (model != null) {
                playMatchingMotion(model, listOf("TapHead", "FlickHead", "Head", "Tap", "Touch"), excludeIdle = true)
                playMatchingTransientExpression(model, EXPRESSIONS_HAPPY, hold = 1.5f)
            }
        } else {
            // 点击身体互动 (Tap Body)
            tapBodyTimer = 1.15f
            springVY -= 3.1f
            springVX += if (relX < 0.5f) 3.4f else -3.4f
            reactionYawImpulse = (0.5f - relX) * 18f
            if (model != null) {
                playMatchingMotion(model, listOf("TapBody", "Body", "Tap", "Special", "Flick"), excludeIdle = true)
                playMatchingTransientExpression(model, EXPRESSIONS_WORRIED + EXPRESSIONS_HAPPY, hold = 1.2f)
            }
        }
        return true
    }

    /**
     * 玩家游戏行为互动（攻击、受伤、跳跃、落地、疾跑、潜行、打开聊天等）。
     */
    fun onPlayerGameEvent(event: String) {
        if (!config.playerInteraction) return
        val model = loaded?.model
        when (event) {
            "attack" -> {
                reactionPitchImpulse = (reactionPitchImpulse - 8.5f).coerceIn(-20f, 20f)
                reactionYawImpulse = (reactionYawImpulse + 5.5f).coerceIn(-22f, 22f)
                springVX += 1.4f
            }
            "hurt" -> {
                hurtWinceTimer = 0.70f
                springVY += 5.2f
                springVX -= 2.8f
                reactionPitchImpulse = 10f
                if (model != null) {
                    playMatchingMotion(model, listOf("Hurt", "Damage", "Shake", "Flick"), excludeIdle = true)
                    playMatchingTransientExpression(model, EXPRESSIONS_HURT, hold = 1.2f)
                }
            }
            "jump" -> {
                springVY -= 3.8f
                reactionPitchImpulse = 6.5f
            }
            "land" -> {
                springVY += 4.5f
                reactionPitchImpulse = -5.0f
            }
            "sprint_start" -> {
                springVY -= 1.8f
                reactionPitchImpulse = -4.0f
            }
            "sneak_start" -> {
                springVY += 2.4f
                reactionPitchImpulse = 5.0f
            }
            "heal", "respawn" -> {
                headPatTimer = 1.25f
                springVY -= 2.6f
                if (model != null) {
                    playMatchingTransientExpression(model, EXPRESSIONS_HAPPY, hold = 1.4f)
                }
            }
            "chat_opened" -> {
                headPatTimer = 0.85f
                springVY -= 2.0f
            }
        }
    }

    private fun playMatchingMotion(model: Live2DModel, keywords: List<String>, excludeIdle: Boolean) {
        if (model.motions.isEmpty()) return
        val pool = if (excludeIdle) {
            model.motions.keys.filterNot { it.contains("Idle", ignoreCase = true) }
        } else {
            model.motions.keys.toList()
        }
        if (pool.isEmpty()) return
        val matched = keywords.firstNotNullOfOrNull { kw ->
            pool.firstOrNull { it.contains(kw, ignoreCase = true) }
        } ?: pool.random()
        model.playMotion(matched)
        idleMotionCooldown = System.currentTimeMillis() + 3_500L
    }

    private fun playMatchingTransientExpression(model: Live2DModel, candidates: List<String>, hold: Float) {
        if (!config.expressionsEnabled || model.expressions.isEmpty()) return
        val hit = candidates.firstOrNull { model.hasExpression(it) } ?: return
        model.playExpression(hit, hold, 0.22f)
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
    fun getModel(): Live2DModel? = loaded?.model

    fun playMotion(name: String) {
        val model = loaded?.model ?: return
        if (model.hasMotion(name)) {
            model.playMotion(name)
            idleMotionCooldown = System.currentTimeMillis() + 4_000L
        }
    }

    fun getStatus(): String = buildString {
        append("核心 ").append(NativeCubismCore.describeBackend())
        append(" | 模型 ").append(loadedName ?: config.model.ifBlank { "(未选择)" })
        append(" | ").append(config.size).append("px")
        append(" | ").append(config.posX).append(',').append(config.posY)
        append(" | 视线 ").append(config.lookAtMode)
        append(" | 表情 ").append(getEffectiveExpression() ?: "auto")
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

    private fun resetTiming() {
        lastFrameNanos = 0L
        firstFrame = true
        bobPhase = 0.0
        breathPhase = 0.0
        hairPhase = 0.0
        chewPhase = 0.0
        springAccumulatorX = 0f
        springAccumulatorY = 0f
        earAccumulator = 0f
        springVX = 0f
        springVY = 0f
        earVelocity = 0f
        headPatTimer = 0f
        tapBodyTimer = 0f
        hurtWinceTimer = 0f
        reactionYawImpulse = 0f
        reactionPitchImpulse = 0f
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

    /* ------------------------------ 每帧渲染 ------------------------------ */

    fun render(context: GuiGraphicsExtractor, tickDelta: Float) {
        if (!config.enabled) {
            lastFrameNanos = 0L
            return
        }
        if (mc.player == null || mc.level == null) {
            lastFrameNanos = 0L
            return
        }
        val screen = mc.gui.screen()
        if (screen != null && screen !is net.minecraft.client.gui.screens.ChatScreen && !config.showInScreens) {
            return
        }

        ensureModelLoaded()

        val now = System.nanoTime()
        val previous = lastFrameNanos
        lastFrameNanos = now
        frameDelta = if (previous == 0L) 1f / 60f
        else ((now - previous) / 1_000_000_000f).coerceIn(1f / 240f, 0.1f)

        advancePhasesAndTimers(frameDelta)
        updateTracking(tickDelta)

        val model = loaded?.model
        if (model != null) {
            try {
                // 1. Reset baseline & set configured opacity so motions/gaze compose cleanly without drift
                model.resetParametersToDefault()
                model.modelOpacity = config.opacity.coerceIn(0.1f, 1f)
                model.beginExpressionFrame()

                // 2. Base motion curves (Idle / Tap / Event motion)
                applyIdleMotion(model)
                model.updateTransientAnimations(frameDelta)

                // 3. Additive Live2D LookAt, Player Interaction, Blink & Breath on top of motion
                applyInteractiveParameters(model)

                // 4. Expressions (persistent or auto-detected)
                updateExpressionDetect(model)
                applyExpressions(model)

                // 5. Cubism Physics & Pose
                if (config.physicsEnabled) {
                    val physDt = frameDelta * config.physicsStrength.coerceIn(0.1f, 2.5f)
                    model.physics?.apply(model.core, physDt)
                }
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

        val crouchOffset = if (config.playerInteraction && mc.player?.isShiftKeyDown == true) 5.5f else 0f
        val drawX = config.posX + springX * config.spring.screenX
        val drawY = config.posY + springY * config.spring.screenY + idleBob() + crouchOffset
        val x = floor(drawX).toInt()
        val y = floor(drawY).toInt()

        lastBoundsLeft = x
        lastBoundsTop = y
        lastBoundsWidth = width
        lastBoundsHeight = height
        lastElementBounds = ScreenRectangle(x, y, width, height)

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

            if (loadError != null || current.lastRenderedDrawables == 0) {
                val diagColor = if (loadError != null) Color4b(0xFF, 0x3B, 0x3B, 0x50) else Color4b(0xFF, 0xD5, 0x4F, 0x50)
                context.drawQuad(
                    x.toFloat(), y.toFloat(), x + width.toFloat(), y + height.toFloat(),
                    diagColor,
                )
                val reason = loadError ?: ("0 drawable 可见（可见 drawable=${countVisible(model)}）")
                context.text(mc.font, reason, x, y + height + 2, 0xFFFF5555.toInt(), true)
            }
        } else {
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

    /* ---------------------------- 视线与玩家互动追踪 ---------------------------- */

    private fun advancePhasesAndTimers(dt: Float) {
        val idle = config.idleSway
        val step = dt.toDouble()
        if (idle.enabled) {
            bobPhase = wrapPhase(bobPhase + step * (1.0 / 1.2) * idle.bobSpeed)
            hairPhase = wrapPhase(hairPhase + step * 2.8 * idle.hairSpeed)
        }
        if (config.autoBreath) {
            breathPhase = wrapPhase(breathPhase + step * 2.1 * config.breathSpeed)
        }
        chewPhase = wrapPhase(chewPhase + step * 11.5)

        headPatTimer = (headPatTimer - dt).coerceAtLeast(0f)
        tapBodyTimer = (tapBodyTimer - dt).coerceAtLeast(0f)
        hurtWinceTimer = (hurtWinceTimer - dt).coerceAtLeast(0f)

        val decay = timeFactor(0.16f, dt)
        reactionYawImpulse = lerp(reactionYawImpulse, 0f, decay)
        reactionPitchImpulse = lerp(reactionPitchImpulse, 0f, decay)
    }

    private fun wrapPhase(value: Double): Double {
        val tau = Math.PI * 2.0
        val wrapped = value % tau
        return if (wrapped < 0) wrapped + tau else wrapped
    }

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

        val mode = config.lookAtMode
        val sens = config.lookSensitivity.coerceIn(0f, 3f)
        val screenOpen = mc.gui.screen() != null
        val useMouse = mode.equals("Mouse", ignoreCase = true) ||
            (mode.equals("Auto", ignoreCase = true) && screenOpen)
        val useCamera = mode.equals("Camera", ignoreCase = true) ||
            (mode.equals("Auto", ignoreCase = true) && !screenOpen)

        var targetX = 0f
        var targetY = 0f
        var targetZ = 0f
        var targetEyeX = 0f
        var targetEyeY = 0f

        if (useMouse) {
            // Standard Live2D Mouse Cursor Gaze relative to model's face center
            elementBounds()
            val faceCenterX = lastBoundsLeft + lastBoundsWidth * 0.5f
            val faceCenterY = lastBoundsTop + lastBoundsHeight * 0.28f
            val mouseX = MouseHandler.getScaledXPos(mc.window, mc.mouseHandler.xpos()).toFloat()
            val mouseY = MouseHandler.getScaledYPos(mc.window, mc.mouseHandler.ypos()).toFloat()
            val guiW = mc.window.guiScaledWidth.coerceAtLeast(1).toFloat()
            val guiH = mc.window.guiScaledHeight.coerceAtLeast(1).toFloat()

            val nx = ((mouseX - faceCenterX) / (guiW * 0.42f) * sens).coerceIn(-1.2f, 1.2f)
            val ny = ((faceCenterY - mouseY) / (guiH * 0.42f) * sens).coerceIn(-1.2f, 1.2f)

            targetX = (nx * 28f).coerceIn(-30f, 30f)
            targetY = (ny * 22f).coerceIn(-26f, 26f)
            targetZ = (nx * ny * 10f + nx * 5f).coerceIn(-18f, 18f)
            targetEyeX = nx.coerceIn(-1f, 1f)
            targetEyeY = ny.coerceIn(-1f, 1f)
        } else if (useCamera) {
            val head = config.headSway
            val limit = head.rateLimit
            val yawRate = (deltaYaw / dt).coerceIn(-limit, limit)
            val pitchRate = (deltaPitch / dt).coerceIn(-limit, limit)

            // Combine camera turn velocity + persistent view pitch + player strafe/walk lean!
            val opts = mc.options
            val rawStrafe = (if (opts.keyLeft.isDown) 1f else 0f) - (if (opts.keyRight.isDown) 1f else 0f)
            val rawForward = (if (opts.keyUp.isDown) 1f else 0f) - (if (opts.keyDown.isDown) 1f else 0f)
            val strafe = if (config.playerInteraction) rawStrafe else 0f
            val forward = if (config.playerInteraction) rawForward else 0f
            val sprintBoost = if (config.playerInteraction && player.isSprinting) 1.3f else 1.0f

            val rawX = (-yawRate * 0.042f - strafe * 10f * sprintBoost) * sens
            val rawY = (-pitchRate * 0.032f - pitch * 0.22f + forward * 3.5f * sprintBoost) * sens
            val rawZ = (yawRate * 0.022f - strafe * 7.5f) * sens

            targetX = rawX.coerceIn(-30f, 30f)
            targetY = rawY.coerceIn(-25f, 25f)
            targetZ = rawZ.coerceIn(-18f, 18f)
            targetEyeX = (targetX / 26f).coerceIn(-1f, 1f)
            targetEyeY = (targetY / 22f).coerceIn(-1f, 1f)
        }

        // Overlay player/tap transient impulses
        targetX = (targetX + reactionYawImpulse).coerceIn(-30f, 30f)
        targetY = (targetY + reactionPitchImpulse).coerceIn(-28f, 28f)
        if (headPatTimer > 0f) {
            targetZ = (targetZ + sin(bobPhase * 4.5).toFloat() * 7.5f).coerceIn(-20f, 20f)
        }

        val smooth = timeFactor(config.smoothing.coerceIn(0.02f, 0.6f), dt)
        angleX = lerp(angleX, targetX, smooth)
        angleY = lerp(angleY, targetY, smooth)
        angleZ = lerp(angleZ, targetZ, smooth * 0.8f)
        bodyAngleX = lerp(bodyAngleX, angleX * 0.38f, smooth * 0.6f)
        eyeBallX = lerp(eyeBallX, targetEyeX, (smooth * 1.65f).coerceIn(0f, 1f))
        eyeBallY = lerp(eyeBallY, targetEyeY, (smooth * 1.65f).coerceIn(0f, 1f))

        val spring = config.spring
        if (spring.enabled && config.physicsEnabled) {
            springX = updateSpring(springX, angleX + bodyAngleX, spring.stiffness, spring.damping, true, dt)
            springY = updateSpring(springY, angleY, spring.verticalStiffness, spring.verticalDamping, false, dt)
        } else {
            val settle = timeFactor(0.15f, dt)
            springX = lerp(springX, 0f, settle)
            springY = lerp(springY, 0f, settle)
            springVX = 0f
            springVY = 0f
        }
    }

    private fun timeFactor(perFrame: Float, dt: Float): Float {
        val p = perFrame.coerceIn(0.0001f, 0.9999f)
        return (1f - (1f - p).pow(dt * 60f)).coerceIn(0f, 1f)
    }

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

    /* ---------------------------- 叠加交互参数 ---------------------------- */

    private fun addClampedParameter(model: Live2DModel, id: String, delta: Float) {
        val idx = model.core.indexOfParameter(id)
        if (idx < 0) return
        val next = (model.core.parameterValueAt(idx) + delta)
            .coerceIn(model.core.parameterMin(idx), model.core.parameterMax(idx))
        model.core.setParameterValue(idx, next)
    }

    private fun applyInteractiveParameters(model: Live2DModel) {
        val now = System.currentTimeMillis()
        val t = now / 1000.0
        val player = mc.player

        // 1. Eye blink / Hurt wince / Head-pat happy squint
        var eyeOpen = 1f
        if (config.blinkEnabled) {
            if (blinkStartedAt < 0L && now >= nextBlinkAt) blinkStartedAt = now
            if (blinkStartedAt >= 0L) {
                val progress = ((now - blinkStartedAt) / 170f).coerceIn(0f, 1f)
                eyeOpen = if (progress < 0.5f) 1f - progress * 2f else (progress - 0.5f) * 2f
                if (progress >= 1f) {
                    blinkStartedAt = -1L
                    nextBlinkAt = now + 2200L + (sin(t * 1.7) * 500.0 + 650.0).toLong()
                }
            }
        }

        if (hurtWinceTimer > 0f) {
            model.setParameter("ParamEyeLOpen", 0.05f)
            model.setParameter("ParamEyeROpen", 0.05f)
            model.setParameter("ParamBrowLY", -0.75f)
            model.setParameter("ParamBrowRY", -0.75f)
        } else if (headPatTimer > 0.25f) {
            // Happy '^ ^' smile eyes + cheek blush when head-patted!
            model.setParameter("ParamEyeLOpen", 0.15f)
            model.setParameter("ParamEyeROpen", 0.15f)
            model.setParameter("ParamEyeLSmile", 1f)
            model.setParameter("ParamEyeRSmile", 1f)
            model.setParameter("ParamCheek", 1f)
            model.setParameter("ParamMouthForm", 1f)
        } else if (config.blinkEnabled) {
            model.setParameter("ParamEyeLOpen", eyeOpen)
            model.setParameter("ParamEyeROpen", eyeOpen)
        }

        if (config.soundBlinkEnabled && soundActivity >= config.soundBlinkThreshold) {
            model.setParameter("ParamEyeLOpen", 0f)
            model.setParameter("ParamEyeROpen", 0f)
        }

        // 2. Auto Breath
        if (config.autoBreath && config.breathSpeed > 0f) {
            val breath = ((sin(breathPhase) + 1.0) * 0.5).toFloat()
            model.setParameter("ParamBreath", breath)
        }

        // 3. Additive LookAt (works simultaneously with Idle / Tap motions!)
        addClampedParameter(model, "ParamAngleX", angleX)
        addClampedParameter(model, "ParamAngleY", angleY)
        addClampedParameter(model, "ParamAngleZ", angleZ)
        addClampedParameter(model, "ParamBodyAngleX", bodyAngleX)
        addClampedParameter(model, "ParamBodyAngleY", angleY * 0.24f)
        addClampedParameter(model, "ParamBodyAngleZ", angleZ * 0.32f)
        addClampedParameter(model, "ParamEyeBallX", eyeBallX)
        addClampedParameter(model, "ParamEyeBallY", eyeBallY)

        // 4. Player eating / drinking / item-use interactive mouth + tap surprise mouth
        if (config.playerInteraction && player != null && player.isUsingItem) {
            val chew = ((sin(chewPhase) + 1.0) * 0.42 + 0.12).toFloat()
            model.setParameter("ParamMouthOpenY", chew)
            model.setParameter("ParamMouthForm", 0.6f)
        } else if (tapBodyTimer > 0.3f) {
            model.setParameter("ParamMouthOpenY", (tapBodyTimer * 0.7f).coerceIn(0f, 0.85f))
        }

        // 5. Secondary hair & ear sway when model lacks .physics3.json
        if (config.physicsEnabled && model.physics == null) {
            val physGain = config.physicsStrength.coerceIn(0f, 2.5f)
            val hairX = (angleX * 0.09f + sin(hairPhase).toFloat() * 1.1f) * physGain
            val hairY = (angleY * 0.08f + sin(hairPhase * 0.75 + 1.0).toFloat() * 0.75f) * physGain
            addClampedParameter(model, "ParamHairFront", hairX)
            addClampedParameter(model, "ParamHairSide", hairX)
            addClampedParameter(model, "ParamHairBack", hairX)
            addClampedParameter(model, "ParamHairAhoge", hairY)
        }

        val idle = config.idleSway
        val earTarget = if (config.physicsEnabled) {
            (angleZ * 0.55f + angleX * 0.18f) * config.physicsStrength
        } else 0f
        earAccumulator = (earAccumulator + frameDelta).coerceAtMost(SPRING_MAX_BACKLOG)
        val earSubStep = 1f / 120f
        while (earAccumulator >= earSubStep) {
            earAccumulator -= earSubStep
            earVelocity += (earTarget - earSpring) * idle.earStiffness
            earVelocity *= idle.earDamping
            earSpring += earVelocity
        }
        val ear = earSpring.coerceIn(-idle.earLimit, idle.earLimit)
        addClampedParameter(model, "Param10", ear)
        addClampedParameter(model, "Param14", -ear)
    }

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

        val candidates = ArrayList<String>(4)
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

    fun getEffectiveExpression(): String? = activeExpression ?: detectedExpression

    private fun lerp(from: Float, to: Float, factor: Float): Float =
        from + (to - from) * factor.coerceIn(0f, 1f)

    private fun idleBob(): Float {
        if (!config.autoBreath || config.breathSpeed <= 0f) return 0f
        return (sin(bobPhase) * config.idleSway.bobAmount).toFloat()
    }

    private fun saveConfig() {
        val now = System.currentTimeMillis()
        if (now - lastConfigSave < 300L) return
        lastConfigSave = now
        config.save(configPath)
    }

    fun saveConfigNow() {
        lastConfigSave = System.currentTimeMillis()
        config.save(configPath)
    }

    private companion object {
        const val SPRING_MAX_BACKLOG = 0.25f

        val EXPRESSIONS_HURT = listOf("F01", "F02", "Hurt", "hurt", "Pain", "pain", "Sad", "sad")
        val EXPRESSIONS_HAPPY = listOf("F02", "F03", "Happy", "happy", "Smile", "smile", "Joy")
        val EXPRESSIONS_NORMAL = listOf("F01", "Normal", "normal", "Neutral", "neutral", "Idle")
        val EXPRESSIONS_COMBAT = listOf("F04", "Angry", "angry", "Serious", "serious", "Battle")
        val EXPRESSIONS_WORRIED = listOf("F05", "Worried", "worried", "Fear", "fear", "Surprise")
        val EXPRESSIONS_SLEEPY = listOf("F06", "Sleepy", "sleepy", "Sleep", "sleep", "Tired")
    }
}
