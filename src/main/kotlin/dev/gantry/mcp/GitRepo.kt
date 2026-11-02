package dev.gantry.mcp

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Precedes each exclude line Gantry adds, so it only ever removes its own. A comment of its own: in ignore files `#`
 * starts a comment only at the beginning of a line, so a trailing one would become part of the pattern.
 */
private const val EXCLUDE_MARKER = "# gantry mcp"

/**
 * The git repository agent config is installed into. Files Gantry writes there are hidden through the clone's own
 * `info/exclude`, never `.gitignore`: the config is personal to one machine and must not show up as a change.
 */
class GitRepo private constructor(
    val root: Path,
) {
    /** Whether [relative] is committed or staged, in which case Gantry must not edit it. */
    fun isTracked(relative: String): Boolean = git(root, "ls-files", "--error-unmatch", "--", relative) != null

    /** Whether git ignores [relative], through any ignore file. */
    fun isIgnored(relative: String): Boolean = git(root, "check-ignore", "-q", "--", relative) != null

    fun addExclude(relative: String) {
        if (isExcluded(relative)) return
        val file = excludeFile()
        Files.createDirectories(file.parent)
        val current = if (Files.exists(file)) Files.readString(file) else ""
        val separator = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
        Files.writeString(file, current + separator + EXCLUDE_MARKER + "\n" + relative + "\n")
    }

    fun removeExclude(relative: String) {
        val file = excludeFile()
        if (!Files.exists(file)) return
        val lines = Files.readAllLines(file)
        val ours =
            lines.indices
                .filter { i ->
                    lines[i] == relative && i > 0 && lines[i - 1] == EXCLUDE_MARKER
                }.flatMap { listOf(it - 1, it) }
        if (ours.isNotEmpty()) Files.writeString(file, lines.filterIndexed { i, _ -> i !in ours }.joinToString("") { "$it\n" })
    }

    fun isExcluded(relative: String): Boolean {
        val file = excludeFile()
        return Files.exists(file) && Files.readAllLines(file).any { it.trim() == relative }
    }

    /** `info/exclude` of this clone (resolved by git, so worktrees get theirs). */
    private fun excludeFile(): Path {
        val path = git(root, "rev-parse", "--git-path", "info/exclude")?.trim() ?: ".git/info/exclude"
        return root.resolve(path)
    }

    companion object {
        /** The repository containing [dir], or null outside one (or when git isn't installed). */
        fun find(dir: Path): GitRepo? = git(dir, "rev-parse", "--show-toplevel")?.trim()?.let { GitRepo(Path.of(it).toRealPath()) }

        /** Whether [dir] is inside a git repository, judged by its `.git` entries alone (for when git isn't installed). */
        fun looksLikeRepo(dir: Path): Boolean =
            generateSequence(dir.toAbsolutePath()) { it.parent }.any { Files.exists(it.resolve(".git")) }

        /** Output of `git -C [dir] args`, or null when git fails or isn't installed. */
        private fun git(
            dir: Path,
            vararg args: String,
        ): String? =
            try {
                val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() == 0) output else null
            } catch (_: IOException) {
                null
            }
    }
}
