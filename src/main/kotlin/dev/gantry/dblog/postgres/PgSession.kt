package dev.gantry.dblog.postgres

import dev.gantry.dblog.QueryEntry
import dev.gantry.dblog.QueryError
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Caps on what one entry keeps: at most [maxRows] rows, and [maxBytes] of row values (a longer value is cut). */
data class Limits(
    val maxRows: Int,
    val maxBytes: Int,
)

/**
 * Turns one Postgres connection's traffic, both directions, into [QueryEntry]s passed to [emit]. Requests are queued
 * as the client sends them and the server's responses are matched to them in order, so pipelined extended-protocol
 * messages pair up correctly. The two directions may call in from different threads. Unexpected input is ignored,
 * never thrown: a parser bug must not break the relay.
 */
class PgSession(
    private val service: String,
    private val connectionId: Long,
    private val limits: Limits,
    private val emit: (QueryEntry) -> Unit,
) {
    /** The client's `application_name`, from the startup message or a later `SET`. */
    @Volatile
    var client: String? = null

    private class Completion(
        val tag: String?,
        val error: QueryError?,
        val end: Instant,
    )

    private class Column(
        val name: String,
        val type: Int,
    )

    private class Statement(
        val sql: String,
        var paramTypes: IntArray,
    ) {
        var columns: List<Column>? = null
    }

    private class Portal(
        val statement: Statement,
        val paramFormats: IntArray,
        val params: List<ByteArray?>,
        val resultFormats: IntArray,
        val cut: Boolean,
    ) {
        var columns: List<Column>? = null
        var suspended: Run? = null
    }

    /** Row bytes held across the entries of one simple query, which are only emitted at its ReadyForQuery. */
    private class Budget {
        var used = 0L
    }

    /** One statement's execution, collecting its rows until it completes. */
    private inner class Run(
        var sql: String,
        val portal: Portal?,
        private val budget: Budget = Budget(),
    ) {
        var start: Instant? = null
        var columns: List<Column>? = null
        var formats: IntArray = IntArray(0)
        val rows = ArrayList<List<String?>>()
        var count = 0L
        var full = false
        var truncated = portal?.cut ?: false
        var done = false
        var completion: Completion? = null

        fun resolveColumns() {
            if (columns == null && portal != null) {
                columns = portal.columns ?: portal.statement.columns
                formats = portal.resultFormats
            }
        }

        fun addRow(payload: ByteArray) {
            count++
            if (full || rows.size >= limits.maxRows || budget.used >= limits.maxBytes) {
                full = true
                truncated = true
                return
            }
            try {
                val r = PgReader(payload)
                val values =
                    List(r.int16()) { i ->
                        val length = r.int32()
                        if (length < 0) {
                            null
                        } else {
                            keep(columns?.getOrNull(i)?.type ?: 0, formatOf(formats, i), r.slice(length, sliceCap))
                        }
                    }
                val size = values.sumOf { it?.toByteArray(UTF_8)?.size ?: 0 }
                if (budget.used + size > limits.maxBytes) {
                    full = true
                    truncated = true
                    return
                }
                rows += values
                budget.used += size
            } catch (_: Exception) {
                truncated = true
            }
        }

        /** Decodes a value read with [sliceCap], so a value longer than maxBytes arrives cut and is marked so. */
        fun keep(
            type: Int,
            format: Int,
            raw: ByteArray,
        ): String {
            val value = PgValues.decode(type, format, raw, limits.maxBytes)
            val cut = cut(value, limits.maxBytes)
            if (raw.size > limits.maxBytes || cut.length != value.length) truncated = true
            return cut
        }
    }

    private sealed class Pending(
        val at: Instant,
    )

    private class ParseP(
        at: Instant,
        val statement: Statement,
    ) : Pending(at)

    private class BindP(
        at: Instant,
    ) : Pending(at)

    private class DescribeP(
        at: Instant,
        val statement: Statement?,
        val portal: Portal?,
    ) : Pending(at)

    private class CloseP(
        at: Instant,
        val portal: Portal?,
    ) : Pending(at)

    private class ExecuteP(
        at: Instant,
        val run: Run,
    ) : Pending(at)

    /** Ends a request cycle at ReadyForQuery: Sync, a simple Query, or a FunctionCall. */
    private open class Boundary(
        at: Instant,
    ) : Pending(at)

    private inner class SimpleP(
        at: Instant,
        val sql: String,
    ) : Boundary(at) {
        val results = ArrayList<Run>()
        val budget = Budget()
        var current: Run? = null
        var next: Instant? = null
        var failed = false

        fun run(now: Instant): Run =
            current ?: Run(sql, null, budget).also {
                it.start = next ?: maxOf(at, now)
                current = it
            }
    }

    private val sliceCap = if (limits.maxBytes == Int.MAX_VALUE) Int.MAX_VALUE else limits.maxBytes + 1
    private val statements = HashMap<String, Statement>()
    private val portals = HashMap<String, Portal>()
    private val queue = ArrayDeque<Pending>()
    private val suspended = LinkedHashSet<Run>()

    @Synchronized
    fun fromClient(message: PgMessage) {
        try {
            onClient(message, Instant.now())
        } catch (_: Exception) {
            // Malformed or unexpected input: skip the message.
        }
    }

    @Synchronized
    fun fromServer(message: PgMessage) {
        try {
            onServer(message, Instant.now())
        } catch (_: Exception) {
            // Malformed or unexpected input: skip the message.
        }
    }

    private fun onClient(
        m: PgMessage,
        now: Instant,
    ) {
        val r = PgReader(m.payload)
        when (m.type) {
            PG_UNTYPED -> startup(r)
            'Q' -> enqueue(SimpleP(now, r.cstring()), now)
            'P' -> {
                val name = r.cstring()
                val sql = r.cstring()
                val statement = Statement(sql, IntArray(r.int16()) { r.int32() })
                statements[name] = statement
                enqueue(ParseP(now, statement), now)
            }
            'B' -> {
                val portalName = r.cstring()
                val statement = statements[r.cstring()] ?: Statement("", IntArray(0))
                val paramFormats = IntArray(r.int16()) { r.int16() }
                // A frame cut at the reader's cap keeps the parameters that fit.
                val params = ArrayList<ByteArray?>()
                var resultFormats = IntArray(0)
                var cut = m.cut
                try {
                    repeat(r.int16()) { params += r.int32().let { n -> if (n < 0) null else r.slice(n, sliceCap) } }
                    resultFormats = IntArray(r.int16()) { r.int16() }
                } catch (_: IndexOutOfBoundsException) {
                    cut = true
                }
                portals[portalName] = Portal(statement, paramFormats, params, resultFormats, cut)
                enqueue(BindP(now), now)
            }
            'D' -> {
                val kind = r.byte().toChar()
                val name = r.cstring()
                enqueue(DescribeP(now, if (kind == 'S') statements[name] else null, if (kind == 'P') portals[name] else null), now)
            }
            'E' -> {
                val portal = portals[r.cstring()]
                val run = portal?.suspended ?: Run(portal?.statement?.sql ?: "", portal)
                enqueue(ExecuteP(now, run), now)
            }
            'C' -> {
                val kind = r.byte().toChar()
                val name = r.cstring()
                val portal = if (kind == 'P') portals.remove(name) else null
                if (kind == 'S') statements.remove(name)
                enqueue(CloseP(now, portal), now)
            }
            'S', 'F' -> enqueue(Boundary(now), now)
        }
    }

    private fun startup(r: PgReader) {
        if (r.int32() shr 16 != 3) return
        while (r.remaining > 1) {
            val key = r.cstring()
            val value = r.cstring()
            if (key == "application_name") client = value
        }
    }

    private fun onServer(
        m: PgMessage,
        now: Instant,
    ) {
        val r = PgReader(m.payload)
        when (m.type) {
            '1' -> take<ParseP>(now)
            '2' -> take<BindP>(now)
            '3' -> take<CloseP>(now)?.portal?.suspended?.let { finish(it, null, null, now) }
            'n' -> take<DescribeP>(now)
            't' -> (queue.firstOrNull() as? DescribeP)?.statement?.paramTypes = IntArray(r.int16()) { r.int32() }
            'T' -> rowDescription(r, now)
            'D' ->
                active(now)?.let { run ->
                    run.resolveColumns()
                    run.addRow(m.payload)
                }
            'C' -> complete(r.cstring(), now)
            'I' -> emptyQuery(now)
            's' ->
                (queue.firstOrNull() as? ExecuteP)?.let { head ->
                    head.run.portal?.suspended = head.run
                    suspended += head.run
                    pop(now)
                }
            'E' -> error(r, now)
            'Z' -> ready(r.byte().toChar(), now)
            'S' -> if (r.cstring() == "application_name") client = r.cstring()
        }
    }

    private fun rowDescription(
        r: PgReader,
        now: Instant,
    ) {
        val formats = ArrayList<Int>()
        val columns =
            List(r.int16()) {
                val name = r.cstring()
                r.int32()
                r.int16()
                val type = r.int32()
                r.int16()
                r.int32()
                formats += r.int16()
                Column(name, type)
            }
        when (val head = queue.firstOrNull()) {
            is DescribeP -> {
                head.portal?.columns = columns
                head.statement?.columns = columns
                pop(now)
            }
            is SimpleP ->
                head.run(now).let {
                    it.columns = columns
                    it.formats = formats.toIntArray()
                }
            is ExecuteP -> head.run.columns = columns
            else -> Unit
        }
    }

    /** The statement currently receiving rows: the head Execute, or the simple query's current statement. */
    private fun active(now: Instant): Run? =
        when (val head = queue.firstOrNull()) {
            is ExecuteP -> head.run
            is SimpleP -> head.run(now)
            else -> null
        }

    private fun complete(
        tag: String,
        now: Instant,
    ) {
        when (val head = queue.firstOrNull()) {
            is ExecuteP -> {
                pop(now)
                finish(head.run, tag, null, now)
            }
            is SimpleP -> {
                val run = head.run(now)
                head.results += run
                head.current = null
                head.next = now
                run.done = true
                complete(run, tag, null, now)
            }
            else -> Unit
        }
    }

    private fun emptyQuery(now: Instant) {
        when (val head = queue.firstOrNull()) {
            is ExecuteP -> pop(now)
            is SimpleP -> head.current = null
            else -> Unit
        }
    }

    private fun error(
        r: PgReader,
        now: Instant,
    ) {
        var code = ""
        var message = ""
        while (r.remaining > 0) {
            val field = r.byte()
            if (field == 0) break
            val value = r.cstring()
            if (field == 'C'.code) code = value
            if (field == 'M'.code) message = value
        }
        val error = QueryError(code, message)
        val head = queue.firstOrNull()
        if (head is SimpleP) {
            val run = head.run(now)
            head.results += run
            head.current = null
            head.failed = true
            run.done = true
            complete(run, null, error, now)
            return
        }
        if (head == null || head is Boundary) return
        // The server skips everything up to the next Sync: log the first Execute it aborted, or a failed Parse.
        val skipped = ArrayList<Pending>()
        while (queue.isNotEmpty() && queue.first() !is Boundary) skipped += queue.removeFirst()
        val run =
            skipped.firstNotNullOfOrNull { (it as? ExecuteP)?.run }
                ?: skipped.firstNotNullOfOrNull { (it as? ParseP)?.statement }?.let { st ->
                    Run(st.sql, null).also { it.start = skipped.first().at }
                }
        run?.let {
            if (it.start == null) it.start = skipped.first().at
            finish(it, null, error, now)
        }
    }

    private fun ready(
        status: Char,
        now: Instant,
    ) {
        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            if (item is SimpleP) emitSimple(item)
            if (item is Boundary) break
        }
        if (status == 'I') suspended.toList().forEach { finish(it, null, null, now) }
        onHead(now)
    }

    private fun emitSimple(query: SimpleP) {
        val pieces = splitSql(query.sql)
        val labelled = pieces.size > 1 && (pieces.size == query.results.size || query.failed && query.results.size <= pieces.size)
        query.results.forEachIndexed { i, run ->
            if (labelled) run.sql = pieces[i]
            emitRun(run)
        }
    }

    private fun finish(
        run: Run,
        tag: String?,
        error: QueryError?,
        now: Instant,
    ) {
        if (run.done) return
        run.done = true
        suspended -= run
        run.portal?.let { if (it.suspended === run) it.suspended = null }
        complete(run, tag, error, now)
        emitRun(run)
    }

    private fun complete(
        run: Run,
        tag: String?,
        error: QueryError?,
        now: Instant,
    ) {
        run.resolveColumns()
        run.completion = Completion(tag, error, now)
    }

    private fun emitRun(run: Run) {
        val done = run.completion ?: return
        run.completion = null
        val start = run.start ?: done.end
        val parts = done.tag?.split(' ').orEmpty()
        val verb = parts.takeWhile { it.toLongOrNull() == null }.joinToString(" ").ifEmpty { null }
        val number = parts.lastOrNull()?.toLongOrNull()
        val returnsRows = verb == "SELECT" || verb == "FETCH" || run.columns != null
        val entry =
            QueryEntry(
                id = UUID.randomUUID().toString(),
                time = start.toString(),
                durationMs = Duration.between(start, done.end).toNanos() / 1_000_000.0,
                service = service,
                connectionId = connectionId,
                client = client,
                sql = run.sql,
                params = params(run),
                columns = run.columns?.map { it.name },
                rows = if (run.columns != null || run.count > 0) run.rows.toList() else null,
                rowCount = if (returnsRows) (if (run.count > 0) run.count else number ?: run.count) else null,
                affected = if (verb in AFFECTING_VERBS || !returnsRows) number else null,
                command = verb,
                error = done.error,
                truncated = run.truncated,
            )
        try {
            emit(entry)
        } catch (_: Exception) {
            // The sink's failure must not reach the relay either.
        }
    }

    private fun params(run: Run): List<String?> {
        val portal = run.portal ?: return emptyList()
        return portal.params.mapIndexed { i, bytes ->
            bytes?.let { run.keep(portal.statement.paramTypes.getOrElse(i) { 0 }, formatOf(portal.paramFormats, i), it) }
        }
    }

    private fun enqueue(
        item: Pending,
        now: Instant,
    ) {
        queue.addLast(item)
        if (queue.size > MAX_PENDING) queue.removeFirst()
        if (queue.size == 1) onHead(now)
    }

    private fun pop(now: Instant) {
        queue.removeFirstOrNull()
        onHead(now)
    }

    /**
     * Pops the first [T] of this request cycle, dropping requests queued before it that never got their response.
     * Never drops past an Execute or the cycle's end; returns null (queue untouched) when no [T] is reachable.
     */
    private inline fun <reified T : Pending> take(now: Instant): T? {
        val index = queue.indexOfFirst { it is T || it is ExecuteP || it is Boundary }
        if (index < 0 || queue[index] !is T) return null
        repeat(index) { queue.removeFirst() }
        return (queue.removeFirst() as T).also { onHead(now) }
    }

    // A pipelined statement starts when the server gets to it: when it was sent, or when the one before it finished.
    private fun onHead(now: Instant) {
        when (val head = queue.firstOrNull()) {
            is ExecuteP -> if (head.run.start == null) head.run.start = maxOf(head.at, now)
            is SimpleP -> if (head.next == null) head.next = maxOf(head.at, now)
            else -> Unit
        }
    }

    private companion object {
        const val MAX_PENDING = 10_000
        val AFFECTING_VERBS = setOf("INSERT", "UPDATE", "DELETE", "MERGE", "COPY", "MOVE")

        fun formatOf(
            formats: IntArray,
            i: Int,
        ) = when (formats.size) {
            0 -> 0
            1 -> formats[0]
            else -> formats.getOrElse(i) { 0 }
        }

        fun cut(
            value: String,
            maxBytes: Int,
        ): String {
            val bytes = value.toByteArray(UTF_8)
            if (bytes.size <= maxBytes) return value
            val decoder = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE)
            val out = CharBuffer.allocate(maxBytes)
            decoder.decode(ByteBuffer.wrap(bytes, 0, maxBytes), out, true)
            return out.flip().toString()
        }
    }
}

