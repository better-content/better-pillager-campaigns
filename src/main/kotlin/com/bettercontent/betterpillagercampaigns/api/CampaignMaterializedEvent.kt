package com.bettercontent.betterpillagercampaigns.api

import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.eventbus.api.Event

/** A complete native packet has materialized and passed its path/terrain validation. */
class CampaignMaterializedEvent(val player: ServerPlayer, val invasionId: String, val wave: Int, val members: Int) : Event()
