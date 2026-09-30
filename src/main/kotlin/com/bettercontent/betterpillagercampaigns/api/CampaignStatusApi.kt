package com.bettercontent.betterpillagercampaigns.api

import com.bettercontent.betterpillagercampaigns.core.InvasionPhase
import com.bettercontent.betterpillagercampaigns.data.PillagerWorldData
import net.minecraft.server.level.ServerPlayer

/** A read-only, terrain-free view of the pressure directed at one player. */
object CampaignStatusApi {
    enum class PressureState { QUIET, GATHERING, APPROACHING, MATERIALIZED, SURVIVED }

    @JvmStatic
    fun state(player: ServerPlayer): PressureState {
        val track = PillagerWorldData.get(player.server).snapshot().tracks[player.uuid.toString()]
            ?: return PressureState.QUIET
        val invasion = track.invasion
        if (invasion == null) {
            return if (track.outcomeAdjustment < 0) PressureState.SURVIVED
            else if (track.eligibleTicks > 0) PressureState.GATHERING
            else PressureState.QUIET
        }
        return when (invasion.phase) {
            InvasionPhase.WARNED -> PressureState.GATHERING
            InvasionPhase.APPROACHING, InvasionPhase.READY_TO_MATERIALIZE -> PressureState.APPROACHING
            InvasionPhase.ACTIVE -> PressureState.MATERIALIZED
            InvasionPhase.RETIRING -> PressureState.SURVIVED
        }
    }
}
