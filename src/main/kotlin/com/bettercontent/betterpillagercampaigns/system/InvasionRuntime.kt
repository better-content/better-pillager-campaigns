package com.bettercontent.betterpillagercampaigns.system

import com.bettercontent.betterpillagercampaigns.PillagerCampaignsMod
import com.bettercontent.betterpillagercampaigns.core.BlockPoint
import com.bettercontent.betterpillagercampaigns.core.DirectorEffect
import com.bettercontent.betterpillagercampaigns.core.EffectKind
import com.bettercontent.betterpillagercampaigns.core.EffectResult
import com.bettercontent.betterpillagercampaigns.data.PillagerWorldData
import com.bettercontent.gameplaynotices.GameplayNotice
import com.bettercontent.gameplaynotices.GameplayNotices
import com.bettercontent.gameplaynotices.NoticeTheme
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.monster.PatrollingMonster
import net.minecraft.world.entity.monster.Pillager
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.fml.ModList
import java.util.ArrayDeque

internal enum class CampaignTargetAvailability { LIVE_SAME_LEVEL, DEAD, ABSENT, DIFFERENT_LEVEL }

internal object CampaignTargetPolicy {
    fun mayPursue(availability: CampaignTargetAvailability): Boolean =
        availability == CampaignTargetAvailability.LIVE_SAME_LEVEL

    fun recentHit(now: Long, hitTick: Long): Boolean = now >= hitTick && now - hitTick < 200L
}

object InvasionRuntime {
    private val spawnTicks = ArrayDeque<Long>()
    const val INVASION_TAG = "PillagerCampaignsInvasion"
    const val MEMBER_TAG = "PillagerCampaignsMember"
    const val TARGET_TAG = "PillagerCampaignsTarget"
    const val KIND_TAG = "PillagerCampaignsKind"
    const val WAVE_TAG = "PillagerCampaignsWave"
    private const val LAST_ATTACKER_TAG = "PillagerCampaignsLastAttacker"
    private const val LAST_HIT_TICK_TAG = "PillagerCampaignsLastHitTick"

    fun execute(server: MinecraftServer, effects: List<DirectorEffect>): List<EffectResult> = effects.mapNotNull { effect ->
        when (effect.kind) {
            EffectKind.WARN -> EffectResult(effect.effectId, warn(server, effect))
            EffectKind.MATERIALIZE -> {
                val successful = materializeConnected(server, effect) ?: return@mapNotNull null
                if (successful && effect.announceWave) announceWave(server, effect)
                EffectResult(effect.effectId, successful)
            }
            EffectKind.RETIRE -> EffectResult(effect.effectId, retire(server, effect.invasionId))
        }
    }

    private fun warn(server: MinecraftServer, effect: DirectorEffect): Boolean {
        val recipients = warningPlayerIds(effect).mapNotNull { player(server, it) }
        recipients.forEach { player ->
            player.playNotifySound(SoundEvents.RAID_HORN.get(), SoundSource.HOSTILE, 1.1f, 0.85f)
            GameplayNotices.send(player, GameplayNotice("campaign:${effect.invasionId}:warning", NoticeTheme.COMBAT,
                Component.literal("Pillager assault forming"), Component.literal("You have two minutes.")))
        }
        return recipients.isNotEmpty()
    }

    private fun announceWave(server: MinecraftServer, effect: DirectorEffect) {
        val message = if (effect.encounterKind == com.bettercontent.betterpillagercampaigns.core.EncounterKind.ASSAULT)
            "Pillager assault wave ${effect.waveIndex + 1}/3 has arrived."
        else "A pillager scouting party has arrived."
        warningPlayerIds(effect).mapNotNull { player(server, it) }.forEach { recipient ->
            recipient.playNotifySound(SoundEvents.RAID_HORN.get(), SoundSource.HOSTILE, 0.85f, 1.05f)
            GameplayNotices.send(recipient, GameplayNotice("campaign:${effect.invasionId}:wave:${effect.waveIndex}",
                NoticeTheme.COMBAT, Component.literal(message), Component.literal("Defend your position.")))
        }
    }

