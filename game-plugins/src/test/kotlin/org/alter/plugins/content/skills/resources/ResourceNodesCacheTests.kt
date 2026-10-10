package org.alter.plugins.content.skills.resources

import dev.openrune.cache.CacheManager
import gg.rsmod.util.BuildInfo
import org.junit.Assume
import org.junit.BeforeClass
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Checks the loaded entries of `resource_nodes.json` against the OSRS cache: the option exists on every node
 * object and the depleted object has the same footprint. Stumps lack "Chop down"; empty rocks keep "Mine" in the
 * 241 cache, so for them the check is only that a depleted object is never itself a bound node. Skipped where no
 * cache is installed.
 */
class ResourceNodesCacheTests {
    private val table = ResourceNodeDefs.load(Paths.get("../data/cfg/resources/resource_nodes.json"))

    private val objectIds: Map<String, Int> = Files.readAllLines(Paths.get("../data/cfg/rscm/object.rscm"))
        .map { it.split(':') }.filter { it.size == 2 }.mapNotNull { (name, id) -> id.trim().toIntOrNull()?.let { "object." + name.trim() to it } }.toMap()

    private fun hasOption(id: Int, option: String) = CacheManager.getObject(id).actions.any { it?.equals(option, ignoreCase = true) == true }

    @Test
    fun `every node object has the option and its depleted object matches in size`() {
        val problems = mutableListOf<String>()
        table.loaded.forEach { node ->
            val option = table.skills.getValue(node.skill).option
            node.objects.forEach { pair ->
                val obj = objectIds.getValue(pair.obj)
                val depleted = objectIds.getValue(pair.depleted)
                val a = CacheManager.getObject(obj)
                val b = CacheManager.getObject(depleted)
                if (!hasOption(obj, option)) problems += "${pair.obj} has no '$option' (${a.actions.filterNotNull()})"
                if (pair.depleted in table.byObject) problems += "${pair.depleted} is itself a node object"
                if (node.skill == "woodcutting" && hasOption(depleted, option)) problems += "${pair.depleted} has '$option'"
                if (a.sizeX != b.sizeX || a.sizeY != b.sizeY) problems += "${pair.obj} ${a.sizeX}x${a.sizeY} vs ${pair.depleted} ${b.sizeX}x${b.sizeY}"
            }
        }
        assertEquals(emptyList(), problems)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadCache() {
            val path = Paths.get("../data", "cache")
            // The OSRS cache is not committed; skip (rather than fail) where it hasn't been installed, e.g. CI.
            Assume.assumeTrue("No cache at ${path.toAbsolutePath()}", Files.exists(path.resolve("main_file_cache.dat2")))
            CacheManager.init(path, BuildInfo.REVISION)
        }
    }
}
