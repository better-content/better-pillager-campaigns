package com.bettercontent.pillagercampaigns.core

import java.util.PriorityQueue
import kotlin.math.abs

/** A bounded existence search for one ground recruit. No entity, world, or chunk-loader access. */
class VirtualReachability(
    private val origin: Point,
    private val bounds: Bounds,
    private val isGoal: (Point) -> Boolean,
    private val heuristic: (Point) -> Int,
    private val maximumExpansions: Int,
    private val terrain: (Point) -> Cell,
    private val retainWitness: Boolean = false,
) {
    data class Point(val x: Int, val y: Int, val z: Int) {
        fun offset(dx: Int = 0, dy: Int = 0, dz: Int = 0) = Point(x + dx, y + dy, z + dz)
        // Diagonal moves are not used. A vertical step can accompany a horizontal move.
        fun lowerBoundTo(other: Point) = maxOf(abs(x - other.x) + abs(z - other.z), (abs(y - other.y) + 1) / 2)
    }

    data class Bounds(val x: IntRange, val y: IntRange, val z: IntRange) {
        operator fun contains(p: Point) = p.x in x && p.y in y && p.z in z
    }

    enum class Cell { CLEAR, SUPPORT, OBSTACLE, UNKNOWN, PENDING }
    enum class UnknownReason { TERRAIN_OR_BOUNDARY, SEARCH_BUDGET }
    sealed interface Result {
        data object Running : Result
        data class Reachable(val endpoint: Point) : Result
        data object Unreachable : Result
        data class Unknown(val reason: UnknownReason) : Result
    }

    private enum class Check { YES, NO, UNKNOWN, PENDING }
    private data class Node(val point: Point, val distance: Int, val estimate: Int, val order: Long, val parent: Node?)
    private val frontier = PriorityQueue(compareBy<Node> { it.distance.toLong() + it.estimate }
        .thenBy { it.estimate }.thenBy { it.order })
    private val best = HashMap<Point, Int>()
    private var nextOrder = 0L
    private var started = false
    private var incomplete = false
    private var result: Result = Result.Running
    var expanded: Int = 0
        private set
    var witness: List<Point> = emptyList()
        private set

    init { require(maximumExpansions > 0) }

    /** Yield on pending terrain or the per-slice budget. Never block for terrain I/O. */
    fun advance(sliceExpansions: Int): Result {
        require(sliceExpansions > 0)
        if (result != Result.Running) return result
        if (!started) {
            if (origin !in bounds) return finish(Result.Unknown(UnknownReason.TERRAIN_OR_BOUNDARY))
            when (stand(origin)) {
                Check.PENDING -> return result
                Check.UNKNOWN -> return finish(Result.Unknown(UnknownReason.TERRAIN_OR_BOUNDARY))
                Check.NO -> return finish(Result.Unreachable)
                Check.YES -> add(origin, 0)
            }
            started = true
        }
        var slice = 0
        while (slice < sliceExpansions) {
            val node = frontier.poll() ?: return finish(if (incomplete)
                Result.Unknown(UnknownReason.TERRAIN_OR_BOUNDARY) else Result.Unreachable)
            if (best[node.point] != node.distance) continue
            if (isGoal(node.point)) {
                if (retainWitness) witness = generateSequence(node) { it.parent }.map { it.point }.toList().asReversed()
                return finish(Result.Reachable(node.point))
            }
            if (expanded == maximumExpansions) return finish(Result.Unknown(UnknownReason.SEARCH_BUDGET))
            for ((dx, dz) in CARDINALS) for (dy in HEIGHT_STEPS) {
                val destination = node.point.offset(dx, dy, dz)
                if ((best[destination] ?: Int.MAX_VALUE) <= node.distance + 1) continue
                when (step(node.point, destination)) {
                    Check.YES -> if (destination in bounds) add(destination, node.distance + 1, node) else incomplete = true
                    Check.NO -> Unit
                    Check.UNKNOWN -> incomplete = true
                    Check.PENDING -> {
                        frontier += node
                        return result
                    }
                }
            }
            expanded++
            slice++
        }
        return result
    }

    /** Recheck just the witness, not the surrounding search space. Call against a fresh terrain slice. */
    fun validate(path: List<Point>): Result {
        if (path.firstOrNull() != origin || !isGoal(path.last())) return Result.Unreachable
        fun result(check: Check): Result? = when (check) {
            Check.YES -> null
            Check.NO -> Result.Unreachable
            Check.PENDING -> Result.Running
            Check.UNKNOWN -> Result.Unknown(UnknownReason.TERRAIN_OR_BOUNDARY)
        }
        result(stand(origin))?.let { return it }
        for (i in 1 until path.size) {
            val from = path[i - 1]
            val to = path[i]
            if (!allowsGeometry(from, to)) return Result.Unreachable
            result(step(from, to))?.let { return it }
        }
        return Result.Reachable(path.last())
    }

    fun allowsWalk(from: Point, to: Point): Boolean = allowsGeometry(from, to) && step(from, to) == Check.YES

    private fun allowsGeometry(from: Point, to: Point) =
        abs(from.x - to.x) + abs(from.z - to.z) == 1 && to.y - from.y in -2..1

    private fun add(point: Point, distance: Int, parent: Node? = null) {
        best[point] = distance
        frontier += Node(point, distance, heuristic(point), nextOrder++, if (retainWitness) parent else null)
    }

    private fun finish(value: Result): Result {
        result = value
        frontier.clear()
        best.clear()
        return result
    }

    private fun step(from: Point, to: Point): Check {
        val destination = stand(to)
        if (destination != Check.YES) return destination
        // The body needs room to rise before moving into a step, or to drop in the far column.
        if (to.y > from.y) return clear(from.offset(dy = 2))
        for (y in to.y + 2 .. from.y + 1) {
            val room = clear(to.copy(y = y))
            if (room != Check.YES) return room
        }
        return Check.YES
    }

    private fun stand(point: Point): Check {
        val feet = clear(point)
        if (feet != Check.YES) return feet
        val head = clear(point.offset(dy = 1))
        if (head != Check.YES) return head
        return when (terrain(point.offset(dy = -1))) {
            Cell.SUPPORT -> Check.YES
            Cell.PENDING -> Check.PENDING
            Cell.UNKNOWN -> Check.UNKNOWN
            else -> Check.NO
        }
    }

    private fun clear(point: Point) = when (terrain(point)) {
        Cell.CLEAR -> Check.YES
        Cell.PENDING -> Check.PENDING
        Cell.UNKNOWN -> Check.UNKNOWN
        else -> Check.NO
    }

    companion object {
        /** Choose an eligible point only on the connected route to its exact final target. */
        fun spawnPoint(witness: List<Point>, band: IntRange, eligible: (Point) -> Boolean): Point? {
            val target = witness.lastOrNull() ?: return null
            return witness.firstOrNull { point ->
                maxOf(abs(point.x - target.x), abs(point.z - target.z)) in band && eligible(point)
            }
        }

        private val CARDINALS = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)
        private val HEIGHT_STEPS = intArrayOf(0, 1, -1, -2)
    }
}
