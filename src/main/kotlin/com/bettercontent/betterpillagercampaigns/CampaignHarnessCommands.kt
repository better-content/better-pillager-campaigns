package com.bettercontent.betterpillagercampaigns

import com.bettercontent.betterpillagercampaigns.core.BlockPoint
import com.bettercontent.betterpillagercampaigns.core.DirectorCommand
import com.bettercontent.betterpillagercampaigns.core.EncounterKind
import com.bettercontent.betterpillagercampaigns.core.InvasionPhase
import com.bettercontent.betterpillagercampaigns.core.MemberDefeatObservation
import com.bettercontent.betterpillagercampaigns.core.StrategicFrontier
import com.bettercontent.betterpillagercampaigns.core.StrategicRouteObservation
import com.bettercontent.betterpillagercampaigns.data.PillagerWorldData
import com.bettercontent.betterpillagercampaigns.data.TerrainAtlasData
import com.bettercontent.betterpillagercampaigns.system.InvasionRuntime
import com.bettercontent.betterpillagercampaigns.system.SurfaceGridSampler
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level

object CampaignHarnessCommands {
    private const val ENABLED_PROPERTY = "better_pillager_campaigns.harness"

    fun enabled(): Boolean = java.lang.Boolean.getBoolean(ENABLED_PROPERTY)

    fun register(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("harness")
        .requires { it.hasPermission(4) }
        .then(Commands.literal("spawn")
            .then(spawnKind("scout", EncounterKind.SCOUT))
            .then(spawnKind("assault", EncounterKind.ASSAULT)))
        .then(Commands.literal("next_wave")
            .then(Commands.argument("player", EntityArgument.player())
                .executes { nextWave(it.source, EntityArgument.getPlayer(it, "player")) }))
        .then(Commands.literal("protect")
            .then(Commands.argument("player", EntityArgument.player())
                .executes { protect(it.source, EntityArgument.getPlayer(it, "player")) }))

    private fun spawnKind(name: String, kind: EncounterKind): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name)
            .then(spawnMode("immediate", kind, immediate = true))
            .then(spawnMode("routed", kind, immediate = false))

    private fun spawnMode(
        name: String,
        kind: EncounterKind,
        immediate: Boolean,
    ): LiteralArgumentBuilder<CommandSourceStack> {
        val player = Commands.argument("player", EntityArgument.player())
            .executes { spawn(it.source, EntityArgument.getPlayer(it, "player"), kind, immediate, null) }
            .then(Commands.argument("intensity", IntegerArgumentType.integer(0, 5))
                .executes { spawn(it.source, EntityArgument.getPlayer(it, "player"), kind, immediate,
                    IntegerArgumentType.getInteger(it, "intensity")) })
        return Commands.literal(name).then(player)
    }

