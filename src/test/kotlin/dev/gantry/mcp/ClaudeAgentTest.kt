package dev.gantry.mcp

import dev.gantry.mcp.agents.ClaudeAgent
import dev.gantry.mcp.agents.CommandResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClaudeAgentTest {
    private val home = Files.createTempDirectory("gantry-home")
    private val dir = Files.createTempDirectory("gantry-project").toRealPath()
    private val ws = Workspace(dir, null)
    private val claudeJson = home.resolve(".claude.json")
    private val calls = mutableListOf<Pair<List<String>, Path>>()

    private fun cliAgent(exit: (List<String>) -> Int = { 0 }) =
        ClaudeAgent(
            userHome = home,
            claudeCli = "/bin/claude",
            runner = { command, workDir ->
                calls += command to workDir
                CommandResult(exit(command), if (exit(command) == 0) "" else "boom")
            },
            windows = false,
        )

    private val fileAgent = ClaudeAgent(userHome = home, claudeCli = null, windows = false)

    /** ~/.claude.json with [command] registered as gantry-shop for this project, as a previous install left it. */
    private fun registered(command: String) =
        claudeJson.writeText(
            """{"projects":{"$dir":{"mcpServers":{"gantry-shop":{"type":"stdio","command":"$command","args":["mcp","serve","shop"],"env":{"JAVA_HOME":"/old-jdk"}}}}}}""",
        )

    @Test
    fun `uses the claude cli in local scope`() {
        val outcome = cliAgent().install(ws, entry("shop"))

        assertIs<Outcome.Installed>(outcome)
        assertEquals(listOf("/bin/claude", "mcp", "remove", "gantry-shop", "--scope", "local"), calls[0].first)
        // Plain arguments, no JSON: quotes inside an argument don't survive cmd.exe running claude.cmd on Windows.
        assertEquals(
            listOf(
                "/bin/claude",
                "mcp",
                "add",
                "--scope",
                "local",
                "--transport",
                "stdio",
                "gantry-shop",
                "-e",
                "JAVA_HOME=/jdk",
                "--",
                "/g/bin/gantry",
                "mcp",
                "serve",
                "shop",
            ),
            calls[1].first,
        )
        assertTrue(calls.all { it.second == dir }, "local scope is keyed on the directory claude runs in")
        assertTrue("restart Claude Code" in outcome.note!!)
    }

    @Test
    fun `cli failure is reported, not hidden`() {
        val outcome = cliAgent(exit = { if ("add" in it) 1 else 0 }).install(ws, entry("shop"))

        assertIs<Outcome.Skipped>(outcome)
        assertTrue("boom" in outcome.reason)
    }

    @Test
    fun `a failed reinstall puts the previous entry back`() {
        registered("/old/bin/gantry")

        val outcome = cliAgent(exit = { if ("add" in it && "/g/bin/gantry" in it) 1 else 0 }).install(ws, entry("shop"))

        assertIs<Outcome.Skipped>(outcome)
        assertTrue("restored the previous entry" in outcome.reason)
        val restore = calls.last().first
        assertEquals(listOf("--", "/old/bin/gantry", "mcp", "serve", "shop"), restore.takeLast(5))
        assertTrue("JAVA_HOME=/old-jdk" in restore)
    }

    @Test
    fun `cli uninstall that finds nothing is skipped`() {
        assertIs<Outcome.Skipped>(cliAgent(exit = { 1 }).uninstall(ws, "shop"))
    }

    @Test
    fun `cli uninstall errors are passed on`() {
        registered("/g/bin/gantry")

        val outcome = cliAgent(exit = { 1 }).uninstall(ws, "shop")

        assertIs<Outcome.Skipped>(outcome)
        assertTrue("boom" in outcome.reason)
    }

    @Test
    fun `without the cli on windows it does not guess the config format`() {
        val outcome = ClaudeAgent(userHome = home, claudeCli = null, windows = true).install(ws, entry("shop"))

        assertIs<Outcome.Skipped>(outcome)
        assertTrue("claude" in outcome.reason)
        assertFalse(claudeJson.exists())
    }

    @Test
    fun `falls back to editing claude json`() {
        claudeJson.writeText(
            """{"numStartups":3,"projects":{"/elsewhere":{"mcpServers":{"x":{"command":"y"}}},"$dir":{"allowedTools":["Bash"]}}}""",
        )

        val outcome = fileAgent.install(ws, entry("shop"))

        assertIs<Outcome.Installed>(outcome)
        assertTrue("restart" in outcome.note!!)
        val root = Json.parseToJsonElement(claudeJson.readText()).jsonObject
        assertEquals("3", root.getValue("numStartups").jsonPrimitive.content)
        val projects = root.getValue("projects").jsonObject
        assertTrue(
            "x" in
                projects
                    .getValue("/elsewhere")
                    .jsonObject
                    .getValue("mcpServers")
                    .jsonObject,
        )
        val project = projects.getValue(dir.toString()).jsonObject
        assertTrue("allowedTools" in project)
        assertEquals(
            "/g/bin/gantry",
            project
                .getValue("mcpServers")
                .jsonObject
                .getValue("gantry-shop")
                .jsonObject
                .getValue("command")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `never writes mcp json`() {
        cliAgent().install(ws, entry("shop"))
        fileAgent.install(ws, entry("shop"))

        assertFalse(dir.resolve(".mcp.json").exists())
    }

    @Test
    fun `lists and removes connected apps`() {
        fileAgent.install(ws, entry("shop"))
        fileAgent.install(ws, entry("billing"))
        assertEquals(listOf("billing", "shop"), fileAgent.connectedApps(ws))

        assertIs<Outcome.Removed>(fileAgent.uninstall(ws, "shop"))
        assertEquals(listOf("billing"), fileAgent.connectedApps(ws))
        assertIs<Outcome.Skipped>(fileAgent.uninstall(ws, "shop"))
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
        fileAgent.install(ws, original)

        assertEquals(mapOf("shop" to original), fileAgent.entries(ws))
    }
}
