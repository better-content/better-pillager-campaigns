package com.bettercontent.betterpillagercampaigns.system

import com.bettercontent.betterdeathsdoor.api.InjuryApi
import net.minecraft.world.entity.player.Player
import net.minecraftforge.fml.ModList

internal object InjuryCompat {
    fun semanticHealth(player: Player): Float =
        if (ModList.get().isLoaded("better_deaths_door")) InjuryApi.semanticHealth(player) else player.health
}
