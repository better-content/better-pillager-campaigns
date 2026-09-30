package com.bettercontent.betterpillagercampaigns.data

import com.bettercontent.betterpillagercampaigns.core.SurfaceCell
import com.bettercontent.betterpillagercampaigns.system.SurfaceGridSampler
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.saveddata.SavedData
import kotlin.math.abs

/** A compact record of terrain that has genuinely been loaded. Reads never load or generate chunks. */
class TerrainAtlasData private constructor(
    private val chunks: MutableMap<Long, AtlasChunk> = linkedMapOf(),
    var revision: Long = 0L,
) : SavedData() {
    data class AtlasChunk(val x: Int, val z: Int, var lastSeen: Long, val columns: IntArray)

    fun observe(level: ServerLevel, chunk: LevelChunk) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return
        val pos = chunk.pos
        val encoded = IntArray(256)
        for (localZ in 0..15) for (localX in 0..15) {
            val cell = SurfaceGridSampler.surfaceCell(level, chunk, pos.minBlockX + localX, pos.minBlockZ + localZ)
            encoded[localZ * 16 + localX] = encode(cell)
        }
        val key = pos.toLong()
        val old = chunks[key]
        if (old == null || !old.columns.contentEquals(encoded)) revision++
        chunks[key] = AtlasChunk(pos.x, pos.z, level.gameTime, encoded)
        evict()
        setDirty()
    }

    fun cellsAround(targetX: Int, targetZ: Int, radius: Int): List<SurfaceCell> {
        val minChunkX = (targetX - radius) shr 4
        val maxChunkX = (targetX + radius) shr 4
        val minChunkZ = (targetZ - radius) shr 4
        val maxChunkZ = (targetZ + radius) shr 4
        val result = ArrayList<SurfaceCell>()
        for (chunkZ in minChunkZ..maxChunkZ) for (chunkX in minChunkX..maxChunkX) {
            val chunk = chunks[ChunkPos.asLong(chunkX, chunkZ)] ?: continue
            for (localZ in 0..15) for (localX in 0..15) {
                val x = (chunkX shl 4) + localX
                val z = (chunkZ shl 4) + localZ
                if (maxOf(abs(x - targetX), abs(z - targetZ)) <= radius)
                    result += decode(x, z, chunk.columns[localZ * 16 + localX])
            }
        }
        return result
    }

    override fun save(tag: CompoundTag): CompoundTag {
        tag.putInt("schema", 1)
        tag.putLong("revision", revision)
        val list = ListTag()
        chunks.values.forEach { chunk ->
            val entry = CompoundTag()
            entry.putInt("x", chunk.x)
            entry.putInt("z", chunk.z)
            entry.putLong("seen", chunk.lastSeen)
            entry.putIntArray("columns", chunk.columns)
            list.add(entry)
        }
        tag.put("chunks", list)
        return tag
    }

    private fun evict() {
        if (chunks.size <= MAX_CHUNKS) return
        chunks.values.sortedWith(compareBy<AtlasChunk>(AtlasChunk::lastSeen).thenBy(AtlasChunk::x).thenBy(AtlasChunk::z))
            .take(chunks.size - MAX_CHUNKS).forEach { chunks.remove(ChunkPos.asLong(it.x, it.z)) }
    }

    companion object {
        private const val KEY = "better_pillager_campaigns_terrain_atlas"
        private const val MAX_CHUNKS = 65_536

        fun get(server: MinecraftServer): TerrainAtlasData = server.overworld().dataStorage.computeIfAbsent(
            ::load, ::TerrainAtlasData, KEY,
        )

        internal fun load(tag: CompoundTag): TerrainAtlasData {
            if (tag.getInt("schema") != 1) return TerrainAtlasData()
            val chunks = linkedMapOf<Long, AtlasChunk>()
            val list = tag.getList("chunks", Tag.TAG_COMPOUND.toInt())
            for (index in 0 until list.size) {
                val entry = list.getCompound(index)
                val columns = entry.getIntArray("columns")
                if (columns.size != 256) continue
                val chunk = AtlasChunk(entry.getInt("x"), entry.getInt("z"), entry.getLong("seen"), columns)
                chunks[ChunkPos.asLong(chunk.x, chunk.z)] = chunk
            }
            return TerrainAtlasData(chunks, tag.getLong("revision"))
        }

        private fun encode(cell: SurfaceCell): Int = (cell.bodyY and 0xffff) or (if (cell.passable) 1 shl 16 else 0)
        private fun decode(x: Int, z: Int, encoded: Int): SurfaceCell {
            val unsignedY = encoded and 0xffff
            val y = if (unsignedY >= 0x8000) unsignedY - 0x10000 else unsignedY
            return SurfaceCell(x, y, z, encoded and (1 shl 16) != 0)
        }
    }
}
