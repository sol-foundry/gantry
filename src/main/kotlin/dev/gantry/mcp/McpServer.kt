package dev.gantry.mcp

import dev.gantry.dblog.QueryEntry
import dev.gantry.dblog.parseQueryTime
import dev.gantry.dblog.queryLog
import dev.gantry.dblog.queryLoggedDatabases
import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.gateway.Call
import dev.gantry.gateway.GATEWAY
import dev.gantry.gateway.callLog
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.Writer

/** Protocol revisions this server speaks, newest first; a client asking for another gets the newest. */
private val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

private const val TOOL = "get_connection_details"
private const val CALLS_TOOL = "get_gateway_calls"
private const val CALLS_DEFAULT = 20
private const val CALLS_MAX = 100
private const val BODY_BYTES = 4096
private const val QUERIES_TOOL = "get_db_queries"
private const val QUERY_ROWS = 20
private const val QUERY_VALUE_CHARS = 200

private const val PARSE_ERROR = -32700
private const val INVALID_REQUEST = -32600
private const val METHOD_NOT_FOUND = -32601
private const val INVALID_PARAMS = -32602
private const val INTERNAL_ERROR = -32603

/**
 * A Model Context Protocol server over stdio for one app: newline-delimited JSON-RPC in, one JSON line per reply out.
 * Its read-only tools return the app's connection details and, where the app has them, its gateway calls and database
 * queries; agents use their own tools from there.
 * Only replies go to [output], so nothing else may write to stdout while it runs.
 */
