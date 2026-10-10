plugins {
    `java-test-fixtures`
}

description = "Alter2 pure data layer: wiki sync, snapshot, drop math, missing-content model. No game-engine imports."

val lib = rootProject.project.libs

dependencies {
    implementation(projects.util)
    implementation(rootProject.projects.plugins.filestore)
    implementation(rootProject.projects.plugins.rscm)
    implementation(lib.kotlinx.coroutines)
    implementation(lib.okhttp)
    implementation(lib.jackson.module.kotlin)
    // jackson-module-kotlin would otherwise pull kotlin-reflect 1.5.x, which cannot read Kotlin 2.0 metadata.
    implementation(kotlin("reflect"))

    testImplementation(lib.okhttp.mockwebserver)
    testImplementation(lib.kotlinx.coroutines.test)
    testFixturesImplementation(rootProject.projects.plugins.filestore)
}

/**
 * Regenerates the committed OSRS Wiki snapshot in data/cfg/wiki. Runs from game-server/ so ../data resolves
 * like it does for the server. Pass flags with -PwikiArgs="--offline" or -PwikiArgs="--refresh".
 */
tasks.register<JavaExec>("wikiSync") {
    group = "alter data"
    description = "Fetch the OSRS Wiki data and rewrite data/cfg/wiki"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.data.cli.WikiSyncMainKt")
    workingDir = rootProject.file("game-server")
    maxHeapSize = "3g"
    args(providers.gradleProperty("wikiArgs").map { it.split(' ').filter(String::isNotBlank) }.getOrElse(emptyList()))
    outputs.upToDateWhen { false }
}

/**
 * Regenerates the wiki entries in data/cfg/spawns/npcs from the wiki's {{Map}} templates, keeping every manual
 * entry. Never touches data/cfg/wiki. Runs from game-server/ like wikiSync. Pass flags with
 * -PspawnArgs="--offline" or -PspawnArgs="--refresh"; -PspawnArgs="--apply-edits" instead applies the in-game
 * spawn edits in data/run/spawn-edits.jsonl (offline).
 */
tasks.register<JavaExec>("spawnSync") {
    group = "alter data"
    description = "Generate NPC spawns from the OSRS Wiki {{Map}} templates into data/cfg/spawns/npcs"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.alter.data.cli.SpawnSyncMainKt")
    workingDir = rootProject.file("game-server")
    maxHeapSize = "3g"
    args(providers.gradleProperty("spawnArgs").map { it.split(' ').filter(String::isNotBlank) }.getOrElse(emptyList()))
    outputs.upToDateWhen { false }
}
