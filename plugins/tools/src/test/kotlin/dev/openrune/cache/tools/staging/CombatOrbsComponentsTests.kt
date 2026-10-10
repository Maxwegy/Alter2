package dev.openrune.cache.tools.staging

import dev.openrune.cache.filestore.Cache
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * The server's bindings on interfaces 593 (`combat_interface`) and 160 (`orbs`), checked against the revision 241
 * cache in `data/cache`: each bound component exists, has the expected gameval name, and transmits the op the
 * server handles. The ids mirror `AttackTab`, `SpecialAttacksPlugin`, `RunEnergyPlugin`, `PrayersPlugin`,
 * `XpDropsPlugin`, `WorldMapPlugin` and `sendWeaponComponentInformation`; see
 * `data/reports/combat-orbs-241-components.md`. Skipped without a cache.
 */
class CombatOrbsComponentsTests {
    private data class Binding(val iface: Int, val component: Int, val name: String, val ops: List<Int>, val opText: String? = null)

    private val buttons = listOf(
        Binding(593, 6, "0", listOf(1)),
        Binding(593, 10, "1", listOf(1)),
        Binding(593, 14, "2", listOf(1)),
        Binding(593, 18, "3", listOf(1)),
        Binding(593, 32, "retaliate", listOf(1), "Auto retaliate"),
        Binding(593, 39, "special_attack", listOf(1), "Special Attack"),
        Binding(160, 36, "specbutton", listOf(1)),
        Binding(160, 28, "runbutton", listOf(1), "Toggle Run"),
        Binding(160, 20, "prayerbutton", listOf(1, 2)),
        Binding(160, 6, "xp_drops", listOf(1, 2)),
        Binding(160, 55, "worldmap", listOf(1, 2, 3, 4)),
        // The autocast buttons (data/cfg/combat/autocast.json), already bound from the cache.
        Binding(593, 28, "autocast_normal", listOf(1), "Choose spell"),
        Binding(593, 23, "autocast_defensive", listOf(1), "Choose spell"),
    )

    @Test
    fun `every bound button exists, is named as expected and transmits its ops`() {
        for (b in buttons) {
            val c = component(b.iface, b.component)
            assertEquals("${b.iface}:${b.component}", b.name, names(b.iface)[b.component])
            for (op in b.ops) assertTrue("${b.iface}:${b.component} op$op", c.transmitsOp(op))
            b.opText?.let { text -> assertTrue("${b.iface}:${b.component} ops ${c.ops}", c.ops!!.any { it != null && text in it }) }
        }
    }

    @Test
    fun `the weapon name and category are text components`() {
        assertEquals("title", names(593)[3])
        assertEquals("text", component(593, 3).typeName)
        assertEquals("category", names(593)[5])
        assertEquals("text", component(593, 5).typeName)
    }

    @Test
    fun `the 228-era ids are not the buttons on 241`() {
        val old = listOf(593 to 5, 593 to 9, 593 to 13, 593 to 17, 593 to 31, 593 to 36, 160 to 35, 160 to 27, 160 to 19, 160 to 5, 160 to 53)
        for ((iface, id) in old) assertFalse("$iface:$id still transmits op1", component(iface, id).transmitsOp(1))
    }

    private fun component(iface: Int, id: Int): If3Component {
        val c = components.getValue(iface)[id]
        assertNotNull("$iface:$id missing", c)
        assertEquals("$iface:$id", null, c!!.error)
        return c
    }

    private fun names(iface: Int): Map<Int, String> = gameval.getValue(iface)

    companion object {
        private val dir = Paths.get("../../data/cache")
        private var cache: Cache? = null
        private val components = mutableMapOf<Int, Map<Int, If3Component>>()
        private val gameval = mutableMapOf<Int, Map<Int, String>>()

        @BeforeClass
        @JvmStatic
        fun load() {
            if (!Files.exists(dir.resolve("main_file_cache.idx255"))) return
            val c = Cache.load(dir, false)
            cache = c
            for (iface in listOf(593, 160)) {
                components[iface] = InterfaceDump.read(c, iface)
                gameval[iface] = Gameval.componentNames(c, iface)
            }
        }

        @AfterClass
        @JvmStatic
        fun close() {
            cache?.close()
        }
    }

    @org.junit.Before
    fun needsCache() {
        assumeTrue("no cache in data/cache", cache != null)
        assumeTrue("no gameval tables (revision before 241)", gameval.values.all { it.isNotEmpty() })
    }
}
