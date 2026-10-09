package org.alter.data.wiki

/*
 * Typed accessors for raw Bucket rows. Bucket omits null fields, returns repeated fields as arrays, and may
 * return numbers as strings (TEXT fields), so every accessor is lenient and returns null when absent.
 */

fun BucketRow.str(field: String): String? = when (val value = this[field]) {
    null -> null
    is List<*> -> value.firstOrNull()?.toString()
    else -> value.toString()
}?.trim()?.takeIf { it.isNotEmpty() }

fun BucketRow.int(field: String): Int? = when (val value = this[field]) {
    is Number -> value.toInt()
    else -> str(field)?.replace(",", "")?.toDoubleOrNull()?.toInt()
}

fun BucketRow.double(field: String): Double? = when (val value = this[field]) {
    is Number -> value.toDouble()
    else -> str(field)?.replace(",", "")?.toDoubleOrNull()
}

fun BucketRow.bool(field: String): Boolean? = when (val value = this[field]) {
    is Boolean -> value
    null -> null
    else -> value.toString().lowercase() in setOf("true", "yes", "1")
}

fun BucketRow.strings(field: String): List<String> = when (val value = this[field]) {
    null -> emptyList()
    is List<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
    else -> listOfNotNull(value.toString().trim().takeIf(String::isNotEmpty))
}

/** Repeated numeric id fields (`id`, `item_id`) are TEXT arrays like ["415","416"]; non-numeric entries ("N/A") are dropped. */
fun BucketRow.ids(field: String): List<Int> = strings(field).mapNotNull { it.toIntOrNull() }
