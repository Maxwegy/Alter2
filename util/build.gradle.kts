description = "Alter Utilities, This module will be eventually implemented inside game-server"

/**
 * Generates [gg.rsmod.util.BuildInfo] from the single revision source in gradle.properties
 * (alter.osrsRevision / alter.rsprotVersion). Nothing else may hardcode the revision.
 */
val generateBuildInfo by tasks.registering {
    val osrsRevision = providers.gradleProperty("alter.osrsRevision")
    val rsprotVersion = providers.gradleProperty("alter.rsprotVersion")
    val outputDir = layout.buildDirectory.dir("generated/buildinfo/kotlin")
    inputs.property("osrsRevision", osrsRevision)
    inputs.property("rsprotVersion", rsprotVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("gg/rsmod/util/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package gg.rsmod.util
            |
            |/** Generated from gradle.properties by util:generateBuildInfo. Do not edit. */
            |object BuildInfo {
            |    const val REVISION: Int = ${osrsRevision.get().toInt()}
            |    const val RSPROT_VERSION: String = "${rsprotVersion.get()}"
            |}
            |""".trimMargin(),
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateBuildInfo)
}
