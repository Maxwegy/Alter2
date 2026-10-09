package org.alter.data.wiki

/**
 * Builds the Lua query string for the wiki's `action=bucket` API, e.g.
 * `bucket('dropsline').select('page_name','drop_json').where('page_name','Abyssal demon').limit(5000).offset(0).run()`.
 */
data class BucketQuery(
    val bucket: String,
    private val fields: List<String> = emptyList(),
    private val joins: List<Triple<String, String, String>> = emptyList(),
    private val conditions: List<String> = emptyList(),
    private val order: Pair<String, Boolean>? = null,
    val limit: Int = 5000,
    val offset: Int = 0,
) {
    fun select(vararg names: String) = copy(fields = fields + names)

    fun join(other: String, otherField: String, thisField: String) = copy(joins = joins + Triple(other, otherField, thisField))

    fun where(field: String, value: String) = copy(conditions = conditions + "${quote(field)},${quote(value)}")

    /** For INTEGER fields such as `infobox_scenery.object_id`, which must not be quoted. */
    fun where(field: String, value: Int) = copy(conditions = conditions + "${quote(field)},$value")

    /** Rows whose page is in [category] (e.g. "Pets"). */
    fun whereCategory(category: String) = copy(conditions = conditions + quote("Category:$category"))

    fun orderBy(field: String, ascending: Boolean = true) = copy(order = field to ascending)

    fun page(limit: Int, offset: Int) = copy(limit = limit, offset = offset)

    fun build(): String = buildString {
        append("bucket(").append(quote(bucket)).append(')')
        joins.forEach { (other, a, b) -> append(".join(").append(quote(other)).append(',').append(quote(a)).append(',').append(quote(b)).append(')') }
        if (fields.isNotEmpty()) append(".select(").append(fields.joinToString(",") { quote(it) }).append(')')
        conditions.forEach { append(".where(").append(it).append(')') }
        order?.let { (field, ascending) -> append(".orderBy(").append(quote(field)).append(',').append(quote(if (ascending) "asc" else "desc")).append(')') }
        append(".limit(").append(limit).append(").offset(").append(offset).append(").run()")
    }

    private fun quote(value: String) = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
