package com.bettercontent.betterpillagercampaigns.core

import kotlinx.serialization.Serializable

@Serializable
data class InvasionRules(
    val scoutWindowMinTicks: Long = 7_200L,
    val scoutWindowMaxTicks: Long = 14_400L,
    val assaultWindowMinTicks: Long = 72_000L,
    val assaultWindowMaxTicks: Long = 144_000L,
    val assaultWarningSurfaceTicks: Long = 2_400L,
    val deathGraceTicks: Long = 24_000L,
    val timeTierTicks: Long = 144_000L,
    val maximumIntensity: Int = 5,
    val scoutMinimumMembers: Int = 3,
    val scoutMaximumMembers: Int = 6,
    val scoutGroupCap: Int = 12,
    val assaultMinimumMembers: Int = 16,
    val assaultMaximumMembers: Int = 24,
    val assaultGroupCap: Int = 48,
    val groupRadiusBlocks: Int = 64,
    val assaultWaves: Int = 3,
    val waveProgressTimeoutTicks: Long = 1_500L,
    val scoutActiveTicks: Long = 2_400L,
    val assaultActiveTicks: Long = 14_400L,
    val activeIdleTicks: Long = 6_000L,
    val targetUnavailableTicks: Long = 1_200L,
    val approachMinimumBlocks: Int = 48,
    val approachMaximumBlocks: Int = 72,
    val approachTargetRadiusBlocks: Int = 12,
    val maximumSearchExpansions: Int = 4_096,
    val localApproachRetryTicks: Long = 100L,
    val localApproachTimeoutTicks: Long = 2_400L,
    val strategicOriginMinimumBlocks: Int = 512,
    val strategicOriginMaximumBlocks: Int = 768,
    val strategicMaximumSearchExpansions: Int = 262_144,
    val scoutStrategicMilliBlocksPerTick: Int = 80,
    val assaultStrategicMilliBlocksPerTick: Int = 50,
    val globalCampaignMobCap: Int = 96,
    val maximumSpawnsPerTick: Int = 8,
    val maximumSpawnsPerSecond: Int = 24,
    val normalPacketSpacingTicks: Long = 20L,
) {
    init {
        require(scoutWindowMinTicks in 0..scoutWindowMaxTicks)
        require(assaultWindowMinTicks in 0..assaultWindowMaxTicks)
        require(assaultWarningSurfaceTicks >= 0 && deathGraceTicks >= 0 && timeTierTicks > 0)
        require(maximumIntensity >= 0)
        require(scoutMinimumMembers in 1..scoutMaximumMembers && scoutMaximumMembers <= scoutGroupCap)
        require(assaultMinimumMembers in 1..assaultMaximumMembers && assaultMaximumMembers <= assaultGroupCap)
        require(groupRadiusBlocks > 0 && assaultWaves == 3 && waveProgressTimeoutTicks > 0)
        require(scoutActiveTicks > 0 && assaultActiveTicks > 0 && activeIdleTicks > 0 && targetUnavailableTicks > 0)
        require(approachMinimumBlocks in 1..approachMaximumBlocks && approachTargetRadiusBlocks > 0 && maximumSearchExpansions > 0)
        require(localApproachRetryTicks > 0 && localApproachTimeoutTicks > 0)
        require(strategicOriginMinimumBlocks > approachMaximumBlocks)
        require(strategicOriginMinimumBlocks <= strategicOriginMaximumBlocks && strategicMaximumSearchExpansions > 0)
        require(scoutStrategicMilliBlocksPerTick > 0 && assaultStrategicMilliBlocksPerTick > 0)
        require(globalCampaignMobCap > 0 && maximumSpawnsPerTick > 0 && maximumSpawnsPerSecond >= maximumSpawnsPerTick)
        require(normalPacketSpacingTicks > 0)
    }
}

@Serializable enum class RecruitRole { LINE, RANGED, FRONTLINE, SUPPORT, ELITE }

@Serializable
data class RecruitSpec(
    val entityId: String,
    val cost: Int,
    val unlockIntensity: Int,
    val role: RecruitRole,
    val weight: Int = 1,
    val maximumPerSquad: Int = 24,
    val optional: Boolean = false,
)

@Serializable data class BlockPoint(val dimension: String, val x: Int, val y: Int, val z: Int)

@Serializable
data class SurfaceCell(val x: Int, val bodyY: Int, val z: Int, val passable: Boolean = true)

@Serializable data class SurfaceGridObservation(val playerId: String, val cells: List<SurfaceCell>, val complete: Boolean = false)

@Serializable enum class StrategicFrontier { OPEN, DEFENSE, UNKNOWN }

@Serializable
data class StrategicRouteObservation(
    val playerId: String,
    val invasionId: String,
    val target: BlockPoint,
    val atlasRevision: Long,
    val route: List<BlockPoint>,
    val frontier: StrategicFrontier,
)

@Serializable
data class PlayerObservation(
    val playerId: String,
    val eligible: Boolean,
    val surfaceEligible: Boolean,
    val physicallyAvailable: Boolean,
    val position: BlockPoint? = null,
    val lowHealth: Boolean = false,
)

@Serializable enum class EncounterKind { SCOUT, ASSAULT }
@Serializable enum class InvasionPhase { WARNED, APPROACHING, READY_TO_MATERIALIZE, ACTIVE, RETIRING }
@Serializable enum class InvasionOutcome { CLEARED, TARGET_DIED, RETIRED }

