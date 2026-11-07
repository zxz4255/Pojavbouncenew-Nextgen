/*
 * Live2DConfig —— 独立于模块设置的状态文件（位置、大小、模型、表情、事件映射、摆动参数）。
 *
 * 存到 <gamedir>/LiquidBounce/live2d.json，与模块自身的 Value 设置解耦：
 * 位置/大小允许通过鼠标拖拽、滚轮实时改动，不必每次触发一次配置写盘。
 *
 * fix-21：新增三组摆动参数（HeadSway / Spring / IdleSway）。
 * 所有默认值与 fix-20 的写死常量 **逐位等价**，升级后观感不变。
 * 旧配置文件缺少这些键时自动取默认值，不需要删配置。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/Live2DConfig.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.utils.client.logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class Live2DConfig {

    var enabled: Boolean = true
    var model: String = ""
    var size: Int = 260
    var posX: Int = 16
    var posY: Int = 110

    var swayStrength: Float = 3.0f
    var smoothing: Float = 0.1f
    var followPitch: Boolean = true

    /** 呼吸速度倍率；1.0 = fix-20 的 sin(t * 2.1)。 */
    var breathSpeed: Float = 1.0f

    var blinkEnabled: Boolean = true
    var expressionsEnabled: Boolean = true
    var masksEnabled: Boolean = true

    var chatDragEnabled: Boolean = true
    var wheelResizeStep: Int = 8

    var soundBlinkEnabled: Boolean = false
    var soundBlinkThreshold: Float = 0.5f

    var idleMotionGroup: String = "Idle"
    var autoIdleMotion: Boolean = true

    /** 表情自动检测模式，与 ModuleLive2D.ExpressionMode 的 tag 对应。 */
    var expressionDetect: String = "Health"

    /* ---------------------------- 摆动参数 ---------------------------- */

    /**
     * 头部摆动：视角角速度 → ParamAngleX/Y/Z。
     *
     * 所有 *Gain 都是乘在 fix-20 基准系数上的倍率，默认 1.0 即原表现：
     *   ParamAngleX = -yawRate   * 0.0009 * swayStrength * yawGain
     *   ParamAngleY = -pitchRate * 0.0007 * swayStrength * pitchGain
     *   ParamAngleZ =  yawRate   * 0.0005 * swayStrength * rollGain
     */
    class HeadSwaySettings {
        /** 关闭后角度平滑归零，模型回到中性姿态（仍然完整渲染）。 */
        var enabled: Boolean = true

        var yawGain: Float = 1.0f
        var pitchGain: Float = 1.0f
        var rollGain: Float = 1.0f

        /** 角度上限（Live2D 参数度）。 */
        var maxYaw: Float = 30f
        var maxPitch: Float = 20f
        var maxRoll: Float = 18f

        /** 角速度钳制（度/秒），防止瞬移/甩头把参数打飞。 */
        var rateLimit: Float = 720f

        /** ParamBodyAngleX 跟随 ParamAngleX 的比例。 */
        var bodyFollow: Float = 0.35f

        /**  roll / body 相对主动平滑系数的倍率。 */
        var rollSmoothing: Float = 0.75f
        var bodySmoothing: Float = 0.55f

        /** 眼球跟随强度；0 = 眼睛不动。 */
        var eyeFollow: Float = 1.0f

        fun sanitize() {
            yawGain = yawGain.finite(1f).coerceIn(0f, 3f)
            pitchGain = pitchGain.finite(1f).coerceIn(0f, 3f)
            rollGain = rollGain.finite(1f).coerceIn(0f, 3f)
            maxYaw = maxYaw.finite(30f).coerceIn(0f, 60f)
            maxPitch = maxPitch.finite(20f).coerceIn(0f, 60f)
            maxRoll = maxRoll.finite(18f).coerceIn(0f, 60f)
            rateLimit = rateLimit.finite(720f).coerceIn(60f, 2160f)
            bodyFollow = bodyFollow.finite(0.35f).coerceIn(0f, 1f)
            rollSmoothing = rollSmoothing.finite(0.75f).coerceIn(0.1f, 1f)
            bodySmoothing = bodySmoothing.finite(0.55f).coerceIn(0.1f, 1f)
            eyeFollow = eyeFollow.finite(1f).coerceIn(0f, 2f)
        }
    }

    /**
     * 弹簧：把头部角度转成模型在屏幕上的位移（晃动 / 回弹）。
     *
     * stiffness 越大回弹越快，damping 越小摆动越久。
     * 积分是固定 1/120s 子步，参数与帧率无关。
     */
    class SpringSettings {
        /** 关闭后模型不再随转头在屏幕上位移（呼吸浮动仍由 IdleSway 控制）。 */
        var enabled: Boolean = true

        var stiffness: Float = 0.12f
        var damping: Float = 0.78f
        var verticalStiffness: Float = 0.08f
        var verticalDamping: Float = 0.82f

        /** 屏幕位移倍率（GUI 像素 / 参数度）。 */
        var screenX: Float = 0.55f
        var screenY: Float = 0.30f

        fun sanitize() {
            stiffness = stiffness.finite(0.12f).coerceIn(0.01f, 0.6f)
            damping = damping.finite(0.78f).coerceIn(0.30f, 0.98f)
            verticalStiffness = verticalStiffness.finite(0.08f).coerceIn(0.01f, 0.6f)
            verticalDamping = verticalDamping.finite(0.82f).coerceIn(0.30f, 0.98f)
            screenX = screenX.finite(0.55f).coerceIn(0f, 3f)
            screenY = screenY.finite(0.30f).coerceIn(0f, 3f)
        }
    }

    /**
     * 待机摆动：上下浮动、头发飘动、耳朵摆动。
     * 与玩家输入无关，纯粹让模型「活着」。
     */
    class IdleSwaySettings {
        var enabled: Boolean = true

        /** 整体上下浮动幅度（GUI 像素）与速度倍率。 */
        var bobAmount: Float = 1.2f
        var bobSpeed: Float = 1.0f

        /** 头发跟随头部角度的倍率。 */
        var hairFollow: Float = 1.0f

        /** 头发自主飘动的倍率与速度。 */
        var hairWave: Float = 1.0f
        var hairSpeed: Float = 1.0f

        /** 耳朵跟随倍率与弹簧参数。 */
        var earFollow: Float = 1.0f
        var earStiffness: Float = 0.08f
        var earDamping: Float = 0.86f
        var earLimit: Float = 12f

        fun sanitize() {
            bobAmount = bobAmount.finite(1.2f).coerceIn(0f, 8f)
            bobSpeed = bobSpeed.finite(1f).coerceIn(0f, 4f)
            hairFollow = hairFollow.finite(1f).coerceIn(0f, 4f)
            hairWave = hairWave.finite(1f).coerceIn(0f, 4f)
            hairSpeed = hairSpeed.finite(1f).coerceIn(0f, 4f)
            earFollow = earFollow.finite(1f).coerceIn(0f, 4f)
            earStiffness = earStiffness.finite(0.08f).coerceIn(0.01f, 0.4f)
            earDamping = earDamping.finite(0.86f).coerceIn(0.30f, 0.98f)
            earLimit = earLimit.finite(12f).coerceIn(0f, 30f)
        }
    }

    val headSway = HeadSwaySettings()
    val spring = SpringSettings()
    val idleSway = IdleSwaySettings()

    /** 读盘 / 改设置后统一钳制，避免手改 json 里的极端值把模型甩飞。 */
    fun sanitize() {
        size = size.coerceIn(40, 800)
        swayStrength = swayStrength.finite(3f).coerceIn(0f, 10f)
        smoothing = smoothing.finite(0.1f).coerceIn(0.02f, 0.6f)
        breathSpeed = breathSpeed.finite(1f).coerceIn(0f, 4f)
        wheelResizeStep = wheelResizeStep.coerceIn(1, 64)
        soundBlinkThreshold = soundBlinkThreshold.finite(0.5f).coerceIn(0f, 1f)
        headSway.sanitize()
        spring.sanitize()
        idleSway.sanitize()
    }

    val hiddenDrawables: MutableList<String> = mutableListOf()

    /** 事件名 → 动作。 */
    val events: MutableMap<String, EventAction> = LinkedHashMap()

    class EventAction {
        var type: String = "expression"
        var target: String = ""
        var value: Float = 0f
        var duration: Float = 0f
        var fade: Float = 0.3f
    }

    fun save(path: Path) {
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            val root = JsonObject()
            root.addProperty("enabled", enabled)
            root.addProperty("model", model)
            root.addProperty("size", size)
            root.addProperty("posX", posX)
            root.addProperty("posY", posY)
            root.addProperty("swayStrength", swayStrength)
            root.addProperty("smoothing", smoothing)
            root.addProperty("followPitch", followPitch)
            root.addProperty("breathSpeed", breathSpeed)
            root.addProperty("blinkEnabled", blinkEnabled)
            root.addProperty("expressionsEnabled", expressionsEnabled)
            root.addProperty("masksEnabled", masksEnabled)
            root.addProperty("chatDragEnabled", chatDragEnabled)
            root.addProperty("wheelResizeStep", wheelResizeStep)
            root.addProperty("soundBlinkEnabled", soundBlinkEnabled)
            root.addProperty("soundBlinkThreshold", soundBlinkThreshold)
            root.addProperty("idleMotionGroup", idleMotionGroup)
            root.addProperty("autoIdleMotion", autoIdleMotion)
            root.addProperty("expressionDetect", expressionDetect)
            root.add("hiddenDrawables", GSON.toJsonTree(hiddenDrawables))

            root.add("headSway", JsonObject().apply {
                addProperty("enabled", headSway.enabled)
                addProperty("yawGain", headSway.yawGain)
                addProperty("pitchGain", headSway.pitchGain)
                addProperty("rollGain", headSway.rollGain)
                addProperty("maxYaw", headSway.maxYaw)
                addProperty("maxPitch", headSway.maxPitch)
                addProperty("maxRoll", headSway.maxRoll)
                addProperty("rateLimit", headSway.rateLimit)
                addProperty("bodyFollow", headSway.bodyFollow)
                addProperty("rollSmoothing", headSway.rollSmoothing)
                addProperty("bodySmoothing", headSway.bodySmoothing)
                addProperty("eyeFollow", headSway.eyeFollow)
            })

            root.add("spring", JsonObject().apply {
                addProperty("enabled", spring.enabled)
                addProperty("stiffness", spring.stiffness)
                addProperty("damping", spring.damping)
                addProperty("verticalStiffness", spring.verticalStiffness)
                addProperty("verticalDamping", spring.verticalDamping)
                addProperty("screenX", spring.screenX)
                addProperty("screenY", spring.screenY)
            })

            root.add("idleSway", JsonObject().apply {
                addProperty("enabled", idleSway.enabled)
                addProperty("bobAmount", idleSway.bobAmount)
                addProperty("bobSpeed", idleSway.bobSpeed)
                addProperty("hairFollow", idleSway.hairFollow)
                addProperty("hairWave", idleSway.hairWave)
                addProperty("hairSpeed", idleSway.hairSpeed)
                addProperty("earFollow", idleSway.earFollow)
                addProperty("earStiffness", idleSway.earStiffness)
                addProperty("earDamping", idleSway.earDamping)
                addProperty("earLimit", idleSway.earLimit)
            })

            val eventsObject = JsonObject()
            for ((key, action) in events) {
                eventsObject.add(key, JsonObject().apply {
                    addProperty("type", action.type)
                    addProperty("target", action.target)
                    addProperty("value", action.value)
                    addProperty("duration", action.duration)
                    addProperty("fade", action.fade)
                })
            }
            root.add("events", eventsObject)

            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8)
        }.onFailure { logger.warn("保存 Live2D 配置失败", it) }
    }

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().create()

        val defaultPath: Path
            get() = ConfigSystem.rootFolder.toPath().resolve("live2d.json")

        private fun JsonObject.obj(key: String): JsonObject? =
            runCatching { get(key)?.takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()

        private fun JsonObject.bool(key: String, fallback: Boolean): Boolean =
            runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean }.getOrNull() ?: fallback

        private fun JsonObject.num(key: String, fallback: Float): Float =
            runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asFloat }.getOrNull()?.finite(fallback) ?: fallback

        fun load(path: Path = defaultPath): Live2DConfig {
            val config = Live2DConfig()
            if (!Files.isRegularFile(path)) return config
            runCatching {
                Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                    val root = JsonParser.parseReader(reader).asJsonObject
                    root.get("enabled")?.let { config.enabled = it.asBoolean }
                    root.get("model")?.let { config.model = it.asString }
                    root.get("size")?.let { config.size = it.asInt }
                    root.get("posX")?.let { config.posX = it.asInt }
                    root.get("posY")?.let { config.posY = it.asInt }
                    root.get("swayStrength")?.let { config.swayStrength = it.asFloat }
                    root.get("smoothing")?.let { config.smoothing = it.asFloat }
                    root.get("followPitch")?.let { config.followPitch = it.asBoolean }
                    config.breathSpeed = root.num("breathSpeed", config.breathSpeed)
                    root.get("blinkEnabled")?.let { config.blinkEnabled = it.asBoolean }
                    root.get("expressionsEnabled")?.let { config.expressionsEnabled = it.asBoolean }
                    root.get("masksEnabled")?.let { config.masksEnabled = it.asBoolean }
                    root.get("chatDragEnabled")?.let { config.chatDragEnabled = it.asBoolean }
                    root.get("wheelResizeStep")?.let { config.wheelResizeStep = it.asInt }
                    root.get("soundBlinkEnabled")?.let { config.soundBlinkEnabled = it.asBoolean }
                    root.get("soundBlinkThreshold")?.let { config.soundBlinkThreshold = it.asFloat }
                    root.get("idleMotionGroup")?.let { config.idleMotionGroup = it.asString }
                    root.get("autoIdleMotion")?.let { config.autoIdleMotion = it.asBoolean }
                    root.get("expressionDetect")?.let { config.expressionDetect = it.asString }

                    // fix-21 新增组：旧配置没有这些键时保持默认，不报错。
                    root.obj("headSway")?.let { o ->
                        config.headSway.apply {
                            enabled = o.bool("enabled", enabled)
                            yawGain = o.num("yawGain", yawGain)
                            pitchGain = o.num("pitchGain", pitchGain)
                            rollGain = o.num("rollGain", rollGain)
                            maxYaw = o.num("maxYaw", maxYaw)
                            maxPitch = o.num("maxPitch", maxPitch)
                            maxRoll = o.num("maxRoll", maxRoll)
                            rateLimit = o.num("rateLimit", rateLimit)
                            bodyFollow = o.num("bodyFollow", bodyFollow)
                            rollSmoothing = o.num("rollSmoothing", rollSmoothing)
                            bodySmoothing = o.num("bodySmoothing", bodySmoothing)
                            eyeFollow = o.num("eyeFollow", eyeFollow)
                        }
                    }
                    root.obj("spring")?.let { o ->
                        config.spring.apply {
                            enabled = o.bool("enabled", enabled)
                            stiffness = o.num("stiffness", stiffness)
                            damping = o.num("damping", damping)
                            verticalStiffness = o.num("verticalStiffness", verticalStiffness)
                            verticalDamping = o.num("verticalDamping", verticalDamping)
                            screenX = o.num("screenX", screenX)
                            screenY = o.num("screenY", screenY)
                        }
                    }
                    root.obj("idleSway")?.let { o ->
                        config.idleSway.apply {
                            enabled = o.bool("enabled", enabled)
                            bobAmount = o.num("bobAmount", bobAmount)
                            bobSpeed = o.num("bobSpeed", bobSpeed)
                            hairFollow = o.num("hairFollow", hairFollow)
                            hairWave = o.num("hairWave", hairWave)
                            hairSpeed = o.num("hairSpeed", hairSpeed)
                            earFollow = o.num("earFollow", earFollow)
                            earStiffness = o.num("earStiffness", earStiffness)
                            earDamping = o.num("earDamping", earDamping)
                            earLimit = o.num("earLimit", earLimit)
                        }
                    }

                    root.getAsJsonArray("hiddenDrawables")?.forEach {
                        if (it.isJsonPrimitive) config.hiddenDrawables.add(it.asString)
                    }
                    root.getAsJsonObject("events")?.entrySet()?.forEach { (key, element) ->
                        if (!element.isJsonObject) return@forEach
                        val obj = element.asJsonObject
                        config.events[key] = EventAction().apply {
                            obj.get("type")?.let { type = it.asString }
                            obj.get("target")?.let { target = it.asString }
                            obj.get("value")?.let { value = it.asFloat }
                            obj.get("duration")?.let { duration = it.asFloat }
                            obj.get("fade")?.let { fade = it.asFloat }
                        }
                    }
                }
            }.onFailure { logger.warn("读取 Live2D 配置失败", it) }
            config.sanitize()
            return config
        }
    }
}

/** NaN / Infinity 一律回落到默认值，绝不让它进入渲染数学。 */
private fun Float.finite(fallback: Float): Float = if (isFinite()) this else fallback
