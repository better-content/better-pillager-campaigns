package com.bettercontent.betterpillagercampaigns.system

import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.monster.Pillager
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableCrossbowItem
import slimeknights.tconstruct.library.tools.nbt.ToolStack
import java.util.EnumSet
import kotlin.math.atan2
import kotlin.math.sqrt

/** Campaign-only replacement for vanilla's crossbow goal, whose item check excludes TConstruct tools. */
internal class CampaignTconCrossbowGoal(private val pillager: Pillager) : Goal() {
    private val navigation = CampaignHuntNavigation(pillager)
    private var visibleTicks = 0
    private var shotTicks = 0
    private var targetId: java.util.UUID? = null

    init {
        flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
    }

    override fun canUse(): Boolean = valid()

    override fun canContinueToUse(): Boolean = valid()

    private fun valid(): Boolean =
        InvasionRuntime.invasionId(pillager) != null &&
            pillager.target?.let { it.isAlive && it.level() === pillager.level() } == true &&
            pillager.mainHandItem.item is ModifiableCrossbowItem

    override fun start() {
        navigation.reset()
        visibleTicks = 0
        targetId = null
        shotTicks = 0
        pillager.isAggressive = true
    }

    override fun stop() {
        pillager.navigation.stop()
        navigation.reset()
        visibleTicks = 0
        targetId = null
        pillager.isAggressive = false
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun tick() {
        val target = pillager.target ?: return
        if (targetId != target.uuid) {
            targetId = target.uuid
            visibleTicks = 0
            navigation.reset()
        }
        val visible = pillager.sensing.hasLineOfSight(target)
        visibleTicks = if (visible) (visibleTicks + 1).coerceAtMost(5) else 0
        val distanceSquared = pillager.distanceToSqr(target)
        pillager.lookControl.setLookAt(target, 30f, 30f)

        if (visibleTicks < 5 || distanceSquared > 16.0 * 16.0) {
            navigation.tick(target)
        } else {
            pillager.navigation.stop()
            navigation.reset()
            if (--shotTicks <= 0) {
                aimAt(target.eyeY - pillager.eyeY, target.x - pillager.x, target.z - pillager.z)
                val arrow = ItemStack(Items.ARROW).save(CompoundTag())
                ModifiableCrossbowItem.fireCrossbow(ToolStack.from(pillager.mainHandItem), pillager,
                    false, net.minecraft.world.InteractionHand.MAIN_HAND, arrow)
                shotTicks = 40
            }
        }
    }

    private fun aimAt(dy: Double, dx: Double, dz: Double) {
        val yaw = (atan2(dz, dx) * (180.0 / Math.PI) - 90.0).toFloat()
        val pitch = (-atan2(dy, sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI)).toFloat()
        pillager.yRot = yaw
        pillager.xRot = pitch
        pillager.yHeadRot = yaw
        pillager.yBodyRot = yaw
    }
}
