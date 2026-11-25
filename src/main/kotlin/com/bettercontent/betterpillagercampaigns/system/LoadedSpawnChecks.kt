package com.bettercontent.betterpillagercampaigns.system

import com.bettercontent.pillagercampaigns.core.VirtualReachability
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.abs

/** Live checks and packet placement only. This never proposes a campaign route. */
internal object LoadedSpawnChecks {
    private const val PACKET_RADIUS = 5
    private val cardinal = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)

    fun isNearSurface(player: ServerPlayer): Boolean {
        val chunk = player.serverLevel().chunkSource.getChunkNow(player.blockX shr 4, player.blockZ shr 4) ?: return false
        val surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.blockX and 15, player.blockZ and 15) + 1
        return player.blockY >= surfaceY - 8
    }

    fun memberPositions(level: ServerLevel, anchor: BlockPos, count: Int): List<BlockPos> {
        if (!exactColumn(level, anchor)) return emptyList()
        fun BlockPos.point() = VirtualReachability.Point(x, y, z)
        val local = VirtualReachability(anchor.point(), VirtualReachability.Bounds(
            anchor.x - PACKET_RADIUS..anchor.x + PACKET_RADIUS, level.minBuildHeight..level.maxBuildHeight - 1,
            anchor.z - PACKET_RADIUS..anchor.z + PACKET_RADIUS), { false }, { 0 }, 1, VirtualCampaignTerrain(level, 0)::cell)
        val queue = java.util.ArrayDeque<BlockPos>()
        val seen = hashSetOf(anchor.x to anchor.z)
        val positions = mutableListOf<BlockPos>()
        queue += anchor
        while (queue.isNotEmpty() && positions.size < count) {
            val current = queue.removeFirst()
            positions += current
            cardinal.forEach { (dx, dz) ->
                val key = current.x + dx to current.z + dz
                if (key in seen || maxOf(abs(key.first - anchor.x), abs(key.second - anchor.z)) > PACKET_RADIUS) return@forEach
                (1 downTo -2).asSequence().map { dy -> BlockPos(key.first, current.y + dy, key.second) }
                    .firstOrNull { exactColumn(level, it) && local.allowsWalk(current.point(), it.point()) }
                    ?.let { seen.add(key); queue.add(it) }
            }
        }
        return positions.takeIf { it.size == count }.orEmpty()
    }

    private fun exactColumn(level: ServerLevel, body: BlockPos): Boolean {
        if (level.chunkSource.getChunkNow(body.x shr 4, body.z shr 4) == null) return false
        val floor = body.below()
        val floorState = level.getBlockState(floor)
        return floorState.fluidState.isEmpty && floorState.isSolidRender(level, floor) &&
            level.getFluidState(body).isEmpty && level.getFluidState(body.above()).isEmpty &&
            level.getBlockState(body).getCollisionShape(level, body).isEmpty &&
            level.getBlockState(body.above()).getCollisionShape(level, body.above()).isEmpty
    }

    fun loadedRectangle(level: ServerLevel, first: BlockPos, second: BlockPos, margin: Int = 6): Boolean {
        val minChunkX = (minOf(first.x, second.x) - margin) shr 4
        val maxChunkX = (maxOf(first.x, second.x) + margin) shr 4
        val minChunkZ = (minOf(first.z, second.z) - margin) shr 4
        val maxChunkZ = (maxOf(first.z, second.z) + margin) shr 4
        for (chunkX in minChunkX..maxChunkX) for (chunkZ in minChunkZ..maxChunkZ) {
            if (level.chunkSource.getChunkNow(chunkX, chunkZ) == null) return false
        }
        return true
    }
}
