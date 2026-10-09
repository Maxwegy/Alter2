package org.alter.cockpit

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectWriter
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/** One mapper for the API, the store's JSON columns and the event stream. Timestamps are ISO-8601 strings. */
object Json {
    val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** Pretty JSON with LF line endings on every OS, for files that are committed to the repository. */
    val prettyLf: ObjectWriter = mapper.writer(
        DefaultPrettyPrinter().withObjectIndenter(DefaultIndenter("  ", "\n")).withArrayIndenter(DefaultIndenter("  ", "\n")),
    )
}
