package org.alter.data.spawns

/** A world tile column (wiki `x,y` = engine `x,z`); the plane is carried separately. */
data class SpawnPoint(val x: Int, val z: Int)

/** Inclusive tile bounds of a spawn area. */
data class SpawnBounds(val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int)

/**
 * The shapes of the wiki's `{{Map}}` template that map onto an NPC spawn, with the template's defaults
 * (https://oldschool.runescape.wiki/w/Template:Map/doc): pins mark one exact tile; rectangles take
 * `rectX`/`rectY` (default 20, `squareX`/`squareY` are aliases) and an optional `r`; squares and circles take
 * a centre and `r` (default 10), where `r` is half the edge.
 */
sealed interface MapShape {
    data object Pin : MapShape

    data class Rectangle(val rectX: Int = DEFAULT_RECT, val rectY: Int = DEFAULT_RECT, val r: Int? = null) : MapShape

    data class Square(val r: Int = DEFAULT_R) : MapShape

    data class Circle(val r: Int = DEFAULT_R) : MapShape

    companion object {
        const val DEFAULT_RECT = 20
        const val DEFAULT_R = 10
    }
}

/** Pure spawn geometry shared by the boot loader, the data tests and the wiki generator. */
object SpawnRules {
    /** The same formula as the engine's `Tile.regionId`: 64x64 regions, x in the high byte. */
    fun regionId(x: Int, z: Int): Int = ((x shr 6) shl 8) or (z shr 6)

    /** The south-west tile of [regionId]. */
    fun regionBase(regionId: Int): SpawnPoint = SpawnPoint((regionId shr 8) shl 6, (regionId and 0xFF) shl 6)

    /**
     * How far an NPC spawned for [shape] may wander. Pin: 0. Rectangle: `r` when given, otherwise half the
     * shorter side, rounded down (Module:Map's `rectXR = r or floor(rectX/2)`), so Hans's 23x31 gives 11.
     * Square and circle: `r`.
     */
    fun walkRadius(shape: MapShape): Int = when (shape) {
        MapShape.Pin -> 0
        is MapShape.Rectangle -> shape.r ?: (minOf(shape.rectX, shape.rectY) / 2)
        is MapShape.Square -> shape.r
        is MapShape.Circle -> shape.r
    }

    /**
     * The tiles [shape] covers around [centre]. Rectangles follow Module:Map
     * (https://oldschool.runescape.wiki/w/Module:Map): half-widths `r or floor(rectX/2)`, and an odd side adds
     * one tile on the east (north) edge. Squares and circles span `r` each way (the circle's bounding box).
     */
    fun bounds(centre: SpawnPoint, shape: MapShape): SpawnBounds = when (shape) {
        MapShape.Pin -> SpawnBounds(centre.x, centre.x, centre.z, centre.z)
        is MapShape.Rectangle -> {
            val xr = shape.r ?: (shape.rectX / 2)
            val zr = shape.r ?: (shape.rectY / 2)
            SpawnBounds(centre.x - xr, centre.x + xr + (shape.rectX and 1), centre.z - zr, centre.z + zr + (shape.rectY and 1))
        }
        is MapShape.Square -> SpawnBounds(centre.x - shape.r, centre.x + shape.r, centre.z - shape.r, centre.z + shape.r)
        is MapShape.Circle -> SpawnBounds(centre.x - shape.r, centre.x + shape.r, centre.z - shape.r, centre.z + shape.r)
    }
}
