/*
 * Live2DMotion / CubismExpression —— motion3.json 与 exp3.json 的解析与求值。
 *
 * motion3.json 的 Segments 是扁平数组：
 *   [ t0, v0, <segment>... ]
 * 每段以类型开头：
 *   0 线性   : endT, endV
 *   1 贝塞尔 : c1T, c1V, c2T, c2V, endT, endV
 *   2 阶梯   : endT, endV
 *   3 反阶梯 : endT, endV
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/Live2DMotion.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import com.google.gson.JsonObject
import kotlin.math.max
import kotlin.math.min

class Live2DMotion(
    val name: String,
    val duration: Float,
    val loop: Boolean,
    val fadeIn: Float,
    val fadeOut: Float,
    val curves: List<Curve>,
) {

    class Segment(
        val type: Int,
        val startT: Float,
        val startV: Float,
        val endT: Float,
        val endV: Float,
        val c1T: Float,
        val c1V: Float,
        val c2T: Float,
        val c2V: Float,
    ) {
        fun valueAt(t: Float): Float {
            val span = endT - startT
            if (span <= 1e-6f) return endV

            return when (type) {
                BEZIER -> {
                    // 按时间解三次贝塞尔：先用二分逼近 t 对应的参数 s，再取 y。
                    val target = ((t - startT) / span).coerceIn(0f, 1f)
                    val s = solveBezierParameter(
                        (c1T - startT) / span,
                        (c2T - startT) / span,
                        target,
                    )
                    bezier(startV, c1V, c2V, endV, s)
                }
                STEPPED -> startV
                INVERSE_STEPPED -> if (t >= endT) endV else startV
                else -> startV + (endV - startV) * ((t - startT) / span).coerceIn(0f, 1f)
            }
        }

        private fun solveBezierParameter(p1: Float, p2: Float, x: Float): Float {
            var low = 0f
            var high = 1f
            var mid = x
            repeat(24) {
                mid = (low + high) * 0.5f
                val value = bezier(0f, p1, p2, 1f, mid)
                if (value < x) low = mid else high = mid
            }
            return mid
        }

        private fun bezier(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
            val u = 1f - t
            return u * u * u * p0 + 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t * p3
        }
    }

    class Curve(
        val target: String,
        val id: String,
        val segments: List<Segment>,
    ) {
        fun valueAt(t: Float): Float {
            if (segments.isEmpty()) return Float.NaN
            if (t <= segments.first().startT) return segments.first().startV
            for (segment in segments) {
                if (t <= segment.endT) return segment.valueAt(t)
            }
            return segments.last().endV
        }
    }

    fun valueForParameter(id: String, t: Float): Float {
        for (curve in curves) {
            if (curve.target == "Parameter" && curve.id == id) return curve.valueAt(t)
        }
        return Float.NaN
    }

    /** Model 目标曲线（Opacity / EyeBlink 等），id 形如 "Opacity"。 */
    fun valueForModel(id: String, t: Float): Float {
        for (curve in curves) {
            if (curve.target == "Model" && curve.id == id) return curve.valueAt(t)
        }
        return Float.NaN
    }

    companion object {
        const val LINEAR = 0
        const val BEZIER = 1
        const val STEPPED = 2
        const val INVERSE_STEPPED = 3

        fun parse(name: String, json: JsonObject): Live2DMotion {
            val meta = json.getAsJsonObject("Meta") ?: JsonObject()
            val duration = meta.get("Duration")?.asFloat ?: 1f
            val loop = meta.get("Loop")?.asBoolean ?: false
            val fadeIn = meta.get("FadeInTime")?.asFloat ?: 0.5f
            val fadeOut = meta.get("FadeOutTime")?.asFloat ?: 0.5f

            val curves = ArrayList<Curve>()
            val curvesArray = json.getAsJsonArray("Curves")
            if (curvesArray != null) {
                for (element in curvesArray) {
                    if (!element.isJsonObject) continue
                    val obj = element.asJsonObject
                    val target = obj.get("Target")?.asString ?: "Parameter"
                    val id = obj.get("Id")?.asString ?: ""
                    val segmentsArray = obj.getAsJsonArray("Segments") ?: continue

                    val segments = ArrayList<Segment>()
                    if (segmentsArray.size() < 2) {
                        curves.add(Curve(target, id, segments))
                        continue
                    }

                    var prevT = segmentsArray.get(0).asFloat
                    var prevV = segmentsArray.get(1).asFloat
                    var i = 2

                    while (i < segmentsArray.size()) {
                        val type = runCatching { segmentsArray.get(i).asInt }.getOrNull() ?: break
                        i++
                        when (type) {
                            LINEAR, STEPPED, INVERSE_STEPPED -> {
                                if (i + 1 >= segmentsArray.size()) break
                                val endT = segmentsArray.get(i).asFloat
                                val endV = segmentsArray.get(i + 1).asFloat
                                i += 2
                                segments.add(Segment(type, prevT, prevV, endT, endV, prevT, prevV, endT, endV))
                                prevT = endT
                                prevV = endV
                            }
                            BEZIER -> {
                                if (i + 5 >= segmentsArray.size()) break
                                val c1T = segmentsArray.get(i).asFloat
                                val c1V = segmentsArray.get(i + 1).asFloat
                                val c2T = segmentsArray.get(i + 2).asFloat
                                val c2V = segmentsArray.get(i + 3).asFloat
                                val endT = segmentsArray.get(i + 4).asFloat
                                val endV = segmentsArray.get(i + 5).asFloat
                                i += 6
                                segments.add(Segment(BEZIER, prevT, prevV, endT, endV, c1T, c1V, c2T, c2V))
                                prevT = endT
                                prevV = endV
                            }
                            else -> i = segmentsArray.size()
                        }
                    }

                    curves.add(Curve(target, id, segments))
                }
            }

            return Live2DMotion(name, max(0.01f, duration), loop, fadeIn, fadeOut, curves)
        }
    }
}

