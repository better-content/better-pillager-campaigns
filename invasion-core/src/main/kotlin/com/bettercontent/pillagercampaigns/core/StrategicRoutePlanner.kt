package com.bettercontent.betterpillagercampaigns.core

import java.util.PriorityQueue
import kotlin.math.abs

/** Deterministic routing over recorded terrain only. Missing cells are never inferred or generated. */
object StrategicRoutePlanner {
    data class Result(val route: List<SurfaceCell>, val frontier: StrategicFrontier)

    /** The planner's geometric bounds, which may describe either strategic or local routing. */
    data class Rules(
        val approachMinimumBlocks: Int,
        val approachMaximumBlocks: Int,
        val approachTargetRadiusBlocks: Int,
        val originMinimumBlocks: Int,
        val originMaximumBlocks: Int,
        val maximumSearchExpansions: Int,
    ) {
        init {
            require(approachMinimumBlocks in 1..approachMaximumBlocks)
            require(approachTargetRadiusBlocks > 0)
            require(originMinimumBlocks in 0..originMaximumBlocks)
            require(maximumSearchExpansions > 0)
        }
    }

    fun Rules(rules: InvasionRules): Rules = Rules(
        approachMinimumBlocks = rules.approachMinimumBlocks,
        approachMaximumBlocks = rules.approachMaximumBlocks,
        approachTargetRadiusBlocks = rules.approachTargetRadiusBlocks,
        originMinimumBlocks = rules.strategicOriginMinimumBlocks,
        originMaximumBlocks = rules.strategicOriginMaximumBlocks,
        maximumSearchExpansions = rules.strategicMaximumSearchExpansions,
    )

    fun plan(
        cells: Collection<SurfaceCell>, target: BlockPoint, rules: InvasionRules, seed: Long,
        start: BlockPoint? = null, excludedApproaches: Collection<BlockPoint> = emptyList(),
    ): Result? = plan(cells, target, Rules(rules), seed, start, excludedApproaches)

    fun plan(
        cells: Collection<SurfaceCell>, target: BlockPoint, rules: Rules, seed: Long,
        start: BlockPoint? = null, excludedApproaches: Collection<BlockPoint> = emptyList(),
    ): Result? {
        val known = cells.associateBy { key(it.x, it.z) }
        val passable = known.values.filter(SurfaceCell::passable).associateBy { key(it.x, it.z) }
        val retrySeparation = minOf(16, rules.approachMinimumBlocks)
        fun excluded(cell: SurfaceCell) = excludedApproaches.any {
            maxOf(abs(cell.x - it.x), abs(cell.z - it.z)) <= retrySeparation
        }
        val origins = if (start != null) {
            listOfNotNull(passable[key(start.x, start.z)])
        } else {
            data class ScoredOrigin(val cell: SurfaceCell, val missingLineSamples: Int, val stableRank: Long)
            passable.values.asSequence().filter { !excluded(it) &&
                distance(it, target) in rules.originMinimumBlocks..rules.originMaximumBlocks
            }.map { ScoredOrigin(it, missingLineSamples(it, target, known), stableRank(it, seed)) }
                .sortedWith(compareBy<ScoredOrigin> { it.missingLineSamples }
                    .thenBy { it.stableRank }.thenBy { it.cell.x }.thenBy { it.cell.z })
                .map(ScoredOrigin::cell).toList()
        }
        var remainingExpansions = rules.maximumSearchExpansions
        for (origin in origins) {
            if (remainingExpansions <= 0) break
            data class Node(val cell: SurfaceCell, val steps: Int)
            val queue = PriorityQueue(compareBy<Node> { it.steps + manhattan(it.cell, target) }
                .thenBy { manhattan(it.cell, target) }.thenBy { stableRank(it.cell, seed) })
            val previous = hashMapOf<Long, Long?>()
            val originKey = key(origin.x, origin.z)
            queue += Node(origin, 0)
            previous[originKey] = null
            var best = origin
            while (queue.isNotEmpty() && remainingExpansions-- > 0) {
                val node = queue.remove()
                val cell = node.cell
                if (distance(cell, target) < distance(best, target)) best = cell
                if (distance(cell, target) <= rules.approachTargetRadiusBlocks) {
                    val full = reconstruct(cell, previous, passable)
                    val materializationIndex = full.indexOfFirst { distance(it, target) <= rules.approachMaximumBlocks }
                    if (materializationIndex >= 0) return Result(full.take(materializationIndex + 1), StrategicFrontier.OPEN)
                }
                CARDINALS.forEach { (dx, dz) ->
                    val candidateKey = key(cell.x + dx, cell.z + dz)
                    val candidate = known[candidateKey]
                    if (candidate != null && candidate.passable && !excluded(candidate) &&
                        candidate.bodyY - cell.bodyY in -2..1 &&
                        !previous.containsKey(candidateKey)) {
                        previous[candidateKey] = key(cell.x, cell.z)
                        queue += Node(candidate, node.steps + 1)
                    }
                }
            }
            val closer = CARDINALS.map { (dx, dz) -> known[key(best.x + dx, best.z + dz)] }
                .filter { it == null || distance(it, target) < distance(best, target) }
            val frontier = when {
                closer.any { it == null } -> StrategicFrontier.UNKNOWN
                closer.any { it != null && (!it.passable || it.bodyY - best.bodyY !in -2..1) } -> StrategicFrontier.DEFENSE
                else -> StrategicFrontier.UNKNOWN
            }
            if (frontier == StrategicFrontier.DEFENSE && distance(best, target) <= rules.approachMaximumBlocks) {
                return Result(reconstruct(best, previous, passable), frontier)
            }
        }
        return null
    }

    private fun reconstruct(
        end: SurfaceCell,
        previous: Map<Long, Long?>,
        passable: Map<Long, SurfaceCell>,
    ): List<SurfaceCell> {
        val result = mutableListOf<SurfaceCell>()
        var cursor: Long? = key(end.x, end.z)
        while (cursor != null) {
            result += passable.getValue(cursor)
            cursor = previous[cursor]
        }
        return result.asReversed()
    }

    private fun distance(cell: SurfaceCell, target: BlockPoint) = maxOf(abs(cell.x - target.x), abs(cell.z - target.z))
    private fun manhattan(cell: SurfaceCell, target: BlockPoint) = abs(cell.x - target.x) + abs(cell.z - target.z)
    private fun stableRank(cell: SurfaceCell, seed: Long) =
        (seed xor (cell.x.toLong() shl 32) xor cell.z.toLong()) * -7046029254386353131L

    /**
     * Prefer distant origins with a recorded-terrain chain toward the target. This is only an
     * ordering hint: A* still proves every traversed block and enforces the global expansion cap.
     * Sampling at chunk scale prevents an unrelated explored island from consuming the whole
     * search budget before a connected approach corridor is tried.
     */
    private fun missingLineSamples(origin: SurfaceCell, target: BlockPoint, known: Map<Long, SurfaceCell>): Int {
        val dx = target.x - origin.x
        val dz = target.z - origin.z
        val length = maxOf(abs(dx), abs(dz)).coerceAtLeast(1)
        var missing = 0
        var step = 0
        while (step <= length) {
            val x = origin.x + dx * step / length
            val z = origin.z + dz * step / length
            if (!known.containsKey(key(x, z))) missing++
            step += 16
        }
        if (length % 16 != 0 && !known.containsKey(key(target.x, target.z))) missing++
        return missing
    }

    private fun key(x: Int, z: Int) = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
    private val CARDINALS = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)
}
