package com.bettercontent.betterpillagercampaigns.system

import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.monster.RangedAttackMob
import net.minecraft.world.entity.monster.SpellcasterIllager
import java.util.EnumSet

/** Briefly borrows MOVE only after native combat has failed to make progress. */
internal class CampaignHuntGoal(private val mob: Mob) : Goal() {
    private val progress = HuntProgress()
    private val navigation = CampaignHuntNavigation(mob)
    private val hasTconstruct = net.minecraftforge.fml.ModList.get().isLoaded("tconstruct")
    private var until = 0L
    private var cooldown = 0L

    init { flags = EnumSet.of(Flag.MOVE) }

    private fun needsHunt(): Boolean {
        val target = mob.target ?: return false
        if (!target.isAlive || target.level() !== mob.level() || InvasionRuntime.invasionId(mob) == null) return false
        if (mob.isUsingItem || (mob is SpellcasterIllager && mob.isCastingSpell)) return false
        val visible = mob.sensing.hasLineOfSight(target)
        if (visible && recentlyAttacked(mob)) return false
        val reachSquared = if (mob is RangedAttackMob) 256.0
            else (mob.bbWidth * 2.0) * (mob.bbWidth * 2.0) + target.bbWidth
        return !visible || mob.distanceToSqr(target) > reachSquared
    }

    override fun canUse(): Boolean {
        if (!needsHunt() || (hasTconstruct && mob.goalSelector.availableGoals.any { it.isRunning && it.goal is CampaignTconCrossbowGoal })) {
            progress.reset()
            return false
        }
        val target = mob.target ?: return false
        val now = mob.level().gameTime
        val stalled = progress.stalled(now, target.uuid.toString(), mob.x, mob.y, mob.z, mob.navigation.isDone)
        return now >= cooldown && stalled
    }

    override fun start() {
        until = mob.level().gameTime + 100L
        mob.navigation.stop()
    }

    override fun canContinueToUse(): Boolean = mob.level().gameTime < until && needsHunt()
    override fun requiresUpdateEveryTick(): Boolean = true
    override fun tick() { mob.target?.let(navigation::tick) }
    override fun stop() {
        // Yield MOVE ownership without throwing away a useful route. Native combat
        // may replace/stop it immediately; an idle native goal must not cause a pause.
        if (!needsHunt()) {
            mob.navigation.stop()
            navigation.reset()
        }
        progress.reset()
        cooldown = mob.level().gameTime + 40L
    }

    companion object {
        private data class Attack(val target: java.util.UUID, val tick: Long)
        private val attacks = java.util.WeakHashMap<Mob, Attack>()

        fun recordAttack(mob: Mob) {
            val target = mob.target ?: return
            if (InvasionRuntime.invasionId(mob) != null) attacks[mob] = Attack(target.uuid, mob.level().gameTime)
        }

        private fun recentlyAttacked(mob: Mob): Boolean {
            val attack = attacks[mob] ?: return false
            return attack.target == mob.target?.uuid && mob.level().gameTime - attack.tick in 0L..80L
        }
    }
}
