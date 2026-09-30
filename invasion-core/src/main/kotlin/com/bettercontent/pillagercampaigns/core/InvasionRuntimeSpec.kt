package com.bettercontent.betterpillagercampaigns.core

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class InvasionRuntimeSpec(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val revision: String,
    val rules: InvasionRules,
    val recruits: List<RecruitSpec>,
) {
    fun computedRevision(): String {
        val canonical = JSON.encodeToString(copy(revision = ""))
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "invasion-runtime-sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    fun requireValid() {
        require(schemaVersion == CURRENT_SCHEMA_VERSION)
        require(revision == computedRevision())
        require(recruits.isNotEmpty() && recruits.map(RecruitSpec::entityId).distinct().size == recruits.size)
        require(recruits.all { it.entityId.contains(':') && it.cost > 0 && it.unlockIntensity in 0..rules.maximumIntensity && it.weight > 0 && it.maximumPerSquad > 0 })
        require(recruits.any { !it.optional && it.unlockIntensity == 0 && it.role in setOf(RecruitRole.LINE, RecruitRole.RANGED) })
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 3
        private val JSON = Json { encodeDefaults = true }

        fun create(rules: InvasionRules, recruits: List<RecruitSpec>): InvasionRuntimeSpec =
            InvasionRuntimeSpec(revision = "", rules = rules, recruits = recruits.sortedBy(RecruitSpec::entityId))
                .let { it.copy(revision = it.computedRevision()) }
    }
}
