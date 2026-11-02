package dev.gantry.mcp

import dev.gantry.engine.GantryException
import java.nio.file.Files
import java.nio.file.Path

/** Where agent config is installed: the repository root (or the directory given), and its git repo if there is one. */
data class Workspace(
    val dir: Path,
    val repo: GitRepo?,
    /** Inside a git repository, but git isn't available to hide files from it. */
    val gitMissing: Boolean = false,
)

/** What install or uninstall did for one agent. */
sealed interface Outcome {
    val agent: String

    data class Installed(
        override val agent: String,
        val where: String,
        val updated: Boolean,
        val note: String? = null,
    ) : Outcome

    data class Removed(
        override val agent: String,
        val where: String,
    ) : Outcome

    data class Skipped(
        override val agent: String,
        val reason: String,
    ) : Outcome
}

/** A coding agent Gantry can connect to an app, through that agent's most local MCP configuration. */
interface Agent {
    /** Shown to the developer, e.g. "Claude Code". */
    val name: String

    /** Short id for `--agent`, e.g. "claude". */
    val id: String

    fun install(
        ws: Workspace,
        entry: ServerEntry,
    ): Outcome

    /** Removes the `gantry-<app>` entry; [Outcome.Skipped] when there is none. */
    fun uninstall(
        ws: Workspace,
        app: String,
    ): Outcome

    /** App -> its `gantry-<app>` entry in this workspace, as written. */
    fun entries(ws: Workspace): Map<String, ServerEntry>

    /** Apps with a `gantry-*` entry in this workspace. */
    fun connectedApps(ws: Workspace): List<String> = entries(ws).keys.sorted()
}

const val NOT_IN_REPO_NOTE = "not a git repository; the file is not hidden from version control"

/**
 * An agent configured by a file inside the repository. The file is personal, so it is hidden through the clone's
 * `info/exclude`; a file the team has committed is never edited. Subclasses only edit the file's text.
 */
abstract class RepoFileAgent(
    /** Path of the config file relative to the workspace, with `/` separators. */
    private val relative: String,
) : Agent {
    /** [text] (null when the file doesn't exist) with [entry] added or replaced, and whether it was there before. */
    protected abstract fun withEntry(
        text: String?,
        entry: ServerEntry,
    ): Pair<String, Boolean>

    /** [text] without the entry for [app], or null when it has none. */
    protected abstract fun withoutEntry(
        text: String,
        app: String,
    ): String?

    /** Whether [text] holds nothing worth keeping, so the file can be deleted. */
    protected abstract fun isEmpty(text: String): Boolean

    /** App -> entry for every `gantry-*` entry in [text]. */
    protected abstract fun parseEntries(text: String): Map<String, ServerEntry>

    /**
     * The agent's global config file. Run from the home directory, the project file would be this one and connect
     * every project to the app, so install refuses.
     */
    protected abstract val globalFile: Path

    private fun globalReason(ws: Workspace): String? =
        if (file(ws).toAbsolutePath().normalize() == globalFile.toAbsolutePath().normalize()) {
            "$globalFile is $name's global config, which would connect every project to this app; run install inside a project or pass --dir"
        } else {
            null
        }

    /** Shown after a successful install, if the agent needs something more from the developer. */
    protected open val installNote: String? = null

    private fun file(ws: Workspace) = ws.dir.resolve(relative)

    /** The file's path as git sees it: relative to the repository root, which [Workspace.dir] may be below. */
    private fun gitPath(
        ws: Workspace,
        repo: GitRepo,
    ): String = repo.root.relativize(file(ws).toAbsolutePath()).joinToString("/")

    override fun install(
        ws: Workspace,
        entry: ServerEntry,
    ): Outcome {
        globalReason(ws)?.let { return Outcome.Skipped(name, it) }
        if (ws.repo == null && ws.gitMissing) {
            return Outcome.Skipped(name, "this is a git repository but git isn't on PATH, so $relative couldn't be hidden from it")
        }
        if (ws.repo?.let { it.isTracked(gitPath(ws, it)) } == true) {
            return Outcome.Skipped(name, "$relative is tracked by git; add the entry by hand or untrack it")
        }
        val file = file(ws)
        // A file that already shows in `git status` may be on its way into the repo: don't hide it, don't add to it.
        if (Files.exists(file) && ws.repo?.let { !it.isIgnored(gitPath(ws, it)) } == true) {
            return Outcome.Skipped(
                name,
                "$relative already exists and shows in git status, so it may be meant for the team; " +
                    "add the entry by hand, or hide the file (.git/info/exclude) and run this again",
            )
        }
        val current = if (Files.exists(file)) Files.readString(file) else null
        val (text, existed) =
            try {
                withEntry(current, entry)
            } catch (e: GantryException) {
                return Outcome.Skipped(name, "can't read $relative: ${e.message}")
            }
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        ws.repo?.let { it.addExclude(gitPath(ws, it)) }
        val notes = listOfNotNull(installNote, NOT_IN_REPO_NOTE.takeIf { ws.repo == null })
        return Outcome.Installed(name, relative, existed, notes.joinToString("; ").ifEmpty { null })
    }

    override fun uninstall(
        ws: Workspace,
        app: String,
    ): Outcome {
        val file = file(ws)
        if (!Files.exists(file)) return Outcome.Skipped(name, "not installed")
        globalReason(ws)?.let { return Outcome.Skipped(name, it) }
        if (ws.repo?.let { it.isTracked(gitPath(ws, it)) } ==
            true
        ) {
            return Outcome.Skipped(name, "$relative is tracked by git; remove the entry by hand")
        }
        val text =
            try {
                withoutEntry(Files.readString(file), app)
            } catch (e: GantryException) {
                return Outcome.Skipped(name, "can't read $relative: ${e.message}")
            } ?: return Outcome.Skipped(name, "not installed")
        if (isEmpty(text)) {
            Files.delete(file)
            Files.list(file.parent).use { if (it.findFirst().isEmpty) Files.delete(file.parent) }
            ws.repo?.let { it.removeExclude(gitPath(ws, it)) }
        } else {
            Files.writeString(file, text)
        }
        return Outcome.Removed(name, relative)
    }

    override fun entries(ws: Workspace): Map<String, ServerEntry> {
        val file = file(ws)
        if (!Files.exists(file)) return emptyMap()
        return runCatching { parseEntries(Files.readString(file)) }.getOrDefault(emptyMap())
    }
}
