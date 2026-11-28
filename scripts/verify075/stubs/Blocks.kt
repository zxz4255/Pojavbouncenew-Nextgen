package net.ccbluex.liquidbounce.utils.block
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
open class AbstractBlockLocationTracker<T> {
 abstract class State2BlockPos<T> {
  open val shouldCallRecordBlockOnChunkUpdate = false
  abstract fun getStateFor(pos: BlockPos, state: BlockState): T?
  open fun chunkUpdate(chunk: LevelChunk) {}
  fun track(pos: BlockPos, state: T) {}
  fun allPositions(): Sequence<BlockPos> = emptySequence()
 }
}
object ChunkScanner { fun subscribe(x: Any) {}; fun unsubscribe(x: Any) {} }
