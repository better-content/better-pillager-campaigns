package com.bettercontent.betterpillagercampaigns.runner

import com.bettercontent.betterpillagercampaigns.core.*
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ReadinessReport(
    val runtimeSpecRevision: String,
    val passed: Boolean,
    val playerCount: Int,
    val warnings: Int,
    val materializations: Int,
    val resolutions: Int,
    val minimumIntensity: Int,
    val maximumIntensity: Int,
    val checks: Map<String, Boolean>,
    val boundary: String = "Deterministic Invasion Core experiment; not a Minecraft combat or terrain simulation.",
)

object InvasionExperiment {
    fun evaluate(spec: InvasionRuntimeSpec, durationTicks: Long = 720_000L, playerCount: Int = 20): ReadinessReport {
        spec.requireValid()
        val engine = InvasionDirector.create(77L, spec)
        val players = (1..playerCount).map { "player-$it" }
        var warnings = 0
        var materializations = 0
        var resolutions = 0
        val intensities = mutableListOf<Int>()
        val materializedAt = mutableMapOf<String, Long>()
        var now = 0L
        val stepTicks = 200L
        fun record(transition: DirectorTransition) {
            warnings += transition.events.count { it.type == "assault_warned" }
            materializations += transition.events.count { it.type == "packet_materialized" }
            resolutions += transition.events.count { it.type == "resolved" }
        }
        while (now < durationTicks) {
            val snapshot = engine.snapshot()
            val surfaces = players.mapIndexedNotNull { index, id ->
                snapshot.tracks[id]?.invasion
                    ?.takeIf { it.phase in setOf(InvasionPhase.WARNED, InvasionPhase.APPROACHING, InvasionPhase.READY_TO_MATERIALIZE) && it.observedCells.isEmpty() }
                    ?.let { openGrid(id, index * 256) }
            }
            val transition = engine.transition(DirectorFrame(
                elapsedTicks = stepTicks,
                players = players.mapIndexed { index, id -> PlayerObservation(id, true, true, true, BlockPoint("minecraft:overworld", index * 256, 64, 0)) },
                surfaces = surfaces,
            ))
            record(transition)
            val results = transition.effects.map { EffectResult(it.effectId) }
            if (results.isNotEmpty()) record(engine.transition(DirectorFrame(0L, effectResults = results)))
            engine.snapshot().tracks.values.mapNotNull(PlayerPressureTrack::invasion).filter { it.phase == InvasionPhase.ACTIVE }.forEach { invasion ->
                intensities += invasion.intensity
                val start = materializedAt.getOrPut(invasion.invasionId) { now }
                if (now - start >= 1_200L) {
                    record(engine.transition(DirectorFrame(0L, memberDefeats = invasion.members.map { MemberDefeatObservation(invasion.invasionId, it.memberId) })))
                    materializedAt.remove(invasion.invasionId)
                }
            }
            now += stepTicks
        }
        val snapshot = engine.snapshot()
        val checks = linkedMapOf(
            "every-player-warned" to (snapshot.tracks.keys.containsAll(players) && warnings >= playerCount),
            "every-player-materialized" to (materializations >= playerCount),
            "encounters-resolve-after-materialization" to (resolutions >= playerCount),
            "one-active-per-player" to (snapshot.tracks.values.all { it.invasion == null || it.invasion!!.targetPlayerId == it.playerId }),
            "bounded-intensity" to (intensities.all { it in 0..spec.rules.maximumIntensity }),
            "bounded-squads" to (snapshot.tracks.values.mapNotNull { it.invasion }.all {
                it.waves.all { wave -> wave.members.size in spec.rules.scoutMinimumMembers..spec.rules.assaultGroupCap }
            }),
            "durable-effects-reference-live-encounters" to snapshot.pendingEffects.values.all { effect ->
                snapshot.tracks.values.any { it.invasion?.invasionId == effect.invasionId }
            },
        )
        return ReadinessReport(
            spec.revision, checks.values.all { it }, playerCount, warnings, materializations, resolutions,
            intensities.minOrNull() ?: 0, intensities.maxOrNull() ?: 0, checks,
        )
    }

    private fun openGrid(playerId: String, centerX: Int) = SurfaceGridObservation(
        playerId,
        ((-72..72).map { dx -> SurfaceCell(centerX + dx, 64, 0) } +
            (-72..72).map { z -> SurfaceCell(centerX, 64, z) }).distinctBy { it.x to it.z },
        complete = true,
    )
}

object InvasionRunner {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

    @JvmStatic fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println(json.encodeToString(InvasionExperiment.evaluate(exampleSpec())))
            return
        }
        when (args[0]) {
            "example" -> println(json.encodeToString(exampleSpec()))
            "mvp" -> {
                require(args.size == 3) { "usage: mvp <runtime-spec.json> <output-dir>" }
                val spec = json.decodeFromString<InvasionRuntimeSpec>(File(args[1]).readText())
                val report = InvasionExperiment.evaluate(spec)
                val output = File(args[2]).also(File::mkdirs)
                output.resolve("invasion-readiness.json").writeText(json.encodeToString(report))
                output.resolve("invasion-readiness.md").writeText(buildString {
                    appendLine("# Invasion Core Readiness")
                    appendLine()
                    appendLine("**${if (report.passed) "PASS" else "FAIL"}** — ${report.boundary}")
                    report.checks.forEach { (name, passed) -> appendLine("- [${if (passed) "x" else " "}] `$name`") }
                })
                if (!report.passed) error("invasion core readiness failed")
            }
            else -> error("unknown command ${args[0]}")
        }
    }

    private fun exampleSpec() = InvasionRuntimeSpec.create(
        InvasionRules(),
        listOf(
            RecruitSpec("minecraft:pillager", 2, 0, RecruitRole.RANGED, 4),
            RecruitSpec("minecraft:vindicator", 3, 1, RecruitRole.FRONTLINE, 3),
            RecruitSpec("minecraft:witch", 4, 2, RecruitRole.SUPPORT, maximumPerSquad = 1),
            RecruitSpec("minecraft:evoker", 6, 4, RecruitRole.ELITE, maximumPerSquad = 1),
            RecruitSpec("minecraft:ravager", 8, 5, RecruitRole.ELITE, maximumPerSquad = 1),
        ),
    )
}
