package com.bettercontent.betterpillagercampaigns.core

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class InvasionDirector private constructor(
    private val state: DirectorSnapshot,
    private val spec: InvasionRuntimeSpec,
) {
    fun transition(frame: DirectorFrame): DirectorTransition {
        require(frame.elapsedTicks >= 0L)
        val events = mutableListOf<DirectorEvent>()
        applyCommands(frame.commands, events)
        applyEffectResults(frame.effectResults, events)
        mergeSurfaces(frame.surfaces)
        mergeStrategicRoutes(frame.strategicRoutes)
        state.tick += frame.elapsedTicks
        val players = frame.players.associateBy(PlayerObservation::playerId)
        players.values.sortedBy(PlayerObservation::playerId).forEach { player ->
            val track = track(player.playerId)
            if (track.nextScoutEligibleTick < 0L) schedule(track, EncounterKind.SCOUT)
            if (track.nextAssaultEligibleTick < 0L) schedule(track, EncounterKind.ASSAULT)
            if (player.eligible) track.eligibleTicks += frame.elapsedTicks
        }
        applyCombat(frame, events)
        advanceExisting(players, frame.elapsedTicks, events)
        beginDueEncounters(players, events)
        issuePackets(players, frame, events)
        return DirectorTransition(events, state.pendingEffects.values.map(::copyEffect))
    }

    fun snapshot(): DirectorSnapshot = copy(state)

    private fun beginDueEncounters(players: Map<String, PlayerObservation>, events: MutableList<DirectorEvent>) {
        val available = players.values.filter { it.eligible && it.surfaceEligible && it.physicallyAvailable }
        fun candidates(kind: EncounterKind) = available.filter { player ->
            val track = track(player.playerId)
            track.invasion == null && track.joinedInvasionId == null &&
                track.eligibleTicks >= due(track, kind) - dispatchLead(kind)
        }.sortedWith(compareBy<PlayerObservation> { due(track(it.playerId), kind) - track(it.playerId).eligibleTicks }
            .thenBy { it.playerId })
        val kindOrder = listOf(EncounterKind.SCOUT, EncounterKind.ASSAULT).sortedBy { kind ->
            available.minOfOrNull { due(track(it.playerId), kind) } ?: Long.MAX_VALUE
        }
        for (kind in kindOrder) {
            val remaining = candidates(kind).toMutableList()
            while (remaining.isNotEmpty()) {
                val primary = remaining.removeAt(0)
                if (track(primary.playerId).invasion != null || track(primary.playerId).joinedInvasionId != null) continue
                if (kind == EncounterKind.SCOUT && participantInAssault(primary.playerId)) continue
                val group = buildList {
                    add(primary)
                    remaining.filterTo(this) { close(primary, it, spec.rules.groupRadiusBlocks) }
                }
                remaining.removeAll(group.toSet())
                beginEncounter(kind, primary, group, events)
            }
        }
    }

    private fun beginEncounter(
        kind: EncounterKind,
        primary: PlayerObservation,
        participants: List<PlayerObservation>,
        events: MutableList<DirectorEvent>,
    ) {
        val track = track(primary.playerId)
        track.encounterSequence++
        val timeTier = (track.eligibleTicks / spec.rules.timeTierTicks).toInt()
        val intensity = track.forcedIntensity?.also { track.forcedIntensity = null }
            ?: (timeTier + track.outcomeAdjustment).coerceIn(0, spec.rules.maximumIntensity)
        val id = "${kind.name.lowercase()}:${primary.playerId}:${track.encounterSequence}"
        val size = EncounterPolicy.memberCount(kind, intensity, participants.size, spec.rules)
        val waves = if (kind == EncounterKind.SCOUT) {
            listOf(RosterPlanner.planWave(spec.recruits, kind, intensity, 0, size, id, state.worldSeed))
        } else {
            (0 until spec.rules.assaultWaves).map { wave ->
                RosterPlanner.planWave(spec.recruits, kind, intensity, wave, size, id, state.worldSeed)
            }
        }
        val invasion = InvasionState(
            invasionId = id, kind = kind, targetPlayerId = primary.playerId,
            participantPlayerIds = participants.map(PlayerObservation::playerId).sorted(),
            intensity = intensity, waves = waves,
            phase = InvasionPhase.APPROACHING,
            lastCombatTick = state.tick, routeTarget = primary.position,
            scheduledArrivalEligibleTick = due(track, kind),
            adminExpedited = track.expediteNextEncounter == kind,
        )
        initializeVirtualJourney(invasion, track)
        if (invasion.adminExpedited) track.expediteNextEncounter = null
        track.invasion = invasion
        participants.filter { it.playerId != primary.playerId }.forEach { track(it.playerId).joinedInvasionId = id }
        if (kind == EncounterKind.SCOUT) {
            events += event("scout_started", id, "intensity=$intensity players=${participants.size} members=$size")
        } else {
            events += event("assault_dispatched", id, "intensity=$intensity players=${participants.size} wave_size=$size")
        }
    }

    private fun advanceExisting(players: Map<String, PlayerObservation>, elapsed: Long, events: MutableList<DirectorEvent>) {
        primaryTracks().forEach { track ->
            val invasion = track.invasion ?: return@forEach
            val target = chooseTarget(invasion, players)
            val available = target?.let { it.eligible && it.physicallyAvailable } == true
            invasion.targetUnavailableTicks = if (available) 0L else invasion.targetUnavailableTicks + elapsed
            if (invasion.kind == EncounterKind.SCOUT &&
                invasion.targetUnavailableTicks >= spec.rules.targetUnavailableTicks) {
                requestRetire(track, InvasionOutcome.RETIRED, events)
                return@forEach
            }
            // Assaults retain their hostile intent while no participant can be pursued.
            // Stop all per-encounter clocks and route work until a target is available again.
            if (invasion.kind == EncounterKind.ASSAULT && !available) return@forEach
            if (target?.position != null && invasion.routeTarget?.let { moved(it, target.position) } == true) {
                val previousTarget = invasion.routeTarget
                invasion.routeTarget = target.position
                invasion.anchor = null
                invasion.usedAnchors.clear()
                invasion.strategicRoute.clear()
                invasion.strategicRouteIndex = 0
                invasion.localApproachTicks = 0L
                invasion.strategicFrontier = StrategicFrontier.UNKNOWN
                invasion.validatedAnchor = null
                invasion.lastRouteFailure = "target_moved"
                invasion.phase = InvasionPhase.APPROACHING
                if (previousTarget != null) invasion.strategicOrigin = invasion.strategicOrigin?.let { origin ->
                    BlockPoint(target.position.dimension, target.position.x + origin.x - previousTarget.x,
                        target.position.y, target.position.z + origin.z - previousTarget.z)
                }
                updateVirtualPosition(invasion)
            }
            if (invasion.kind == EncounterKind.ASSAULT && !invasion.warningIssued && shouldWarn(track, invasion) &&
                target?.surfaceEligible == true && available) {
                publish(DirectorEffect("", EffectKind.WARN, invasion.targetPlayerId, invasion.invasionId, invasion.kind,
                    participantPlayerIds = invasion.participantPlayerIds))
                invasion.warningIssued = true
                events += event("assault_warned", invasion.invasionId)
            }
            when (invasion.phase) {
                InvasionPhase.WARNED -> invasion.phase = InvasionPhase.APPROACHING
                InvasionPhase.APPROACHING, InvasionPhase.READY_TO_MATERIALIZE ->
                    if (target?.surfaceEligible == true && available) {
                        advanceStrategic(track, invasion, elapsed, events)
                        if (invasion.phase == InvasionPhase.APPROACHING && virtualJourneyComplete(invasion)
                            && track.eligibleTicks >= invasion.scheduledArrivalEligibleTick) {
                            invasion.localApproachTicks += elapsed
                            if (invasion.localApproachTicks >= spec.rules.localApproachTimeoutTicks) {
                                invasion.lastRouteFailure = "local_approach_timeout"
                                if (invasion.kind == EncounterKind.SCOUT) {
                                    requestRetire(track, InvasionOutcome.RETIRED, events)
                                } else {
                                    // Keep assault intent alive and make the missing local route
                                    // eligible for another loaded-terrain search.
                                    invasion.localApproachTicks = 0L
                                    invasion.strategicRoute.clear()
                                    invasion.strategicRouteIndex = 0
                                    invasion.strategicFrontier = StrategicFrontier.UNKNOWN
                                    invasion.anchor = null
                                    invasion.validatedAnchor = null
                                }
                            }
                        }
                    }
                InvasionPhase.ACTIVE -> {
                    invasion.activeTicks += elapsed
                    val hardLimit = if (invasion.kind == EncounterKind.SCOUT) spec.rules.scoutActiveTicks else spec.rules.assaultActiveTicks
                    if (invasion.activeTicks >= hardLimit || state.tick - invasion.lastCombatTick >= spec.rules.activeIdleTicks) {
                        requestRetire(track, InvasionOutcome.RETIRED, events)
                    } else if (invasion.kind == EncounterKind.ASSAULT) advanceWave(invasion, events)
                }
                InvasionPhase.RETIRING -> Unit
            }
        }
    }

    private fun advanceStrategic(
        track: PlayerPressureTrack,
        invasion: InvasionState,
        elapsed: Long,
        events: MutableList<DirectorEvent>,
    ) {
        initializeVirtualJourney(invasion, track)
        val speed = if (invasion.kind == EncounterKind.SCOUT) spec.rules.scoutStrategicMilliBlocksPerTick
            else spec.rules.assaultStrategicMilliBlocksPerTick
        if (invasion.adminExpedited) {
            invasion.strategicTravelMilliBlocks = invasion.strategicJourneyTotalMilliBlocks
            invasion.strategicPosition = invasion.routeTarget
            invasion.scheduledArrivalEligibleTick = track.eligibleTicks
        }
        if (!virtualJourneyComplete(invasion)) {
            invasion.strategicTravelMilliBlocks = minOf(
                invasion.strategicJourneyTotalMilliBlocks,
                invasion.strategicTravelMilliBlocks + elapsed * speed,
            )
            updateVirtualPosition(invasion)
        }
        if (!virtualJourneyComplete(invasion) || track.eligibleTicks < localSearchEligibleTick(invasion)) return
        if (invasion.strategicRoute.isEmpty() || invasion.strategicFrontier == StrategicFrontier.UNKNOWN) return
        if (track.eligibleTicks < invasion.scheduledArrivalEligibleTick) return
        val anchor = invasion.strategicRoute.last()
        invasion.anchor = anchor
        if (invasion.usedAnchors.none { it.x == anchor.x && it.z == anchor.z }) invasion.usedAnchors += anchor
        invasion.phase = InvasionPhase.READY_TO_MATERIALIZE
        events += event("route_ready", invasion.invasionId,
            "${anchor.x},${anchor.y},${anchor.z} frontier=${invasion.strategicFrontier.name.lowercase()}")
    }

    private fun issuePackets(players: Map<String, PlayerObservation>, frame: DirectorFrame, events: MutableList<DirectorEvent>) {
        var capacity = minOf(
            spec.rules.maximumSpawnsPerTick,
            (spec.rules.maximumSpawnsPerSecond - frame.secondSpawnCount).coerceAtLeast(0),
            (spec.rules.globalCampaignMobCap - maxOf(frame.liveCampaignMobs, trackedLivePopulation())).coerceAtLeast(0),
        )
        if (capacity == 0) return
        val ready = primaryTracks().mapNotNull(PlayerPressureTrack::invasion).filter { invasion ->
            invasion.phase in setOf(InvasionPhase.READY_TO_MATERIALIZE, InvasionPhase.ACTIVE) &&
                chooseTarget(invasion, players)?.surfaceEligible == true &&
                invasion.anchor != null && state.tick >= invasion.nextPacketTick &&
                state.pendingEffects.values.none { it.invasionId == invasion.invasionId && it.kind == EffectKind.MATERIALIZE } &&
                invasion.waves[invasion.currentWave].queuedMembers < invasion.waves[invasion.currentWave].members.size
        }.sortedBy(InvasionState::invasionId)
        if (ready.isEmpty()) return
        rotate(ready, state.fairServiceCursor % ready.size).forEach { invasion ->
            if (capacity <= 0) return@forEach
            val wave = invasion.waves[invasion.currentWave]
            val packetLimit = minOf(capacity, 6 * invasion.participantPlayerIds.size)
            val members = wave.members.drop(wave.queuedMembers).take(packetLimit)
            if (members.isEmpty()) return@forEach
            val announceWave = wave.queuedMembers == 0
            wave.queuedMembers += members.size
            val spacing = spec.rules.normalPacketSpacingTicks * if (chooseTarget(invasion, players)?.lowHealth == true) 3L else 1L
            invasion.nextPacketTick = state.tick + spacing
            publish(DirectorEffect("", EffectKind.MATERIALIZE, invasion.targetPlayerId, invasion.invasionId,
                invasion.kind, invasion.currentWave, invasion.anchor, members, invasion.participantPlayerIds,
                invasion.strategicFrontier, validateApproach = invasion.validatedAnchor != invasion.anchor,
                announceWave = announceWave))
            capacity -= members.size
            events += event("packet_queued", invasion.invasionId, "wave=${invasion.currentWave} members=${members.size}")
        }
        state.fairServiceCursor = (state.fairServiceCursor + 1) % ready.size
    }

    private fun advanceWave(invasion: InvasionState, events: MutableList<DirectorEvent>) {
        val wave = invasion.waves[invasion.currentWave]
        if (wave.materializedMembers < wave.members.size || wave.startedTick < 0) return
        val defeated = wave.members.count { it.memberId in invasion.defeatedMemberIds }
        val ready = defeated >= ceil(wave.members.size / 2.0).toInt() ||
            state.tick - wave.startedTick >= spec.rules.waveProgressTimeoutTicks
        if (!ready) return
        if (invasion.currentWave + 1 < invasion.waves.size) {
            invasion.currentWave++
            invasion.phase = InvasionPhase.READY_TO_MATERIALIZE
            events += event("wave_advanced", invasion.invasionId, "wave=${invasion.currentWave}")
        }
    }

    private fun applyEffectResults(results: List<EffectResult>, events: MutableList<DirectorEvent>) {
        results.distinctBy(EffectResult::effectId).forEach { result ->
            val effect = state.pendingEffects.remove(result.effectId) ?: return@forEach
            val track = primaryTracks().firstOrNull { it.invasion?.invasionId == effect.invasionId } ?: return@forEach
            val invasion = track.invasion ?: return@forEach
            when (effect.kind) {
                EffectKind.WARN -> if (!result.successful) publish(effect.copy(effectId = ""))
                EffectKind.MATERIALIZE -> {
                    val wave = invasion.waves[effect.waveIndex]
                    if (result.successful) {
                        wave.materializedMembers += effect.members.size
                        if (effect.validateApproach) invasion.validatedAnchor = effect.anchor
                        if (wave.startedTick < 0) wave.startedTick = state.tick
                        invasion.phase = InvasionPhase.ACTIVE
                        invasion.lastCombatTick = state.tick
                        events += event("packet_materialized", invasion.invasionId,
                            "wave=${effect.waveIndex} members=${effect.members.size}")
                    } else {
                        wave.queuedMembers = (wave.queuedMembers - effect.members.size).coerceAtLeast(wave.materializedMembers)
                        invasion.anchor = null
                        invasion.strategicRoute.clear()
                        invasion.strategicRouteIndex = 0
                        invasion.strategicFrontier = StrategicFrontier.UNKNOWN
                        invasion.validatedAnchor = null
                        invasion.phase = InvasionPhase.APPROACHING
                        invasion.lastRouteFailure = "materialization_rejected"
                        events += event("materialization_failed", invasion.invasionId)
                    }
                }
                EffectKind.RETIRE -> if (result.successful)
                    resolve(track, invasion.pendingOutcome ?: InvasionOutcome.RETIRED, events)
            }
        }
    }

    private fun mergeSurfaces(observations: List<SurfaceGridObservation>) {
        observations.forEach { observation ->
            track(observation.playerId).invasion?.let { invasion ->
                if (observation.complete) invasion.observedCells.clear()
                observation.cells.forEach { invasion.observedCells[cellKey(it.x, it.z)] = it }
                invasion.surfaceObservationComplete = invasion.surfaceObservationComplete || observation.complete
                // Explicit complete grids remain a compact harness input. Production supplies a
                // loaded-terrain local approach after virtual strategic travel.
                if (observation.complete && invasion.strategicRoute.isEmpty()) {
                    val target = invasion.routeTarget ?: return@let
                    val anchor = SurfaceApproach.choose(observation.cells, target, spec.rules,
                        stableSeed(state.worldSeed, invasion.invasionId, invasion.currentWave)) ?: return@let
                    val point = BlockPoint(target.dimension, anchor.x, anchor.bodyY, anchor.z)
                    invasion.strategicRoute = mutableListOf(point)
                    invasion.strategicFrontier = StrategicFrontier.OPEN
                    invasion.lastRouteFailure = "none"
                }
            }
        }
    }

    private fun mergeStrategicRoutes(observations: List<StrategicRouteObservation>) {
        observations.sortedBy(StrategicRouteObservation::invasionId).forEach { observation ->
            val invasion = track(observation.playerId).invasion ?: return@forEach
            if (invasion.invasionId != observation.invasionId || invasion.phase != InvasionPhase.APPROACHING) return@forEach
            if (observation.target != invasion.routeTarget) return@forEach
            if (observation.route.isEmpty()) {
                invasion.strategicRoute.clear()
                invasion.strategicRouteIndex = 0
                invasion.strategicAtlasRevision = observation.atlasRevision
                invasion.strategicFrontier = StrategicFrontier.UNKNOWN
                invasion.lastRouteFailure = "no_loaded_local_route"
                return@forEach
            }
            invasion.strategicRoute = observation.route.toMutableList()
            invasion.strategicRouteIndex = 0
            invasion.strategicAtlasRevision = observation.atlasRevision
            invasion.strategicFrontier = observation.frontier
            invasion.lastRouteFailure = "none"
        }
    }

    private fun applyCombat(frame: DirectorFrame, events: MutableList<DirectorEvent>) {
        frame.combat.map(CombatObservation::invasionId).toSet().forEach { find(it)?.lastCombatTick = state.tick }
        frame.memberDefeats.distinctBy { it.invasionId to it.memberId }.forEach { defeat ->
            val invasion = find(defeat.invasionId) ?: return@forEach
            if (invasion.members.none { it.memberId == defeat.memberId }) return@forEach
            invasion.defeatedMemberIds += defeat.memberId
            invasion.lastCombatTick = state.tick
            if (invasion.defeatedMemberIds.size == invasion.members.size) {
                primaryTracks().firstOrNull { it.invasion?.invasionId == invasion.invasionId }?.let {
                    resolve(it, InvasionOutcome.CLEARED, events)
                }
            }
        }
        frame.targetDeaths.map(TargetDeathObservation::playerId).toSet().forEach { playerId ->
            primaryTracks().firstOrNull { playerId in (it.invasion?.participantPlayerIds ?: emptyList()) }?.let {
                requestRetire(it, InvasionOutcome.TARGET_DIED, events)
            }
        }

    }

    private fun requestRetire(track: PlayerPressureTrack, outcome: InvasionOutcome, events: MutableList<DirectorEvent>) {
        val invasion = track.invasion ?: return
        if (invasion.phase == InvasionPhase.RETIRING) return
        invasion.phase = InvasionPhase.RETIRING
        invasion.pendingOutcome = outcome
        state.pendingEffects.entries.removeIf { it.value.invasionId == invasion.invasionId }
        publish(DirectorEffect("", EffectKind.RETIRE, invasion.targetPlayerId, invasion.invasionId, invasion.kind,
            participantPlayerIds = invasion.participantPlayerIds))
        events += event("retire_requested", invasion.invasionId, outcome.name.lowercase())
    }

    private fun resolve(track: PlayerPressureTrack, outcome: InvasionOutcome, events: MutableList<DirectorEvent>) {
        val invasion = track.invasion ?: return
        invasion.participantPlayerIds.forEach { id ->
            val participant = track(id)
            if (invasion.kind == EncounterKind.ASSAULT) {
                when (outcome) {
                    InvasionOutcome.CLEARED ->
                        participant.outcomeAdjustment = (participant.outcomeAdjustment + 1).coerceAtMost(2)
                    InvasionOutcome.TARGET_DIED ->
                        participant.outcomeAdjustment = (participant.outcomeAdjustment - 1).coerceAtLeast(-2)
                    InvasionOutcome.RETIRED -> Unit
                }
            }
            participant.joinedInvasionId = null
            schedule(participant, invasion.kind, if (outcome == InvasionOutcome.TARGET_DIED) spec.rules.deathGraceTicks else 0)
            if (invasion.kind == EncounterKind.ASSAULT) schedule(participant, EncounterKind.SCOUT)
        }
        track.invasion = null
        state.pendingEffects.entries.removeIf { it.value.invasionId == invasion.invasionId }
        events += event("resolved", invasion.invasionId, outcome.name.lowercase())
    }

    private fun applyCommands(commands: List<DirectorCommand>, events: MutableList<DirectorEvent>) {
        commands.forEach { command ->
            when (command) {
                is DirectorCommand.Force -> track(command.playerId).let {
                    require(command.intensity == null || command.intensity in 0..spec.rules.maximumIntensity)
                    if (command.kind == EncounterKind.SCOUT) it.nextScoutEligibleTick = it.eligibleTicks
                    else it.nextAssaultEligibleTick = it.eligibleTicks
                    if (command.expediteTravel) it.expediteNextEncounter = command.kind
                    if (command.intensity != null) it.forcedIntensity = command.intensity
                    events += event("forced", command.playerId, command.kind.name.lowercase())
                }
                is DirectorCommand.Reset -> if (command.playerId == null) {
                    state.tracks.clear()
                    state.pendingEffects.clear()
                    events += event("reset", "all")
                } else {
                    val id = state.tracks[command.playerId]?.invasion?.invasionId ?: state.tracks[command.playerId]?.joinedInvasionId
                    if (id != null) {
                        primaryTracks().firstOrNull { it.invasion?.invasionId == id }?.invasion?.participantPlayerIds?.forEach {
                            state.tracks[it]?.joinedInvasionId = null
                            state.tracks[it]?.invasion = null
                        }
                        state.pendingEffects.entries.removeIf { it.value.invasionId == id }
                    }
                    state.tracks.remove(command.playerId)
                    events += event("reset", command.playerId)
                }
            }
        }
    }

    private fun schedule(track: PlayerPressureTrack, kind: EncounterKind, grace: Long = 0L) {
        val (min, max) = if (kind == EncounterKind.SCOUT)
            spec.rules.scoutWindowMinTicks to spec.rules.scoutWindowMaxTicks
        else spec.rules.assaultWindowMinTicks to spec.rules.assaultWindowMaxTicks
        val random = Random(stableSeed(state.worldSeed, track.playerId, track.encounterSequence, kind))
        val delay = if (min == max) min else random.nextLong(min, max + 1)
        val due = track.eligibleTicks + maxOf(grace, delay)
        if (kind == EncounterKind.SCOUT) track.nextScoutEligibleTick = due else track.nextAssaultEligibleTick = due
    }

    private fun dispatchLead(kind: EncounterKind): Long {
        val speed = if (kind == EncounterKind.SCOUT) spec.rules.scoutStrategicMilliBlocksPerTick
            else spec.rules.assaultStrategicMilliBlocksPerTick
        return (spec.rules.strategicOriginMaximumBlocks * 1_000L + speed - 1L) / speed
    }
    private fun shouldWarn(track: PlayerPressureTrack, invasion: InvasionState): Boolean {
        if (invasion.strategicRoute.isEmpty() || invasion.strategicFrontier == StrategicFrontier.UNKNOWN) return false
        return invasion.scheduledArrivalEligibleTick - track.eligibleTicks <= spec.rules.assaultWarningSurfaceTicks
    }

    private fun initializeVirtualJourney(invasion: InvasionState, track: PlayerPressureTrack) {
        if (invasion.strategicJourneyTotalMilliBlocks > 0L) return
        val target = invasion.routeTarget ?: return
        val random = Random(stableSeed(state.worldSeed, invasion.invasionId, "virtual_origin"))
        val distance = random.nextInt(
            spec.rules.strategicOriginMinimumBlocks,
            spec.rules.strategicOriginMaximumBlocks + 1,
        )
        val angle = random.nextDouble() * Math.PI * 2.0
        val origin = BlockPoint(
            target.dimension,
            target.x + (cos(angle) * distance).roundToInt(),
            target.y,
            target.z + (sin(angle) * distance).roundToInt(),
        )
        invasion.strategicOrigin = origin
        invasion.strategicPosition = origin
        invasion.strategicJourneyTotalMilliBlocks = maxOf(
            1_000L,
            (hypot((target.x - origin.x).toDouble(), (target.z - origin.z).toDouble()) * 1_000.0).roundToInt().toLong(),
        )
        val speed = if (invasion.kind == EncounterKind.SCOUT) spec.rules.scoutStrategicMilliBlocksPerTick
            else spec.rules.assaultStrategicMilliBlocksPerTick
        val remainingToLocalSearch = (localSearchEligibleTick(invasion) - track.eligibleTicks).coerceAtLeast(0L)
        invasion.strategicTravelMilliBlocks = (
            invasion.strategicJourneyTotalMilliBlocks - remainingToLocalSearch * speed
        ).coerceAtLeast(0L)
        updateVirtualPosition(invasion)
        if (remainingToLocalSearch == 0L || invasion.adminExpedited) {
            invasion.strategicTravelMilliBlocks = invasion.strategicJourneyTotalMilliBlocks
            invasion.strategicPosition = target
        }
    }

    private fun updateVirtualPosition(invasion: InvasionState) {
        val origin = invasion.strategicOrigin ?: return
        val target = invasion.routeTarget ?: return
        val total = invasion.strategicJourneyTotalMilliBlocks.coerceAtLeast(1L)
        val fraction = invasion.strategicTravelMilliBlocks.toDouble() / total.toDouble()
        invasion.strategicPosition = BlockPoint(
            target.dimension,
            (origin.x + (target.x - origin.x) * fraction).roundToInt(),
            target.y,
            (origin.z + (target.z - origin.z) * fraction).roundToInt(),
        )
    }

    private fun virtualJourneyComplete(invasion: InvasionState): Boolean =
        invasion.strategicJourneyTotalMilliBlocks > 0L &&
            invasion.strategicTravelMilliBlocks >= invasion.strategicJourneyTotalMilliBlocks

    private fun localSearchEligibleTick(invasion: InvasionState): Long =
        if (invasion.kind == EncounterKind.ASSAULT)
            (invasion.scheduledArrivalEligibleTick - spec.rules.assaultWarningSurfaceTicks).coerceAtLeast(0L)
        else invasion.scheduledArrivalEligibleTick
    private fun due(track: PlayerPressureTrack, kind: EncounterKind) =
        if (kind == EncounterKind.SCOUT) track.nextScoutEligibleTick else track.nextAssaultEligibleTick
    private fun track(id: String) = state.tracks.getOrPut(id) { PlayerPressureTrack(id) }
    private fun primaryTracks() = state.tracks.values.filter { it.invasion != null }
    private fun trackedLivePopulation() = primaryTracks().sumOf { track ->
        track.invasion!!.waves.sumOf { it.materializedMembers } - track.invasion!!.defeatedMemberIds.size
    }
    private fun find(id: String) = primaryTracks().firstNotNullOfOrNull {
        it.invasion?.takeIf { invasion -> invasion.invasionId == id }
    }
    private fun participantInAssault(id: String) = primaryTracks().any {
        it.invasion?.kind == EncounterKind.ASSAULT && id in it.invasion!!.participantPlayerIds
    }
    private fun chooseTarget(invasion: InvasionState, players: Map<String, PlayerObservation>): PlayerObservation? =
        players[invasion.targetPlayerId]?.takeIf { it.eligible && it.physicallyAvailable }
            ?: invasion.participantPlayerIds.asSequence().mapNotNull(players::get)
                .filter { it.eligible && it.physicallyAvailable }
                .minWithOrNull(compareBy<PlayerObservation> { distanceSquared(it.position, invasion.routeTarget) }
                    .thenBy { it.playerId })

    private fun publish(effect: DirectorEffect) {
        val id = "effect:${++state.effectSequence}"
        state.pendingEffects[id] = effect.copy(effectId = id)
    }
    private fun event(type: String, subject: String, detail: String = "") = DirectorEvent(state.tick, type, subject, detail)

    companion object {
        private val JSON = Json { encodeDefaults = true }
        fun create(worldSeed: Long, spec: InvasionRuntimeSpec) = restore(DirectorSnapshot(worldSeed = worldSeed), spec)
        fun restore(snapshot: DirectorSnapshot, spec: InvasionRuntimeSpec): InvasionDirector {
            spec.requireValid()
            require(snapshot.schemaVersion == DirectorSnapshot.CURRENT_SCHEMA_VERSION)
            return InvasionDirector(copy(snapshot), copy(spec))
        }
        private fun copy(snapshot: DirectorSnapshot): DirectorSnapshot = JSON.decodeFromString(JSON.encodeToString(snapshot))
        private fun copy(spec: InvasionRuntimeSpec): InvasionRuntimeSpec = JSON.decodeFromString(JSON.encodeToString(spec))
        private fun copyEffect(effect: DirectorEffect) = effect.copy(
            members = effect.members.toList(), participantPlayerIds = effect.participantPlayerIds.toList())
        internal fun stableSeed(vararg values: Any): Long = values.fold(-3750763034362895579L) { hash, value ->
            value.toString().fold(hash) { next, character -> (next xor character.code.toLong()) * 1099511628211L }
        }
        private fun cellKey(x: Int, z: Int) = "$x:$z"
        private fun moved(a: BlockPoint, b: BlockPoint) =
            a.dimension != b.dimension || maxOf(abs(a.x - b.x), abs(a.z - b.z)) > 16
        private fun close(a: PlayerObservation, b: PlayerObservation, radius: Int): Boolean {
            val pa = a.position ?: return false
            val pb = b.position ?: return false
            return pa.dimension == pb.dimension && distanceSquared(pa, pb) <= radius.toLong() * radius
        }
        private fun distanceSquared(a: BlockPoint?, b: BlockPoint?): Long {
            if (a == null || b == null || a.dimension != b.dimension) return Long.MAX_VALUE
            val dx = (a.x - b.x).toLong()
            val dz = (a.z - b.z).toLong()
            return dx * dx + dz * dz
        }
        private fun <T> rotate(values: List<T>, offset: Int) = values.drop(offset) + values.take(offset)
    }
}

