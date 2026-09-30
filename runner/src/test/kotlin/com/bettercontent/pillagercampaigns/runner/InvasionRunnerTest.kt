package com.bettercontent.betterpillagercampaigns.runner

import com.bettercontent.betterpillagercampaigns.core.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvasionRunnerTest {
    private val spec = InvasionRuntimeSpec.create(
        InvasionRules(scoutWindowMinTicks = 100, scoutWindowMaxTicks = 100,
            assaultWindowMinTicks = 1_000, assaultWindowMaxTicks = 1_000,
            assaultWarningSurfaceTicks = 20, timeTierTicks = 1_000,
            approachMinimumBlocks = 48, approachMaximumBlocks = 72),
        listOf(
            RecruitSpec("minecraft:pillager", 2, 0, RecruitRole.RANGED, 4),
            RecruitSpec("minecraft:vindicator", 3, 1, RecruitRole.FRONTLINE, 3),
            RecruitSpec("minecraft:witch", 4, 2, RecruitRole.SUPPORT, maximumPerSquad = 1),
        ),
    )

    @Test fun `experiment proves bounded multiplayer lifecycle`() {
        val first = InvasionExperiment.evaluate(spec, 5_000L, 4)
        val second = InvasionExperiment.evaluate(spec, 5_000L, 4)
        assertEquals(first, second)
        assertTrue(first.passed)
        assertTrue(first.warnings >= 4 && first.materializations >= 4 && first.resolutions > 0)
        assertFalse(first.boundary.contains("Minecraft combat simulation"))
    }

    @Test fun `cli writes example and readiness outputs`() {
        InvasionRunner.main(arrayOf("example"))
        val root = File("build/test-runner").also(File::mkdirs)
        val specFile = root.resolve("spec.json")
        specFile.writeText(kotlinx.serialization.json.Json.encodeToString(InvasionRuntimeSpec.serializer(), spec))
        InvasionRunner.main(arrayOf("mvp", specFile.absolutePath, root.resolve("report").absolutePath))
        assertTrue(root.resolve("report/invasion-readiness.json").isFile)
        InvasionRunner.main(emptyArray())
        assertFailsWith<IllegalStateException> { InvasionRunner.main(arrayOf("unknown")) }
    }
}