/**
 * Splits a simple-protocol query string at top-level semicolons, skipping quoted strings, quoted identifiers,
 * dollar-quoted bodies and comments. Only used to label each result; the server's responses decide the entries.
 */
internal fun splitSql(sql: String): List<String> {
    val pieces = ArrayList<String>()
    var start = 0
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        val next = sql.getOrNull(i + 1)
        i =
            when {
                c == '\'' -> skipQuoted(sql, i, '\'', escapes = i > 0 && sql[i - 1] in "eE")
                c == '"' -> skipQuoted(sql, i, '"', escapes = false)
                c == '-' && next == '-' -> sql.indexOf('\n', i).let { if (it < 0) sql.length else it + 1 }
                c == '/' && next == '*' -> sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                c == '$' && (i == 0 || !sql[i - 1].isLetterOrDigit() && sql[i - 1] != '_') -> skipDollar(sql, i)
                c == ';' -> {
                    pieces += sql.substring(start, i).trim()
                    start = i + 1
                    i + 1
                }
                else -> i + 1
            }
    }
    pieces += sql.substring(minOf(start, sql.length)).trim()
    return pieces.filter { it.isNotEmpty() }
}

private fun skipQuoted(
    sql: String,
    from: Int,
    quote: Char,
    escapes: Boolean,
): Int {
    var i = from + 1
    while (i < sql.length) {
        val c = sql[i]
        when {
            escapes && c == '\\' -> i += 2
            c == quote && sql.getOrNull(i + 1) == quote -> i += 2
            c == quote -> return i + 1
            else -> i++
        }
    }
    return sql.length
}

private val dollarTag = Regex("""\$([A-Za-z_][A-Za-z_0-9]*)?\$""")

private fun skipDollar(
    sql: String,
    from: Int,
): Int {
    val tag = dollarTag.matchAt(sql, from)?.value ?: return from + 1
    val end = sql.indexOf(tag, from + tag.length)
    return if (end < 0) sql.length else end + tag.length
}
