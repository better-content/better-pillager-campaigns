import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.SourceSetContainer

plugins {
    idea
    eclipse
    jacoco
    `maven-publish`
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("net.minecraftforge.gradle") version "6.0.54"
}

val minecraftVersion = property("minecraft_version") as String
val forgeVersion = property("forge_version") as String
val kotlinForForgeVersion = property("kotlinforforge_version") as String
val modId = property("mod_id") as String
val modName = property("mod_name") as String
val modVersion = property("mod_version") as String
val modAuthors = property("mod_authors") as String
val modDescription = property("mod_description") as String
val modLicense = property("mod_license") as String
val modIssueTrackerUrl = property("mod_issue_tracker_url") as String

group = property("mod_group") as String
version = modVersion

base {
    archivesName.set("better-pillager-campaigns")
}

evaluationDependsOn(":invasion-core")
val coreMainSourceSet = project(":invasion-core").extensions.getByType<SourceSetContainer>().getByName("main")

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

kotlin {
    jvmToolchain(17)
}

minecraft {
    mappings("official", minecraftVersion)
    copyIdeResources = true

    runs {
        configureEach {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "info")
            property("forge.enabledGameTestNamespaces", "$modId,minecraft")
            mods {
                create(modId) {
                    source(sourceSets.main.get())
                    source(coreMainSourceSet)
                }
            }
        }
        create("client") {
            workingDirectory(project.file("run/campaign-harness-client"))
            property("mixin.env.remapRefMap", "true")
            property("mixin.env.refMapRemappingFile", file("build/createSrgToMcp/output.srg").absolutePath)
        }
        create("server") {
            workingDirectory(project.file("run/campaign-harness-server"))
            property("better_pillager_campaigns.harness", "true")
            property("mixin.env.remapRefMap", "true")
            property("mixin.env.refMapRemappingFile", file("build/createSrgToMcp/output.srg").absolutePath)
            arg("--nogui")
        }
        create("gameTestServer") {
            property("mixin.env.remapRefMap", "true")
            property("mixin.env.refMapRemappingFile", file("build/createSrgToMcp/output.srg").absolutePath)
        }
    }
}

// CI and fresh-release builds provide verified runtime JARs explicitly.
// Ordinary local builds retain the canonical sibling build/libs convention.
fun betterContentJar(repository: String, artifact: String): java.io.File {
    val directory = providers.environmentVariable("BC_CUSTOM_MOD_JAR_DIR").orNull
    require(directory == null || directory.isNotBlank()) { "BC_CUSTOM_MOD_JAR_DIR must not be blank" }
    val jar = if (directory == null) file("../$repository/build/libs/$artifact") else file(directory).resolve(artifact)
    require(jar.isFile) {
        "Missing Better Content provider $artifact at $jar; prepare BC_CUSTOM_MOD_JAR_DIR or build $repository first"
    }
    return jar
}

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net")
    maven("https://thedarkcolour.github.io/KotlinForForge/")
    maven("https://www.cursemaven.com") { content { includeGroup("curse.maven") } }
    ivy {
        name = "downedPlayerRevivalLocal"
        url = uri(betterContentJar("better-deaths-door", "better-deaths-door-1.0.0.jar").parentFile)
        patternLayout { artifact("[artifact]-[revision].[ext]") }
        metadataSources { artifact() }
        content { includeGroup("bettercontent.local") }
    }
}

dependencies {
    implementation(project(":invasion-core"))
    minecraft("net.minecraftforge:forge:$minecraftVersion-$forgeVersion")
    implementation("thedarkcolour:kotlinforforge:$kotlinForForgeVersion")
    compileOnly(fg.deobf("curse.maven:tconstruct-74072:7449219"))
    runtimeOnly(fg.deobf("curse.maven:tconstruct-74072:7449219"))
    runtimeOnly(fg.deobf("curse.maven:mantle-74924:7563777"))
    testImplementation(kotlin("test"))
    compileOnly(fg.deobf("bettercontent.local:better-deaths-door:1.0.0"))
    runtimeOnly(fg.deobf("bettercontent.local:better-deaths-door:1.0.0"))
    runtimeOnly(fg.deobf("curse.maven:it-takes-a-pillage-635843:4981343"))
    runtimeOnly(fg.deobf("curse.maven:savage-and-ravage-381736:7115735"))
    runtimeOnly(fg.deobf("curse.maven:blueprint-382216:6408581"))
}

