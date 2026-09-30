package com.bettercontent.betterpillagercampaigns.system

import com.bettercontent.betterpillagercampaigns.CampaignHarnessCommands
import com.bettercontent.betterpillagercampaigns.core.*
import com.bettercontent.betterpillagercampaigns.data.PillagerWorldData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.nbt.CompoundTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvasionForgeRulesTest {
    @Test fun `interactive harness commands are absent from ordinary launches`() {
        assertFalse(CampaignHarnessCommands.enabled())
    }

    @Test fun `local route bounds may use the approach band without weakening strategic rules`() {
        val production = InvasionRules()
        val local = StrategicRoutePlanner.Rules(
            approachMinimumBlocks = production.approachMinimumBlocks,
            approachMaximumBlocks = production.approachMaximumBlocks,
            approachTargetRadiusBlocks = production.approachTargetRadiusBlocks,
            originMinimumBlocks = production.approachMinimumBlocks,
            originMaximumBlocks = production.approachMaximumBlocks,
            maximumSearchExpansions = production.maximumSearchExpansions,
        )
        assertEquals(production.approachMinimumBlocks, local.originMinimumBlocks)
        assertEquals(production.approachMaximumBlocks, local.originMaximumBlocks)
        assertTrue(local.maximumSearchExpansions > 0)
    }

    @Test fun `authored roster has bounded explicit progression and vanilla fallback`() {
        val roster = InvasionRoster.authored()
        assertTrue(roster.any { it.entityId == "minecraft:pillager" && !it.optional && it.unlockIntensity == 0 })
        assertTrue(roster.any { it.entityId == "minecraft:ravager" && it.unlockIntensity == 5 && it.maximumPerSquad == 1 })
        assertTrue(roster.filter { it.role in setOf(RecruitRole.SUPPORT, RecruitRole.ELITE) }.all { it.maximumPerSquad == 1 })
        assertEquals(roster.size, roster.map { it.entityId }.distinct().size)
        assertTrue(roster.all { it.cost > 0 && it.unlockIntensity in 0..5 })
    }

    @Test fun `surface work begins with connected cardinal corridors`() {
        val offsets = SurfaceGridSampler.orderedOffsets(4)
        assertEquals(81, offsets.size)
        assertEquals(-4 to -2, offsets.first())
        assertTrue((-4 to 0) in offsets)
        assertTrue((4 to 0) in offsets)
        assertTrue((0 to -4) in offsets)
        assertTrue((0 to 4) in offsets)
    }

    @Test fun `member placement expands in stable square rings`() {
        val offsets = SurfaceGridSampler.memberOffsets(2)
        assertEquals(0 to 0, offsets.first())
        assertEquals(25, offsets.size)
        assertEquals(9, offsets.takeWhile { maxOf(kotlin.math.abs(it.first), kotlin.math.abs(it.second)) <= 1 }.size)
    }

    @Test fun `assault warning reaches the primary and every grouped participant once`() {
        val effect = DirectorEffect(
            "warning", EffectKind.WARN, "primary", "assault:group", EncounterKind.ASSAULT,
            participantPlayerIds = listOf("nearby-b", "primary", "nearby-a", "nearby-b"),
        )
        assertEquals(listOf("primary", "nearby-b", "nearby-a"), InvasionRuntime.warningPlayerIds(effect))
    }

    @Test fun `schema two migration preserves pressure clocks but clears active legacy encounters`() {
        val track = PlayerPressureTrack("player", eligibleTicks = 12_345, nextScoutEligibleTick = 20_000,
            nextAssaultEligibleTick = 90_000, encounterSequence = 7, outcomeAdjustment = 2,
            invasion = InvasionState("legacy", EncounterKind.SCOUT, "player", listOf("player"), 2,
                listOf(WavePlan(0, 3, listOf(MemberPlan("member", "minecraft:pillager", 2))))))
        val old = DirectorSnapshot(schemaVersion = 2, worldSeed = 41, tracks = linkedMapOf("player" to track))
        val tag = CompoundTag().also {
            it.putInt("schema", 2)
            it.putString("runtimeRevision", "legacy")
            it.putString("snapshot", Json { encodeDefaults = true }.encodeToString(old))
        }
        val migrated = PillagerWorldData.load(tag, 41).snapshot().tracks.getValue("player")
        assertEquals(12_345, migrated.eligibleTicks)
        assertEquals(20_000, migrated.nextScoutEligibleTick)
        assertEquals(90_000, migrated.nextAssaultEligibleTick)
        assertEquals(7, migrated.encounterSequence)
        assertEquals(2, migrated.outcomeAdjustment)
        assertTrue(migrated.invasion == null && migrated.joinedInvasionId == null)
    }

    @Test fun `schema three migration retains campaign roster but resets retired distant route`() {
        val invasion = InvasionState("legacy", EncounterKind.ASSAULT, "player", listOf("player"), 2,
            listOf(WavePlan(0, 6, listOf(MemberPlan("member", "minecraft:pillager", 2)))),
            phase = InvasionPhase.ACTIVE,
            routeTarget = BlockPoint("minecraft:overworld", 0, 64, 0),
            anchor = BlockPoint("minecraft:overworld", 64, 64, 0),
            strategicOrigin = BlockPoint("minecraft:overworld", 600, 64, 0),
            strategicPosition = BlockPoint("minecraft:overworld", 64, 64, 0),
            strategicRoute = mutableListOf(BlockPoint("minecraft:overworld", 600, 64, 0)),
            strategicTravelMilliBlocks = 1234,
            strategicFrontier = StrategicFrontier.OPEN,
            validatedAnchor = BlockPoint("minecraft:overworld", 64, 64, 0))
        val old = DirectorSnapshot(schemaVersion = 3, worldSeed = 42,
            tracks = linkedMapOf("player" to PlayerPressureTrack("player", invasion = invasion)))
        val tag = CompoundTag().also {
            it.putInt("schema", 3)
            it.putString("runtimeRevision", "legacy")
            it.putString("snapshot", Json { encodeDefaults = true }.encodeToString(old))
        }

        val migrated = PillagerWorldData.load(tag, 42).snapshot().tracks.getValue("player").invasion!!
        assertEquals(InvasionPhase.APPROACHING, migrated.phase)
        assertEquals("migrated_schema_3", migrated.lastRouteFailure)
        assertTrue(migrated.strategicRoute.isEmpty())
        assertTrue(migrated.anchor == null && migrated.validatedAnchor == null)
        assertEquals(1, migrated.waves.single().members.size)
    }
}
