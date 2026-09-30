package com.bettercontent.betterpillagercampaigns.system

import com.bettercontent.betterpillagercampaigns.PillagerCampaignsConfig
import com.bettercontent.betterpillagercampaigns.core.InvasionRuntimeSpec
import com.bettercontent.betterpillagercampaigns.core.RecruitRole
import com.bettercontent.betterpillagercampaigns.core.RecruitSpec
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.registries.ForgeRegistries

object InvasionRoster {
    private val authored = listOf(
        RecruitSpec("minecraft:pillager", 2, 0, RecruitRole.RANGED, weight = 4),
        RecruitSpec("takesapillage:archer", 2, 0, RecruitRole.RANGED, weight = 2, maximumPerSquad = 3, optional = true),
        RecruitSpec("takesapillage:skirmisher", 2, 0, RecruitRole.LINE, weight = 2, maximumPerSquad = 3, optional = true),
        RecruitSpec("minecraft:vindicator", 3, 1, RecruitRole.FRONTLINE, weight = 3, maximumPerSquad = 4),
        RecruitSpec("takesapillage:legioner", 3, 1, RecruitRole.FRONTLINE, weight = 2, maximumPerSquad = 3, optional = true),
        RecruitSpec("minecraft:witch", 4, 2, RecruitRole.SUPPORT, maximumPerSquad = 1),
        RecruitSpec("savage_and_ravage:griefer", 4, 2, RecruitRole.LINE, maximumPerSquad = 2, optional = true),
        RecruitSpec("savage_and_ravage:trickster", 4, 2, RecruitRole.SUPPORT, maximumPerSquad = 1, optional = true),
        RecruitSpec("savage_and_ravage:executioner", 5, 3, RecruitRole.ELITE, maximumPerSquad = 1, optional = true),
        RecruitSpec("savage_and_ravage:iceologer", 5, 3, RecruitRole.ELITE, maximumPerSquad = 1, optional = true),
        RecruitSpec("minecraft:evoker", 6, 4, RecruitRole.ELITE, maximumPerSquad = 1),
        RecruitSpec("minecraft:ravager", 8, 5, RecruitRole.ELITE, maximumPerSquad = 1),
    )

    fun available(): List<RecruitSpec> = authored.filter { recruit ->
        ForgeRegistries.ENTITY_TYPES.containsKey(ResourceLocation(recruit.entityId))
    }

    fun runtimeSpec(): InvasionRuntimeSpec = InvasionRuntimeSpec.create(PillagerCampaignsConfig.rules(), available())

    internal fun authored(): List<RecruitSpec> = authored
}
