package com.bettercontent.pillagercampaigns.core

import com.bettercontent.pillagercampaigns.core.VirtualReachability.Bounds
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Cell
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Point
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Result
import com.bettercontent.pillagercampaigns.core.VirtualReachability.UnknownReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VirtualReachabilityTest {
    private val origin = Point(0, 1, 0)
    private val goal = Point(5, 1, 0)
    private val bounds = Bounds(-12..800, -8..12, -12..12)

    @Test fun `open and sealed covered tunnel use the same three dimensional graph`() {
        assertEquals(Result.Reachable(goal), settle(query { chamber(it, open = true) }))
        assertEquals(Result.Unreachable, settle(query { chamber(it, open = false) }))
        val above = goal.copy(y = 4)
        assertEquals(Result.Unknown(UnknownReason.SEARCH_BUDGET), settle(query(destination = above, max = 20) {
            chamber(it, open = true)
        }))
    }

    @Test fun `an alternate opening can reach the same goal without changing the origin`() {
        assertEquals(Result.Reachable(goal), settle(query { chamber(it, open = false, secondExit = true) }))
    }

    @Test fun `missing terrain differs from a sealed passage and pending data can resume`() {
        val gate = Point(2, 1, 0)
        var gateData = Cell.PENDING
        val search = query { if (it == gate) gateData else chamber(it, open = true) }
        repeat(3) { assertEquals(Result.Running, search.advance(64)) }
        assertTrue(search.expanded < 16)
        gateData = Cell.UNKNOWN
        assertEquals(Result.Unknown(UnknownReason.TERRAIN_OR_BOUNDARY), settle(search))
        gateData = Cell.CLEAR
        assertEquals(Result.Reachable(goal), settle(query { if (it == gate) gateData else chamber(it, open = true) }))
        assertEquals(Result.Unknown(UnknownReason.TERRAIN_OR_BOUNDARY), settle(query {
            if (it == origin) Cell.UNKNOWN else flat(it)
        }))
    }

    @Test fun `budget and boundary truncation cannot prove a blockage`() {
        assertEquals(Result.Unknown(UnknownReason.SEARCH_BUDGET), settle(query(max = 2, terrain = ::flat)))
        assertEquals(Result.Unknown(UnknownReason.TERRAIN_OR_BOUNDARY), settle(query(
            area = Bounds(0..1, 0..4, -1..1), terrain = ::flat,
        )))
        assertEquals(Result.Unknown(UnknownReason.TERRAIN_OR_BOUNDARY), settle(query(
            area = Bounds(1..5, 0..4, -1..1), terrain = ::flat,
        )))
        assertEquals(Result.Unreachable, settle(query { if (it == origin) Cell.OBSTACLE else flat(it) }))
        assertFailsWith<IllegalArgumentException> { query(max = 0, terrain = ::flat) }
        assertFailsWith<IllegalArgumentException> { query(terrain = ::flat).advance(0) }
    }

    @Test fun `step up and drop require overhead clearance and a supported landing`() {
        val destination = Point(6, 1, 0)
        fun corridor(it: Point, lowCeiling: Boolean): Cell = when {
            lowCeiling && it == Point(1, 3, 0) -> Cell.OBSTACLE
            it.x !in 0..6 || it.z != 0 || it.y > 4 -> Cell.OBSTACLE
            it.x in 2..4 && it.y == 1 -> Cell.SUPPORT
            else -> flat(it)
        }
        assertEquals(Result.Reachable(destination), settle(query(destination = destination) { corridor(it, false) }))
        assertEquals(Result.Unreachable, settle(query(destination = destination) { corridor(it, true) }))
    }

    @Test fun `a flat 700 block journey is solved without scanning the whole domain`() {
        val destination = Point(700, 1, 0)
        val search = query(destination = destination, terrain = ::flat)
        assertEquals(Result.Running, search.advance(64))
        assertEquals(64, search.expanded)
        assertEquals(Result.Reachable(destination), settle(search))
        assertEquals(700, search.expanded)
        assertEquals(Result.Reachable(destination), search.advance(64))
    }

    @Test fun `full proof records only one continuous route and rejects a gate closed after search`() {
        val gate = Point(2, 1, 0)
        var gateData = Cell.CLEAR
        val search = VirtualReachability(origin, bounds, { it == goal }, { it.lowerBoundTo(goal) }, 2_000,
            { if (it == gate) gateData else chamber(it, open = true) }, retainWitness = true)
        assertEquals(Result.Reachable(goal), settle(search))
        val route = search.witness
        assertEquals(origin, route.first())
        assertEquals(goal, route.last())
        assertTrue(gate in route)
        assertEquals(Result.Reachable(goal), search.validate(route))
        gateData = Cell.OBSTACLE
        assertEquals(Result.Unreachable, search.validate(route))
        gateData = Cell.UNKNOWN
        assertEquals(Result.Unknown(UnknownReason.TERRAIN_OR_BOUNDARY), search.validate(route))
        gateData = Cell.PENDING
        assertEquals(Result.Running, search.validate(route))
        assertEquals(Result.Unreachable, search.validate(listOf(origin, goal)))
    }

    private fun query(
        destination: Point = goal,
        max: Int = 2_000,
        area: Bounds = bounds,
        terrain: (Point) -> Cell,
    ) = VirtualReachability(origin, area, { it == destination }, { it.lowerBoundTo(destination) }, max, terrain)

    private fun settle(query: VirtualReachability): Result {
        repeat(100) { query.advance(64).let { if (it != Result.Running) return it } }
        error("Search did not settle after 100 slices")
    }

    private fun flat(p: Point) = if (p.y <= 0) Cell.SUPPORT else Cell.CLEAR

    private fun chamber(p: Point, open: Boolean, secondExit: Boolean = false): Cell {
        if (p.x in -2..2 && p.z in -2..2 && p.y == 3) return Cell.SUPPORT
        if (p.y in 1..2 && ((p.x in -2..2 && p.z in setOf(-2, 2)) ||
                (p.z in -2..2 && p.x in setOf(-2, 2)))) {
            if ((open && p.x == 2 && p.z == 0) || (secondExit && p.x == 0 && p.z == 2)) return Cell.CLEAR
            return Cell.SUPPORT
        }
        return flat(p)
    }
}
