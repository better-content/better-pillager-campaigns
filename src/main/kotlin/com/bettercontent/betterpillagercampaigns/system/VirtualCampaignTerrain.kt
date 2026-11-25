package com.bettercontent.betterpillagercampaigns.system

import com.bettercontent.pillagercampaigns.core.VirtualReachability.Cell
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Point
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SupportType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.PalettedContainerRO
import net.minecraft.world.level.material.FluidState
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** One query's read-only view. Live chunks win; neither source takes a chunk ticket. */
internal class VirtualCampaignTerrain(private val level: ServerLevel, private val maximumSavedReads: Int) : BlockGetter {
    init { require(maximumSavedReads >= 0) }

    private val saved = mutableMapOf<Long, CompletableFuture<SavedChunk?>>()
    private val liveChunkKeys = mutableSetOf<Long>()
    private val cellMemo = mutableMapOf<Point, Cell>()
    val savedReads: Int get() = saved.size
    val liveReads: Int get() = liveChunkKeys.size

    /** Live shape lookups stay on the server thread; clear transient data between tick slices. */
    fun beginSlice() {
        check(level.server.isSameThread)
        cellMemo.clear()
    }

    fun cell(point: Point): Cell = cellMemo.getOrPut(point) {
        val pos = BlockPos(point.x, point.y, point.z)
        if (isOutsideBuildHeight(pos) || !level.worldBorder.isWithinBounds(pos)) return@getOrPut Cell.OBSTACLE
        try {
            val block = getBlockState(pos)
            if (!block.fluidState.isEmpty || block.`is`(Blocks.POWDER_SNOW) || block.`is`(Blocks.CACTUS) ||
                block.`is`(Blocks.MAGMA_BLOCK) || block.`is`(Blocks.CAMPFIRE) || block.`is`(Blocks.SOUL_CAMPFIRE)) {
                return@getOrPut Cell.OBSTACLE
            }
            val shape = block.getCollisionShape(this, pos)
            when {
                shape.isEmpty -> Cell.CLEAR
                shape.max(Direction.Axis.Y) == 1.0 &&
                    block.isFaceSturdy(this, pos, Direction.UP, SupportType.FULL) -> Cell.SUPPORT
                else -> Cell.OBSTACLE
            }
        } catch (_: AwaitingChunk) {
            Cell.PENDING
        } catch (_: MissingTerrain) {
            Cell.UNKNOWN
        }
    }

    fun invalidate(pos: ChunkPos) { saved.remove(pos.toLong()) }

    /** Request all witness columns without blocking; collision shapes may request extra neighbors. */
    fun prefetch(path: List<Point>) {
        for (point in path.distinctBy { ChunkPos.asLong(it.x shr 4, it.z shr 4) }) {
            try { getBlockState(BlockPos(point.x, point.y, point.z)) }
            catch (_: AwaitingChunk) { /* Needed by a later validation pass. */ }
            catch (_: MissingTerrain) { /* Validation will report unknown if this column is needed. */ }
        }
    }

    override fun getHeight() = level.height
    override fun getMinBuildHeight() = level.minBuildHeight
    override fun getBlockEntity(pos: BlockPos): BlockEntity? =
        if (getBlockState(pos).hasBlockEntity()) throw MissingTerrain else null
    override fun getFluidState(pos: BlockPos): FluidState = getBlockState(pos).fluidState

    override fun getBlockState(pos: BlockPos): BlockState {
        if (isOutsideBuildHeight(pos)) return Blocks.VOID_AIR.defaultBlockState()
        val chunkX = pos.x shr 4
        val chunkZ = pos.z shr 4
        val key = ChunkPos.asLong(chunkX, chunkZ)
        level.chunkSource.getChunkNow(chunkX, chunkZ)?.let { live ->
            liveChunkKeys += key
            return live.getBlockState(pos)
        }
        val future = saved[key] ?: run {
            if (saved.size >= maximumSavedReads) throw MissingTerrain
            val chunk = ChunkPos(chunkX, chunkZ)
            level.chunkSource.chunkMap.read(chunk)
                .thenApplyAsync { tag -> tag.orElse(null)?.let { SavedChunk.decode(chunk, it) } }
                .also { saved[key] = it }
        }
        if (!future.isDone) throw AwaitingChunk
        val stored = try { future.getNow(null) } catch (_: CompletionException) { null }
        return stored?.block(pos) ?: throw MissingTerrain
    }

    private object AwaitingChunk : RuntimeException(null, null, false, false)
    private object MissingTerrain : RuntimeException(null, null, false, false)

    private class SavedChunk(private val sections: Map<Int, PalettedContainerRO<BlockState>>) {
        fun block(pos: BlockPos): BlockState = sections[pos.y shr 4]?.get(pos.x and 15, pos.y and 15, pos.z and 15)
            ?: Blocks.AIR.defaultBlockState()

        companion object {
            private val states = PalettedContainer.codecRO(
                Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES,
                Blocks.AIR.defaultBlockState(),
            )

            /** Decode only current-version, fully generated chunk palettes, with no POI/entity work. */
            fun decode(pos: ChunkPos, tag: CompoundTag): SavedChunk? {
                if (tag.getInt("DataVersion") != SharedConstants.getCurrentVersion().dataVersion.version ||
                    tag.getInt("xPos") != pos.x || tag.getInt("zPos") != pos.z ||
                    ChunkStatus.byName(tag.getString("Status")) != ChunkStatus.FULL ||
                    !tag.contains("sections", Tag.TAG_LIST.toInt())) return null
                val sections = mutableMapOf<Int, PalettedContainerRO<BlockState>>()
                val list = tag.getList("sections", Tag.TAG_COMPOUND.toInt())
                for (i in 0 until list.size) {
                    val section = list.getCompound(i)
                    if (!section.contains("block_states")) continue // Lighting-only section.
                    if (!section.contains("Y", Tag.TAG_BYTE.toInt()) ||
                        !section.contains("block_states", Tag.TAG_COMPOUND.toInt())) return null
                    val palette = states.parse(NbtOps.INSTANCE, section.getCompound("block_states")).result().orElse(null)
                        ?: return null
                    if (sections.put(section.getByte("Y").toInt(), palette) != null) return null
                }
                return SavedChunk(sections)
            }
        }
    }
}
