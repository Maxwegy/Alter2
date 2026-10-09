package gg.rsmod.util

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RevisionGuardTests {
    private val dir = Files.createTempDirectory("revguard")
    private val manifest = dir.resolve("cache-manifest.json")

    @Test
    fun `missing manifest and no yml revision only warns`() {
        val result = RevisionGuard.check(228, null, manifest)
        assertIs<RevisionGuard.Result.Ok>(result)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `matching yml revision is accepted`() {
        assertIs<RevisionGuard.Result.Ok>(RevisionGuard.check(228, 228, manifest))
    }

    @Test
    fun `mismatching yml revision is fatal`() {
        assertIs<RevisionGuard.Result.Fatal>(RevisionGuard.check(228, 227, manifest))
    }

    @Test
    fun `matching manifest build passes without warnings`() {
        Files.writeString(manifest, """{ "openrs2Id": 2038, "build": 228 }""")
        assertEquals(RevisionGuard.Result.Ok(emptyList()), RevisionGuard.check(228, null, manifest))
    }

    @Test
    fun `mismatching manifest build is fatal`() {
        Files.writeString(manifest, """{ "build": 241 }""")
        assertIs<RevisionGuard.Result.Fatal>(RevisionGuard.check(228, null, manifest))
    }

    @Test
    fun `generated build info is populated`() {
        assert(BuildInfo.REVISION > 0)
        assert(BuildInfo.RSPROT_VERSION.isNotBlank())
    }
}
