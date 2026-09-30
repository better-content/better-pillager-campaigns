package com.bettercontent.betterpillagercampaigns.system

import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraftforge.registries.ForgeRegistries
import slimeknights.tconstruct.library.tools.helper.ToolBuildHandler
import slimeknights.tconstruct.library.tools.item.IModifiable

internal enum class CampaignWeaponStyle {
    CROSSBOW,
    CLEAVER,
}

internal data class CampaignTconLoadout(
    val entityId: String,
    val weaponId: String,
    val style: CampaignWeaponStyle,
    val equipmentDropChance: Float = 0.0f,
)

internal object CampaignTconLoadoutPolicy {
    private val loadouts = listOf(
        CampaignTconLoadout("minecraft:pillager", "tconstruct:crossbow", CampaignWeaponStyle.CROSSBOW),
        CampaignTconLoadout("minecraft:vindicator", "tconstruct:cleaver", CampaignWeaponStyle.CLEAVER),
    ).associateBy(CampaignTconLoadout::entityId)

    fun forEntity(entityId: String): CampaignTconLoadout? = loadouts[entityId]

    fun forCampaignMember(entityId: String, invasionId: String): CampaignTconLoadout? =
        if (invasionId.isBlank()) null else forEntity(entityId)
}

/** Builds authentic, material-valid TConstruct weapons only for campaign-owned vanilla recruits. */
internal object CampaignTconLoadouts {
    fun apply(mob: Mob) {
        val entityId = ForgeRegistries.ENTITY_TYPES.getKey(mob.type)?.toString() ?: return
        val loadout = CampaignTconLoadoutPolicy.forCampaignMember(
            entityId,
            mob.persistentData.getString(InvasionRuntime.INVASION_TAG),
        ) ?: return
        val weaponItem = ForgeRegistries.ITEMS.getValue(ResourceLocation(loadout.weaponId)) as? IModifiable
            ?: error("Pinned TConstruct weapon ${loadout.weaponId} is unavailable")
        val seed = mob.uuid.mostSignificantBits xor mob.uuid.leastSignificantBits
        val weapon = ToolBuildHandler.buildItemRandomMaterials(weaponItem, RandomSource.create(seed))
        check(!weapon.isEmpty) { "TConstruct failed to build ${loadout.weaponId} from valid materials" }

        mob.setItemSlot(EquipmentSlot.MAINHAND, weapon)
        mob.setCanPickUpLoot(false)
        EquipmentSlot.values().forEach { slot -> mob.setDropChance(slot, loadout.equipmentDropChance) }
    }
}
