package dev.gantry.mcp

import dev.gantry.mcp.agents.CodexAgent
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexAgentTest {
    private val root = tempRepo()
    private val ws = workspace(root)
    private val file = root.resolve(".codex/config.toml")
    private val agent = CodexAgent()

    @Test
    fun `creates the file and hides it from git`() {
        val outcome = agent.install(ws, entry("shop"))

        assertIs<Outcome.Installed>(outcome)
        assertTrue("trust" in outcome.note!!)
        val text = file.readText()
        assertTrue("[mcp_servers.gantry-shop]" in text)
        assertTrue("command = \"/g/bin/gantry\"" in text)
        assertTrue("args = [\"mcp\", \"serve\", \"shop\"]" in text)
        assertTrue("[mcp_servers.gantry-shop.env]\nJAVA_HOME = \"/jdk\"" in text)
        assertEquals("", git(root, "status", "--porcelain"))
        assertEquals(listOf("shop"), agent.connectedApps(ws))
    }

    @Test
    fun `other toml content is preserved byte for byte`() {
        val original = "model = \"o3\"\n\n[mcp_servers.other]\ncommand = \"x\"\n"
        Files.createDirectories(file.parent)
        file.writeText(original)
        root.resolve(".git/info/exclude").writeText(".codex/\n")

        agent.install(ws, entry("shop"))
        assertTrue(file.readText().startsWith(original))
        agent.uninstall(ws, "shop")

        assertEquals(original, file.readText())
    }

    @Test
    fun `a comment written after the block survives uninstall`() {
        agent.install(ws, entry("shop"))
        val withComment = file.readText() + "\n# my server\n[mcp_servers.other]\ncommand = \"x\"\n"
        file.writeText(withComment)

        agent.uninstall(ws, "shop")

        assertEquals("# my server\n[mcp_servers.other]\ncommand = \"x\"\n", file.readText())
    }

    @Test
    fun `an entry defined some other way is left to the developer`() {
        Files.createDirectories(file.parent)
        val inline = "[mcp_servers]\ngantry-shop = { command = \"x\" }\n"
        file.writeText(inline)

        assertIs<Outcome.Skipped>(agent.install(ws, entry("shop")))
        assertEquals(inline, file.readText())

        val quoted = "[mcp_servers.\"gantry-shop\"]\ncommand = \"x\"\n"
        file.writeText(quoted)
        assertIs<Outcome.Skipped>(agent.install(ws, entry("shop")))
        assertEquals(quoted, file.readText())
    }

    @Test
    fun `reinstall replaces its own block only`() {
        Files.createDirectories(file.parent)
        file.writeText("[mcp_servers.other]\ncommand = \"x\"\n")
        root.resolve(".git/info/exclude").writeText(".codex/\n")
        agent.install(ws, entry("shop"))
        agent.install(ws, entry("billing"))

        val second = agent.install(ws, entry("shop", command = "/new/bin/gantry"))

        assertTrue((second as Outcome.Installed).updated)
        val text = file.readText()
        assertEquals(1, Regex("""\[mcp_servers\.gantry-shop]""").findAll(text).count())
        assertTrue("command = \"/new/bin/gantry\"" in text)
        assertFalse("command = \"/g/bin/gantry\"\nargs = [\"mcp\", \"serve\", \"shop\"]" in text)
        assertEquals(listOf("billing", "shop"), agent.connectedApps(ws))
        assertTrue(text.startsWith("[mcp_servers.other]\ncommand = \"x\"\n"))
    }

    @Test
    fun `never writes the global config in the home directory`() {
        val home = Files.createTempDirectory("gantry-home").toRealPath()
        val homeAgent = CodexAgent(userHome = home)

        val outcome = homeAgent.install(Workspace(home, null), entry("shop"))

        assertIs<Outcome.Skipped>(outcome)
        assertTrue("global" in outcome.reason)
        assertFalse(home.resolve(".codex/config.toml").exists())
    }

    @Test
    fun `refuses a tracked file`() {
        Files.createDirectories(file.parent)
        file.writeText("model = \"o3\"\n")
        git(root, "add", ".codex/config.toml")
        git(root, "commit", "-qm", "team config")

        assertIs<Outcome.Skipped>(agent.install(ws, entry("shop")))
        assertEquals("model = \"o3\"\n", file.readText())
    }

    @Test
    fun `uninstall deletes only what it made`() {
        agent.install(ws, entry("shop"))
        agent.install(ws, entry("billing"))

        agent.uninstall(ws, "shop")
        assertEquals(listOf("billing"), agent.connectedApps(ws))

        agent.uninstall(ws, "billing")
        assertFalse(file.exists())
        assertFalse(file.parent.exists())
        assertFalse(GitRepo.find(root)!!.isExcluded(".codex/config.toml"))
    }

    @Test
    fun `windows paths are escaped`() {
        agent.install(ws, entry("shop", command = "C:\\g\\bin\\gantry.bat"))

        assertTrue("command = \"C:\\\\g\\\\bin\\\\gantry.bat\"" in file.readText())
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
