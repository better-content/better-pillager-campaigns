package com.bettercontent.betterpillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvasionRuntimeTargetPolicyTest {
    @Test fun `live player in the mob level remains a valid pursuit target`() {
        assertTrue(CampaignTargetPolicy.mayPursue(CampaignTargetAvailability.LIVE_SAME_LEVEL))
    }

    @Test fun `dead target is suspended`() {
        assertFalse(CampaignTargetPolicy.mayPursue(CampaignTargetAvailability.DEAD))
    }

    @Test fun `absent logged out target is suspended`() {
        assertFalse(CampaignTargetPolicy.mayPursue(CampaignTargetAvailability.ABSENT))
    }

    @Test fun `live target in another dimension is suspended`() {
        assertFalse(CampaignTargetPolicy.mayPursue(CampaignTargetAvailability.DIFFERENT_LEVEL))
    }

    @Test fun `scout retaliation lasts exactly two hundred ticks from the latest hit`() {
        assertTrue(CampaignTargetPolicy.recentHit(1_000, 1_000))
        assertTrue(CampaignTargetPolicy.recentHit(1_199, 1_000))
        assertFalse(CampaignTargetPolicy.recentHit(1_200, 1_000))
        assertFalse(CampaignTargetPolicy.recentHit(999, 1_000))
    }
}