/**
 * exp3.json：Parameters[].{Id,Value,Blend}，Blend 支持 Add / Multiply / Overwrite。
 */
class CubismExpression(
    val name: String,
    val fadeIn: Float,
    val fadeOut: Float,
    val parameters: List<Parameter>,
) {

    enum class Blend { ADD, MULTIPLY, OVERWRITE }

    class Parameter(val id: String, val value: Float, val blend: Blend)

    companion object {
        fun parse(name: String, json: JsonObject): CubismExpression {
            val fadeIn = json.get("FadeInTime")?.asFloat ?: 1f
            val fadeOut = json.get("FadeOutTime")?.asFloat ?: 1f

            val parameters = ArrayList<Parameter>()
            val array = json.getAsJsonArray("Parameters")
            if (array != null) {
                for (element in array) {
                    if (!element.isJsonObject) continue
                    val obj = element.asJsonObject
                    val id = obj.get("Id")?.asString ?: continue
                    val valueElement = obj.get("Value") ?: continue
                    if (!valueElement.isJsonPrimitive || !valueElement.asJsonPrimitive.isNumber) continue
                    val blend = when (obj.get("Blend")?.asString?.lowercase()) {
                        "multiply" -> Blend.MULTIPLY
                        "overwrite" -> Blend.OVERWRITE
                        else -> Blend.ADD
                    }
                    parameters.add(Parameter(id, valueElement.asFloat, blend))
                }
            }

            return CubismExpression(name, max(0f, fadeIn), max(0f, fadeOut), parameters)
        }
    }
}

/** Pose groups and per-entry links from the model's pose3.json. */
class CubismPose(val groups: List<Group>) {
    class Entry(val id: String, val linked: List<String>)
    class Group(val entries: List<Entry>)
    private val selected = HashMap<Int, Int>()

    fun isEmpty(): Boolean = groups.isEmpty()
    fun selection(group: Int): Int? = selected[group]
    fun select(group: Int, entry: Int) {
        if (group in groups.indices && entry in groups[group].entries.indices) selected[group] = entry
    }
    fun resetSelection() { selected.clear() }

