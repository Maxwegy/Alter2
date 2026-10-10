dependencies {
    implementation(project(":plugins:filestore"))
    implementation("io.netty:netty-buffer:4.1.107.Final")
    implementation("dev.openrune:js5server:1.0.6")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("cc.ekblad:4koma:1.1.0")
    implementation("me.tongfei:progressbar:0.9.2")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("commons-io:commons-io:2.15.1")
    implementation("com.displee:rs-cache-library:7.3.0")
    implementation("com.akuleshov7:ktoml-core:0.5.1")
    implementation("com.akuleshov7:ktoml-file:0.5.1")
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

dependencies {
    implementation(project(":util"))
    implementation(project(":alter-data"))
}

/*
 * Cache staging (Phase 1 M6). Run from game-server/ so ../data resolves like it does for the server.
 * Pass arguments with -PcacheArgs="...".
 */
fun JavaExec.cacheTool(main: String) {
    group = "alter cache"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(main)
    workingDir = rootProject.file("game-server")
    maxHeapSize = "4g"
    args(providers.gradleProperty("cacheArgs").map { it.split(' ').filter(String::isNotBlank) }.getOrElse(emptyList()))
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("cacheStage") {
    description = "Download an OSRS cache from OpenRS2 into data/cache-staging and verify it (--build N | --latest)"
    cacheTool("dev.openrune.cache.tools.staging.CacheStagerMainKt")
}

tasks.register<JavaExec>("cacheVerify") {
    description = "Verify every group CRC of a cache directory and (re)write its cache-manifest.json (--verify <dir>)"
    cacheTool("dev.openrune.cache.tools.staging.CacheStagerMainKt")
}

tasks.register<JavaExec>("cacheDryRun") {
    description = "Decode a staged cache and report decoder failures and RSCM name changes (<dir> <build>)"
    cacheTool("dev.openrune.cache.tools.staging.DecoderDryRunMainKt")
}

tasks.register<JavaExec>("gamevalDump") {
    description = "Dump the gameval name tables (index 24, revision 241+) of a cache and compare them with data/cfg/rscm (<dir> [<out dir>])"
    cacheTool("dev.openrune.cache.tools.staging.GamevalDumpMainKt")
}

tasks.register<JavaExec>("interfaceDump") {
    description = "List the components of interfaces in a cache: gameval names, if3 type, click mask, ops and text (<dir> <interface id>...)"
    cacheTool("dev.openrune.cache.tools.staging.InterfaceDumpMainKt")
}

tasks.register<JavaExec>("rscmGenerate") {
    description = "Generate RSCM tables for a staged cache from its gameval names, with aliases for every committed name and a migration report (<dir> <build> [--out] [--commit])"
    cacheTool("dev.openrune.cache.tools.rscm.RscmGenerateMainKt")
}
