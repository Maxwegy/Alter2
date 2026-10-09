package gg.rsmod.util

import java.nio.file.Files
import java.nio.file.Path

/**
 * Boot-time check that the build revision ([BuildInfo.REVISION]), an optional legacy `revision`
 * key in game.yml, and the staged cache's `cache-manifest.json` all agree.
 *
 * Pure: it reads at most one file and returns a [Result]; the caller decides how to fail.
 */
object RevisionGuard {
    sealed interface Result {
        data class Ok(val warnings: List<String>) : Result
        data class Fatal(val message: String) : Result
    }

    private val BUILD_PATTERN = Regex(""""build"\s*:\s*(\d+)""")

    fun check(buildRevision: Int, gameYmlRevision: Int?, cacheManifest: Path): Result {
        if (gameYmlRevision != null && gameYmlRevision != buildRevision) {
            return Result.Fatal(
                "game.yml has revision: $gameYmlRevision but this build is revision $buildRevision. " +
                    "Remove the `revision` key from game.yml; the revision now comes from gradle.properties.",
            )
        }
        if (!Files.exists(cacheManifest)) {
            return Result.Ok(
                listOf(
                    "No cache manifest at ${cacheManifest.toAbsolutePath().normalize()}; cannot verify the cache " +
                        "revision. Stage the cache with :plugins:tools:cacheStage to create one.",
                ),
            )
        }
        val cacheBuild = BUILD_PATTERN.find(Files.readString(cacheManifest))?.groupValues?.get(1)?.toInt()
            ?: return Result.Fatal("Cache manifest $cacheManifest has no \"build\" field.")
        if (cacheBuild != buildRevision) {
            return Result.Fatal(
                "Cache in ${cacheManifest.parent} is build $cacheBuild but this build is revision $buildRevision. " +
                    "Restage the matching cache or bump alter.osrsRevision in gradle.properties.",
            )
        }
        return Result.Ok(emptyList())
    }
}
