package org.alter.data.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path

/**
 * Settings for the Phase 1 data infrastructure, read from `data/cfg/infrastructure.yml`.
 * Every field has a default, so a missing file or key is never fatal.
 */
data class InfraConfig(
    val wiki: Wiki = Wiki(),
    val missingContent: MissingContent = MissingContent(),
    val autosave: Autosave = Autosave(),
) {
    data class Wiki(
        /** Contact shown in the User-Agent (email, Discord or URL). The OSRS Wiki asks API users for one. */
        val contact: String = "",
        /** Public repository URL shown in the User-Agent. */
        val repositoryUrl: String = "",
        val minRequestIntervalMs: Long = 1_000,
        val rawCacheTtlHours: Long = 24,
        /** A sync is rejected if any bucket returns this many percent fewer rows than the previous manifest. */
        val maxRowDropPercent: Int = 10,
        /** Item-name patterns (regex, case-insensitive) classified as tertiary drops. */
        val tertiaryPatterns: List<String> = DEFAULT_TERTIARY_PATTERNS,
    )

    data class MissingContent(
        val enabled: Boolean = true,
        val flushIntervalSeconds: Long = 30,
        val maxLocationsPerEntry: Int = 5,
        /** Minimum seconds between two dev chat messages for the same key and player. */
        val devMessageCooldownSeconds: Long = 60,
    )

    data class Autosave(
        val enabled: Boolean = true,
        val intervalMinutes: Long = 5,
    )

    companion object {
        val DEFAULT_TERTIARY_PATTERNS = listOf(
            "^clue (scroll|bottle|nest|geode)",
            "^scroll box",
            "^ensouled .* head$",
            "champion scroll$",
            "^(brimstone|larran's|mossy|giant|ecumenical) key",
            "^dark totem",
            "^ancient shard$",
            "^looting bag$",
            "^blighted ",
            "^long bone$",
            "^curved bone$",
        )

        private val mapper = YAMLMapper().registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        fun load(path: Path): InfraConfig =
            if (Files.exists(path)) mapper.readValue(path.toFile(), InfraConfig::class.java) else InfraConfig()
    }
}
