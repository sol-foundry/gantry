package dev.gantry.mcp

import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.NO_DOCKER
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InstallerTest {
    private val engine = Engine(home = Files.createTempDirectory("gantry-mcp-test"), dockerCommand = NO_DOCKER)
    private val ws = Workspace(Files.createTempDirectory("gantry-project"), null)

    /** Records what it was asked to do and reports the apps it holds. */
    private class FakeAgent(
        override val id: String,
    ) : Agent {
        override val name = id.uppercase()
        val apps = mutableListOf<String>()

        override fun install(
            ws: Workspace,
            entry: ServerEntry,
        ): Outcome {
            apps += entry.name.removePrefix("gantry-")
            return Outcome.Installed(name, "here", updated = false)
        }

        override fun uninstall(
            ws: Workspace,
            app: String,
        ): Outcome = if (apps.remove(app)) Outcome.Removed(name, "here") else Outcome.Skipped(name, "not installed")

        override fun entries(ws: Workspace) = apps.associateWith { entry(it) }
    }

    private val claude = FakeAgent("claude")
    private val cursor = FakeAgent("cursor")
    private val installer = Installer(engine, listOf(claude, cursor)) { entry(it) }

    @Test
    fun `install refuses a missing app`() {
        val e = assertFailsWith<GantryException> { installer.install("ghost", ws, null) }
        assertTrue("gantry create" in e.message!!)
        assertEquals(emptyList(), claude.apps)
    }

    @Test
    fun `install reaches only the chosen agents`() {
        engine.create("shop", services = listOf("postgres"))

        val outcomes = installer.install("shop", ws, setOf("cursor"))

        assertEquals(listOf("CURSOR"), outcomes.map { it.agent })
        assertEquals(emptyList(), claude.apps)
        assertEquals(listOf("shop"), cursor.apps)
    }

    @Test
    fun `uninstall works for an app that no longer exists`() {
        cursor.apps += "gone"

        val outcomes = installer.uninstall("gone", ws, null)

        assertEquals(listOf("Skipped", "Removed"), outcomes.map { it::class.simpleName })
    }

    @Test
    fun `status groups apps by agent`() {
        engine.create("shop", services = listOf("postgres"))
        engine.create("billing", services = listOf("postgres"))
        installer.install("shop", ws, null)
        installer.install("billing", ws, setOf("claude"))

        val status = installer.status(ws)

        assertEquals(
            mapOf("CLAUDE" to listOf("billing", "shop"), "CURSOR" to listOf("shop")),
            status.mapValues { (_, links) ->
                links.map {
                    it.app
                }
            },
        )
        assertTrue(
            status.values.flatten().all { "/g/bin/gantry" in it.problem!! },
            "the fixture's launcher doesn't exist, so every link is stale",
        )
    }

    @Test
    fun `unknown agent ids are rejected`() {
        engine.create("shop", services = listOf("postgres"))

        assertFailsWith<GantryException> { installer.install("shop", ws, setOf("vim")) }
    }
}
