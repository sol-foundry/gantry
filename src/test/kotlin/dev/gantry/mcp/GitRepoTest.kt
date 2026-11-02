package dev.gantry.mcp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs git in [dir] for test setup, failing the test on a non-zero exit. */
fun git(
    dir: Path,
    vararg args: String,
): String {
    val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "git ${args.joinToString(" ")}: $output" }
    return output
}

/** A fresh repository with an identity, so commits work on machines without global git config. */
fun tempRepo(): Path =
    Files.createTempDirectory("gantry-repo").toRealPath().also {
        git(it, "init", "-q")
        git(it, "config", "user.email", "test@example.com")
        git(it, "config", "user.name", "Test")
    }

class GitRepoTest {
    private val root = tempRepo()

    @Test
    fun `finds the top level from a subdirectory`() {
        val sub = Files.createDirectories(root.resolve("services/shop"))

        assertEquals(root, GitRepo.find(sub)?.root)
    }

    @Test
    fun `no repo outside git`() {
        assertNull(GitRepo.find(Files.createTempDirectory("gantry-plain")))
    }

    @Test
    fun `exclude lines are added once and removed only when ours`() {
        val repo = assertNotNull(GitRepo.find(root))
        val exclude = root.resolve(".git/info/exclude")
        exclude.writeText("secret.txt\n")

        repo.addExclude(".cursor/mcp.json")
        repo.addExclude(".cursor/mcp.json")

        assertEquals("secret.txt\n# gantry mcp\n.cursor/mcp.json\n", exclude.readText())
        assertTrue(repo.isExcluded(".cursor/mcp.json"))

        repo.removeExclude(".cursor/mcp.json")

        assertEquals("secret.txt\n", exclude.readText())
        assertFalse(repo.isExcluded(".cursor/mcp.json"))
    }

    @Test
    fun `a pattern the user wrote is never removed`() {
        val repo = assertNotNull(GitRepo.find(root))
        val exclude = root.resolve(".git/info/exclude")
        exclude.writeText(".cursor/mcp.json\n")

        repo.addExclude(".cursor/mcp.json")
        repo.removeExclude(".cursor/mcp.json")

        assertEquals(".cursor/mcp.json\n", exclude.readText())
    }

    @Test
    fun `an excluded file does not show in git status`() {
        val repo = assertNotNull(GitRepo.find(root))
        Files.createDirectories(root.resolve(".cursor")).resolve("mcp.json").writeText("{}")

        repo.addExclude(".cursor/mcp.json")

        assertEquals("", git(root, "status", "--porcelain"))
    }

    @Test
    fun `tracked files are detected`() {
        val repo = assertNotNull(GitRepo.find(root))
        root.resolve("tracked.txt").writeText("x")
        git(root, "add", "tracked.txt")
        git(root, "commit", "-qm", "init")
        root.resolve("loose.txt").writeText("y")

        assertTrue(repo.isTracked("tracked.txt"))
        assertFalse(repo.isTracked("loose.txt"))
    }
}
