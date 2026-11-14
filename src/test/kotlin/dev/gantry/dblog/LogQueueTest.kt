package dev.gantry.dblog

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogQueueTest {
    private fun entry(
        sql: String,
        rows: List<List<String?>>?,
    ) = QueryEntry(
        id = sql,
        time = "2026-11-14T00:00:00Z",
        durationMs = 1.0,
        service = "postgres",
        connectionId = 1,
        client = null,
        sql = sql,
        params = listOf("p"),
        columns = rows?.let { listOf("v") },
        rows = rows,
        rowCount = rows?.size?.toLong(),
        affected = null,
        command = "SELECT",
        error = null,
        truncated = false,
    )

    @Test
    fun `a full queue drops rows but keeps the entry`() {
        val file = createTempDirectory("gantry-queue").resolve("q.jsonl")
        val writer = QueryLogWriter(file, 10_000_000)
        val queue = LogQueue(writer, maxRowBytes = 100)
        queue.offer(entry("big", List(10) { listOf("x".repeat(50)) }))
        queue.offer(entry("small", listOf(listOf("y"))))
        queue.close()
        writer.close()

        val byId = QueryLog(file).recent().associateBy { it.id }
        val big = byId.getValue("big")
        assertNull(big.rows)
        assertNull(big.columns)
        assertEquals(10L, big.rowCount)
        assertEquals(listOf<String?>("p"), big.params)
        assertTrue(big.truncated)
        val small = byId.getValue("small")
        assertEquals(listOf(listOf<String?>("y")), small.rows)
        assertFalse(small.truncated)
    }

    @Test
    fun `a failing writer never stops the queue`() {
        val file = createTempDirectory("gantry-queue").resolve("q.jsonl")
        val writer = QueryLogWriter(file, 10_000_000)
        writer.close()
        val queue = LogQueue(writer)
        repeat(3) { queue.offer(entry("s$it", null)) }
        queue.close()
    }
}
