package net.ccbluex.liquidbounce.features.module
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.Listenable

class ModuleCategory(override val tag: String) : Tagged
object ModuleCategories {
    val PLAYER = ModuleCategory("Player")
    val RENDER = ModuleCategory("Render")
    val COMBAT = ModuleCategory("Combat")
    val entries: List<ModuleCategory> = listOf(RENDER, COMBAT)
}
class DescHolder { fun get(): String = "" }
open class ClientModule(
    name: String,
    val category: ModuleCategory,
    aliases: List<String> = emptyList(),
) : ValueGroup(name, aliases), Listenable {
    var enabled: Boolean = false
    val running: Boolean get() = enabled
    open val tag: String get() = ""
    val description = DescHolder()
    open fun onEnabled() {}
    open fun onDisabled() {}
}
object ModuleManager : Iterable<ClientModule> {
    val modulesConfig = Any()
    val testModules=mutableListOf<ClientModule>()
    override fun iterator(): Iterator<ClientModule> = testModules.iterator()
}
