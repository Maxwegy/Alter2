package dev.openrune.cache.tools.staging

import dev.openrune.cache.filestore.Cache
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * `./gradlew :plugins:tools:interfaceDump -PcacheArgs="<cache dir> <interface id> [<interface id>...]"`
 *
 * Read-only. Lists every component of the given interfaces with its gameval name (index 24, revision 241+),
 * if3 type, parent, position, size, click mask, ops and text, so a server binding (`onButton(593, 39)`) can be
 * checked against the cache instead of memory. Writes `data/reports/interfaces/<id>.md` and prints the same.
 * Exit codes: 0 written, 1 error.
 */
fun main(args: Array<String>) {
    val dataDir = Paths.get(System.getProperty("alter.dataDir") ?: "../data").normalize()
    val exitCode = try {
        require(args.size >= 2) { "Usage: interfaceDump <cache dir> <interface id> [<interface id>...]" }
        val cache = Cache.load(Paths.get(args[0]), false)
        try {
            val out = dataDir.resolve("reports/interfaces")
            Files.createDirectories(out)
            for (id in args.drop(1).map(String::toInt)) {
                val text = InterfaceDump.markdown(id, InterfaceDump.read(cache, id), Gameval.componentNames(cache, id))
                val file: Path = out.resolve("$id.md")
                Files.writeString(file, text)
                println(text)
                println("Written: $file")
            }
        } finally {
            cache.close()
        }
        0
    } catch (e: Exception) {
        e.printStackTrace()
        1
    }
    exitProcess(exitCode)
}

object InterfaceDump {
    const val INDEX = 3

    /** component id → decoded head, for every component file of [interfaceId]. */
    fun read(cache: Cache, interfaceId: Int): Map<Int, If3Component> =
        cache.files(INDEX, interfaceId).sorted().associateWith { file ->
            If3Component.decode(interfaceId, file, cache.data(INDEX, interfaceId, file) ?: ByteArray(0))
        }

    fun markdown(interfaceId: Int, components: Map<Int, If3Component>, names: Map<Int, String>): String = buildString {
        appendLine("# Interface $interfaceId")
        appendLine()
        appendLine("| id | gameval name | type | parent | x,y | w×h | hidden | click mask | ops | text / graphic |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|")
        for ((id, c) in components) {
            val mask = c.clickMask?.let { "0x" + Integer.toHexString(it) } ?: (c.error ?: "?")
            val ops = c.ops?.mapIndexedNotNull { i, op -> op?.let { "${i + 1}:$it" } }?.joinToString(", ") ?: ""
            val content = c.text?.let { "\"$it\"" } ?: c.graphic?.let { "graphic $it" } ?: ""
            appendLine(
                "| $id | ${names[id] ?: ""} | ${c.typeName} | ${if (c.parent < 0) "" else c.parent} | ${c.x},${c.y} | " +
                    "${c.width}×${c.height} | ${if (c.hidden) "yes" else ""} | $mask | $ops | $content |",
            )
        }
    }
}
