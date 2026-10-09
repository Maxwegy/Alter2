package org.alter.data.config

import org.alter.data.io.AtomicFiles
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class InfraConfigTests {
    @Test
    fun `missing file gives defaults`() {
        assertEquals(InfraConfig(), InfraConfig.load(Paths.get("does-not-exist.yml")))
    }

    @Test
    fun `partial file keeps defaults for absent keys`() {
        val file = Files.createTempFile("infra", ".yml")
        AtomicFiles.writeText(file, "wiki:\n  minRequestIntervalMs: 2500\nunknownKey: 1\n")
        val config = InfraConfig.load(file)
        assertEquals(2500, config.wiki.minRequestIntervalMs)
        assertEquals(InfraConfig.Wiki().rawCacheTtlHours, config.wiki.rawCacheTtlHours)
        assertEquals(InfraConfig.MissingContent(), config.missingContent)
    }

    @Test
    fun `committed config parses and matches the defaults`() {
        // Keeps data/cfg/infrastructure.yml and the code defaults from drifting apart.
        assertEquals(InfraConfig(), InfraConfig.load(Paths.get("../data/cfg/infrastructure.yml")))
    }

    @Test
    fun `atomic write replaces existing content`() {
        val file = Files.createTempFile("atomic", ".txt")
        AtomicFiles.writeText(file, "first")
        AtomicFiles.writeText(file, "second")
        assertEquals("second", Files.readString(file))
    }
}
