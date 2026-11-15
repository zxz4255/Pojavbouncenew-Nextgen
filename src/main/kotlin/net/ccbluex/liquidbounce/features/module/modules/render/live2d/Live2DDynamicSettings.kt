package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged

/** Populates ordinary module settings from the currently loaded model. No model names are baked in. */
class Live2DDynamicSettings(
    private val hud: Live2DHudRenderer,
    private val model: Live2DModel,
) {
    data class Item(override val tag: String, val index: Int = -1, val number: Float? = null) : Tagged

    private fun isCurrent(): Boolean = hud.getModel() === model
    private fun uniqueLabel(index: Int, id: String, display: String?): String {
        val clean = (display ?: id).replace(Regex("[\\r\\n\\t]"), " ").trim().ifBlank { id }
        // Prefix and ID avoid collisions even when a model gives two parts the same label.
        return "${index + 1} $id $clean"
    }

    fun addTo(root: ValueGroup) {
        val core = model.core
        val auto = Item("Auto")
        val expressionChoices = linkedSetOf(auto)
        model.expressions.keys.forEachIndexed { index, name ->
            expressionChoices.add(Item("${index + 1}: $name", index))
        }
        val expressionNames = model.expressions.keys.toList()
        root.enumChoice("ModelExpression", auto, expressionChoices).onChanged { choice ->
            if (isCurrent()) hud.setActiveExpression(expressionNames.getOrNull(choice.index))
        }

        val noMotion = Item("None")
        val motionNames = model.motions.keys.toList()
        val motionChoices = linkedSetOf(noMotion)
        motionNames.forEachIndexed { index, name -> motionChoices.add(Item("${index + 1}: $name", index)) }
        root.enumChoice("ModelMotion", noMotion, motionChoices).onChanged { choice ->
            if (isCurrent()) {
                val name = motionNames.getOrNull(choice.index)
                if (name == null) model.stopMotion() else hud.playMotion(name)
            }
        }

        val pose = model.pose
        val poses = root.tree(ValueGroup("ModelPoseGroups"))
        pose?.groups?.forEachIndexed { groupIndex, group ->
            val choices = linkedSetOf<Item>()
            group.entries.forEachIndexed { entryIndex, entry ->
                if (core.indexOfPart(entry.id) >= 0) {
                    val label = model.partNames[entry.id] ?: entry.id
                    choices.add(Item("${entryIndex + 1}: $label [${entry.id}]", entryIndex))
                }
            }
            if (choices.isNotEmpty()) {
                poses.enumChoice("Group${groupIndex + 1}", choices.first(), choices).onChanged { choice ->
                    if (isCurrent()) pose.select(groupIndex, choice.index)
                }
            }
        }

        val parts = root.tree(ValueGroup("ModelParts"))
        core.partIds.forEachIndexed { index, id ->
            if (id.isNotBlank()) {
                parts.boolean(uniqueLabel(index, id, model.partNames[id]), true).onChanged { visible ->
                    if (isCurrent()) core.setPartVisibleByUser(index, visible)
                }
            }
        }

        val parameters = root.tree(ValueGroup("ModelStylesAndParameters"))
        core.parameterIds.forEachIndexed { index, id ->
            if (id.isBlank()) return@forEachIndexed
            val min = core.parameterMin(index)
            val max = core.parameterMax(index)
            if (!min.isFinite() || !max.isFinite() || max <= min) return@forEachIndexed
            val choices = linkedSetOf(Item("Auto"))
            core.parameterKeyValues(index).forEachIndexed { keyIndex, value ->
                choices.add(Item("${keyIndex + 1}: $value", keyIndex, value))
            }
            parameters.enumChoice(uniqueLabel(index, id, model.parameterNames[id]), choices.first(), choices)
                .onChanged { choice -> if (isCurrent()) model.setManualParameter(id, choice.number) }
        }
        root.walkInit()
        root.walkKeyPath()
    }
}
