package com.bettercontent.betterpillagercampaigns.system

import com.bettercontent.betterpillagercampaigns.core.DirectorCommand
import com.bettercontent.betterpillagercampaigns.core.DirectorFrame
import com.bettercontent.betterpillagercampaigns.core.EffectKind
import com.bettercontent.betterpillagercampaigns.core.EncounterKind
import com.bettercontent.betterpillagercampaigns.core.InvasionDirector
import com.bettercontent.betterpillagercampaigns.core.InvasionPhase
import com.bettercontent.betterpillagercampaigns.core.InvasionRules
import com.bettercontent.betterpillagercampaigns.core.InvasionRuntimeSpec
import com.bettercontent.betterpillagercampaigns.core.PlayerObservation
import com.bettercontent.betterpillagercampaigns.core.StrategicFrontier
import com.bettercontent.betterpillagercampaigns.core.StrategicRouteObservation
import com.bettercontent.betterpillagercampaigns.core.BlockPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthoredRosterDirectorRouteTest {
    @Test fun `authored roster assault preserves intent through target movement and resumes on a valid route`() {
        val rules = InvasionRules(
            scoutWindowMinTicks = 10_000,
            scoutWindowMaxTicks = 10_000,
            assaultWindowMinTicks = 1_000,
            assaultWindowMaxTicks = 1_000,
            assaultWarningSurfaceTicks = 0,
            approachMinimumBlocks = 2,
            approachMaximumBlocks = 4,
            approachTargetRadiusBlocks = 1,
            strategicOriginMinimumBlocks = 5,
            strategicOriginMaximumBlocks = 6,
            assaultStrategicMilliBlocksPerTick = 1_000,
        )
        val spec = InvasionRuntimeSpec.create(rules, InvasionRoster.authored())
        val director = InvasionDirector.create(0xD1A03L, spec)
        director.transition(DirectorFrame(0, commands = listOf(
            DirectorCommand.Force("player", EncounterKind.ASSAULT, expediteTravel = true),
        )))

        val initialTarget = BlockPoint("minecraft:overworld", 0, 64, 0)
        val initial = director.transition(DirectorFrame(0, players = listOf(player(initialTarget))))
        val firstInvasion = director.snapshot().tracks.getValue("player").invasion!!
        val invasionId = firstInvasion.invasionId
        val originalOrigin = firstInvasion.strategicOrigin!!
        val originOffset = originalOrigin.x - initialTarget.x to originalOrigin.z - initialTarget.z
        assertEquals(EncounterKind.ASSAULT, firstInvasion.kind)
        assertEquals(InvasionPhase.APPROACHING, firstInvasion.phase)
        assertTrue(initial.effects.none { it.kind == EffectKind.RETIRE || it.kind == EffectKind.MATERIALIZE })

        val movedTarget = BlockPoint("minecraft:overworld", 24, 64, 0)
        val moved = director.transition(DirectorFrame(0, players = listOf(player(movedTarget))))
        val relocated = director.snapshot().tracks.getValue("player").invasion!!
        assertEquals(invasionId, relocated.invasionId)
        assertEquals(EncounterKind.ASSAULT, relocated.kind)
        assertEquals(InvasionPhase.APPROACHING, relocated.phase)
        assertEquals(movedTarget, relocated.routeTarget)
        assertEquals(movedTarget.x + originOffset.first, relocated.strategicOrigin!!.x,
            "The strategic corridor should translate with the moving target")
        assertEquals(movedTarget.z + originOffset.second, relocated.strategicOrigin!!.z)
        assertTrue(relocated.strategicRoute.isEmpty(), "A route for the old target must not be reused")
        assertEquals("target_moved", relocated.lastRouteFailure)
        assertTrue(moved.events.none { it.type == "resolved" || it.type == "retire_requested" })
        assertTrue(moved.effects.none { it.kind == EffectKind.RETIRE })

        val route = (30 downTo 26).map { x -> BlockPoint("minecraft:overworld", x, 64, 0) }
        val resumed = director.transition(DirectorFrame(0, players = listOf(player(movedTarget)), strategicRoutes = listOf(
            StrategicRouteObservation("player", invasionId, movedTarget, 3, route, StrategicFrontier.OPEN),
        )))
        val ready = director.snapshot().tracks.getValue("player").invasion!!
        assertEquals(invasionId, ready.invasionId)
        assertEquals(InvasionPhase.READY_TO_MATERIALIZE, ready.phase)
        assertEquals(route.last(), ready.anchor)
        assertTrue(resumed.effects.any { it.kind == EffectKind.MATERIALIZE && it.invasionId == invasionId })
        assertTrue(resumed.effects.none { it.kind == EffectKind.RETIRE })
        assertTrue(director.snapshot().tracks.getValue("player").invasion != null,
            "Route readiness is not a fabricated successful assault outcome")
    }

    private fun player(position: BlockPoint) = PlayerObservation(
        playerId = "player",
        eligible = true,
        surfaceEligible = true,
        physicallyAvailable = true,
        position = position,
    )
}
