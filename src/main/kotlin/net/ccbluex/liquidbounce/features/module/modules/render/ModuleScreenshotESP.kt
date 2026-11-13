/* GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.DrawOutlinesEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.CachedMeshStorage
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.addShapeFaces
import net.ccbluex.liquidbounce.render.buildMesh
import net.ccbluex.liquidbounce.render.drawGenericBlockESP
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.translate
import net.ccbluex.liquidbounce.render.utils.DistanceFadeUniformValueGroup
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.block.AbstractBlockLocationTracker
import net.ccbluex.liquidbounce.utils.block.ChunkScanner
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes

/**
 * Screenshot-style outlines. The vanilla outline target has no scene-depth test: VisibleOnly is
 * therefore an entity/block-level multi-ray approximation, not per-pixel depth occlusion.
 */
object ModuleScreenshotESP : ClientModule("ScreenshotESP", ModuleCategories.RENDER) {
    // AllTargets includes all entity categories, the local player, held items and block entities.
    val allTargets by boolean("AllTargets", false)
    val players by boolean("Players", true)
    val self by boolean("Self", false)
    val animals by boolean("Animals", false)
    val otherLiving by boolean("OtherLiving", false)
    val otherEntities by boolean("OtherEntities", false)
    val droppedItems by boolean("DroppedItems", true)
    val heldItems by boolean("HeldItems", true)
    val storageBlocks by boolean("StorageBlocks", false)
    val otherBlockEntities by boolean("OtherBlockEntities", false)
    enum class HaloStyle(override val tag: String) : Tagged { GLOW("Glow"), SHADOW("Shadow") }
    val halo by boolean("Halo", true)
    val haloStyle by enumChoice("HaloStyle", HaloStyle.GLOW)
    val haloRadius by float("HaloRadius", 10f, 2f..20f, "px")
    val haloStrength by float("HaloStrength", 0.65f, 0f..1f)
    val throughWalls by boolean("ThroughWalls", true)
    val color by color("Color", Color4b.WHITE)
    private val maxDistance by float("MaximumDistance", 128f, 1f..512f)

    private val animalCategories = setOf(MobCategory.CREATURE, MobCategory.AMBIENT, MobCategory.AXOLOTLS,
        MobCategory.UNDERGROUND_WATER_CREATURE, MobCategory.WATER_CREATURE, MobCategory.WATER_AMBIENT)

    val renderHeldItems: Boolean get() = allTargets || heldItems
    private val renderBlocks: Boolean get() = allTargets || storageBlocks || otherBlockEntities
    private val blockMesh = CachedMeshStorage("ScreenshotESP BlockEntities")
    private val blockFade = DistanceFadeUniformValueGroup()
    private var blockRefreshTicks = 0

    override fun onEnabled() {
        blockRefreshTicks = 0
        ChunkScanner.subscribe(BlockEntityScanner)
    }

    override fun onDisabled() {
        ChunkScanner.unsubscribe(BlockEntityScanner)
        blockMesh.clearStates()
        blockMesh.clearBuffers()
    }

    fun wants(entity: Entity?): Boolean {
        val localPlayer = mc.player ?: return false
        val level = mc.level ?: return false
        if (!running || entity == null) return false
        val selected = allTargets || when {
            entity === localPlayer -> self
            entity is Player -> players
            entity is ItemEntity -> droppedItems
            entity is Animal || entity is LivingEntity && entity.type.category in animalCategories -> animals
            entity is LivingEntity -> otherLiving
            else -> otherEntities
        }
        if (!selected) return false
        val eye = mc.gameRenderer.mainCamera().position()
        if (eye.distanceToSqr(entity.position()) > maxDistance * maxDistance) return false
        // The camera can be inside the local player's own bounds; ray tests cannot classify it.
        if (throughWalls || entity === localPlayer) return true
        val box = entity.boundingBox
        val cx = (box.minX + box.maxX) * .5
        val cy = (box.minY + box.maxY) * .5
        val cz = (box.minZ + box.maxZ) * .5
        val points = listOf(Vec3(cx,cy,cz), Vec3(cx,box.maxY,cz), Vec3(cx,box.minY,cz),
            Vec3(box.minX,cy,cz), Vec3(box.maxX,cy,cz), Vec3(cx,cy,box.minZ), Vec3(cx,cy,box.maxZ))
        return points.any { point ->
            level.clip(ClipContext(eye, point, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, localPlayer)).type ==
                HitResult.Type.MISS
        }
    }

