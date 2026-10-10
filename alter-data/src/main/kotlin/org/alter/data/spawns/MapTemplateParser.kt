package org.alter.data.spawns

import java.math.BigDecimal

/** One `{{Map|...}}` occurrence: [raw] is the verbatim template, [named] its named and [positional] its unnamed parameters. */
data class MapTemplate(val raw: String, val named: Map<String, String>, val positional: List<String>) {
    /** The feature type, lower case; null when the template sets none. */
    val mtype: String? get() = named["mtype"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}

/** A wiki coordinate as written (`3212`, `3055.5`); wiki `y` is engine `z`. */
data class MapCoordinate(val x: BigDecimal, val y: BigDecimal)

/** One spawnable feature of a `{{Map}}`: the spawn tile (the feature's centre), its plane and its shape. */
data class MapFeature(val point: SpawnPoint, val height: Int, val shape: MapShape)

/** Why a `{{Map}}` (or a whole infobox version) produced no spawn. [key] is the report's reason name. */
sealed class SpawnSkip(val key: String, val detail: String) {
    class NoMtype : SpawnSkip("noMtype", "no mtype: Module:Map draws no feature, only the view centre")

    class UnsupportedMtype(val mtype: String) : SpawnSkip("unsupportedMtype.$mtype", "mtype=$mtype")

    class NonSurfaceMapId(val mapId: String) : SpawnSkip("nonSurfaceMapId", "mapID=$mapId")

    class BadPlane(val plane: String) : SpawnSkip("badPlane", "plane=$plane")

    class AnonymousFeature(val arg: String) : SpawnSkip("anonymousFeature", "anonymous feature '$arg'")

    class BadCoordinates(why: String) : SpawnSkip("badCoordinates", why)

    class NoCoordinates : SpawnSkip("noCoordinates", "no coordinates")

    class FractionalR(val r: String) : SpawnSkip("fractionalR", "r=$r shifts the centre by half a tile")

    class BadShape(why: String) : SpawnSkip("badShape", why)
}

/**
 * Reads `{{Map}}` templates the way Module:Map (https://oldschool.runescape.wiki/w/Module:Map) does, for the
 * shapes that describe an NPC spawn; Template:Map/doc (https://oldschool.runescape.wiki/w/Template:Map/doc)
 * documents the parameters.
 *
 * - Only `mtype` pin, rectangle, square and circle become spawns. Without an `mtype` the Module creates no
 *   feature at all (its coordinates only centre the view; a rendered `{{Map|3212,3219}}` has no overlay, checked
 *   with `action=parse` on 2026-10-10), so that is skipped as `noMtype`.
 * - These are single-point features: the Module makes one feature per coordinate (`AddFeaturePerCoord`).
 * - Coordinates are unnamed `x,y` parameters (also `x:y,x:y` pairs and `x:..,y:..`); the named `x`/`y` are used
 *   only when there are none. A missing or non-numeric coordinate falls back to the view default
 *   (3233, 3222) in the Module; here it is an error, never a location.
 * - `mapID` defaults to 0, the surface; features cannot span mapIDs, and anything else is skipped (D2).
 * - `plane` is 0-3, default 0.
 * - A fractional `r` shifts the centre by half a tile, so it is skipped as `fractionalR`.
 */
object MapTemplateParser {
    val SPAWN_MTYPES = setOf("pin", "rectangle", "square", "circle")

    sealed interface Result {
        data class Features(val features: List<MapFeature>) : Result

        data class Skipped(val skip: SpawnSkip) : Result
    }

    /** Every `{{Map|...}}` in [text] (comments and `<nowiki>` blocks ignored). `|map = No` gives none. */
    fun templates(text: String): List<MapTemplate> {
        val clean = Wikitext.stripInert(text)
        return Wikitext.templates(clean) { Wikitext.sameName(it, "Map") }.map { range ->
            val raw = clean.substring(range.first, range.last + 1)
            val parts = Wikitext.split(raw.substring(2, raw.length - 2))
            val named = LinkedHashMap<String, String>()
            val positional = mutableListOf<String>()
            parts.drop(1).forEach { part ->
                val eq = Wikitext.equalsAtDepthZero(part)
                if (eq >= 0) named[part.substring(0, eq).trim()] = part.substring(eq + 1).trim() else positional += part.trim()
            }
            MapTemplate(raw, named, positional)
        }
    }

    sealed interface Coordinates {
        data class Ok(val coords: List<MapCoordinate>) : Coordinates

        data class Bad(val skip: SpawnSkip) : Coordinates
    }

