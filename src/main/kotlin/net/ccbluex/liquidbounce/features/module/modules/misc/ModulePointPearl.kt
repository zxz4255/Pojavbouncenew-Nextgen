/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce).
 * Copyright (c) 2015-2026 CCBlueX. GPL-3.0-or-later, without warranty.
 */
package net.ccbluex.liquidbounce.features.module.modules.misc

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.misc.pointpearl.PearlMapScreen
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.projectiles.SituationalProjectileAngleCalculator
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.entity.PositionExtrapolation
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.useHotbarSlotOrOffhand
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfo
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfoRenderer
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/** A conservative, local planner; it never claims an exact server-side landing. */
object ModulePointPearl : ClientModule("PointPearl", ModuleCategories.MISC, disableOnQuit = true) {
    private val rotations = tree(RotationsValueGroup(this))
    private val tolerance by float("Tolerance", 0.55f, 0.25f..1.0f, "blocks")
    private val allowMove by boolean("AutoMove", true)
    private val maxWalk by int("MaxWalk", 24, 4..32, "blocks")
    private var target: BlockPos? = null
    private var targetWorld: Any? = null
    private var phase = Phase.IDLE
    private var aim: Rotation? = null
    private var route: ArrayDeque<BlockPos> = ArrayDeque()
    private var walked = 0
    private var ticks = 0
    private var stuck = 0
    private var lastX = 0.0
    private var lastZ = 0.0
    private enum class Phase { IDLE, BRAKE, MOVE, AIM }

    override fun onEnabled() {
        target = null; aim = null; phase = Phase.IDLE; route.clear()
        if (mc.level == null || mc.player == null) { enabled = false; return }
        mc.execute { if (enabled && mc.level != null) mc.gui.setScreen(PearlMapScreen()) }
    }

    override fun onDisabled() {
        phase = Phase.IDLE; target = null; targetWorld = null; aim = null; route.clear()
        if (mc.gui.screen() is PearlMapScreen) mc.gui.setScreen(null)
    }

    fun begin(pos: BlockPos) {
        if (!enabled || mc.level == null || mc.player == null) return
        if (!safeColumn(pos.x, pos.y, pos.z)) { chat("PointPearl: target is no longer safe/loaded."); return }
        val pearl = Slots.OffhandWithHotbar.findSlot(Items.ENDER_PEARL)
        if (pearl == null) { chat("PointPearl: put an ender pearl in hotbar or offhand."); return }
        if (player.cooldowns.isOnCooldown(pearl.itemStack)) { chat("PointPearl: pearl is on cooldown."); return }
        target = pos.immutable(); targetWorld = mc.level; phase = Phase.BRAKE
        route.clear(); aim = null; walked = 0; ticks = 0; stuck = 0
        lastX = player.x; lastZ = player.z
        mc.gui.setScreen(null)
        chat("PointPearl: planning to ${pos.x}, ${pos.y + 1}, ${pos.z}. Move/jump/sneak to cancel.")
    }

    fun cancelMap() { if (phase == Phase.IDLE && enabled) enabled = false }

    private fun cancel(reason: String) {
        if (phase != Phase.IDLE) chat("PointPearl: $reason")
        phase = Phase.IDLE; target = null; targetWorld = null; aim = null; route.clear()
        enabled = false
    }

    private val worldChange = handler<WorldChangeEvent> { cancel("world changed; cancelled.") }

    private val movement = handler<MovementInputEvent> { event ->
        if (phase == Phase.IDLE || mc.gui.screen() != null) return@handler
        if (event.directionalInput.isMoving || event.jump || event.sneak) {
            cancel("manual input; cancelled.")
            return@handler
        }
        if (phase == Phase.MOVE) {
            val next = route.firstOrNull()
            if (next == null || !player.onGround() || floor(player.y).toInt() != next.y + 1 ||
                !safeColumn(next.x, next.y, next.z)) {
                event.directionalInput = DirectionalInput.NONE
                cancel("route changed before movement.")
                return@handler
            }
            val dx = next.x + 0.5 - player.x; val dz = next.z + 0.5 - player.z
            val distance = hypot(dx, dz)
            if (distance < 0.23) {
                event.directionalInput = DirectionalInput.NONE
                return@handler
            }
            val step = minOf(distance, 0.32)
            val swept = player.boundingBox.expandTowards(dx / distance * step, 0.0, dz / distance * step)
            if (!world.noCollision(player, swept) || !supported(swept, next.y)) {
                event.directionalInput = DirectionalInput.NONE
                cancel("movement path obstructed or unsupported.")
                return@handler
            }
            // Set direction before emitting FORWARDS even on the first input event after planning.
            player.yRot = Math.toDegrees(atan2(-dx, dz)).toFloat()
            event.directionalInput = DirectionalInput.FORWARDS
        } else {
            event.directionalInput = DirectionalInput.NONE
        }
        event.jump = false
        event.sneak = false
    }