    fun apply(model: CubismModel) {
        model.resetPartOpacityInput()
        groups.forEachIndexed { groupIndex, group ->
            val valid = group.entries.withIndex().filter { model.indexOfPart(it.value.id) >= 0 }
            if (valid.isEmpty()) return@forEachIndexed
            val active = selected[groupIndex]?.takeIf { n -> valid.any { it.index == n } }
                ?: valid.first().index
            val linkValues = LinkedHashMap<String, Float>()
            group.entries.forEachIndexed { entryIndex, entry ->
                val opacity = if (entryIndex == active) 1f else 0f
                val i = model.indexOfPart(entry.id)
                if (i >= 0) model.setPartOpacityInput(i, opacity)
                entry.linked.forEach { id ->
                    linkValues[id] = max(linkValues[id] ?: 0f, opacity)
                }
            }
            linkValues.forEach { (id, opacity) ->
                val index = model.indexOfPart(id)
                if (index >= 0) model.setPartOpacityInput(index, opacity)
            }
        }
    }

    companion object {
        fun parse(json: JsonObject): CubismPose {
            val result = ArrayList<Group>()
            val groupsArray = json.getAsJsonArray("Groups") ?: return CubismPose(result)
            for (group in groupsArray) {
                // Cubism pose3 normally uses Groups: [[{Id, Link}, ...], ...].
                // Also accept the object/Parts variant used by earlier DroidBridge data.
                val entriesArray = when {
                    group.isJsonArray -> group.asJsonArray
                    group.isJsonObject -> group.asJsonObject.getAsJsonArray("Parts")
                    else -> null
                } ?: continue
                val entries = ArrayList<Entry>()
                for (element in entriesArray) {
                    if (!element.isJsonObject) continue
                    val item = element.asJsonObject
                    val id = item.get("Id")?.asString ?: continue
                    val links = ArrayList<String>()
                    item.getAsJsonArray("Link")?.forEach { link ->
                        if (link.isJsonPrimitive && link.asJsonPrimitive.isString) links.add(link.asString)
                    }
                    entries.add(Entry(id, links))
                }
                if (entries.isNotEmpty()) result.add(Group(entries))
            }
            return CubismPose(result)
        }
    }
}

/** 物理（physics3.json）的简化实现：输入/输出均为参数 id。 */
class CubismPhysics(private val settings: List<Setting>) {

    class Setting(
        val inputParameter: String,
        val outputParameter: String,
        val scale: Float,
        val weight: Float,
    )

    private val smoothed = HashMap<String, Float>()

    fun apply(model: CubismModel, dt: Float) {
        if (settings.isEmpty()) return
        val step = min(1f, max(0.05f, dt * 12f))
        for (setting in settings) {
            val inputIndex = model.indexOfParameter(setting.inputParameter)
            val outputIndex = model.indexOfParameter(setting.outputParameter)
            if (inputIndex < 0 || outputIndex < 0) continue

            val input = model.parameterValueAt(inputIndex)
            val target = input * setting.scale
            val key = setting.outputParameter
            val previous = smoothed[key] ?: model.parameterValueAt(outputIndex)
            val next = previous + (target - previous) * step * setting.weight
            smoothed[key] = next
            model.setParameterValue(outputIndex, next)
        }
    }

    companion object {
        fun parse(json: JsonObject): CubismPhysics {
            val settings = ArrayList<Setting>()
            val physicsSettings = json.getAsJsonArray("PhysicsSettings")
            val meta = json.getAsJsonObject("Meta")
            val dict = HashMap<String, String>()
            meta?.getAsJsonArray("PhysicsDictionary")?.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val obj = element.asJsonObject
                val id = obj.get("Id")?.asString ?: return@forEach
                val name = obj.get("Name")?.asString ?: id
                dict[name] = id
            }

            physicsSettings?.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val obj = element.asJsonObject
                val outputArray = obj.getAsJsonArray("Output") ?: return@forEach
                val inputArray = obj.getAsJsonArray("Input") ?: return@forEach
                val output = outputArray.firstOrNull()?.asJsonObject ?: return@forEach
                val input = inputArray.firstOrNull()?.asJsonObject ?: return@forEach
                val outputId = output.get("Id")?.asString ?: return@forEach
                val inputId = input.get("Id")?.asString ?: return@forEach
                val scale = output.get("Scale")?.asFloat ?: 1f
                val weight = output.get("Weight")?.asFloat ?: 100f
                settings.add(Setting(inputId, outputId, scale, (weight / 100f).coerceIn(0f, 1f)))
            }

            return CubismPhysics(settings)
        }
    }
}