    /**
     * The template's coordinates, following Module:Map's `ParseAnonArgs`: each unnamed parameter is split on
     * `,`, then each option on `:`. A bare number is the next x or y; `x:y` with two numbers is a pair; `x:` and
     * `y:` set one axis. Any other `key:value` makes the parameter an anonymous feature with its own options.
     * The named `x` and `y` are the coordinate only when no unnamed one exists.
     */
    fun coordinates(t: MapTemplate): Coordinates {
        val coords = mutableListOf<MapCoordinate>()
        for (arg in t.positional) {
            if (arg.isBlank()) continue
            val axes = mutableListOf<BigDecimal>()
            for (opt in arg.split(Regex("""\s*,\s*""")).filter(String::isNotBlank)) {
                val kv = opt.split(Regex("""\s*:\s*"""))
                when {
                    kv.size == 1 -> axes += number(kv[0]) ?: return Coordinates.Bad(SpawnSkip.BadCoordinates("'${kv[0]}' is not a number"))
                    kv.size == 2 && number(kv[0]) != null && number(kv[1]) != null -> {
                        if (axes.size % 2 != 0) return Coordinates.Bad(SpawnSkip.BadCoordinates("mismatched coordinates in '$arg'"))
                        axes += number(kv[0])!!
                        axes += number(kv[1])!!
                    }
                    kv.size == 2 && (kv[0] == "x" || kv[0] == "y") ->
                        axes += number(kv[1]) ?: return Coordinates.Bad(SpawnSkip.BadCoordinates("'$opt' is not a number"))
                    else -> return Coordinates.Bad(SpawnSkip.AnonymousFeature(arg))
                }
            }
            if (axes.size % 2 != 0) return Coordinates.Bad(SpawnSkip.BadCoordinates("mismatched coordinates in '$arg'"))
            axes.chunked(2).forEach { (x, y) -> coords += MapCoordinate(x, y) }
        }
        if (coords.isEmpty()) {
            val x = t.named["x"]?.takeIf { it.isNotBlank() }
            val y = t.named["y"]?.takeIf { it.isNotBlank() }
            if (x != null && y != null) {
                val nx = number(x) ?: return Coordinates.Bad(SpawnSkip.BadCoordinates("x=$x is not a number"))
                val ny = number(y) ?: return Coordinates.Bad(SpawnSkip.BadCoordinates("y=$y is not a number"))
                coords += MapCoordinate(nx, ny)
            }
        }
        return Coordinates.Ok(coords)
    }

    /** The spawnable features of [t], or the first reason it has none. */
    fun features(t: MapTemplate): Result {
        fun skip(s: SpawnSkip) = Result.Skipped(s)
        val mtype = t.mtype ?: return skip(SpawnSkip.NoMtype())
        if (mtype !in SPAWN_MTYPES) return skip(SpawnSkip.UnsupportedMtype(mtype))
        t.named["mapID"]?.takeIf { it.isNotBlank() }?.let { if (number(it)?.compareTo(BigDecimal.ZERO) != 0) return skip(SpawnSkip.NonSurfaceMapId(it)) }
        val planeRaw = t.named["plane"]?.takeIf { it.isNotBlank() }
        val height = if (planeRaw == null) 0 else planeRaw.toIntOrNull()?.takeIf { it in 0..NpcSpawnFiles.MAX_HEIGHT } ?: return skip(SpawnSkip.BadPlane(planeRaw))
        val coords = when (val c = coordinates(t)) {
            is Coordinates.Bad -> return skip(c.skip)
            is Coordinates.Ok -> c.coords
        }
        if (coords.isEmpty()) return skip(SpawnSkip.NoCoordinates())
        val shape = when (val s = shape(mtype, t.named)) {
            is ShapeResult.Bad -> return skip(s.skip)
            is ShapeResult.Ok -> s.shape
        }
        val features = coords.map { c ->
            val x = tile(c.x) ?: return skip(SpawnSkip.BadCoordinates("${c.x},${c.y} is not a tile"))
            val z = tile(c.y) ?: return skip(SpawnSkip.BadCoordinates("${c.x},${c.y} is not a tile"))
            MapFeature(SpawnPoint(x, z), height, shape)
        }
        return Result.Features(features)
    }

    private sealed interface ShapeResult {
        data class Ok(val shape: MapShape) : ShapeResult

        data class Bad(val skip: SpawnSkip) : ShapeResult
    }

    /**
     * Module:Map draws squares with the rectangle code: `r or floor(rectX/2)`, `rectX`/`squareX` default 20.
     * So a square without `r` but with a size is a rectangle, and one with neither is `r` 10.
     */
    private fun shape(mtype: String, named: Map<String, String>): ShapeResult {
        val rRaw = named["r"]?.takeIf { it.isNotBlank() }
        val r = rRaw?.let { raw ->
            val n = number(raw) ?: return ShapeResult.Bad(SpawnSkip.BadShape("r=$raw is not a number"))
            if (n.stripTrailingZeros().scale() > 0) return ShapeResult.Bad(SpawnSkip.FractionalR(raw))
            n.toInt().takeIf { it >= 0 } ?: return ShapeResult.Bad(SpawnSkip.BadShape("r=$raw is negative"))
        }
        fun side(vararg keys: String): Int? {
            val raw = keys.firstNotNullOfOrNull { named[it]?.takeIf(String::isNotBlank) } ?: return null
            return raw.toIntOrNull()?.takeIf { it >= 1 } ?: -1
        }
        val rectX = side("rectX", "squareX")
        val rectY = side("rectY", "squareY")
        if (rectX == -1 || rectY == -1) return ShapeResult.Bad(SpawnSkip.BadShape("rectX/rectY must be whole numbers of at least 1"))
        return ShapeResult.Ok(
            when (mtype) {
                "pin" -> MapShape.Pin
                "circle" -> MapShape.Circle(r ?: MapShape.DEFAULT_R)
                "square" -> if (r == null && (rectX != null || rectY != null)) {
                    MapShape.Rectangle(rectX ?: MapShape.DEFAULT_RECT, rectY ?: MapShape.DEFAULT_RECT)
                } else {
                    MapShape.Square(r ?: MapShape.DEFAULT_R)
                }
                else -> MapShape.Rectangle(rectX ?: MapShape.DEFAULT_RECT, rectY ?: MapShape.DEFAULT_RECT, r)
            },
        )
    }

    private fun number(s: String): BigDecimal? = s.trim().toBigDecimalOrNull()

    private fun tile(n: BigDecimal): Int? =
        n.takeIf { it.stripTrailingZeros().scale() <= 0 }?.toInt()?.takeIf { it in 0..NpcSpawnFiles.MAX_COORDINATE }
}
