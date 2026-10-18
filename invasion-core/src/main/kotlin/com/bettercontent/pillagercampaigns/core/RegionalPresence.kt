package com.bettercontent.betterpillagercampaigns.core

import kotlinx.serialization.Serializable
import kotlin.math.hypot

/** Only actual online living Survival observations enter this ledger. Each tick is recorded
 * once, in the occupied cell; nearby visits add together only when querying influence.
 * World age and offline time are deliberately absent.
 */
@Serializable
data class RegionalPresence(val cells: MutableMap<String, PresenceCell> = linkedMapOf(), var sequence: Long = 0) {
    fun record(position: BlockPoint, ticks: Long = 1) {
        require(ticks in 0..20) // never catch-up or fabricated historical elapsed time
        if (ticks == 0L) return
        sequence++
        val key = key(position.dimension, Math.floorDiv(position.x, GRID), Math.floorDiv(position.z, GRID))
        if (key !in cells && cells.size >= MAX_CELLS) {
            // Preserve meaningful/established history over transient exploration. At most one
            // eviction/scan per newly occupied cell, never a scan per influenced regional cell.
            val weakest = cells.entries.minWith(compareBy<Map.Entry<String, PresenceCell>> { it.value.ticks }
                .thenBy { it.value.touched })
            cells.remove(weakest.key)
        }
        // Admit the new occupied cell after eviction, so a full ledger cannot permanently
        // starve a newly settled region of its first few ticks.
        val cell = cells.getOrPut(key) { PresenceCell(x = position.x, z = position.z) }
        cell.ticks = (cell.ticks + ticks).coerceAtMost(REQUIRED_TICKS * 4.0)
        cell.touched = sequence
    }

    fun accumulatedTicks(position: BlockPoint): Double {
        val gx = Math.floorDiv(position.x, GRID)
        val gz = Math.floorDiv(position.z, GRID)
        var total = 0.0
        for (z in gz - 8..gz + 8) for (x in gx - 8..gx + 8) {
            val cell = cells[key(position.dimension, x, z)] ?: continue
            total += cell.ticks * influence(hypot(cell.x.toDouble() - position.x, cell.z.toDouble() - position.z))
        }
        return total
    }

    fun familiar(position: BlockPoint): Boolean = accumulatedTicks(position) >= REQUIRED_TICKS

    companion object {
        const val REQUIRED_TICKS = 36_000L
        const val MAX_CELLS = 1_024
        const val GRID = 32
        private fun key(dimension: String, x: Int, z: Int) = "$dimension|$x|$z"
        fun influence(distance: Double): Double {
            if (distance <= 128) return 1.0
            if (distance >= 256) return 0.0
            val t = (distance - 128) / 128
            return 1 - t * t * (3 - 2 * t)
        }
    }
}

@Serializable
data class PresenceCell(var ticks: Double = 0.0, var touched: Long = 0, val x: Int = 0, val z: Int = 0)
