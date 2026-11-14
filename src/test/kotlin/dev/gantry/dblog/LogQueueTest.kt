package dev.gantry.dblog

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
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
        params: List<String?> = listOf("p"),
        id: String = sql,
    ) = QueryEntry(
        id = id,
        time = "2026-11-14T00:00:00Z",
        durationMs = 1.0,
        service = "postgres",
        connectionId = 1,
        client = null,
        sql = sql,
        params = params,
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
    fun `a failing writer never stops the queue and is reported once`() {
        val written = ArrayList<String>()
        var calls = 0
        val err = ByteArrayOutputStream()
        val stderr = System.err
        System.setErr(PrintStream(err, true))
        try {
            val queue =
                LogQueue({ entry ->
                    calls++
                    if (calls <= 2) throw IOException("disk full")
                    written += entry.sql
                })
            repeat(4) { queue.offer(entry("s$it", null)) }
            queue.close()
        } finally {
            System.setErr(stderr)
        }

        assertEquals(4, calls)
        assertEquals(listOf("s2", "s3"), written)
        assertEquals(1, Regex("cannot write the query log").findAll(err.toString()).count())
    }

    @Test
    fun `sql and parameters count toward the budget and are cut, not dropped, when over it`() {
        val kept = ArrayList<QueryEntry>()
        val queue = LogQueue({ kept += it }, maxRowBytes = 100)
        queue.offer(entry("x".repeat(100_000), null, params = listOf("v".repeat(10_000), null), id = "big"))
        queue.close()

        val e = kept.single()
        assertEquals(LogQueue.SHRUNK_SQL_BYTES, e.sql.length)
        assertEquals(LogQueue.SHRUNK_PARAM_BYTES, e.params[0]!!.length)
        assertNull(e.params[1])
        assertTrue(e.truncated)
    }

    @Test
    fun `an entry within the budget keeps its sql and parameters whole`() {
        val kept = ArrayList<QueryEntry>()
        val queue = LogQueue({ kept += it }, maxRowBytes = 100_000)
        queue.offer(entry("x".repeat(5_000), null, params = listOf("v".repeat(1_000))))
        queue.close()

        val e = kept.single()
        assertEquals(5_000, e.sql.length)
        assertEquals(1_000, e.params.single()!!.length)
        assertFalse(e.truncated)
    }

    @Test
    fun `the writer survives an Error thrown by a write`() {
        val written = ArrayList<String>()
        val stderr = System.err
        System.setErr(PrintStream(ByteArrayOutputStream(), true))
        try {
            val queue =
                LogQueue({ entry ->
                    if (entry.sql == "s0") throw StackOverflowError("deep")
                    written += entry.sql
                })
            repeat(3) { queue.offer(entry("s$it", null)) }
            queue.close()
        } finally {
            System.setErr(stderr)
        }

        assertEquals(listOf("s1", "s2"), written)
    }
}