    private fun spawn(
        source: CommandSourceStack,
        player: ServerPlayer,
        kind: EncounterKind,
        immediate: Boolean,
        intensity: Int?,
    ): Int {
        if (player.serverLevel().dimension() != Level.OVERWORLD ||
            player.gameMode.gameModeForPlayer != GameType.SURVIVAL || !player.isAlive) {
            source.sendFailure(Component.literal("Harness target must be a living Overworld Survival player"))
            return 0
        }
        if (invasionFor(player) != null) {
            source.sendFailure(Component.literal("Player already belongs to an active campaign; reset it first"))
            return 0
        }

        if (!immediate) {
            val refreshed = PillagerCampaignsEvents.refreshLoadedTerrain(player)
            PillagerCampaignsEvents.advance(source.server, 0L, listOf(
                DirectorCommand.Force(player.uuid.toString(), kind, expediteTravel = true, intensity = intensity),
            ))
            val invasion = invasionFor(player)
            if (invasion == null) {
                source.sendFailure(Component.literal("Harness could not create a campaign for the target player"))
                return 0
            }
            val (anchorPoint, attempted) = tryLoadedApproaches(source, player, immediate = false)
            val active = invasionFor(player)
            if (anchorPoint == null || active?.phase != InvasionPhase.ACTIVE) {
                cleanupFailedStart(player)
                source.sendFailure(Component.literal(
                    "Routed campaign could not validate a loaded approach after $attempted candidate(s)",
                ))
                return 0
            }
            source.sendSuccess({ Component.literal(
                "Harness started routed ${kind.name.lowercase()} for ${player.scoreboardName}" +
                    " intensity=${intensity ?: "policy"}; refreshed=$refreshed loaded chunks; " +
                    "anchor=$anchorPoint attempts=$attempted; virtual travel is expedited and local routing remains required",
            ) }, true)
            return Command.SINGLE_SUCCESS
        }

        val observation = PillagerCampaignsEvents.observePlayer(player)
        PillagerCampaignsEvents.advance(source.server, 0L, listOf(
            DirectorCommand.Force(player.uuid.toString(), kind, expediteTravel = true, intensity = intensity),
        ), playersOverride = listOf(observation))
        val invasion = invasionFor(player)
        if (invasion == null) {
            source.sendFailure(Component.literal("Harness could not create a campaign for the target player"))
            return 0
        }
        val (anchorPoint, attempted) = tryLoadedApproaches(source, player, immediate = true)
        val active = invasionFor(player)
        if (anchorPoint == null || active?.validatedAnchor != anchorPoint) {
            cleanupFailedStart(player)
            source.sendFailure(Component.literal(
                "Immediate campaign lead could not validate a loaded approach after $attempted candidate(s)",
            ))
            return 0
        }
        source.sendSuccess({ Component.literal(
            "Harness started immediate ${kind.name.lowercase()} for ${player.scoreboardName}" +
                " intensity=${active.intensity} anchor=$anchorPoint attempts=$attempted" +
                " phase=${active.phase.name.lowercase()}",
        ) }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun tryLoadedApproaches(
        source: CommandSourceStack,
        player: ServerPlayer,
        immediate: Boolean,
    ): Pair<BlockPoint?, Int> {
        val rules = PillagerCampaignsConfig.rules()
        val rejected = mutableListOf<BlockPos>()
        repeat(8) {
            val invasion = invasionFor(player) ?: return null to rejected.size
            if (invasion.phase != InvasionPhase.APPROACHING) return null to rejected.size
            val packetSize = minOf(rules.maximumSpawnsPerTick, 6 * invasion.participantPlayerIds.size)
            val anchor = SurfaceGridSampler.immediateAnchor(
                player.serverLevel(), player.blockPosition(), rules.approachMinimumBlocks,
                rules.approachMaximumBlocks, packetSize, rejected,
            ) ?: return null to rejected.size
            rejected += anchor
            val target = invasion.routeTarget ?: BlockPoint(
                player.serverLevel().dimension().location().toString(), player.blockX, player.blockY, player.blockZ,
            )
            val anchorPoint = BlockPoint(target.dimension, anchor.x, anchor.y, anchor.z)
            PillagerCampaignsEvents.advance(source.server, 0L, strategicRoutes = listOf(
                StrategicRouteObservation(
                    invasion.targetPlayerId, invasion.invasionId, target,
                    if (immediate) -2L else TerrainAtlasData.get(source.server).revision,
                    listOf(anchorPoint), StrategicFrontier.OPEN,
                ),
            ), playersOverride = if (immediate) listOf(PillagerCampaignsEvents.observePlayer(player)) else null)
            var active = invasionFor(player)
            // A rejected packet leaves the next packet behind the director's spacing clock.
            // Give a newly accepted route its scheduled packet before choosing another anchor.
            if (active?.phase == InvasionPhase.READY_TO_MATERIALIZE) {
                PillagerCampaignsEvents.advance(
                    source.server, rules.normalPacketSpacingTicks * 3,
                    playersOverride = if (immediate) listOf(PillagerCampaignsEvents.observePlayer(player)) else null,
                )
                active = invasionFor(player)
            }
            if (active?.validatedAnchor == anchorPoint && active.phase == InvasionPhase.ACTIVE) {
                return anchorPoint to rejected.size
            }
        }
        return null to rejected.size
    }

    private fun nextWave(source: CommandSourceStack, player: ServerPlayer): Int {
        val invasion = invasionFor(player)
        if (invasion == null || invasion.kind != EncounterKind.ASSAULT) {
            source.sendFailure(Component.literal("Player does not belong to an active assault"))
            return 0
        }
        val wave = invasion.waves[invasion.currentWave]
        val snapshot = PillagerWorldData.get(source.server).snapshot()
        if (invasion.phase != InvasionPhase.ACTIVE || wave.materializedMembers < wave.members.size ||
            snapshot.pendingEffects.values.any { it.invasionId == invasion.invasionId }) {
            source.sendFailure(Component.literal("Current assault wave must be fully materialized with no pending effects"))
            return 0
        }
        InvasionRuntime.liveMembers(source.server, invasion.invasionId)
            .filter { it.persistentData.getInt(InvasionRuntime.WAVE_TAG) == invasion.currentWave }
            .forEach { it.discard() }
        PillagerCampaignsEvents.advance(
            source.server, 0L,
            injectedDefeats = wave.members.map { MemberDefeatObservation(invasion.invasionId, it.memberId) },
        )
        val after = invasionFor(player)
        val message = if (after == null) {
            "Harness completed assault for ${player.scoreboardName}"
        } else {
            "Harness advanced ${player.scoreboardName} to assault wave ${after.currentWave + 1}/${after.waves.size}"
        }
        source.sendSuccess({ Component.literal(message) }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun protect(source: CommandSourceStack, player: ServerPlayer): Int {
        if (player.gameMode.gameModeForPlayer != GameType.SURVIVAL || !player.isAlive) {
            source.sendFailure(Component.literal("Harness target must be a living Survival player before protection is enabled"))
            return 0
        }
        player.getAbilities().invulnerable = true
        player.onUpdateAbilities()
        source.sendSuccess({ Component.literal(
            "Protected ${player.scoreboardName} gamemode=${player.gameMode.gameModeForPlayer.name.lowercase()}" +
                " invulnerable=${player.getAbilities().invulnerable}",
        ) }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun cleanupFailedStart(player: ServerPlayer) {
        invasionFor(player)?.let { InvasionRuntime.retire(player.server, it.invasionId) }
        PillagerCampaignsEvents.advance(player.server, 0L, listOf(DirectorCommand.Reset(player.uuid.toString())))
    }

    private fun invasionFor(player: ServerPlayer) = PillagerWorldData.get(player.server).snapshot().let { snapshot ->
        val track = snapshot.tracks[player.uuid.toString()]
        track?.invasion ?: track?.joinedInvasionId?.let { joined ->
            snapshot.tracks.values.mapNotNull { it.invasion }.firstOrNull { it.invasionId == joined }
        }
    }
}
