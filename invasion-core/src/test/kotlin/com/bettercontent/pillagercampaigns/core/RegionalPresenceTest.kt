package com.bettercontent.betterpillagercampaigns.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class RegionalPresenceTest {
    private val home = BlockPoint("minecraft:overworld", 0, 64, 0)

    @Test fun `thirty observed minutes establish home not past world age or another dimension`() {
        val presence = RegionalPresence()
        assertFalse(presence.familiar(home))
        repeat(1_799) { presence.record(home, 20) }
        assertFalse(presence.familiar(home))
        presence.record(home, 19)
        assertFalse(presence.familiar(home))
        presence.record(home)
        assertTrue(presence.familiar(home))
        assertTrue(presence.familiar(home.copy(x = 128)))
        assertEquals(18_000.0, presence.accumulatedTicks(home.copy(x = 192)), 0.01)
        assertEquals(0.0, presence.accumulatedTicks(home.copy(x = 256)))
        assertFalse(presence.familiar(home.copy(dimension = "minecraft:the_nether")))
        assertTrue(presence.familiar(home.copy(y = -50)))
        val restored = Json.decodeFromString<RegionalPresence>(Json.encodeToString(presence))
        assertEquals(presence, restored)
        assertFailsWith<IllegalArgumentException> { restored.record(home, 36_000) }
        val before = restored.accumulatedTicks(home)
        restored.record(home, 0)
        assertEquals(before, restored.accumulatedTicks(home))
    }

    @Test fun `moving locally accumulates together sparse bounds survive long exploration and saturation`() {
        val presence = RegionalPresence()
        repeat(1_800) { presence.record(home.copy(x = if (it % 2 == 0) -32 else 32), 20) }
        assertTrue(presence.familiar(home))
        repeat(8_000) { presence.record(home, 20) }
        assertEquals(180_000.0, presence.accumulatedTicks(home))
        repeat(2_048) { presence.record(home.copy(x = it * 1_024, dimension = "test:dimension${it % 3}"), 20) }
        assertEquals(RegionalPresence.MAX_CELLS, presence.cells.size)
        assertTrue(presence.familiar(home)) // transient exploration must not evict established home
        val restored = Json.decodeFromString<RegionalPresence>(Json.encodeToString(presence))
        assertTrue(restored.familiar(home))
        val newHome = home.copy(x = 4_000_000)
        repeat(1_800) { restored.record(newHome, 20) }
        assertTrue(restored.familiar(newHome)) // a full ledger must still admit newly settled regions
        assertTrue(restored.familiar(home))
    }

    @Test fun `falloff is smooth continuous and queries have no cell boundary jumps`() {
        assertEquals(1.0, RegionalPresence.influence(128.0))
        assertEquals(0.0, RegionalPresence.influence(256.0))
        val values = (128..256).map { RegionalPresence.influence(it.toDouble()) }
        assertTrue(values.zipWithNext().all { (a, b) -> a >= b && a - b < 0.02 })
        val presence = RegionalPresence()
        repeat(1_800) { presence.record(home, 20) }
        val samples = (120..270).map { presence.accumulatedTicks(home.copy(x = it)) }
        assertTrue(samples.zipWithNext().all { (a, b) -> a >= b && a - b < 500 })
        val negative = RegionalPresence()
        repeat(1_800) { negative.record(home.copy(x = -32, z = -32), 20) }
        assertTrue(negative.familiar(home.copy(x = -33, z = -33)))
    }
}
