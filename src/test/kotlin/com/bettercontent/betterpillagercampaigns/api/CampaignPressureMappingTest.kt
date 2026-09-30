package com.bettercontent.betterpillagercampaigns.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CampaignPressureMappingTest {
    @Test fun exposesAllStableStates() {
        assertEquals(5, CampaignStatusApi.PressureState.entries.size)
        assertEquals(listOf("QUIET", "GATHERING", "APPROACHING", "MATERIALIZED", "SURVIVED"),
            CampaignStatusApi.PressureState.entries.map { it.name })
    }
}
