package org.alter.game.rsprot

import gg.rsmod.util.BuildInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rsprot artifact is derived from alter.osrsRevision, so this guards against a mis-published or
 * mis-wired artifact. Read by reflection: osrs-<rev>-shared is only on the runtime classpath.
 */
class RsprotRevisionTests {
    @Test
    fun `rsprot revision matches the build revision`() {
        val revision = Class.forName("net.rsprot.protocol.common.RSProtConstants").getField("REVISION").getInt(null)
        assertEquals(BuildInfo.REVISION, revision)
    }
}
