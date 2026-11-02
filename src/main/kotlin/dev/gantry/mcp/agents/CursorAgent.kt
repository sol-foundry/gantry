package dev.gantry.mcp.agents

import dev.gantry.engine.GantryException
import dev.gantry.mcp.RepoFileAgent
import dev.gantry.mcp.ServerEntry
import dev.gantry.mcp.entryName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import kotlin.collections.minus

private const val SERVERS = "mcpServers"

/** Formats JSON with [indent], so a rewritten file keeps the indentation it had. */
private fun prettyJson(indent: String) =
    Json {
        prettyPrint = true
        prettyPrintIndent = indent
    }

/** The indentation [text] uses (two spaces when it has none to copy). */
private fun indentOf(text: String?): String = text?.let { Regex("""\n([ \t]+)\S""").find(it)?.groupValues?.get(1) } ?: "  "

/** Cursor reads project servers from `.cursor/mcp.json`; its global file would connect every project, so it's not used. */
class CursorAgent(
    userHome: Path = Path.of(System.getProperty("user.home")),
) : RepoFileAgent(".cursor/mcp.json") {
    override val name = "Cursor"
    override val id = "cursor"
    override val globalFile: Path = userHome.resolve(".cursor/mcp.json")

    override fun withEntry(
        text: String?,
        entry: ServerEntry,
    ): Pair<String, Boolean> {
        val root = text?.let(::parse) ?: JsonObject(emptyMap())
        val servers = root[SERVERS] as? JsonObject ?: JsonObject(emptyMap())
        val updated = JsonObject(root + (SERVERS to JsonObject(servers + (entry.name to stdioServer(entry)))))
        return prettyJson(indentOf(text)).encodeToString(JsonObject.serializer(), updated) + "\n" to (entry.name in servers)
    }

    override fun withoutEntry(
        text: String,
        app: String,
    ): String? {
        val root = parse(text)
        val servers = root[SERVERS] as? JsonObject ?: return null
        if (entryName(app) !in servers) return null
        return prettyJson(indentOf(text)).encodeToString(
            JsonObject.serializer(),
            JsonObject(
                root + (SERVERS to JsonObject(servers - entryName(app))),
            ),
        ) +
            "\n"
    }

    override fun isEmpty(text: String): Boolean {
        val root = parse(text)
        return root.keys.all { it == SERVERS } && (root[SERVERS] as? JsonObject).isNullOrEmpty()
    }

    override fun parseEntries(text: String): Map<String, ServerEntry> = gantryEntries(parse(text)[SERVERS] as? JsonObject)

    private fun parse(text: String): JsonObject =
        try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            throw GantryException("not valid JSON (${e.message?.lineSequence()?.first()})")
        } catch (_: IllegalArgumentException) {
            throw GantryException("not a JSON object")
        }
}

/** The JSON form of an entry shared by Cursor's file and Claude Code's local scope. */
fun stdioServer(entry: ServerEntry): JsonObject =
    JsonObject(
        mapOf(
            "type" to JsonPrimitive("stdio"),
            "command" to JsonPrimitive(entry.command),
            "args" to JsonArray(entry.args.map(::JsonPrimitive)),
            "env" to JsonObject(entry.env.mapValues { JsonPrimitive(it.value) }),
        ),
    )

/** App -> entry for the `gantry-*` servers in an `mcpServers` object (Cursor's file, Claude Code's local scope). */
fun gantryEntries(servers: JsonObject?): Map<String, ServerEntry> =
    servers
        .orEmpty()
        .filterKeys { it.startsWith("gantry-") }
        .mapNotNull { (name, server) ->
            (server as? JsonObject)?.let { serverEntryFrom(name, it) }?.let { name.removePrefix("gantry-") to it }
        }.toMap()

/** An entry from its JSON form, or null when it isn't a stdio server with a command. */
fun serverEntryFrom(
    name: String,
    server: JsonObject,
): ServerEntry? {
    val command = (server["command"] as? JsonPrimitive)?.contentOrNull ?: return null
    val args = (server["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    val env =
        (server["env"] as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }
            ?.toMap()
            .orEmpty()
    return ServerEntry(name, command, args, env)
}
