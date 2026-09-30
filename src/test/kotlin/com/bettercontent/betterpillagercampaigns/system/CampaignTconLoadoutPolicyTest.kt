package com.bettercontent.betterpillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CampaignTconLoadoutPolicyTest {
    @Test fun `pillager crossbow and vindicator cleaver produce different combat styles`() {
        val pillager = CampaignTconLoadoutPolicy.forEntity("minecraft:pillager")
        val vindicator = CampaignTconLoadoutPolicy.forEntity("minecraft:vindicator")

        assertEquals("tconstruct:crossbow", pillager?.weaponId)
        assertEquals(CampaignWeaponStyle.CROSSBOW, pillager?.style)
        assertEquals("tconstruct:cleaver", vindicator?.weaponId)
        assertEquals(CampaignWeaponStyle.CLEAVER, vindicator?.style)
        assertNotEquals(pillager?.style, vindicator?.style)
    }

    @Test fun `only configured campaign archetypes receive bounded-drop loadouts`() {
        for (entityId in listOf("minecraft:pillager", "minecraft:vindicator")) {
            assertEquals(0.0f, CampaignTconLoadoutPolicy.forEntity(entityId)?.equipmentDropChance)
            assertEquals(null, CampaignTconLoadoutPolicy.forCampaignMember(entityId, ""))
            assertEquals(
                CampaignTconLoadoutPolicy.forEntity(entityId),
                CampaignTconLoadoutPolicy.forCampaignMember(entityId, "campaign-1"),
            )
        }
        assertNull(CampaignTconLoadoutPolicy.forCampaignMember("minecraft:witch", "campaign-1"))
        assertNull(CampaignTconLoadoutPolicy.forCampaignMember("takesapillage:archer", "campaign-1"))
    }
}
