package org.alter.plugins.content.skills.resources

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `data/cfg/resources/resource_nodes.json` is hand-maintained; these checks keep it honest and resolvable without a cache. */
class ResourceNodesDataTests {
    private val table = ResourceNodeDefs.load(Paths.get("../data/cfg/resources/resource_nodes.json"))

    /** `name:id` lines of a committed RSCM table, in file order. */
    private fun rscm(kind: String): List<Pair<String, Int>> = Files.readAllLines(Paths.get("../data/cfg/rscm/$kind.rscm"))
        .map { it.split(':') }.filter { it.size == 2 }.mapNotNull { (name, id) -> id.trim().toIntOrNull()?.let { "$kind." + name.trim() to it } }

    /** True when [name] exists and is the first (canonical, gameval) name for its id rather than an alias. */
    private fun canonical(kind: String): (String) -> Boolean {
        val lines = rscm(kind)
        val idByName = lines.toMap()
        val firstById = lines.reversed().associate { (name, id) -> id to name }
        return { name -> idByName[name]?.let { firstById[it] } == name }
    }

    private val objectOk = canonical("object")
    private val itemOk = canonical("item")

    private val objectNames = table.nodes.flatMap { n -> n.objects.flatMap { listOf(it.obj, it.depleted) } + n.objectsTodo.map { it.obj } }
    private val itemNames = table.skills.values.flatMap { s -> s.tools.map { it.item } + (s.preRoll?.table?.mapNotNull { it.item } ?: emptyList()) } +
        table.nodes.flatMap { n -> listOfNotNull(n.reward) + n.tertiary.mapNotNull { it.item } }

    @Test
    fun `names are canonical RSCM names`() {
        assertEquals(emptyList(), objectNames.distinct().filterNot(objectOk), "unknown object names or aliases; use the first name per id in object.rscm")
        assertEquals(emptyList(), itemNames.distinct().filterNot(itemOk), "unknown item names or aliases; use the first name per id in item.rscm")
    }

    @Test
    fun `each object belongs to one entry`() {
        val nodeObjects = table.nodes.flatMap { n -> n.objects.map { it.obj } + n.objectsTodo.map { it.obj } }
        assertEquals(nodeObjects.size, nodeObjects.toSet().size)
    }

    @Test
    fun `every entry, tool and pre-roll cites the OSRS wiki`() {
        fun wiki(s: String?) = s?.startsWith("https://oldschool.runescape.wiki/") == true
        assertEquals(emptyList(), table.nodes.filterNot { wiki(it.source) }.map { it.id })
        assertEquals(emptyList(), table.skills.values.flatMap { it.tools }.filterNot { wiki(it.source) }.map { it.item })
        assertEquals(emptyList(), table.skills.values.filterNot { wiki(it.source) }.map { it.key })
        table.skills.values.mapNotNull { it.preRoll }.forEach { assertTrue(wiki(it.source)) }
    }

    @Test
    fun `the loaded and skipped node ids`() {
        assertEquals(listOf("tree", "oak", "willow", "copper", "tin", "iron", "coal"), table.loaded.map { it.id })
        assertEquals(
            listOf("maple", "teak", "mahogany", "yew", "magic", "redwood", "clay", "silver", "gold", "mithril", "adamantite", "runite"),
            table.skipped.map { it.id },
        )
    }

    @Test
    fun `sourced values`() {
        val n = table.nodes.associateBy { it.id }
        assertEquals(Depletion.Timer(45), n.getValue("oak").depletion)
        assertEquals(Depletion.Timer(50), n.getValue("willow").depletion)
        assertEquals(Respawn.Range(59, 98), n.getValue("tree").respawn)
        assertEquals(Success.ByLevel(Chart(16, 100)), n.getValue("coal").success)
        assertEquals(Chart(64, 200), (n.getValue("tree").success as Success.ByTier).charts["bronze"])
        assertEquals(4, table.skills.getValue("woodcutting").rollIntervalTicks)
        assertEquals(listOf("item.infernal_axe", "item.trail_gilded_axe"), table.skills.getValue("woodcutting").tools.filterNot { it.loaded }.map { it.item })
        assertEquals(128, table.skills.getValue("mining").preRoll!!.tableWeight)
        // The nest tertiaries are TODO and never applied.
        assertTrue(table.loaded.flatMap { it.tertiary }.none { it.loaded })
    }

    @Test
    fun `messages are null`() {
        assertTrue(table.messages.all.all { it == null })
    }

    @Test
    fun `the parser rejects a bad file`() {
        val skills = """"skills":{"mining":{"option":"Mine","successBy":"level","tools":[{"item":"item.bronze_pickaxe","level":1,"rollIntervalTicks":8,"animation":625}]}}"""
        fun file(node: String) = """{"schemaVersion":1,"messages":{},$skills,"nodes":[$node]}"""
        val ok = """{"id":"copper","skill":"mining","level":1,"experience":17.5,"objects":[{"object":"object.copperrock1","depleted":"object.rocks1"}],"depletion":"always","respawnTicks":4,"success":{"byLevel":[100,350]},"reward":"item.copper_ore"}"""
        assertEquals(1, ResourceNodeDefs.parse(file(ok)).loaded.size)
        assertFailsWith<IllegalArgumentException> { ResourceNodeDefs.parse(file(ok).replace("\"schemaVersion\":1", "\"schemaVersion\":2")) }
        assertFailsWith<IllegalArgumentException> { ResourceNodeDefs.parse(file(ok.replace("\"level\":1,", "\"level\":0,"))) }
        assertFailsWith<IllegalArgumentException> { ResourceNodeDefs.parse(file(ok.replace("\"skill\":\"mining\"", "\"skill\":\"sailing\""))) }
        assertFailsWith<IllegalArgumentException> { ResourceNodeDefs.parse(file(ok.replace(",\"reward\":\"item.copper_ore\"", ""))) }
        assertFailsWith<IllegalArgumentException> { ResourceNodeDefs.parse(file(ok.replace("\"always\"", "\"sometimes\""))) }
        assertFailsWith<IllegalArgumentException> { ResourceNodeDefs.parse(file(ok.replace("{\"byLevel\":[100,350]}", "{\"byTier\":{\"bronze\":[1,2]}}"))) }
        assertFailsWith<IllegalArgumentException> { ResourceNodeDefs.parse(file(ok.replace("[100,350]", "[350,100]"))) }
        // The same object in two entries.
        assertFailsWith<IllegalArgumentException> { ResourceNodeDefs.parse(file("$ok,${ok.replace("\"copper\"", "\"tin\"")}")) }
        // A TODO entry needs no objects, chart or respawn.
        assertEquals(1, ResourceNodeDefs.parse(file("""{"id":"gold","skill":"mining","level":40,"experience":65,"todo":"later"}""")).skipped.size)
    }
}
