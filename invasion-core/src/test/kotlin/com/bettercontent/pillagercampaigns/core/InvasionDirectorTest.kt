package com.bettercontent.betterpillagercampaigns.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.ceil
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvasionDirectorTest {
    private val rules = InvasionRules(
        scoutWindowMinTicks = 100, scoutWindowMaxTicks = 100,
        assaultWindowMinTicks = 1_000, assaultWindowMaxTicks = 1_000,
        assaultWarningSurfaceTicks = 20, deathGraceTicks = 250, timeTierTicks = 1_000,
        waveProgressTimeoutTicks = 50, scoutActiveTicks = 80, assaultActiveTicks = 500,
        activeIdleTicks = 300, targetUnavailableTicks = 40,
        approachMinimumBlocks = 2, approachMaximumBlocks = 4, approachTargetRadiusBlocks = 1,
        maximumSearchExpansions = 128,
    )
    private val roster = listOf(
        RecruitSpec("minecraft:pillager", 2, 0, RecruitRole.RANGED, weight = 4),
        RecruitSpec("takesapillage:archer", 2, 0, RecruitRole.RANGED, optional = true),
        RecruitSpec("takesapillage:skirmisher", 2, 0, RecruitRole.LINE, optional = true),
        RecruitSpec("minecraft:vindicator", 3, 1, RecruitRole.FRONTLINE, weight = 3),
        RecruitSpec("takesapillage:legioner", 3, 1, RecruitRole.FRONTLINE, optional = true),
        RecruitSpec("minecraft:witch", 4, 2, RecruitRole.SUPPORT, maximumPerSquad = 1),
        RecruitSpec("savage_and_ravage:griefer", 4, 2, RecruitRole.LINE, maximumPerSquad = 2, optional = true),
        RecruitSpec("savage_and_ravage:trickster", 4, 2, RecruitRole.SUPPORT, maximumPerSquad = 1, optional = true),
        RecruitSpec("savage_and_ravage:executioner", 5, 3, RecruitRole.ELITE, maximumPerSquad = 1, optional = true),
        RecruitSpec("savage_and_ravage:iceologer", 5, 3, RecruitRole.ELITE, maximumPerSquad = 1, optional = true),
        RecruitSpec("minecraft:evoker", 6, 4, RecruitRole.ELITE, maximumPerSquad = 1),
        RecruitSpec("minecraft:ravager", 8, 5, RecruitRole.ELITE, maximumPerSquad = 1),
    )
    private val spec = InvasionRuntimeSpec.create(rules, roster)

    @Test fun `fixed 1024 seed corpus is deterministic and clocks remain in authored bands`() {
        val first = (0L..1_023L).map(::timeline)
        val second = (0L..1_023L).map(::timeline)
        assertEquals(first, second)
        first.forEach { timeline ->
            assertTrue(timeline.scoutDue in 7_200L..14_400L)
            assertTrue(timeline.assaultDue in 72_000L..144_000L)
            assertTrue(timeline.scouts >= 4)
            assertTrue(timeline.assaults >= 1)
        }
    }

    @Test fun `eligible clocks pause offline underground delivery waits and transport label is absent`() {
        val engine = InvasionDirector.create(1, fixedSpec())
        engine.transition(playerFrame(50, eligible = false, surface = false))
        assertEquals(0, engine.snapshot().tracks["player"]!!.eligibleTicks)
        engine.transition(playerFrame(100, surface = false))
        val track = engine.snapshot().tracks["player"]!!
        assertEquals(100, track.eligibleTicks)
        assertNull(track.invasion)
        engine.transition(playerFrame(0, surface = true))
        assertEquals(EncounterKind.SCOUT, track(engine).invasion?.kind)
        val descriptor = PlayerObservation.serializer().descriptor
        assertFalse((0 until descriptor.elementsCount).map(descriptor::getElementName).contains("transport"))
    }

    @Test fun `scouts are silent cohesive bounded and withdraw exactly`() {
        val engine = InvasionDirector.create(2, fixedSpec())
        val started = engine.transition(playerFrame(100))
        assertTrue(started.effects.none { it.kind == EffectKind.WARN })
        val invasion = track(engine).invasion!!
        assertEquals(EncounterKind.SCOUT, invasion.kind)
        assertEquals(3, invasion.members.size)
        assertTrue(invasion.members.all { member ->
            roster.first { it.entityId == member.recruitId }.role in setOf(RecruitRole.LINE, RecruitRole.RANGED)
        })
        materializeCurrent(engine)
        engine.transition(playerFrame(79))
        assertEquals(InvasionPhase.ACTIVE, track(engine).invasion!!.phase)
        val retiring = engine.transition(playerFrame(1))
        assertTrue(retiring.effects.any { it.kind == EffectKind.RETIRE })
    }

    @Test fun `assault warning is tied to arrival and uses three progress-gated waves`() {
        val engine = InvasionDirector.create(3, fixedSpec())
        val forced = engine.transition(DirectorFrame(0, commands = listOf(DirectorCommand.Force("player", EncounterKind.ASSAULT))))
        assertTrue(forced.events.any { it.type == "forced" })
        val dispatched = engine.transition(playerFrame(0))
        assertTrue(dispatched.effects.none { it.kind == EffectKind.WARN },
            "An assault with no known route must not warn and then stall indefinitely")
        val arrived = engine.transition(playerFrame(19, surfaces = listOf(grid())))
        assertTrue(arrived.effects.any { it.kind == EffectKind.WARN })
        assertTrue(arrived.effects.any { it.kind == EffectKind.MATERIALIZE && it.announceWave })
        acknowledge(engine, arrived)
        drainWave(engine)
        var invasion = track(engine).invasion!!
        assertEquals(0, invasion.currentWave)
        val half = ceil(invasion.waves[0].members.size / 2.0).toInt()
        engine.transition(playerFrame(0, defeats = invasion.waves[0].members.take(half).map {
            MemberDefeatObservation(invasion.invasionId, it.memberId)
        }))
        engine.transition(playerFrame(1))
        invasion = track(engine).invasion!!
        assertEquals(1, invasion.currentWave)
        engine.transition(playerFrame(0, surfaces = listOf(grid())))
        drainWave(engine)
        engine.transition(playerFrame(50))
        assertEquals(2, track(engine).invasion!!.currentWave)
    }

    @Test fun `grouped assault warns every participant at the full lead and cannot arrive early`() {
        val warningRules = rules.copy(
            scoutWindowMinTicks = 10_000, scoutWindowMaxTicks = 10_000,
            assaultWindowMinTicks = 1_000, assaultWindowMaxTicks = 1_000,
            assaultWarningSurfaceTicks = 20,
        )
        val engine = InvasionDirector.create(31, InvasionRuntimeSpec.create(warningRules, roster))
        val players = listOf(observation("a", 0), observation("b", 20))
        engine.transition(DirectorFrame(0, players = players))
        engine.transition(DirectorFrame(979, players = players,
            surfaces = listOf(grid("a", 0))))
        val beforeWarning = engine.snapshot().pendingEffects.values
        assertTrue(beforeWarning.none { it.kind == EffectKind.WARN || it.kind == EffectKind.MATERIALIZE })

        val warned = engine.transition(DirectorFrame(1, players = players))
        val warning = warned.effects.single { it.kind == EffectKind.WARN }
        assertEquals(listOf("a", "b"), warning.participantPlayerIds)
        assertTrue(warned.effects.none { it.kind == EffectKind.MATERIALIZE },
            "The warning must precede arrival by the configured 20 eligible ticks")
        acknowledge(engine, warned)

        val stillApproaching = engine.transition(DirectorFrame(19, players = players))
        assertTrue(stillApproaching.effects.none { it.kind == EffectKind.MATERIALIZE })
        val arrival = engine.transition(DirectorFrame(1, players = players))
        assertTrue(arrival.effects.any { it.kind == EffectKind.MATERIALIZE })
    }

    @Test fun `group scaling shares one encounter and exact provisional sizes`() {
        assertEquals(listOf(3, 3, 4, 4, 5, 6), (0..5).map {
            EncounterPolicy.memberCount(EncounterKind.SCOUT, it, 1, InvasionRules())
        })
        assertEquals(listOf(16, 17, 19, 20, 22, 24), (0..5).map {
            EncounterPolicy.memberCount(EncounterKind.ASSAULT, it, 1, InvasionRules())
        })
        assertEquals(36, EncounterPolicy.memberCount(EncounterKind.ASSAULT, 5, 2, InvasionRules()))
        assertEquals(48, EncounterPolicy.memberCount(EncounterKind.ASSAULT, 5, 4, InvasionRules()))
        val engine = InvasionDirector.create(4, fixedSpec())
        engine.transition(DirectorFrame(0, commands = listOf(
            DirectorCommand.Force("a", EncounterKind.ASSAULT), DirectorCommand.Force("b", EncounterKind.ASSAULT),
        )))
        engine.transition(DirectorFrame(0, players = listOf(observation("a", 0), observation("b", 20))))
        val primaries = engine.snapshot().tracks.values.mapNotNull(PlayerPressureTrack::invasion)
        assertEquals(1, primaries.size)
        assertEquals(listOf("a", "b"), primaries.single().participantPlayerIds)
        assertEquals(24, primaries.single().waves.first().members.size)
    }

    @Test fun `wave rosters obey roles budgets optional eligibility and elite rules`() {
        for (seed in 0L..1_023L) {
            val id = "assault:test:$seed"
            for (intensity in 0..5) for (wave in 0..2) {
                val size = EncounterPolicy.memberCount(EncounterKind.ASSAULT, intensity, 1, rules)
                val plan = RosterPlanner.planWave(roster, EncounterKind.ASSAULT, intensity, wave, size, id, seed)
                assertEquals(EncounterPolicy.waveBudget(size, intensity, wave), plan.budget)
                assertTrue(plan.members.sumOf(MemberPlan::cost) <= plan.budget)
                val chosen = plan.members.map { member -> roster.first { it.entityId == member.recruitId } }
                assertTrue(chosen.count { it.role == RecruitRole.SUPPORT } <= 1)
                assertTrue(chosen.count { it.role == RecruitRole.ELITE } <= 1)
                if (wave == 0) assertTrue(chosen.all { it.role in setOf(RecruitRole.LINE, RecruitRole.RANGED) })
                if (wave == 2 && intensity >= 3) assertTrue(chosen.any { it.role == RecruitRole.ELITE })
                if (intensity < 5) assertTrue(chosen.none { it.entityId == "minecraft:ravager" })
            }
        }
        val fallback = roster.filterNot(RecruitSpec::optional)
        val plan = RosterPlanner.planWave(fallback, EncounterKind.ASSAULT, 5, 2, 12, "fallback", 9)
        assertEquals(12, plan.members.size)
        roster.filter(RecruitSpec::optional).forEach { missing ->
            assertEquals(8, RosterPlanner.planWave(roster - missing, EncounterKind.ASSAULT, 0, 0, 8,
                "missing:${missing.entityId}", 1).members.size)
        }
    }

    @Test fun `packet caps low-health spacing global allowance and round-robin are hard`() {
        val engine = InvasionDirector.create(5, fixedSpec())
        engine.transition(DirectorFrame(0, commands = listOf(
            DirectorCommand.Force("a", EncounterKind.ASSAULT), DirectorCommand.Force("b", EncounterKind.ASSAULT),
        )))
        val far = listOf(observation("a", 0, low = true), observation("b", 100))
        var transition = engine.transition(DirectorFrame(0, players = far))
        acknowledge(engine, transition)
        transition = engine.transition(DirectorFrame(20, players = far,
            surfaces = listOf(grid("a", 0), grid("b", 100)), liveCampaignMobs = 90, secondSpawnCount = 20))
        assertTrue(transition.effects.filter { it.kind == EffectKind.MATERIALIZE }.sumOf { it.members.size } <= 4)
        acknowledge(engine, transition)
        val served = mutableSetOf<String>()
        repeat(4) {
            transition = engine.transition(DirectorFrame(60, players = far, surfaces = listOf(grid("a", 0), grid("b", 100))))
            transition.effects.filter { it.kind == EffectKind.MATERIALIZE }.forEach {
                assertTrue(it.members.size <= 6)
                assertTrue(it.members.count { member -> roster.first { recruit -> recruit.entityId == member.recruitId }.role == RecruitRole.ELITE } <= 1)
                served += it.playerId
            }
            acknowledge(engine, transition)
        }
        assertEquals(setOf("a", "b"), served)
        assertTrue(track(engine, "a").invasion!!.nextPacketTick >= engine.snapshot().tick)
    }

    @Test fun `only the first packet at an anchor requests native path validation`() {
        val engine = InvasionDirector.create(51, fixedSpec())
        engine.transition(DirectorFrame(0, commands = listOf(
            DirectorCommand.Force("player", EncounterKind.ASSAULT, intensity = 5),
        )))
        engine.transition(playerFrame(0))
        val first = engine.transition(playerFrame(20, surfaces = listOf(grid())))
        val firstPacket = first.effects.single { it.kind == EffectKind.MATERIALIZE }
        assertTrue(firstPacket.validateApproach)
        acknowledge(engine, first)
        assertEquals(firstPacket.anchor, track(engine).invasion!!.validatedAnchor)

        val second = engine.transition(playerFrame(20, surfaces = listOf(grid())))
        val secondPacket = second.effects.single { it.kind == EffectKind.MATERIALIZE }
        assertFalse(secondPacket.validateApproach)
        assertEquals(firstPacket.anchor, secondPacket.anchor)
    }

    @Test fun `forced intensity is exact bounded and consumed by one encounter`() {
        val engine = InvasionDirector.create(52, fixedSpec())
        engine.transition(DirectorFrame(0, commands = listOf(
            DirectorCommand.Force("player", EncounterKind.ASSAULT, intensity = 5),
        )))
        engine.transition(playerFrame(0))
        val invasion = track(engine).invasion!!
        assertEquals(5, invasion.intensity)
        assertEquals(24, invasion.waves.first().members.size)
        assertNull(track(engine).forcedIntensity)

        assertFailsWith<IllegalArgumentException> {
            engine.transition(DirectorFrame(0, commands = listOf(
                DirectorCommand.Force("other", EncounterKind.SCOUT, intensity = 6),
            )))
        }
    }

    @Test fun `only final death retires the encounter and applies death grace`() {
        val engine = InvasionDirector.create(6, fixedSpec())
        engine.transition(DirectorFrame(0, commands = listOf(DirectorCommand.Force("player", EncounterKind.ASSAULT))))
        acknowledge(engine, engine.transition(playerFrame(0)))
        engine.transition(playerFrame(20, surfaces = listOf(grid())))
        engine.transition(playerFrame(0, surfaces = listOf(grid())))
        assertTrue(engine.snapshot().pendingEffects.values.any { it.kind == EffectKind.MATERIALIZE })
        val injured = engine.transition(DirectorFrame(0, players = listOf(observation("player", low = true))))
        assertTrue(injured.effects.none { it.kind == EffectKind.RETIRE })
        assertNotNull(track(engine).invasion)
        val retire = engine.transition(DirectorFrame(0, players = listOf(observation("player", low = true)),
            targetDeaths = listOf(TargetDeathObservation("player"))))
        assertTrue(retire.effects.none { it.kind == EffectKind.MATERIALIZE })
        acknowledge(engine, retire)
        val track = track(engine)
        assertNull(track.invasion)
        assertEquals(-1, track.outcomeAdjustment)
        assertTrue(track.nextAssaultEligibleTick >= track.eligibleTicks + 250)
    }

    @Test fun `assault intent and active clock suspend while target is unavailable then resume`() {
        val engine = InvasionDirector.create(61, fixedSpec())
        engine.transition(DirectorFrame(0, commands = listOf(
            DirectorCommand.Force("player", EncounterKind.ASSAULT),
        )))
        engine.transition(playerFrame(0))
        val arrival = engine.transition(playerFrame(20, surfaces = listOf(grid())))
        assertTrue(arrival.effects.any { it.kind == EffectKind.MATERIALIZE })
        acknowledge(engine, arrival)

        val active = track(engine).invasion!!
        assertEquals(InvasionPhase.ACTIVE, active.phase)
        val invasionId = active.invasionId
        val activeTicks = active.activeTicks
        val unavailable = engine.transition(DirectorFrame(80, players = listOf(
            PlayerObservation("player", eligible = true, surfaceEligible = true, physicallyAvailable = false,
                position = BlockPoint("minecraft:overworld", 0, 64, 0)),
        )))

        assertEquals(invasionId, track(engine).invasion?.invasionId)
        assertTrue(track(engine).invasion!!.targetUnavailableTicks >= rules.targetUnavailableTicks)
        assertEquals(activeTicks, track(engine).invasion!!.activeTicks,
            "An unavailable target suspends the assault clock")
        assertTrue(unavailable.effects.none { it.kind == EffectKind.RETIRE || it.kind == EffectKind.MATERIALIZE })

        engine.transition(playerFrame(1, surfaces = listOf(grid())))
        val resumed = track(engine).invasion!!
        assertEquals(invasionId, resumed.invasionId)
        assertEquals(0L, resumed.targetUnavailableTicks)
        assertEquals(activeTicks + 1, resumed.activeTicks)
        assertEquals(InvasionPhase.ACTIVE, resumed.phase)
    }

    @Test fun `route requires a complete connected loaded observation and rejects partial progress`() {
        val target = BlockPoint("minecraft:overworld", 0, 64, 0)
        val open = (-4..4).map { x -> SurfaceCell(x, 64, 0) }
        assertNotNull(SurfaceApproach.choose(open, target, rules, 1))
        assertNull(SurfaceApproach.choose(open.filter { it.x !in -1..1 }, target, rules, 1))
        assertNull(SurfaceApproach.choose(open.map { if (kotlin.math.abs(it.x) == 1) it.copy(bodyY = 68) else it }, target, rules, 1))
        assertNull(SurfaceApproach.choose(open.map { it.copy(passable = false) }, target, rules, 1))
    }

    @Test fun `strategic routing starts far away respects cliffs walls and unknown terrain`() {
        val strategicRules = rules.copy(
            strategicOriginMinimumBlocks = 8, strategicOriginMaximumBlocks = 10,
            strategicMaximumSearchExpansions = 2_000,
        )
        val target = BlockPoint("minecraft:overworld", 0, 64, 0)
        val open = (0..10).flatMap { x -> (-2..2).map { z -> SurfaceCell(x, 64, z) } }
        val route = assertNotNull(StrategicRoutePlanner.plan(open, target, strategicRules, 9))
        assertEquals(StrategicFrontier.OPEN, route.frontier)
        assertTrue(maxOf(kotlin.math.abs(route.route.first().x), kotlin.math.abs(route.route.first().z)) in 8..10)
        assertTrue(maxOf(kotlin.math.abs(route.route.last().x), kotlin.math.abs(route.route.last().z)) in 2..4)

        val sealed = open.map { if (it.x == 1) it.copy(passable = false) else it }
        val defense = assertNotNull(StrategicRoutePlanner.plan(sealed, target, strategicRules, 9))
        assertEquals(StrategicFrontier.DEFENSE, defense.frontier)
        assertTrue(defense.route.last().x >= 2, "A sealed target must leave the materialization point outside")

        val cliff = open.map { if (it.x in -1..1) it.copy(bodyY = 72) else it }
        val cliffRoute = assertNotNull(StrategicRoutePlanner.plan(cliff, target, strategicRules, 9))
        assertEquals(StrategicFrontier.DEFENSE, cliffRoute.frontier)

        val incomplete = open.filter { it.x >= 3 }
        assertNull(StrategicRoutePlanner.plan(incomplete, target, strategicRules, 9),
            "Missing never-recorded terrain must block rather than be guessed")
    }

    @Test fun `failed exact approach is excluded and deterministic routing selects another side`() {
        val retryRules = rules.copy(
            strategicOriginMinimumBlocks = 8, strategicOriginMaximumBlocks = 10,
            strategicMaximumSearchExpansions = 8_000,
        )
        val target = BlockPoint("minecraft:overworld", 0, 64, 0)
        val north = (1..10).flatMap { x -> (-5..-3).map { z -> SurfaceCell(x, 64, z) } }
        val south = (1..10).flatMap { x -> (3..5).map { z -> SurfaceCell(x, 64, z) } }
        val joins = (-5..5).flatMap { z -> (0..1).map { x -> SurfaceCell(x, 64, z) } }
        val cells = (north + south + joins).distinctBy { it.x to it.z }
        val first = assertNotNull(StrategicRoutePlanner.plan(cells, target, retryRules, 41L))
        val failed = BlockPoint(target.dimension, first.route.last().x, first.route.last().bodyY, first.route.last().z)
        val second = assertNotNull(StrategicRoutePlanner.plan(
            cells, target, retryRules, 42L, excludedApproaches = listOf(failed),
        ))
        val separation = maxOf(kotlin.math.abs(second.route.last().x - failed.x),
            kotlin.math.abs(second.route.last().z - failed.z))
        assertTrue(separation > minOf(16, retryRules.approachMinimumBlocks),
            "Retry anchor ${second.route.last()} must leave the rejected approach zone around $failed")
        assertEquals(second, StrategicRoutePlanner.plan(
            cells, target, retryRules, 42L, excludedApproaches = listOf(failed),
        ))
    }

    @Test fun `failed materialization immediately retries a different proven route`() {
        val travelRules = rules.copy(
            strategicOriginMinimumBlocks = 5, strategicOriginMaximumBlocks = 6,
            scoutStrategicMilliBlocksPerTick = 1_000,
        )
        val engine = InvasionDirector.create(79, InvasionRuntimeSpec.create(travelRules, roster))
        engine.transition(DirectorFrame(0, commands = listOf(
            DirectorCommand.Force("player", EncounterKind.SCOUT, expediteTravel = true),
        )))
        engine.transition(playerFrame(0))
        var invasion = track(engine).invasion!!
        val firstRoute = (6 downTo 4).map { BlockPoint("minecraft:overworld", it, 64, 0) }
        val first = engine.transition(DirectorFrame(0, players = listOf(observation("player")), strategicRoutes = listOf(
            StrategicRouteObservation("player", invasion.invasionId, invasion.routeTarget!!, 10, firstRoute, StrategicFrontier.OPEN),
        )))
        val failedEffect = first.effects.single { it.kind == EffectKind.MATERIALIZE }
        assertTrue(failedEffect.validateApproach)
        engine.transition(DirectorFrame(0, effectResults = listOf(EffectResult(failedEffect.effectId, false))))
        invasion = track(engine).invasion!!
        assertEquals(firstRoute.last(), invasion.usedAnchors.single())
        assertNull(invasion.validatedAnchor)
        assertEquals(InvasionPhase.APPROACHING, invasion.phase)

        val alternateRoute = (6 downTo 4).map { BlockPoint("minecraft:overworld", it, 64, 4) }
        val retry = engine.transition(DirectorFrame(travelRules.normalPacketSpacingTicks,
            players = listOf(observation("player")), strategicRoutes = listOf(
            StrategicRouteObservation("player", invasion.invasionId, invasion.routeTarget!!, 10, alternateRoute, StrategicFrontier.OPEN),
        )))
        val retryEffect = retry.effects.single { it.kind == EffectKind.MATERIALIZE }
        assertTrue(retryEffect.validateApproach)
        assertEquals(alternateRoute.last(), retryEffect.anchor)
        assertEquals(2, track(engine).invasion!!.usedAnchors.size)
    }

    @Test fun `immaterial travel uses fixed point speed and cadence remains an arrival window`() {
        val travelRules = rules.copy(
            strategicOriginMinimumBlocks = 5, strategicOriginMaximumBlocks = 6,
            scoutStrategicMilliBlocksPerTick = 1_000,
        )
        val engine = InvasionDirector.create(77, InvasionRuntimeSpec.create(travelRules, roster))
        engine.transition(playerFrame(94))
        val invasion = track(engine).invasion!!
        assertEquals(100, invasion.scheduledArrivalEligibleTick)
        val points = (6 downTo 4).map { BlockPoint("minecraft:overworld", it, 64, 0) }
        engine.transition(DirectorFrame(0, players = listOf(observation("player")), strategicRoutes = listOf(
            StrategicRouteObservation("player", invasion.invasionId, invasion.routeTarget!!, 4, points, StrategicFrontier.OPEN),
        )))
        val journeyOrigin = track(engine).invasion!!.strategicOrigin
        val progressBefore = track(engine).invasion!!.strategicTravelMilliBlocks
        engine.transition(playerFrame(2))
        val travelling = track(engine).invasion!!
        assertEquals(progressBefore + 2_000, travelling.strategicTravelMilliBlocks)
        assertTrue(travelling.strategicPosition != journeyOrigin)
        assertEquals(InvasionPhase.APPROACHING, travelling.phase,
            "An early route arrival must wait for the authored encounter cadence")
        val arrival = engine.transition(playerFrame(4))
        assertTrue(arrival.effects.any { it.kind == EffectKind.MATERIALIZE })
        assertEquals(journeyOrigin, track(engine).invasion!!.strategicOrigin)
    }

    @Test fun `expedited admin encounters still require a known route and preserve its distant origin`() {
        val travelRules = rules.copy(
            strategicOriginMinimumBlocks = 5, strategicOriginMaximumBlocks = 6,
            scoutStrategicMilliBlocksPerTick = 1_000,
        )
        val engine = InvasionDirector.create(78, InvasionRuntimeSpec.create(travelRules, roster))
        engine.transition(DirectorFrame(0, commands = listOf(
            DirectorCommand.Force("player", EncounterKind.SCOUT, expediteTravel = true),
        )))
        engine.transition(playerFrame(0))
        val invasion = track(engine).invasion!!
        val journeyOrigin = invasion.strategicOrigin

        val unknown = engine.transition(playerFrame(10))
        assertEquals(InvasionPhase.APPROACHING, track(engine).invasion!!.phase)
        assertTrue(unknown.effects.none { it.kind == EffectKind.MATERIALIZE },
            "An expedited encounter must not invent a route across unknown terrain")

        val points = (6 downTo 4).map { BlockPoint("minecraft:overworld", it, 64, 0) }
        val routed = engine.transition(DirectorFrame(0, players = listOf(observation("player")), strategicRoutes = listOf(
            StrategicRouteObservation("player", invasion.invasionId, invasion.routeTarget!!, 9, points, StrategicFrontier.OPEN),
        )))
        assertTrue(routed.effects.any { it.kind == EffectKind.MATERIALIZE })
        val expedited = track(engine).invasion!!
        assertEquals(journeyOrigin, expedited.strategicOrigin)
        assertEquals(expedited.strategicJourneyTotalMilliBlocks, expedited.strategicTravelMilliBlocks)
        assertEquals(points.last(), expedited.anchor)
        assertEquals(0, expedited.strategicRouteIndex)
        assertEquals(StrategicFrontier.OPEN, expedited.strategicFrontier)
    }

    @Test fun `completed virtual journey retires after bounded local approach failures`() {
        val timeoutRules = rules.copy(localApproachTimeoutTicks = 40)
        val engine = InvasionDirector.create(80, InvasionRuntimeSpec.create(timeoutRules, roster))
        engine.transition(DirectorFrame(0, commands = listOf(
            DirectorCommand.Force("player", EncounterKind.SCOUT, expediteTravel = true),
        )))
        engine.transition(playerFrame(0))

        val retiring = engine.transition(playerFrame(40))
        assertTrue(retiring.effects.any { it.kind == EffectKind.RETIRE })
        val invasion = track(engine).invasion!!
        assertEquals(InvasionPhase.RETIRING, invasion.phase)
        assertEquals("local_approach_timeout", invasion.lastRouteFailure)

        acknowledge(engine, retiring)
        val resolved = track(engine)
        assertNull(resolved.invasion)
        assertTrue(resolved.nextScoutEligibleTick > resolved.eligibleTicks)
    }

    @Test fun `assault route timeout suspends approach and resumes when a loaded route appears`() {
        val timeoutRules = rules.copy(
            assaultWarningSurfaceTicks = 0,
            localApproachTimeoutTicks = 40,
            strategicOriginMinimumBlocks = 5,
            strategicOriginMaximumBlocks = 6,
            assaultStrategicMilliBlocksPerTick = 1_000,
        )
        val engine = InvasionDirector.create(82, InvasionRuntimeSpec.create(timeoutRules, roster))
        engine.transition(DirectorFrame(0, commands = listOf(
            DirectorCommand.Force("player", EncounterKind.ASSAULT, expediteTravel = true),
        )))
        engine.transition(playerFrame(0))
        var invasion = track(engine).invasion!!
        val invasionId = invasion.invasionId
        engine.transition(DirectorFrame(0, players = listOf(observation("player")), strategicRoutes = listOf(
            StrategicRouteObservation("player", invasionId, invasion.routeTarget!!, 1, emptyList(), StrategicFrontier.UNKNOWN),
        )))

        val timedOut = engine.transition(playerFrame(40))
        invasion = track(engine).invasion!!
        assertEquals(invasionId, invasion.invasionId)
        assertEquals(EncounterKind.ASSAULT, invasion.kind)
        assertEquals(InvasionPhase.APPROACHING, invasion.phase)
        assertEquals("local_approach_timeout", invasion.lastRouteFailure)
        assertEquals(0L, invasion.localApproachTicks, "A timeout starts a fresh bounded retry window")
        assertTrue(timedOut.effects.none { it.kind == EffectKind.RETIRE || it.kind == EffectKind.MATERIALIZE })

        val route = (6 downTo 4).map { BlockPoint("minecraft:overworld", it, 64, 0) }
        val resumed = engine.transition(DirectorFrame(0, players = listOf(observation("player")), strategicRoutes = listOf(
            StrategicRouteObservation("player", invasionId, invasion.routeTarget!!, 2, route, StrategicFrontier.OPEN),
        )))
        assertEquals(invasionId, track(engine).invasion!!.invasionId)
        assertEquals(InvasionPhase.READY_TO_MATERIALIZE, track(engine).invasion!!.phase)
        assertEquals("none", track(engine).invasion!!.lastRouteFailure)
        assertTrue(resumed.effects.any { it.kind == EffectKind.MATERIALIZE })
        assertTrue(resumed.effects.none { it.kind == EffectKind.RETIRE })
    }

    @Test fun `moving target relocates virtual corridor without resetting travel progress`() {
        val engine = InvasionDirector.create(81, fixedSpec())
        engine.transition(playerFrame(94))
        val before = track(engine).invasion!!
        val originOffset = before.strategicOrigin!!.x - before.routeTarget!!.x to
            (before.strategicOrigin!!.z - before.routeTarget!!.z)
        val progress = before.strategicTravelMilliBlocks

        engine.transition(DirectorFrame(0, players = listOf(observation("player", 20))))
        val moved = track(engine).invasion!!
        assertEquals(progress, moved.strategicTravelMilliBlocks)
        assertEquals(20 + originOffset.first, moved.strategicOrigin!!.x)
        assertEquals(originOffset.second, moved.strategicOrigin!!.z)
        assertEquals("target_moved", moved.lastRouteFailure)
    }

    @Test fun `default distant origin corpus is deterministic and reaches the approach band`() {
        val target = BlockPoint("minecraft:overworld", 0, 64, 0)
        val recordedCorridor = (0..768).map { SurfaceCell(it, 64, 0) }
        fun corpus() = (0L..1_023L).map { seed ->
            assertNotNull(StrategicRoutePlanner.plan(recordedCorridor, target, InvasionRules(), seed))
        }
        val first = corpus()
        assertEquals(first, corpus())
        first.forEach { result ->
            assertTrue(result.route.first().x in 512..768)
            assertTrue(result.route.last().x in 48..72)
            assertEquals(StrategicFrontier.OPEN, result.frontier)
        }
    }

    @Test fun `production scale terrain atlas routes without stalling the server tick`() {
        val target = BlockPoint("minecraft:overworld", 0, 64, 0)
        val recordedTerrain = (0..768).flatMap { x ->
            (-24..23).map { z -> SurfaceCell(x, 64, z) }
        }
        lateinit var result: StrategicRoutePlanner.Result
        val elapsed = measureTimeMillis {
            result = assertNotNull(StrategicRoutePlanner.plan(recordedTerrain, target, InvasionRules(), 29L))
        }
        assertTrue(elapsed < 2_000, "Production-scale strategic routing took ${elapsed}ms")
        assertTrue(result.route.first().x in 512..768)
        assertTrue(result.route.last().x in 48..72)
    }

    @Test fun `connected recorded approach wins before an unrelated explored island exhausts the search budget`() {
        val target = BlockPoint("minecraft:overworld", 0, 64, 0)
        val connected = (0..540).flatMap { x -> (-2..2).map { z -> SurfaceCell(x, 64, z) } }
        val unrelated = (512..620).flatMap { x -> (300..408).map { z -> SurfaceCell(x, 64, z) } }
        val constrained = InvasionRules(strategicMaximumSearchExpansions = 700)

        val result = assertNotNull(StrategicRoutePlanner.plan(unrelated + connected, target, constrained, 8665659058770451541L))

        assertTrue(result.route.first().x in 512..540)
        assertTrue(result.route.first().z in -2..2)
        assertEquals(StrategicFrontier.OPEN, result.frontier)
        assertTrue(result.route.last().x in 48..72)
    }

    @Test fun `restore is exact schema is strict and invalid inputs fail closed`() {
        spec.requireValid()
        assertEquals(spec.revision, spec.computedRevision())
        assertFailsWith<IllegalArgumentException> { spec.copy(revision = "drift").requireValid() }
        assertFailsWith<IllegalArgumentException> { InvasionRuntimeSpec.create(rules, emptyList()).requireValid() }
        assertFailsWith<IllegalArgumentException> { InvasionRules(scoutWindowMinTicks = 2, scoutWindowMaxTicks = 1) }
        val engine = InvasionDirector.create(7, spec)
        engine.transition(playerFrame(50))
        val restored = InvasionDirector.restore(engine.snapshot(), spec)
        assertEquals(Json.encodeToString(engine.snapshot()), Json.encodeToString(restored.snapshot()))
        assertFailsWith<IllegalArgumentException> {
            InvasionDirector.restore(engine.snapshot().copy(schemaVersion = 1), spec)
        }
        engine.transition(DirectorFrame(0, commands = listOf(DirectorCommand.Reset("player"))))
        assertTrue(engine.snapshot().tracks.isEmpty())
        engine.transition(DirectorFrame(0, commands = listOf(DirectorCommand.Reset())))
    }

    private data class Timeline(val scoutDue: Long, val assaultDue: Long, val scouts: Int, val assaults: Int)

    private fun timeline(seed: Long): Timeline {
        val corpusRules = InvasionRules(
            scoutWindowMinTicks = 7_200, scoutWindowMaxTicks = 14_400,
            assaultWindowMinTicks = 72_000, assaultWindowMaxTicks = 144_000,
            assaultWarningSurfaceTicks = 0, scoutActiveTicks = 20, activeIdleTicks = 20,
            approachMinimumBlocks = 2, approachMaximumBlocks = 4, approachTargetRadiusBlocks = 1,
        )
        val engine = InvasionDirector.create(seed, InvasionRuntimeSpec.create(corpusRules, roster))
        engine.transition(playerFrame(0))
        val initial = track(engine)
        val scoutDue = initial.nextScoutEligibleTick
        val assaultDue = initial.nextAssaultEligibleTick
        var scouts = 0
        var assaults = 0
        repeat(1_000) {
            var transition = engine.transition(playerFrame(200, surfaces = listOf(grid())))
            scouts += transition.events.count { event -> event.type == "scout_started" }
            assaults += transition.events.count { event -> event.type == "assault_warned" }
            if (transition.effects.isNotEmpty()) {
                transition = engine.transition(playerFrame(0, results = transition.effects.map { EffectResult(it.effectId) }))
                scouts += transition.events.count { event -> event.type == "scout_started" }
                assaults += transition.events.count { event -> event.type == "assault_warned" }
            }
        }
        return Timeline(scoutDue, assaultDue, scouts, assaults)
    }

    private fun fixedSpec() = InvasionRuntimeSpec.create(rules, roster)
    private fun observation(id: String, x: Int = 0, low: Boolean = false) =
        PlayerObservation(id, true, true, true, BlockPoint("minecraft:overworld", x, 64, 0), low)
    private fun playerFrame(
        ticks: Long, eligible: Boolean = true, surface: Boolean = true,
        results: List<EffectResult> = emptyList(), surfaces: List<SurfaceGridObservation> = emptyList(),
        defeats: List<MemberDefeatObservation> = emptyList(),
    ) = DirectorFrame(ticks, listOf(PlayerObservation("player", eligible, surface, true,
        BlockPoint("minecraft:overworld", 0, 64, 0))), surfaces, results, defeats)
    private fun grid(id: String = "player", center: Int = 0) = SurfaceGridObservation(
        id, (-4..4).map { SurfaceCell(center + it, 64, 0) }, complete = true,
    )
    private fun track(engine: InvasionDirector, id: String = "player") = engine.snapshot().tracks.getValue(id)

    private fun acknowledge(engine: InvasionDirector, transition: DirectorTransition) {
        if (transition.effects.isNotEmpty()) engine.transition(DirectorFrame(0,
            effectResults = transition.effects.map { EffectResult(it.effectId) }))
    }

    private fun materializeCurrent(engine: InvasionDirector) {
        val transition = engine.transition(playerFrame(0, surfaces = listOf(grid())))
        acknowledge(engine, transition)
    }

    private fun drainWave(engine: InvasionDirector) {
        repeat(4) {
            val transition = engine.transition(playerFrame(20, surfaces = listOf(grid())))
            acknowledge(engine, transition)
            val invasion = track(engine).invasion ?: return
            if (invasion.waves[invasion.currentWave].materializedMembers == invasion.waves[invasion.currentWave].members.size) return
        }
    }
}
