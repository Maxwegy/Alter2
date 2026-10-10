package org.alter.plugins.content.infrastructure.spawns

import org.alter.game.model.Direction

/** Pure parsing of the spawn commands' arguments; every failure is a message for the player. */
object SpawnCommandArgs {
    sealed interface Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>

        data class Error(val message: String) : Parsed<Nothing>
    }

    /** The compass directions a spawn entry may face (the engine's [Direction] without NONE). */
    val COMPASS: List<Direction> = Direction.values().filter { it != Direction.NONE }

    /** `::setwander <n>`: one non-negative whole number. */
    fun walkRadius(args: Array<String>): Parsed<Int> {
        val usage = "Usage: ::setwander <n>, where n is a walk radius of 0 or more tiles."
        if (args.size != 1) return Parsed.Error(usage)
        val n = args[0].toIntOrNull() ?: return Parsed.Error(usage)
        return if (n < 0) Parsed.Error(usage) else Parsed.Ok(n)
    }

    /** `::setdirection <DIR>`: one compass direction name, any case, `-` or `_` between words. */
    fun direction(args: Array<String>): Parsed<Direction> {
        val usage = "Usage: ::setdirection <DIR>, where DIR is one of ${COMPASS.joinToString { it.name }}."
        if (args.size != 1) return Parsed.Error(usage)
        val name = args[0].uppercase().replace('-', '_')
        return COMPASS.firstOrNull { it.name == name }?.let { Parsed.Ok(it) } ?: Parsed.Error(usage)
    }

    /** Commands that take no arguments. */
    fun none(command: String, args: Array<String>): Parsed<Unit> =
        if (args.isEmpty()) Parsed.Ok(Unit) else Parsed.Error("Usage: ::$command (no arguments); it acts on the NPC on your tile.")
}
