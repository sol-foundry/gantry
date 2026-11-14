package dev.gantry.dblog

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
}
