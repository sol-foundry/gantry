package dev.gantry.mcp.agents

import dev.gantry.engine.GantryException
import dev.gantry.mcp.RepoFileAgent
import dev.gantry.mcp.ServerEntry
import dev.gantry.mcp.entryName
import java.nio.file.Path
import kotlin.text.iterator

/**
 * Codex reads project servers from `.codex/config.toml`, for projects the developer has trusted. Gantry owns one
 * block per app (a marker comment, `[mcp_servers.gantry-<app>]` and its `.env` table) and edits it as text, so
 * everything else in the file stays exactly as written.
 */
class CodexAgent(
    userHome: Path = Path.of(System.getProperty("user.home")),
    codexHome: String? = System.getenv("CODEX_HOME"),
) : RepoFileAgent(".codex/config.toml") {
    override val name = "Codex"
    override val id = "codex"
    override val globalFile: Path =
        (
            codexHome
                ?.takeIf {
                    it.isNotBlank()
                }?.let { Path.of(it) } ?: userHome.resolve(".codex")
        ).resolve("config.toml")

    override val installNote =
        "Codex reads project config only for trusted projects; trust this project in Codex if it doesn't pick the server up."

    override fun withEntry(
        text: String?,
        entry: ServerEntry,
    ): Pair<String, Boolean> {
        val app = entry.name.removePrefix("gantry-")
        if (text != null && definedElsewhere(text, entry.name)) {
            throw GantryException("${entry.name} is already defined there in a form Gantry doesn't edit; change it by hand")
        }
        val rest = text?.let { withoutEntry(it, app) }
        val base = rest ?: text.orEmpty()
        val separator =
            when {
                base.isEmpty() -> ""
                base.endsWith("\n") -> "\n"
                else -> "\n\n"
            }
        return base + separator + block(app, entry) to (rest != null)
    }

    override fun withoutEntry(
        text: String,
        app: String,
    ): String? {
        val lines = text.split("\n")
        val header = lines.indexOfFirst { it.trim() == "[mcp_servers.${entryName(app)}]" }
        if (header < 0) return null
        val start = if (header > 0 && lines[header - 1].trim() == marker(app)) header - 1 else header
        val ownPrefix = "[mcp_servers.${entryName(app)}."
        var next = header + 1
        while (next < lines.size && !(lines[next].trimStart().startsWith("[") && !lines[next].trimStart().startsWith(ownPrefix))) next++
        // The block ends at its last header or setting; comments and blank lines after it belong to whatever follows.
        val end = (header until next).last { i -> lines[i].trimStart().let { it.startsWith("[") || KEY_LINE.containsMatchIn(it) } } + 1
        var before = lines.subList(0, start)
        var after = lines.subList(end, lines.size)
        // Drop the blank line install put in front of the block (or, at the top of the file, the one after it).
        if (before.lastOrNull()?.isBlank() == true) {
            before = before.dropLast(1)
        } else if (before.isEmpty()) {
            after = after.dropWhile { it.isBlank() }.ifEmpty { if (after.isEmpty()) after else listOf("") }
        }
        return (before + after).joinToString("\n")
    }

    override fun isEmpty(text: String): Boolean = text.isBlank()

    override fun parseEntries(text: String): Map<String, ServerEntry> {
        val lines = text.lines()
        return lines.indices
            .mapNotNull { i ->
                HEADER
                    .matchEntire(lines[i].trim())
                    ?.groupValues
                    ?.get(1)
                    ?.let { it to i }
            }.associate { (app, header) ->
                val values = mutableMapOf<String, String>()
                val env = linkedMapOf<String, String>()
                var args = emptyList<String>()
                var inEnv = false
                for (line in lines.drop(header + 1)) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("[")) {
                        if (trimmed == "[mcp_servers.${entryName(app)}.env]") inEnv = true else break
                        continue
                    }
                    val key = trimmed.substringBefore("=", "").trim().ifEmpty { continue }
                    val value = trimmed.substringAfter("=").trim()
                    when {
                        inEnv -> env[key] = tomlStrings(value).firstOrNull() ?: continue
                        key == "args" -> args = tomlStrings(value)
                        else -> tomlStrings(value).firstOrNull()?.let { values[key] = it }
                    }
                }
                app to ServerEntry(entryName(app), values["command"].orEmpty(), args, env)
            }
    }

    /** `name` as an inline table, a quoted table header or a dotted key: forms only the developer should edit. */
    private fun definedElsewhere(
        text: String,
        name: String,
    ): Boolean {
        val n = Regex.escape(name)
        val forms =
            listOf(
                Regex("""^\[\s*mcp_servers\s*\.\s*["']$n["']\s*[.\]]"""),
                Regex("""^["']?$n["']?\s*="""),
                Regex("""^mcp_servers\s*\.\s*["']?$n["']?\s*[.=]"""),
            )
        return text.lines().any { line -> forms.any { it.containsMatchIn(line.trim()) } }
    }

    private fun marker(app: String) = "# gantry mcp: $app"

    private fun block(
        app: String,
        entry: ServerEntry,
    ): String =
        buildString {
            appendLine(marker(app))
            appendLine("[mcp_servers.${entry.name}]")
            appendLine("command = ${tomlString(entry.command)}")
            appendLine("args = [${entry.args.joinToString(", ") { tomlString(it) }}]")
            if (entry.env.isNotEmpty()) {
                appendLine()
                appendLine("[mcp_servers.${entry.name}.env]")
                entry.env.forEach { (key, value) -> appendLine("$key = ${tomlString(value)}") }
            }
        }

    private companion object {
        val KEY_LINE = Regex("""^["']?[A-Za-z0-9_.-]+["']?\s*=""")
        val TOML_STRING = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

        /** The basic strings in [value], unescaped: one for `"x"`, several for an array. */
        fun tomlStrings(value: String): List<String> =
            TOML_STRING
                .findAll(value)
                .map { match ->
                    buildString {
                        var escaped = false
                        for (c in match.groupValues[1]) {
                            if (escaped) {
                                append(
                                    when (c) {
                                        'n' -> '\n'
                                        't' -> '\t'
                                        else -> c
                                    },
                                )
                                escaped = false
                            } else if (c == '\\') {
                                escaped = true
                            } else {
                                append(c)
                            }
                        }
                    }
                }.toList()

        val HEADER = Regex("""^\[mcp_servers\.gantry-([A-Za-z0-9_-]+)]$""")

        /** A TOML basic string: backslashes and quotes escaped (Windows paths), control characters as escapes. */
        fun tomlString(value: String): String =
            "\"" +
                value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\t", "\\t") + "\""
    }
}
