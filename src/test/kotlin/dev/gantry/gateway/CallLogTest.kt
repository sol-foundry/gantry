package dev.gantry.gateway

import dev.gantry.engine.ExecResult
import dev.gantry.engine.GantryException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallLogTest {
    private val dir: Path = Files.createTempDirectory("gantry-calllog-test")

    private fun fixture(name: String) = CallLogTest::class.java.getResource("/gateway/$name")!!.readText()

    private fun line(
        id: String?,
        uri: String = "/x",
        status: Int = 200,
        method: String = "GET",
        reqHeaders: String = """{"authorization":"Bearer t","accept":"*/*"}""",
        respHeaders: String = """{"set-cookie":["a=1","b=2"],"content-type":"text/plain"}""",
        reqBody: String? = null,
        respBody: String? = null,
        route: String = "default",
    ): String {
        val rid = id?.let { ""","x-request-id":"$it"""" } ?: ""
        val rb = reqBody?.let { ""","body":"$it"""" } ?: ""
        val pb = respBody?.let { ""","body":"$it"""" } ?: ""
        return """{"request":{"uri":"$uri","method":"$method","headers":${reqHeaders.dropLast(1)}$rid}$rb},""" +
            """"start_time":1793814923986,"latency":3.6,"response":{"status":$status,"headers":$respHeaders$pb},""" +
            """"route_id":"$route","upstream":"1.2.3.4:80"}"""
    }

    private fun write(
        calls: List<String>,
        auth: List<String> = emptyList(),
    ) {
        Files.writeString(dir.resolve("calls.log"), calls.joinToString("\n", postfix = "\n"))
        if (auth.isNotEmpty()) Files.writeString(dir.resolve("auth.log"), auth.joinToString("\n", postfix = "\n"))
    }

    private fun log(settings: LogSettings = LogSettings()) = CallLog(dir, settings)

    @Test
    fun `parses file-logger lines and joins auth decisions by request id`() {
        Files.writeString(dir.resolve("calls.log"), fixture("apisix-calls.log"))
        Files.writeString(dir.resolve("auth.log"), fixture("gantry-auth.log"))

        val calls = log().recent()

        assertEquals(2, calls.size)
        val pub = calls[0]
        assertEquals("6676f110-9a79-47f8-92f2-8cab768f8a97", pub.id)
        assertEquals("POST", pub.method)
        assertEquals("/pub/items?x=1", pub.uri)
        assertEquals(200, pub.status)
        assertEquals(4L, pub.latencyMs)
        assertEquals("default", pub.route)
        assertEquals("2026-11-04T17:55:23.986Z", pub.time)
        assertNull(pub.upstreamUri)
        assertNull(pub.authDecision)
        assertNull(pub.userId)
        assertEquals("""{"name":"widget","n":1}""", pub.requestBody)
        assertEquals("application/json", pub.requestHeaders["content-type"])
        assertEquals("[redacted]", pub.responseHeaders["set-cookie"])
        assertEquals("sid=abc", log(LogSettings(redact = emptyList())).recent().first().responseHeaders["set-cookie"])

        val denied = calls[1]
        assertEquals("me", denied.route)
        assertEquals(403, denied.status)
        assertEquals("deny", denied.authDecision)
        assertEquals("malformed token", denied.authReason)
        assertNull(denied.userId)
    }

    @Test
    fun `user id comes from the auth context`() {
        write(
            listOf(line("a"), line("b"), line("c")),
            listOf(
                """{"time":"t","requestId":"a","route":"r","decision":"allow","status":200,"reason":null,"context":{"userId":"u1","uid":"x"}}""",
                """{"time":"t","requestId":"b","route":"r","decision":"allow","status":200,"reason":null,"context":{"uid":"u2","sub":"x"}}""",
                """{"time":"t","requestId":"c","route":"r","decision":"allow","status":200,"reason":null,"context":{"sub":"u3"}}""",
            ),
        )

        assertEquals(listOf("u3", "u2", "u1"), log().recent().map { it.userId })
    }

    @Test
    fun `redacts configured headers in requests and responses`() {
        write(listOf(line("a")))

        val call = log(LogSettings(redact = listOf("Authorization", "SET-COOKIE"))).recent().single()

        assertEquals("[redacted]", call.requestHeaders["authorization"])
        assertEquals("*/*", call.requestHeaders["accept"])
        assertEquals("[redacted]", call.responseHeaders["set-cookie"])
        assertEquals("text/plain", call.responseHeaders["content-type"])
        assertFalse("Bearer" in call.toString())
    }

    @Test
    fun `joins multi-valued headers`() {
        write(listOf(line("a")))

        assertEquals("a=1, b=2", log(LogSettings(redact = emptyList())).recent().single().responseHeaders["set-cookie"])
    }

    @Test
    fun `cuts bodies without splitting a code point and flags truncated`() {
        write(listOf(line("a", reqBody = "abéé", respBody = "ok"), line("b", reqBody = "abc")))

        val calls = log(LogSettings(maxBodyBytes = 3)).recent()

        assertEquals("abc", calls[0].requestBody)
        assertFalse(calls[0].truncated)
        // "ab" + e-acute is 4 bytes; 3 bytes would cut the second code point in half.
        assertEquals("ab", calls[1].requestBody)
        assertEquals("ok", calls[1].responseBody)
        assertTrue(calls[1].truncated)
    }

    @Test
    fun `filters by status class and path`() {
        write(
            listOf(
                line("a", uri = "/users/1?x=1", status = 200),
                line("b", uri = "/users/2", status = 404),
                line("c", uri = "/orders/9", status = 500),
                line("d", uri = "/orders/9", status = 404),
            ),
        )
        val log = log()

        assertEquals(listOf("d", "b"), log.recent(status = "4xx").map { it.id })
        assertEquals(listOf("c"), log.recent(status = "5xx").map { it.id })
        assertEquals(listOf("d", "b"), log.recent(status = "404").map { it.id })
        assertEquals(listOf("b", "a"), log.recent(path = "/users/*").map { it.id })
        assertEquals(listOf("d"), log.recent(path = "/orders/**", status = "404").map { it.id })
        assertFailsWith<GantryException> { log.recent(status = "oops") }
    }

    @Test
    fun `newest first with a limit, skipping malformed lines`() {
        write(listOf(line("a"), "not json", line("b"), """{"request":"x"}""", line("c"), """{"truncated"""))

        assertEquals(listOf("c", "b", "a"), log().recent().map { it.id })
        assertEquals(listOf("c", "b"), log().recent(limit = 2).map { it.id })
    }

    @Test
    fun `get finds a call by id`() {
        write(listOf(line("a"), line("b")))

        assertEquals("a", log().get("a")?.id)
        assertNull(log().get("zzz"))
    }

    @Test
    fun `a call without a request id gets a stable id`() {
        write(listOf(line(null), line(null, uri = "/y")))

        val first = log().recent()
        val second = log().recent()

        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(2, first.map { it.id }.toSet().size)
        assertNotNull(log().get(first[1].id))
    }

    @Test
    fun `no files is no calls`() {
        assertEquals(emptyList(), log().recent())
        log().trim()
    }

    @Test
    fun `reads from the end across chunk boundaries`() {
        val big = "x".repeat(100_000)
        write((1..30).map { line("id$it", respBody = if (it % 2 == 0) big else "s") })

        val calls = log(LogSettings(maxBodyBytes = 10)).recent(limit = 5)

        assertEquals(listOf("id30", "id29", "id28", "id27", "id26"), calls.map { it.id })
        assertEquals(30, log(LogSettings(maxBodyBytes = 10)).recent(limit = 100).size)
    }

    @Test
    fun `trim keeps the newest lines in place`() {
        write((1..10).map { line("id$it") }, (1..10).map { """{"requestId":"id$it","decision":"allow","status":200}""" })
        val file = dir.resolve("calls.log")
        val inode = Files.readAttributes(file, "unix:ino")["ino"]

        log(LogSettings(maxCalls = 3)).trim()

        assertEquals(listOf("id8", "id9", "id10"), Files.readAllLines(file).map { Regex("id\\d+").find(it)!!.value })
        assertEquals(3, Files.readAllLines(dir.resolve("auth.log")).size)
        assertEquals(inode, Files.readAttributes(file, "unix:ino")["ino"])
    }

    @Test
    fun `a NUL gap left by a writer's stale offset reads as the lines around it`() {
        val file = dir.resolve("calls.log")
        Files.write(file, (line("id1") + "\n").toByteArray() + ByteArray(300_000) + (line("id2") + "\n" + line("id3") + "\n").toByteArray())

        assertEquals(listOf("id3", "id2", "id1"), log().recent().map { it.id })
    }

    @Test
    fun `trim rewrites through the writer's container and leaves the host file to it`() {
        write((1..10).map { line("id$it") }, (1..10).map { """{"requestId":"id$it","decision":"allow","status":200}""" })
        val before = Files.readString(dir.resolve("calls.log"))
        val written = mutableMapOf<String, String>()

        CallLog(dir, LogSettings(maxCalls = 3), writer = { name, content ->
            written[name] = String(content)
            ExecResult.DONE
        }).trim()

        assertEquals(
            listOf("id8", "id9", "id10"),
            written
                .getValue("calls.log")
                .lines()
                .filter {
                    it.isNotEmpty()
                }.map { Regex("id\\d+").find(it)!!.value },
        )
        assertEquals(3, written.getValue("auth.log").lines().count { it.isNotEmpty() })
        assertEquals(before, Files.readString(dir.resolve("calls.log")))
    }

    @Test
    fun `trim writes on the host when the writer's container is stopped`() {
        write((1..10).map { line("id$it") })

        CallLog(dir, LogSettings(maxCalls = 3), writer = { _, _ -> ExecResult.NOT_RUNNING }).trim()

        assertEquals(3, Files.readAllLines(dir.resolve("calls.log")).size)
    }

    @Test
    fun `trim leaves the file alone when the running container cannot rewrite it`() {
        write((1..10).map { line("id$it") })

        CallLog(dir, LogSettings(maxCalls = 3), writer = { _, _ -> ExecResult.FAILED }).trim()

        assertEquals(10, Files.readAllLines(dir.resolve("calls.log")).size)
    }

    @Test
    fun `clear empties through the writer's container`() {
        write((1..5).map { line("id$it") }, listOf("""{"requestId":"id1","decision":"allow","status":200}"""))
        val written = mutableMapOf<String, Int>()

        CallLog(dir, LogSettings(), writer = { name, content ->
            written[name] = content.size
            ExecResult.DONE
        }).clear()

        assertEquals(mapOf("calls.log" to 0, "auth.log" to 0), written)
    }

    @Test
    fun `a file bloated by a NUL gap is rewritten even with few lines`() {
        val settings = LogSettings(maxCalls = 3)
        val file = dir.resolve("calls.log")
        Files.write(file, ByteArray(100_000) + (line("id1") + "\n" + line("id2") + "\n").toByteArray())

        assertEquals(listOf("id2", "id1"), CallLog(dir, settings).recent().map { it.id })
        assertEquals(2, Files.readAllLines(file).size)
        assertFalse(Files.readAllBytes(file).contains(0))
    }

    @Test
    fun `a failed rewrite is not retried until the file grows`() {
        write((1..10).map { line("id$it", respBody = "y".repeat(500)) })
        var attempts = 0
        val log =
            CallLog(dir, LogSettings(maxCalls = 3), writer = { _, _ ->
                attempts++
                ExecResult.FAILED
            })

        log.recent()
        log.recent()

        assertEquals(1, attempts)
        assertEquals(10, Files.readAllLines(dir.resolve("calls.log")).size)
    }

    @Test
    fun `clear reports a failed file and still clears the other`() {
        write((1..5).map { line("id$it") }, listOf("""{"requestId":"id1","decision":"allow","status":200}"""))
        val tried = mutableListOf<String>()
        val log =
            CallLog(dir, LogSettings(), writer = { name, _ ->
                tried += name
                if (name == "calls.log") ExecResult.FAILED else ExecResult.NOT_RUNNING
            })

        val e = assertFailsWith<GantryException> { log.clear() }

        assertTrue("calls.log" in e.message!!)
        assertEquals(listOf("calls.log", "auth.log"), tried)
        assertEquals(0, Files.size(dir.resolve("auth.log")))
    }

    @Test
    fun `clear empties both files in place`() {
        write((1..5).map { line("id$it") }, listOf("""{"requestId":"id1","decision":"allow","status":200}"""))
        val file = dir.resolve("calls.log")
        val inode = Files.readAttributes(file, "unix:ino")["ino"]

        val log = log()
        log.clear()

        assertEquals(0, Files.size(file))
        assertEquals(0, Files.size(dir.resolve("auth.log")))
        assertEquals(inode, Files.readAttributes(file, "unix:ino")["ino"])
        assertEquals(emptyList(), log.recent())

        Files.writeString(file, line("id6") + "\n")
        assertEquals(listOf("id6"), log.recent().map { it.id })
    }

    @Test
    fun `clear without files does nothing`() {
        log().clear()
        assertFalse(Files.exists(dir.resolve("calls.log")))
    }

    @Test
    fun `trim leaves a short file alone and skips a read-only one`() {
        write((1..3).map { line("id$it") })
        val file = dir.resolve("calls.log")
        val before = Files.readString(file)
        log(LogSettings(maxCalls = 3)).trim()
        assertEquals(before, Files.readString(file))

        write((1..10).map { line("id$it") })
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"))
        try {
            // Running as root ignores the mode; only assert when the file really is unwritable.
            if (!Files.isWritable(file)) {
                log(LogSettings(maxCalls = 3)).trim()
                assertEquals(10, Files.readAllLines(file).size)
            }
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        }
    }

    @Test
    fun `reading trims a file that has grown past the limit`() {
        val settings = LogSettings(maxCalls = 3)
        write((1..40).map { line("id$it", respBody = "y".repeat(500)) })

        assertEquals(listOf("id40", "id39", "id38"), CallLog(dir, settings).recent().map { it.id })
        assertEquals(3, Files.readAllLines(dir.resolve("calls.log")).size)
    }

    @Test
    fun `a large file that is still short is not rescanned until it grows`() {
        val settings = LogSettings(maxCalls = 5)
        // Over the 2 KB per call threshold, yet only 3 lines.
        write((1..3).map { line("id$it", respBody = "y".repeat(8000)) })
        val file = dir.resolve("calls.log")
        val log = CallLog(dir, settings)

        log.recent()
        val scans = trimScans.get()
        val modified = Files.getLastModifiedTime(file)
        log.recent()
        log.recent()

        assertEquals(scans, trimScans.get())
        assertEquals(modified, Files.getLastModifiedTime(file))
        assertEquals(3, log.recent().size)
    }

    @Test
    fun `concurrent trims leave exactly the newest lines`() {
        val settings = LogSettings(maxCalls = 20)
        write((1..200).map { line("id$it", respBody = "y".repeat(100)) })
        val log = CallLog(dir, settings)
        val pool =
            java.util.concurrent.Executors
                .newFixedThreadPool(8)

        (1..8).map { pool.submit { log.trim() } }.forEach { it.get() }
        pool.shutdown()

        val ids = Files.readAllLines(dir.resolve("calls.log")).map { Regex("id\\d+").find(it)!!.value }
        assertEquals((181..200).map { "id$it" }, ids)
    }

    @Test
    fun `headers set from a secret and query tokens are redacted without being listed`() {
        val config =
            parseGatewayConfig(
                """
                authorizers:
                  ws:
                    type: jwt
                    jwksUrl: http://keys
                    tokenFrom: query:access_token
                routes:
                  - name: r
                    match: {path: /r/**}
                    auth: ws
                    headers:
                      x-api-key: "k-{{secret:API_KEY}}"
                      x-user-id: "{{sub}}"
                default:
                  headers:
                    X-Service-Token: "{{secret:SERVICE}}"
                log:
                  redact: [authorization]
                """.trimIndent(),
            )
        write(
            listOf(
                line(
                    "a",
                    uri = "/ws?room=1&access_token=eyJ.secret.sig&Access_Token=x2",
                    reqHeaders = """{"x-api-key":"k-123","x-service-token":"s-1","x-user-id":"u1","authorization":"Bearer t"}""",
                ),
            ),
        )
        val call = CallLog.forConfig(dir, config).recent().single()
        assertEquals("[redacted]", call.requestHeaders["x-api-key"])
        assertEquals("[redacted]", call.requestHeaders["x-service-token"])
        assertEquals("[redacted]", call.requestHeaders["authorization"])
        assertEquals("u1", call.requestHeaders["x-user-id"])
        assertEquals("/ws?room=1&access_token=[redacted]&Access_Token=[redacted]", call.uri)
        assertFalse("eyJ" in call.toString() || "k-123" in call.toString() || "s-1" in call.toString())
        // The path filter still sees the path.
        assertEquals(1, CallLog.forConfig(dir, config).recent(path = "/ws").size)
    }

    @Test
    fun `a symlinked log file is never read or truncated`() {
        val outside = Files.createTempFile("gantry-calllog-outside", ".log")
        Files.writeString(outside, (1..10).joinToString("\n", postfix = "\n") { line("id$it") })
        Files.createSymbolicLink(dir.resolve("calls.log"), outside)
        Files.createSymbolicLink(dir.resolve("auth.log"), outside)

        val log = log(LogSettings(maxCalls = 3))
        log.trim()
        log.clear()
        assertEquals(10, Files.readAllLines(outside).size)
        assertEquals(emptyList(), log.recent())
        assertNull(log.get("id10"))
    }

    @Test
    fun `shared headers set from a secret are redacted`() {
        val config = parseGatewayConfig("headers:\n  x-api-key: \"{{secret:API_KEY}}\"\n  x-tenant: acme\n")
        write(listOf(line("a", reqHeaders = """{"x-api-key":"k-123","x-tenant":"acme"}""")))
        val call = CallLog.forConfig(dir, config).recent().single()
        assertEquals("[redacted]", call.requestHeaders["x-api-key"])
        assertEquals("acme", call.requestHeaders["x-tenant"])
    }
}
