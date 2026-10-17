package com.bettercontent.betterpillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HuntProgressTest {
    @Test fun idleAndMovingPathsHaveDifferentGracePeriods() {
        val progress = HuntProgress()
        assertFalse(progress.stalled(0, "a", 0.0, 0.0, 0.0, true))
        assertFalse(progress.stalled(39, "a", 0.0, 0.0, 0.0, true))
        assertTrue(progress.stalled(40, "a", 0.0, 0.0, 0.0, true))
        assertFalse(progress.stalled(79, "a", 0.0, 0.0, 0.0, false))
        assertTrue(progress.stalled(80, "a", 0.0, 0.0, 0.0, false))
    }

    @Test fun movementInAnyDirectionCountsAsProgress() {
        val progress = HuntProgress()
        progress.stalled(0, "a", 0.0, 0.0, 0.0, false)
        assertFalse(progress.stalled(79, "a", -1.0, 0.0, 0.0, false))
        assertFalse(progress.stalled(150, "a", -1.0, 0.0, 0.0, false))
        assertTrue(progress.stalled(159, "a", -1.0, 0.0, 0.0, false))
    }

    @Test fun smallJitterDoesNotHideAStall() {
        val progress = HuntProgress()
        progress.stalled(0, "a", 0.0, 0.0, 0.0, false)
        assertTrue(progress.stalled(80, "a", 0.1, 0.1, 0.1, false))
    }

    @Test fun targetChangeResetAndClockRollbackRestartObservation() {
        val progress = HuntProgress()
        progress.stalled(100, "a", 0.0, 0.0, 0.0, true)
        assertFalse(progress.stalled(200, "b", 0.0, 0.0, 0.0, true))
        assertFalse(progress.stalled(0, "b", 0.0, 0.0, 0.0, true))
        progress.reset()
        assertFalse(progress.stalled(100, "b", 0.0, 0.0, 0.0, true))
    }
}
