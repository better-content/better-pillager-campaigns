package com.bettercontent.betterpillagercampaigns.system

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap

/** One bounded query per attempt; shared by fallback movement and the crossbow goal. */
internal class CampaignHuntNavigation(private val mob: Mob) {
    private val progress = HuntProgress()
    private var nextAttempt = Long.MIN_VALUE
    private var failures = 0
    private var candidate = 0
    private var detouring = false
    private var targetId: java.util.UUID? = null
    private var destination: Vec3? = null

    fun reset() {
        progress.reset()
        targetId = null
        destination = null
        failures = 0
        candidate = 0
        detouring = false
        nextAttempt = Long.MIN_VALUE
    }

    fun tick(target: LivingEntity) {
        val level = mob.level() as? ServerLevel ?: return
        if (!target.isAlive || target.level() !== level) return
        val now = level.gameTime
        if (targetId != target.uuid) {
            reset()
            targetId = target.uuid
            nextAttempt = now + Math.floorMod(mob.id, 10)
        }
        val stuck = progress.stalled(now, target.uuid.toString(), mob.x, mob.y, mob.z, mob.navigation.isDone)
        val movedTarget = destination?.distanceToSqr(target.position())?.let { it >= 16.0 } ?: true
        if (now < nextAttempt || (!mob.navigation.isDone && !stuck && !movedTarget)) return
        nextAttempt = now + 20L + Math.floorMod(mob.id, 7)
        // Never let the native pathfinder construct a chunk cache across an unloaded boundary.
        // Block-position createPath uses a mob-centred searchRange + 8 region. Shrink
        // the search near the loaded edge rather than requiring a full FOLLOW_RANGE halo.
        if (mob.distanceToSqr(target) > 96.0 * 96.0) return
        // Hunting is not target acquisition: short native FOLLOW_RANGE values must
        // not prevent an already-assigned hunter from seeing a loaded detour.
        val searchRange = listOf(32, 24, 16, 8, 4).firstOrNull { candidateRange ->
            SurfaceGridSampler.loadedRectangle(level, mob.blockPosition(), mob.blockPosition(), candidateRange + 8)
        } ?: return
        if (!budget(level, now)) return
        val index = if (stuck || failures > 0) candidate else 0
        // A short loaded search can stop at a wall before seeing its opening. Merely
        // querying points on the far side repeats that failure. Probe along the near
        // side instead; a reachable detour is useful even if it initially moves away.
        val dx = target.x - mob.x
        val dz = target.z - mob.z
        val side = if (index == 2) -1 else 1
        val step = minOf(8.0, searchRange - 1.0) * side
        // Read the actual obstruction normal, not just the target bearing. A target
        // moving along a wall must not turn a lateral probe straight into that wall.
        fun blocked(x: Int, z: Int): Boolean {
            val body = mob.blockPosition().offset(x, 0, z)
            return !level.getBlockState(body).getCollisionShape(level, body).isEmpty ||
                !level.getBlockState(body.above()).getCollisionShape(level, body.above()).isEmpty
        }
        val blockedX = dx != 0.0 && blocked(kotlin.math.sign(dx).toInt(), 0)
        val blockedZ = dz != 0.0 && blocked(0, kotlin.math.sign(dz).toInt())
        val alongZ = if (blockedX != blockedZ) blockedX else kotlin.math.abs(dx) >= kotlin.math.abs(dz)
        val pos = if (index == 0) target.blockPosition() else if (alongZ)
            net.minecraft.core.BlockPos.containing(mob.x, mob.y, mob.z + kotlin.math.sign(dx) * step)
        else net.minecraft.core.BlockPos.containing(mob.x - kotlin.math.sign(dz) * step, mob.y, mob.z)
        if (!level.worldBorder.isWithinBounds(pos) ||
            level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) == null) {
            failedAttempt(now, index)
            return
        }
        // createPath otherwise reuses an unfinished path to the same destination.
        if (stuck) mob.navigation.stop()
        val path = mob.navigation.createPath(pos, 0, searchRange)
        // A direct partial path may advance a distant hunter. Lateral destinations must
        // be genuinely reachable, and a successful side is retained rather than oscillating.
        val end = path?.endNode
        val advances = !detouring && index == 0 && end != null &&
            target.distanceToSqr(end.x + 0.5, end.y.toDouble(), end.z + 0.5) + 1.0 < mob.distanceToSqr(target)
        if (path != null && (path.canReach() || advances) && mob.navigation.moveTo(path, 1.0)) {
            destination = target.position()
            failures = 0
            if (index != 0) {
                candidate = index
                detouring = true
            } else if (path.canReach()) detouring = false
        } else failedAttempt(now, index)
    }

    private fun failedAttempt(now: Long, index: Int) {
        // A closer partial endpoint must not undo a successful lateral detour.
        // Retain that side until a complete direct route exists or the side fails.
        if (!(index == 0 && detouring && candidate != 0)) candidate = (index + 1) % 3
        failures = (failures + 1).coerceAtMost(5)
        nextAttempt = now + 20L * failures + Math.floorMod(mob.id, 7)
    }

    companion object {
        private data class Budget(var tick: Long, var queries: Int)
        private val budgets = WeakHashMap<ServerLevel, Budget>()
        private fun budget(level: ServerLevel, now: Long): Boolean {
            val budget = budgets.getOrPut(level) { Budget(now, 0) }
            if (budget.tick != now) { budget.tick = now; budget.queries = 0 }
            if (budget.queries >= 4) return false
            budget.queries++
            return true
        }
    }
}