object EncounterPolicy {
    fun memberCount(kind: EncounterKind, intensity: Int, players: Int, rules: InvasionRules): Int {
        require(players > 0)
        return when (kind) {
            EncounterKind.SCOUT -> (3 + 3 * intensity / rules.maximumIntensity.coerceAtLeast(1) +
                2 * (players - 1)).coerceAtMost(rules.scoutGroupCap)
            EncounterKind.ASSAULT -> {
                val base = rules.assaultMinimumMembers +
                    (rules.assaultMaximumMembers - rules.assaultMinimumMembers) * intensity /
                    rules.maximumIntensity.coerceAtLeast(1)
                ceil(base * (1.0 + 0.5 * (players - 1))).toInt().coerceAtMost(rules.assaultGroupCap)
            }
        }
    }
    fun waveBudget(size: Int, intensity: Int, waveIndex: Int) =
        (size * (2.25 + 0.25 * intensity + 0.25 * waveIndex)).roundToInt()
}

object RosterPlanner {
    fun planWave(
        recruits: List<RecruitSpec>, kind: EncounterKind, intensity: Int, waveIndex: Int,
        memberCount: Int, encounterId: String, worldSeed: Long,
    ): WavePlan {
        val eligible = recruits.filter { it.unlockIntensity <= intensity }.sortedBy(RecruitSpec::entityId)
        require(eligible.isNotEmpty())
        val allowedRoles = when {
            kind == EncounterKind.SCOUT -> setOf(RecruitRole.LINE, RecruitRole.RANGED)
            waveIndex == 0 -> setOf(RecruitRole.LINE, RecruitRole.RANGED)
            else -> RecruitRole.entries.toSet()
        }
        val available = eligible.filter { it.role in allowedRoles }
        require(available.isNotEmpty()) { "no eligible recruits for $kind wave $waveIndex" }
        val budget = if (kind == EncounterKind.SCOUT) memberCount * 3
            else EncounterPolicy.waveBudget(memberCount, intensity, waveIndex)
        val random = Random(InvasionDirector.stableSeed(worldSeed, encounterId, waveIndex))
        val chosen = mutableListOf<RecruitSpec>()
        var remaining = budget
        fun add(candidates: List<RecruitSpec>): Boolean {
            val cheapest = available.minOf(RecruitSpec::cost)
            val slotsAfter = memberCount - chosen.size - 1
            val usable = candidates.filter { recruit ->
                recruit.cost + cheapest * slotsAfter <= remaining &&
                    chosen.count { it.entityId == recruit.entityId } < recruit.maximumPerSquad
            }
            if (usable.isEmpty()) return false
            val selected = weighted(usable, random)
            chosen += selected
            remaining -= selected.cost
            return true
        }
        if (kind == EncounterKind.ASSAULT && waveIndex == 2 && intensity >= 3)
            add(available.filter { it.role == RecruitRole.ELITE && (it.entityId != "minecraft:ravager" || intensity >= 5) })
        if (kind == EncounterKind.ASSAULT && waveIndex >= 1)
            add(available.filter { it.role == RecruitRole.FRONTLINE })
        if (waveIndex == 1) add(available.filter { it.role == RecruitRole.SUPPORT })
        while (chosen.size < memberCount) {
            val candidates = available.filter { candidate ->
                (candidate.role != RecruitRole.SUPPORT || chosen.none { it.role == RecruitRole.SUPPORT }) &&
                    (candidate.role != RecruitRole.ELITE || chosen.none { it.role == RecruitRole.ELITE }) &&
                    (candidate.entityId != "minecraft:ravager" || intensity >= 5)
            }
            if (!add(candidates)) break
        }
        require(chosen.size == memberCount) { "budget $budget cannot form $memberCount-member $kind wave $waveIndex" }
        val members = chosen.mapIndexed { index, recruit ->
            MemberPlan("wave:$waveIndex:member:${index + 1}", recruit.entityId, recruit.cost, waveIndex)
        }
        require(members.sumOf(MemberPlan::cost) <= budget)
        return WavePlan(waveIndex, budget, members)
    }

