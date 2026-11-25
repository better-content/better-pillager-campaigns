package com.bettercontent.betterpillagercampaigns.system

import com.bettercontent.betterpillagercampaigns.PillagerCampaignsMod
import com.bettercontent.betterpillagercampaigns.core.BlockPoint
import com.bettercontent.betterpillagercampaigns.core.StrategicRouteObservation
import com.bettercontent.pillagercampaigns.core.VirtualReachability
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Bounds
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Cell
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Point
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Result
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import kotlin.math.abs

/** One transient, bounded connectivity proof per campaign; never force-loads terrain. */
internal object VirtualCampaignRouting {
    private const val MAX_JOBS = 3
    private const val EXPANSIONS_PER_TICK = 128
    private const val TOTAL_EXPANSIONS = 16_384
    private const val MAX_SAVED_CHUNKS = 192
    private const val MAX_WITNESS = 1_536
    private const val HALF_CORRIDOR_EXTRA = 48
    private const val MAX_TICKS = 1_200L
    private val jobs = linkedMapOf<String, Job>()
    private val proofs = mutableMapOf<String, Proof>()

    data class Request(val playerId: String, val id: String, val origin: BlockPoint, val target: BlockPoint, val pursuedId: String)
    data class Band(val min: Int, val max: Int)
    enum class Admission { READY, WAIT, REJECT }

    fun clear() { jobs.clear(); proofs.clear() }
    fun retain(ids: Set<String>) { jobs.keys.retainAll(ids); proofs.keys.retainAll(ids) }
    fun discard(id: String) { jobs.remove(id); proofs.remove(id) }
    fun targetId(id: String): String? = proofs[id]?.request?.pursuedId

    fun onResidencyChange(pos: ChunkPos) {
        jobs.values.forEach { it.view.invalidate(pos) }
        proofs.values.forEach { it.view.invalidate(pos) }
    }

    fun busy(request: Request): Boolean {
        jobs[request.id]?.let { if (it.request != request) jobs.remove(request.id) }
        return jobs.containsKey(request.id)
    }
    fun hasCapacity() = jobs.size < MAX_JOBS

    fun enqueue(level: ServerLevel, request: Request, goal: BlockPoint, band: Band,
                excluded: List<BlockPoint>, packetSize: Int) {
        check(hasCapacity() && !busy(request))
        proofs.remove(request.id)
        jobs[request.id] = Job(level, request, goal, band, excluded, packetSize)
        PillagerCampaignsMod.LOGGER.debug("VIRTUAL_APPROACH queued id={} origin={} target={} band={}",
            request.id, request.origin, goal, band)
    }

    /** Round-robin one search slice per tick, regardless of waiting campaigns. */
    fun tick() {
        val first = jobs.entries.firstOrNull { it.value.done == null } ?: return
        val key = first.key
        val job = jobs.remove(key) ?: return
        job.advance()
        jobs[key] = job
    }

    fun poll(request: Request): StrategicRouteObservation? {
        if (!busy(request)) return null
        val job = jobs[request.id] ?: return null
        val outcome = job.done ?: return null
        jobs.remove(request.id)
        outcome.proof?.let { proofs[request.id] = it }
        val anchor = outcome.proof?.anchor
        PillagerCampaignsMod.LOGGER.debug(
            "VIRTUAL_APPROACH result id={} result={} anchor={} expansions={} savedReads={} liveReads={} witness={} searchMicros={} maxSliceMicros={}",
            request.id, outcome.reason ?: "reachable", anchor, job.expanded, job.view.savedReads, job.view.liveReads,
            outcome.proof?.path?.size, job.searchMicros, job.maxSliceMicros)
        return StrategicRouteObservation(request.playerId, request.id, request.target, job.level.gameTime,
            listOfNotNull(anchor), outcome.reason)
    }

    fun admission(level: ServerLevel, id: String, anchor: BlockPoint?, liveTarget: BlockPoint): Admission {
        val proof = proofs[id] ?: return Admission.REJECT
        if (proof.level !== level || anchor != proof.anchor || liveTarget.dimension != proof.goal.dimension ||
            horizontalDistance(liveTarget.toPoint(), proof.goal.toPoint()) > 16 || abs(liveTarget.y - proof.goal.y) > 8) {
            discard(id)
            return Admission.REJECT
        }
        val start = System.nanoTime()
        // Re-read live cells in one bounded commit pass on the server thread. Saved palettes
        // come from a separate view, invalidated if their chunks are loaded/unloaded later.
        proof.view.beginSlice()
        proof.view.prefetch(proof.path)
        val result = proof.validator.validate(proof.path)
        val gate = when (result) {
            is Result.Reachable -> Admission.READY
            Result.Running -> if (proof.verificationStarted == Long.MAX_VALUE || level.gameTime - proof.verificationStarted < MAX_TICKS)
                Admission.WAIT else Admission.REJECT
            else -> Admission.REJECT
        }
        if (gate != Admission.WAIT) {
            PillagerCampaignsMod.LOGGER.debug("VIRTUAL_COMMIT id={} result={} action={} witness={} micros={}",
                id, result, gate, proof.path.size, (System.nanoTime() - start) / 1_000)
            if (gate == Admission.REJECT) discard(id) else proof.verificationStarted = Long.MAX_VALUE
        } else if (proof.verificationStarted == Long.MAX_VALUE) {
            proof.verificationStarted = level.gameTime
        }
        return gate
    }

