package com.bettercontent.betterpillagercampaigns.core

import com.bettercontent.pillagercampaigns.core.VirtualReachability
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Bounds
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Cell
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Point
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Result
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.ceil
import kotlin.random.Random
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
        approachMinimumBlocks = 2, approachMaximumBlocks = 4,
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
        val arrived = engine.transition(playerFrame(19, routes = fixtureRoutes(engine)))
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
        engine.transition(playerFrame(0, routes = fixtureRoutes(engine)))
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
            strategicRoutes = fixtureRoutes(engine, "a")))
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
            strategicRoutes = fixtureRoutes(engine, "a", "b"), liveCampaignMobs = 90, secondSpawnCount = 20))
        assertTrue(transition.effects.filter { it.kind == EffectKind.MATERIALIZE }.sumOf { it.members.size } <= 4)
        acknowledge(engine, transition)
        val served = mutableSetOf<String>()
        repeat(4) {
            transition = engine.transition(DirectorFrame(60, players = far, strategicRoutes = fixtureRoutes(engine, "a", "b")))
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

    @Test fun `successive packets share the same proven anchor without another route observation`() {
        val engine = InvasionDirector.create(51, fixedSpec())
        engine.transition(DirectorFrame(0, commands = listOf(
            DirectorCommand.Force("player", EncounterKind.ASSAULT, intensity = 5),
        )))
        engine.transition(playerFrame(0))
        val first = engine.transition(playerFrame(20, routes = fixtureRoutes(engine)))
        val firstPacket = first.effects.single { it.kind == EffectKind.MATERIALIZE }
        assertNotNull(firstPacket.anchor)
        assertNull(track(engine).invasion!!.validatedAnchor)
        acknowledge(engine, first)
        assertEquals(firstPacket.anchor, track(engine).invasion!!.validatedAnchor)

        val second = engine.transition(playerFrame(20))
        val secondPacket = second.effects.single { it.kind == EffectKind.MATERIALIZE }
        assertEquals(firstPacket.anchor, secondPacket.anchor)
        assertTrue(firstPacket.members.intersect(secondPacket.members.toSet()).isEmpty())
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
        engine.transition(playerFrame(20, routes = fixtureRoutes(engine)))
        engine.transition(playerFrame(0, routes = fixtureRoutes(engine)))
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
        val arrival = engine.transition(playerFrame(20, routes = fixtureRoutes(engine)))
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

        engine.transition(playerFrame(1, routes = fixtureRoutes(engine)))
        val resumed = track(engine).invasion!!
        assertEquals(invasionId, resumed.invasionId)
        assertEquals(0L, resumed.targetUnavailableTicks)
        assertEquals(activeTicks + 1, resumed.activeTicks)
        assertEquals(InvasionPhase.ACTIVE, resumed.phase)
    }

    @Test fun `a partial or missing route cannot offer a spawn on the near side`() {
        val start = Point(10, 1, 0)
        val goal = Point(0, 1, 0)
        val bounds = Bounds(-1..11, -2..8, -1..1)
        fun probe(blocked: (Point) -> Cell?) = route(start, goal, bounds, 2_000) { p ->
            blocked(p) ?: corridor(p, 0..10, 0..0)
        }
        val (open, witness) = probe { null }
        assertEquals(Result.Reachable(goal), open)
        assertEquals(start, witness.first())
        assertEquals(goal, witness.last())
        assertEquals(Point(4, 1, 0), VirtualReachability.spawnPoint(witness, 2..4) { true })
        val (missing, partial) = probe { if (it.x == 2 && it.z == 0) Cell.UNKNOWN else null }
        assertEquals(Result.Unknown(VirtualReachability.UnknownReason.TERRAIN_OR_BOUNDARY), missing)
        assertNull(VirtualReachability.spawnPoint(partial, 2..4) { true })
    }

    @Test fun `strategic routing starts at its actual origin and does not cross walls or cliffs`() {
        val start = Point(10, 1, 0)
        val goal = Point(0, 1, 0)
        val bounds = Bounds(-1..11, -2..8, -1..1)
        fun probe(obstructed: (Point) -> Boolean) = route(start, goal, bounds, 2_000) { p ->
            if (obstructed(p)) Cell.OBSTACLE else corridor(p, 0..10, 0..0)
        }
        assertEquals(Result.Reachable(goal), probe { false }.first)
        assertEquals(Result.Unreachable, probe { it.x == 1 }.first)
        assertEquals(Result.Unreachable, probe { it.x == 1 && it.y <= 6 }.first)
        assertEquals(Result.Unreachable, probe { it.y == 1 }.first)
        val budgetLimited = route(start, goal, bounds, 2) { corridor(it, 0..10, 0..0) }
        assertEquals(Result.Unknown(VirtualReachability.UnknownReason.SEARCH_BUDGET), budgetLimited.first)
        assertTrue(budgetLimited.second.isEmpty(), "A partial route is never a valid spawn witness")
    }

    @Test fun `failed exact approach is excluded and next eligible spot on the witness is deterministic`() {
        val start = Point(10, 1, 0)
        val goal = Point(0, 1, 0)
        val (result, witness) = route(start, goal, Bounds(-1..11, -2..8, -1..1), 2_000) {
            corridor(it, 0..10, 0..0)
        }
        assertEquals(Result.Reachable(goal), result)
        val failed = assertNotNull(VirtualReachability.spawnPoint(witness, 2..4) { true })
        val alternate = assertNotNull(VirtualReachability.spawnPoint(witness, 2..4) { it != failed })
        assertEquals(Point(3, 1, 0), alternate)
        assertEquals(alternate, VirtualReachability.spawnPoint(witness, 2..4) { it != failed })
        assertNull(VirtualReachability.spawnPoint(witness, 2..4) { false })
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
            StrategicRouteObservation("player", invasion.invasionId, invasion.routeTarget!!, 10, firstRoute),
        )))
        val failedEffect = first.effects.single { it.kind == EffectKind.MATERIALIZE }
        assertEquals(firstRoute.last(), failedEffect.anchor)
        engine.transition(DirectorFrame(0, effectResults = listOf(EffectResult(failedEffect.effectId, false))))
        invasion = track(engine).invasion!!
        assertEquals(firstRoute.last(), invasion.usedAnchors.single())
        assertNull(invasion.validatedAnchor)
        assertEquals(InvasionPhase.APPROACHING, invasion.phase)

        val alternateRoute = (6 downTo 4).map { BlockPoint("minecraft:overworld", it, 64, 4) }
        val retry = engine.transition(DirectorFrame(travelRules.normalPacketSpacingTicks,
            players = listOf(observation("player")), strategicRoutes = listOf(
            StrategicRouteObservation("player", invasion.invasionId, invasion.routeTarget!!, 10, alternateRoute),
        )))
        val retryEffect = retry.effects.single { it.kind == EffectKind.MATERIALIZE }
        assertNull(track(engine).invasion!!.validatedAnchor)
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
            StrategicRouteObservation("player", invasion.invasionId, invasion.routeTarget!!, 4, points),
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
            StrategicRouteObservation("player", invasion.invasionId, invasion.routeTarget!!, 9, points),
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
            StrategicRouteObservation("player", invasionId, invasion.routeTarget!!, 1, emptyList()),
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
            StrategicRouteObservation("player", invasionId, invasion.routeTarget!!, 2, route),
        )))
        assertEquals(invasionId, track(engine).invasion!!.invasionId)
        assertEquals(InvasionPhase.READY_TO_MATERIALIZE, track(engine).invasion!!.phase)
        assertEquals("none", track(engine).invasion!!.lastRouteFailure)
        assertTrue(resumed.effects.any { it.kind == EffectKind.MATERIALIZE })
        assertTrue(resumed.effects.none { it.kind == EffectKind.RETIRE })
    }

    @Test fun `moving target replans from the fixed origin without resetting travel progress`() {
        val engine = InvasionDirector.create(81, fixedSpec())
        engine.transition(playerFrame(94))
        val before = track(engine).invasion!!
        val origin = assertNotNull(before.strategicOrigin)
        val progress = before.strategicTravelMilliBlocks

        engine.transition(DirectorFrame(0, players = listOf(observation("player", 20))))
        val moved = track(engine).invasion!!
        assertEquals(progress, moved.strategicTravelMilliBlocks)
        assertEquals(20, moved.routeTarget?.x)
        assertEquals(origin, moved.strategicOrigin)
        assertEquals("target_moved", moved.lastRouteFailure)
    }

    @Test fun `default distant origin corpus is deterministic and its routes contain stock spawn spots`() {
        val target = Point(0, 1, 0)
        fun corpus(): List<Pair<Point, Point>> {
            val byOrigin = mutableMapOf<Int, Pair<Point, Point>>()
            return (0L..1_023L).map { seed ->
                val x = Random(seed).nextInt(512, 769)
                byOrigin.getOrPut(x) {
                    val start = Point(x, 1, 0)
                    val (result, witness) = route(start, target, Bounds(-1..769, -2..8, -1..1), 2_000) {
                        corridor(it, 0..768, 0..0)
                    }
                    assertEquals(Result.Reachable(target), result)
                    assertEquals(start, witness.first())
                    assertEquals(target, witness.last())
                    start to assertNotNull(VirtualReachability.spawnPoint(witness, 48..72) { true })
                }
            }
        }
        val first = corpus()
        assertEquals(first, corpus())
        first.forEach { (start, spawn) ->
            assertTrue(start.x in 512..768)
            assertTrue(spawn.x in 48..72)
        }
    }

    @Test fun `production scale 3D terrain routes in bounded slices`() {
        val target = Point(0, 1, 0)
        val start = Point(768, 1, 0)
        lateinit var witness: List<Point>
        val elapsed = measureTimeMillis {
            val search = VirtualReachability(start, Bounds(-1..769, -2..8, -25..24),
                { it == target }, { 2 * it.lowerBoundTo(target) }, 16_384,
                { corridor(it, 0..768, -24..23) }, retainWitness = true)
            assertEquals(Result.Running, search.advance(128))
            assertEquals(128, search.expanded)
            assertEquals(Result.Reachable(target), settleRoute(search))
            witness = search.witness
        }
        assertTrue(elapsed < 2_000, "Production-scale routing took ${elapsed}ms")
        assertEquals(start, witness.first())
        assertEquals(target, witness.last())
        assertTrue(assertNotNull(VirtualReachability.spawnPoint(witness, 48..72) { true }).x in 48..72)
    }

    @Test fun `disconnected explored terrain never replaces the campaign origin`() {
        val target = Point(0, 1, 0)
        val start = Point(540, 1, 0)
        val bounds = Bounds(-1..621, -2..8, -3..409)
        fun terrain(p: Point) = if (p.x in 512..620 && p.z in 300..408) corridor(p, 512..620, 300..408)
            else corridor(p, 0..540, -2..2)
        val (result, witness) = route(start, target, bounds, 700, ::terrain)
        assertEquals(Result.Reachable(target), result)
        assertEquals(start, witness.first())
        assertTrue(assertNotNull(VirtualReachability.spawnPoint(witness, 48..72) { true }).x in 48..72)
        val (isolated, noWitness) = route(Point(540, 1, 350), target, bounds, 700, ::terrain)
        assertTrue(isolated !is Result.Reachable)
        assertTrue(noWitness.isEmpty())
    }

    private fun corridor(point: Point, xs: IntRange, zs: IntRange): Cell = when {
        point.x !in xs || point.z !in zs -> Cell.OBSTACLE
        point.y <= 0 -> Cell.SUPPORT
        else -> Cell.CLEAR
    }

    private fun route(start: Point, target: Point, bounds: Bounds, limit: Int, terrain: (Point) -> Cell): Pair<Result, List<Point>> {
        val search = VirtualReachability(start, bounds, { it == target }, { 2 * it.lowerBoundTo(target) }, limit,
            terrain, retainWitness = true)
        return settleRoute(search) to search.witness
    }

    private fun settleRoute(search: VirtualReachability): Result {
        repeat(200) { search.advance(128).let { if (it != Result.Running) return it } }
        error("bounded fixture search did not settle")
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
            approachMinimumBlocks = 2, approachMaximumBlocks = 4,
        )
        val engine = InvasionDirector.create(seed, InvasionRuntimeSpec.create(corpusRules, roster))
        engine.transition(playerFrame(0))
        val initial = track(engine)
        val scoutDue = initial.nextScoutEligibleTick
        val assaultDue = initial.nextAssaultEligibleTick
        var scouts = 0
        var assaults = 0
        var newlyDispatched = true
        repeat(1_000) {
            val routes = if (newlyDispatched) fixtureRoutes(engine) else emptyList()
            var transition = engine.transition(playerFrame(200, routes = routes))
            newlyDispatched = transition.events.any { it.type in setOf("scout_started", "assault_dispatched") }
            scouts += transition.events.count { event -> event.type == "scout_started" }
            assaults += transition.events.count { event -> event.type == "assault_warned" }
            if (transition.effects.isNotEmpty()) {
                transition = engine.transition(playerFrame(0, results = transition.effects.map { EffectResult(it.effectId) }))
                newlyDispatched = newlyDispatched || transition.events.any { it.type in setOf("scout_started", "assault_dispatched") }
                scouts += transition.events.count { event -> event.type == "scout_started" }
                assaults += transition.events.count { event -> event.type == "assault_warned" }
            }
        }
        return Timeline(scoutDue, assaultDue, scouts, assaults)
    }

    @Test fun `unfamiliar dispatch and approach wait without consuming global eligible progression`() {
        val engine = InvasionDirector.create(1, fixedSpec())
        val stranger = observation("player").copy(regionFamiliar = false)
        engine.transition(DirectorFrame(10_000, listOf(stranger)))
        assertEquals(10_000, track(engine).eligibleTicks)
        assertNull(track(engine).invasion)
        assertTrue(engine.snapshot().regionalPresence.isEmpty()) // elapsed frame is not presence
        engine.transition(DirectorFrame(0, listOf(stranger.copy(regionFamiliar = true))))
        val initial = track(engine).invasion!!
        engine.transition(DirectorFrame(10_000, listOf(stranger)))
        val paused = track(engine).invasion!!
        assertEquals(initial.strategicTravelMilliBlocks, paused.strategicTravelMilliBlocks)
        assertEquals(initial.localApproachTicks, paused.localApproachTicks)
        assertEquals(initial.targetUnavailableTicks, paused.targetUnavailableTicks)
        assertEquals(20_000, track(engine).eligibleTicks)
        engine.transition(DirectorFrame(1, listOf(stranger.copy(regionFamiliar = true))))
        assertTrue(track(engine).invasion!!.localApproachTicks > paused.localApproachTicks)
    }

    @Test fun `active enemies keep lifetime but fresh waves and participant grouping require familiar ground`() {
        val engine = InvasionDirector.create(1, fixedSpec())
        val known = observation("player")
        val unknown = observation("other").copy(regionFamiliar = false)
        engine.transition(DirectorFrame(0, listOf(known, unknown), commands = listOf(
            DirectorCommand.Force("player", EncounterKind.ASSAULT))))
        val snapshot = engine.snapshot()
        val invasion = snapshot.tracks.getValue("player").invasion!!
        assertEquals(listOf("player"), invasion.participantPlayerIds)
        invasion.phase = InvasionPhase.ACTIVE
        invasion.anchor = known.position
        val wave = invasion.waves[0]
        wave.materializedMembers = wave.members.size
        wave.queuedMembers = wave.members.size
        wave.startedTick = snapshot.tick
        invasion.defeatedMemberIds.addAll(wave.members.take((wave.members.size + 1) / 2).map { it.memberId })
        val restored = InvasionDirector.restore(snapshot, fixedSpec())
        val transition = restored.transition(DirectorFrame(10, listOf(known.copy(regionFamiliar = false))))
        assertEquals(0, track(restored).invasion!!.currentWave)
        assertEquals(10, track(restored).invasion!!.activeTicks)
        assertEquals(InvasionPhase.ACTIVE, track(restored).invasion!!.phase)
        assertTrue(transition.effects.none { it.kind == EffectKind.MATERIALIZE })
        restored.transition(DirectorFrame(0, listOf(known)))
        assertEquals(1, track(restored).invasion!!.currentWave)
    }

    @Test fun `presence is independently persisted per player and never inferred from cadence`() {
        val engine = InvasionDirector.create(1, fixedSpec())
        val point = observation("player").position!!
        repeat(36_000) { engine.recordPresence("player", point) }
        assertTrue(engine.regionFamiliar("player", point))
        assertFalse(engine.regionFamiliar("other", point))
        val restored = InvasionDirector.restore(engine.snapshot(), fixedSpec())
        restored.transition(DirectorFrame(1_000_000)) // offline must add nothing
        assertEquals(engine.snapshot().regionalPresence, restored.snapshot().regionalPresence)
        assertTrue(restored.regionFamiliar("player", point))
    }

    private fun fixedSpec() = InvasionRuntimeSpec.create(rules, roster)
    private fun observation(id: String, x: Int = 0, low: Boolean = false) =
        PlayerObservation(id, true, true, true, BlockPoint("minecraft:overworld", x, 64, 0), low)
    private fun playerFrame(
        ticks: Long, eligible: Boolean = true, surface: Boolean = true,
        results: List<EffectResult> = emptyList(), routes: List<StrategicRouteObservation> = emptyList(),
        defeats: List<MemberDefeatObservation> = emptyList(),
    ) = DirectorFrame(ticks, listOf(PlayerObservation("player", eligible, surface, true,
        BlockPoint("minecraft:overworld", 0, 64, 0))), effectResults = results, memberDefeats = defeats,
        strategicRoutes = routes)

    private fun fixtureRoutes(engine: InvasionDirector, vararg ids: String = arrayOf("player")): List<StrategicRouteObservation> {
        val snapshot = engine.snapshot()
        return ids.mapNotNull { id ->
            val invasion = snapshot.tracks[id]?.invasion ?: return@mapNotNull null
            val target = invasion.routeTarget ?: return@mapNotNull null
            StrategicRouteObservation(id, invasion.invasionId, target, snapshot.tick,
                listOf(target.copy(x = target.x + 4)))
        }
    }
    private fun track(engine: InvasionDirector, id: String = "player") = engine.snapshot().tracks.getValue(id)

    private fun acknowledge(engine: InvasionDirector, transition: DirectorTransition) {
        if (transition.effects.isNotEmpty()) engine.transition(DirectorFrame(0,
            effectResults = transition.effects.map { EffectResult(it.effectId) }))
    }

    private fun materializeCurrent(engine: InvasionDirector) {
        val transition = engine.transition(playerFrame(0, routes = fixtureRoutes(engine)))
        acknowledge(engine, transition)
    }

    private fun drainWave(engine: InvasionDirector) {
        repeat(4) {
            val transition = engine.transition(playerFrame(20, routes = fixtureRoutes(engine)))
            acknowledge(engine, transition)
            val invasion = track(engine).invasion ?: return
            if (invasion.waves[invasion.currentWave].materializedMembers == invasion.waves[invasion.currentWave].members.size) return
        }
    }
}
