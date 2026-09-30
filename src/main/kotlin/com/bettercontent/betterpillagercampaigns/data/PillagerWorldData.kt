package com.bettercontent.betterpillagercampaigns.data

import com.bettercontent.betterpillagercampaigns.PillagerCampaignsMod
import com.bettercontent.betterpillagercampaigns.core.DirectorFrame
import com.bettercontent.betterpillagercampaigns.core.DirectorSnapshot
import com.bettercontent.betterpillagercampaigns.core.DirectorTransition
import com.bettercontent.betterpillagercampaigns.core.InvasionDirector
import com.bettercontent.betterpillagercampaigns.core.InvasionRuntimeSpec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData

class PillagerWorldData private constructor(private var restored: DirectorSnapshot) : SavedData() {
    private var engine: InvasionDirector? = null
    private var runtimeRevision: String = "unresolved"

    fun transition(frame: DirectorFrame, spec: InvasionRuntimeSpec): DirectorTransition {
        val director = engine ?: InvasionDirector.restore(restored, spec).also {
            engine = it
            runtimeRevision = spec.revision
        }
        val result = director.transition(frame)
        restored = director.snapshot()
        setDirty()
        return result
    }

    fun attach(spec: InvasionRuntimeSpec) {
        if (engine == null) {
            engine = InvasionDirector.restore(restored, spec)
            runtimeRevision = spec.revision
        }
    }

    fun snapshot(): DirectorSnapshot = engine?.snapshot() ?: copy(restored)
    fun runtimeRevision(): String = runtimeRevision

    internal fun restoreSnapshot(snapshot: DirectorSnapshot, spec: InvasionRuntimeSpec) {
        restored = copy(snapshot)
        engine = InvasionDirector.restore(restored, spec)
        runtimeRevision = spec.revision
        setDirty()
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val snapshot = snapshot()
        tag.putInt("schema", DirectorSnapshot.CURRENT_SCHEMA_VERSION)
        tag.putString("runtimeRevision", runtimeRevision)
        tag.putString("snapshot", JSON.encodeToString(snapshot))
        return tag
    }

    companion object {
        private const val KEY = "better_pillager_campaigns_world"
        private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = false }

        fun get(server: MinecraftServer): PillagerWorldData {
            val seed = server.overworld().seed
            return server.overworld().dataStorage.computeIfAbsent(
                { tag -> load(tag, seed) },
                { PillagerWorldData(DirectorSnapshot(worldSeed = seed)) },
                KEY,
            )
        }

        internal fun load(tag: CompoundTag, worldSeed: Long): PillagerWorldData {
            val schema = tag.getInt("schema")
            if (schema !in 2..DirectorSnapshot.CURRENT_SCHEMA_VERSION || !tag.contains("snapshot")) {
                if (!tag.isEmpty) PillagerCampaignsMod.LOGGER.warn("Discarding unsupported Better Pillager Campaigns state schema {}", schema)
                return PillagerWorldData(DirectorSnapshot(worldSeed = worldSeed))
            }
            var snapshot = runCatching { JSON.decodeFromString<DirectorSnapshot>(tag.getString("snapshot")) }
                .getOrElse { error ->
                    PillagerCampaignsMod.LOGGER.error("Invalid Better Pillager Campaigns invasion state; starting fresh", error)
                    DirectorSnapshot(worldSeed = worldSeed)
                }
            if (schema == 2) {
                snapshot = snapshot.copy(schemaVersion = DirectorSnapshot.CURRENT_SCHEMA_VERSION,
                    pendingEffects = linkedMapOf()).also { migrated ->
                    migrated.tracks.values.forEach { track ->
                        track.invasion = null
                        track.joinedInvasionId = null
                    }
                }
                PillagerCampaignsMod.LOGGER.warn(
                    "Migrated Better Pillager Campaigns schema 2 clocks to schema 4; active near-player campaigns were cleared")
            }
            if (schema == 3) {
                snapshot = snapshot.copy(schemaVersion = DirectorSnapshot.CURRENT_SCHEMA_VERSION,
                    pendingEffects = linkedMapOf()).also { migrated ->
                    migrated.tracks.values.mapNotNull { it.invasion }.forEach { invasion ->
                        invasion.anchor = null
                        invasion.validatedAnchor = null
                        invasion.strategicRoute.clear()
                        invasion.strategicRouteIndex = 0
                        invasion.strategicOrigin = null
                        invasion.strategicPosition = null
                        invasion.strategicTravelMilliBlocks = 0L
                        invasion.strategicJourneyTotalMilliBlocks = 0L
                        invasion.strategicFrontier = com.bettercontent.betterpillagercampaigns.core.StrategicFrontier.UNKNOWN
                        invasion.phase = com.bettercontent.betterpillagercampaigns.core.InvasionPhase.APPROACHING
                        invasion.lastRouteFailure = "migrated_schema_3"
                    }
                }
                PillagerCampaignsMod.LOGGER.warn(
                    "Migrated Better Pillager Campaigns schema 3 active encounters to virtual strategic travel")
            }
            return PillagerWorldData(snapshot).also { it.runtimeRevision = tag.getString("runtimeRevision").ifBlank { "unresolved" } }
        }

        private fun copy(snapshot: DirectorSnapshot): DirectorSnapshot = JSON.decodeFromString(JSON.encodeToString(snapshot))
    }
}
