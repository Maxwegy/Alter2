package dev.openrune.cache.tools.staging

import com.google.gson.GsonBuilder
import gg.rsmod.util.BuildInfo
import net.lingala.zip4j.ZipFile
import org.alter.data.io.AtomicFiles
import org.alter.data.report.Report
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.system.exitProcess

/** Written next to a staged cache. The server's boot guard compares [build] with its revision. */
data class CacheManifest(
    val openrs2Id: Int,
    val build: Int,
    val timestamp: String?,
    val files: Map<String, String>,
    val verification: Verification,
) {
    data class Verification(val groups: Int, val missing: Int, val crcMismatches: Int, val ok: Boolean)
}

/**
 * `./gradlew :plugins:tools:cacheStage [-PcacheArgs="--build 241 | --latest | --verify <dir>"]`
 *
 * Downloads an OSRS cache from OpenRS2 into `data/cache-staging/<build>-<id>/`, verifies every group's CRC
 * and writes `cache-manifest.json`. Never touches `data/cache`: promoting a staged cache is a deliberate,
 * reviewable step. Exit codes: 0 verified, 2 verification failed, 1 error.
 */
fun main(args: Array<String>) {
    val dataDir = Paths.get(System.getProperty("alter.dataDir") ?: "../data").normalize()
    val exitCode = try {
        val verifyIndex = args.indexOf("--verify")
        if (verifyIndex >= 0) {
            verifyOnly(Paths.get(args[verifyIndex + 1]), dataDir)
        } else {
            val build = when {
                "--latest" in args -> null
                "--build" in args -> args[args.indexOf("--build") + 1].toInt()
                else -> BuildInfo.REVISION
            }
            stage(build, dataDir)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        1
    }
    exitProcess(exitCode)
}

private fun stage(build: Int?, dataDir: Path): Int {
    val openrs2 = OpenRs2()
    val cache = openrs2.select(build)
    val target = dataDir.resolve("cache-staging/${cache.build}-${cache.id}")
    println("Staging OpenRS2 cache ${cache.id} (build ${cache.build}, ${cache.timestamp}, ${cache.size?.div(1_048_576)} MB) into $target")
    if (cache.groups != null && cache.validGroups != cache.groups) {
        println("Warning: OpenRS2 reports only ${cache.validGroups}/${cache.groups} valid groups for this cache.")
    }

    Files.createDirectories(target)
    val zip = target.resolve("disk.zip")
    openrs2.download("caches/runescape/${cache.id}/disk.zip", zip)
    // disk.zip holds a cache/ folder; extract its files flat into the target.
    ZipFile(zip.toFile()).use { it.extractAll(target.resolve("extract").toString()) }
    val extracted = target.resolve("extract/cache")
    Files.list(extracted).use { stream -> stream.forEach { Files.move(it, target.resolve(it.fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING) } }
    Files.delete(extracted)
    Files.delete(target.resolve("extract"))
    Files.delete(zip)

    // Map squares are XTEA-encrypted up to build 236; OpenRS2 lists no keys after that.
    if ((cache.keys ?: 0) > 0) {
        openrs2.download("caches/runescape/${cache.id}/keys.json", target.resolve("xteas.json"))
    }
    return writeManifest(target, cache.id, cache.build!!, cache.timestamp, dataDir)
}

private fun verifyOnly(dir: Path, dataDir: Path): Int {
    val existing = dir.resolve("cache-manifest.json")
    val previous = if (Files.exists(existing)) GsonBuilder().create().fromJson(Files.readString(existing), CacheManifest::class.java) else null
    return writeManifest(dir, previous?.openrs2Id ?: -1, previous?.build ?: -1, previous?.timestamp, dataDir)
}

private fun writeManifest(dir: Path, openrs2Id: Int, build: Int, timestamp: String?, dataDir: Path): Int {
    println("Verifying every group's CRC in $dir ...")
    val result = Js5Verifier.verify(dir)
    val files = Files.list(dir).use { stream ->
        stream.filter { it.isRegularFile() && it.fileName.toString() != "cache-manifest.json" }
            .sorted()
            .toList()
    }.associate { it.fileName.toString() to sha256(it) }
    val manifest = CacheManifest(openrs2Id, build, timestamp, files, CacheManifest.Verification(result.groups, result.missing, result.crcMismatches, result.ok))
    AtomicFiles.writeText(dir.resolve("cache-manifest.json"), GsonBuilder().setPrettyPrinting().create().toJson(manifest) + "\n")

    val report = Report(
        tool = "cache-stage",
        summary = mapOf("dir" to dir.toString(), "openrs2Id" to openrs2Id, "build" to build, "groups" to result.groups, "missing" to result.missing, "crcMismatches" to result.crcMismatches),
        sections = result.indices.filter { it.missing.isNotEmpty() || it.crcMismatches.isNotEmpty() }.map { index ->
            Report.Section("Index ${index.index}", index.missing.map { "group $it missing" } + index.crcMismatches.map { "group $it CRC mismatch" }, Report.Severity.ERROR)
        },
    )
    println("Report: ${report.write(dataDir.resolve("reports"))}")
    println("${result.groups} groups: ${result.missing} missing, ${result.crcMismatches} CRC mismatches -> ${if (result.ok) "OK" else "FAILED"}")
    return if (result.ok) 0 else 2
}

private fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
