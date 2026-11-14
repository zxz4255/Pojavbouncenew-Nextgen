/*
 * ModuleLive2D —— LiquidBounce 模块入口。
 *
 * 设置项与原版 MinecraftLive2d / Samsara 对齐；位置、大小、模型、表情、事件映射
 * 存在 <gamedir>/LiquidBounce/live2d.json（Live2DConfig）。
 *
 * 模型目录：<gamedir>/config/live2d/ 下任意深度的解压目录；运行时周期扫描并同步到 Model 下拉项。
 *
 * fix-21：
 *   1. 新增三组可调摆动参数：HeadSway / Spring / IdleSway，外加 BreathSpeed。
 *   2. 修掉「改设置不生效」——旧实现只在 onEnabled() 里同步一次，
 *      在 GUI 里拖 SwayStrength / Smoothing 滑块完全没反应。现在每个值
 *      都有 onChanged，改完立刻推到运行中的 HUD。
 *   3. 拖拽/滚轮改位置后回写模块设置，避免下次推送时用旧坐标把模型弹回去。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/ModuleLive2D.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.ChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.MouseScrollEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.ScreenEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.integration.screen.CustomScreenType
import net.ccbluex.liquidbounce.integration.screen.ScreenManager
import net.ccbluex.liquidbounce.integration.screen.impl.CustomSharedMinecraftScreen
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.inGame
import net.minecraft.client.MouseHandler
import net.minecraft.client.gui.screens.ChatScreen

object ModuleLive2D : ClientModule(
    "Live2D",
    ModuleCategories.RENDER,
    state = false,
) {

    /* ------------------------------ 设置 ------------------------------ */

    /** Runtime renderer owned by this module. */
    private var hud: Live2DHudRenderer? = null

    private data class ModelOption(
        val modelName: String?,
        override val tag: String,
    ) : Tagged

    private val useSavedModel = ModelOption(null, "Use saved model")
    private val noModelsFound = ModelOption(null, "No models detected")
    private var discoveredModelNames = Live2DModelLoader().availableModels()
    private var nextModelDiscoveryAt = 0L
    private var refreshingModelChoices = false
    private var syncingModelChoice = false

    private fun modelChoices(models: List<String>, unresolved: ModelOption? = null): Set<ModelOption> =
        buildSet {
            add(useSavedModel)
            if (models.isEmpty()) {
                add(noModelsFound)
                unresolved?.takeIf { it.modelName != null }?.let(::add)
            }
            models.forEach { add(ModelOption(it, it)) }
        }

    private val modelNameValue: ChoiceListValue<ModelOption> =
        ChoiceListValue(
            name = "Model",
            defaultValue = if (discoveredModelNames.isEmpty()) noModelsFound else useSavedModel,
            choices = modelChoices(discoveredModelNames),
            unknownChoiceFactory = { savedName ->
                savedName.takeIf { it.isNotBlank() }?.let { ModelOption(it, it) } ?: useSavedModel
            },
        ).also { value(it) }.apply {
            // Keep the concrete ChoiceListValue type: chaining onChanged() returns Value<T>,
            // which would hide choices/updateChoices from the live model scanner.
            onChanged { option ->
                if (refreshingModelChoices || syncingModelChoice) return@onChanged
                val name = option.modelName ?: return@onChanged
                val current = hud ?: return@onChanged
                mc.schedule {
                    if (hud === current && enabled &&
                        (current.getLoadedModelName() != name || current.getConfig().model != name)
                    ) {
                        current.setModel(name)
                    }
                }
            }
        }
    private val modelName by modelNameValue

    private fun setModelChoice(name: String): Boolean {
        val option = modelNameValue.choices.firstOrNull { it.modelName == name } ?: return false
        if (modelNameValue.get() == option) return false
        syncingModelChoice = true
        try {
            modelNameValue.set(option)
        } finally {
            syncingModelChoice = false
        }
        return true
    }

    // Model-dependent values are inserted directly into this module after loading. Keep the
    // actual Value instances so model changes can remove every old choice and its callbacks.
    private val detectedValues = mutableListOf<Value<*>>()
    private var settingsModel: Live2DModel? = null
    private var nextOptionsAttempt = 0L

    private fun clearDetectedValues() {
        detectedValues.forEach { value ->
            if (value is ValueGroup && value.base === this) drop(value)
            else inner.remove(value)
        }
        detectedValues.clear()
    }

    private fun refreshDetectedOptions() {
        val currentHud = hud
        currentHud?.getLoadedModelName()?.let { loadedName ->
            if (setModelChoice(loadedName)) refreshClickGui()
        }
        val current = currentHud?.getModel()
        if (current === settingsModel) return
        val now = System.currentTimeMillis()
        if (current != null && now < nextOptionsAttempt) return
        clearDetectedValues()
        settingsModel = null
        if (current == null || currentHud == null) {
            walkKeyPath()
            refreshClickGui()
            return
        }
        val before = containedValues.toSet()
        try {
            Live2DDynamicSettings(currentHud, current).addTo(this)
            detectedValues.addAll(containedValues.filterNot { it in before })
            settingsModel = current
            nextOptionsAttempt = 0L
            logger.info("Live2D 动态设置已生成: 模型={} 表情={} 动作={} 部件={} 参数={} 根项目={}",
                currentHud.getLoadedModelName(), current.expressions.size, current.motions.size,
                current.core.partCount, current.core.parameterCount, detectedValues.size)
            refreshClickGui()
        } catch (error: Exception) {
            // Remove partial registration; retry later rather than marking this model as done.
            detectedValues.addAll(containedValues.filterNot { it in before })
            clearDetectedValues()
            walkKeyPath()
            nextOptionsAttempt = now + 5000L
            logger.error("Live2D 生成动态模块设置失败，将在 5 秒后重试", error)
        }
    }

    private fun refreshClickGui() {
        mc.schedule {
            // A standalone cached GUI has its own browser. The shared browser is separate;
            // only reload it while the user is actually viewing the ordinary ClickGUI.
            ModuleClickGui.sync()
            val screen = mc.gui.screen()
            if (screen is CustomSharedMinecraftScreen && screen.screenType == CustomScreenType.CLICK_GUI) {
                ScreenManager.mainBrowser?.reload()
            }
        }
    }

    /** Re-scan once per second while active so copied, removed, or renamed models update the selector. */
    private fun refreshAvailableModelChoices(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now < nextModelDiscoveryAt) return
        nextModelDiscoveryAt = now + 1000L

        val currentHud = hud
        val models = currentHud?.getAvailableModels() ?: Live2DModelLoader().availableModels()
        val modelsChanged = models != discoveredModelNames
        val oldSelection = modelNameValue.get()
        val unresolved = oldSelection.takeIf { models.isEmpty() && it.modelName != null }
        val nextChoices = modelChoices(models, unresolved)
        val configuredFallback = currentHud?.getConfig()?.model?.takeIf { it in models }
        val fallback = (configuredFallback ?: models.firstOrNull())
            ?.let { ModelOption(it, it) } ?: noModelsFound

        refreshingModelChoices = true
        val choicesUpdated = try {
            modelNameValue.updateChoices(nextChoices, fallback)
        } finally {
            refreshingModelChoices = false
        }
        val selectionChanged = modelNameValue.get() != oldSelection
        discoveredModelNames = models

        if (modelsChanged || choicesUpdated) refreshClickGui()

        if (currentHud != null && (modelsChanged || selectionChanged)) {
            val selected = modelNameValue.get().modelName?.takeIf { it in models }
            val configured = currentHud.getConfig().model.takeIf { it in models }
            val target = selected ?: configured ?: models.firstOrNull()
            if (target != null &&
                (currentHud.getLoadedModelName() != target || currentHud.getConfig().model != target)
            ) {
                mc.schedule {
                    if (hud === currentHud && enabled &&
                        (currentHud.getLoadedModelName() != target || currentHud.getConfig().model != target)
                    ) {
                        currentHud.setModel(target)
                    }
                }
            }
        }
    }

    private val sizeValue = int("Size", 260, 40..800, "px").onChanged { value ->
        hud?.apply { getConfig().size = value; saveConfigNow() }
    }
    private val size by sizeValue

    private val posXValue = int("PosX", 16, -2000..4000, "px").onChanged { value ->
        hud?.apply { getConfig().posX = value; saveConfigNow() }
    }
    private val posX by posXValue

    private val posYValue = int("PosY", 110, -2000..4000, "px").onChanged { value ->
        hud?.apply { getConfig().posY = value; saveConfigNow() }
    }
    private val posY by posYValue

    /* ---------------------------- 摆动：总控 ---------------------------- */

    private val swayStrengthValue = float("SwayStrength", 3.0f, 0.0f..10.0f)
        .onChanged { onSwayValueChanged() }
    private val swayStrength by swayStrengthValue

    private val smoothingValue = float("Smoothing", 0.1f, 0.02f..0.6f)
        .onChanged { onSwayValueChanged() }
    private val smoothing by smoothingValue

    private val followPitchValue = boolean("FollowPitch", true)
        .onChanged { onSwayValueChanged() }
    private val followPitch by followPitchValue

    private val breathSpeedValue = float("BreathSpeed", 1.0f, 0.0f..4.0f, "x")
        .onChanged { onSwayValueChanged() }
    private val breathSpeed by breathSpeedValue

    /**
     * 摆动预设。选一个会一次性写入下面所有分组；
     * 之后手改任何一项会自动回到 Custom，标签不会撒谎。
     */
    enum class SwayPreset(override val tag: String) : Tagged {
        Custom("Custom"),
        Subtle("Subtle"),
        Natural("Natural"),
        Lively("Lively"),
    }

    private val swayPresetValue = enumChoice("SwayPreset", SwayPreset.Natural)
        .onChanged { preset ->
            if (settingsReady && !applyingPreset && preset != SwayPreset.Custom) applyPreset(preset)
        }

    /** 预设写入期间抑制「手改 → 回到 Custom」的回调，否则会自己把标签打回去。 */
    private var applyingPreset = false

    /**
     * 三个摆动分组构建完成后才置 true。
     *
     * 配置反序列化会对每个 Value 调 set()，从而触发 onChanged。若不挡住，
     * 早期回调会去读还没初始化的 HeadSway/Spring/IdleSway（Kotlin object 懒加载），
     * 轻则 NPE 重则把刚读进来的值又覆盖掉。
     */
    private var settingsReady = false

    private val blink by boolean("Blink", true).onChanged { pushFeatureSettings() }
    private val expressionsEnabled by boolean("Expressions", true).onChanged { pushFeatureSettings() }
    private val masks by boolean("Masks", true).onChanged { pushFeatureSettings() }
    private val idleMotion by boolean("IdleMotion", true).onChanged { pushFeatureSettings() }

    /** 表情自动检测模式。 */
    enum class ExpressionMode(override val tag: String) : Tagged {
        Off("Off"),
        Health("Health"),
        Combat("Combat"),
        Environment("Environment"),
        All("All"),
    }

    private val expressionMode by enumChoice("ExpressionDetect", ExpressionMode.Health)
        .onChanged { pushFeatureSettings() }

    init {
        tree(HeadSway)
        tree(Spring)
        tree(IdleSway)
        settingsReady = true
    }

    /* ---------------------------- 摆动：头部 ---------------------------- */

    /**
     * 视角角速度 → 头部角度。
     * Gain 是乘在 fix-20 基准系数上的倍率，1.0 = 原表现。
     */
    private object HeadSway : ToggleableValueGroup(ModuleLive2D, "HeadSway", true) {
        val yawGain = swayFloat("YawGain", 1.0f, 0.0f..3.0f, "x")
        val pitchGain = swayFloat("PitchGain", 1.0f, 0.0f..3.0f, "x")
        val rollGain = swayFloat("RollGain", 1.0f, 0.0f..3.0f, "x")

        val maxYaw = swayFloat("MaxYaw", 30f, 0f..60f, "°")
        val maxPitch = swayFloat("MaxPitch", 20f, 0f..60f, "°")
        val maxRoll = swayFloat("MaxRoll", 18f, 0f..60f, "°")

        val rateLimit = swayFloat("RateLimit", 720f, 60f..2160f, "°/s")

        val bodyFollow = swayFloat("BodyFollow", 0.35f, 0.0f..1.0f)
        val rollSmoothing = swayFloat("RollSmoothing", 0.75f, 0.1f..1.0f)
        val bodySmoothing = swayFloat("BodySmoothing", 0.55f, 0.1f..1.0f)
        val eyeFollow = swayFloat("EyeFollow", 1.0f, 0.0f..2.0f, "x")

        init {
            enabledValue.onChanged { onSwayValueChanged() }
        }
    }

    /* ---------------------------- 摆动：弹簧 ---------------------------- */

    /** 头部角度 → 模型在屏幕上的位移。Stiffness 越大回弹越快，Damping 越小摆得越久。 */
    private object Spring : ToggleableValueGroup(ModuleLive2D, "Spring", true) {
        val stiffness = swayFloat("Stiffness", 0.12f, 0.01f..0.6f)
        val damping = swayFloat("Damping", 0.78f, 0.30f..0.98f)
        val verticalStiffness = swayFloat("VerticalStiffness", 0.08f, 0.01f..0.6f)
        val verticalDamping = swayFloat("VerticalDamping", 0.82f, 0.30f..0.98f)

        val screenX = swayFloat("ScreenX", 0.55f, 0.0f..3.0f, "px")
        val screenY = swayFloat("ScreenY", 0.30f, 0.0f..3.0f, "px")

        init {
            enabledValue.onChanged { onSwayValueChanged() }
        }
    }

    /* ---------------------------- 摆动：待机 ---------------------------- */

    /** 与玩家输入无关的自主摆动：上下浮动、头发飘动、耳朵摆动。 */
    private object IdleSway : ToggleableValueGroup(ModuleLive2D, "IdleSway", true) {
        val bobAmount = swayFloat("BobAmount", 1.2f, 0.0f..8.0f, "px")
        val bobSpeed = swayFloat("BobSpeed", 1.0f, 0.0f..4.0f, "x")

        val hairFollow = swayFloat("HairFollow", 1.0f, 0.0f..4.0f, "x")
        val hairWave = swayFloat("HairWave", 1.0f, 0.0f..4.0f, "x")
        val hairSpeed = swayFloat("HairSpeed", 1.0f, 0.0f..4.0f, "x")

        val earFollow = swayFloat("EarFollow", 1.0f, 0.0f..4.0f, "x")
        val earStiffness = swayFloat("EarStiffness", 0.08f, 0.01f..0.4f)
        val earDamping = swayFloat("EarDamping", 0.86f, 0.30f..0.98f)
        val earLimit = swayFloat("EarLimit", 12f, 0f..30f, "°")

        init {
            enabledValue.onChanged { onSwayValueChanged() }
        }
    }

    /**
     * 在任意 ValueGroup 上建一个「改了就立即生效」的 float。
     * 返回 Value 而不是委托属性，预设需要能反向往里写。
     */
    private fun ValueGroup.swayFloat(
        name: String,
        default: Float,
        range: ClosedFloatingPointRange<Float>,
        suffix: String = "",
    ): Value<Float> = float(name, default, range, suffix).onChanged { onSwayValueChanged() }

    /* ---------------------------- 设置同步 ---------------------------- */

    /** 手改任何摆动项 → 预设标签回到 Custom，然后推送。 */
    private fun onSwayValueChanged() {
        if (!settingsReady || applyingPreset) return
        if (swayPresetValue.get() != SwayPreset.Custom) {
            applyingPreset = true
            runCatching { swayPresetValue.set(SwayPreset.Custom) }
            applyingPreset = false
        }
        pushSwaySettings()
    }

    /**
     * 把摆动设置推给运行中的 HUD。**每帧读的是 config 对象**，
     * 所以这里写完下一帧就生效，不需要重新开启模块。
     */
    private fun pushSwaySettings() {
        if (!settingsReady) return
        val renderer = hud ?: return
        val config = renderer.getConfig()

        config.swayStrength = swayStrength
        config.smoothing = smoothing
        config.followPitch = followPitch
        config.breathSpeed = breathSpeed

        config.headSway.apply {
            enabled = HeadSway.enabled
            yawGain = HeadSway.yawGain.get()
            pitchGain = HeadSway.pitchGain.get()
            rollGain = HeadSway.rollGain.get()
            maxYaw = HeadSway.maxYaw.get()
            maxPitch = HeadSway.maxPitch.get()
            maxRoll = HeadSway.maxRoll.get()
            rateLimit = HeadSway.rateLimit.get()
            bodyFollow = HeadSway.bodyFollow.get()
            rollSmoothing = HeadSway.rollSmoothing.get()
            bodySmoothing = HeadSway.bodySmoothing.get()
            eyeFollow = HeadSway.eyeFollow.get()
        }

        config.spring.apply {
            enabled = Spring.enabled
            stiffness = Spring.stiffness.get()
            damping = Spring.damping.get()
            verticalStiffness = Spring.verticalStiffness.get()
            verticalDamping = Spring.verticalDamping.get()
            screenX = Spring.screenX.get()
            screenY = Spring.screenY.get()
        }

        config.idleSway.apply {
            enabled = IdleSway.enabled
            bobAmount = IdleSway.bobAmount.get()
            bobSpeed = IdleSway.bobSpeed.get()
            hairFollow = IdleSway.hairFollow.get()
            hairWave = IdleSway.hairWave.get()
            hairSpeed = IdleSway.hairSpeed.get()
            earFollow = IdleSway.earFollow.get()
            earStiffness = IdleSway.earStiffness.get()
            earDamping = IdleSway.earDamping.get()
            earLimit = IdleSway.earLimit.get()
        }

        config.sanitize()
        renderer.saveConfigNow()
    }

    /** 与摆动无关的开关，同样改成即时生效。 */
    private fun pushFeatureSettings() {
        if (!settingsReady) return
        val renderer = hud ?: return
        renderer.getConfig().apply {
            blinkEnabled = blink
            expressionsEnabled = this@ModuleLive2D.expressionsEnabled
            masksEnabled = masks
            autoIdleMotion = idleMotion
            expressionDetect = expressionMode.tag
        }
        renderer.saveConfigNow()
    }

    /**
     * 一次性写入一组预设。全程举着 [applyingPreset]，
     * 免得每个 set 都把标签打回 Custom / 反复写盘。
     */
    private fun applyPreset(preset: SwayPreset) {
        applyingPreset = true
        try {
            when (preset) {
                SwayPreset.Subtle -> {
                    swayStrengthValue.set(1.5f)
                    smoothingValue.set(0.08f)
                    HeadSway.yawGain.set(0.7f)
                    HeadSway.pitchGain.set(0.6f)
                    HeadSway.rollGain.set(0.5f)
                    HeadSway.maxYaw.set(18f)
                    HeadSway.maxPitch.set(12f)
                    HeadSway.maxRoll.set(10f)
                    HeadSway.bodyFollow.set(0.25f)
                    HeadSway.eyeFollow.set(0.8f)
                    Spring.stiffness.set(0.10f)
                    Spring.damping.set(0.85f)
                    Spring.verticalStiffness.set(0.07f)
                    Spring.verticalDamping.set(0.88f)
                    Spring.screenX.set(0.30f)
                    Spring.screenY.set(0.18f)
                    IdleSway.bobAmount.set(0.7f)
                    IdleSway.hairWave.set(0.6f)
                    IdleSway.earFollow.set(0.6f)
                }

                SwayPreset.Natural -> {
                    swayStrengthValue.set(3.0f)
                    smoothingValue.set(0.10f)
                    HeadSway.yawGain.set(1.0f)
                    HeadSway.pitchGain.set(1.0f)
                    HeadSway.rollGain.set(1.0f)
                    HeadSway.maxYaw.set(30f)
                    HeadSway.maxPitch.set(20f)
                    HeadSway.maxRoll.set(18f)
                    HeadSway.bodyFollow.set(0.35f)
                    HeadSway.eyeFollow.set(1.0f)
                    Spring.stiffness.set(0.12f)
                    Spring.damping.set(0.78f)
                    Spring.verticalStiffness.set(0.08f)
                    Spring.verticalDamping.set(0.82f)
                    Spring.screenX.set(0.55f)
                    Spring.screenY.set(0.30f)
                    IdleSway.bobAmount.set(1.2f)
                    IdleSway.hairWave.set(1.0f)
                    IdleSway.earFollow.set(1.0f)
                }

                SwayPreset.Lively -> {
                    swayStrengthValue.set(5.0f)
                    smoothingValue.set(0.16f)
                    HeadSway.yawGain.set(1.4f)
                    HeadSway.pitchGain.set(1.2f)
                    HeadSway.rollGain.set(1.3f)
                    HeadSway.maxYaw.set(40f)
                    HeadSway.maxPitch.set(26f)
                    HeadSway.maxRoll.set(26f)
                    HeadSway.bodyFollow.set(0.45f)
                    HeadSway.eyeFollow.set(1.2f)
                    Spring.stiffness.set(0.16f)
                    Spring.damping.set(0.72f)
                    Spring.verticalStiffness.set(0.11f)
                    Spring.verticalDamping.set(0.76f)
                    Spring.screenX.set(0.90f)
                    Spring.screenY.set(0.50f)
                    IdleSway.bobAmount.set(2.0f)
                    IdleSway.hairWave.set(1.6f)
                    IdleSway.earFollow.set(1.5f)
                }

                SwayPreset.Custom -> Unit
            }
        } finally {
            applyingPreset = false
        }
        pushSwaySettings()
    }

    /**
     * 拖拽 / 滚轮改的是 config，这里把结果回写到模块设置。
     * 否则下一次 pushSwaySettings / onEnabled 会用旧坐标把模型弹回去。
     */
    private fun syncTransformFromConfig() {
        val config = hud?.getConfig() ?: return
        if (sizeValue.get() != config.size) sizeValue.set(config.size)
        if (posXValue.get() != config.posX) posXValue.set(config.posX)
        if (posYValue.get() != config.posY) posYValue.set(config.posY)
    }

    /** 拖拽/滚轮只在聊天框内生效，避免和正常操作抢输入。 */
    private var chatOpen: Boolean = false
    private var draggingModel: Boolean = false
    private var lastTickMouseX: Double = 0.0
    private var lastTickMouseY: Double = 0.0

    /* ---------------------------- 生命周期 ---------------------------- */

    override fun onEnabled() {
        if (hud == null) {
            hud = Live2DHudRenderer(Live2DConfig.load(), Live2DConfig.defaultPath)
        }
        refreshAvailableModelChoices(force = true)

        // 模块设置覆盖持久配置。必须在 hud 已存在时也执行，
        // 否则在 GUI 里改 PosX/PosY/Size 永远不会生效。
        hud?.let { renderer ->
            val config = renderer.getConfig()
            config.size = size
            config.posX = posX
            config.posY = posY

            val available = renderer.getAvailableModels()
            val selectedModel = modelName.modelName?.takeIf { it in available }
            val configuredModel = config.model.takeIf { it in available }
            val modelToUse = selectedModel ?: configuredModel ?: available.firstOrNull()
            if (modelToUse != null) {
                config.model = modelToUse
                if (setModelChoice(modelToUse)) refreshClickGui()
            }
        }
        pushSwaySettings()
        pushFeatureSettings()
        hud?.saveConfigNow()
        val current = hud
        if (current != null && inGame) mc.schedule {
            if (enabled && hud === current) {
                current.ensureModelLoaded()
                refreshDetectedOptions()
            }
        }

        val available = hud?.getAvailableModels() ?: emptyList()
        if (available.isEmpty()) {
            logger.warn("Live2D：{} 下未找到 *.model3.json", Live2DModelLoader().modelsRoot)
        } else {
            logger.info("Live2D：发现 {} 个模型：{}", available.size, available.joinToString(", "))
        }
        super.onEnabled()
    }

    override fun onDisabled() {
        hud?.close()
        hud = null
        refreshDetectedOptions()
        draggingModel = false
        super.onDisabled()
    }

    /* ------------------------------ 事件 ------------------------------ */

    @Suppress("unused")
    private val overlayHandler = handler<OverlayRenderEvent> { event ->
        hud?.render(event.context, event.tickDelta)
        refreshAvailableModelChoices()
        refreshDetectedOptions()
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        hud?.handleTick()
        refreshAvailableModelChoices()
        // 拖拽：用 tick 采样鼠标位移，避免依赖 UI 事件里不保证存在的坐标。
        if (chatOpen && draggingModel) {
            val renderer = hud
            if (renderer != null) {
                val mouseX = scaledMouseX()
                val mouseY = scaledMouseY()
                val dx = (mouseX - lastTickMouseX).toInt()
                val dy = (mouseY - lastTickMouseY).toInt()
                if (dx != 0 || dy != 0) {
                    renderer.movePosition(dx, dy)
                    syncTransformFromConfig()
                }
                lastTickMouseX = mouseX
                lastTickMouseY = mouseY
            }
        }
    }

    @Suppress("unused")
    private val screenHandler = handler<ScreenEvent> { event ->
        chatOpen = event.screen is ChatScreen
        if (!chatOpen) draggingModel = false
    }

    @Suppress("unused")
    private val mouseButtonHandler = handler<MouseButtonEvent> { event ->
        if (!chatOpen || !event.isLeftButton) {
            draggingModel = false
            return@handler
        }
        if (event.isPressed) {
            val renderer = hud ?: return@handler
            val bounds = renderer.elementBounds()
            val mouseX = scaledMouseX().toInt()
            val mouseY = scaledMouseY().toInt()
            draggingModel = bounds.containsPoint(mouseX, mouseY)
            if (draggingModel) {
                lastTickMouseX = scaledMouseX()
                lastTickMouseY = scaledMouseY()
            }
        } else {
            if (draggingModel) hud?.saveConfigNow()
            draggingModel = false
        }
    }

    @Suppress("unused")
    private val mouseScrollHandler = handler<MouseScrollEvent> { event ->
        if (!chatOpen) return@handler
        val renderer = hud ?: return@handler
        val bounds = renderer.elementBounds()
        if (!bounds.containsPoint(scaledMouseX().toInt(), scaledMouseY().toInt())) return@handler
        val config = renderer.getConfig()
        renderer.setSize(config.size + (event.vertical * config.wheelResizeStep).toInt())
        syncTransformFromConfig()
    }

    /** MouseHandler 里存的是窗口坐标，换算成 GUI 坐标。 */
    private val mouseHandler: MouseHandler
        get() = mc.mouseHandler

    private fun scaledMouseX(): Double =
        MouseHandler.getScaledXPos(mc.window, mouseHandler.xpos())

    private fun scaledMouseY(): Double =
        MouseHandler.getScaledYPos(mc.window, mouseHandler.ypos())

    /* ---------------------------- 对外接口 ---------------------------- */

    fun cycleModel() = hud?.cycleModel()

    fun setModel(name: String) = hud?.setModel(name)

    fun cycleExpression() = hud?.cycleExpression()

    fun toggleHud() = hud?.toggleEnabled()

    fun status(): String = hud?.getStatus() ?: "Live2D 未启用"

    fun renderer(): Live2DHudRenderer? = hud

    fun availableModels(): List<String> = hud?.getAvailableModels() ?: emptyList()

    fun availableExpressions(): List<String> = hud?.getAvailableExpressions() ?: emptyList()

    fun availableMotions(): List<String> = hud?.getAvailableMotions() ?: emptyList()

    fun currentModelName(): String = hud?.getLoadedModelName() ?: ""

    fun triggerEvent(event: String) = hud?.getEventSystem()?.fire(event)
}
