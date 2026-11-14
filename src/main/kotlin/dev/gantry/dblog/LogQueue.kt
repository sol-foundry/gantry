package dev.gantry.dblog

import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Hands [QueryEntry]s to a [QueryLogWriter] on one background thread, so recording never waits on the disk. It holds at most
 * about [maxRowBytes] of entry text (SQL, parameters, rows): an entry that does not fit is still queued, without its
 * columns and rows, its SQL and parameters cut short and marked truncated, so every statement keeps its SQL, parameters,
 * timing and outcome.
 */
class LogQueue internal constructor(
    private val append: (QueryEntry) -> Unit,
    private val maxRowBytes: Long = 32L * 1024 * 1024,
) : Closeable {
    constructor(writer: QueryLogWriter, maxRowBytes: Long = 32L * 1024 * 1024) : this(writer::append, maxRowBytes)

    private val pending = LinkedBlockingQueue<Any>()
    private val heldBytes = AtomicLong()
    private val closed = AtomicBoolean()
    private val reported = AtomicBoolean()
    private val drain = thread(isDaemon = true, name = "dblog-writer") { run() }

    /** Queues [entry]; never blocks. */
    fun offer(entry: QueryEntry) {
        if (closed.get()) return
        val size = textBytes(entry)
        if (heldBytes.addAndGet(size) <= maxRowBytes) {
            pending.add(Held(entry, size))
            return
        }
        heldBytes.addAndGet(-size)
        val shrunk = entry.copy(columns = null, rows = null, truncated = true).capped(SHRUNK_SQL_BYTES, SHRUNK_PARAM_BYTES)
        val shrunkSize = textBytes(shrunk)
        heldBytes.addAndGet(shrunkSize)
        pending.add(Held(shrunk, shrunkSize))
    }

    /** Writes everything queued so far, then stops the background thread. The writer stays open. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.add(STOP)
        drain.join(TimeUnit.SECONDS.toMillis(30))
    }

    private fun run() {
        while (true) {
            val item = pending.take()
            if (item === STOP) return
            val held = item as Held
            try {
                append(held.entry)
            } catch (e: Throwable) {
                report(e)
            } finally {
                heldBytes.addAndGet(-held.size)
            }
        }
    }

    private fun report(e: Throwable) {
        if (reported.compareAndSet(false, true)) System.err.println("gantry: cannot write the query log: $e")
    }

    private class Held(
        val entry: QueryEntry,
        val size: Long,
    )

    internal companion object {
        /** What an entry that no longer fits keeps of its SQL and of each parameter. */
        const val SHRUNK_SQL_BYTES = 4096
        const val SHRUNK_PARAM_BYTES = 256

        private val STOP = Any()

        private fun textBytes(entry: QueryEntry): Long =
            entry.sql.length.toLong() +
                entry.params.sumOf { (it?.length ?: 0).toLong() + 1 } +
                (entry.columns?.sumOf { it.length.toLong() } ?: 0L) +
                (entry.rows?.sumOf { row -> row.sumOf { (it?.length ?: 0).toLong() } + row.size } ?: 0L)
    }
}
