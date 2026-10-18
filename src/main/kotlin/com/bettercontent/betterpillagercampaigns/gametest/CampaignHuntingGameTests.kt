package com.bettercontent.betterpillagercampaigns.gametest

import com.bettercontent.betterpillagercampaigns.PillagerCampaignsMod
import com.bettercontent.betterpillagercampaigns.system.*
import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.monster.RangedAttackMob
import net.minecraft.world.entity.projectile.AbstractArrow
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries
import java.util.EnumSet
import java.util.UUID
import java.util.function.Consumer
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.event.entity.living.LivingHurtEvent
import net.minecraftforge.event.entity.living.LivingDamageEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.eventbus.api.Event
import net.minecraftforge.eventbus.api.EventPriority

@GameTestHolder(PillagerCampaignsMod.MOD_ID)
@PrefixGameTestTemplate(false)
object CampaignHuntingGameTests {
    private data class Case(val id: String, val crossbow: Boolean = false, val sealed: Boolean = false,
        val moving: Boolean = false, val stationary: Boolean = false)

    private data class Scenario(val frame: (Int) -> Boolean, val close: () -> Unit)

    @JvmStatic
    @GameTest(templateNamespace = PillagerCampaignsMod.MOD_ID, template = "wide_arena", batch = "hunting", timeoutTicks = 6500)
    fun rosterHuntingAndCombatOwnership(helper: GameTestHelper) {
        val cases = listOf(
            Case("minecraft:pillager"), Case("takesapillage:archer"), Case("takesapillage:skirmisher"),
            Case("takesapillage:legioner"), Case("savage_and_ravage:griefer"),
            Case("savage_and_ravage:trickster"), Case("savage_and_ravage:executioner"),
            Case("savage_and_ravage:iceologer"), Case("minecraft:ravager"),
            Case("minecraft:pillager", crossbow = true), Case("takesapillage:archer", stationary = true),
            Case("takesapillage:skirmisher", sealed = true), Case("takesapillage:skirmisher", moving = true),
        )
        // One arena and one player/mob pair at a time. This avoids thirteen simultaneous
        // structure preparations and prevents neighboring combat/tickets from helping a case.
        var index = 0
        var elapsed = 0
        var current = scenario(helper, cases[index])
        helper.onEachTick {
            try {
                if (index < cases.size && current.frame(++elapsed)) {
                    index++
                    if (index == cases.size) helper.succeed()
                    else { elapsed = 0; current = scenario(helper, cases[index]) }
                }
            } catch (failure: Throwable) {
                current.close()
                throw failure
            }
        }
    }

