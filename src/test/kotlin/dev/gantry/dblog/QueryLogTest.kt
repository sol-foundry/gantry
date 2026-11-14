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
    ) = QueryEntry(
        id = id,
        time = time,
        durationMs = 1.5,
        service = "db",
        connectionId = 7,
        client = client,
        sql = sql,
        params = listOf("a", null),
        columns = null,
        rows = null,
        rowCount = null,
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
}
