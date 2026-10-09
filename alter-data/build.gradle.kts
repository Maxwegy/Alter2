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
