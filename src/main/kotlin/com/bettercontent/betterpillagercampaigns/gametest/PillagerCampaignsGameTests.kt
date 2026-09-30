package com.bettercontent.betterpillagercampaigns.gametest

import com.bettercontent.betterpillagercampaigns.PillagerCampaignsMod
import com.bettercontent.betterpillagercampaigns.PillagerCampaignsEvents
import com.bettercontent.betterpillagercampaigns.core.*
import com.bettercontent.betterpillagercampaigns.system.InjuryCompat
import com.bettercontent.betterpillagercampaigns.system.InvasionRoster
import com.bettercontent.betterpillagercampaigns.system.InvasionRuntime
import com.bettercontent.betterpillagercampaigns.system.CampaignTconLoadouts
import com.bettercontent.betterpillagercampaigns.system.SurfaceGridSampler
import com.bettercontent.betterpillagercampaigns.data.TerrainAtlasData
import com.bettercontent.betterpillagercampaigns.data.PillagerWorldData
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.projectile.AbstractArrow
import net.minecraft.world.level.GameType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.server.level.TicketType
import net.minecraftforge.common.util.FakePlayer
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import java.util.UUID
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableCrossbowItem

@GameTestHolder(PillagerCampaignsMod.MOD_ID)
@PrefixGameTestTemplate(false)
object PillagerCampaignsGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 260)
    fun everyScoutArchetypeRetaliatesForTenSeconds(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        for (x in 0..28) for (z in 0..14) {
            val pos = base.offset(x, 0, z)
            helper.level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState())
            helper.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
            helper.level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState())
        }
        val primary = ServerPlayer(helper.level.server, helper.level,
            GameProfile(UUID.nameUUIDFromBytes("campaign-test-scout-primary".toByteArray()), "campaign-test-scout-primary"))
        val connection = Connection(PacketFlow.SERVERBOUND)
        val channel = EmbeddedChannel(connection)
        helper.level.server.playerList.placeNewPlayer(connection, primary)
        primary.setGameMode(GameType.SURVIVAL)
        primary.abilities.invulnerable = true
        primary.moveTo(base.x + 3.5, base.y.toDouble(), base.z + 7.5)

        val attacker = EntityType.ZOMBIE.create(helper.level)!!
        attacker.moveTo(base.x + 23.5, base.y.toDouble(), base.z + 7.5)
        attacker.isNoAi = true
        attacker.isInvulnerable = true
        helper.level.addFreshEntity(attacker)
        val scouts = listOf("minecraft:pillager", "takesapillage:archer", "takesapillage:skirmisher")
            .mapIndexed { index, id ->
                val type = ForgeRegistries.ENTITY_TYPES.getValue(net.minecraft.resources.ResourceLocation.tryParse(id))!!
                val mob = type.create(helper.level) as Mob
                mob.moveTo(base.x + 5.5, base.y.toDouble(), base.z + 5.5 + index)
                mob.finalizeSpawn(helper.level, helper.level.getCurrentDifficultyAt(mob.blockPosition()),
                    MobSpawnType.EVENT, null, null)
                helper.level.addFreshEntity(mob)
                mob.setOnGround(true)
                mob.persistentData.putString(InvasionRuntime.INVASION_TAG, "scout-retaliation-proof")
                mob.persistentData.putString(InvasionRuntime.KIND_TAG, "scout")
                mob.persistentData.putUUID(InvasionRuntime.TARGET_TAG, primary.uuid)
                if (mob.type == EntityType.PILLAGER) CampaignTconLoadouts.apply(mob)
                InvasionRuntime.installCombatGoal(mob)
                mob.target = primary
                InvasionRuntime.recordScoutHit(mob, attacker)
                mob.navigation.moveTo(base.x + 2.5, base.y.toDouble(), base.z + 7.5, 1.0)
                InvasionRuntime.maintainTarget(mob)
                helper.assertTrue(mob.target === attacker, "$id must target its attacker while moving away")
                mob
            }
        var archerFired = false
        var skirmisherApproached = false
        helper.onEachTick {
            archerFired = archerFired || helper.level.getEntitiesOfClass(AbstractArrow::class.java,
                scouts[1].boundingBox.inflate(35.0)).any { it.owner === scouts[1] }
            skirmisherApproached = skirmisherApproached || scouts[2].x > base.x + 9.5
        }
        helper.runAfterDelay(190) {
            scouts.forEach { mob ->
                InvasionRuntime.maintainTarget(mob)
                helper.assertTrue(mob.target === attacker,
                    "${ForgeRegistries.ENTITY_TYPES.getKey(mob.type)} must keep retaliating before 200 ticks")
            }
            helper.assertTrue(archerFired, "The scout archer must fire at its attacker")
            helper.assertTrue(skirmisherApproached, "The scout skirmisher must turn and pursue its attacker")
        }
        helper.runAfterDelay(210) {
            scouts.forEach { mob ->
                InvasionRuntime.maintainTarget(mob)
                helper.assertTrue(mob.target === primary,
                    "${ForgeRegistries.ENTITY_TYPES.getKey(mob.type)} must restore its campaign target after 200 ticks")
                mob.discard()
            }
            attacker.discard()
            helper.level.server.playerList.remove(primary)
            channel.finishAndReleaseAll()
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 150)
    fun campaignTconPillagerPursuesAndFires(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        for (x in 0..30) for (z in 0..14) {
            val pos = base.offset(x, 0, z)
            helper.level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState())
            helper.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
            helper.level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState())
        }
        val target = fake(helper, "tcon-combat")
        target.setGameMode(GameType.SURVIVAL)
        target.moveTo(base.x + 27.5, base.y.toDouble(), base.z + 7.5)
        val pillager = EntityType.PILLAGER.create(helper.level)!!
        pillager.moveTo(base.x + 2.5, base.y.toDouble(), base.z + 7.5)
        helper.assertTrue(helper.level.addFreshEntity(pillager), "Test pillager must enter the level")
        pillager.setOnGround(true)
        pillager.persistentData.putString(InvasionRuntime.INVASION_TAG, "tcon-combat-proof")
        CampaignTconLoadouts.apply(pillager)
        InvasionRuntime.installCombatGoal(pillager)
        helper.assertTrue(pillager.mainHandItem.item is ModifiableCrossbowItem,
            "The campaign pillager must retain its TConstruct crossbow")
        pillager.target = target
        var moved = false
        var fired = false
        helper.onEachTick {
            moved = moved || pillager.x > base.x + 6.5
            fired = fired || helper.level.getEntitiesOfClass(AbstractArrow::class.java,
                pillager.boundingBox.inflate(40.0)).any { it.owner === pillager }
        }
        helper.runAfterDelay(110) {
            helper.assertTrue(moved, "TConstruct-equipped pillager must path toward its target")
            helper.assertTrue(fired, "TConstruct-equipped pillager must fire a real arrow")
            pillager.discard()
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 80)
    fun runtimeRosterAndDummyPlayerAtYDriveProductionPolicy(helper: GameTestHelper) {
        val spec = InvasionRoster.runtimeSpec()
        spec.requireValid()
        val required = setOf(
            "minecraft:pillager", "takesapillage:archer", "takesapillage:skirmisher",
            "takesapillage:legioner", "savage_and_ravage:griefer", "savage_and_ravage:trickster",
            "savage_and_ravage:executioner", "savage_and_ravage:iceologer",
        )
        helper.assertTrue(spec.recruits.map { it.entityId }.containsAll(required),
            "Build roster must expose the authored It Takes a Pillage and Savage & Ravage recruits")
        val fake = fake(helper, "policy")
        fake.setGameMode(GameType.SURVIVAL)
        val atY = helper.absolutePos(BlockPos(8, 3, 8))
        fake.moveTo(atY.x + 0.5, atY.y.toDouble(), atY.z + 0.5)
        val director = InvasionDirector.create(19, InvasionRuntimeSpec.create(
            InvasionRules(scoutWindowMinTicks = 0, scoutWindowMaxTicks = 0), spec.recruits))
        director.transition(DirectorFrame(0, listOf(PlayerObservation(fake.uuid.toString(), true, true, true,
            BlockPoint("minecraft:overworld", atY.x, atY.y, atY.z)))))
        val encounter = director.snapshot().tracks[fake.uuid.toString()]?.invasion
        helper.assertTrue(encounter?.kind == EncounterKind.SCOUT,
            "A real dummy survival player at the supplied Y must start the due scout policy")
        helper.assertTrue(encounter?.members?.size == 3, "Initial scout must contain exactly three authored recruits")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 100)
    fun loadedSurfaceGateCollisionFluidAndReachabilityAreExact(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        buildSurface(helper, base)
        val fake = fake(helper, "route")
        fake.setGameMode(GameType.SURVIVAL)
        fake.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
        val immediate = SurfaceGridSampler.immediateAnchor(helper.level, fake.blockPosition(), 6, 8, 3)
        helper.assertTrue(immediate != null && SurfaceGridSampler.loadedRectangle(helper.level, immediate, fake.blockPosition()),
            "Harness arrival must select a complete loaded-only anchor in the requested band")
        val zombie = EntityType.ZOMBIE.create(helper.level)!!
        zombie.moveTo(base.x + 3.5, base.y.toDouble(), base.z + 7.5)
        helper.assertTrue(InvasionRuntime.exactCandidate(helper.level, zombie, zombie.blockPosition()),
            "Dry two-block space on a solid floor must be an exact candidate")
        helper.level.addFreshEntity(zombie)
        zombie.setOnGround(true)
        val openPath = zombie.navigation.createPath(fake.blockPosition(), 0)
        helper.assertTrue(openPath != null && openPath.canReach(),
            "Open gate path=$openPath canReach=${openPath?.canReach()} mob=${zombie.blockPosition()} target=${fake.blockPosition()} added=${!zombie.isRemoved}")

        for (z in 6..8) {
            helper.level.setBlockAndUpdate(base.offset(7, 0, z), Blocks.STONE.defaultBlockState())
            helper.level.setBlockAndUpdate(base.offset(7, 1, z), Blocks.STONE.defaultBlockState())
        }
        val closedPath = zombie.navigation.createPath(fake.blockPosition(), 0)
        helper.assertTrue(closedPath == null || !closedPath.canReach(), "Closed gate must be partial or unreachable")

        val solid = base.offset(3, 0, 3)
        helper.level.setBlockAndUpdate(solid, Blocks.STONE.defaultBlockState())
        zombie.moveTo(solid.x + 0.5, solid.y.toDouble(), solid.z + 0.5)
        helper.assertTrue(!InvasionRuntime.exactCandidate(helper.level, zombie, solid),
            "Solid occupation must reject exact materialization")
        val fluid = base.offset(4, 0, 3)
        helper.level.setBlockAndUpdate(fluid, Blocks.WATER.defaultBlockState())
        zombie.moveTo(fluid.x + 0.5, fluid.y.toDouble(), fluid.z + 0.5)
        helper.assertTrue(!InvasionRuntime.exactCandidate(helper.level, zombie, fluid),
            "Fluid occupation must reject exact materialization")

        val remoteChunkX = (base.x shr 4) + 20
        val remoteChunkZ = base.z shr 4
        helper.assertTrue(helper.level.chunkSource.getChunkNow(remoteChunkX, remoteChunkZ) == null,
            "Remote route chunk must begin unloaded")
        helper.assertTrue(SurfaceGridSampler.surfaceCell(helper.level, remoteChunkX shl 4, remoteChunkZ shl 4) == null,
            "Unknown surface cells must remain impassable")
        val remoteTarget = BlockPos((remoteChunkX shl 4) + 8, base.y, (remoteChunkZ shl 4) + 8)
        helper.assertTrue(SurfaceGridSampler.immediateAnchor(helper.level, remoteTarget, 6, 8, 3) == null,
            "An immediate campaign must not invent a loaded arrival anchor in unexplored terrain")
        helper.assertTrue(helper.level.chunkSource.getChunkNow(remoteChunkX, remoteChunkZ) == null,
            "Sampling or searching for an immediate anchor must not load, ticket, or generate its chunk")
        zombie.discard()
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 100)
    fun eventAuthoredSpawnGetsTargetProvenanceAndCleansUp(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        buildSurface(helper, base)
        val fake = fake(helper, "spawn")
        fake.setGameMode(GameType.SURVIVAL)
        fake.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
        val anchor = base.offset(3, 0, 7)
        helper.assertTrue(SurfaceGridSampler.loadedRectangle(helper.level, anchor, fake.blockPosition()),
            "The complete bounded navigation region must already be loaded before EVENT materialization")
        InvasionRoster.runtimeSpec().recruits.forEachIndexed { index, recruit ->
            val invasionId = "catalogue-test-$index"
            val memberId = "wave:1:member:${index + 1}"
            val effect = DirectorEffect(
                "test-effect-$index", EffectKind.MATERIALIZE, fake.uuid.toString(), invasionId,
                EncounterKind.ASSAULT, 1,
                BlockPoint("minecraft:overworld", anchor.x, anchor.y, anchor.z),
                listOf(MemberPlan(memberId, recruit.entityId, recruit.cost, 1)),
            )
            helper.assertTrue(InvasionRuntime.materializeAgainst(helper.level, fake, effect),
                "EVENT-authored ${recruit.entityId} must spawn and reach the dummy player")
            val member = InvasionRuntime.liveMembers(helper.level.server, invasionId).single()
            helper.assertTrue(member.target === fake, "${recruit.entityId} must target the eligible dummy player")
            helper.assertTrue(member.persistentData.getString(InvasionRuntime.INVASION_TAG) == invasionId &&
                member.persistentData.getString(InvasionRuntime.MEMBER_TAG) == memberId,
                "${recruit.entityId} must receive encounter and member provenance")
            helper.assertTrue(member.persistentData.getString(InvasionRuntime.KIND_TAG) == "assault" &&
                member.persistentData.getInt(InvasionRuntime.WAVE_TAG) == 1,
                "${recruit.entityId} must receive kind and wave provenance")
            InvasionRuntime.retire(helper.level.server, invasionId)
            helper.assertTrue(InvasionRuntime.liveMembers(helper.level.server, invasionId).isEmpty(),
                "${recruit.entityId} must be cleaned after its catalogue probe")
        }
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 100)
    fun campaignApproachUsesOneLeadPathThenLeavesNavigationToVanilla(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        buildSurface(helper, base)
        val fake = fake(helper, "single-path")
        fake.setGameMode(GameType.SURVIVAL)
        fake.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
        val members = (1..6).map { MemberPlan("lead-proof-$it", "minecraft:pillager", 2) }
        var pathQueries = 0
        val firstAnchor = base.offset(3, 0, 7)
        val first = DirectorEffect(
            "lead-proof-first", EffectKind.MATERIALIZE, fake.uuid.toString(), "lead-proof-invasion",
            EncounterKind.ASSAULT, 0,
            BlockPoint("minecraft:overworld", firstAnchor.x, firstAnchor.y, firstAnchor.z), members,
            validateApproach = true,
        )
        helper.assertTrue(InvasionRuntime.materializeAgainst(helper.level, fake, first) { _, _, _ ->
            pathQueries++
            true
        }, "A complete packet must materialize after its lead path is accepted")
        helper.assertTrue(pathQueries == 1,
            "A six-member packet must run exactly one campaign-owned navigation query, got $pathQueries")
        InvasionRuntime.retire(helper.level.server, first.invasionId)

        val laterAnchor = base.offset(3, 0, 11)
        val later = first.copy(
            effectId = "lead-proof-later",
            anchor = BlockPoint("minecraft:overworld", laterAnchor.x, laterAnchor.y, laterAnchor.z),
            members = (7..12).map { MemberPlan("lead-proof-$it", "minecraft:pillager", 2) },
            validateApproach = false,
        )
        helper.assertTrue(InvasionRuntime.materializeAgainst(helper.level, fake, later) { _, _, _ ->
            pathQueries++
            true
        }, "A later packet at a validated approach must materialize under vanilla navigation")
        helper.assertTrue(pathQueries == 1,
            "Later packets must not run another campaign-owned navigation query")
        InvasionRuntime.retire(helper.level.server, later.invasionId)
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 120)
    fun sealedDefenseMaterializesOutsideAndDoesNotTeleportInside(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        buildSurface(helper, base)
        for (z in 6..8) {
            helper.level.setBlockAndUpdate(base.offset(7, 0, z), Blocks.STONE.defaultBlockState())
            helper.level.setBlockAndUpdate(base.offset(7, 1, z), Blocks.STONE.defaultBlockState())
        }
        val fake = fake(helper, "sealed")
        fake.setGameMode(GameType.SURVIVAL)
        fake.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
        val outside = base.offset(3, 0, 7)
        val effect = DirectorEffect(
            "sealed-effect", EffectKind.MATERIALIZE, fake.uuid.toString(), "sealed-invasion",
            EncounterKind.ASSAULT, 0,
            BlockPoint("minecraft:overworld", outside.x, outside.y, outside.z),
            listOf(MemberPlan("sealed-member", "minecraft:pillager", 2)),
            strategicFrontier = StrategicFrontier.DEFENSE,
        )
        helper.assertTrue(InvasionRuntime.materializeAgainst(helper.level, fake, effect),
            "A known sealed defense must materialize on its exterior even though the real path is partial")
        val member = InvasionRuntime.liveMembers(helper.level.server, "sealed-invasion").single()
        helper.assertTrue(member.blockX < base.x + 7,
            "The defensive frontier must never place a campaign member inside the wall")
        helper.assertTrue(member.navigation.createPath(fake.blockPosition(), 0)?.canReach() != true,
            "The real authored mob must see the sealed player as unreachable")
        InvasionRuntime.retire(helper.level.server, "sealed-invasion")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 160)
    fun failedOpenApproachRetriesAtReachableAnchorWithoutCrossingDefense(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        buildSurface(helper, base)
        for (z in 6..8) {
            helper.level.setBlockAndUpdate(base.offset(7, 0, z), Blocks.STONE.defaultBlockState())
            helper.level.setBlockAndUpdate(base.offset(7, 1, z), Blocks.STONE.defaultBlockState())
        }
        val fake = fake(helper, "retry")
        fake.setGameMode(GameType.SURVIVAL)
        fake.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
        val playerId = fake.uuid.toString()
        val target = BlockPoint("minecraft:overworld", fake.blockX, fake.blockY, fake.blockZ)
        val rules = InvasionRules(
            scoutWindowMinTicks = 0, scoutWindowMaxTicks = 0,
            approachMinimumBlocks = 2, approachMaximumBlocks = 4, approachTargetRadiusBlocks = 1,
            strategicOriginMinimumBlocks = 5, strategicOriginMaximumBlocks = 6,
        )
        val director = InvasionDirector.create(83, InvasionRuntimeSpec.create(rules,
            listOf(RecruitSpec("minecraft:pillager", 2, 0, RecruitRole.RANGED))))
        director.transition(DirectorFrame(0, commands = listOf(
            DirectorCommand.Force(playerId, EncounterKind.SCOUT, expediteTravel = true),
        )))
        val player = PlayerObservation(playerId, true, true, true, target)
        director.transition(DirectorFrame(0, players = listOf(player)))
        var invasion = director.snapshot().tracks.getValue(playerId).invasion!!

        val outside = base.offset(3, 0, 7)
        val rejected = director.transition(DirectorFrame(0, players = listOf(player), strategicRoutes = listOf(
            StrategicRouteObservation(playerId, invasion.invasionId, target, 1,
                listOf(BlockPoint(target.dimension, outside.x - 3, outside.y, outside.z),
                    BlockPoint(target.dimension, outside.x, outside.y, outside.z)), StrategicFrontier.OPEN),
        ))).effects.single { it.kind == EffectKind.MATERIALIZE }
        helper.assertTrue(!InvasionRuntime.materializeAgainst(helper.level, fake, rejected),
            "The exact pathfinder must reject the atlas-open approach outside the closed defense")
        director.transition(DirectorFrame(0, effectResults = listOf(EffectResult(rejected.effectId, false))))
        invasion = director.snapshot().tracks.getValue(playerId).invasion!!
        helper.assertTrue(invasion.usedAnchors.single() == rejected.anchor,
            "The rejected exact approach must be retained as a retry exclusion")

        val reachable = base.offset(9, 0, 7)
        val retried = director.transition(DirectorFrame(rules.normalPacketSpacingTicks, players = listOf(player),
            strategicRoutes = listOf(StrategicRouteObservation(playerId, invasion.invasionId, target, 2,
                listOf(BlockPoint(target.dimension, reachable.x + 3, reachable.y, reachable.z),
                    BlockPoint(target.dimension, reachable.x, reachable.y, reachable.z)), StrategicFrontier.OPEN),
            ))).effects.single { it.kind == EffectKind.MATERIALIZE }
        helper.assertTrue(retried.anchor == BlockPoint(target.dimension, reachable.x, reachable.y, reachable.z),
            "Retry must advance to the alternate anchor instead of recycling the rejected one")
        helper.assertTrue(InvasionRuntime.materializeAgainst(helper.level, fake, retried),
            "The alternate same-side approach must pass exact navigation")
        val members = InvasionRuntime.liveMembers(helper.level.server, invasion.invasionId)
        helper.assertTrue(members.size == 3 && members.all { it.target === fake },
            "The complete scout must arrive and target the dummy player after one rejected approach")
        helper.assertTrue(members.all { it.blockX > base.x + 7 },
            "Retry materialization must stay on the player's reachable side, never cross the wall")
        InvasionRuntime.retire(helper.level.server, invasion.invasionId)
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 200)
    fun twelveScheduledAssaultsCompleteThroughRealForgeMaterialization(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        buildSurface(helper, base)
        val fake = fake(helper, "reliability")
        fake.setGameMode(GameType.SURVIVAL)
        fake.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
        val playerId = fake.uuid.toString()
        val target = BlockPoint("minecraft:overworld", fake.blockX, fake.blockY, fake.blockZ)
        val anchorPos = base.offset(3, 0, 7)
        val anchor = BlockPoint(target.dimension, anchorPos.x, anchorPos.y, anchorPos.z)
        val rules = InvasionRules(
            scoutWindowMinTicks = 1_000_000, scoutWindowMaxTicks = 1_000_000,
            assaultWindowMinTicks = 20, assaultWindowMaxTicks = 20,
            assaultWarningSurfaceTicks = 5, timeTierTicks = 1_000_000,
            waveProgressTimeoutTicks = 50, assaultActiveTicks = 500, activeIdleTicks = 300,
            approachMinimumBlocks = 2, approachMaximumBlocks = 10, approachTargetRadiusBlocks = 1,
            maximumSearchExpansions = 128, strategicOriginMinimumBlocks = 11,
            strategicOriginMaximumBlocks = 12, assaultStrategicMilliBlocksPerTick = 2_000,
            normalPacketSpacingTicks = 1,
        )
        val director = InvasionDirector.create(97, InvasionRuntimeSpec.create(rules, InvasionRoster.runtimeSpec().recruits))
        val player = PlayerObservation(playerId, true, true, true, target)
        val expectedCycles = 12
        val dispatched = linkedSetOf<String>()
        val warned = linkedSetOf<String>()
        val completed = linkedSetOf<String>()
        val materializedByWave = linkedMapOf<String, MutableMap<Int, Int>>()

        fun record(transition: DirectorTransition) {
            transition.events.forEach { event ->
                when (event.type) {
                    "assault_dispatched" -> dispatched += event.subjectId
                    "assault_warned" -> warned += event.subjectId
                    "resolved" -> completed += event.subjectId
                }
            }
        }

        var iterations = 0
        while (completed.size < expectedCycles && iterations++ < 2_000) {
            val before = director.snapshot().tracks[playerId]?.invasion
            val route = before?.takeIf { it.phase == InvasionPhase.APPROACHING }?.let { invasion ->
                listOf(StrategicRouteObservation(playerId, invasion.invasionId, target, 1,
                    listOf(anchor), StrategicFrontier.OPEN))
            }.orEmpty()
            val defeated = before?.takeIf { it.phase == InvasionPhase.ACTIVE }?.let { invasion ->
                val wave = invasion.waves[invasion.currentWave]
                if (wave.materializedMembers == wave.members.size) {
                    val live = InvasionRuntime.liveMembers(helper.level.server, invasion.invasionId)
                    helper.assertTrue(live.size == wave.members.size,
                        "${invasion.invasionId} wave ${wave.waveIndex} planned ${wave.members.size} but had ${live.size} live mobs")
                    helper.assertTrue(live.all { it.target === fake },
                        "Every materialized member must retain the scheduled target")
                    live.forEach { it.discard() }
                    wave.members.map { MemberDefeatObservation(invasion.invasionId, it.memberId) }
                } else emptyList()
            }.orEmpty()
            val transition = director.transition(DirectorFrame(
                elapsedTicks = 1,
                players = listOf(player),
                strategicRoutes = route,
                memberDefeats = defeated,
                liveCampaignMobs = InvasionRuntime.liveCampaignPopulation(helper.level.server),
            ))
            record(transition)
            val results = transition.effects.map { effect ->
                val successful = when (effect.kind) {
                    EffectKind.WARN -> true
                    EffectKind.MATERIALIZE -> InvasionRuntime.materializeAgainst(helper.level, fake, effect)
                    EffectKind.RETIRE -> InvasionRuntime.retire(helper.level.server, effect.invasionId)
                }
                helper.assertTrue(successful, "${effect.kind} failed for repeated campaign ${effect.invasionId}")
                if (effect.kind == EffectKind.MATERIALIZE) {
                    val waves = materializedByWave.getOrPut(effect.invasionId) { linkedMapOf() }
                    waves[effect.waveIndex] = waves.getOrDefault(effect.waveIndex, 0) + effect.members.size
                }
                EffectResult(effect.effectId, successful)
            }
            if (results.isNotEmpty()) {
                record(director.transition(DirectorFrame(0, players = listOf(player), effectResults = results,
                    liveCampaignMobs = InvasionRuntime.liveCampaignPopulation(helper.level.server))))
            }
        }

        helper.assertTrue(dispatched.size == expectedCycles,
            "Expected $expectedCycles scheduled assaults, dispatched ${dispatched.size}")
        helper.assertTrue(warned == dispatched, "Every scheduled assault must issue its warning")
        helper.assertTrue(completed == dispatched, "Every scheduled assault must complete before the next cycle")
        dispatched.forEach { invasionId ->
            val waves = materializedByWave[invasionId].orEmpty()
            helper.assertTrue(waves.keys == setOf(0, 1, 2), "$invasionId did not materialize all three waves: $waves")
            helper.assertTrue(waves.values.all { it in 16..24 }, "$invasionId produced an out-of-bounds wave: $waves")
            helper.assertTrue(InvasionRuntime.liveMembers(helper.level.server, invasionId).isEmpty(),
                "$invasionId left campaign mobs behind after completion")
        }
        PillagerCampaignsMod.LOGGER.info(
            "Reliability soak completed {} naturally scheduled assaults, {} waves, and {} real Forge mob materializations",
            completed.size, materializedByWave.values.sumOf { it.size },
            materializedByWave.values.sumOf { waves -> waves.values.sum() },
        )
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 600)
    fun minimumIntensityAssaultDefeatsFullHealthSurvivalPlayer(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        buildSurface(helper, base)
        helper.level.server.setDifficulty(Difficulty.NORMAL, true)
        val target = ServerPlayer(helper.level.server, helper.level,
            GameProfile(UUID.nameUUIDFromBytes("campaign-test-lethality".toByteArray()), "campaign-test-lethality"))
        val connection = Connection(PacketFlow.SERVERBOUND)
        val channel = EmbeddedChannel(connection)
        helper.level.server.playerList.placeNewPlayer(connection, target)
        target.setGameMode(GameType.SURVIVAL)
        target.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
        target.health = target.maxHealth

        val runtime = InvasionRoster.runtimeSpec()
        val playerId = target.uuid.toString()
        val targetPoint = BlockPoint("minecraft:overworld", target.blockX, target.blockY, target.blockZ)
        val player = PlayerObservation(playerId, true, true, true, targetPoint)
        PillagerCampaignsEvents.advance(helper.level.server, 0,
            commands = listOf(DirectorCommand.Force(playerId, EncounterKind.ASSAULT,
                expediteTravel = true, intensity = 0)), playersOverride = listOf(player))
        val invasion = PillagerWorldData.get(helper.level.server).snapshot().tracks.getValue(playerId).invasion!!
        val invasionId = invasion.invasionId
        val memberCount = invasion.waves[0].members.size
        val anchor = base.offset(3, 0, 7)
        PillagerCampaignsEvents.advance(helper.level.server, 0,
            strategicRoutes = listOf(StrategicRouteObservation(playerId, invasionId, targetPoint, 1,
                listOf(BlockPoint(targetPoint.dimension, anchor.x, anchor.y, anchor.z)), StrategicFrontier.OPEN)),
            playersOverride = listOf(player))
        var dispatches = 0
        while (InvasionRuntime.liveMembers(helper.level.server, invasionId).size < memberCount && dispatches++ < 10) {
            PillagerCampaignsEvents.advance(helper.level.server, runtime.rules.normalPacketSpacingTicks,
                playersOverride = listOf(player))
        }
        val attackers = InvasionRuntime.liveMembers(helper.level.server, invasionId)
        helper.assertTrue(attackers.isNotEmpty() && attackers.all { it.target === target },
            "The rate-limited authored assault packet must spawn targeting the Survival player")
        PillagerCampaignsMod.LOGGER.info(
            "Lethality attacker loadout: {}",
            attackers.joinToString { mob ->
                "${ForgeRegistries.ENTITY_TYPES.getKey(mob.type)}[hand=${mob.mainHandItem.item},noAi=${mob.isNoAi}]"
            },
        )

        val startingHealth = target.health
        var lowestHealth = startingHealth
        var survivedDeathsDoor = false
        var finished = false
        var combatTicks = 0
        helper.onEachTick {
            if (finished) return@onEachTick
            combatTicks++
            if (combatTicks % runtime.rules.normalPacketSpacingTicks.toInt() == 0) {
                PillagerCampaignsEvents.advance(helper.level.server, runtime.rules.normalPacketSpacingTicks,
                    playersOverride = listOf(player))
            }
            lowestHealth = minOf(lowestHealth, target.health)
            if (target.isAlive && InjuryCompat.semanticHealth(target) == 0f) {
                survivedDeathsDoor = true
                val current = PillagerWorldData.get(helper.level.server).snapshot().tracks.getValue(playerId).invasion
                helper.assertTrue(current != null && current.phase != InvasionPhase.RETIRING,
                    "An active Death's Door player must not cause assault retirement")
            }
            if (!target.isAlive) {
                helper.assertTrue(survivedDeathsDoor, "The assault must continue through Death's Door to actual final death")
                finished = true
                val killer = target.killCredit
                helper.assertTrue(killer != null &&
                    killer.persistentData.getString(InvasionRuntime.INVASION_TAG) == invasionId,
                    "A campaign-tagged assault member must receive kill credit, got $killer")
                PillagerCampaignsMod.LOGGER.info(
                    "Lethality validation: minimum-intensity authored assault ({} planned, {} materialized) defeated a full-health Survival player in {} ticks; health {} -> {}",
                    memberCount, InvasionRuntime.liveMembers(helper.level.server, invasionId).size,
                    combatTicks, startingHealth, target.health,
                )
                InvasionRuntime.retire(helper.level.server, invasionId)
                PillagerCampaignsEvents.advance(helper.level.server, 0,
                    commands = listOf(DirectorCommand.Reset(playerId)), playersOverride = emptyList())
                helper.level.server.playerList.remove(target)
                channel.finishAndReleaseAll()
                helper.succeed()
            } else if (combatTicks % 100 == 0) {
                PillagerCampaignsMod.LOGGER.info(
                    "Lethality validation progress: tick={} difficulty={} health={} lowest_health={} live_attackers={} targeted={} navigating={} ticking={} los={} nearest={} spectator={} creative={} invulnerable={}",
                    combatTicks, helper.level.difficulty, target.health, lowestHealth,
                    InvasionRuntime.liveMembers(helper.level.server, invasionId).size,
                    attackers.count { it.target === target }, attackers.count { !it.navigation.isDone },
                    attackers.count { it.tickCount > 0 }, attackers.count { it.sensing.hasLineOfSight(target) },
                    attackers.minOfOrNull { it.distanceTo(target) }, target.isSpectator, target.isCreative,
                    target.isInvulnerableTo(helper.level.damageSources().mobAttack(attackers.first())),
                )
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "minecraft", template = "empty", timeoutTicks = 300)
    fun terrainAtlasSurvivesActualChunkUnloadWithoutReloadingIt(helper: GameTestHelper) {
        val level = helper.level
        val origin = helper.absolutePos(BlockPos.ZERO)
        val chunkPos = ChunkPos((origin.x shr 4) + 40, (origin.z shr 4) + 40)
        level.chunkSource.addRegionTicket(TicketType.UNKNOWN, chunkPos, 2, chunkPos)
        val chunk = level.getChunk(chunkPos.x, chunkPos.z)
        val atlas = TerrainAtlasData.get(level.server)
        atlas.observe(level, chunk)
        val sampleX = chunkPos.minBlockX + 8
        val sampleZ = chunkPos.minBlockZ + 8
        helper.assertTrue(atlas.cellsAround(sampleX, sampleZ, 1).isNotEmpty(),
            "Loaded terrain must enter the strategic atlas")
        level.chunkSource.removeRegionTicket(TicketType.UNKNOWN, chunkPos, 2, chunkPos)
        helper.runAfterDelay(200) {
            helper.assertTrue(level.chunkSource.getChunkNow(chunkPos.x, chunkPos.z) == null,
                "The remote proof chunk must actually unload")
            helper.assertTrue(atlas.cellsAround(sampleX, sampleZ, 1).isNotEmpty(),
                "Atlas terrain must remain available after the source chunk unloads")
            helper.assertTrue(level.chunkSource.getChunkNow(chunkPos.x, chunkPos.z) == null,
                "Reading atlas terrain must not reload or ticket the chunk")
            helper.succeed()
        }
    }

    private fun buildSurface(helper: GameTestHelper, base: BlockPos) {
        for (x in 0..14) for (z in 0..14) {
            helper.level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.STONE.defaultBlockState())
            helper.level.setBlockAndUpdate(base.offset(x, 0, z), Blocks.AIR.defaultBlockState())
            helper.level.setBlockAndUpdate(base.offset(x, 1, z), Blocks.AIR.defaultBlockState())
        }
        for (z in 0..14) if (z !in 6..8) {
            helper.level.setBlockAndUpdate(base.offset(7, 0, z), Blocks.STONE.defaultBlockState())
            helper.level.setBlockAndUpdate(base.offset(7, 1, z), Blocks.STONE.defaultBlockState())
        }
    }

    private fun fake(helper: GameTestHelper, suffix: String) =
        FakePlayer(helper.level, GameProfile(UUID.nameUUIDFromBytes("campaign-test-$suffix".toByteArray()), "campaign-test-$suffix"))
}