    private val rotationUpdate = handler<RotationUpdateEvent> {
        val rotation = if (phase == Phase.AIM) aim else null
        if (rotation != null) RotationManager.setRotationTarget(
            rotations.toRotationTarget(rotation), Priority.IMPORTANT_FOR_PLAYER_LIFE, this@ModulePointPearl
        )
    }

    private val tick = handler<GameTickEvent> {
        val goal = target ?: return@handler
        if (mc.level !== targetWorld || !player.isAlive || player.isPassenger || player.isFallFlying ||
            player.isInWater || mc.gui.screen() != null) { cancel("state changed; cancelled."); return@handler }
        if (!safeColumn(goal.x, goal.y, goal.z)) { cancel("target changed or unloaded."); return@handler }
        val slot = Slots.OffhandWithHotbar.findSlot(Items.ENDER_PEARL)
        if (slot == null || player.cooldowns.isOnCooldown(slot.itemStack)) {
            cancel("no usable pearl in hotbar/offhand."); return@handler
        }
        if (++ticks > 1200) { cancel("timed out."); return@handler }
        when (phase) {
            Phase.BRAKE -> {
                if (!player.onGround()) { cancel("not on stable ground."); return@handler }
                if (hypot(player.deltaMovement.x, player.deltaMovement.z) > 0.025) return@handler
                val solved = solve(goal)
                if (solved != null) { aim = solved; phase = Phase.AIM; stuck = 0; return@handler }
                if (!allowMove || walked >= maxWalk) { cancel("no validated trajectory from reachable ground."); return@handler }
                val path = findRoute(goal)
                if (path.isEmpty()) { cancel("no conservative flat route to a better launch point."); return@handler }
                route = ArrayDeque(path.take(minOf(4, maxWalk - walked)))
                phase = Phase.MOVE; stuck = 0; lastX = player.x; lastZ = player.z
            }
            Phase.MOVE -> {
                val next = route.firstOrNull() ?: run { phase = Phase.BRAKE; return@handler }
                val currentY = floor(player.y).toInt()
                if (!player.onGround() || currentY != next.y + 1 || !safeColumn(next.x, next.y, next.z)) {
                    cancel("route changed or player left the ground."); return@handler
                }
                val dx = next.x + 0.5 - player.x; val dz = next.z + 0.5 - player.z
                val distance = hypot(dx, dz)
                if (distance < 0.23) {
                    route.removeFirst(); walked++
                    // Brake at each node so momentum cannot carry the player beyond checked ground.
                    phase = Phase.BRAKE; return@handler
                }
                val step = minOf(distance, 0.32)
                val swept = player.boundingBox.expandTowards(dx / distance * step, 0.0, dz / distance * step)
                if (!world.noCollision(player, swept) || !supported(swept, next.y)) {
                    cancel("movement path obstructed or unsupported."); return@handler
                }
                player.yRot = Math.toDegrees(atan2(-dx, dz)).toFloat()
                if (hypot(player.x - lastX, player.z - lastZ) < 0.015) stuck++ else stuck = 0
                lastX = player.x; lastZ = player.z
                if (stuck > 25) { cancel("movement blocked."); return@handler }
            }
            Phase.AIM -> {
                if (!player.onGround() || hypot(player.deltaMovement.x, player.deltaMovement.z) > 0.025) {
                    aim = null; phase = Phase.BRAKE; return@handler
                }
                val wanted = aim ?: run { phase = Phase.BRAKE; return@handler }
                if (++stuck > 80) { cancel("could not align rotation."); return@handler }
                val actual = RotationManager.serverRotation
                if (actual.directionAngleTo(wanted) > 0.25f) return@handler
                if (!hitsTarget(goal, actual)) { cancel("trajectory changed or became blocked."); return@handler }
                // Only one use request; the server is authoritative and may still reject or scatter it.
                useHotbarSlotOrOffhand(slot, 0, actual.yaw, actual.pitch)
                chat("PointPearl: throw requested (actual landing is server-authoritative).")
                phase = Phase.IDLE; enabled = false
            }
            Phase.IDLE -> Unit
        }
    }

