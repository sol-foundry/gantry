package dev.gantry.mcp.agents

import dev.gantry.engine.GantryException
import dev.gantry.mcp.Agent
import dev.gantry.mcp.Outcome
import dev.gantry.mcp.ServerEntry
import dev.gantry.mcp.Workspace
import dev.gantry.mcp.entryName
import dev.gantry.mcp.findOnPath
import dev.gantry.mcp.isWindows
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.collections.minus

data class CommandResult(
    val exit: Int,
    val output: String,
)

/** Runs an external command in a directory; injectable so tests never call the real `claude`. */
fun interface CommandRunner {
    fun run(
        command: List<String>,
        dir: Path,
    ): CommandResult
}

object ProcessRunner : CommandRunner {
    override fun run(
        command: List<String>,
        dir: Path,
    ): CommandResult =
        try {
            val process = ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            CommandResult(process.waitFor(), output.trim())
        } catch (e: IOException) {
            CommandResult(-1, e.message ?: "couldn't run ${command.first()}")
        }
}

private const val RESTART_NOTE = "restart Claude Code in this project to pick it up"
private const val DIRECT_EDIT_NOTE =
    "edited ~/.claude.json directly (the claude command isn't on PATH); restart any running Claude Code session so it doesn't overwrite the change"

private val claudeJsonFormat =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

/**
 * Claude Code's local scope: private to this developer and this project, stored in `~/.claude.json` under the
 * project's path, never in the repo (`.mcp.json` is the team's shared file). The `claude` CLI is preferred because a
 * running session rewrites `~/.claude.json` from memory and could undo a direct edit.
 */
class ClaudeAgent(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
    private val claudeCli: String? = findOnPath("claude")?.toString(),
    private val runner: CommandRunner = ProcessRunner,
    private val windows: Boolean = isWindows(),
) : Agent {
    override val name = "Claude Code"
    override val id = "claude"

    private val claudeJson get() = userHome.resolve(".claude.json")

    override fun install(
        ws: Workspace,
        entry: ServerEntry,
    ): Outcome {
        val previous = runCatching { servers(read(), ws)[entry.name] as? JsonObject }.getOrNull()
        val where = "local scope for ${ws.dir}"
        if (claudeCli != null) {
            runner.run(listOf(claudeCli, "mcp", "remove", entry.name, "--scope", "local"), ws.dir)
            val added = runner.run(addCommand(claudeCli, entry), ws.dir)
            if (added.exit == 0) return Outcome.Installed(name, where, previous != null, RESTART_NOTE)
            // Don't leave the developer with nothing: put back what worked before.
            val restored =
                previous?.let { serverEntryFrom(entry.name, it) }?.let { runner.run(addCommand(claudeCli, it), ws.dir).exit == 0 } == true
            val tail = if (restored) "; restored the previous entry" else ""
            return Outcome.Skipped(name, "claude mcp add failed: ${added.output}$tail")
        }
        if (windows) {
            return Outcome.Skipped(name, "the claude command isn't on PATH; install the Claude Code CLI and run this again")
        }
        return try {
            editServers(ws) { it + (entry.name to stdioServer(entry)) }
            Outcome.Installed(name, where, previous != null, "$RESTART_NOTE; $DIRECT_EDIT_NOTE")
        } catch (e: GantryException) {
            Outcome.Skipped(name, e.message ?: "can't edit ~/.claude.json")
        }
    }

    override fun uninstall(
        ws: Workspace,
        app: String,
    ): Outcome {
        val where = "local scope for ${ws.dir}"
        if (app !in connectedApps(ws)) return Outcome.Skipped(name, "not installed")
        if (claudeCli != null) {
            val removed = runner.run(listOf(claudeCli, "mcp", "remove", entryName(app), "--scope", "local"), ws.dir)
            return if (removed.exit ==
                0
            ) {
                Outcome.Removed(name, where)
            } else {
                Outcome.Skipped(name, "claude mcp remove failed: ${removed.output}")
            }
        }
        if (windows) return Outcome.Skipped(name, "the claude command isn't on PATH; remove ${entryName(app)} with it")
        return try {
            editServers(ws) { it - entryName(app) }
            Outcome.Removed(name, where)
        } catch (e: GantryException) {
            Outcome.Skipped(name, e.message ?: "can't edit ~/.claude.json")
        }
    }

    /**
     * `claude mcp add` with plain arguments rather than `add-json`: on Windows `claude` is a `.cmd` script run by
     * cmd.exe, which mangles the quotes inside a JSON argument.
     */
    private fun addCommand(
        cli: String,
        entry: ServerEntry,
    ): List<String> =
        listOf(cli, "mcp", "add", "--scope", "local", "--transport", "stdio", entry.name) +
            entry.env.flatMap { (key, value) -> listOf("-e", "$key=$value") } +
            listOf("--", entry.command) + entry.args

    override fun entries(ws: Workspace): Map<String, ServerEntry> =
        runCatching { gantryEntries(servers(read(), ws)) }.getOrDefault(emptyMap())

    private fun read(): JsonObject {
        if (!Files.exists(claudeJson)) return JsonObject(emptyMap())
        return try {
            Json.parseToJsonElement(Files.readString(claudeJson)).jsonObject
        } catch (_: SerializationException) {
            throw GantryException("~/.claude.json is not valid JSON; left it alone")
        } catch (_: IllegalArgumentException) {
            throw GantryException("~/.claude.json is not a JSON object; left it alone")
        }
    }

    private fun servers(
        root: JsonObject,
        ws: Workspace,
    ): JsonObject =
        ((root["projects"] as? JsonObject)?.get(ws.dir.toString()) as? JsonObject)?.get("mcpServers") as? JsonObject
            ?: JsonObject(emptyMap())

    /** Rewrites this project's `mcpServers`, keeping every other key; written to a temp file and moved into place. */
    private fun editServers(
        ws: Workspace,
        change: (Map<String, JsonElement>) -> Map<String, JsonElement>,
    ) {
        val root = read()
        val projects = root["projects"] as? JsonObject ?: JsonObject(emptyMap())
        val project = projects[ws.dir.toString()] as? JsonObject ?: JsonObject(emptyMap())
        val updated =
            JsonObject(
                root +
                    (
                        "projects" to
                            JsonObject(
                                projects +
                                    (ws.dir.toString() to JsonObject(project + ("mcpServers" to JsonObject(change(servers(root, ws)))))),
                            )
                    ),
            )
        val temp = Files.createTempFile(userHome, ".claude.json", ".tmp")
        Files.writeString(temp, claudeJsonFormat.encodeToString(JsonObject.serializer(), updated) + "\n")
        Files.move(temp, claudeJson, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
