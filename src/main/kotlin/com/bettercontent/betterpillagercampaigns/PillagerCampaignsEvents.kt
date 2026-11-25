package com.bettercontent.betterpillagercampaigns

import com.bettercontent.betterpillagercampaigns.core.*
import com.bettercontent.betterpillagercampaigns.data.PillagerWorldData
import com.bettercontent.betterpillagercampaigns.system.InvasionRoster
import com.bettercontent.betterpillagercampaigns.system.InvasionRuntime
import com.bettercontent.betterpillagercampaigns.system.LoadedSpawnChecks
import com.bettercontent.betterpillagercampaigns.system.InjuryCompat
import com.bettercontent.betterpillagercampaigns.system.CampaignHuntGoal
import com.bettercontent.betterpillagercampaigns.system.VirtualCampaignRouting
import net.minecraft.world.entity.projectile.Projectile
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelResource
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.living.LivingEvent
import net.minecraftforge.event.entity.living.LivingHurtEvent
import net.minecraftforge.event.level.ChunkEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

object PillagerCampaignsEvents {
    private val nextStrategicRouteAttempt = mutableMapOf<String, Long>()
    private val defeatedMembers = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val combatInvasions = ConcurrentHashMap.newKeySet<String>()
    private val deadTargets = ConcurrentHashMap.newKeySet<String>()

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        if (PillagerCampaignsConfig.disableVanillaPatrolSpawning.get()) {
            event.server.gameRules.getRule(GameRules.RULE_DO_PATROL_SPAWNING).set(false, event.server)
        }
        PillagerWorldData.get(event.server).attach(InvasionRoster.runtimeSpec())
        nextStrategicRouteAttempt.clear()
        VirtualCampaignRouting.clear()
    }

    @SubscribeEvent
    fun onServerStopped(event: ServerStoppedEvent) {
        VirtualCampaignRouting.clear()
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val server = event.server
        if (!PillagerCampaignsConfig.enabled.get()) return
        VirtualCampaignRouting.tick()
        // Sample the actual online state every tick, never the elapsed campaign/world clock.
        server.playerList.players.forEach(::recordOnlinePresence)
        val now = server.overworld().gameTime
        val interval = PillagerCampaignsConfig.intervalTicks.get().toLong()
        if (now % interval != 0L) return
        val observedPlayers = server.playerList.players.map(::observePlayer)
        val players = if (CampaignHarnessCommands.enabled()) {
            val snapshot = PillagerWorldData.get(server).snapshot()
            observedPlayers.filter { player ->
                val track = snapshot.tracks[player.playerId]
                track?.invasion != null || track?.joinedInvasionId != null
            }
        } else {
            observedPlayers
        }
        advance(server, interval, playersOverride = players)
    }

    internal fun advance(
        server: MinecraftServer,
        elapsedTicks: Long,
        commands: List<DirectorCommand> = emptyList(),
        injectedDefeats: List<MemberDefeatObservation> = emptyList(),
        playersOverride: List<PlayerObservation>? = null,
    ): DirectorTransition {
        val data = PillagerWorldData.get(server)
        val spec = InvasionRoster.runtimeSpec()
        val snapshot = data.snapshot()
        val now = server.overworld().gameTime
        val liveInvasions = snapshot.tracks.values.mapNotNull { it.invasion?.invasionId }.toSet()
        nextStrategicRouteAttempt.keys.retainAll(liveInvasions)
        VirtualCampaignRouting.retain(liveInvasions)
        val players = playersOverride ?: server.playerList.players.map(::observePlayer)
        val playersById = players.associateBy(PlayerObservation::playerId)
        val routes = snapshot.tracks.values.mapNotNull { track ->
            val invasion = track.invasion?.takeIf { it.phase == InvasionPhase.APPROACHING } ?: return@mapNotNull null
            if (players.none { it.playerId in invasion.participantPlayerIds && it.regionFamiliar && it.physicallyAvailable && it.eligible })
                return@mapNotNull null
            val target = invasion.routeTarget ?: return@mapNotNull null
            val localSearchTick = if (invasion.kind == EncounterKind.ASSAULT)
                (invasion.scheduledArrivalEligibleTick - spec.rules.assaultWarningSurfaceTicks).coerceAtLeast(0L)
            else invasion.scheduledArrivalEligibleTick
            if (invasion.strategicJourneyTotalMilliBlocks <= 0L ||
                invasion.strategicTravelMilliBlocks < invasion.strategicJourneyTotalMilliBlocks ||
                track.eligibleTicks < localSearchTick) return@mapNotNull null
            if (invasion.strategicRoute.isNotEmpty() && invasion.strategicFrontier == StrategicFrontier.OPEN)
                return@mapNotNull null
            val origin = invasion.strategicOrigin ?: return@mapNotNull null
            val pursued = InvasionDirector.chooseTarget(invasion, playersById)?.takeIf { it.surfaceEligible }
                ?: return@mapNotNull null
            val goal = pursued.position ?: return@mapNotNull null
            val request = VirtualCampaignRouting.Request(track.playerId, invasion.invasionId, origin, target, pursued.playerId)
            VirtualCampaignRouting.poll(request)?.let { observation ->
                val cooldown = when (observation.failureReason) {
                    "virtual_search_budget", "virtual_witness_budget", "virtual_route_deadline" -> 4L
                    else -> 1L
                }
                nextStrategicRouteAttempt[invasion.invasionId] = now + spec.rules.localApproachRetryTicks * cooldown
                return@mapNotNull observation
            }
            if (VirtualCampaignRouting.busy(request) || !VirtualCampaignRouting.hasCapacity() ||
                now < nextStrategicRouteAttempt.getOrDefault(invasion.invasionId, Long.MIN_VALUE)) return@mapNotNull null
            val band = VirtualCampaignRouting.Band(spec.rules.approachMinimumBlocks, spec.rules.approachMaximumBlocks)
            val wave = invasion.waves[invasion.currentWave]
            val packetSize = minOf(wave.members.size - wave.queuedMembers, 6 * invasion.participantPlayerIds.size,
                spec.rules.maximumSpawnsPerTick, spec.rules.maximumSpawnsPerSecond).coerceAtLeast(1)
            VirtualCampaignRouting.enqueue(server.overworld(), request, goal, band, invasion.usedAnchors, packetSize)
            null
        }
        val frame = DirectorFrame(
            elapsedTicks = elapsedTicks,
            players = players,
            strategicRoutes = routes,
            memberDefeats = defeatedMembers.toList().also { defeatedMembers.clear() }
                .map { (invasion, member) -> MemberDefeatObservation(invasion, member) } + injectedDefeats,
            combat = combatInvasions.toList().also { combatInvasions.clear() }.map(::CombatObservation),
            targetDeaths = deadTargets.toList().also { deadTargets.clear() }.map(::TargetDeathObservation),
            commands = commands,
            liveCampaignMobs = InvasionRuntime.liveCampaignPopulation(server),
            secondSpawnCount = InvasionRuntime.spawnsLastSecond(server.overworld().gameTime),
        )
        val transition = data.transition(frame, spec)
        val results = InvasionRuntime.execute(server, transition.effects)
        if (results.isNotEmpty()) data.transition(DirectorFrame(0L, effectResults = results), spec)
        return transition
    }

    @SubscribeEvent
    fun onChunkLoad(event: ChunkEvent.Load) = invalidateSavedRouteTerrain(event)

    @SubscribeEvent
    fun onChunkUnload(event: ChunkEvent.Unload) = invalidateSavedRouteTerrain(event)

    private fun invalidateSavedRouteTerrain(event: ChunkEvent) {
        val level = event.level as? net.minecraft.server.level.ServerLevel ?: return
        if (level.dimension() != Level.OVERWORLD || event.chunk !is net.minecraft.world.level.chunk.LevelChunk) return
        val pos = event.chunk.pos
        if (level.server.isSameThread) VirtualCampaignRouting.onResidencyChange(pos)
        else level.server.execute { VirtualCampaignRouting.onResidencyChange(pos) }
    }

    internal fun observePlayer(player: ServerPlayer): PlayerObservation {
        val eligible = player.serverLevel().dimension() == Level.OVERWORLD && player.gameMode.gameModeForPlayer == GameType.SURVIVAL
        return PlayerObservation(
            player.uuid.toString(), eligible,
            eligible && LoadedSpawnChecks.isNearSurface(player),
            physicallyAvailable = player.isAlive,
            position = BlockPoint(player.serverLevel().dimension().location().toString(), player.blockX, player.blockY, player.blockZ),
            lowHealth = InjuryCompat.semanticHealth(player) / player.maxHealth.coerceAtLeast(1.0f) < 0.35f,
            regionFamiliar = regionFamiliar(player),
        )
    }

    internal fun recordOnlinePresence(player: ServerPlayer) {
        if (!player.isAlive || player.gameMode.gameModeForPlayer != GameType.SURVIVAL) return
        PillagerWorldData.get(player.serverLevel().server).recordPresence(player.uuid.toString(), BlockPoint(
            player.serverLevel().dimension().location().toString(), player.blockX, player.blockY, player.blockZ))
    }

    internal fun regionFamiliar(player: ServerPlayer): Boolean = PillagerWorldData.get(player.serverLevel().server)
        .regionFamiliar(player.uuid.toString(), BlockPoint(player.serverLevel().dimension().location().toString(),
            player.blockX, player.blockY, player.blockZ))

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    fun onLivingDeath(event: LivingDeathEvent) {
        val invasion = InvasionRuntime.invasionId(event.entity)
        val member = InvasionRuntime.memberId(event.entity)
        if (invasion != null && member != null) defeatedMembers += invasion to member
        if (event.entity is ServerPlayer) deadTargets += event.entity.uuid.toString()
    }

    @SubscribeEvent
    fun onLivingHurt(event: LivingHurtEvent) {
        (event.source.entity as? Mob)?.takeIf { it.target === event.entity }?.let(CampaignHuntGoal::recordAttack)
        InvasionRuntime.invasionId(event.entity)?.let(combatInvasions::add)
        InvasionRuntime.invasionId(event.source.entity)?.let(combatInvasions::add)
        (event.entity as? Mob)?.takeIf { InvasionRuntime.invasionId(it) != null }
            ?.let { InvasionRuntime.recordScoutHit(it, event.source.entity) }
    }

    @SubscribeEvent
    fun onLivingTick(event: LivingEvent.LivingTickEvent) {
        (event.entity as? Mob)?.takeIf { InvasionRuntime.invasionId(it) != null }?.let(InvasionRuntime::maintainTarget)
    }

    @SubscribeEvent
    fun onEntityJoin(event: EntityJoinLevelEvent) {
        if (event.level.isClientSide) return
        ((event.entity as? Projectile)?.owner as? Mob)?.let(CampaignHuntGoal::recordAttack)
        val mob = event.entity as? Mob ?: return
        val invasionId = InvasionRuntime.invasionId(mob) ?: return
        val server = event.level.server ?: return
        val active = PillagerWorldData.get(server).snapshot().tracks.values.any { it.invasion?.invasionId == invasionId }
        if (!active) {
            // Join fires before the entity receives its world-removal callback. Discard alone
            // would still let Forge register and track an already-removed, unticking ghost.
            event.isCanceled = true
            mob.discard()
        } else InvasionRuntime.installCombatGoal(mob)
    }

    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        val root = Commands.literal("better_pillager_campaigns")
            .then(Commands.literal("status")
                .executes { status(it.source, null) }
                .then(Commands.argument("player", StringArgumentType.word()).executes { status(it.source, StringArgumentType.getString(it, "player")) }))
            .then(Commands.literal("force").requires { it.hasPermission(2) }
                .executes { force(it.source, null, EncounterKind.ASSAULT) }
                .then(Commands.literal("scout")
                    .executes { force(it.source, null, EncounterKind.SCOUT) }
                    .then(Commands.argument("player", StringArgumentType.word())
                        .executes { force(it.source, StringArgumentType.getString(it, "player"), EncounterKind.SCOUT) }))
                .then(Commands.literal("assault")
                    .executes { force(it.source, null, EncounterKind.ASSAULT) }
                    .then(Commands.argument("player", StringArgumentType.word())
                        .executes { force(it.source, StringArgumentType.getString(it, "player"), EncounterKind.ASSAULT) })))
            .then(Commands.literal("inspect").requires { it.hasPermission(2) }
                .executes { inspect(it.source, null) }
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes { inspect(it.source, StringArgumentType.getString(it, "player")) }))
            .then(Commands.literal("reset").requires { it.hasPermission(2) }
                .executes { reset(it.source, null) }
                .then(Commands.argument("player", StringArgumentType.word()).executes { reset(it.source, StringArgumentType.getString(it, "player")) }))
            .then(Commands.literal("export_runtime_spec").requires { it.hasPermission(2) }.executes { exportSpec(it.source) })
        if (CampaignHarnessCommands.enabled()) root.then(CampaignHarnessCommands.register())
        event.dispatcher.register(root)
    }

    private fun status(source: CommandSourceStack, name: String?): Int {
        val player = target(source, name)
        if (player == null) {
            source.sendFailure(Component.literal("Player is not online")); return 0
        }
        val track = PillagerWorldData.get(source.server).snapshot().tracks[player.uuid.toString()]
        val message = if (track == null) "pressure=uninitialized" else buildString {
            append("eligible_ticks=${track.eligibleTicks} next_scout=${track.nextScoutEligibleTick} next_assault=${track.nextAssaultEligibleTick} outcome_adjustment=${track.outcomeAdjustment}")
            track.invasion?.let {
                val journeyPercent = if (it.strategicJourneyTotalMilliBlocks <= 0L) 0L else
                    (it.strategicTravelMilliBlocks * 100L / it.strategicJourneyTotalMilliBlocks).coerceIn(0L, 100L)
                append(" encounter=${it.invasionId} kind=${it.kind.name.lowercase()} phase=${it.phase.name.lowercase()} wave=${it.currentWave + 1}/${it.waves.size} intensity=${it.intensity} members=${it.members.size} virtual_progress=${journeyPercent}% local_approach_ticks=${it.localApproachTicks} route_failure=${it.lastRouteFailure} origin=${it.strategicOrigin} position=${it.strategicPosition} frontier=${it.strategicFrontier.name.lowercase()} route_checked_tick=${it.routeCheckedAtTick}")
            }
        }
        source.sendSuccess({ Component.literal(message) }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun force(source: CommandSourceStack, name: String?, kind: EncounterKind): Int {
        val player = target(source, name)
        if (player == null) { source.sendFailure(Component.literal("Player is not online")); return 0 }
        val observation = observePlayer(player)
        if (!observation.eligible || !observation.surfaceEligible || !observation.physicallyAvailable || !observation.regionFamiliar) {
            source.sendFailure(Component.literal("Cannot force a campaign: ${player.scoreboardName} must be alive, near the Overworld surface in Survival, with 30 minutes of accumulated regional presence"))
            return 0
        }
        val data = PillagerWorldData.get(source.server)
        val spec = InvasionRoster.runtimeSpec()
        val before = data.snapshot()
        val existing = before.tracks[player.uuid.toString()]
        if (existing?.invasion != null || existing?.joinedInvasionId != null) {
            source.sendFailure(Component.literal("Cannot force a campaign: ${player.scoreboardName} already belongs to an active encounter"))
            return 0
        }
        advance(source.server, 0L,
            listOf(DirectorCommand.Force(player.uuid.toString(), kind, expediteTravel = true)),
            playersOverride = listOf(observation))
        val created = data.snapshot().tracks[player.uuid.toString()]?.invasion
        if (created == null) {
            data.restoreSnapshot(before, spec)
            source.sendFailure(Component.literal("Cannot force a campaign: the Director rejected the encounter before local materialization"))
            return 0
        }
        advance(source.server, 0L, playersOverride = listOf(observation))
        source.sendSuccess({ Component.literal("Queued ${kind.name.lowercase()} ${created.invasionId} for ${player.scoreboardName}; validating the full route before spawning") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun inspect(source: CommandSourceStack, name: String?): Int {
        val player = target(source, name)
        if (player == null) { source.sendFailure(Component.literal("Player is not online")); return 0 }
        val track = PillagerWorldData.get(source.server).snapshot().tracks[player.uuid.toString()]
        val invasion = track?.invasion
        if (invasion == null) {
            source.sendSuccess({ Component.literal("campaign_inspect player=${player.scoreboardName} encounter=none") }, false)
            return Command.SINGLE_SUCCESS
        }
        val live = InvasionRuntime.liveMembers(source.server, invasion.invasionId)
        val roster = live.mapNotNull { net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(it.type)?.toString() }
            .groupingBy { it }.eachCount().toSortedMap()
            .entries.joinToString(",") { "${it.key}=${it.value}" }
        val targeted = live.count { it.persistentData.hasUUID(InvasionRuntime.TARGET_TAG) &&
            it.persistentData.getUUID(InvasionRuntime.TARGET_TAG) == player.uuid && it.target === player }
        val provenanced = live.count { it.persistentData.getString(InvasionRuntime.INVASION_TAG) == invasion.invasionId &&
            it.persistentData.getString(InvasionRuntime.MEMBER_TAG).isNotBlank() }
        source.sendSuccess({ Component.literal(
            "campaign_inspect player=${player.scoreboardName} encounter=${invasion.invasionId} kind=${invasion.kind.name.lowercase()} phase=${invasion.phase.name.lowercase()} wave=${invasion.currentWave + 1}/${invasion.waves.size} planned=${invasion.waves[invasion.currentWave].members.size} queued=${invasion.waves[invasion.currentWave].queuedMembers} materialized=${invasion.waves[invasion.currentWave].materializedMembers} live=${live.size} targeted=$targeted provenanced=$provenanced virtual_travel=${invasion.strategicTravelMilliBlocks}/${invasion.strategicJourneyTotalMilliBlocks} local_approach_ticks=${invasion.localApproachTicks} route_failure=${invasion.lastRouteFailure} frontier=${invasion.strategicFrontier.name.lowercase()} origin=${invasion.strategicOrigin} anchor=${invasion.anchor} validated_anchor=${invasion.validatedAnchor} path_proof_pending=${invasion.anchor != invasion.validatedAnchor} roster=[$roster]") }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun reset(source: CommandSourceStack, name: String?): Int {
        val snapshot = PillagerWorldData.get(source.server).snapshot()
        val playerId = name?.let { source.server.playerList.getPlayerByName(it)?.uuid?.toString() }
        if (name != null && playerId == null) { source.sendFailure(Component.literal("Player is not online")); return 0 }
        snapshot.tracks.values.filter { playerId == null || it.playerId == playerId }.mapNotNull { it.invasion?.invasionId }
            .forEach { InvasionRuntime.retire(source.server, it) }
        advance(source.server, 0L, listOf(DirectorCommand.Reset(playerId)))
        source.sendSuccess({ Component.literal(if (playerId == null) "Reset all invasion pressure" else "Reset invasion pressure for $name") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun exportSpec(source: CommandSourceStack): Int {
        val directory = source.server.getWorldPath(LevelResource.ROOT).resolve("better_pillager_campaigns/exports")
        Files.createDirectories(directory)
        val target = directory.resolve("invasion-runtime-spec.json")
        val temporary = directory.resolve("invasion-runtime-spec.json.tmp")
        Files.writeString(temporary, Json { prettyPrint = true; encodeDefaults = true }.encodeToString(InvasionRoster.runtimeSpec()))
        runCatching { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
        source.sendSuccess({ Component.literal("Exported $target") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun target(source: CommandSourceStack, name: String?): ServerPlayer? =
        if (name != null) source.server.playerList.getPlayerByName(name) else source.player
}