class McpServer(
    private val engine: Engine,
    private val app: String,
    private val version: String,
) {
    fun serve(
        input: BufferedReader,
        output: Writer,
    ) {
        input.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            handle(line)?.let {
                output.write(it.toString())
                output.write("\n")
                output.flush()
            }
        }
    }

    /** The reply to one message, or null for notifications. Never throws: a bad message must not end the session. */
    private fun handle(line: String): JsonObject? {
        val element =
            try {
                Json.parseToJsonElement(line)
            } catch (_: IllegalArgumentException) {
                return error(JsonNull, PARSE_ERROR, "Parse error")
            }
        // Batches and bare values aren't requests this server takes.
        val message = element as? JsonObject ?: return error(JsonNull, INVALID_REQUEST, "Invalid Request")
        // Without an id it's a notification; without a method, a response to something we never sent. Neither gets a reply.
        val id = message["id"] ?: return null
        if ("method" !in message) return null
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            when (message.text("method")) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JsonObject(emptyMap()))
                "tools/list" -> result(id, buildJsonObject { put("tools", buildJsonArray { tools().forEach { add(it) } }) })
                "tools/call" -> {
                    val name = params.text("name")
                    when {
                        name == TOOL -> result(id, callTool())
                        name == CALLS_TOOL && hasGateway() -> result(id, callGatewayCalls(params["arguments"] as? JsonObject))
                        name == QUERIES_TOOL && loggedDatabases().isNotEmpty() ->
                            result(id, callDbQueries(params["arguments"] as? JsonObject))
                        else -> error(id, INVALID_PARAMS, "Unknown tool: $name")
                    }
                }
                else -> error(id, METHOD_NOT_FOUND, "Method not found")
            }
        } catch (e: Exception) {
            error(id, INTERNAL_ERROR, "Internal error: ${e.message}")
        }
    }

    /** A string field, or null when it is missing or not a string (a wrongly typed field must not throw). */
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun initialize(params: JsonObject): JsonObject {
        val requested = params.text("protocolVersion")
        return buildJsonObject {
            put("protocolVersion", requested?.takeIf { it in SUPPORTED_VERSIONS } ?: SUPPORTED_VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") {} }
            putJsonObject("serverInfo") {
                put("name", "gantry")
                put("version", version)
            }
            put(
                "instructions",
                "Gantry runs this project's local infrastructure (app '$app'). Call $TOOL to learn how to reach its " +
                    "databases, queues, cache and other services, then connect with your own tools (psql, mysql, aws, redis-cli, ...).",
            )
        }
    }

    /** Whether the app has a gateway service, read fresh each time since services come and go. */
    private fun hasGateway(): Boolean =
        try {
            engine.readApp(app).service(GATEWAY) != null
        } catch (_: GantryException) {
            false
        } catch (_: SerializationException) {
            false
        }

    private fun loggedDatabases(): List<String> =
        try {
            engine.queryLoggedDatabases(app)
        } catch (_: GantryException) {
            emptyList()
        } catch (_: SerializationException) {
            emptyList()
        }

    private fun tools() =
        listOfNotNull(
            toolDefinition(),
            if (hasGateway()) gatewayCallsDefinition() else null,
            loggedDatabases().takeIf { it.isNotEmpty() }?.let(::dbQueriesDefinition),
        )

    private fun dbQueriesDefinition(databases: List<String>) =
        buildJsonObject {
            put("name", QUERIES_TOOL)
            put(
                "description",
                "The newest statements Gantry app '$app' ran against its databases (${databases.joinToString()}), newest first, " +
                    "with SQL, parameters, duration and outcome (row count, rows affected or error), without result rows. " +
                    "Pass id to get one statement with its result rows. Rows are cut to $QUERY_ROWS and parameters and " +
                    "values to $QUERY_VALUE_CHARS characters (truncated is then true). Filter with search (text in the SQL " +
                    "or client, ignoring case) and since (how long ago, like 15m, 2h or 1d, or an ISO time).",
            )
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("service") {
                        put("type", "string")
                        put("description", "The database service; optional when the app has one query-logged database.")
                    }
                    putJsonObject("search") {
                        put("type", "string")
                        put("description", "Only statements whose SQL or client contains this text, ignoring case.")
                    }
                    putJsonObject("since") {
                        put("type", "string")
                        put("description", "Only statements from then on: how long ago (15m, 2h, 1d) or an ISO time.")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "How many statements to return (default $CALLS_DEFAULT, at most $CALLS_MAX).")
                    }
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "One statement's id from a listing: returns just it, with its result rows.")
                    }
                }
            }
            putJsonObject("annotations") { put("readOnlyHint", true) }
        }

    private fun callDbQueries(arguments: JsonObject?): JsonObject =
        try {
            val databases = loggedDatabases()
            val service = arguments?.text("service")?.ifBlank { null }
            val name =
                when {
                    service != null && service in databases -> service
                    service != null -> throw GantryException(
                        "'$service' is not a query-logged database of '$app' (have: ${databases.joinToString()})",
                    )
                    databases.size == 1 -> databases.single()
                    else -> throw GantryException("name a service: ${databases.joinToString()}")
                }
            val limit = ((arguments?.get("limit") as? JsonPrimitive)?.intOrNull ?: CALLS_DEFAULT).coerceIn(1, CALLS_MAX)
            val since = arguments?.text("since")?.ifBlank { null }?.let { parseQueryTime(it) }
            val log = engine.queryLog(app, name)
            val id = arguments?.text("id")?.ifBlank { null }
            val entries =
                if (id != null) {
                    listOfNotNull(log.get(id))
                } else {
                    log.recent(limit, arguments?.text("search")?.ifBlank { null }, since)
                }.map { it.cutForAgent() }
            buildJsonObject { put("content", buildJsonArray { add(textContent(Json.encodeToString(entries))) }) }
        } catch (e: GantryException) {
            toolError(e.message ?: "error")
        }

    /** Rows, values and parameters are cut again for agents, so a few large statements do not flood their context. */
    private fun QueryEntry.cutForAgent(): QueryEntry {
        var cut = rows != null && rows.size > QUERY_ROWS

        fun short(value: String?): String? =
            if (value != null && value.length > QUERY_VALUE_CHARS) {
                cut = true
                value.take(QUERY_VALUE_CHARS)
            } else {
                value
            }
        val cells = rows?.take(QUERY_ROWS)?.map { row -> row.map(::short) }
        val shortParams = params.map(::short)
        return copy(rows = cells, params = shortParams, truncated = truncated || cut)
    }

    private fun gatewayCallsDefinition() =
        buildJsonObject {
            put("name", CALLS_TOOL)
            put(
                "description",
                "The newest calls through the REST API gateway of Gantry app '$app', newest first, with request and response " +
                    "headers and bodies and the auth decision for each. Secrets in headers are redacted, and bodies are cut " +
                    "to 4 KB each (truncated is then true). Filter with status (a class like 4xx, or a code like 404) and " +
                    "path (a glob such as /users/**, matched without the query string).",
            )
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "How many calls to return (default $CALLS_DEFAULT, at most $CALLS_MAX).")
                    }
                    putJsonObject("status") {
                        put("type", "string")
                        put("description", "Only calls with this status: a class such as 4xx or an exact code such as 404.")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Only calls whose path matches this glob, e.g. /users/** (* is one path segment).")
                    }
                }
            }
            putJsonObject("annotations") { put("readOnlyHint", true) }
        }

    private fun callGatewayCalls(arguments: JsonObject?): JsonObject =
        try {
            val limit = ((arguments?.get("limit") as? JsonPrimitive)?.intOrNull ?: CALLS_DEFAULT).coerceIn(1, CALLS_MAX)
            val status = arguments?.text("status")?.ifBlank { null }
            val path = arguments?.text("path")?.ifBlank { null }
            val calls = engine.callLog(app).recent(limit, status, path).map { it.cutBodies() }
            buildJsonObject { put("content", buildJsonArray { add(textContent(Json.encodeToString(calls))) }) }
        } catch (e: GantryException) {
            toolError(e.message ?: "error")
        }

    /** Bodies are cut again for agents, so a few large calls do not flood their context. */
    private fun Call.cutBodies(): Call {
        val request = requestBody?.cutTo(BODY_BYTES)
        val response = responseBody?.cutTo(BODY_BYTES)
        return copy(
            requestBody = request?.first,
            responseBody = response?.first,
            truncated = truncated || request?.second == true || response?.second == true,
        )
    }

    private fun String.cutTo(max: Int): Pair<String, Boolean> {
        val bytes = toByteArray(Charsets.UTF_8)
        if (bytes.size <= max) return this to false
        var end = max
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) to true
    }

    private fun toolDefinition() =
        buildJsonObject {
            put("name", TOOL)
            put(
                "description",
                "Live connection details for Gantry app '$app': each service's state, host, port, credentials and env " +
                    "variables, plus queue and topic names. Call it again after the app changes; values are always current.",
            )
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {}
            }
            putJsonObject("annotations") { put("readOnlyHint", true) }
        }

    private fun callTool(): JsonObject =
        try {
            val details = connectionDetails(engine, app)
            buildJsonObject {
                put("content", buildJsonArray { add(textContent(details.toText())) })
                put("structuredContent", Json.encodeToJsonElement(details))
            }
        } catch (e: GantryException) {
            toolError(e.message ?: "error")
        } catch (e: SerializationException) {
            // `gantry start` and the dashboard rewrite app.json in place; a call can land mid-write.
            toolError("Couldn't read app '$app' (it may be being updated right now); try again. ${e.message?.lineSequence()?.first()}")
        }

    private fun toolError(message: String) =
        buildJsonObject {
            put("content", buildJsonArray { add(textContent(message)) })
            put("isError", true)
        }

    private fun textContent(text: String) =
        buildJsonObject {
            put("type", "text")
            put("text", text)
        }

    private fun result(
        id: JsonElement,
        result: JsonObject,
    ) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(
        id: JsonElement,
        code: Int,
        message: String,
    ) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }
}
