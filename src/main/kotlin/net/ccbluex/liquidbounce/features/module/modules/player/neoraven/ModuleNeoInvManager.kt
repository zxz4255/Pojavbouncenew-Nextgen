/* GPL-3.0-only. NeoRaven b483ffc InvManager2 port; see licenses/neoraven/INVENTORY.md. */
package net.ccbluex.liquidbounce.features.module.modules.player.neoraven

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.combat.autoarmor.ModuleAutoArmor
import net.ccbluex.liquidbounce.features.module.modules.player.invcleaner.ModuleInventoryCleaner
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.collection.itemSortedSetOf
import net.ccbluex.liquidbounce.utils.inventory.SafeInventorySession
import net.ccbluex.liquidbounce.utils.inventory.InventorySafetyPolicy
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import kotlin.math.roundToInt
import kotlin.random.Random
import net.ccbluex.liquidbounce.features.module.modules.player.neoraven.NeoInventoryPolicy.Kind

/** Independent settings/planner; explicit activation ownership, deferred session and visible pause state. */
object ModuleNeoInvManager : ClientModule("NeoInvManager",ModuleCategories.PLAYER,
    aliases = listOf("NeoInventoryManager","NeoInvManager2","Neo背包整理")) {
    override val externalBind: Boolean get() = true
    init { NeoInvHotkeys }

    // Explicit user-approved 095 migration: preserve the key/modifiers, change old HOLD once only.
    private val tapToggleMigrated = boolean("TapToggleMigration095", false).notAnOption()
    internal fun migrateTapToggle() {
        if (tapToggleMigrated.get()) return
        val old = bind
        if (old.action == net.ccbluex.liquidbounce.utils.input.InputBind.BindAction.HOLD) {
            bindValue.set(old.copy(action = net.ccbluex.liquidbounce.utils.input.InputBind.BindAction.TOGGLE))
            NeoInvDiagnostics.note("BIND_MIGRATED_095", "HOLD -> TOGGLE; key/modifiers preserved")
        }
        tapToggleMigrated.set(true)
        try {
            net.ccbluex.liquidbounce.config.ConfigSystem.store(net.ccbluex.liquidbounce.features.module.ModuleManager.modulesConfig)
        } catch (error: Exception) {
            logger.warn("[NeoInvManager] Tap-toggle migration active; could not persist configuration", error)
        }
    }

    val inventoryOnly by boolean("InventoryOnly",false)
    val protectCustomItems by boolean("ProtectCustomItems",true)
    val autoArmor by boolean("AutoArmor",true)
    val throwTrash by boolean("ThrowTrash",true)
    val autoClose by boolean("AutoClose",false)
    val onlySortOnce by boolean("OnlySortOnce",false)
    val onlyConfigured by boolean("OnlyItemsConfiguredAreTrash",false)
    val projectilesTrash by boolean("ProjectilesIsTrash",false)
    val normalFoodTrash by boolean("NormalFoodIsTrash",true)
    val bowTrash by boolean("BowIsTrash",true)
    val potionTrash by boolean("PotionIsTrash",true)
    val toolsTrash by boolean("ToolsAreTrash",true)
    val swordSlot by int("SwordSlot",1,0..9)
    val projectilesSlot by int("ProjectilesSlot",3,0..9)
    val blockSlot by int("BlockSlot",2,0..9)
    val foodSlot by int("FoodSlot",9,0..9)
    val bowSlot by int("BowSlot",4,0..9)
    val pickaxeSlot by int("PickaxeSlot",5,0..9)
    val axeSlot by int("AxeSlot",6,0..9)
    val shovelSlot by int("ShovelSlot",7,0..9)
    val potionSlot by int("PotionSlot",8,0..9)
    val fireballSlot by int("FireballSlot",0,0..9)
    val enderPearlSlot by int("EnderPearlSlot",0,0..9)
    val shearsSlot by int("ShearsSlot",0,0..9)
    val maxBlockSlots by int("MaxBlockSlots",10,1..36)
    val maxArrowSlots by int("MaxArrowSlots",10,1..36)
    val maxTrashThrows by int("MaxTrashThrows",36,1..36)
    val startDelay by int("StartDelay",50,0..1000,"ms").onChange { (it/10f).roundToInt()*10 }
    val minDelay by int("MinDelay",50,0..1000,"ms").onChange { (it/10f).roundToInt()*10 }
    val maxDelay by int("MaxDelay",50,0..1000,"ms").onChange { (it/10f).roundToInt()*10 }
    // Safety addition, default EMPTY: upstream ThrowTrash alone is not permission to delete items.
    private val discardItems by items("DiscardItems", itemSortedSetOf())
    // Enabling and reading a GUI tag never constructs/touches the native click session.
    private var session: SafeInventorySession? = null
    @Volatile private var status = "Ready: background sorting"
    override val tag: String get() = "096 · $status"
    private var screen: InventoryScreen? = null
    private var backgroundAllowed: Boolean? = null
    private var sessionWorld: Any? = null
    private var owner: Any? = null
    private var readyAt = 0L
    private var sorted = false
    private var throws = 0
    private var faulted = false
    private var acquirePending = false
    init {
        NeoInvDiagnostics.install(enabledValue)
        literalDescription {
            "096 后台／开背包整理；InventoryOnly 可限制为开背包。ProtectCustomItems 控制命名物品重排保护；丢弃仍需 DiscardItems 白名单。日志：logs/neo-invmanager-092.log。" +
                "当前 enabled=$enabled；$status。最后记录：${NeoInvDiagnostics.lastSummary}"
        }
    }
    private fun reset() {
        session = null; screen = null; sessionWorld = null; owner = null; backgroundAllowed = null
        readyAt = 0; sorted = false; throws = 0; faulted = false
        status = "Ready: background sorting"
    }
    override fun onEnabled() {
        NeoInvDiagnostics.note("LIFECYCLE_ENABLE", "storedBeforeCommit=$enabled", includeStack = true)
        reset(); acquirePending = true
    }
    override fun onDisabled() {
        NeoInvDiagnostics.note("LIFECYCLE_DISABLE", "storedBeforeCommit=$enabled", includeStack = true)
        reset(); acquirePending = false
    }
    private val worldChangeHandler = handler<WorldChangeEvent> {
        NeoInvDiagnostics.note("WORLD_CHANGE", "enabled=$enabled")
        reset(); acquirePending = enabled
    }

    /** One handoff per activation; existing modules/settings remain installed, never overwritten. */
    private fun acquireInventoryControl() {
        acquirePending = false
        NeoInvDiagnostics.note("HANDOFF", "once-per-activation")
        for (other in listOf(ModuleInventoryCleaner, ModuleAutoArmor)) {
            if (!other.enabled) continue
            try { other.enabled = false }
            catch (error: Exception) { logger.error("[NeoInvManager] Could not release ${other.name}", error) }
        }
    }
    private fun pauseOnError(error: Throwable) {
        if (!faulted) NeoInvDiagnostics.note("RUNTIME_PAUSE", error.javaClass.name)
        if (!faulted) logger.error("[NeoInvManager] Inventory pass stopped; module remains enabled, reopen inventory to retry", error)
        faulted = true
        status = "Paused: error, reopen inventory"
    }

    private fun describe(stack: ItemStack): NeoInventoryPolicy.Item? {
        val item = NeoChestItems.describe(stack) ?: return null
        val kind = when {
            stack.`is`(Items.FIRE_CHARGE) -> Kind.FIREBALL
            stack.`is`(Items.SHEARS) -> Kind.SHEARS
            else -> when (item.kind) {
                NeoChestPolicy.Kind.ARMOR -> Kind.ARMOR; NeoChestPolicy.Kind.SWORD -> Kind.SWORD
                NeoChestPolicy.Kind.BLOCK -> Kind.BLOCK; NeoChestPolicy.Kind.BOW -> Kind.BOW
                NeoChestPolicy.Kind.ARROW -> Kind.ARROW; NeoChestPolicy.Kind.FOOD -> Kind.FOOD
                NeoChestPolicy.Kind.POTION -> Kind.POTION; NeoChestPolicy.Kind.PEARL -> Kind.PEARL
                NeoChestPolicy.Kind.EGG,NeoChestPolicy.Kind.SNOWBALL -> Kind.PROJECTILE
                NeoChestPolicy.Kind.ROD -> Kind.ROD; NeoChestPolicy.Kind.PICKAXE -> Kind.PICKAXE
                NeoChestPolicy.Kind.AXE -> Kind.AXE; NeoChestPolicy.Kind.SHOVEL -> Kind.SHOVEL
                else -> Kind.OTHER
            }
        }
        val armor = when (item.armorType) { "HEAD" -> 5; "CHEST" -> 6; "LEGS" -> 7; "FEET" -> 8; else -> -1 }
        val score = when (kind) {
            // InvManager2.efficiency has no ChestStealer's 30%-durability cutoff.
            Kind.PICKAXE, Kind.AXE, Kind.SHOVEL -> stack.getDestroySpeed(Blocks.STONE.defaultBlockState()).toDouble()
            Kind.SHEARS -> (stack.maxDamage - stack.damageValue).toDouble()
            else -> item.value
        }
        return NeoInventoryPolicy.Item(kind,score,stack.count,armor,item.goldenApple,
            stack.`is`(Items.SPLASH_POTION),item.placeable)
    }
    private fun targets() = mapOf(Kind.SWORD to swordSlot, Kind.PROJECTILE to projectilesSlot, Kind.BLOCK to blockSlot,
        Kind.BOW to bowSlot, Kind.PICKAXE to pickaxeSlot, Kind.AXE to axeSlot, Kind.SHOVEL to shovelSlot,
        Kind.FOOD to foodSlot, Kind.POTION to potionSlot, Kind.FIREBALL to fireballSlot, Kind.PEARL to enderPearlSlot, Kind.SHEARS to shearsSlot)
    private val tickHandler = handler<GameTickEvent> {
        val before = status
        try { runInventoryTick() }
        catch (error: Exception) { pauseOnError(error) }
        catch (error: LinkageError) { pauseOnError(error) }
        finally {
            if (status != before) NeoInvDiagnostics.note("STATUS", "enabled=$enabled $status")
        }
    }

    private fun runInventoryTick() {
        if (mc.player == null || mc.level == null) { reset(); return }
        if (acquirePending) acquireInventoryControl()
        val current = mc.gui.screen() as? InventoryScreen
        if ((mc.gui.screen() != null && current == null) || (inventoryOnly && current == null)) {
            reset(); status = if (inventoryOnly && mc.gui.screen() == null) "Waiting: open player inventory" else "Paused: another screen"
            return
        }
        if (mc.player!!.containerMenu !== mc.player!!.inventoryMenu) {
            reset(); status = "Paused: another container"; return
        }
        val now = System.currentTimeMillis()
        if (current !== screen || sessionWorld !== mc.level || owner !== mc.player || backgroundAllowed != !inventoryOnly) {
            reset(); screen = current; sessionWorld = mc.level; owner = mc.player; backgroundAllowed = !inventoryOnly; readyAt = now + startDelay
        }
        if (ModuleInventoryCleaner.running || ModuleAutoArmor.running) {
            status = "Paused: another inventory module"; return
        }
        if (ModuleScaffold.running ||
            ModuleKillAura.running && ModuleKillAura.targetTracker.target != null) {
            status = "Paused: combat or scaffold"; return
        }
        if (faulted) return
        if (now < readyAt) { status = "Start delay"; return }
        val clicks = session ?: SafeInventorySession(allowClosedInventory = !inventoryOnly).also { session = it }
        if (!clicks.ready()) {
            status = clicks.fault?.let { "Paused: $it" } ?: "Waiting: ${clicks.waitReason}"
            return
        }
        status = "Sorting"
        val menu = mc.player!!.inventoryMenu
        val items = menu.slots.map { describe(it.item) }
        val pinned = if (protectCustomItems) (5..44).filter { SafeInventorySession.pinned(menu.getSlot(it).item) }.toSet() else emptySet()
        val delay = NeoChestPolicy.jitter(minDelay,maxDelay,Random.nextDouble())
        var blocked: String? = null
        fun submit(plan: List<InventorySafetyPolicy.Click>, action: String): Boolean {
            if (clicks.submit(plan, delay)) {
                status = action
                NeoInvDiagnostics.note("ACTION", "$action clicks=${plan.size} screen=${current != null}")
                return true
            }
            blocked = clicks.fault ?: clicks.waitReason
            return false
        }
        if (autoArmor) {
            val upgrade = NeoInventoryPolicy.armorUpgrade(items,pinned)
            if (upgrade != null) {
                val (source,armor) = upgrade
                val scratch = (0..8).firstOrNull { 36+it !in pinned }
                if ((source >= 36 || scratch != null) && menu.getSlot(armor).mayPickup(mc.player!!)) {
                    val plan = clicks.equip(source,armor,scratch ?: 0)
                    if (plan != null && submit(plan, "Equipping armor")) return
                    blocked = blocked ?: "Armor exchange unavailable"
                } else blocked = "Armor slot or scratch hotbar protected"
                // An unplannable armor exchange must not starve all later sorting forever.
                if (clicks.fault != null) { status = "Paused: ${clicks.fault}"; return }
            }
        }
        if (!(onlySortOnce && sorted)) {
            val sort = NeoInventoryPolicy.nextSort(items,targets(),pinned)
            if (sort != null) {
                if (submit(listOf(sort), "Sorting hotbar")) return
                status = "Blocked: $blocked"; return
            }
            sorted = true
        }
        if (throwTrash && throws < maxTrashThrows) {
            val settings = NeoInventoryPolicy.Settings(autoArmor,throwTrash,onlyConfigured,projectilesTrash,
                normalFoodTrash,bowTrash,potionTrash,toolsTrash,maxBlockSlots,maxArrowSlots)
            val discard = NeoInventoryPolicy.trashCandidates(items,settings).firstOrNull {
                SafeInventorySession.mayDiscard(menu.getSlot(it).item,discardItems)
            }
            if (discard != null && submit(listOf(InventorySafetyPolicy.Click(discard,1,InventorySafetyPolicy.Mode.THROW)), "Discarding allowlisted trash")) {
                throws++; return
            }
        }
        if (clicks.fault != null) { status = "Paused: ${clicks.fault}"; return }
        if (!clicks.completePass()) { status = "Waiting: ${clicks.waitReason}"; return }
        status = when {
            blocked != null -> "Blocked: $blocked"
            pinned.isNotEmpty() -> "Done: ${pinned.size} custom stacks protected"
            throwTrash && discardItems.isEmpty() -> "Done: trash needs DiscardItems allowlist"
            else -> "Done"
        }
        if (autoClose && current != null && menu.carried.isEmpty) mc.player!!.closeContainer()
    }
}
