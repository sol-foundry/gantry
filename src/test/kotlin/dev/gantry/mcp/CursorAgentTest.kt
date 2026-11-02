package dev.gantry.mcp

import dev.gantry.mcp.agents.CursorAgent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CursorAgentTest {
    private val root = tempRepo()
    private val ws = workspace(root)
    private val file = root.resolve(".cursor/mcp.json")
    private val agent = CursorAgent()

    private fun servers() =
        Json
            .parseToJsonElement(file.readText())
            .jsonObject
            .getValue("mcpServers")
            .jsonObject

    @Test
    fun `creates the file and hides it from git`() {
        assertIs<Outcome.Installed>(agent.install(ws, entry("shop")))

        val server = servers().getValue("gantry-shop").jsonObject
        assertEquals("/g/bin/gantry", server.getValue("command").jsonPrimitive.content)
        assertEquals("stdio", server.getValue("type").jsonPrimitive.content)
        assertEquals("", git(root, "status", "--porcelain"))
        assertEquals(listOf("shop"), agent.connectedApps(ws))
    }

    @Test
    fun `keeps other servers and replaces its own entry`() {
        Files.createDirectories(file.parent)
        file.writeText("""{"mcpServers":{"other":{"command":"x"}},"theme":"dark"}""")
        root.resolve(".git/info/exclude").writeText(".cursor/\n")

        agent.install(ws, entry("shop"))
        val second = agent.install(ws, entry("shop", command = "/new/bin/gantry"))

        assertTrue((second as Outcome.Installed).updated)
        assertEquals(
            "x",
            servers()
                .getValue("other")
                .jsonObject
                .getValue("command")
                .jsonPrimitive.content,
        )
        assertEquals(
            "/new/bin/gantry",
            servers()
                .getValue("gantry-shop")
                .jsonObject
                .getValue("command")
                .jsonPrimitive.content,
        )
        assertEquals(
            "dark",
            Json
                .parseToJsonElement(file.readText())
                .jsonObject
                .getValue("theme")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `never writes the global config in the home directory`() {
        val home = Files.createTempDirectory("gantry-home").toRealPath()
        val homeAgent = CursorAgent(userHome = home)

        val outcome = homeAgent.install(Workspace(home, null), entry("shop"))

        assertIs<Outcome.Skipped>(outcome)
        assertTrue("global" in outcome.reason)
        assertFalse(home.resolve(".cursor/mcp.json").exists())
    }

    @Test
    fun `keeps the file's indentation`() {
        Files.createDirectories(file.parent)
        file.writeText("{\n    \"mcpServers\": {\n        \"other\": {\n            \"command\": \"x\"\n        }\n    }\n}\n")
        root.resolve(".git/info/exclude").writeText(".cursor/\n")

        agent.install(ws, entry("shop"))
        assertTrue(file.readText().startsWith("{\n    \"mcpServers\": {\n        \"other\""))
        agent.uninstall(ws, "shop")

        assertEquals(
            "{\n    \"mcpServers\": {\n        \"other\": {\n            \"command\": \"x\"\n        }\n    }\n}\n",
            file.readText(),
        )
    }

    @Test
    fun `a config file already visible in git status is left alone`() {
        Files.createDirectories(file.parent)
        file.writeText("""{"mcpServers":{"team":{"command":"x"}}}""")

        val outcome = agent.install(ws, entry("shop"))

        assertIs<Outcome.Skipped>(outcome)
        assertTrue("git status" in outcome.reason)
        assertEquals("""{"mcpServers":{"team":{"command":"x"}}}""", file.readText())
    }

    @Test
    fun `without git it does not write a file it cannot hide`() {
        val outcome = agent.install(Workspace(root, null, gitMissing = true), entry("shop"))

        assertIs<Outcome.Skipped>(outcome)
        assertFalse(file.exists())
    }

    @Test
    fun `refuses a tracked file`() {
        Files.createDirectories(file.parent)
        file.writeText("""{"mcpServers":{}}""")
        git(root, "add", ".cursor/mcp.json")
        git(root, "commit", "-qm", "team config")

        assertIs<Outcome.Skipped>(agent.install(ws, entry("shop")))
        assertEquals("""{"mcpServers":{}}""", file.readText())
    }

    @Test
    fun `refuses a file it cannot parse`() {
        Files.createDirectories(file.parent)
        file.writeText("{ not json")

        assertIs<Outcome.Skipped>(agent.install(ws, entry("shop")))
        assertEquals("{ not json", file.readText())
    }

    @Test
    fun `uninstall deletes only what it made`() {
        agent.install(ws, entry("shop"))
        agent.install(ws, entry("billing"))

        assertIs<Outcome.Removed>(agent.uninstall(ws, "shop"))
        assertEquals(listOf("billing"), agent.connectedApps(ws))
        assertEquals("", git(root, "status", "--porcelain"))

        agent.uninstall(ws, "billing")
        assertFalse(file.exists())
        assertFalse(file.parent.exists())
        assertFalse(GitRepo.find(root)!!.isExcluded(".cursor/mcp.json"))
    }

    @Test
    fun `uninstall keeps a file with other servers`() {
        Files.createDirectories(file.parent)
        file.writeText("""{"mcpServers":{"other":{"command":"x"}}}""")
        root.resolve(".git/info/exclude").writeText(".cursor/\n")
        agent.install(ws, entry("shop"))

        agent.uninstall(ws, "shop")

        assertEquals(setOf("other"), servers().keys)
        assertEquals("", git(root, "status", "--porcelain"))
    }

    @Test
    fun `a workspace below the repo root is hidden by its full path`() {
        val sub = Files.createDirectories(root.resolve("services/shop"))
        val subWs = Workspace(sub, GitRepo.find(sub))

        agent.install(subWs, entry("shop"))

        assertTrue(sub.resolve(".cursor/mcp.json").exists())
        assertEquals("", git(root, "status", "--porcelain"))
        agent.uninstall(subWs, "shop")
        assertFalse(GitRepo.find(root)!!.isExcluded("services/shop/.cursor/mcp.json"))
    }

    @Test
    fun `uninstall of an app that is not there is skipped`() {
        assertIs<Outcome.Skipped>(agent.uninstall(ws, "shop"))
    }

    @Test
    fun `entries read back what install wrote`() {
        val original =
            ServerEntry(
                "gantry-shop",
                "C:\\g\\bin \"x\"\\gantry",
                listOf("mcp", "serve", "shop"),
                mapOf(
                    "JAVA_HOME" to "/jdk",
                    "GANTRY_DOCKER" to "/d",
                ),
            )
        agent.install(ws, original)

        assertEquals(mapOf("shop" to original), agent.entries(ws))
    }
}