    private fun weighted(candidates: List<RecruitSpec>, random: Random): RecruitSpec {
        var cursor = random.nextInt(candidates.sumOf(RecruitSpec::weight))
        candidates.forEach { candidate ->
            cursor -= candidate.weight
            if (cursor < 0) return candidate
        }
        return candidates.last()
    }
}

object SurfaceApproach {
    fun choose(cells: Collection<SurfaceCell>, target: BlockPoint, rules: InvasionRules, seed: Long): SurfaceCell? {
        val passable = cells.filter(SurfaceCell::passable).associateBy { it.x to it.z }
        val origins = passable.values.filter {
            chebyshev(it, target) in rules.approachMinimumBlocks..rules.approachMaximumBlocks
        }.sortedWith(compareByDescending<SurfaceCell> { chebyshev(it, target) }
            .thenBy { stableRank(it, seed) }.thenBy { it.x }.thenBy { it.z })
        data class Node(val cell: SurfaceCell, val origin: SurfaceCell)
        origins.forEach originLoop@ { origin ->
            val queue = ArrayDeque<Node>()
            val seen = hashSetOf(origin.x to origin.z)
            queue += Node(origin, origin)
            var expansions = 0
            while (queue.isNotEmpty() && expansions < rules.maximumSearchExpansions) {
                expansions++
                val node = queue.removeFirst()
                if (chebyshev(node.cell, target) <= rules.approachTargetRadiusBlocks) return node.origin
                CARDINALS.forEach neighbors@ { (dx, dz) ->
                    val next = passable[node.cell.x + dx to node.cell.z + dz] ?: return@neighbors
                    val rise = next.bodyY - node.cell.bodyY
                    if (rise > 1 || rise < -2 || !seen.add(next.x to next.z)) return@neighbors
                    queue += Node(next, origin)
                }
            }
        }
        return null
    }
    private val CARDINALS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    private fun chebyshev(cell: SurfaceCell, target: BlockPoint) =
        maxOf(abs(cell.x - target.x), abs(cell.z - target.z))
    private fun stableRank(cell: SurfaceCell, seed: Long) =
        (seed xor (cell.x.toLong() shl 32) xor cell.z.toLong()) * -7046029254386353131L
}
