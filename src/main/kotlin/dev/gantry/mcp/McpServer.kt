package dev.gantry.mcp

import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.Writer

/** Protocol revisions this server speaks, newest first; a client asking for another gets the newest. */
private val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

private const val TOOL = "get_connection_details"

private const val PARSE_ERROR = -32700
private const val INVALID_REQUEST = -32600
private const val METHOD_NOT_FOUND = -32601
private const val INVALID_PARAMS = -32602
private const val INTERNAL_ERROR = -32603

/**
 * A Model Context Protocol server over stdio for one app: newline-delimited JSON-RPC in, one JSON line per reply out.
 * It offers a single read-only tool returning the app's connection details; agents use their own tools from there.
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
                "tools/list" -> result(id, buildJsonObject { put("tools", buildJsonArray { add(toolDefinition()) }) })
                "tools/call" -> {
                    val name = params.text("name")
                    if (name == TOOL) result(id, callTool()) else error(id, INVALID_PARAMS, "Unknown tool: $name")
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