    /** Only full-height solid floors with two air blocks. Reject known dangerous blocks. */
    fun safeColumn(x: Int, floorY: Int, z: Int): Boolean {
        val level = mc.level ?: return false
        if (!level.hasChunkAt(x, z)) return false
        val floor = BlockPos(x, floorY, z)
        val state = level.getBlockState(floor)
        val block = state.block
        if (block == Blocks.MAGMA_BLOCK || block == Blocks.CACTUS || block == Blocks.CAMPFIRE ||
            block == Blocks.SOUL_CAMPFIRE || block == Blocks.POWDER_SNOW || block == Blocks.FIRE ||
            block == Blocks.SOUL_FIRE || block == Blocks.SWEET_BERRY_BUSH) return false
        if (!state.fluidState.isEmpty || !state.isCollisionShapeFullBlock(level, floor)) return false
        val feet = level.getBlockState(floor.above())
        val head = level.getBlockState(floor.above(2))
        if (!feet.isAir || !head.isAir || !feet.fluidState.isEmpty || !head.fluidState.isEmpty) return false
        return level.noCollision(player, AABB(x + 0.2, floorY + 1.0, z + 0.2, x + 0.8, floorY + 2.8, z + 0.8))
    }

    /** Check support under all corners of the swept player footprint, not just its centre. */
    private fun supported(box: AABB, floorY: Int): Boolean {
        val xs = intArrayOf(floor(box.minX + 0.001).toInt(), floor(box.maxX - 0.001).toInt())
        val zs = intArrayOf(floor(box.minZ + 0.001).toInt(), floor(box.maxZ - 0.001).toInt())
        for (x in xs) for (z in zs) if (!safeColumn(x, floorY, z)) return false
        return true
    }

    private fun hitsTarget(goal: BlockPos, rotation: Rotation): Boolean {
        val sim = TrajectoryInfoRenderer.getHypotheticalTrajectory(
            simulationOwner = player, trajectoryInfo = TrajectoryInfo.GENERIC,
            trajectoryType = TrajectoryType.EnderPearl, rotation = rotation, partialTicks = 0f
        ).runSimulation(160)
        // Simulation treats missing chunks as empty; never authorize a trajectory through them.
        if (sim.positions.any { !world.hasChunkAt(floor(it.x).toInt(), floor(it.z).toInt()) }) return false
        val hit = sim.hitResult as? BlockHitResult ?: return false
        if (hit.blockPos != goal || hit.direction != Direction.UP) return false
        val p = hit.location
        return hypot(p.x - (goal.x + 0.5), p.z - (goal.z + 0.5)) <= tolerance
    }

    private fun solve(goal: BlockPos): Rotation? {
        val center = Vec3(goal.x + 0.5, goal.y + 1.0, goal.z + 0.5)
        val base = SituationalProjectileAngleCalculator.calculateAngleFor(
            TrajectoryInfo.GENERIC, sourcePos = player.position(),
            targetPosFunction = PositionExtrapolation.constant(center),
            targetShape = EntityDimensions.fixed(1f, 0f)
        ) ?: return null
        for (dp in floatArrayOf(0f, -0.7f, 0.7f, -1.4f, 1.4f)) {
            for (dy in floatArrayOf(0f, -0.7f, 0.7f)) {
                val candidate = Rotation(base.yaw + dy, (base.pitch + dp).coerceIn(-90f, 90f))
                if (hitsTarget(goal, candidate)) return candidate
            }
        }
        return null
    }

    /** 4-neighbour BFS on one elevation; no jumping, drops, stairs, swimming or block breaking. */
    private fun findRoute(goal: BlockPos): List<BlockPos> {
        val y = floor(player.y).toInt() - 1
        val start = BlockPos(floor(player.x).toInt(), y, floor(player.z).toInt())
        if (!safeColumn(start.x, y, start.z)) return emptyList()
        val queue = ArrayDeque<BlockPos>(); queue.add(start)
        val prev = HashMap<BlockPos, BlockPos?>(); prev[start] = null
        val depth = HashMap<BlockPos, Int>(); depth[start] = 0
        fun dist(p: BlockPos) = hypot((p.x - goal.x).toDouble(), (p.z - goal.z).toDouble())
        val initial = dist(start)
        var best = start; var bestDistance = initial
        val directions = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        while (queue.isNotEmpty() && prev.size < 5000) {
            val node = queue.removeFirst(); val d = depth[node] ?: continue
            if (d >= maxWalk - walked) continue
            for ((dx, dz) in directions) {
                val n = node.offset(dx, 0, dz)
                if (prev.containsKey(n) || !safeColumn(n.x, y, n.z)) continue
                prev[n] = node; depth[n] = d + 1; queue.add(n)
                val distance = dist(n)
                // Avoid walking right onto the clicked tile and calling it a pearl teleport.
                if (distance >= 5.0 && distance < bestDistance - 0.05) { best = n; bestDistance = distance }
            }
        }
        if (best == start || initial - bestDistance < 1.0) return emptyList()
        val path = ArrayList<BlockPos>(); var node: BlockPos? = best
        while (node != null && node != start) { path.add(node); node = prev[node] }
        path.reverse(); return path
    }
}