    /** Containers and other block entities use the same outlined silhouette path as StorageESP. */
    private object BlockEntityScanner : AbstractBlockLocationTracker.State2BlockPos<Boolean>() {
        override val shouldCallRecordBlockOnChunkUpdate: Boolean get() = false
        override fun chunkUpdate(chunk: LevelChunk) {
            for ((pos, _) in chunk.blockEntities) track(pos, true)
        }
        override fun getStateFor(pos: BlockPos, state: BlockState): Boolean? =
            if (state.hasBlockEntity()) true else null
    }

    @Suppress("unused")
    private val blockTick = handler<GameTickEvent> {
        val level = mc.level ?: return@handler
        val localPlayer = mc.player ?: return@handler
        if (!renderBlocks || color.isTransparent || BlockEntityScanner.isEmpty()) {
            blockMesh.clearStates()
            blockRefreshTicks = 0
            return@handler
        }
        // Recheck range, settings and occlusion periodically; chunk tracker handles load/unload.
        if (blockRefreshTicks++ % 5 != 0) return@handler
        val eye = mc.gameRenderer.mainCamera().position()
        val selected = BlockEntityScanner.allPositions().mapNotNull { mutable ->
            val pos = mutable.immutable()
            val blockEntity = level.getBlockEntity(pos) ?: return@mapNotNull null
            val isStorage = with(ModuleStorageESP) { blockEntity.categorize() != null }
            if (!allTargets && !(if (isStorage) storageBlocks else otherBlockEntities)) return@mapNotNull null
            val center = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            if (eye.distanceToSqr(center) > maxDistance * maxDistance) return@mapNotNull null
            if (!throughWalls && !blockVisible(eye, pos, localPlayer)) return@mapNotNull null
            val state = level.getBlockState(pos)
            if (state.isAir) return@mapNotNull null
            val shape = state.getShape(level, pos)
            pos to if (shape.isEmpty) Shapes.block() else shape
        }.toList()
        if (selected.isEmpty()) {
            blockMesh.clearStates()
            return@handler
        }
        blockMesh.buildMesh(ClientRenderPipelines.outlineQuads(useColor = true), localPlayer.blockPosition()) { pose, origin ->
            for ((pos, shape) in selected) {
                pose.withPush {
                    translate(pos.asLong(), origin)
                    addShapeFaces(last().pose(), shape, color)
                }
            }
        }
    }

    /** A ray hitting the target block itself counts as visible; a nearer block is an occluder. */
    private fun blockVisible(eye: Vec3, pos: BlockPos, player: Player): Boolean {
        val level = mc.level ?: return false
        val x = pos.x.toDouble(); val y = pos.y.toDouble(); val z = pos.z.toDouble()
        val points = listOf(Vec3(x+.5,y+.5,z+.5), Vec3(x+.15,y+.5,z+.5), Vec3(x+.85,y+.5,z+.5),
            Vec3(x+.5,y+.15,z+.5), Vec3(x+.5,y+.85,z+.5), Vec3(x+.5,y+.5,z+.15), Vec3(x+.5,y+.5,z+.85))
        return points.any { point ->
            val hit = level.clip(ClipContext(eye, point, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
            hit.type == HitResult.Type.MISS || hit is BlockHitResult && hit.blockPos == pos
        }
    }

    @Suppress("unused")
    private val blockOutline = handler<DrawOutlinesEvent> { event ->
        if (!renderBlocks || color.isTransparent) return@handler
        if (event.renderTarget.drawGenericBlockESP(blockMesh, ClientRenderPipelines.outlineQuads(useColor = true), blockFade)) {
            event.markDirty()
        }
    }
}
