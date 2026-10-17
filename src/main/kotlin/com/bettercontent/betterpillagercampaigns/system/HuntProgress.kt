package com.bettercontent.betterpillagercampaigns.system

/** Measures displacement, not decreasing target distance: going around a wall is progress. */
internal class HuntProgress {
    private var target: String? = null
    private var x = 0.0
    private var y = 0.0
    private var z = 0.0
    private var since = 0L

    fun reset() { target = null }

    fun stalled(now: Long, targetId: String, px: Double, py: Double, pz: Double, idle: Boolean): Boolean {
        if (target != targetId || now < since ||
            (px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z) >= 1.0) {
            target = targetId
            x = px; y = py; z = pz; since = now
        }
        return now - since >= if (idle) 40L else 80L
    }
}
