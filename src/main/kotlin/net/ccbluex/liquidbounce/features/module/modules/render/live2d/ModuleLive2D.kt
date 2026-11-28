/*
 * ModuleLive2D —— 标准 Live2D 桌面伙伴模块（支持视线跟随、摸头/点击互动、玩家行为联动）。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/ModuleLive2D.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import net.ccbluex.liquidbounce.config.types.Value
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
import net.ccbluex.liquidbounce.utils.client.inGame
import net.minecraft.client.MouseHandler
import net.minecraft.client.gui.screens.ChatScreen
import kotlin.math.hypot

object ModuleLive2D : ClientModule(
    name = "Live2D",
    category = ModuleCategories.RENDER,
    aliases = listOf("Live2DHud", "Vtuber", "Cubism"),
) {

    private var hud: Live2DHudRenderer? = null

    private data class ModelOption(override val tag: String, val modelName: String?) : Tagged

    private val useSavedModel = ModelOption("Auto (自动选择)", null)
    private val noModelsFound = ModelOption("未检测到模型 (放入 config/live2d/)", null)
    private var discoveredModelNames: List<String> = runCatching { Live2DModelLoader().availableModels() }
        .getOrDefault(emptyList())
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

    /* ---------------------------- 标准 Live2D 配置项 ---------------------------- */

    private val modelNameValue: ChoiceListValue<ModelOption> =
        ChoiceListValue(
            name = "Model",
            defaultValue = if (discoveredModelNames.isEmpty()) noModelsFound else useSavedModel,
            choices = modelChoices(discoveredModelNames),
            unknownChoiceFactory = { savedName ->
                savedName.takeIf { it.isNotBlank() }?.let { ModelOption(it, it) } ?: useSavedModel
            },
        ).also { value(it) }.apply {
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

    private val sizeValue = int("Scale", 260, 40..800, "px").apply {
        onChanged { value -> hud?.setSize(value) }
    }
    private val size by sizeValue

    private val posXValue = int("PosX", 16, -800..3840, "px").apply {
        onChanged { value ->
            hud?.let { it.getConfig().posX = value; it.saveConfigNow() }
        }
    }
    private val posX by posXValue

    private val posYValue = int("PosY", 110, -800..2160, "px").apply {
        onChanged { value ->
            hud?.let { it.getConfig().posY = value; it.saveConfigNow() }
        }
    }
    private val posY by posYValue

    private val opacity by int("Opacity", 100, 10..100, "%").apply {
        onChanged { pushStandardSettings() }
    }

    enum class LookAtMode(override val tag: String) : Tagged {
        Auto("Auto"),
        Mouse("Mouse"),
        Camera("Camera"),
        Off("Off"),
    }

    private val lookAtMode by enumChoice("LookAt", LookAtMode.Auto).apply {
        onChanged { pushStandardSettings() }
    }

    private val lookSensitivity by float("LookSensitivity", 1.0f, 0.0f..2.5f).apply {
        onChanged { pushStandardSettings() }
    }

    private val smoothing by float("Smoothing", 0.12f, 0.02f..0.50f).apply {
        onChanged { pushStandardSettings() }
    }

    private val autoBlink by boolean("AutoBlink", true).apply {
        onChanged { pushStandardSettings() }
    }

    private val autoBreath by boolean("AutoBreath", true).apply {
        onChanged { pushStandardSettings() }
    }

    private val breathSpeed by float("BreathSpeed", 1.0f, 0.1f..3.0f, "x").apply {
        onChanged { pushStandardSettings() }
    }

    private val physicsEnabled by boolean("Physics", true).apply {
        onChanged { pushStandardSettings() }
    }

    private val physicsStrength by float("PhysicsStrength", 1.0f, 0.1f..2.5f, "x").apply {
        onChanged { pushStandardSettings() }
    }

    private val idleMotion by boolean("IdleMotion", true).apply {
        onChanged { pushStandardSettings() }
    }

    private val tapInteraction by boolean("TapInteraction", true).apply {
        onChanged { pushStandardSettings() }
    }

    private val playerInteraction by boolean("PlayerInteraction", true).apply {
        onChanged { pushStandardSettings() }
    }

    enum class ExpressionMode(override val tag: String) : Tagged {
        All("All"),
        Health("Health"),
        Combat("Combat"),
        Environment("Environment"),
        Off("Off"),
    }

    private val autoExpression by enumChoice("AutoExpression", ExpressionMode.All).apply {
        onChanged { pushStandardSettings() }
    }

    private val masksEnabled by boolean("ClippingMask", true).apply {
        onChanged { pushStandardSettings() }
    }

    private val showInScreens by boolean("ShowInScreens", true).apply {
        onChanged { pushStandardSettings() }
    }

    /* ---------------------------- 动态模型选项 ---------------------------- */

    private val detectedValues = mutableListOf<Value<*>>()
    private var settingsModel: Live2DModel? = null
    private var nextOptionsAttempt = 0L

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
            refreshClickGui()
        } catch (error: Exception) {
            detectedValues.addAll(containedValues.filterNot { it in before })
            clearDetectedValues()
            walkKeyPath()
            nextOptionsAttempt = now + 5000L
            logger.error("Live2D 生成动态模块设置失败，将在 5 秒后重试", error)
        }
    }

    private fun refreshClickGui() {
        mc.schedule {
            ModuleClickGui.sync()
            refreshAvailableModelChoices()
        }
    }

    private fun refreshAvailableModelChoices(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now < nextModelDiscoveryAt) return
        nextModelDiscoveryAt = now + 1000L

        val models = runCatching { (hud?.getAvailableModels() ?: Live2DModelLoader().availableModels()) }
            .getOrDefault(emptyList())
        if (!force && models == discoveredModelNames) return
        discoveredModelNames = models

        val currentOption = modelNameValue.get()
        val updatedChoices = modelChoices(models, currentOption)
        refreshingModelChoices = true
        try {
            modelNameValue.updateChoices(updatedChoices)
            val resolved = currentOption.modelName?.let { want ->
                updatedChoices.firstOrNull { it.modelName == want }
            } ?: hud?.getLoadedModelName()?.let { loaded ->
                updatedChoices.firstOrNull { it.modelName == loaded }
            } ?: if (models.isEmpty()) noModelsFound else useSavedModel
            if (modelNameValue.get() != resolved) {
                modelNameValue.set(resolved)
            }
        } finally {
            refreshingModelChoices = false
        }
    }

    private fun pushStandardSettings() {
        val renderer = hud ?: return
        renderer.getConfig().apply {
            opacity = this@ModuleLive2D.opacity / 100f
            lookAtMode = this@ModuleLive2D.lookAtMode.tag
            lookSensitivity = this@ModuleLive2D.lookSensitivity
            smoothing = this@ModuleLive2D.smoothing
            blinkEnabled = this@ModuleLive2D.autoBlink
            autoBreath = this@ModuleLive2D.autoBreath
            breathSpeed = this@ModuleLive2D.breathSpeed
            physicsEnabled = this@ModuleLive2D.physicsEnabled
            physicsStrength = this@ModuleLive2D.physicsStrength
            autoIdleMotion = this@ModuleLive2D.idleMotion
            tapInteraction = this@ModuleLive2D.tapInteraction
            playerInteraction = this@ModuleLive2D.playerInteraction
            expressionsEnabled = this@ModuleLive2D.autoExpression != ExpressionMode.Off
            expressionDetect = this@ModuleLive2D.autoExpression.tag
            masksEnabled = this@ModuleLive2D.masksEnabled
            showInScreens = this@ModuleLive2D.showInScreens
        }
        renderer.saveConfigNow()
    }

    private fun syncTransformFromConfig() {
        val config = hud?.getConfig() ?: return
        if (sizeValue.get() != config.size) sizeValue.set(config.size)
        if (posXValue.get() != config.posX) posXValue.set(config.posX)
        if (posYValue.get() != config.posY) posYValue.set(config.posY)
    }

    private var chatOpen = false
    private var draggingModel = false
    private var pressOnModel = false
    private var pressStartX = 0.0
    private var pressStartY = 0.0
    private var lastTickMouseX = 0.0
    private var lastTickMouseY = 0.0

    /* ---------------------------- 生命周期 ---------------------------- */

    override fun onEnabled() {
        val nativeReady = NativeCubismCore.ensureLoaded(Live2DConfig.defaultPath.parent?.toFile())
        if (nativeReady) {
            logger.info("Live2D 原生核心已就绪: {}", NativeCubismCore.describeBackend())
        } else {
            logger.error("Live2D 原生核心加载失败: {}", NativeCubismCore.describeBackend())
        }
        if (hud == null) {
            hud = Live2DHudRenderer(Live2DConfig.load(), Live2DConfig.defaultPath)
        }
        refreshAvailableModelChoices(force = true)

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
        pushStandardSettings()
        val current = hud
        if (current != null && inGame) mc.schedule {
            if (enabled && hud === current) {
                current.ensureModelLoaded()
                refreshDetectedOptions()
            }
        }
        super.onEnabled()
    }

    override fun onDisabled() {
        hud?.close()
        hud = null
        refreshDetectedOptions()
        draggingModel = false
        pressOnModel = false
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
        if (!chatOpen) {
            draggingModel = false
            pressOnModel = false
        }
    }

    @Suppress("unused")
    private val mouseButtonHandler = handler<MouseButtonEvent> { event ->
        val renderer = hud ?: return@handler
        val screenOpen = mc.gui.screen() != null
        if (!screenOpen || !event.isLeftButton) {
            draggingModel = false
            pressOnModel = false
            return@handler
        }
        val mx = scaledMouseX()
        val my = scaledMouseY()
        if (event.isPressed) {
            val hit = renderer.elementBounds().containsPoint(mx.toInt(), my.toInt())
            pressOnModel = hit
            pressStartX = mx
            pressStartY = my
            if (chatOpen && hit) {
                draggingModel = true
                lastTickMouseX = mx
                lastTickMouseY = my
            }
        } else {
            val movedDist = hypot(mx - pressStartX, my - pressStartY)
            if (pressOnModel && movedDist < 6.5) {
                // Short click -> trigger Live2D Head Pat / Body Tap interaction!
                renderer.onModelTapped(mx, my)
            }
            if (draggingModel) {
                renderer.saveConfigNow()
            }
            draggingModel = false
            pressOnModel = false
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
