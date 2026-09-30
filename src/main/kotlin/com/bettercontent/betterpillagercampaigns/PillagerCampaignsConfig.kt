package com.bettercontent.betterpillagercampaigns

import com.bettercontent.betterpillagercampaigns.core.InvasionRules
import net.minecraftforge.common.ForgeConfigSpec

object PillagerCampaignsConfig {
    val SPEC: ForgeConfigSpec
    val enabled: ForgeConfigSpec.BooleanValue
    val disableVanillaPatrolSpawning: ForgeConfigSpec.BooleanValue
    val intervalTicks: ForgeConfigSpec.IntValue
    val surfaceSampleBudget: ForgeConfigSpec.IntValue
    private val scoutMin: ForgeConfigSpec.IntValue
    private val scoutMax: ForgeConfigSpec.IntValue
    private val scoutActive: ForgeConfigSpec.IntValue
    private val assaultMin: ForgeConfigSpec.IntValue
    private val assaultMax: ForgeConfigSpec.IntValue
    private val assaultWarning: ForgeConfigSpec.IntValue
    private val waveTimeout: ForgeConfigSpec.IntValue
    private val groupRadius: ForgeConfigSpec.IntValue
    private val globalCap: ForgeConfigSpec.IntValue
    private val perTickCap: ForgeConfigSpec.IntValue
    private val perSecondCap: ForgeConfigSpec.IntValue
    private val timeTier: ForgeConfigSpec.IntValue
    private val approachMin: ForgeConfigSpec.IntValue
    private val approachMax: ForgeConfigSpec.IntValue
    private val searchExpansions: ForgeConfigSpec.IntValue
    private val localRetry: ForgeConfigSpec.IntValue
    private val localTimeout: ForgeConfigSpec.IntValue
    private val activeIdle: ForgeConfigSpec.IntValue
    private val targetUnavailable: ForgeConfigSpec.IntValue
    private val deathGrace: ForgeConfigSpec.IntValue

    init {
        val b = ForgeConfigSpec.Builder()
        b.push("scheduler")
        enabled = b.define("enabled", true)
        disableVanillaPatrolSpawning = b.define("disable_vanilla_patrol_spawning", true)
        intervalTicks = b.defineInRange("interval_ticks", 20, 1, 1_200)
        surfaceSampleBudget = b.comment("Retained compatibility key from 0.4; strategic routing now uses the persistent loaded-terrain atlas.")
            .defineInRange("surface_sample_budget", 16_384, 64, 65_536)
        b.pop()

        b.push("scouts")
        scoutMin = b.defineInRange("minimum_eligible_ticks", 7_200, 0, 1_200_000)
        scoutMax = b.defineInRange("maximum_eligible_ticks", 14_400, 0, 1_200_000)
        scoutActive = b.defineInRange("withdraw_after_active_ticks", 2_400, 200, 144_000)
        b.pop()

        b.push("assaults")
        assaultMin = b.defineInRange("minimum_eligible_ticks", 72_000, 0, 2_400_000)
        assaultMax = b.defineInRange("maximum_eligible_ticks", 144_000, 0, 2_400_000)
        assaultWarning = b.defineInRange("warning_surface_ticks", 2_400, 0, 24_000)
        waveTimeout = b.defineInRange("wave_progress_timeout_ticks", 1_500, 100, 24_000)
        deathGrace = b.defineInRange("death_grace_ticks", 24_000, 0, 1_200_000)
        b.pop()

        b.push("grouping")
        groupRadius = b.defineInRange("nearby_player_radius", 64, 8, 256)
        b.pop()

        b.push("limits")
        globalCap = b.defineInRange("global_campaign_mobs", 96, 1, 160)
        perTickCap = b.defineInRange("spawns_per_tick", 8, 1, 32)
        perSecondCap = b.defineInRange("spawns_per_second", 24, 1, 160)
        b.pop()

        b.push("scaling")
        timeTier = b.defineInRange("eligible_ticks_per_intensity", 144_000, 1, 12_000_000)
        b.pop()

        b.push("approach")
        approachMin = b.defineInRange("minimum_blocks", 48, 8, 256)
        approachMax = b.defineInRange("maximum_blocks", 72, 8, 384)
        searchExpansions = b.defineInRange("maximum_search_expansions", 4_096, 64, 65_536)
        localRetry = b.defineInRange("local_retry_ticks", 100, 20, 1_200)
        localTimeout = b.defineInRange("local_timeout_ticks", 2_400, 200, 24_000)
        b.pop()

        b.push("cleanup")
        activeIdle = b.defineInRange("active_idle_ticks", 6_000, 200, 144_000)
        targetUnavailable = b.defineInRange("target_unavailable_ticks", 1_200, 20, 24_000)
        b.pop()
        SPEC = b.build()
    }

    fun rules(): InvasionRules = InvasionRules(
        scoutWindowMinTicks = minOf(scoutMin.get(), scoutMax.get()).toLong(),
        scoutWindowMaxTicks = maxOf(scoutMin.get(), scoutMax.get()).toLong(),
        assaultWindowMinTicks = minOf(assaultMin.get(), assaultMax.get()).toLong(),
        assaultWindowMaxTicks = maxOf(assaultMin.get(), assaultMax.get()).toLong(),
        assaultWarningSurfaceTicks = assaultWarning.get().toLong(),
        deathGraceTicks = deathGrace.get().toLong(),
        timeTierTicks = timeTier.get().toLong(),
        groupRadiusBlocks = groupRadius.get(),
        waveProgressTimeoutTicks = waveTimeout.get().toLong(),
        scoutActiveTicks = scoutActive.get().toLong(),
        approachMinimumBlocks = minOf(approachMin.get(), approachMax.get()),
        approachMaximumBlocks = maxOf(approachMin.get(), approachMax.get()),
        maximumSearchExpansions = searchExpansions.get(),
        localApproachRetryTicks = localRetry.get().toLong(),
        localApproachTimeoutTicks = localTimeout.get().toLong(),
        activeIdleTicks = activeIdle.get().toLong(),
        targetUnavailableTicks = targetUnavailable.get().toLong(),
        globalCampaignMobCap = globalCap.get(),
        maximumSpawnsPerTick = perTickCap.get(),
        maximumSpawnsPerSecond = maxOf(perTickCap.get(), perSecondCap.get()),
    )
}
