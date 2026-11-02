package dev.gantry.mcp

import dev.gantry.engine.Engine
import dev.gantry.engine.NO_DOCKER
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpServerTest {
    private val engine =
        Engine(home = Files.createTempDirectory("gantry-mcp-test"), dockerCommand = NO_DOCKER).apply {
            create("shop", services = listOf("postgres"))
        }

    private fun exchange(
        vararg lines: String,
        app: String = "shop",
    ): List<JsonElement> {
        val out = StringWriter()
        McpServer(engine, app, "1.2.3").serve(BufferedReader(StringReader(lines.joinToString("\n", postfix = "\n"))), out)
        return out
            .toString()
            .lines()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it) }
    }

    private fun initialize(version: String) =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}"""

    private val call = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_connection_details","arguments":{}}}"""

    private fun JsonElement.at(vararg path: String): JsonElement = path.fold(this) { node, key -> node.jsonObject.getValue(key) }

    @Test
    fun `initialize negotiates the protocol version`() {
        val known = exchange(initialize("2025-06-18")).single()
        assertEquals("2025-06-18", known.at("result", "protocolVersion").jsonPrimitive.content)
        assertEquals("gantry", known.at("result", "serverInfo", "name").jsonPrimitive.content)
        assertEquals("1.2.3", known.at("result", "serverInfo", "version").jsonPrimitive.content)
        assertTrue("shop" in known.at("result", "instructions").jsonPrimitive.content)
        known.at("result", "capabilities", "tools")

        val unknown = exchange(initialize("1999-01-01")).single()
        assertEquals("2025-11-25", unknown.at("result", "protocolVersion").jsonPrimitive.content)
    }

    @Test
    fun `lists one read-only tool`() {
        val tools = exchange("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""").single().at("result", "tools").jsonArray

        assertEquals(1, tools.size)
        assertEquals("get_connection_details", tools[0].at("name").jsonPrimitive.content)
        assertEquals("true", tools[0].at("annotations", "readOnlyHint").jsonPrimitive.content)
        assertEquals("object", tools[0].at("inputSchema", "type").jsonPrimitive.content)
    }

    @Test
    fun `tool call returns text and structured details`() {
        val result = exchange(call).single().at("result")

        assertEquals("shop", result.at("structuredContent", "app").jsonPrimitive.content)
        assertEquals(
            "text",
            result
                .at("content")
                .jsonArray[0]
                .at("type")
                .jsonPrimitive.content,
        )
        assertTrue(
            "postgres" in
                result
                    .at("content")
                    .jsonArray[0]
                    .at("text")
                    .jsonPrimitive.content,
        )
    }

    @Test
    fun `missing app is a tool error, not a crash`() {
        val result = exchange(call, app = "ghost").single().at("result")

        assertEquals("true", result.at("isError").jsonPrimitive.content)
        assertTrue(
            "gantry create" in
                result
                    .at("content")
                    .jsonArray[0]
                    .at("text")
                    .jsonPrimitive.content,
        )
    }

    @Test
    fun `notifications get no reply and bad lines get errors`() {
        val replies =
            exchange(
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                "{not json",
                """{"jsonrpc":"2.0","id":7,"method":"nope"}""",
                """{"jsonrpc":"2.0","id":"s","method":"ping"}""",
            )

        assertEquals(3, replies.size)
        assertEquals(-32700, replies[0].at("error", "code").jsonPrimitive.int)
        assertEquals(JsonNull, replies[0].at("id"))
        assertEquals(-32601, replies[1].at("error", "code").jsonPrimitive.int)
        assertEquals(7, replies[1].at("id").jsonPrimitive.int)
        assertEquals("s", replies[2].at("id").jsonPrimitive.content)
        assertEquals(0, replies[2].at("result").jsonObject.size)
    }

    @Test
    fun `wrongly typed fields get an error and the session goes on`() {
        val replies =
            exchange(
                """{"jsonrpc":"2.0","id":2,"method":{"x":1}}""",
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":{"x":1}}}""",
                """{"jsonrpc":"2.0","id":4,"method":"initialize","params":{"protocolVersion":[1]}}""",
                """{"jsonrpc":"2.0","id":5,"method":"ping"}""",
            )

        assertEquals(4, replies.size)
        assertTrue(replies[0].jsonObject.containsKey("error"))
        assertTrue(replies[1].jsonObject.containsKey("error"))
        assertEquals("2025-11-25", replies[2].at("result", "protocolVersion").jsonPrimitive.content)
        assertEquals(5, replies[3].at("id").jsonPrimitive.int)
    }

    @Test
    fun `an unreadable app file is a tool error and the session goes on`() {
        // `gantry up` rewrites app.json in place; a call can land while it is half written.
        engine
            .appDir("shop")
            .resolve("app.json")
            .toFile()
            .writeText("{\"name\": \"shop\", \"serv")

        val replies = exchange(call, """{"jsonrpc":"2.0","id":9,"method":"ping"}""")

        assertEquals("true", replies[0].at("result", "isError").jsonPrimitive.content)
        assertEquals(9, replies[1].at("id").jsonPrimitive.int)
    }

    @Test
    fun `responses get no reply and non-objects are invalid requests`() {
        val replies =
            exchange(
                """{"jsonrpc":"2.0","id":1,"result":{}}""",
                """[{"jsonrpc":"2.0","id":2,"method":"ping"}]""",
                "42",
            )

        assertEquals(2, replies.size)
        assertEquals(-32600, replies[0].at("error", "code").jsonPrimitive.int)
        assertEquals(-32600, replies[1].at("error", "code").jsonPrimitive.int)
    }

    @Test
    fun `unknown tool is an invalid params error`() {
        val reply = exchange("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"drop_tables"}}""").single()

        assertEquals(-32602, reply.at("error", "code").jsonPrimitive.int)
    }
}
