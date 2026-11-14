package dev.gantry.dblog

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryLogWriterTest {
    private val file: Path = Files.createTempDirectory("gantry-querylogwriter-test").resolve("db.jsonl")

    private fun entry(id: Int) =
        QueryEntry(
            id = "id$id",
            time = "2026-11-14T10:00:00Z",
            durationMs = 1.0,
            service = "db",
            connectionId = 1,
            client = null,
            sql = "select $id " + "x".repeat(100),
            params = emptyList(),
            columns = null,
            rows = null,
            rowCount = null,
            affected = null,
            command = null,
            error = null,
            truncated = false,
        )

    @Test
    fun `the writer trims to the newest entries past its size and keeps the inode`() {
        val cap = 4000L
        val writer = QueryLogWriter(file, cap)
        writer.append(entry(0))
        val inode = Files.getAttribute(file, "unix:ino")

        (1..60).forEach { writer.append(entry(it)) }
        writer.close()

        assertEquals(inode, Files.getAttribute(file, "unix:ino"))
        assertTrue(Files.size(file) <= cap)
        val ids = QueryLog(file).recent(limit = 1000).map { it.id }
        assertEquals("id60", ids.first())
        assertTrue(ids.size in 2..30, "kept ${ids.size}")
        assertEquals(ids.size, ids.distinct().size)
        assertEquals((60 downTo 60 - ids.size + 1).map { "id$it" }, ids)
    }

    @Test
    fun `the writer appends to an existing file`() {
        QueryLogWriter(file, 1_000_000).use { it.append(entry(1)) }
        QueryLogWriter(file, 1_000_000).use { it.append(entry(2)) }
        assertEquals(listOf("id2", "id1"), QueryLog(file).recent().map { it.id })
    }
}
