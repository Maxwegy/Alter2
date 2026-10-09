import com.github.gradle.node.npm.task.NpmTask

plugins {
    application
    alias(libs.plugins.node)
}

description = "Alter2 Dev Cockpit: local control center for the game server (inbox, server control, audit log)."

val lib = rootProject.project.libs

dependencies {
    implementation(projects.alterData)
    implementation(projects.util)
    implementation(lib.ktor.server.core)
    implementation(lib.ktor.server.cio)
    implementation(lib.ktor.server.sse)
    implementation(lib.ktor.server.content.negotiation)
    implementation(lib.ktor.server.status.pages)
    implementation(lib.ktor.serialization.jackson)
    implementation(lib.sqlite.jdbc)
    implementation(lib.kotlinx.coroutines)
    implementation(lib.okhttp)
    implementation(lib.jackson.module.kotlin)
    implementation(kotlin("reflect"))

    testImplementation(lib.ktor.server.test.host)
    testImplementation(lib.okhttp.mockwebserver)
}

application {
    mainClass.set("org.alter.cockpit.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx512m")
}

// Like the game server, the cockpit runs from game-server/ so ../data resolves the same way everywhere.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.file("game-server")
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

/*
 * The web UI (web/, Vue 3 + Vite) is built into build/web/static and packaged as resources, so the
 * distribution is one process with no Node at runtime. Gradle downloads Node itself, so CI needs no extra
 * setup. `-PskipWeb` skips the UI build for quick backend-only runs.
 */
val skipWeb = project.hasProperty("skipWeb")

node {
    download.set(true)
    version.set("22.12.0")
    nodeProjectDir.set(file("web"))
    npmInstallCommand.set("ci")
}

tasks.npmInstall {
    onlyIf { !skipWeb }
}

val webBuild = tasks.register<NpmTask>("webBuild") {
    group = "build"
    description = "Build the cockpit web UI with Vite"
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "build"))
    inputs.dir("web/src")
    inputs.files("web/index.html", "web/package.json", "web/package-lock.json", "web/vite.config.ts", "web/tsconfig.json")
    outputs.dir(layout.buildDirectory.dir("web"))
    onlyIf { !skipWeb }
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("web"))
}

tasks.processResources {
    dependsOn(webBuild)
}
