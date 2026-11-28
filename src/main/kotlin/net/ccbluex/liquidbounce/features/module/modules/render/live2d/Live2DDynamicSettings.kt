package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged

/**
 * Standard Live2D dynamic model controls (Expression, Motion, and Pose groups)
 * populated directly from the currently loaded `.model3.json`.
 */
internal class Live2DDynamicSettings(
    private val hud: Live2DHudRenderer,
    private val model: Live2DModel,
) {

    fun addTo(root: ValueGroup) {
        // 1. Standard Live2D Expression selector
        if (model.expressions.isNotEmpty()) {
            val choices = LinkedHashSet<NameChoice>()
            choices.add(NameChoice("Auto", null))
            model.expressions.keys.forEachIndexed { idx, name ->
                val label = if (choices.any { it.tag.equals(name, ignoreCase = true) }) {
                    "$name#${idx + 1}"
                } else {
                    name
                }
                choices.add(NameChoice(label, name))
            }
            val current = hud.getActiveExpression()
            val initial = choices.firstOrNull { it.key == current } ?: choices.first()
            root.enumChoice("Expression", initial, choices)
                .doNotIncludeAlways()
                .onChanged { choice -> hud.setActiveExpression(choice.key) }
        }

        // 2. Standard Live2D Motion trigger selector
        if (model.motions.isNotEmpty()) {
            val choices = LinkedHashSet<NameChoice>()
            choices.add(NameChoice("Idle", null))
            model.motions.keys.forEachIndexed { idx, name ->
                val label = if (choices.any { it.tag.equals(name, ignoreCase = true) }) {
                    "$name#${idx + 1}"
                } else {
                    name
                }
                choices.add(NameChoice(label, name))
            }
            val initial = choices.first()
            root.enumChoice("Motion", initial, choices)
                .doNotIncludeAlways()
                .onChanged { choice ->
                    val key = choice.key
                    if (key == null) {
                        model.stopMotion()
                    } else {
                        hud.playMotion(key)
                    }
                }
        }

        // 3. Standard Live2D Pose group switcher (if model defines .pose3.json groups)
        val pose = model.pose
        if (pose != null && !pose.isEmpty()) {
            val group = ValueGroup("Poses").apply { doNotIncludeAlways() }
            var addedAny = false
            pose.groups.forEachIndexed { groupIndex, poseGroup ->
                if (poseGroup.entries.size <= 1) return@forEachIndexed
                val choices = LinkedHashSet<PoseChoice>()
                poseGroup.entries.forEachIndexed { entryIdx, entry ->
                    val rawLabel = model.partNames[entry.id]?.takeIf { it.isNotBlank() } ?: entry.id
                    val uniqueLabel = if (choices.any { it.tag.equals(rawLabel, ignoreCase = true) }) {
                        "$rawLabel#${entryIdx + 1}"
                    } else {
                        rawLabel
                    }
                    choices.add(PoseChoice(uniqueLabel, entryIdx))
                }
                if (choices.size > 1) {
                    val activeEntry = pose.selection(groupIndex) ?: 0
                    val initial = choices.firstOrNull { it.entryIdx == activeEntry } ?: choices.first()
                    group.enumChoice("Pose${groupIndex + 1}", initial, choices)
                        .doNotIncludeAlways()
                        .onChanged { selected ->
                            pose.select(groupIndex, selected.entryIdx)
                            model.update()
                        }
                    addedAny = true
                }
            }
            if (addedAny) {
                root.tree(group)
            }
        }
        root.walkKeyPath()
    }

    private data class NameChoice(override val tag: String, val key: String?) : Tagged
    private data class PoseChoice(override val tag: String, val entryIdx: Int) : Tagged
}
