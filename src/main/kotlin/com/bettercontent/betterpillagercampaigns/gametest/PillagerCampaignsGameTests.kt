package com.bettercontent.betterpillagercampaigns.gametest

import com.bettercontent.betterpillagercampaigns.PillagerCampaignsMod
import com.bettercontent.betterpillagercampaigns.PillagerCampaignsEvents
import com.bettercontent.betterpillagercampaigns.core.*
import com.bettercontent.betterpillagercampaigns.system.InjuryCompat
import com.bettercontent.betterpillagercampaigns.system.InvasionRoster
import com.bettercontent.betterpillagercampaigns.system.InvasionRuntime
import com.bettercontent.betterpillagercampaigns.system.CampaignTconLoadouts
import com.bettercontent.betterpillagercampaigns.system.LoadedSpawnChecks
import com.bettercontent.betterpillagercampaigns.system.VirtualCampaignRouting
import com.bettercontent.betterpillagercampaigns.system.VirtualCampaignTerrain
import com.bettercontent.pillagercampaigns.core.VirtualReachability
import com.bettercontent.pillagercampaigns.core.VirtualReachability.Point
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
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.living.LivingHurtEvent
import net.minecraftforge.eventbus.api.EventPriority
import java.util.function.Consumer
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
    private fun regionalPresenceSurvivesNativeSaveAndBlocksFreshPackets(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        buildSurface(helper, base)
        val player = fake(helper, "presence")
        player.setGameMode(GameType.SURVIVAL)
        player.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
        val data = PillagerWorldData.get(helper.level.server)
        val id = player.uuid.toString()
        val known = data.snapshot()
        // Exercise a legitimately large bounded ledger through actual NBT binary encoding.
        repeat(1_023) { known.regionalPresence.getValue(id).record(
            BlockPoint("test:remote_dimension", it * 1_024, 64, 0), 20) }
        val unknown = data.snapshot().also { it.regionalPresence.remove(id) }
        data.restoreSnapshot(unknown, InvasionRoster.runtimeSpec())
        val anchor = base.offset(3, 0, 7)
        val effect = DirectorEffect("presence-first", EffectKind.MATERIALIZE, id, "presence-invasion",
            EncounterKind.ASSAULT, 0, BlockPoint("minecraft:overworld", anchor.x, anchor.y, anchor.z),
            listOf(MemberPlan("presence-member", "minecraft:pillager", 2)))
        helper.assertTrue(!InvasionRuntime.materializeAgainst(helper.level, player, effect),
            "Unknown regional history must reject fresh packets before spawning")
        data.restoreSnapshot(known, InvasionRoster.runtimeSpec())
        val bytes = java.io.ByteArrayOutputStream()
        net.minecraft.nbt.NbtIo.write(data.save(net.minecraft.nbt.CompoundTag()), java.io.DataOutputStream(bytes))
        helper.assertTrue(bytes.size() > 65_535, "Persistence fixture must exceed the NBT string limit")
        val savedTag = net.minecraft.nbt.NbtIo.read(java.io.DataInputStream(java.io.ByteArrayInputStream(bytes.toByteArray())))
        val reloaded = PillagerWorldData.load(savedTag, helper.level.seed)
        helper.assertTrue(reloaded.regionFamiliar(id, BlockPoint("minecraft:overworld", player.blockX, player.blockY, player.blockZ)),
            "Native NBT save/reload must retain regional history")
        helper.assertTrue(materializeFixture(helper, player, effect), "Established presence permits a packet")
        val mob = InvasionRuntime.liveMembers(helper.level.server, effect.invasionId).single()
        data.restoreSnapshot(unknown, InvasionRoster.runtimeSpec())
        val next = effect.copy(effectId = "presence-next", waveIndex = 1,
            members = listOf(MemberPlan("presence-next-member", "minecraft:pillager", 2, 1)))
        helper.assertTrue(!InvasionRuntime.materializeAgainst(helper.level, player, next), "Later waves must recheck presence")
        helper.assertTrue(!InvasionRuntime.freshTargetEligible(player), "Fallback target must not bypass familiarity")
        mob.discard()
        data.restoreSnapshot(known, InvasionRoster.runtimeSpec())
        val sequence = data.snapshot().regionalPresence.getValue(id).sequence
        player.setGameMode(GameType.CREATIVE)
        helper.assertTrue(!InvasionRuntime.freshTargetEligible(player), "Creative fallback must be rejected even at home")
        PillagerCampaignsEvents.recordOnlinePresence(player)
        player.setGameMode(GameType.SPECTATOR)
        PillagerCampaignsEvents.recordOnlinePresence(player)
        helper.assertTrue(data.snapshot().regionalPresence.getValue(id).sequence == sequence,
            "Creative and Spectator ticks cannot accumulate presence")
        player.setGameMode(GameType.SURVIVAL)
        PillagerCampaignsEvents.recordOnlinePresence(player)
        helper.assertTrue(data.snapshot().regionalPresence.getValue(id).sequence == sequence + 1,
            "A living Survival observation adds only its actual one tick")
        player.health = 0f
        PillagerCampaignsEvents.recordOnlinePresence(player)
        helper.assertTrue(data.snapshot().regionalPresence.getValue(id).sequence == sequence + 1,
            "Dead players cannot accumulate presence")
    }

    @JvmStatic
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena", batch = "campaign_retaliation", timeoutTicks = 340)
    fun everyScoutArchetypeRetaliatesForTenSeconds(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(0, 2, 0))
        // Closed native duels prevent observed allied arrows from invalidating another scout.
        // No scout health, combat goal, projectile or damage event is suppressed.
        for (x in 0..33) for (z in 0..33) {
            helper.level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.STONE.defaultBlockState())
            val separator = x == 0 || x == 33 || z in listOf(0, 11, 22, 33)
            for (y in 0..5) helper.level.setBlockAndUpdate(base.offset(x, y, z),
                if (separator) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState())
        }
        requireCampaignFixtureWorld(helper, base)
        val laneZ = listOf(5.5, 16.5, 27.5)
        val primary = ServerPlayer(helper.level.server, helper.level,
            GameProfile(UUID.nameUUIDFromBytes("campaign-test-scout-primary".toByteArray()), "campaign-test-scout-primary"))
        val connection = Connection(PacketFlow.SERVERBOUND)
        val channel = EmbeddedChannel(connection)
        helper.level.server.playerList.placeNewPlayer(connection, primary)
        primary.setGameMode(GameType.SURVIVAL)
        primary.abilities.invulnerable = true
        primary.moveTo(base.x + 3.5, base.y.toDouble(), base.z + laneZ[0])

        val attackers = laneZ.map { z ->
            EntityType.ZOMBIE.create(helper.level)!!.also { attacker ->
                attacker.moveTo(base.x + 23.5, base.y.toDouble(), base.z + z)
                attacker.isNoAi = true
                attacker.isInvulnerable = true
                helper.assertTrue(helper.level.addFreshEntity(attacker), "Each lane's original zombie attacker must enter the level")
            }
        }
        val scouts = listOf("minecraft:pillager", "takesapillage:archer", "takesapillage:skirmisher")
            .mapIndexed { index, id ->
                val type = ForgeRegistries.ENTITY_TYPES.getValue(net.minecraft.resources.ResourceLocation.tryParse(id))!!
                val mob = type.create(helper.level) as Mob
                mob.moveTo(base.x + 5.5, base.y.toDouble(), base.z + laneZ[index])
                mob.finalizeSpawn(helper.level, helper.level.getCurrentDifficultyAt(mob.blockPosition()),
                    MobSpawnType.EVENT, null, null)
                helper.assertTrue(helper.level.addFreshEntity(mob), "$id must enter the level")
                mob.setOnGround(true)
                mob.persistentData.putString(InvasionRuntime.INVASION_TAG, "scout-retaliation-proof")
                mob.persistentData.putString(InvasionRuntime.KIND_TAG, "scout")
                mob.persistentData.putUUID(InvasionRuntime.TARGET_TAG, primary.uuid)
                if (mob.type == EntityType.PILLAGER) CampaignTconLoadouts.apply(mob)
                InvasionRuntime.installCombatGoal(mob)
                mob.target = primary
                mob
            }
        val attackerByScout = scouts.mapIndexed { index, mob -> mob.uuid to attackers[index] }.toMap()
        val deathTrace = CampaignFixtureActorTrace(helper, scouts + attackers, 340L,
            "scout-retaliation three-native-lanes authoredArena=${base.offset(0, -1, 0)}..${base.offset(34, 6, 34)}")
        val readinessStarted = helper.tick
        var armedAt: Long? = null
        var archerFired = false
        var skirmisherApproached = false
        val latestHit = mutableMapOf<UUID, Long>()
        val hurtEventHit = mutableMapOf<UUID, Long>()
        val checked190 = mutableMapOf<UUID, Long>()
        val checked200 = mutableMapOf<UUID, Long>()
        val preHitArrowIds = mutableSetOf<UUID>()
        val subscriptions = mutableListOf<Any>()
        var hurtEvents = 0
        var droppedHurtEvents = 0
        var retaliationWindowExpired = false
        fun actorState(entity: net.minecraft.world.entity.Entity): String =
            "${ForgeRegistries.ENTITY_TYPES.getKey(entity.type)}[uuid=${entity.uuid},pos=${entity.blockPosition()}," +
                "chunk=${ChunkPos(entity.blockPosition())},registered=${helper.level.getEntity(entity.uuid) === entity}," +
                "entityTicking=${helper.level.isPositionEntityTicking(entity.blockPosition())}," +
                "age=${entity.tickCount},alive=${entity.isAlive},removed=${entity.removalReason}," +
                "noAi=${(entity as? Mob)?.isNoAi},invulnerable=${entity.isInvulnerable},target=${(entity as? Mob)?.target?.uuid}," +
                "lastAttacker=${entity.persistentData.takeIf { it.hasUUID("PillagerCampaignsLastAttacker") }?.getUUID("PillagerCampaignsLastAttacker")}," +
                "lastHitPresent=${entity.persistentData.contains("PillagerCampaignsLastHitTick")}," +
                "lastHit=${entity.persistentData.getLong("PillagerCampaignsLastHitTick")}]"
        fun state(): String = "testTick=${helper.tick},nativeTick=${helper.level.gameTime},armedAt=$armedAt," +
            "latestHits=$latestHit,checked190=$checked190,checked200=$checked200," +
            "primaryOnline=${helper.level.server.playerList.getPlayer(primary.uuid) === primary}," +
            "primary=${actorState(primary)},attackers=${attackers.joinToString { actorState(it) }}," +
            "scouts=${scouts.joinToString { actorState(it) }},arenaBase=$base"
        fun trace(stage: String) {
            org.slf4j.LoggerFactory.getLogger("ScoutRetaliationTest").info("Retaliation {}: {}", stage, state())
        }
        fun closeTrace() {
            deathTrace.close()
            subscriptions.toList().forEach { MinecraftForge.EVENT_BUS.unregister(it) }
            subscriptions.clear()
        }
        fun check(condition: Boolean, message: String) {
            if (!condition) { trace("failure"); closeTrace() }
            helper.assertTrue(condition, "$message; ${state()}")
        }
        fun checkNativeActors(stage: String) {
            (listOf(primary) + attackers + scouts).forEach { entity ->
                check(entity.isAlive && !entity.isRemoved && helper.level.getEntity(entity.uuid) === entity &&
                    helper.level.isPositionEntityTicking(entity.blockPosition()) && entity.tickCount > 0,
                    "$stage requires every original actor living, nonremoved, UUID-identical and natively ticking: ${deathTrace.state(entity)}")
            }
            check(helper.level.server.playerList.getPlayer(primary.uuid) === primary,
                "$stage requires the same real online primary player")
        }
        // Caller audit: the only production recordScoutHit caller is onLivingHurt.
        // LOWEST observes after that normal-priority handler, including canceled events.
        val hurtTrace = Consumer<LivingHurtEvent> { event ->
            if (event.entity.level() === helper.level && scouts.any { it === event.entity && it.uuid == event.entity.uuid }) {
                if (hurtEvents++ < 128) {
                    val source = event.source.entity
                    val direct = event.source.directEntity
                    val tag = event.entity.persistentData
                    val tick = helper.level.gameTime
                    val matches = source != null && tag.hasUUID("PillagerCampaignsLastAttacker") &&
                        tag.getUUID("PillagerCampaignsLastAttacker") == source.uuid &&
                        tag.contains("PillagerCampaignsLastHitTick") && tag.getLong("PillagerCampaignsLastHitTick") == tick
                    if (matches) hurtEventHit[event.entity.uuid] = tick
                    org.slf4j.LoggerFactory.getLogger("ScoutRetaliationTest").info(
                        "Retaliation hurt-event: victim={} nativeTick={} testTick={} msgId={} amount={} canceled={} source={} direct={} directOwner={} postHandlerMatched={} postLastHit={} postAttacker={}",
                        event.entity.uuid, tick, helper.tick, event.source.msgId, event.amount, event.isCanceled,
                        source?.let(::actorState), direct?.let(::actorState),
                        (direct as? net.minecraft.world.entity.projectile.Projectile)?.owner?.uuid,
                        matches, tag.getLong("PillagerCampaignsLastHitTick"),
                        tag.takeIf { it.hasUUID("PillagerCampaignsLastAttacker") }?.getUUID("PillagerCampaignsLastAttacker"))
                } else droppedHurtEvents++
            }
        }
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, LivingHurtEvent::class.java, hurtTrace)
        subscriptions.add(hurtTrace)
        // Public tick cleanup covers the unchanged native timeout without reflecting testInfo.
        val timeoutTrace = Consumer<TickEvent.ServerTickEvent> { event ->
            if (event.phase == TickEvent.Phase.END && event.server === helper.level.server && helper.tick >= 340L) {
                trace("native-timeout-observer-cleanup")
                closeTrace()
            }
        }
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, TickEvent.ServerTickEvent::class.java, timeoutTrace)
        subscriptions.add(timeoutTrace)
        trace("spawn-added-before-native-readiness")
        helper.onEachTick {
            try {
                if (!retaliationWindowExpired) {
                    if (armedAt == null) {
                        // Successful insertion need not mean UUID visibility or native ticking.
                        // Let the real online player's own native tickets settle first.
                        val actors = listOf(primary) + attackers + scouts
                        val ready = helper.level.server.playerList.getPlayer(primary.uuid) === primary &&
                            actors.all { entity -> helper.level.getEntity(entity.uuid) === entity &&
                                helper.level.isPositionEntityTicking(entity.blockPosition()) && entity.tickCount > 0 }
                        if (ready) {
                            trace("native-readiness")
                            preHitArrowIds.addAll(helper.level.getEntitiesOfClass(AbstractArrow::class.java,
                                scouts[1].boundingBox.inflate(35.0)).filter { it.owner === scouts[1] }.map { it.uuid })
                            armedAt = helper.level.gameTime
                            scouts.forEachIndexed { index, mob ->
                                val attacker = attackerByScout.getValue(mob.uuid)
                                check(mob.hurt(helper.level.damageSources().mobAttack(attacker), 1f) &&
                                    hurtEventHit[mob.uuid] == helper.level.gameTime,
                                    "Each original scout requires its own zombie's actual native Hurt event and production handler")
                                latestHit[mob.uuid] = helper.level.gameTime
                                mob.navigation.moveTo(base.x + 2.5, base.y.toDouble(), base.z + laneZ[index], 1.0)
                                InvasionRuntime.maintainTarget(mob)
                                check(mob.persistentData.getLong("PillagerCampaignsLastHitTick") == armedAt && mob.target === attacker,
                                    "${ForgeRegistries.ENTITY_TYPES.getKey(mob.type)} must target its attacker while moving away at the actual native hit tick")
                            }
                            trace("armed-native-hit")
                        } else check(helper.tick - readinessStarted < 100L,
                            "Retaliation actors must become UUID-visible and natively entity-ticking within 100 existing test ticks")
                    } else {
                        val now = helper.level.gameTime
                        archerFired = archerFired || helper.level.getEntitiesOfClass(AbstractArrow::class.java,
                            scouts[1].boundingBox.inflate(35.0)).any { it.owner === scouts[1] && it.uuid !in preHitArrowIds }
                        skirmisherApproached = skirmisherApproached || scouts[2].x > base.x + 9.5
                        scouts.forEach { mob ->
                            val attacker = attackerByScout.getValue(mob.uuid)
                            val tag = mob.persistentData
                            val observed = tag.takeIf { it.contains("PillagerCampaignsLastHitTick") }?.getLong("PillagerCampaignsLastHitTick")
                            if (observed != null && observed > latestHit.getValue(mob.uuid)) {
                                check(hurtEventHit[mob.uuid] == observed && tag.hasUUID("PillagerCampaignsLastAttacker") &&
                                    tag.getUUID("PillagerCampaignsLastAttacker") == attacker.uuid,
                                    "Every later native re-arm must have a matching actual post-handler LivingHurtEvent source trace")
                                latestHit[mob.uuid] = observed
                                trace("actual-hurt-rearmed-${mob.uuid}")
                            }
                            val hitTick = latestHit.getValue(mob.uuid)
                            val elapsed = now - hitTick
                            if (checked190[mob.uuid] != hitTick && elapsed >= 190L) {
                                check(elapsed == 190L, "Each pre-expiry oracle must run at exactly 190 native ticks after that mob's most recent hit")
                                checkNativeActors("190-native-tick retaliation oracle")
                                InvasionRuntime.maintainTarget(mob)
                                check(mob.target === attacker,
                                    "${ForgeRegistries.ENTITY_TYPES.getKey(mob.type)} must keep retaliating before 200 ticks")
                                check(archerFired, "The scout archer must fire at its attacker")
                                check(skirmisherApproached, "The scout skirmisher must turn and pursue its attacker")
                                checked190[mob.uuid] = hitTick
                                trace("pre-expiry-190-native-ticks-${mob.uuid}")
                            }
                            if (checked200[mob.uuid] != hitTick && elapsed >= 200L) {
                                check(elapsed == 200L && checked190[mob.uuid] == hitTick,
                                    "Each expiry oracle must run at exactly 200 native ticks after that mob's most recent hit, after its full 190-tick oracle")
                                checkNativeActors("200-native-tick retaliation oracle")
                                InvasionRuntime.maintainTarget(mob)
                                check(mob.target === primary,
                                    "${ForgeRegistries.ENTITY_TYPES.getKey(mob.type)} must restore its campaign target after 200 game ticks")
                                checked200[mob.uuid] = hitTick
                                trace("expiry-200-native-ticks-${mob.uuid}")
                            }
                        }
                        if (scouts.all { checked200[it.uuid] == latestHit[it.uuid] }) {
                            check(droppedHurtEvents == 0 && !deathTrace.truncated,
                                "The bounded actual-hit and death event traces must not lose observations")
                            trace("all-latest-native-windows-complete")
                            closeTrace()
                            scouts.forEach { it.discard() }
                            attackers.forEach { it.discard() }
                            helper.level.server.playerList.remove(primary)
                            channel.finishAndReleaseAll()
                            retaliationWindowExpired = true
                            helper.succeed()
                        }
                    }
                }
            } catch (failure: Throwable) {
                closeTrace()
                throw failure
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena", batch = "campaign_crossbow", timeoutTicks = 150)
    fun campaignTconPillagerPursuesAndFires(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        for (x in 0..20) for (z in 0..24) {
            val pos = base.offset(x, 0, z)
            helper.level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState())
            helper.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
            helper.level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState())
        }
        val target = ServerPlayer(helper.level.server, helper.level,
            GameProfile(UUID.nameUUIDFromBytes("campaign-test-tcon-combat".toByteArray()), "campaign-test-tcon-combat"))
        val connection = Connection(PacketFlow.SERVERBOUND)
        val channel = EmbeddedChannel(connection)
        helper.level.server.playerList.placeNewPlayer(connection, target)
        target.setGameMode(GameType.SURVIVAL)
        target.abilities.invulnerable = true
        target.moveTo(base.x + 20.5, base.y.toDouble(), base.z + 24.5)
        val pillager = EntityType.PILLAGER.create(helper.level)!!
        pillager.moveTo(base.x + 5.5, base.y.toDouble(), base.z + 9.5)
        pillager.finalizeSpawn(helper.level, helper.level.getCurrentDifficultyAt(pillager.blockPosition()),
            MobSpawnType.EVENT, null, null)
        helper.assertTrue(helper.level.addFreshEntity(pillager), "Test pillager must enter the level")
        pillager.setOnGround(true)
        pillager.setPersistenceRequired()
        pillager.persistentData.putString(InvasionRuntime.INVASION_TAG, "tcon-combat-proof")
        CampaignTconLoadouts.apply(pillager)
        InvasionRuntime.installCombatGoal(pillager)
        helper.assertTrue(pillager.mainHandItem.item is ModifiableCrossbowItem,
            "The campaign pillager must retain its TConstruct crossbow")
        pillager.target = target
        val initialDistance = pillager.distanceTo(target)
        var movedTowardTarget = false
        var fired = false
        helper.onEachTick {
            movedTowardTarget = movedTowardTarget || pillager.distanceTo(target) < initialDistance - 1.0f
            fired = fired || helper.level.getEntitiesOfClass(AbstractArrow::class.java,
                pillager.boundingBox.inflate(40.0)).any { it.owner === pillager }
        }
        helper.runAfterDelay(110) {
            helper.assertTrue(movedTowardTarget || pillager.distanceTo(target) < initialDistance - 1.0f,
                "TConstruct-equipped pillager must path toward its target; " +
                    "distance=${pillager.distanceTo(target)}, targetAlive=${target.isAlive}, " +
                    "initialDistance=$initialDistance, " +
                    "base=$base, targetPosition=${target.blockPosition()}, " +
                    "campaignId=${InvasionRuntime.invasionId(pillager)}, " +
                    "mainHand=${ForgeRegistries.ITEMS.getKey(pillager.mainHandItem.item)}, " +
                    "runningGoals=${pillager.goalSelector.availableGoals.filter { it.isRunning }.joinToString { it.goal.javaClass.simpleName }}, " +
                    "position=${pillager.blockPosition()}")
            val runningGoals = pillager.goalSelector.availableGoals
                .filter { it.isRunning }
                .joinToString { it.goal.javaClass.simpleName }
            helper.assertTrue(fired,
                "TConstruct-equipped pillager must fire a real arrow; distance=${pillager.distanceTo(target)}, " +
                    "lineOfSight=${pillager.sensing.hasLineOfSight(target)}, targetAlive=${target.isAlive}, " +
                    "runningGoals=[$runningGoals], position=${pillager.blockPosition()}")
            pillager.discard()
            helper.level.server.playerList.remove(target)
            channel.finishAndReleaseAll()
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena", batch = "campaign_policy", timeoutTicks = 80)
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
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena", batch = "campaign_collision", timeoutTicks = 100)
    fun loadedSurfaceGateCollisionFluidAndReachabilityAreExact(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        buildSurface(helper, base)
        val fake = fake(helper, "route")
        fake.setGameMode(GameType.SURVIVAL)
        fake.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
        val witness = fixtureWitness(helper, base.offset(1, 0, 7), fake.blockPosition())
        val selected = VirtualReachability.spawnPoint(witness, 6..8) { true }
        helper.assertTrue(selected != null, "The 3D witness must provide an arrival anchor in the requested band")
        val anchor = BlockPos(selected!!.x, selected.y, selected.z)
        val positions = LoadedSpawnChecks.memberPositions(helper.level, anchor, 3)
        helper.assertTrue(LoadedSpawnChecks.loadedRectangle(helper.level, anchor, fake.blockPosition()) &&
            positions.size == 3 && positions.first() == anchor && positions.toSet().size == 3 &&
            positions.drop(1).all { it.distManhattan(anchor) == 1 },
            "Packet placement must preserve the exact anchor and expand to connected, distinct nearby positions")
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
        helper.assertTrue(fixtureWitness(helper, base.offset(1, 0, 7), fake.blockPosition()).isEmpty(),
            "The virtual navigator must not supply a spawn witness through a closed gate")

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
        val remoteTarget = BlockPos((remoteChunkX shl 4) + 8, base.y, (remoteChunkZ shl 4) + 8)
        val remoteCell = VirtualCampaignTerrain(helper.level, 0).cell(Point(remoteTarget.x, remoteTarget.y, remoteTarget.z))
        helper.assertTrue(remoteCell == VirtualReachability.Cell.UNKNOWN && fixtureWitness(helper,
            remoteTarget, remoteTarget.east(8)).isEmpty(), "Missing terrain must not authorize an approach")
        helper.assertTrue(helper.level.chunkSource.getChunkNow(remoteChunkX, remoteChunkZ) == null,
            "A search for a 3D witness must not load, ticket, or generate its chunk")
        zombie.discard()
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena", batch = "campaign_stale_join", timeoutTicks = 100)
    fun endedCampaignRejectsFreshAndSavedMembersBeforeRegistration(helper: GameTestHelper) {
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        buildSurface(helper, base)
        val player = fake(helper, "stale-join")
        player.setGameMode(GameType.SURVIVAL)
        player.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
        val anchor = base.offset(3, 0, 7)
        val effect = DirectorEffect("stale-join", EffectKind.MATERIALIZE, player.uuid.toString(),
            "stale-join-invasion", EncounterKind.SCOUT, 0,
            BlockPoint("minecraft:overworld", anchor.x, anchor.y, anchor.z),
            listOf(MemberPlan("stale-join-member", "minecraft:pillager", 2)))
        helper.assertTrue(materializeFixture(helper, player, effect), "Active campaign control must materialize")
        val original = InvasionRuntime.liveMembers(helper.level.server, effect.invasionId).single()
        val saved = net.minecraft.nbt.CompoundTag()
        helper.assertTrue(original.save(saved), "Campaign member must be serializable as a chunk entity")
        original.remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK)
        fun reload(): Mob = EntityType.loadEntityRecursive(saved.copy(), helper.level) { it } as Mob
        fun registered(mob: Mob): Boolean = helper.level.getEntity(mob.uuid) === mob &&
            helper.level.getEntity(mob.id) === mob &&
            helper.level.getEntitiesOfClass(Mob::class.java, mob.boundingBox.inflate(1.0)).any { it === mob }

        // Exercise Forge's loaded-from-disk registration path, not a manually posted event.
        val active = reload()
        helper.level.addLegacyChunkEntities(java.util.stream.Stream.of(active))
        helper.assertTrue(!active.isRemoved && registered(active), "A saved active member must rejoin normally")
        InvasionRuntime.retire(helper.level.server, effect.invasionId)
        val data = PillagerWorldData.get(helper.level.server)
        val snapshot = data.snapshot()
        snapshot.tracks.getValue(effect.playerId).invasion = null
        data.restoreSnapshot(snapshot, InvasionRoster.runtimeSpec())

        val stale = reload()
        helper.level.addLegacyChunkEntities(java.util.stream.Stream.of(stale))
        fun assertRejected(mob: Mob) {
            helper.assertTrue(helper.level.getEntity(mob.uuid) == null && helper.level.getEntity(mob.id) == null &&
                helper.level.allEntities.none { it === mob } &&
                helper.level.getEntitiesOfClass(Mob::class.java, mob.boundingBox.inflate(1.0)).none { it === mob },
                "Ended campaign member must never enter lookup, tracking-visible storage or collision sections")
        }
        assertRejected(stale)
        val fresh = EntityType.PILLAGER.create(helper.level)!!
        fresh.moveTo(anchor.x + 0.5, anchor.y.toDouble(), anchor.z + 0.5)
        fresh.persistentData.putString(InvasionRuntime.INVASION_TAG, effect.invasionId)
        helper.assertTrue(!helper.level.addFreshEntity(fresh), "Fresh stale members must report rejected registration")
        assertRejected(fresh)
        helper.runAfterDelay(2) {
            assertRejected(stale)
            assertRejected(fresh)
            helper.assertTrue(InvasionRuntime.liveMembers(helper.level.server, effect.invasionId).isEmpty(),
                "Rejected members must not inflate campaign population")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena", batch = "campaign_spawn", timeoutTicks = 100)
    fun eventAuthoredSpawnGetsTargetProvenanceAndCleansUp(helper: GameTestHelper) {
        regionalPresenceSurvivesNativeSaveAndBlocksFreshPackets(helper)
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        buildSurface(helper, base)
        val fake = fake(helper, "spawn")
        fake.setGameMode(GameType.SURVIVAL)
        fake.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
        val anchor = base.offset(3, 0, 7)
        helper.assertTrue(LoadedSpawnChecks.loadedRectangle(helper.level, anchor, fake.blockPosition()),
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
            helper.assertTrue(materializeFixture(helper, fake, effect),
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
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena", batch = "campaign_singlepath", timeoutTicks = 100)
    fun laterPacketsReuseTheSharedRouteButRecheckItAtInsertion(helper: GameTestHelper) {
        val level = helper.level
        val server = level.server
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        buildSurface(helper, base)
        val player = ServerPlayer(server, level,
            GameProfile(UUID.nameUUIDFromBytes("campaign-test-shared-route".toByteArray()), "shared_route"))
        val connection = Connection(PacketFlow.SERVERBOUND)
        val channel = EmbeddedChannel(connection)
        val id = player.uuid.toString()
        val invasionId = "packet-shared-route"
        server.playerList.placeNewPlayer(connection, player)
        try {
            player.setGameMode(GameType.SURVIVAL)
            player.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
            val target = BlockPoint("minecraft:overworld", player.blockX, player.blockY, player.blockZ)
            establishPresence(player, target)
            val members = (1..18).map { MemberPlan("shared-proof-$it", "minecraft:pillager", 2) }
            val data = PillagerWorldData.get(server)
            val staged = data.snapshot()
            staged.tracks.getOrPut(id) { PlayerPressureTrack(id) }.invasion = InvasionState(
                invasionId, EncounterKind.ASSAULT, id, listOf(id), 0, listOf(WavePlan(0, 36, members)), phase = InvasionPhase.ACTIVE)
            data.restoreSnapshot(staged, InvasionRoster.runtimeSpec())
            val request = VirtualCampaignRouting.Request(id, invasionId,
                BlockPoint(target.dimension, base.x + 1, base.y, base.z + 7), target, id)
            VirtualCampaignRouting.enqueue(level, request, target, VirtualCampaignRouting.Band(8, 8), emptyList(), 6)
            repeat(4) { VirtualCampaignRouting.tick() }
            val anchor = VirtualCampaignRouting.poll(request)?.route?.singleOrNull()
                ?: error("Open connected fixture did not obtain its shared proof")
            fun packet(index: Int) = DirectorEffect("shared-proof-packet-$index", EffectKind.MATERIALIZE,
                id, invasionId, EncounterKind.ASSAULT, 0, anchor, members.drop(index * 6).take(6))
            repeat(2) { index ->
                helper.assertTrue(InvasionRuntime.execute(server, listOf(packet(index))).single().successful,
                    "Packet $index must materialize without submitting another route search")
                val live = InvasionRuntime.liveMembers(server, invasionId)
                helper.assertTrue(live.size == 6 && live.all { it.target === player && it.navigation.path == null },
                    "A shared witness must admit the whole packet without prescribing per-mob navigation")
                InvasionRuntime.retire(server, invasionId)
            }
            for (z in 6..8) for (y in 0..1)
                level.setBlockAndUpdate(base.offset(7, y, z), Blocks.STONE.defaultBlockState())
            helper.assertTrue(!InvasionRuntime.execute(server, listOf(packet(2))).single().successful &&
                InvasionRuntime.liveMembers(server, invasionId).isEmpty(),
                "Earlier packets do not exempt a later packet from live witness validation")
        } finally {
            InvasionRuntime.retire(server, invasionId)
            VirtualCampaignRouting.discard(invasionId)
            val data = PillagerWorldData.get(server)
            val clean = data.snapshot()
            clean.tracks.remove(id)
            clean.regionalPresence.remove(id)
            data.restoreSnapshot(clean, InvasionRoster.runtimeSpec())
            server.playerList.remove(player)
            channel.finishAndReleaseAll()
        }
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena", batch = "campaign_sealed", timeoutTicks = 120)
    fun sealedDefenseDoesNotMaterializeOnEitherSide(helper: GameTestHelper) {
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
        )
        helper.assertTrue(!materializeFixture(helper, fake, effect),
            "An exterior anchor without a route to the player must not authorize a packet")
        helper.assertTrue(InvasionRuntime.liveMembers(helper.level.server, effect.invasionId).isEmpty(),
            "The sealed defense must produce no members on either side")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena", batch = "campaign_stale_route", timeoutTicks = 120)
    fun closedGateRevokesTheFullRouteAtInsertion(helper: GameTestHelper) {
        val level = helper.level
        val server = level.server
        val base = helper.absolutePos(BlockPos(1, 2, 1))
        buildSurface(helper, base)
        val gate = base.offset(7, 0, 7)
        for (z in listOf(6, 8)) for (y in 0..1)
            level.setBlockAndUpdate(base.offset(7, y, z), Blocks.STONE.defaultBlockState())
        val player = ServerPlayer(server, level,
            GameProfile(UUID.nameUUIDFromBytes("campaign-test-stale-route".toByteArray()), "stale_route"))
        val connection = Connection(PacketFlow.SERVERBOUND)
        val channel = EmbeddedChannel(connection)
        server.playerList.placeNewPlayer(connection, player)
        try {
            player.setGameMode(GameType.SURVIVAL)
            player.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
            val target = BlockPoint("minecraft:overworld", player.blockX, player.blockY, player.blockZ)
            establishPresence(player, target)
            val id = player.uuid.toString()
            val invasionId = "commit-witness-regression"
            val request = VirtualCampaignRouting.Request(id, invasionId,
                BlockPoint(target.dimension, base.x + 1, base.y, base.z + 7), target, id)
            val members = (1..3).map { MemberPlan("commit-witness-$it", "minecraft:pillager", 2) }
            val data = PillagerWorldData.get(server)
            val staged = data.snapshot()
            staged.tracks.getOrPut(id) { PlayerPressureTrack(id) }.invasion = InvasionState(
                invasionId, EncounterKind.ASSAULT, id, listOf(id), 0, listOf(WavePlan(0, 6, members)), phase = InvasionPhase.ACTIVE)
            data.restoreSnapshot(staged, InvasionRoster.runtimeSpec())
            fun prepare(): DirectorEffect {
                VirtualCampaignRouting.enqueue(level, request, target, VirtualCampaignRouting.Band(8, 8), emptyList(), members.size)
                repeat(4) { VirtualCampaignRouting.tick() }
                val anchor = VirtualCampaignRouting.poll(request)?.route?.singleOrNull()
                    ?: error("Open connected fixture did not obtain a full-route proof")
                return DirectorEffect("commit-witness", EffectKind.MATERIALIZE, id, invasionId, EncounterKind.ASSAULT,
                    0, anchor, members)
            }
            val stale = prepare()
            for (y in 0..1) level.setBlockAndUpdate(gate.above(y), Blocks.STONE.defaultBlockState())
            helper.assertTrue(!InvasionRuntime.execute(server, listOf(stale)).single().successful,
                "The live commit check must revoke a proof after its passage is sealed")
            helper.assertTrue(InvasionRuntime.liveMembers(server, invasionId).isEmpty(), "A stale witness must not spawn even one recruit")
            helper.assertTrue(!InvasionRuntime.execute(server, listOf(stale)).single().successful,
                "An anchor without a current proof must not fall back to native pathfinding")
            for (y in 0..1) level.setBlockAndUpdate(gate.above(y), Blocks.AIR.defaultBlockState())
            helper.assertTrue(InvasionRuntime.execute(server, listOf(prepare())).single().successful,
                "A fresh proof from the same departure must work after reopening")
            val live = InvasionRuntime.liveMembers(server, invasionId)
            helper.assertTrue(live.size == 3 && live.all { it.target === player }, "The reopened passage must deliver the complete packet")
        } finally {
            InvasionRuntime.retire(server, "commit-witness-regression")
            VirtualCampaignRouting.discard("commit-witness-regression")
            val data = PillagerWorldData.get(server)
            val clean = data.snapshot()
            clean.tracks.remove(player.uuid.toString())
            clean.regionalPresence.remove(player.uuid.toString())
            data.restoreSnapshot(clean, InvasionRoster.runtimeSpec())
            server.playerList.remove(player)
            channel.finishAndReleaseAll()
        }
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena", batch = "campaign_retry", timeoutTicks = 160)
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
            approachMinimumBlocks = 2, approachMaximumBlocks = 4,
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
                    BlockPoint(target.dimension, outside.x, outside.y, outside.z))),
        ))).effects.single { it.kind == EffectKind.MATERIALIZE }
        helper.assertTrue(!materializeFixture(helper, fake, rejected),
            "The 3D navigator must reject the supplied approach outside the closed defense")
        director.transition(DirectorFrame(0, effectResults = listOf(EffectResult(rejected.effectId, false))))
        invasion = director.snapshot().tracks.getValue(playerId).invasion!!
        helper.assertTrue(invasion.usedAnchors.single() == rejected.anchor,
            "The rejected exact approach must be retained as a retry exclusion")

        val reachable = base.offset(9, 0, 7)
        val retried = director.transition(DirectorFrame(rules.normalPacketSpacingTicks, players = listOf(player),
            strategicRoutes = listOf(StrategicRouteObservation(playerId, invasion.invasionId, target, 2,
                listOf(BlockPoint(target.dimension, reachable.x + 3, reachable.y, reachable.z),
                    BlockPoint(target.dimension, reachable.x, reachable.y, reachable.z))),
            ))).effects.single { it.kind == EffectKind.MATERIALIZE }
        helper.assertTrue(retried.anchor == BlockPoint(target.dimension, reachable.x, reachable.y, reachable.z),
            "Retry must advance to the alternate anchor instead of recycling the rejected one")
        helper.assertTrue(materializeFixture(helper, fake, retried),
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
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena", batch = "campaign_concurrent", timeoutTicks = 200)
    fun threeSeparatedEncountersRetryAndRemainConcurrent(helper: GameTestHelper) {
        // Headless native participants exercise server materialization, not client transport.
        val bases = (0..2).map { helper.absolutePos(BlockPos(1 + it * 512, 2, 1)) }
        val tickets = bases.flatMap { base ->
            listOf(base, base.offset(14, 0, 0), base.offset(0, 0, 14), base.offset(14, 0, 14)).map(::ChunkPos)
        }.distinct()
        tickets.forEach { helper.level.chunkSource.addRegionTicket(TicketType.FORCED, it, 2, it) }
        val players = bases.mapIndexed { index, base ->
            buildSurface(helper, base)
            for (z in 6..8) for (y in 0..1)
                helper.level.setBlockAndUpdate(base.offset(7, y, z), Blocks.STONE.defaultBlockState())
            fake(helper, "concurrent-$index").also { player ->
                player.setGameMode(GameType.SURVIVAL)
                player.moveTo(base.x + 11.5, base.y.toDouble(), base.z + 7.5)
                establishPresence(player, BlockPoint("minecraft:overworld", player.blockX, player.blockY, player.blockZ))
            }
        }
        // Remote arenas are outside the template's implicit tickets. Wait for actual native
        // visibility before spawning; a successful insertion into a hidden chunk is not proof.
        helper.startSequence().thenWaitUntil {
            bases.forEach { base -> helper.assertTrue(helper.level.isPositionEntityTicking(base.offset(9, 0, 7)) &&
                helper.level.isPositionEntityTicking(base.offset(11, 0, 7)), "All remote approaches must be natively entity-ticking") }
        }.thenExecute {
        helper.assertTrue(players.all { InvasionRuntime.freshTargetEligible(it) },
            "All three native Survival targets must satisfy production familiarity and admission")
        val observations = players.map { player -> PlayerObservation(player.uuid.toString(), true, true, true,
            BlockPoint("minecraft:overworld", player.blockX, player.blockY, player.blockZ)) }
        val rules = InvasionRules(scoutWindowMinTicks = 0, scoutWindowMaxTicks = 0,
            approachMinimumBlocks = 2, approachMaximumBlocks = 4,
            strategicOriginMinimumBlocks = 5, strategicOriginMaximumBlocks = 6)
        val director = InvasionDirector.create(183, InvasionRuntimeSpec.create(rules,
            listOf(RecruitSpec("minecraft:pillager", 2, 0, RecruitRole.RANGED))))
        director.transition(DirectorFrame(0, commands = observations.map {
            DirectorCommand.Force(it.playerId, EncounterKind.SCOUT, expediteTravel = true)
        }))
        director.transition(DirectorFrame(0, players = observations))
        fun routes(reachable: Boolean) = observations.mapIndexed { index, observation ->
            val invasion = director.snapshot().tracks.getValue(observation.playerId).invasion!!
            val pos = bases[index].offset(if (reachable) 9 else 3, 0, 7)
            StrategicRouteObservation(observation.playerId, invasion.invasionId, observation.position!!,
                if (reachable) 2 else 1, listOf(
                    BlockPoint("minecraft:overworld", pos.x + (if (reachable) 3 else -3), pos.y, pos.z),
                    BlockPoint("minecraft:overworld", pos.x, pos.y, pos.z)))
        }
        val rejected = director.transition(DirectorFrame(0, players = observations, strategicRoutes = routes(false)))
            .effects.filter { it.kind == EffectKind.MATERIALIZE }
        helper.assertTrue(rejected.size == 3, "All separated eligible targets must receive independent encounters")
        director.transition(DirectorFrame(0, effectResults = rejected.map { effect ->
            val player = players.single { it.uuid.toString() == effect.playerId }
            helper.assertTrue(!materializeFixture(helper, player, effect), "Closed loaded routes must fail exact navigation")
            EffectResult(effect.effectId, false)
        }))
        rejected.forEach { effect -> helper.assertTrue(
            director.snapshot().tracks.getValue(effect.playerId).invasion!!.usedAnchors.contains(effect.anchor),
            "Every encounter must retain its own failed anchor") }
        val retried = director.transition(DirectorFrame(rules.normalPacketSpacingTicks, players = observations,
            strategicRoutes = routes(true))).effects.filter { it.kind == EffectKind.MATERIALIZE }
        helper.assertTrue(retried.size == 3, "All three encounters must retry through the production director")
        retried.forEach { effect ->
            helper.assertTrue(materializeFixture(helper, players.single { it.uuid.toString() == effect.playerId }, effect),
                "Each same-side loaded retry must materialize")
        }
        director.transition(DirectorFrame(0, players = observations,
            effectResults = retried.map { EffectResult(it.effectId, true) },
            liveCampaignMobs = InvasionRuntime.liveCampaignPopulation(helper.level.server)))
        helper.assertTrue(observations.all { director.snapshot().tracks.getValue(it.playerId).invasion?.phase == InvasionPhase.ACTIVE },
            "All three production Director encounters must be active together after native acknowledgements")
        helper.assertTrue(retried.map { it.invasionId }.toSet().size == 3, "Concurrent encounter IDs must be distinct")
        retried.forEach { effect ->
            val target = players.single { it.uuid.toString() == effect.playerId }
            val members = InvasionRuntime.liveMembers(helper.level.server, effect.invasionId)
            helper.assertTrue(members.size == effect.members.size && members.isNotEmpty() &&
                members.all { it.target === target && it.isAlive && !it.isRemoved },
                "All encounters must coexist with their own living members and exact target: " +
                    "id=${effect.invasionId},planned=${effect.members.size},actual=${members.size}," +
                    "targets=${members.map { it.target?.uuid }},expected=${target.uuid}")
        }
        retried.forEach { InvasionRuntime.retire(helper.level.server, it.invasionId) }
        tickets.forEach { helper.level.chunkSource.removeRegionTicket(TicketType.FORCED, it, 2, it) }
        }.thenSucceed()
    }

    @JvmStatic
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena", batch = "campaign_schedule", timeoutTicks = 200)
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
            approachMinimumBlocks = 2, approachMaximumBlocks = 10, strategicOriginMinimumBlocks = 11,
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
                    listOf(anchor)))
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
                    EffectKind.MATERIALIZE -> materializeFixture(helper, fake, effect)
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
    // Other same-batch scenarios materialize hundreds of members synchronously. Let the native
    // 20-tick recent-spawn window expire before creating this independent full-health target.
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena", batch = "campaign_lethality", timeoutTicks = 600, setupTicks = 21)
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
        establishPresence(target, targetPoint)
        val player = PlayerObservation(playerId, true, true, true, targetPoint)
        PillagerCampaignsEvents.advance(helper.level.server, 0,
            commands = listOf(DirectorCommand.Force(playerId, EncounterKind.ASSAULT,
                expediteTravel = true, intensity = 0)), playersOverride = listOf(player))
        val invasion = PillagerWorldData.get(helper.level.server).snapshot().tracks.getValue(playerId).invasion!!
        val invasionId = invasion.invasionId
        val memberCount = invasion.waves[0].members.size
        // Stage a short, real route through the arena's opening for this combat fixture.
        // The scheduler and materializer consume an actual transient witness, not an injected anchor.
        val data = PillagerWorldData.get(helper.level.server)
        val staged = data.snapshot()
        val origin = BlockPoint(targetPoint.dimension, base.x, base.y, base.z + 7)
        staged.tracks.getValue(playerId).invasion!!.strategicOrigin = origin
        data.restoreSnapshot(staged, runtime)
        VirtualCampaignRouting.enqueue(helper.level,
            VirtualCampaignRouting.Request(playerId, invasionId, origin, targetPoint, playerId), targetPoint,
            VirtualCampaignRouting.Band(8, 8), emptyList(), minOf(memberCount, 6))
        repeat(4) { VirtualCampaignRouting.tick() }
        PillagerCampaignsEvents.advance(helper.level.server, 0, playersOverride = listOf(player))
        var dispatches = 0
        while (InvasionRuntime.liveMembers(helper.level.server, invasionId).size < memberCount && dispatches++ < 10) {
            PillagerCampaignsEvents.advance(helper.level.server, runtime.rules.normalPacketSpacingTicks,
                playersOverride = listOf(player))
        }
        val attackers = InvasionRuntime.liveMembers(helper.level.server, invasionId)
        helper.assertTrue(attackers.isNotEmpty() && attackers.all { it.target === target },
            "The rate-limited authored assault packet must spawn targeting the Survival player; " +
                "attackerCount=${attackers.size}, rateCounter=${InvasionRuntime.spawnsLastSecond(helper.level.server.overworld().gameTime)}, " +
                "actualTargetUUIDs=${attackers.map { it.target?.uuid }}, expectedTargetUUID=${target.uuid}")
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
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena", batch = "campaign_unload", timeoutTicks = 360)
    fun changedTerrainSurvivesActualChunkUnloadWithoutReloadingIt(helper: GameTestHelper) {
        val level = helper.level
        val origin = helper.absolutePos(BlockPos.ZERO)
        val chunk = ChunkPos((origin.x shr 4) + 40, (origin.z shr 4) + 40)
        level.chunkSource.addRegionTicket(TicketType.UNKNOWN, chunk, 2, chunk)
        level.getChunk(chunk.x, chunk.z)
        val sample = Point(chunk.minBlockX + 8, level.minBuildHeight + 7, chunk.minBlockZ + 8)
        val position = BlockPos(sample.x, sample.y, sample.z)
        val terrain = VirtualCampaignTerrain(level, 1)
        terrain.beginSlice()
        helper.assertTrue(terrain.cell(sample) == VirtualReachability.Cell.CLEAR, "Fixture must begin with an empty cell")
        level.setBlockAndUpdate(position, Blocks.STONE.defaultBlockState())
        terrain.beginSlice()
        helper.assertTrue(terrain.cell(sample) == VirtualReachability.Cell.SUPPORT && terrain.savedReads == 0,
            "The same live coordinate must see the edit without reading disk")
        level.chunkSource.removeRegionTicket(TicketType.UNKNOWN, chunk, 2, chunk)
        helper.runAfterDelay(200) {
            val saved = VirtualCampaignTerrain(level, 1)
            fun checkSaved() {
                helper.assertTrue(level.chunkSource.getChunkNow(chunk.x, chunk.z) == null,
                    "Reading the edited chunk must not reload it")
                saved.beginSlice()
                val cell = saved.cell(sample)
                if (cell == VirtualReachability.Cell.PENDING) { helper.runAfterDelay(1, ::checkSaved); return }
                helper.assertTrue(cell == VirtualReachability.Cell.SUPPORT && saved.liveReads == 0 && saved.savedReads == 1,
                    "The 3D navigator must read the actual edit from the saved palette")
                helper.succeed()
            }
            checkSaved()
        }
    }

    private fun materializeFixture(
        helper: GameTestHelper,
        player: ServerPlayer,
        effect: DirectorEffect,
    ): Boolean {
        // Direct insertion fixtures use short, resident 3D routes. The production path is
        // exercised separately with registered players and real departure-to-target proofs.
        val anchor = effect.anchor ?: return false
        if (fixtureWitness(helper, BlockPos(anchor.x, anchor.y, anchor.z), player.blockPosition()).isEmpty()) return false
        // Production publishes its Director transition before executing effects. Direct adapter
        // fixtures must likewise publish an active encounter, not rely on removed join ghosts.
        val data = PillagerWorldData.get(helper.level.server)
        val snapshot = data.snapshot()
        snapshot.tracks.getOrPut(effect.playerId) { PlayerPressureTrack(effect.playerId) }.invasion = InvasionState(
            effect.invasionId, effect.encounterKind, effect.playerId, listOf(effect.playerId), 0,
            listOf(WavePlan(effect.waveIndex, effect.members.sumOf { it.cost }, effect.members)),
            phase = InvasionPhase.ACTIVE,
        )
        data.restoreSnapshot(snapshot, InvasionRoster.runtimeSpec())
        val successful = InvasionRuntime.materializeAgainst(helper.level, player, effect)
        if (successful) InvasionRuntime.liveMembers(helper.level.server, effect.invasionId).forEach { mob ->
            helper.assertTrue(!mob.isRemoved && mob.isAlive && helper.level.getEntity(mob.uuid) === mob,
                "Successful fixture materialization must produce registered living members, not removed ghosts")
        }
        return successful
    }

    private fun fixtureWitness(helper: GameTestHelper, from: BlockPos, to: BlockPos): List<Point> {
        val start = Point(from.x, from.y, from.z)
        val goal = Point(to.x, to.y, to.z)
        val terrain = VirtualCampaignTerrain(helper.level, 0)
        val bounds = VirtualReachability.Bounds(minOf(start.x, goal.x) - 2..maxOf(start.x, goal.x) + 2,
            minOf(start.y, goal.y) - 2..maxOf(start.y, goal.y) + 2,
            minOf(start.z, goal.z) - 2..maxOf(start.z, goal.z) + 2)
        val search = VirtualReachability(start, bounds, { it == goal }, { it.lowerBoundTo(goal) }, 1_024,
            terrain::cell, retainWitness = true)
        return if (search.advance(1_024) is VirtualReachability.Result.Reachable) search.witness else emptyList()
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
        FakePlayer(helper.level, GameProfile(UUID.nameUUIDFromBytes("campaign-test-$suffix".toByteArray()), "campaign-test-$suffix")).also {
            // Existing materialization fixtures deliberately model an established home region.
            val base = helper.absolutePos(BlockPos(1, 2, 1))
            establishPresence(it, BlockPoint("minecraft:overworld", base.x, base.y, base.z))
        }

    private fun establishPresence(player: ServerPlayer, position: BlockPoint) {
        val presence = RegionalPresence()
        repeat(1_800) { presence.record(position, 20) }
        val data = PillagerWorldData.get(player.serverLevel().server)
        val snapshot = data.snapshot()
        snapshot.regionalPresence[player.uuid.toString()] = presence
        data.restoreSnapshot(snapshot, InvasionRoster.runtimeSpec())
    }
}
