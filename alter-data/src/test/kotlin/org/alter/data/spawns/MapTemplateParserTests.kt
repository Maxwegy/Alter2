package org.alter.data.spawns

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MapTemplateParserTests {
    private fun mapOf(page: String) = MapTemplateParser.templates(InfoboxFields.pairs(InfoboxFields.infoboxes(page).single()).single().map!!)

    private fun coords(t: MapTemplate) = assertIs<MapTemplateParser.Coordinates.Ok>(MapTemplateParser.coordinates(t)).coords
        .map { it.x.toInt() to it.y.toInt() }

    private fun features(raw: String) =
        assertIs<MapTemplateParser.Result.Features>(MapTemplateParser.features(MapTemplateParser.templates(raw).single())).features

    private fun skip(raw: String) =
        assertIs<MapTemplateParser.Result.Skipped>(MapTemplateParser.features(MapTemplateParser.templates(raw).single())).skip

    @Test
    fun `Hans is one rectangle, 23 by 31, radius 11`() {
        val t = mapOf(Fixtures.hans).single()
        assertEquals("{{Map|name=Hans|3212,3219|rectX=23|rectY=31|mtype=rectangle}}", t.raw)
        assertEquals("rectangle", t.mtype)
        assertEquals(listOf(3212 to 3219), coords(t))
        val f = assertIs<MapTemplateParser.Result.Features>(MapTemplateParser.features(t)).features.single()
        assertEquals(MapFeature(SpawnPoint(3212, 3219), 0, MapShape.Rectangle(23, 31)), f)
        assertEquals(11, SpawnRules.walkRadius(f.shape))
    }

    @Test
    fun `Shop keeper is a pin at the named x and y`() {
        val t = mapOf(Fixtures.shopKeeper).single()
        assertEquals("pin", t.mtype)
        assertEquals(listOf(3211 to 3247), coords(t))
        assertEquals(
            listOf(MapFeature(SpawnPoint(3211, 3247), 0, MapShape.Pin)),
            assertIs<MapTemplateParser.Result.Features>(MapTemplateParser.features(t)).features,
        )
    }

    @Test
    fun `Duke Horacio is a five-point polygon on plane 1, which is skipped`() {
        val t = mapOf(Fixtures.duke).single()
        assertEquals(5, coords(t).size)
        assertEquals("polygon", t.mtype)
        assertEquals("1", t.named["plane"])
        assertEquals("unsupportedMtype.polygon", assertIs<MapTemplateParser.Result.Skipped>(MapTemplateParser.features(t)).skip.key)
    }

    @Test
    fun `Town Crier squares use the named centre and r across map1 to map7`() {
        val maps = InfoboxFields.pairs(InfoboxFields.infoboxes(Fixtures.townCrier).single()).map { MapTemplateParser.templates(it.map!!).single() }
        val all = maps.map { assertIs<MapTemplateParser.Result.Features>(MapTemplateParser.features(it)).features.single() }
        assertEquals(7, all.size)
        assertEquals(MapFeature(SpawnPoint(3254, 3428), 0, MapShape.Square(4)), all[0])
        assertEquals(MapFeature(SpawnPoint(3081, 3251), 0, MapShape.Square(5)), all[1])
        assertEquals(MapFeature(SpawnPoint(1664, 3669), 0, MapShape.Square(4)), all[6])
    }

    @Test
    fun `the doc's two-pin example makes two pins`() {
        val t = MapTemplateParser.templates(Fixtures.mapDoc).single { it.named["group"] == "pins2" }
        assertEquals("{{Map|mtype=pin|icon=magentaPin|2638,3300|2661,3308|group=pins2}}", t.raw)
        assertEquals(listOf(2638 to 3300, 2661 to 3308), coords(t))
        assertEquals(listOf(SpawnPoint(2638, 3300), SpawnPoint(2661, 3308)), features(t.raw).map { it.point })
    }

    @Test
    fun `the doc's nowiki examples are not templates`() {
        assertEquals(1, MapTemplateParser.templates(Fixtures.mapDoc).count { it.raw.contains("2638,3300") })
    }

    @Test
    fun `anonymous x colon y pairs`() {
        val t = MapTemplateParser.templates("{{Map|1000:1000,2000:2000|mtype=pin}}").single()
        assertEquals(listOf(1000 to 1000, 2000 to 2000), coords(t))
        assertEquals(listOf(SpawnPoint(1000, 1000), SpawnPoint(2000, 2000)), features(t.raw).map { it.point })
    }

    @Test
    fun `map = No has no template, two templates give two`() {
        assertTrue(MapTemplateParser.templates("No").isEmpty())
        val box = InfoboxFields.parse("Infobox NPC\n|map = No\n|id = 1")
        assertTrue(MapTemplateParser.templates(InfoboxFields.pairs(box).single().map!!).isEmpty())
        assertEquals(2, MapTemplateParser.templates("{{Map|1,2|mtype=pin}}{{Map|3,4|mtype=pin}}").size)
        assertTrue(MapTemplateParser.templates("{{Mapframe|1,2}}{{Map link|x}}").isEmpty())
        assertEquals(1, MapTemplateParser.templates("{{map|1,2|mtype=pin}}").size)
    }

    @Test
    fun `a Map without coordinates is NoCoordinates, never the view default`() {
        assertEquals("noCoordinates", skip("{{Map|name=X|mtype=pin}}").key)
        assertEquals("noCoordinates", skip("{{Map|name=X|x=3200|mtype=pin}}").key)
        assertEquals("badCoordinates", skip("{{Map|name=X|abc,3200|mtype=pin}}").key)
        assertEquals("badCoordinates", skip("{{Map|3200|mtype=pin}}").key)
        assertEquals("badCoordinates", skip("{{Map|3200.5,3200|mtype=pin}}").key)
    }

    @Test
    fun `skip reasons`() {
        assertEquals("noMtype", skip("{{Map|name=X|3212,3219}}").key)
        assertEquals("unsupportedMtype.line", skip("{{Map|mtype=line|1,2|3,4}}").key)
        assertEquals("nonSurfaceMapId", skip("{{Map|mapID=21|3104,5280|mtype=pin}}").key)
        assertEquals("nonSurfaceMapId", skip("{{Map|mapID=-1|3104,5280|mtype=pin}}").key)
        assertEquals("badPlane", skip("{{Map|plane=4|3104,3280|mtype=pin}}").key)
        assertEquals("fractionalR", skip("{{Map|mtype=square|r=5.5|2253,2925}}").key)
        assertEquals("anonymousFeature", skip("{{Map|zoom=3|mtype=square|r=1|3168,2411|mtype:dot,fill:#FFFF00,3177,2420}}").key)
        assertEquals("badShape", skip("{{Map|mtype=rectangle|rectX=0|1,2}}").key)
    }

    @Test
    fun `surface mapID 0, plane and shape defaults`() {
        assertEquals(MapFeature(SpawnPoint(3165, 3490), 2, MapShape.Circle(18)), features("{{Map|mapID=0|plane=2|mtype=circle|r=18|3165,3490}}").single())
        assertEquals(MapShape.Circle(10), features("{{Map|mtype=circle|1,2}}").single().shape)
        assertEquals(MapShape.Square(10), features("{{Map|mtype=square|1,2}}").single().shape)
        assertEquals(MapShape.Rectangle(21, 30), features("{{Map|mtype=rectangle|rectX=21|rectY=30|3363,3310}}").single().shape)
        assertEquals(MapShape.Rectangle(20, 20), features("{{Map|mtype=rectangle|1,2}}").single().shape)
        assertEquals(MapShape.Rectangle(23, 31, 5), features("{{Map|mtype=rectangle|rectX=23|rectY=31|r=5|1,2}}").single().shape)
        assertEquals(MapShape.Rectangle(7, 20), features("{{Map|mtype=square|squareX=7|1,2}}").single().shape)
    }

    @Test
    fun `single-point shapes make one feature per coordinate, like Module Map`() {
        assertEquals(listOf(SpawnPoint(1, 2), SpawnPoint(3, 4)), features("{{Map|mtype=square|r=2|1,2|3,4}}").map { it.point })
    }

    @Test
    fun `named x and y are used only without unnamed coordinates`() {
        assertEquals(listOf(SpawnPoint(5, 6)), features("{{Map|x=1|y=2|5,6|mtype=pin}}").map { it.point })
        val t = MapTemplateParser.templates("{{Map|x:7,y:8|mtype=pin}}").single()
        assertEquals(listOf(MapCoordinate(BigDecimal(7), BigDecimal(8))), assertIs<MapTemplateParser.Coordinates.Ok>(MapTemplateParser.coordinates(t)).coords)
    }
}