    private fun scenario(helper: GameTestHelper, case: Case): Scenario {
        val (id, crossbow, sealed, moving, stationary) = case
        val base = helper.absolutePos(BlockPos(0, 2, 0))
        for (x in 0..33) for (z in 0..33) {
            helper.level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.STONE.defaultBlockState())
            val wall = (!stationary && x == 16 && (sealed || z !in 24..30)) ||
                (sealed && (x == 33 || ((z == 0 || z == 33) && x in 16..33)))
            for (y in 0..4) helper.level.setBlockAndUpdate(base.offset(x, y, z),
                if (wall) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState())
        }
        requireCampaignFixtureWorld(helper, base)
        // A real online player supplies entity-ticking tickets, not merely loaded test chunks.
        val name = "hunt-${id.substringAfter(':')}-${if (crossbow) "bow" else if (sealed) "seal" else if (moving) "move" else if (stationary) "fire" else "idle"}"
        val target = ServerPlayer(helper.level.server, helper.level,
            GameProfile(UUID.nameUUIDFromBytes(name.toByteArray()), name))
        val connection = Connection(PacketFlow.SERVERBOUND)
        val channel = EmbeddedChannel(connection)
        helper.level.server.playerList.placeNewPlayer(connection, target)
        target.setGameMode(GameType.SURVIVAL)
        target.abilities.invulnerable = true
        target.moveTo(base.x + 26.5, base.y.toDouble(), base.z + 16.5)
        val mob = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation(id))!!.create(helper.level) as Mob
        mob.moveTo(base.x + (if (stationary) 14.5 else 6.5), base.y.toDouble(), base.z + 16.5)
        mob.finalizeSpawn(helper.level, helper.level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.EVENT, null, null)
        helper.assertTrue(helper.level.addFreshEntity(mob), "$id must enter the level")
        mob.setOnGround(true)
        mob.setPersistenceRequired()
        mob.persistentData.putString(InvasionRuntime.INVASION_TAG, "hunt-$id")
        mob.persistentData.putUUID(InvasionRuntime.TARGET_TAG, target.uuid)
        mob.persistentData.putString(InvasionRuntime.KIND_TAG, "assault")
        // Fix target acquisition; real navigation and collisions remain native in every case.
        mob.targetSelector.removeAllGoals { true }
        mob.target = target
        if (crossbow) CampaignTconLoadouts.apply(mob)
        else if (!stationary) {
            // Isolate MOVE starvation from legitimate stationary spells/attacks.
            mob.goalSelector.removeAllGoals { true }
            mob.goalSelector.addGoal(1, object : Goal() {
                init { flags = EnumSet.of(Flag.MOVE) }
                override fun canUse() = true
            })
        }
        InvasionRuntime.installCombatGoal(mob)
        InvasionRuntime.installCombatGoal(mob)
        helper.assertTrue(mob.goalSelector.availableGoals.count { it.goal is CampaignHuntGoal } == 1,
            "Hunting installation must be idempotent")
        // Own the complete maximum native collision cache before starting the case clock.
        // This is synchronous fixture preparation, never a production force-load policy.
        for (chunkX in ((base.x - 40) shr 4)..((base.x + 33 + 40) shr 4))
            for (chunkZ in ((base.z - 40) shr 4)..((base.z + 33 + 40) shr 4))
                helper.level.getChunk(chunkX, chunkZ)
        helper.assertTrue(SurfaceGridSampler.loadedRectangle(helper.level, base, base.offset(33, 0, 33), 40),
            "Fixture must own its complete FULL 32+8 collision halo before native hunting")
        // No subscription exists while setup assertions can fail before Scenario is returned.
        val deathTrace = CampaignFixtureActorTrace(helper, listOf(mob), 6500L,
            "hunting:$case authoredArena=${base.offset(0, -1, 0)}..${base.offset(34, 5, 34)}")
        var crossed = false
        var firingPosition = false
        var fired = false
        var borrowedMovement = false
        val readinessStarted = helper.level.gameTime
        var readyAt: Long? = null
        return Scenario(frame@ { _ ->
            if (readyAt == null) {
                val ready = helper.level.server.playerList.getPlayer(target.uuid) === target &&
                    listOf(mob, target).all { actor -> actor.isAlive && !actor.isRemoved && actor.tickCount > 0 &&
                        helper.level.getEntity(actor.uuid) === actor && helper.level.isPositionEntityTicking(actor.blockPosition()) }
                helper.assertTrue(ready || helper.level.gameTime - readinessStarted < 20L,
                    "Native case readiness must fit 20 ticks: ${deathTrace.state(mob)}; ${deathTrace.state(target)}")
                if (!ready) return@frame false
                readyAt = helper.level.gameTime
                deathTrace.note("case-native-ready-full-owned-halo")
            }
            val elapsed = (helper.level.gameTime - readyAt!!).toInt()
            helper.assertTrue(SurfaceGridSampler.loadedRectangle(helper.level, mob.blockPosition(), mob.blockPosition(), 40),
                "Native hunting requires its prewarmed FULL 32+8 collision halo: ${deathTrace.state(mob)}")
            crossed = crossed || mob.x > base.x + 17.0
            firingPosition = firingPosition || (mob is RangedAttackMob &&
                mob.distanceToSqr(target) <= 256.0 && mob.sensing.hasLineOfSight(target))
            borrowedMovement = borrowedMovement || mob.goalSelector.availableGoals.any { it.isRunning && it.goal is CampaignHuntGoal }
            fired = fired || helper.level.getEntitiesOfClass(AbstractArrow::class.java, mob.boundingBox.inflate(40.0)).any { it.owner === mob }
            if (sealed) helper.assertTrue(!(mob.x > base.x + 17.0 && mob.x < base.x + 33.0 &&
                mob.z > base.z + 1.0 && mob.z < base.z + 33.0), "Hunters must not enter a sealed defense")
            if (moving && elapsed == 80) {
                helper.assertTrue(mob.x < base.x + 20.0, "The new wall must obstruct an upcoming route, not be placed behind the hunter")
                target.moveTo(base.x + 26.5, base.y.toDouble(), base.z + 6.5)
                for (z in 0..22) for (y in 0..4) {
                    helper.level.setBlockAndUpdate(base.offset(20, y, z), Blocks.STONE.defaultBlockState())
                }
            }
            // The two-wall moving-target workload has an extra recovery/travel leg.
            if (elapsed < if (moving) 650 else 450) return@frame false
            val diagnostic = "$case pos=${mob.blockPosition()} target=${target.blockPosition()} " +
                "path=${mob.navigation.path} ticks=${mob.tickCount} removed=${mob.removalReason} " +
                "goals=${mob.goalSelector.availableGoals.filter { it.isRunning }.map { it.goal.javaClass.simpleName }}"
            helper.assertTrue(mob.isAlive && !mob.isRemoved,
                "Hunting motion/combat oracle requires its original living, nonremoved hunter: $diagnostic; ${deathTrace.state(mob)}")
            helper.assertTrue(!deathTrace.truncated, "Hunting damage/death observations must fit the bounded trace")
            if (moving) helper.assertTrue(mob.distanceToSqr(target) < 16.0,
                "Hunter must reacquire a moving target behind a second obstruction: $diagnostic")
            helper.assertTrue(sealed || stationary || crossed || firingPosition,
                "Hunter must round the wall or reach a visible firing position: $diagnostic")
            if (stationary) helper.assertTrue(!borrowedMovement, "A successfully firing native archer must retain MOVE ownership")
            else helper.assertTrue(crossbow || borrowedMovement, "Hunter must recover from native MOVE starvation: $diagnostic")
            if (crossbow || stationary) helper.assertTrue(fired, "Ranged hunter must fire a real arrow: $diagnostic")
            if (sealed) helper.assertTrue(helper.level.getBlockState(base.offset(16, 0, 16)).`is`(Blocks.STONE), "Defense must remain intact")
            helper.level.getEntitiesOfClass(AbstractArrow::class.java, mob.boundingBox.inflate(40.0))
                .filter { it.owner === mob }.forEach { it.discard() }
            deathTrace.note("case-owned-success-cleanup")
            mob.discard()
            deathTrace.close()
            helper.level.server.playerList.remove(target)
            channel.finishAndReleaseAll()
            org.slf4j.LoggerFactory.getLogger("CampaignHuntingTest").info("Hunting case passed: {}", case)
            true
        }, deathTrace::close)
    }
}