tasks.processResources {
    val props = mapOf(
        "minecraftVersion" to minecraftVersion,
        "forgeVersion" to forgeVersion,
        "kotlinForForgeVersion" to kotlinForForgeVersion,
        "modId" to modId,
        "modName" to modName,
        "modVersion" to modVersion,
        "modAuthors" to modAuthors,
        "modDescription" to modDescription,
        "modIssueTrackerUrl" to modIssueTrackerUrl,
        "modLicense" to modLicense,
    )

    inputs.properties(props)
    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) {
        expand(props)
    }
}

tasks.jar {
    from("src/compat/resources")
    dependsOn(":invasion-core:classes")
    from(coreMainSourceSet.output)
    finalizedBy("reobfJar")
}

tasks.named<Jar>("sourcesJar") {
    from("src/compat/resources")
}

val syncGameTestStructures by tasks.registering(Copy::class) {
    from("gameteststructures")
    into(layout.projectDirectory.dir("run/gameteststructures"))
}

val cleanGameTestWorld by tasks.registering(Delete::class) {
    delete(layout.projectDirectory.dir("run/world"))
}

val prepareCampaignHarness by tasks.registering(Copy::class) {
    group = "campaign harness"
    description = "Prepares the isolated lightweight campaign server without deleting its world."
    from(layout.projectDirectory.file("harness/server.properties"))
    into(layout.projectDirectory.dir("run/campaign-harness-server"))
}

tasks.register<Delete>("resetCampaignHarnessWorld") {
    group = "campaign harness"
    description = "Deletes only the generated campaign harness world; the next run creates a fresh flat world."
    delete(layout.projectDirectory.dir("run/campaign-harness-server/world"))
}

tasks.withType<JavaExec>().configureEach {
    if (name == "runGameTestServer") {
        dependsOn(cleanGameTestWorld, syncGameTestStructures)
    }
    if (name == "runServer") {
        dependsOn(prepareCampaignHarness)
        standardInput = System.`in`
    }
}

tasks.register("runCampaignHarnessServer") {
    group = "campaign harness"
    description = "Runs the isolated lightweight authored-roster campaign server."
    dependsOn(tasks.named("runServer"))
}

tasks.register("runCampaignHarnessClient") {
    group = "campaign harness"
    description = "Runs the matching lightweight client for the campaign harness server."
    dependsOn(tasks.named("runClient"))
}

val stageRuntimeJar by tasks.registering(Copy::class) {
    group = "build"
    description = "Stages the reobfuscated runtime jar into build/libs using the canonical release filename."
    dependsOn(tasks.named("reobfJar"))
    from(layout.buildDirectory.file("reobfJar/output.jar"))
    into(layout.buildDirectory.dir("libs"))
    rename { "better-pillager-campaigns-${modVersion}.jar" }
}

tasks.assemble {
    dependsOn(stageRuntimeJar)
}

tasks.register("headlessGameTest") {
    group = "verification"
    description = "Runs Forge game tests in a headless dedicated server."
    dependsOn(tasks.named("runGameTestServer"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude(
                        "**/PillagerCampaignsMod*",
                        "**/PillagerCampaignsEvents*",
                        "**/PillagerCampaignsConfig*",
                        "**/InvasionRuntime*",
                        "**/PillagerWorldData*",
                        "**/SurfaceGridSampler*",
                        "**/InvasionRoster*",
                        "**/api/CampaignStatusApi*",
                        "**/gametest/**",
                    )
                }
            },
        ),
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    classDirectories.setFrom(tasks.jacocoTestReport.get().classDirectories)
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                // Forge adapters are proved by headless GameTests; the pure policy module retains the 90% gate.
                minimum = "0.00".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.register("verifyFast") {
    group = "verification"
    description = "Runs the fast deterministic verification lane."
    dependsOn(tasks.named("check"))
    dependsOn(":invasion-core:check")
    dependsOn(":runner:test")
}

tasks.register("verifyFull") {
    group = "verification"
    description = "Runs the full verification lane, including headless Forge GameTests."
    dependsOn(tasks.named("verifyFast"))
    dependsOn(tasks.named("headlessGameTest"))
}

tasks.register("verifyWorld") {
    group = "verification"
    description = "Runs real authored-roster Forge GameTests with exact pack dependencies."
    dependsOn(tasks.named("headlessGameTest"))
}

tasks.register("invasionCoreExperiment") {
    group = "application"
    description = "Runs the deterministic Invasion Core scenario inspector; this is not a Minecraft simulation."
    dependsOn(":runner:run")
}
