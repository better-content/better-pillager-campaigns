package com.bettercontent.betterpillagercampaigns

import com.bettercontent.betterpillagercampaigns.core.DirectorCommand
import com.bettercontent.betterpillagercampaigns.core.EncounterKind
import com.bettercontent.betterpillagercampaigns.core.InvasionPhase
import com.bettercontent.betterpillagercampaigns.core.MemberDefeatObservation
import com.bettercontent.betterpillagercampaigns.data.PillagerWorldData
import com.bettercontent.betterpillagercampaigns.system.InvasionRuntime
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType

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
        Commands.literal(name).then(Commands.literal("routed").then(Commands.argument("player", EntityArgument.player())
            .executes { spawn(it.source, EntityArgument.getPlayer(it, "player"), kind, null) }
            .then(Commands.argument("intensity", IntegerArgumentType.integer(0, 5))
                .executes { spawn(it.source, EntityArgument.getPlayer(it, "player"), kind,
                    IntegerArgumentType.getInteger(it, "intensity")) })))

    private fun spawn(source: CommandSourceStack, player: ServerPlayer, kind: EncounterKind, intensity: Int?): Int {
        val observation = PillagerCampaignsEvents.observePlayer(player)
        if (!observation.eligible || !observation.surfaceEligible || !observation.physicallyAvailable || !observation.regionFamiliar) {
            source.sendFailure(Component.literal("Harness target must be a living Survival player at an established Overworld surface"))
            return 0
        }
        if (invasionFor(player) != null) {
            source.sendFailure(Component.literal("Player already belongs to an active campaign; reset it first"))
            return 0
        }
        PillagerCampaignsEvents.advance(source.server, 0L, listOf(
            DirectorCommand.Force(player.uuid.toString(), kind, expediteTravel = true, intensity = intensity),
        ), playersOverride = listOf(observation))
        val invasion = invasionFor(player)
        if (invasion == null) {
            source.sendFailure(Component.literal("Harness could not create a campaign for the target player"))
            return 0
        }
        PillagerCampaignsEvents.advance(source.server, 0L, playersOverride = listOf(observation))
        source.sendSuccess({ Component.literal(
            "Harness queued ${kind.name.lowercase()} ${invasion.invasionId} for ${player.scoreboardName}" +
                " intensity=${invasion.intensity}; validating the full route before spawning",
        ) }, true)
        return Command.SINGLE_SUCCESS
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

    private fun invasionFor(player: ServerPlayer) = PillagerWorldData.get(player.server).snapshot().let { snapshot ->
        val track = snapshot.tracks[player.uuid.toString()]
        track?.invasion ?: track?.joinedInvasionId?.let { joined ->
            snapshot.tracks.values.mapNotNull { it.invasion }.firstOrNull { it.invasionId == joined }
        }
    }
}