@Serializable data class MemberPlan(val memberId: String, val recruitId: String, val cost: Int, val waveIndex: Int = 0)

@Serializable
data class WavePlan(
    val waveIndex: Int,
    val budget: Int,
    val members: List<MemberPlan>,
    var queuedMembers: Int = 0,
    var materializedMembers: Int = 0,
    var startedTick: Long = -1L,
)

@Serializable
data class InvasionState(
    val invasionId: String,
    val kind: EncounterKind,
    val targetPlayerId: String,
    val participantPlayerIds: List<String>,
    val intensity: Int,
    val waves: List<WavePlan>,
    var currentWave: Int = 0,
    var phase: InvasionPhase = InvasionPhase.WARNED,
    var warningSurfaceTicks: Long = 0L,
    var activeTicks: Long = 0L,
    var lastCombatTick: Long = 0L,
    var targetUnavailableTicks: Long = 0L,
    var nextPacketTick: Long = 0L,
    var routeTarget: BlockPoint? = null,
    val defeatedMemberIds: MutableSet<String> = linkedSetOf(),
    val observedCells: MutableMap<String, SurfaceCell> = linkedMapOf(),
    var surfaceObservationComplete: Boolean = false,
    var anchor: BlockPoint? = null,
    val usedAnchors: MutableList<BlockPoint> = mutableListOf(),
    var pendingOutcome: InvasionOutcome? = null,
    var scheduledArrivalEligibleTick: Long = 0L,
    var warningIssued: Boolean = false,
    var strategicOrigin: BlockPoint? = null,
    var strategicPosition: BlockPoint? = null,
    var strategicRoute: MutableList<BlockPoint> = mutableListOf(),
    var strategicRouteIndex: Int = 0,
    var strategicTravelMilliBlocks: Long = 0L,
    var strategicJourneyTotalMilliBlocks: Long = 0L,
    var localApproachTicks: Long = 0L,
    var lastRouteFailure: String = "none",
    var strategicAtlasRevision: Long = -1L,
    var strategicFrontier: StrategicFrontier = StrategicFrontier.UNKNOWN,
    var validatedAnchor: BlockPoint? = null,
    var adminExpedited: Boolean = false,
) {
    val members: List<MemberPlan> get() = waves.flatMap(WavePlan::members)
    val threatBudget: Int get() = waves.sumOf(WavePlan::budget)
}

@Serializable
data class PlayerPressureTrack(
    val playerId: String,
    var eligibleTicks: Long = 0L,
    var nextScoutEligibleTick: Long = -1L,
    var nextAssaultEligibleTick: Long = -1L,
    var encounterSequence: Long = 0L,
    var outcomeAdjustment: Int = 0,
    var invasion: InvasionState? = null,
    var joinedInvasionId: String? = null,
    var expediteNextEncounter: EncounterKind? = null,
    var forcedIntensity: Int? = null,
)

@Serializable
data class DirectorSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val worldSeed: Long,
    var tick: Long = 0L,
    var effectSequence: Long = 0L,
    var fairServiceCursor: Int = 0,
    val tracks: MutableMap<String, PlayerPressureTrack> = linkedMapOf(),
    val pendingEffects: MutableMap<String, DirectorEffect> = linkedMapOf(),
) {
    companion object { const val CURRENT_SCHEMA_VERSION: Int = 4 }
}

@Serializable enum class EffectKind { WARN, MATERIALIZE, RETIRE }

@Serializable
data class DirectorEffect(
    val effectId: String,
    val kind: EffectKind,
    val playerId: String,
    val invasionId: String,
    val encounterKind: EncounterKind = EncounterKind.ASSAULT,
    val waveIndex: Int = 0,
    val anchor: BlockPoint? = null,
    val members: List<MemberPlan> = emptyList(),
    val participantPlayerIds: List<String> = emptyList(),
    val strategicFrontier: StrategicFrontier = StrategicFrontier.OPEN,
    val validateApproach: Boolean = true,
    val announceWave: Boolean = false,
)

@Serializable data class EffectResult(val effectId: String, val successful: Boolean = true)
@Serializable data class MemberDefeatObservation(val invasionId: String, val memberId: String)
@Serializable data class CombatObservation(val invasionId: String)
@Serializable data class TargetDeathObservation(val playerId: String)

@Serializable
sealed interface DirectorCommand {
    @Serializable data class Force(
        val playerId: String,
        val kind: EncounterKind = EncounterKind.ASSAULT,
        val expediteTravel: Boolean = false,
        val intensity: Int? = null,
    ) : DirectorCommand
    @Serializable data class Reset(val playerId: String? = null) : DirectorCommand
}

@Serializable
data class DirectorFrame(
    val elapsedTicks: Long,
    val players: List<PlayerObservation> = emptyList(),
    val surfaces: List<SurfaceGridObservation> = emptyList(),
    val effectResults: List<EffectResult> = emptyList(),
    val memberDefeats: List<MemberDefeatObservation> = emptyList(),
    val combat: List<CombatObservation> = emptyList(),
    val targetDeaths: List<TargetDeathObservation> = emptyList(),
    val commands: List<DirectorCommand> = emptyList(),
    val liveCampaignMobs: Int = 0,
    val secondSpawnCount: Int = 0,
    val strategicRoutes: List<StrategicRouteObservation> = emptyList(),
)

data class DirectorTransition(val events: List<DirectorEvent>, val effects: List<DirectorEffect>)
@Serializable data class DirectorEvent(val tick: Long, val type: String, val subjectId: String, val detail: String = "")
