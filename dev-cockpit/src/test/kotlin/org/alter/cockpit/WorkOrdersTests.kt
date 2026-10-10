package org.alter.cockpit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.alter.cockpit.auth.Principal
import org.alter.cockpit.auth.Role
import org.alter.cockpit.events.EventBus
import org.alter.cockpit.inbox.ActionExecutor
import org.alter.cockpit.inbox.ApiException
import org.alter.cockpit.inbox.InboxAction
import org.alter.cockpit.inbox.InboxService
import org.alter.cockpit.store.AuditStore
import org.alter.cockpit.store.Database
import org.alter.cockpit.store.InboxStore
import org.alter.cockpit.workorders.CompileGate
import org.alter.cockpit.workorders.CompileResult
import org.alter.cockpit.workorders.RscmNames
import org.alter.cockpit.workorders.Scaffold
import org.alter.cockpit.workorders.ScaffoldContext
import org.alter.cockpit.workorders.ScaffoldFile
import org.alter.cockpit.workorders.ScaffoldService
import org.alter.cockpit.workorders.ScaffoldWorkspace
import org.alter.cockpit.workorders.WorkOrders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** `apply` through [WorkOrders]: the card flashes `compiling`, then carries the gate's verdict and the commit. */
class WorkOrdersTests {
    private val repo: Path = Files.createTempDirectory("repo")
    private val db = Database.inMemory()
    private val bus = EventBus()
    private val dev = Principal(1, Role.DEV, "dev")
    private val executor = object : ActionExecutor {
        override fun handles(kind: String) = kind.startsWith("enrich.")
        override suspend fun execute(action: InboxAction): Map<String, Any?> = mapOf("kind" to "talk_to")
    }
    // Unconfined everywhere: the launched apply finishes before apply() returns, so the tests need no waiting.
    private val unconfined = CoroutineScope(Dispatchers.Unconfined)
    private val inbox = InboxService(InboxStore(db), AuditStore(db), bus, listOf(executor), unconfined)
    private val workspace = ScaffoldWorkspace(repo, repo.resolve("data/cockpit/worktrees"), "main")
    private val scaffolds = ScaffoldService(emptyList(), ScaffoldContext(RscmNames(repo.resolve("data/cfg/rscm"))) { false })

    private class FakeGate(private val result: CompileResult) : CompileGate {
        override val task = result.task
        var calls = 0

        override fun compile(worktree: Path, task: String): CompileResult {
            calls++
            return result
        }
    }

    private fun git(vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(repo.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
        return out
    }

    @Before
    fun init() {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test")
        Files.writeString(repo.resolve("README.md"), "hello\n")
        Files.writeString(repo.resolve(".gitignore"), "/data/cockpit/\n")
        git("add", "-A")
        git("commit", "-q", "-m", "base")
    }

    /** A DONE card that already has a scaffold preview, the state the Apply button acts on. */
    private fun cardWithScaffold(): String {
        val card = inbox.propose("enrich.npc_op", "missing:NPC_OP:3105", "Talk-to Hans", "1 hit", params = mapOf("id" to 3105))!!
        inbox.go(card.id, dev)
        val scaffold = Scaffold("dialogue", "Hans dialogue", "cockpit/dialogue-hans-3105", listOf(ScaffoldFile("src/HansPlugin.kt", "class HansPlugin\n")))
        inbox.attachResult(card.id, dev, "workorder.scaffold", mapOf("scaffold" to scaffold, "preview" to workspace.preview(card.id, scaffold)))
        return card.id
    }

    @Suppress("UNCHECKED_CAST")
    private fun compile(card: InboxAction) = card.result?.get("compile") as Map<String, Any?>?

    @Test
    fun `a passing compile commits and records passed`() {
        val gate = FakeGate(CompileResult(true, emptyList(), 2500, ":game-plugins:compileKotlin"))
        val orders = WorkOrders(inbox, scaffolds, workspace, gate, unconfined, Dispatchers.Unconfined)
        val id = cardWithScaffold()
        orders.apply(id, dev)
        val card = inbox.get(id)
        assertEquals("passed", compile(card)?.get("state"))
        assertEquals(2500, (compile(card)?.get("durationMs") as Number).toInt())
        assertNotNull(card.result?.get("applied"))
        assertEquals("Hans dialogue", git("log", "--format=%s", "cockpit/dialogue-hans-3105").lines().first())
        assertEquals(1, gate.calls)
        // The UI saw the compiling flash first, then the verdict.
        val states = bus.flow.replayCache.filter { it.type == "inbox.updated" }.mapNotNull { ((it.payload as InboxAction).result?.get("compile") as? Map<*, *>)?.get("state") }
        assertEquals(listOf("compiling", "passed"), states.takeLast(2))
    }

    @Test
    fun `a failing compile records the error lines and commits nothing, and apply can be retried`() {
        val gate = FakeGate(CompileResult(false, listOf("e: src/HansPlugin.kt:1:1 Expecting a top level declaration"), 900, ":game-plugins:compileKotlin"))
        val orders = WorkOrders(inbox, scaffolds, workspace, gate, unconfined, Dispatchers.Unconfined)
        val id = cardWithScaffold()
        orders.apply(id, dev)
        val card = inbox.get(id)
        assertEquals("failed", compile(card)?.get("state"))
        assertEquals(listOf("e: src/HansPlugin.kt:1:1 Expecting a top level declaration"), compile(card)?.get("errors"))
        assertNull(card.result?.get("applied"))
        assertEquals("base", git("log", "--format=%s", "cockpit/dialogue-hans-3105").trim())
        // not compiling any more, so another apply is allowed
        orders.apply(id, dev)
        assertEquals(2, gate.calls)
    }

    @Test
    fun `apply without a scaffold is refused`() {
        val orders = WorkOrders(inbox, scaffolds, workspace, FakeGate(CompileResult.skipped("test")), unconfined, Dispatchers.Unconfined)
        val card = inbox.propose("enrich.npc_op", "missing:NPC_OP:1", "Talk-to Man", "1 hit", params = mapOf("id" to 1))!!
        inbox.go(card.id, dev)
        val refused = assertThrows(ApiException::class.java) { orders.apply(card.id, dev) }
        assertTrue(refused.message!!.contains("scaffold"))
    }
}
