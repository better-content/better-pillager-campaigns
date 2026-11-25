package com.bettercontent.betterpillagercampaigns.gametest

import com.bettercontent.betterpillagercampaigns.PillagerCampaignsMod
import com.bettercontent.pillagercampaigns.core.VirtualReachability
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Bounds
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Point
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Result
import com.bettercontent.pillagercampaigns.core.VirtualReachability.UnknownReason
import com.bettercontent.betterpillagercampaigns.system.VirtualCampaignTerrain
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(PillagerCampaignsMod.MOD_ID)
@PrefixGameTestTemplate(false)
object VirtualCampaignNavigationGameTests {
    private val ticket = TicketType.create("virtual_navigation_fixture", compareBy<ChunkPos>({ it.x }, { it.z }))

    @JvmStatic
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena",
        batch = "virtual_navigation_tunnel", timeoutTicks = 3_600)
    fun loadedMixedAndSavedTunnelsAgree(helper: GameTestHelper) {
        val reference = helper.absolutePos(BlockPos.ZERO)
        val west = ChunkPos((reference.x shr 4) + 70, (reference.z shr 4) + 70)
        val open = tunnel(helper, west, open = true)
        val sealed = tunnel(helper, ChunkPos(west.x + 6, west.z), open = false)
        val westChunks = listOf(open.west, sealed.west)
        val eastChunks = listOf(open.east, sealed.east)
        val both = westChunks + eastChunks

        fun pair(mode: String, present: List<ChunkPos>, absent: List<ChunkPos>, next: () -> Unit) {
            val expectLive = when (mode) { "loaded" -> 2; "mixed" -> 1; else -> 0 }
            assertResidency(helper, present, absent)
            probe(helper, "$mode-open", open.start, open.end, Result.Reachable(open.end), 2, 2_000,
                present, absent, expectedSavedReads = 2 - expectLive, expectedLiveReads = expectLive) {
                probe(helper, "$mode-sealed", sealed.start, sealed.end, Result.Unreachable, 2, 2_000,
                    present, absent, expectedSavedReads = 2 - expectLive, expectedLiveReads = expectLive, done = next)
            }
        }

        pair("loaded", both, emptyList()) {
            eastChunks.forEach { release(helper, it) }
            awaitUnloaded(helper, eastChunks) {
                pair("mixed", westChunks, eastChunks) {
                    westChunks.forEach { release(helper, it) }
                    awaitUnloaded(helper, both) { pair("saved", emptyList(), both, helper::succeed) }
                }
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena",
        batch = "virtual_navigation_distance", timeoutTicks = 3_600)
    fun provesA600BlockRouteWithoutLoadingTheStrip(helper: GameTestHelper) {
        val reference = helper.absolutePos(BlockPos.ZERO)
        val first = ChunkPos((reference.x shr 4) + 85, (reference.z shr 4) + 85)
        val x = first.minBlockX + 8
        val z = first.minBlockZ + 8
        val distance = 600
        val strip = (first.x..((x + distance) shr 4)).map { ChunkPos(it, first.z) }
        strip.forEach { hold(helper, it) }
        val y = helper.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
        val start = Point(x, y, z)
        val end = start.offset(dx = distance)
        strip.forEach { release(helper, it) }
        awaitUnloaded(helper, strip) {
            probe(helper, "saved-600", start, end, Result.Reachable(end), 64, 4_096,
                emptyList(), strip, expectedSavedReads = strip.size, expectedLiveReads = 0, done = helper::succeed)
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena",
        batch = "virtual_navigation_unknown", timeoutTicks = 900)
    fun ungeneratedOriginStaysUnknownAndIsNotGenerated(helper: GameTestHelper) {
        val reference = helper.absolutePos(BlockPos.ZERO)
        val chunk = ChunkPos((reference.x shr 4) + 190, (reference.z shr 4) + 190)
        val start = Point(chunk.minBlockX + 8, helper.level.minBuildHeight + 4, chunk.minBlockZ + 8)
        probe(helper, "unsaved-origin", start, start.offset(dx = 2), Result.Unknown(UnknownReason.TERRAIN_OR_BOUNDARY),
            1, 64, emptyList(), listOf(chunk), expectedSavedReads = 1, expectedLiveReads = 0) {
            val stored = helper.level.chunkSource.chunkMap.read(chunk)
            fun confirm() {
                if (!stored.isDone) { helper.runAfterDelay(1, ::confirm); return }
                helper.assertTrue(stored.getNow(null)?.isEmpty == true,
                    "The fixture origin must have no saved chunk before or after attempting navigation")
                assertResidency(helper, emptyList(), listOf(chunk))
                helper.succeed()
            }
            confirm()
        }
    }

    private data class Tunnel(val start: Point, val end: Point, val west: ChunkPos, val east: ChunkPos)

    private fun tunnel(helper: GameTestHelper, west: ChunkPos, open: Boolean): Tunnel {
        val level = helper.level
        val east = ChunkPos(west.x + 1, west.z)
        listOf(west, east).forEach { hold(helper, it) }
        val seam = east.minBlockX
        val z = west.minBlockZ + 8
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, seam - 4, z)
        for (x in seam - 6..seam + 2) for (dz in -2..2) for (dy in 0..2) {
            val shell = x == seam - 6 || x == seam + 2 || dz == -2 || dz == 2 || dy == 2
            val state = when {
                x == seam + 2 && dz == 0 && dy == 0 -> Blocks.OAK_FENCE_GATE.defaultBlockState()
                    .setValue(FenceGateBlock.FACING, Direction.EAST).setValue(FenceGateBlock.OPEN, open)
                x == seam + 2 && dz == 0 && dy == 1 -> Blocks.AIR.defaultBlockState()
                shell -> Blocks.STONE.defaultBlockState()
                else -> Blocks.AIR.defaultBlockState()
            }
            level.setBlock(BlockPos(x, y + dy, z + dz), state, 2)
        }
        return Tunnel(Point(seam - 4, y, z), Point(seam + 4, y, z), west, east)
    }

    private fun probe(
        helper: GameTestHelper, label: String, start: Point, end: Point, expected: Result,
        readLimit: Int, expansionLimit: Int, present: List<ChunkPos>, absent: List<ChunkPos>,
        expectedSavedReads: Int, expectedLiveReads: Int, done: () -> Unit,
    ) {
        val view = VirtualCampaignTerrain(helper.level, readLimit)
        val bounds = Bounds(minOf(start.x, end.x) - 12 .. maxOf(start.x, end.x) + 12,
            start.y - 4 .. start.y + 8, start.z - 6 .. start.z + 6)
        val search = VirtualReachability(start, bounds, { it == end }, { it.lowerBoundTo(end) }, expansionLimit, view::cell)
        var slices = 0
        var maxSliceNanos = 0L
        fun advance() {
            assertResidency(helper, present, absent)
            val begin = System.nanoTime()
            view.beginSlice()
            val result = search.advance(48)
            maxSliceNanos = maxOf(maxSliceNanos, System.nanoTime() - begin)
            slices++
            assertResidency(helper, present, absent)
            if (result == Result.Running) { helper.runAfterDelay(1, ::advance); return }
            PillagerCampaignsMod.LOGGER.info(
                "VIRTUAL_NAV case={} result={} origin={} goal={} expansions={} slices={} savedReads={} liveReads={} maxSliceMicros={}",
                label, result, start, end, search.expanded, slices, view.savedReads, view.liveReads, maxSliceNanos / 1_000,
            )
            helper.assertTrue(result == expected, "$label: expected $expected, got $result")
            helper.assertTrue(view.savedReads == expectedSavedReads && view.liveReads == expectedLiveReads,
                "$label: expected $expectedSavedReads saved/$expectedLiveReads live chunk reads, got ${view.savedReads}/${view.liveReads}")
            done()
        }
        advance()
    }

    private fun hold(helper: GameTestHelper, pos: ChunkPos) {
        helper.level.chunkSource.addRegionTicket(ticket, pos, 0, pos)
        helper.level.getChunk(pos.x, pos.z)
    }

    private fun release(helper: GameTestHelper, pos: ChunkPos) = helper.level.chunkSource.removeRegionTicket(ticket, pos, 0, pos)

    private fun awaitUnloaded(helper: GameTestHelper, expected: List<ChunkPos>, ready: () -> Unit) {
        // Give the actual unload/save work time to complete; queries never take the fixture's tickets.
        helper.runAfterDelay(160) {
            assertResidency(helper, emptyList(), expected)
            ready()
        }
    }

    private fun assertResidency(helper: GameTestHelper, present: List<ChunkPos>, absent: List<ChunkPos>) {
        val cache = helper.level.chunkSource
        val missing = present.filter { cache.getChunkNow(it.x, it.z) == null }
        val loaded = absent.filter { cache.getChunkNow(it.x, it.z) != null }
        helper.assertTrue(missing.isEmpty() && loaded.isEmpty(),
            "Unexpected chunk residency: held but unavailable=$missing; expected unloaded but loaded=$loaded")
    }
}