    internal fun warningPlayerIds(effect: DirectorEffect): List<String> =
        (listOf(effect.playerId) + effect.participantPlayerIds).distinct()

    /** Null keeps the existing pending packet until nonblocking witness reads complete. */
    private fun materializeConnected(server: MinecraftServer, effect: DirectorEffect): Boolean? {
        val level = server.overworld()
        val target = VirtualCampaignRouting.targetId(effect.invasionId)?.let { player(server, it) }
            ?: return rejected(level, effect, "virtual_proof_missing_or_target_absent")
        val livePosition = BlockPoint(target.level().dimension().location().toString(), target.blockX, target.blockY, target.blockZ)
        return when (VirtualCampaignRouting.admission(level, effect.invasionId, effect.anchor, livePosition)) {
            VirtualCampaignRouting.Admission.WAIT -> null
            VirtualCampaignRouting.Admission.REJECT -> rejected(level, effect, "virtual_proof_invalid_at_insertion")
            VirtualCampaignRouting.Admission.READY -> materializeAgainst(level, target, effect)
        }
    }

    internal fun materializeAgainst(
        level: ServerLevel,
        target: ServerPlayer,
        effect: DirectorEffect,
    ): Boolean {
        val server = level.server
        if (!freshTargetEligible(target) || target.serverLevel() !== level) return rejected(level, effect, "target_ineligible")
        val anchor = effect.anchor?.takeIf { it.dimension == level.dimension().location().toString() } ?: return false
        val anchorPos = BlockPos(anchor.x, anchor.y, anchor.z)
        val targetPos = target.blockPosition()
        if (!LoadedSpawnChecks.loadedRectangle(level, anchorPos, targetPos)) return rejected(level, effect, "unloaded_approach")
        val existing = liveMembers(server, effect.invasionId).associateBy { it.persistentData.getString(MEMBER_TAG) }
        val missing = effect.members.filter { it.memberId !in existing }
        if (missing.isEmpty()) return true
        val positions = LoadedSpawnChecks.memberPositions(level, anchorPos, missing.size)
        if (positions.size != missing.size) return rejected(level, effect, "insufficient_positions")
        if (positions.any { !level.isPositionEntityTicking(it) }) return rejected(level, effect, "inactive_spawn_area")

        // Resolve the persisted encounter tier rather than inferring it from wave index: later
        // intensity tiers also have a first wave, and retries keep the same saved invasion.
        val firstTier = PillagerWorldData.get(server).snapshot().tracks[effect.playerId]?.invasion
            ?.takeIf { it.invasionId == effect.invasionId }?.intensity == 0
        val prepared = missing.zip(positions).mapNotNull { (member, pos) ->
            // Old saved tier-zero waves can still reference an archer from the previous roster.
            // Materialize that member as a native-crossbow pillager without changing saved IDs.
            val recruitId = if (firstTier && member.recruitId == "takesapillage:archer")
                "minecraft:pillager" else member.recruitId
            val type = ResourceLocation.tryParse(recruitId)?.let(ForgeRegistries.ENTITY_TYPES::getValue)
                ?: return@mapNotNull null
            val mob = type.create(level) as? Mob ?: return@mapNotNull null
            runCatching {
                mob.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, level.random.nextFloat() * 360f, 0f)
                require(exactCandidate(level, mob, pos))
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT,
                    null as SpawnGroupData?, null as CompoundTag?)
                require(LoadedSpawnChecks.loadedRectangle(level, anchorPos, targetPos))
                mob.setPersistenceRequired()
                mob.persistentData.putString(INVASION_TAG, effect.invasionId)
                mob.persistentData.putString(MEMBER_TAG, member.memberId)
                mob.persistentData.putUUID(TARGET_TAG, target.uuid)
                mob.persistentData.putString(KIND_TAG, effect.encounterKind.name.lowercase())
                mob.persistentData.putInt(WAVE_TAG, effect.waveIndex)
                mob.target = target
                if (firstTier) {
                    // Early recruits use native combat, not randomized TCon materials or
                    // armor inherited from optional recruits' finalizeSpawn equipment.
                    EquipmentSlot.values().filter { it.type == EquipmentSlot.Type.ARMOR || it == EquipmentSlot.OFFHAND }
                        .forEach { mob.setItemSlot(it, ItemStack.EMPTY) }
                    when (recruitId) {
                        "minecraft:pillager" -> mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.CROSSBOW))
                        "takesapillage:skirmisher" -> mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.STONE_SWORD))
                    }
                } else if (ModList.get().isLoaded("tconstruct")) {
                    CampaignTconLoadouts.apply(mob)
                }
                installCombatGoal(mob)
                if (mob is PatrollingMonster) {
                    mob.patrolTarget = targetPos
                    mob.isPatrolLeader = member == effect.members.first()
                }
                mob
            }.getOrElse { error ->
                rejected(level, effect, "recruit_preparation_failed")
                mob.discard()
                null
            }
        }
        if (prepared.size != missing.size || !LoadedSpawnChecks.loadedRectangle(level, anchorPos, targetPos)) {
            prepared.forEach(Entity::discard)
            return rejected(level, effect, "preparation_rollback")
        }
        val spawned = mutableListOf<Mob>()
        // Spawn admission already proved the whole route. Keep actual collision,
        // loaded-area and insertion checks without a second, follow-range-limited path search.
        prepared.forEach { mob ->
            if (level.addFreshEntity(mob)) spawned += mob
            else {
                spawned.forEach(Entity::discard)
                prepared.filter { it !in spawned }.forEach(Entity::discard)
                return rejected(level, effect, "member_insertion_rollback")
            }
        }
        if (!LoadedSpawnChecks.loadedRectangle(level, anchorPos, targetPos)) {
            spawned.forEach(Entity::discard)
            return rejected(level, effect, "loaded_boundary_rollback")
        }
        synchronized(spawnTicks) { repeat(spawned.size) { spawnTicks.addLast(server.overworld().gameTime) } }
        val log = com.bettercontent.betterpillagercampaigns.PlaytestDiagnostics.LOG
        if (log.enabled("materialization")) com.bettercontent.betterpillagercampaigns.diagnostics.FeatureTelemetry.packet(
            log, effect.invasionId, effect.waveIndex, spawned.size, effect.effectId)
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
            com.bettercontent.betterpillagercampaigns.api.CampaignMaterializedEvent(target, effect.invasionId, effect.waveIndex, spawned.size))
        return true
    }

    private fun rejected(level: ServerLevel, effect: DirectorEffect, reason: String): Boolean {
        val log = com.bettercontent.betterpillagercampaigns.PlaytestDiagnostics.LOG
        if (log.enabled("materialization")) com.bettercontent.betterpillagercampaigns.diagnostics.FeatureTelemetry.rejected(log,
            com.bettercontent.betterpillagercampaigns.diagnostics.PlaytestLog.Context.server(level.dimension().location().toString(), level.gameTime),
            effect.invasionId, effect.waveIndex, effect.effectId, reason)
        return false
    }

    internal fun exactCandidate(level: ServerLevel, mob: Mob, pos: BlockPos): Boolean {
        if (level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) == null) return false
        if (!level.worldBorder.isWithinBounds(pos)) return false
        if (!level.getFluidState(pos).isEmpty || !level.getFluidState(pos.above()).isEmpty) return false
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty) return false
        if (!level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty) return false
        return level.noCollision(mob)
    }

    fun retire(server: MinecraftServer, invasionId: String): Boolean {
        liveMembers(server, invasionId).forEach(Entity::discard)
        return true
    }

    fun liveMembers(server: MinecraftServer, invasionId: String): List<Mob> = server.allLevels.flatMap { level ->
        level.allEntities.filterIsInstance<Mob>()
            .filter { it.persistentData.getString(INVASION_TAG) == invasionId }.toList()
    }

    fun liveCampaignPopulation(server: MinecraftServer): Int = server.allLevels.sumOf { level ->
        level.allEntities.filterIsInstance<Mob>().count { it.persistentData.contains(INVASION_TAG) }
    }

    fun spawnsLastSecond(now: Long): Int = synchronized(spawnTicks) {
        while (spawnTicks.firstOrNull()?.let { now - it >= 20L } == true) spawnTicks.removeFirst()
        spawnTicks.size
    }

    fun maintainTarget(mob: Mob) {
        val tag = mob.persistentData
        if (!tag.hasUUID(TARGET_TAG)) return
        val level = mob.level() as? ServerLevel ?: return
        val target = level.server.playerList.getPlayer(tag.getUUID(TARGET_TAG))
        val availability = when {
            target == null -> CampaignTargetAvailability.ABSENT
            !target.isAlive -> CampaignTargetAvailability.DEAD
            target.serverLevel() !== level -> CampaignTargetAvailability.DIFFERENT_LEVEL
            else -> CampaignTargetAvailability.LIVE_SAME_LEVEL
        }
        if (!CampaignTargetPolicy.mayPursue(availability)) {
            mob.target = null
            mob.navigation.stop()
            return
        }
        val attacker = if (tag.getString(KIND_TAG) == "scout" && tag.hasUUID(LAST_ATTACKER_TAG) &&
            tag.contains(LAST_HIT_TICK_TAG) && CampaignTargetPolicy.recentHit(level.gameTime, tag.getLong(LAST_HIT_TICK_TAG))) {
            level.getEntity(tag.getUUID(LAST_ATTACKER_TAG)) as? LivingEntity
        } else null
        val activeAttacker = attacker?.takeIf { it.isAlive && it.level() === level && !mob.isAlliedTo(it) }
        if (activeAttacker == null) {
            tag.remove(LAST_ATTACKER_TAG)
            tag.remove(LAST_HIT_TICK_TAG)
        }
        val chosen = activeAttacker ?: target
        if (mob.target !== chosen) mob.target = chosen
    }

    fun recordScoutHit(mob: Mob, attacker: Entity?) {
        if (mob.persistentData.getString(KIND_TAG) != "scout") return
        val living = attacker as? LivingEntity ?: return
        if (!living.isAlive || living.level() !== mob.level() || mob.isAlliedTo(living)) return
        mob.persistentData.putUUID(LAST_ATTACKER_TAG, living.uuid)
        mob.persistentData.putLong(LAST_HIT_TICK_TAG, mob.level().gameTime)
        mob.target = living
    }

    fun installCombatGoal(mob: Mob) {
        if (mob.goalSelector.availableGoals.none { it.goal is CampaignHuntGoal }) {
            // Dormant while native combat makes progress; temporary MOVE ownership on a stall.
            mob.goalSelector.addGoal(0, CampaignHuntGoal(mob))
        }
        if (mob !is Pillager || !ModList.get().isLoaded("tconstruct") ||
            mob.mainHandItem.item != ForgeRegistries.ITEMS.getValue(ResourceLocation("tconstruct:crossbow"))) return
        if (mob.goalSelector.availableGoals.none { it.goal is CampaignTconCrossbowGoal }) {
            // TConstruct's launcher extends ProjectileWeaponItem, so vanilla's
            // CrossbowAttackGoal also claims MOVE/LOOK at priority 2. Give our
            // compatible firing goal precedence or vanilla consumes the slot.
            mob.goalSelector.addGoal(1, CampaignTconCrossbowGoal(mob))
        }
    }

    fun invasionId(entity: Entity?): String? =
        (entity as? Mob)?.persistentData?.getString(INVASION_TAG)?.takeIf(String::isNotBlank)
    fun memberId(entity: Entity?): String? =
        (entity as? Mob)?.persistentData?.getString(MEMBER_TAG)?.takeIf(String::isNotBlank)

    internal fun freshTargetEligible(player: ServerPlayer): Boolean =
        player.isAlive && player.gameMode.gameModeForPlayer == GameType.SURVIVAL &&
            player.serverLevel().dimension() == Level.OVERWORLD &&
            com.bettercontent.betterpillagercampaigns.PillagerCampaignsEvents.regionFamiliar(player)

    private fun player(server: MinecraftServer, playerId: String): ServerPlayer? =
        runCatching { java.util.UUID.fromString(playerId) }.getOrNull()?.let(server.playerList::getPlayer)
}
