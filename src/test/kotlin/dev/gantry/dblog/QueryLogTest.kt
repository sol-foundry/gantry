package dev.gantry.dblog

import dev.gantry.engine.ExecResult
import dev.gantry.engine.GantryException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryLogTest {
    private val file: Path = Files.createTempDirectory("gantry-querylog-test").resolve("db.jsonl")

    private fun entry(
        id: String,
        time: String = "2026-11-14T10:00:00Z",
        sql: String = "select 1",
        client: String? = null,
        rows: List<List<String?>>? = null,
    ) = QueryEntry(
        id = id,
        time = time,
        durationMs = 1.5,
        service = "db",
        connectionId = 7,
        client = client,
        sql = sql,
        params = listOf("a", null),
        columns = rows?.let { listOf("v") },
        rows = rows,
        rowCount = rows?.size?.toLong(),
        affected = null,
        command = null,
        error = null,
        truncated = false,
    )

    private fun write(vararg entries: QueryEntry) {
        val writer = QueryLogWriter(file, 1_000_000)
        entries.forEach(writer::append)
        writer.close()
    }

    @Test
    fun `recent filters by search, time window and limit, newest first`() {
        write(
            entry("1", "2026-11-14T10:00:00Z", "select * from users"),
            entry("2", "2026-11-14T10:01:00Z", "update orders set x = 1", client = "Worker-A"),
            entry("3", "2026-11-14T10:02:00Z", "SELECT * FROM Users where id = 1"),
            entry("4", "2026-11-14T10:03:00Z", "delete from users"),
        )
        val log = QueryLog(file)

        assertEquals(listOf("4", "3", "2", "1"), log.recent().map { it.id })
        assertEquals(listOf("4", "3"), log.recent(limit = 2).map { it.id })
        assertEquals(listOf("4", "3", "1"), log.recent(search = "USERS").map { it.id })
        assertEquals(listOf("2"), log.recent(search = "worker-a").map { it.id })
        assertEquals(
            listOf("3", "2"),
            log.recent(from = Instant.parse("2026-11-14T10:01:00Z"), to = Instant.parse("2026-11-14T10:02:00Z")).map { it.id },
        )
    }

    @Test
    fun `get finds an entry by id`() {
        write(entry("a"), entry("b", sql = "select 2"))
        val log = QueryLog(file)

        assertEquals("select 2", log.get("b")?.sql)
        assertEquals(listOf("a", null), log.get("a")?.params?.let { listOf("a", it[1]) })
        assertNull(log.get("missing"))
    }

    @Test
    fun `a NUL gap and a malformed line are skipped`() {
        write(entry("1"))
        val good = Files.readAllBytes(file)
        val line2 = good.toString(Charsets.UTF_8).replace("\"id\":\"1\"", "\"id\":\"2\"")
        Files.write(file, good + ByteArray(40) + "not json\n".toByteArray() + "{\"id\":\"x\"}\n".toByteArray() + line2.toByteArray())

        val log = QueryLog(file)

        assertEquals(listOf("2", "1"), log.recent().map { it.id })
        assertNotNull(log.get("1"))
    }

    @Test
    fun `a missing file has no entries`() {
        assertEquals(emptyList(), QueryLog(file.resolveSibling("nope.jsonl")).recent())
    }

    @Test
    fun `clearing goes through the proxy container`() {
        write(entry("1", "2026-11-14T10:00:00Z", "select 1"))
        val sent = mutableListOf<ByteArray>()

        QueryLog(file) { content ->
            sent += content
            ExecResult.DONE
        }.clear()

        assertEquals(1, sent.size)
        assertEquals(0, sent.single().size)
        assertEquals(listOf("1"), QueryLog(file).recent().map { it.id })
    }

    @Test
    fun `clearing empties the file here when the proxy is not running and fails when it cannot be reached`() {
        write(entry("1", "2026-11-14T10:00:00Z", "select 1"))
        QueryLog(file) { ExecResult.NOT_RUNNING }.clear()
        assertEquals(0, Files.size(file))

        write(entry("2", "2026-11-14T10:00:00Z", "select 2"))
        assertFailsWith<GantryException> { QueryLog(file) { ExecResult.FAILED }.clear() }
        assertEquals(listOf("2"), QueryLog(file).recent().map { it.id })
    }

    @Test
    fun `clearing a log that was never written does nothing`() {
        QueryLog(file).clear()
        assertTrue(QueryLog(file).recent().isEmpty())
    }

    @Test
    fun `time bounds read as an instant or as how long ago`() {
        val now = Instant.parse("2026-11-14T10:00:00Z")
        assertEquals(Instant.parse("2026-11-14T09:45:00Z"), parseQueryTime("15m", now))
        assertEquals(Instant.parse("2026-11-14T08:00:00Z"), parseQueryTime("2h", now))
        assertEquals(Instant.parse("2026-11-13T10:00:00Z"), parseQueryTime("1d", now))
        assertEquals(Instant.parse("2026-11-14T09:59:30Z"), parseQueryTime("30s", now))
        assertEquals(Instant.parse("2026-11-14T10:05:00Z"), parseQueryTime("2026-11-14T10:05:00Z", now))
        assertFailsWith<GantryException> { parseQueryTime("yesterday", now) }
    }

    @Test
    fun `recent leaves out rows and columns, get has them`() {
        write(entry("1", rows = listOf(listOf("a"), listOf("b"))))
        val log = QueryLog(file)

        val listed = log.recent().single()
        assertNull(listed.rows)
        assertNull(listed.columns)
        assertEquals(2L, listed.rowCount)
        assertEquals(listOf("a", null), listed.params)
        assertEquals(listOf(listOf<String?>("a"), listOf("b")), log.get("1")?.rows)
        assertEquals(listOf("v"), log.get("1")?.columns)
    }

    @Test
    fun `recent stops reading once a run of entries ended before from`() {
        val early = entry("early", "2026-11-14T10:30:00Z")
        val old = (1..100).map { entry("old$it", "2026-11-14T09:00:00Z") }
        write(early, *old.toTypedArray(), entry("new", "2026-11-14T10:10:00Z"))

        assertEquals(listOf("new"), QueryLog(file).recent(from = Instant.parse("2026-11-14T10:00:00Z")).map { it.id })
    }

    @Test
    fun `search ignores case for any text, and matches quotes and backslashes`() {
        write(
            entry("1", sql = "select 'Été'"),
            entry("2", sql = "select \"Quoted\" from t"),
            entry("3", sql = "select 'a\\b'"),
            entry("4", sql = "select 1", rows = listOf(listOf("users"))),
        )
        val log = QueryLog(file)

        assertEquals(listOf("1"), log.recent(search = "éTÉ").map { it.id })
        assertEquals(listOf("2"), log.recent(search = "\"quoted\"").map { it.id })
        assertEquals(listOf("3"), log.recent(search = "a\\b").map { it.id })
        assertEquals(emptyList(), log.recent(search = "users").map { it.id })
    }

    @Test
    fun `recent after an id returns only what was written after it`() {
        write(entry("1"), entry("2"), entry("3"))
        val log = QueryLog(file)

        assertEquals(listOf("3"), log.recent(after = "2").map { it.id })
        assertEquals(emptyList(), log.recent(after = "3").map { it.id })
        assertEquals(listOf("3", "2", "1"), log.recent(after = "gone").map { it.id })
        assertEquals("3", log.newestId())
        assertNull(QueryLog(file.resolveSibling("nope.jsonl")).newestId())
    }

    @Test
    fun `a time too far back is a clear error`() {
        val now = Instant.parse("2026-11-14T10:00:00Z")
        assertFailsWith<GantryException> { parseQueryTime("99999999999999999999d", now) }
        assertFailsWith<GantryException> { parseQueryTime("9999999999999d", now) }
        assertFailsWith<GantryException> { parseQueryTime("9223372036854775807s", now) }
    }

    @Test
    fun `a log the host cannot write, with the proxy stopped, says how to clear it`() {
        write(entry("1"))
        if (!Files.getFileStore(file).supportsFileAttributeView("posix")) return
        Files.setPosixFilePermissions(
            file,
            java.nio.file.attribute.PosixFilePermissions
                .fromString("r--r--r--"),
        )
        if (Files.isWritable(file)) return
        val e = assertFailsWith<GantryException> { QueryLog(file) { ExecResult.NOT_RUNNING }.clear() }
        assertTrue("start the app" in e.message.orEmpty(), e.message)
    }
}
