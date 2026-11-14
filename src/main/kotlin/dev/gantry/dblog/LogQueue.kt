package dev.gantry.dblog

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Hands [QueryEntry]s to a [QueryLogWriter] on one background thread, so recording never waits on the disk. It holds at most
 * about [maxRowBytes] of row data: an entry that does not fit is still queued, without its columns and rows and marked
 * truncated, so every statement keeps its SQL, parameters, timing and outcome.
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
        val size = rowBytes(entry)
        if (size == 0L) {
            pending.add(Held(entry, 0))
            return
        }
        val held = heldBytes.addAndGet(size)
        if (held <= maxRowBytes) {
            pending.add(Held(entry, size))
        } else {
            heldBytes.addAndGet(-size)
            pending.add(Held(entry.copy(columns = null, rows = null, truncated = true), 0))
        }
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
            } catch (e: IOException) {
                report(e)
            } catch (e: RuntimeException) {
                report(e)
            } finally {
                heldBytes.addAndGet(-held.size)
            }
        }
    }

    private fun report(e: Exception) {
        if (reported.compareAndSet(false, true)) System.err.println("gantry: cannot write the query log: $e")
    }

    private class Held(
        val entry: QueryEntry,
        val size: Long,
    )

    private companion object {
        val STOP = Any()

        fun rowBytes(entry: QueryEntry): Long =
            (entry.columns?.sumOf { it.length.toLong() } ?: 0L) +
                (entry.rows?.sumOf { row -> row.sumOf { (it?.length ?: 0).toLong() } + row.size } ?: 0L)
    }
}
