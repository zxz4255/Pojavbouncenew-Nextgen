/* GPL-3.0-only. Derived from NeoRaven b483ffc InvManager2. Safety fixes are documented in 076. */
package net.ccbluex.liquidbounce.features.module.modules.player.neoraven

import net.ccbluex.liquidbounce.utils.inventory.InventorySafetyPolicy

/** Pure item classification is supplied by the modern adapter; no click mutates this snapshot. */
object NeoInventoryPolicy {
    enum class Kind { ARMOR, SWORD, PROJECTILE, ROD, BLOCK, BOW, PICKAXE, AXE, SHOVEL, FOOD, POTION, FIREBALL, PEARL, SHEARS, ARROW, OTHER }
    data class Item(val kind: Kind, val score: Double = 0.0, val count: Int = 1,
        val armor: Int = -1, val golden: Boolean = false, val splash: Boolean = false, val placeable: Boolean = false)
    // Priority is the upstream sortClick order. First owner wins target collisions.
    val order = listOf(Kind.SWORD,Kind.PROJECTILE,Kind.BLOCK,Kind.BOW,Kind.PICKAXE,Kind.AXE,Kind.SHOVEL,
        Kind.FOOD,Kind.POTION,Kind.FIREBALL,Kind.PEARL,Kind.SHEARS)
    data class Settings(val autoArmor: Boolean = true, val throwTrash: Boolean = true,
        val onlyConfigured: Boolean = false, val projectileTrash: Boolean = false, val normalFoodTrash: Boolean = true,
        val bowTrash: Boolean = true, val potionTrash: Boolean = true, val toolsTrash: Boolean = true,
        val blockLimit: Int = 10, val arrowLimit: Int = 10)
    fun matches(item: Item, kind: Kind): Boolean = when (kind) {
        Kind.PROJECTILE -> item.kind == Kind.PROJECTILE || item.kind == Kind.ROD
        Kind.BLOCK -> item.kind == Kind.BLOCK && item.placeable
        Kind.POTION -> item.kind == Kind.POTION && item.splash
        else -> item.kind == kind
    }
    fun score(item: Item, kind: Kind): Double = when (kind) {
        Kind.FOOD -> (if (item.golden) 1000.0 else 0.0) + item.count
        Kind.PROJECTILE -> (if (item.kind == Kind.ROD) 0.0 else 100.0) + item.count
        Kind.SWORD,Kind.BOW,Kind.PICKAXE,Kind.AXE,Kind.SHOVEL -> item.score
        else -> item.count.toDouble()
    }
    fun nextSort(items: List<Item?>, slots: Map<Kind, Int>, pinned: Set<Int> = emptySet()): InventorySafetyPolicy.Click? {
        require(items.size == 46)
        val targets = order.map { kind ->
            InventorySafetyPolicy.Target((slots[kind] ?: 0) - 1, (9..44).mapNotNull { id ->
                val item = items[id] ?: return@mapNotNull null
                if (id in pinned || !matches(item,kind)) null else InventorySafetyPolicy.Candidate(id,score(item,kind))
            })
        }
        return InventorySafetyPolicy.nextSwap(targets,pinned)
    }
    fun armorUpgrade(items: List<Item?>, pinned: Set<Int> = emptySet()): Pair<Int,Int>? {
        for (armor in 5..8) {
            if (armor in pinned) continue
            val worn = items[armor]?.score ?: -1.0
            val best = (9..44).filter { it !in pinned && items[it]?.kind == Kind.ARMOR && items[it]?.armor == armor }
                .maxWithOrNull(compareBy<Int> { items[it]!!.score }.thenByDescending { it }) ?: continue
            if (items[best]!!.score > worn) return best to armor
        }
        return null
    }
    /** Upstream candidate rules, with symmetric-shears deletion fixed to strict-better only. */
    fun trashCandidates(items: List<Item?>, settings: Settings): List<Int> {
        require(items.size == 46)
        var blocks = 0; var arrows = 0
        return (9..44).filter { id ->
            val item = items[id] ?: return@filter false
            when (item.kind) {
                Kind.ARMOR -> (5..44).any { other -> other != id && items[other]?.kind == Kind.ARMOR &&
                    items[other]?.armor == item.armor && items[other]!!.score > item.score }
                Kind.SWORD,Kind.SHEARS -> (9..44).any { other -> other != id && items[other]?.kind == item.kind && items[other]!!.score > item.score }
                Kind.BOW -> settings.bowTrash
                Kind.ARROW -> arrows++ >= settings.arrowLimit
                Kind.PICKAXE,Kind.AXE,Kind.SHOVEL -> settings.toolsTrash || (9..44).any { other ->
                    other != id && items[other]?.kind == item.kind && items[other]!!.score > item.score }
                Kind.FOOD -> settings.normalFoodTrash && !item.golden
                Kind.POTION -> settings.potionTrash
                Kind.PROJECTILE,Kind.ROD,Kind.PEARL,Kind.FIREBALL -> settings.projectileTrash
                Kind.BLOCK -> item.placeable && blocks++ >= settings.blockLimit
                Kind.OTHER -> !settings.onlyConfigured
            }
        }
    }
}
