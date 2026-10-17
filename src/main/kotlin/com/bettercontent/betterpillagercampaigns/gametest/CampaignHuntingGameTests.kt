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

@GameTestHolder(PillagerCampaignsMod.MOD_ID)
@PrefixGameTestTemplate(false)
object CampaignHuntingGameTests {
    private data class Case(val id: String, val crossbow: Boolean = false, val sealed: Boolean = false,
        val moving: Boolean = false, val stationary: Boolean = false)

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
            if (index < cases.size && current(++elapsed)) {
                index++
                if (index == cases.size) helper.succeed()
                else { elapsed = 0; current = scenario(helper, cases[index]) }
            }
        }
    }

    private fun scenario(helper: GameTestHelper, case: Case): (Int) -> Boolean {
        val (id, crossbow, sealed, moving, stationary) = case
        val base = helper.absolutePos(BlockPos(0, 2, 0))
        for (x in 0..33) for (z in 0..33) {
            helper.level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.STONE.defaultBlockState())
            val wall = (!stationary && x == 16 && (sealed || z !in 24..30)) ||
                (sealed && (x == 33 || ((z == 0 || z == 33) && x in 16..33)))
            for (y in 0..4) helper.level.setBlockAndUpdate(base.offset(x, y, z),
                if (wall) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState())
        }
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
        var crossed = false
        var firingPosition = false
        var fired = false
        var borrowedMovement = false
        return frame@ { elapsed ->
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
            mob.discard()
            helper.level.server.playerList.remove(target)
            channel.finishAndReleaseAll()
            org.slf4j.LoggerFactory.getLogger("CampaignHuntingTest").info("Hunting case passed: {}", case)
            true
        }
    }
}
