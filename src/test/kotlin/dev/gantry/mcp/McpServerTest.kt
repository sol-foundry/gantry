package dev.gantry.mcp

import dev.gantry.dblog.QueryEntry
import dev.gantry.dblog.QueryLogWriter
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
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpServerTest {
    private val engine =
        Engine(
            home = Files.createTempDirectory("gantry-mcp-test"),
            dockerCommand = NO_DOCKER,
            runtimeLib = Files.createTempDirectory("gantry-lib").also { it.resolve("gantry-0.0.0.jar").writeText("jar bytes") },
        ).apply {
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
    fun `lists the connection details tool first, read-only`() {
        val tools = exchange("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""").single().at("result", "tools").jsonArray

        assertEquals(2, tools.size)
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

    private fun gatewayLine(
        id: String,
        reqBody: String,
    ) = """{"request":{"uri":"/a","method":"POST","headers":{"authorization":"Bearer secret","x-request-id":"$id"},"body":"$reqBody"},""" +
        """"start_time":1793814923986,"latency":2.0,"response":{"status":200,"headers":{}},"route_id":"default"}"""

    private fun callsCall(arguments: String = "{}") =
        """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_gateway_calls","arguments":$arguments}}"""

    @Test
    fun `mcp lists get_gateway_calls only when the app has a gateway`() {
        fun names(app: String) =
            exchange("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""", app = app)
                .single()
                .at("result", "tools")
                .jsonArray
                .map { it.at("name").jsonPrimitive.content }

        assertEquals(listOf("get_connection_details", "get_db_queries"), names("shop"))

        engine.create("edge", services = listOf("gateway"))
        val tools = exchange("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""", app = "edge").single().at("result", "tools").jsonArray
        assertEquals(listOf("get_connection_details", "get_gateway_calls"), tools.map { it.at("name").jsonPrimitive.content })
        assertTrue("4 KB" in tools[1].at("description").jsonPrimitive.content)
        assertEquals("true", tools[1].at("annotations", "readOnlyHint").jsonPrimitive.content)

        val unknown = exchange(callsCall(), app = "shop").single()
        assertEquals(-32602, unknown.at("error", "code").jsonPrimitive.int)
    }

    @Test
    fun `get_gateway_calls returns redacted calls with bodies cut to 4 KB`() {
        engine.create("edge", services = listOf("gateway"))
        val logs = Files.createDirectories(engine.appDir("edge").resolve("gateway-log"))
        Files.writeString(
            logs.resolve("calls.log"),
            listOf(gatewayLine("one", "small"), gatewayLine("two", "z".repeat(10_000))).joinToString("\n", postfix = "\n"),
        )

        val result = exchange(callsCall("""{"limit":1}"""), app = "edge").single().at("result")
        val text =
            result
                .at("content")
                .jsonArray[0]
                .at("text")
                .jsonPrimitive.content
        val calls = Json.parseToJsonElement(text).jsonArray

        assertEquals(1, calls.size)
        assertEquals("two", calls[0].at("id").jsonPrimitive.content)
        assertEquals(
            4096,
            calls[0]
                .at("requestBody")
                .jsonPrimitive.content.length,
        )
        assertEquals("true", calls[0].at("truncated").jsonPrimitive.content)
        assertEquals("[redacted]", calls[0].at("requestHeaders", "authorization").jsonPrimitive.content)
        assertFalse("secret" in text)

        val filtered = exchange(callsCall("""{"status":"5xx"}"""), app = "edge").single().at("result")
        assertEquals(
            "[]",
            filtered
                .at("content")
                .jsonArray[0]
                .at("text")
                .jsonPrimitive.content,
        )
    }

    private fun queriesCall(arguments: String = "{}") =
        """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"get_db_queries","arguments":$arguments}}"""

    private fun dbEntry(
        id: String,
        time: String,
        sql: String,
        rows: List<List<String?>>,
        params: List<String?> = emptyList(),
    ) = QueryEntry(
        id = id,
        time = time,
        durationMs = 1.0,
        service = "postgres",
        connectionId = 1,
        client = null,
        sql = sql,
        params = params,
        columns = listOf("a"),
        rows = rows,
        rowCount = rows.size.toLong(),
        affected = null,
        command = "SELECT",
        error = null,
        truncated = false,
    )

    @Test
    fun `get_db_queries lists entries without rows, and returns one by id with rows and values cut`() {
        val file = engine.appDir("shop").resolve("db-log").resolve("postgres.jsonl")
        QueryLogWriter(file, 10_000_000).use { w ->
            w.append(dbEntry("one", "2026-11-14T10:00:00Z", "select 1", listOf(listOf("x"))))
            w.append(
                dbEntry(
                    "two",
                    "2026-11-14T10:01:00Z",
                    "select big",
                    (1..50).map { listOf("v".repeat(500)) },
                    listOf("p".repeat(500), null),
                ),
            )
        }

        val tools = exchange("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""").single().at("result", "tools").jsonArray
        assertEquals(listOf("get_connection_details", "get_db_queries"), tools.map { it.at("name").jsonPrimitive.content })
        assertEquals("true", tools[1].at("annotations", "readOnlyHint").jsonPrimitive.content)

        fun entries(arguments: String) =
            Json
                .parseToJsonElement(
                    exchange(queriesCall(arguments))
                        .single()
                        .at("result", "content")
                        .jsonArray[0]
                        .at("text")
                        .jsonPrimitive.content,
                ).jsonArray

        val all = entries("""{"limit":5}""")
        assertEquals(listOf("two", "one"), all.map { it.at("id").jsonPrimitive.content })
        assertTrue(all.all { it.jsonObject["rows"] == null || it.jsonObject["rows"] is JsonNull })
        assertEquals("50", all[0].at("rowCount").jsonPrimitive.content)
        assertEquals(
            200,
            all[0]
                .at("params")
                .jsonArray[0]
                .jsonPrimitive.content.length,
        )

        val one = entries("""{"id":"two"}""").single()
        assertEquals(20, one.at("rows").jsonArray.size)
        assertEquals(
            200,
            one
                .at("rows")
                .jsonArray[0]
                .jsonArray[0]
                .jsonPrimitive.content.length,
        )
        assertEquals(
            200,
            one
                .at("params")
                .jsonArray[0]
                .jsonPrimitive.content.length,
        )
        assertEquals("true", one.at("truncated").jsonPrimitive.content)
        assertEquals(0, entries("""{"id":"missing"}""").size)

        assertEquals(listOf("one"), entries("""{"service":"postgres","search":"select 1"}""").map { it.at("id").jsonPrimitive.content })
        assertEquals(listOf("two"), entries("""{"since":"2026-11-14T10:01:00Z"}""").map { it.at("id").jsonPrimitive.content })

        val bad = exchange(queriesCall("""{"service":"nope"}""")).single().at("result")
        assertEquals("true", bad.at("isError").jsonPrimitive.content)
    }

    @Test
    fun `get_db_queries is unknown for an app without a query-logged database`() {
        engine.create("cache", services = listOf("valkey"))
        val tools = exchange("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""", app = "cache").single().at("result", "tools").jsonArray
        assertEquals(listOf("get_connection_details"), tools.map { it.at("name").jsonPrimitive.content })
        assertEquals(
            -32602,
            exchange(queriesCall(), app = "cache")
                .single()
                .at("error", "code")
                .jsonPrimitive.int,
        )
    }

    @Test
    fun `listing tools does not rewrite saved app state`() {
        engine.create("edge", services = listOf("gateway"))
        val state = engine.appDir("edge").resolve("app.json")
        val withLegacy = Files.readString(state).replaceFirst("\"services\": [", "\"services\": [{\"id\": \"redisinsight\"},")
        Files.writeString(state, withLegacy)

        exchange("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""", app = "edge")

        assertEquals(withLegacy, Files.readString(state))
    }
}