/** Authored source GameTest isolation only; production worlds and interactive runs are untouched. */
internal fun requireCampaignFixtureWorld(helper: GameTestHelper, base: BlockPos) {
    helper.assertTrue(helper.level.chunkSource.generator is net.minecraft.world.level.levelgen.FlatLevelSource &&
        helper.level.seed == 0L && helper.level.minBuildHeight == -64,
        "Source GameTests require the actual authored flat seed0 Overworld, not merely properties")
    for (x in 0..33) for (z in 0..33) for (y in 0..5) {
        val pos = base.offset(x, y, z)
        helper.assertTrue(helper.level.getFluidState(pos).isEmpty,
            "Authored dry fixture body must not contain environmental fluid at $pos")
    }
}

/** Read-only diagnostics for these two campaign fixtures, never a production observer. */
internal class CampaignFixtureActorTrace(
    private val helper: GameTestHelper,
    private val actors: List<Mob>,
    private val timeout: Long,
    private val context: String,
) {
    private val subscriptions = mutableListOf<Any>()
    private var observations = 0
    var truncated = false
        private set
    private val origin = helper.absolutePos(BlockPos.ZERO)
    private val templateBounds = net.minecraft.world.phys.AABB(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble(),
        origin.x + 34.0, origin.y + 8.0, origin.z + 34.0)

    fun state(entity: net.minecraft.world.entity.Entity): String =
        "${ForgeRegistries.ENTITY_TYPES.getKey(entity.type)}[uuid=${entity.uuid},id=${entity.id},pos=${entity.position()},bbox=${entity.boundingBox}," +
            "health=${(entity as? net.minecraft.world.entity.LivingEntity)?.health},maxHealth=${(entity as? net.minecraft.world.entity.LivingEntity)?.maxHealth}," +
            "alive=${entity.isAlive},removed=${entity.removalReason},age=${entity.tickCount}," +
            "level=${entity.level().dimension().location()},chunk=${net.minecraft.world.level.ChunkPos(entity.blockPosition())}," +
            "loaded=${helper.level.chunkSource.getChunkNow(entity.blockX shr 4, entity.blockZ shr 4) != null}," +
            "registered=${helper.level.getEntity(entity.uuid) === entity},ticking=${helper.level.isPositionEntityTicking(entity.blockPosition())}," +
            "campaign=${InvasionRuntime.invasionId(entity)},target=${(entity as? Mob)?.target?.uuid}]"

    private fun accepts(entity: net.minecraft.world.entity.Entity) =
        entity.level() === helper.level && actors.any { it === entity && it.uuid == entity.uuid }

    private fun observe(kind: String, entity: net.minecraft.world.entity.Entity, event: Event?,
        source: net.minecraft.world.damagesource.DamageSource? = null, amount: Float? = null) {
        if (!accepts(entity)) return
        if (observations++ >= 128) {
            if (!truncated) org.slf4j.LoggerFactory.getLogger("CampaignFixtureDeathTrace").warn("Actor trace overflow: {}", context)
            truncated = true
            return
        }
        val stack = StackWalker.getInstance().walk { frames ->
            frames.limit(32).map { "${it.className}#${it.methodName}:${it.lineNumber}" }.toList().joinToString(" <- ")
        }
        org.slf4j.LoggerFactory.getLogger("CampaignFixtureDeathTrace").info(
            "Actor event={} context={} nativeTick={} testTick={} declaredTemplateBounds={} actor={} msgId={} amount={} canceled={} source={} direct={} owner={} stack={}",
            kind, context, helper.level.gameTime, helper.tick, templateBounds, state(entity), source?.msgId, amount,
            event?.let { it.isCancelable && it.isCanceled }, source?.entity?.let(::state), source?.directEntity?.let(::state),
            (source?.directEntity as? net.minecraft.world.entity.projectile.Projectile)?.owner?.let(::state), stack)
    }

    fun note(kind: String) { actors.forEach { observe(kind, it, null) } }

    init {
        val hurt = Consumer<LivingHurtEvent> { observe("hurt", it.entity, it, it.source, it.amount) }
        val damage = Consumer<LivingDamageEvent> { observe("final-damage", it.entity, it, it.source, it.amount) }
        val death = Consumer<LivingDeathEvent> { observe("death", it.entity, it, it.source) }
        val leave = Consumer<EntityLeaveLevelEvent> { observe("leave", it.entity, it) }
        val guard = Consumer<TickEvent.ServerTickEvent> {
            if (it.phase == TickEvent.Phase.END && it.server === helper.level.server && helper.tick >= timeout) {
                try { note("timeout-observer-cleanup") } finally { close() }
            }
        }
        // Track every listener before registration so even a partial addListener failure rolls back.
        subscriptions.addAll(listOf(hurt, damage, death, leave, guard))
        try {
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, LivingHurtEvent::class.java, hurt)
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, LivingDamageEvent::class.java, damage)
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, LivingDeathEvent::class.java, death)
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EntityLeaveLevelEvent::class.java, leave)
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, TickEvent.ServerTickEvent::class.java, guard)
            note("observer-start")
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun close() {
        subscriptions.toList().forEach { MinecraftForge.EVENT_BUS.unregister(it) }
        subscriptions.clear()
    }
}