    private class Proof(val level: ServerLevel, val request: Request, val goal: BlockPoint,
                        val anchor: BlockPoint, val path: List<Point>, bounds: Bounds) {
        val view = VirtualCampaignTerrain(level, MAX_SAVED_CHUNKS)
        val validator = VirtualReachability(path.first(), bounds, { it == path.last() }, { 0 }, 1, view::cell)
        var verificationStarted = Long.MAX_VALUE
        init { view.prefetch(path) }
    }
    private data class Outcome(val proof: Proof? = null, val reason: String? = null)

    private class Job(
        val level: ServerLevel, val request: Request, private val goal: BlockPoint, private val band: Band,
        private val excluded: List<BlockPoint>, private val packetSize: Int,
    ) {
        val view = VirtualCampaignTerrain(level, MAX_SAVED_CHUNKS)
        private val target = goal.toPoint()
        private val startTick = level.gameTime
        private var solver: VirtualReachability? = null
        private lateinit var bounds: Bounds
        val expanded: Int get() = solver?.expanded ?: 0
        var done: Outcome? = null
            private set
        var searchMicros = 0L
            private set
        var maxSliceMicros = 0L
            private set

        fun advance() {
            val start = System.nanoTime()
            try { advanceSlice() } finally {
                val elapsed = (System.nanoTime() - start) / 1_000
                searchMicros += elapsed
                maxSliceMicros = maxOf(maxSliceMicros, elapsed)
            }
        }

        private fun advanceSlice() {
            if (level.gameTime - startTick >= MAX_TICKS) {
                done = Outcome(reason = "virtual_route_deadline")
                return
            }
            view.beginSlice()
            val search = solver ?: prepare() ?: return
            done = when (val result = search.advance(EXPANSIONS_PER_TICK)) {
                Result.Running -> null
                is Result.Reachable -> finish(search.witness)
                Result.Unreachable -> Outcome(reason = "no_virtual_route")
                is Result.Unknown -> Outcome(reason = when (result.reason) {
                    VirtualReachability.UnknownReason.SEARCH_BUDGET -> "virtual_search_budget"
                    VirtualReachability.UnknownReason.TERRAIN_OR_BOUNDARY -> "virtual_terrain_unknown"
                })
            }
        }

        private fun finish(path: List<Point>): Outcome {
            if (path.size > MAX_WITNESS) return Outcome(reason = "virtual_witness_budget")
            val targetBlock = target.blockPos()
            val anchor = VirtualReachability.spawnPoint(path, band.min..band.max) { step ->
                excluded.none { it.x == step.x && it.z == step.z } &&
                    LoadedSpawnChecks.loadedRectangle(level, step.blockPos(), targetBlock) &&
                    LoadedSpawnChecks.memberPositions(level, step.blockPos(), packetSize).let { packet ->
                        packet.size == packetSize && packet.all(level::isPositionEntityTicking)
                    }
            } ?: return Outcome(reason = "virtual_connected_no_spawn_on_witness")
            return Outcome(Proof(level, request, goal, anchor.toBlockPoint(goal.dimension), path, bounds))
        }

        private fun prepare(): VirtualReachability? {
            if (request.origin.dimension != goal.dimension) {
                done = Outcome(reason = "virtual_origin_wrong_dimension")
                return null
            }
            val start = resolveOrigin(request.origin.toPoint()) ?: return null
            bounds = Bounds(minOf(start.x, target.x) - HALF_CORRIDOR_EXTRA .. maxOf(start.x, target.x) + HALF_CORRIDOR_EXTRA,
                level.minBuildHeight .. level.maxBuildHeight - 1,
                minOf(start.z, target.z) - HALF_CORRIDOR_EXTRA .. maxOf(start.z, target.z) + HALF_CORRIDOR_EXTRA)
            return VirtualReachability(start, bounds, { it == target }, { 2 * it.lowerBoundTo(target) }, TOTAL_EXPANSIONS,
                view::cell, retainWitness = true).also { solver = it }
        }

        private fun resolveOrigin(hint: Point): Point? {
            // Stored Y is a height hint; never shift the actual departure X/Z.
            for (dy in ORIGIN_HEIGHT_HINTS) {
                val feet = hint.offset(dy = dy)
                val cells = listOf(view.cell(feet), view.cell(feet.offset(dy = 1)), view.cell(feet.offset(dy = -1)))
                if (Cell.PENDING in cells) return null
                if (Cell.UNKNOWN in cells) {
                    done = Outcome(reason = "virtual_origin_unknown")
                    return null
                }
                if (cells[0] == Cell.CLEAR && cells[1] == Cell.CLEAR && cells[2] == Cell.SUPPORT) return feet
            }
            done = Outcome(reason = "virtual_origin_unstandable")
            return null
        }
    }

    private val ORIGIN_HEIGHT_HINTS = listOf(0) + (1..24).flatMap { listOf(-it, it) }
    private fun horizontalDistance(a: Point, b: Point) = maxOf(abs(a.x - b.x), abs(a.z - b.z))
    private fun BlockPoint.toPoint() = Point(x, y, z)
    private fun Point.blockPos() = BlockPos(x, y, z)
    private fun Point.toBlockPoint(dimension: String) = BlockPoint(dimension, x, y, z)
}
