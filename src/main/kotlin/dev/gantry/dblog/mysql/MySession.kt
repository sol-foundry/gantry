package dev.gantry.dblog.mysql

import dev.gantry.dblog.QueryEntry
import dev.gantry.dblog.QueryError
import dev.gantry.dblog.capped
import dev.gantry.dblog.postgres.Limits
import dev.gantry.dblog.utf8Prefix
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Turns one MySQL connection's traffic, both directions, into [QueryEntry]s passed to [emit]: text queries (each
 * result of a multi-result query is its own entry), and prepared statements, one entry per execute with its decoded
 * parameters. An error's [QueryError.code] is `<errno>/<SQLSTATE>` (just the errno when the server sends no state).
 * Requests queue as the client sends them and the server's responses are matched in order. The two directions may
 * call in from different threads. Unexpected input is ignored, never thrown: a parser bug must not break the relay.
 */
class MySession(
    private val service: String,
    private val connectionId: Long,
    private val limits: Limits,
    private val emit: (QueryEntry) -> Unit,
) {
    /** The client's `program_name` connection attribute. */
    @Volatile
    var client: String? = null

    private class Column(
        val name: String,
        val type: Int,
        val charset: Int,
        val unsigned: Boolean,
    )

    private class Statement(
        val sql: String,
        val paramCount: Int,
        val cut: Boolean,
    ) {
        /** Parameter types from the last execute that sent them: the type in the low byte, 0x8000 when unsigned. */
        var types: IntArray? = null
        val longData = HashMap<Int, ByteArrayOutputStream>()
        var longDataBytes = 0
        var longDataCut = false
    }

    /** One statement's execution, collecting its rows until it completes. */
    private inner class Run(
        val sql: String,
        val params: List<String?>,
        var truncated: Boolean,
        val start: Instant,
    ) {
        var columns: ArrayList<Column>? = null
        val rows = ArrayList<List<String?>>()
        var count = 0L
        var used = 0L
        var full = false

        fun addRow(
            packet: MyPacket,
            binary: Boolean,
        ) {
            count++
            if (full || rows.size >= limits.maxRows || used >= limits.maxBytes) {
                full = true
                truncated = true
                return
            }
            if (packet.cut) truncated = true
            val cols = columns ?: return
            try {
                val r = MyReader(packet.payload)
                val values =
                    if (binary) {
                        r.byte()
                        val nulls = r.bytes((cols.size + 9) / 8)
                        cols.mapIndexed { i, c ->
                            if (bit(nulls, i + 2)) {
                                null
                            } else {
                                keep(MyValues.readBinary(r, c.type, c.unsigned, MyValues.columnBytes(c.type, c.charset), limits.maxBytes))
                            }
                        }
                    } else {
                        cols.map { keep(MyValues.readText(r, MyValues.columnBytes(it.type, it.charset), limits.maxBytes)) }
                    }
                val size = values.sumOf { it?.toByteArray(UTF_8)?.size ?: 0 }
                if (used + size > limits.maxBytes) {
                    full = true
                    truncated = true
                    return
                }
                rows += values
                used += size
            } catch (_: Exception) {
                truncated = true
            }
        }

        fun keep(value: MyValue): String? {
            val (text, cut) = fit(value)
            if (cut) truncated = true
            return text
        }
    }

    private enum class Stage { FIRST, COLUMNS, COLUMNS_EOF, ROWS }

    private sealed class Pending {
        var start: Instant? = null
    }

    /** A request whose response is one or more results: a text query or a prepared statement's execute. */
    private abstract inner class Results : Pending() {
        abstract val binary: Boolean
        var stage = Stage.FIRST
        var remaining = 0L
        var run: Run? = null
        var index = 0
        var next: Instant? = null

        abstract fun newRun(start: Instant): Run

        fun begin(now: Instant): Run = newRun(next ?: start ?: now)
    }

    private inner class Query(
        val sql: String,
        val cut: Boolean,
    ) : Results() {
        override val binary = false
        private val pieces by lazy { if (caps and Caps.MULTI_STATEMENTS != 0L) splitMySql(sql) else listOf(sql) }

        // A multi-statement query's results are labelled with their statement while the counts line up.
        override fun newRun(start: Instant) =
            Run(
                if (pieces.size > 1 &&
                    index < pieces.size
                ) {
                    pieces[index]
                } else {
                    sql
                },
                emptyList(),
                cut,
                start,
            )
    }

    private inner class Execute(
        val packet: MyPacket,
    ) : Results() {
        override val binary = true
        private var resolved: Run? = null

        // Decoded only once the response arrives, so the statement's prepare response has been seen whichever thread ran first.
        override fun newRun(start: Instant): Run {
            val first = resolved ?: resolve(packet, start).also { resolved = it }
            return if (index == 0) first else Run(first.sql, first.params, first.truncated, start)
        }
    }

    private inner class Prepare(
        val sql: String,
        val cut: Boolean,
    ) : Pending() {
        var skipping = false
        var remaining = 0
    }

    /** COM_STMT_FETCH or COM_FIELD_LIST: rows or definitions up to an EOF, not logged. */
    private class Fetch : Pending()

    /** A command answered by a single packet; [onOk] runs when it is an OK. */
    private class Reply(
        val onOk: () -> Unit = {},
    ) : Pending()

    private class ChangeUser : Pending()

    /** A command with no response, applied in order when it reaches the head of the queue. */
    private class Action(
        val apply: () -> Unit,
    ) : Pending()

    private val sliceCap = if (limits.maxBytes == Int.MAX_VALUE) Int.MAX_VALUE else limits.maxBytes + 1
    private val statements =
        object : LinkedHashMap<Int, Statement>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Statement>?) = size > MAX_STATEMENTS
        }
    private val queue = ArrayDeque<Pending>()
    private var greeted = false
    private var authenticated = false
    private var loggedIn = false
    private var serverCaps = 0L
    private var clientCaps = 0L
    private var lastCommand = -1
    private var sendingData = false
    private var closed = false

    private val caps get() = serverCaps and clientCaps
    private val deprecateEof get() = caps and Caps.DEPRECATE_EOF != 0L
    private val queryAttributes get() = caps and Caps.QUERY_ATTRIBUTES != 0L

    @Synchronized
    fun fromClient(packet: MyPacket) {
        if (closed) return
        try {
            onClient(packet, Instant.now())
        } catch (_: Exception) {
            // Malformed or unexpected input: skip the packet.
        }
    }

    @Synchronized
    fun fromServer(packet: MyPacket) {
        if (closed) return
        try {
            onServer(packet, Instant.now())
        } catch (_: Exception) {
            // Malformed or unexpected input: skip the packet.
        }
    }

    /** The connection dropped: logs the statement in flight, marked truncated. Later packets are ignored. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        try {
            val head = queue.firstOrNull()
            queue.clear()
            if (head is Results) {
                val now = Instant.now()
                val run = head.run ?: if (head.stage == Stage.FIRST && head.index == 0) head.begin(now) else null
                run?.let {
                    it.truncated = true
                    finish(it, null, null, now)
                }
            }
        } catch (_: Exception) {
            // Nothing more to log.
        }
    }

    private fun onClient(
        p: MyPacket,
        now: Instant,
    ) {
        if (!loggedIn) return login(p)
        // Auth exchanges and LOCAL INFILE data continue a sequence; only a command restarts it at 0. Infile data
        // can wrap its sequence back to 0, so once it flows everything up to its empty closing packet is data.
        if (sendingData) {
            if (p.payload.isEmpty()) sendingData = false
            return
        }
        if (p.seq != 0) {
            if (lastCommand == COM_QUERY && p.payload.isNotEmpty()) sendingData = true
            return
        }
        val r = MyReader(p.payload)
        lastCommand = r.byte()
        when (lastCommand) {
            COM_QUERY -> enqueue(querySql(r, p), now)
            COM_STMT_PREPARE -> enqueue(Prepare(r.rest(), p.cut), now)
            COM_STMT_EXECUTE -> enqueue(Execute(p), now)
            COM_STMT_FETCH, COM_FIELD_LIST -> enqueue(Fetch(), now)
            COM_INIT_DB, COM_PING, COM_SET_OPTION, COM_STATISTICS -> enqueue(Reply(), now)
            COM_STMT_RESET -> r.int32().let { id -> enqueue(Reply { statements[id]?.clearLongData() }, now) }
            COM_RESET_CONNECTION -> enqueue(Reply { statements.clear() }, now)
            COM_CHANGE_USER -> enqueue(ChangeUser(), now)
            COM_STMT_CLOSE -> r.int32().let { id -> enqueue(Action { statements.remove(id) }, now) }
            COM_STMT_SEND_LONG_DATA -> {
                val id = r.int32()
                val param = r.int16()
                val data = r.slice(r.remaining.toLong(), sliceCap)
                val cut = p.cut || r.remaining > 0
                enqueue(Action { statements[id]?.addLongData(param, data, cut) }, now)
            }
        }
    }

    private fun login(p: MyPacket) {
        if (MyHandshake.isSslRequest(p.payload)) return
        val r = MyReader(p.payload)
        clientCaps = r.int32().toLong() and 0xFFFFFFFFL
        loggedIn = true
        r.int32()
        r.byte()
        r.skip(23)
        r.cstring()
        when {
            clientCaps and Caps.LENENC_DATA != 0L -> r.skip(r.lenenc())
            clientCaps and Caps.SECURE_CONNECTION != 0L -> r.skip(r.byte().toLong())
            else -> r.cstring()
        }
        if (clientCaps and Caps.CONNECT_WITH_DB != 0L) r.cstring()
        if (clientCaps and Caps.PLUGIN_AUTH != 0L) r.cstring()
        if (clientCaps and Caps.CONNECT_ATTRS == 0L) return
        val end = r.lenenc() + r.pos
        while (r.pos < end) {
            val key = r.lenencString()
            val value = r.lenencString()
            if (key == "program_name") client = value
        }
    }

    /** A COM_QUERY's SQL, after any query attributes ahead of it. */
    private fun querySql(
        r: MyReader,
        p: MyPacket,
    ): Query {
        try {
            if (queryAttributes) {
                val count = r.lenenc().toInt()
                r.lenenc()
                if (count > 0) {
                    val nulls = r.bytes((count + 7) / 8)
                    if (r.byte() != 1) return Query("", true)
                    val types =
                        IntArray(count) {
                            r.int16().also { r.skip(r.lenenc()) }
                        }
                    types.forEachIndexed { i, t -> if (!bit(nulls, i)) MyValues.binarySize(r, t and 0xFF) }
                }
            }
            return Query(r.rest(), p.cut)
        } catch (_: Exception) {
            return Query("", true)
        }
    }

    private fun onServer(
        p: MyPacket,
        now: Instant,
    ) {
        val first =
            p.payload
                .firstOrNull()
                ?.toInt()
                ?.and(0xFF) ?: return
        if (!greeted) {
            greeted = true
            serverCaps = MyHandshake.greetingCapabilities(p.payload)
            return
        }
        if (!authenticated) {
            // Auth switch (0xFE) and more-data (0x01) packets continue the exchange; OK ends it.
            if (first == 0x00) authenticated = true
            return
        }
        val head = queue.firstOrNull() ?: return
        val done =
            try {
                respond(head, p, first, now)
            } catch (_: Exception) {
                runCatching { abandon(head, now) }
                true
            }
        if (done) pop(now)
    }

    /** Applies one response packet to [head]; true once its response is complete. */
    private fun respond(
        head: Pending,
        p: MyPacket,
        first: Int,
        now: Instant,
    ): Boolean =
        when (head) {
            is Results -> results(head, p, first, now)
            is Prepare -> prepared(head, p, first, now)
            is Fetch -> first == 0xFF || isEnd(p, first)
            is Reply -> true.also { if (first == 0x00) head.onOk() }
            is ChangeUser -> (first == 0x00 || first == 0xFF).also { if (first == 0x00) statements.clear() }
            is Action -> true
        }

    private fun results(
        head: Results,
        p: MyPacket,
        first: Int,
        now: Instant,
    ): Boolean {
        when (head.stage) {
            Stage.FIRST ->
                when (first) {
                    0x00 -> {
                        val ok = ok(p)
                        finish(head.begin(now), ok.affected, null, now)
                        return more(head, ok.status, now)
                    }
                    0xFF -> {
                        finish(head.begin(now), null, error(p), now)
                        return true
                    }
                    // LOCAL INFILE request: the client sends the file, then the server answers OK or ERR.
                    0xFB -> return false
                    else -> {
                        val count = MyReader(p.payload).lenenc()
                        val run = head.begin(now)
                        run.columns = ArrayList(minOf(count, MAX_COLUMNS).toInt())
                        head.run = run
                        head.remaining = count
                        head.stage = if (count > 0) Stage.COLUMNS else afterColumns()
                    }
                }
            Stage.COLUMNS -> {
                val run = head.run!!
                run.columns!! += column(p)
                if (--head.remaining == 0L) head.stage = afterColumns()
            }
            Stage.COLUMNS_EOF -> {
                if (first == 0xFF) return failed(head, p, now)
                val status = eofStatus(p)
                head.stage = Stage.ROWS
                // A cursor was opened: the rows come later through COM_STMT_FETCH.
                if (status and SERVER_STATUS_CURSOR_EXISTS != 0) {
                    finish(head.run!!, null, null, now)
                    head.run = null
                    return true
                }
            }
            Stage.ROWS ->
                when {
                    isEnd(p, first) -> {
                        val status = if (deprecateEof) ok(p).status else eofStatus(p)
                        finish(head.run!!, null, null, now)
                        head.run = null
                        return more(head, status, now)
                    }
                    first == 0xFF -> return failed(head, p, now)
                    else -> head.run!!.addRow(p, head.binary)
                }
        }
        return false
    }

    private fun afterColumns() = if (deprecateEof) Stage.ROWS else Stage.COLUMNS_EOF

    private fun failed(
        head: Results,
        p: MyPacket,
        now: Instant,
    ): Boolean {
        finish(head.run!!, null, error(p), now)
        head.run = null
        return true
    }

    private fun more(
        head: Results,
        status: Int,
        now: Instant,
    ): Boolean {
        if (status and SERVER_MORE_RESULTS_EXISTS == 0) return true
        head.stage = Stage.FIRST
        head.index++
        head.next = now
        return false
    }

    /** An EOF packet, or with CLIENT_DEPRECATE_EOF the OK that replaces it (a row can start with 0xFE only past 16 MB). */
    private fun isEnd(
        p: MyPacket,
        first: Int,
    ) = first == 0xFE && p.length < (if (deprecateEof) MyPackets.MAX_CHUNK.toLong() else 9L)

    private fun prepared(
        head: Prepare,
        p: MyPacket,
        first: Int,
        now: Instant,
    ): Boolean {
        if (head.skipping) return --head.remaining <= 0
        when (first) {
            0x00 -> {
                val r = MyReader(p.payload)
                r.byte()
                val id = r.int32()
                val columns = r.int16()
                val params = r.int16()
                statements[id] = Statement(head.sql, params, head.cut)
                val eof = if (deprecateEof) 0 else 1
                head.remaining = params + (if (params > 0) eof else 0) + columns + (if (columns > 0) eof else 0)
                head.skipping = true
                return head.remaining == 0
            }
            0xFF -> finish(Run(head.sql, emptyList(), head.cut, head.start ?: now), null, error(p), now)
        }
        return true
    }

    /** A response that made no sense: log what was gathered as truncated and move on. */
    private fun abandon(
        head: Pending,
        now: Instant,
    ) {
        if (head !is Results) return
        val run = head.run ?: if (head.stage == Stage.FIRST) head.begin(now) else return
        run.truncated = true
        finish(run, null, null, now)
        head.run = null
    }

    private fun resolve(
        packet: MyPacket,
        start: Instant,
    ): Run {
        val r = MyReader(packet.payload)
        val st = runCatching { r.byte().let { statements[r.int32()] } }.getOrNull() ?: return Run("", emptyList(), packet.cut, start)
        val params = ArrayList<String?>()
        var cut = packet.cut || st.cut || st.longDataCut
        try {
            val flags = r.byte()
            r.int32()
            val count = if (queryAttributes && flags and PARAMETER_COUNT_AVAILABLE != 0) r.lenenc().toInt() else st.paramCount
            if (count > 0) {
                val nulls = r.bytes((count + 7) / 8)
                val types =
                    if (r.byte() == 1) {
                        IntArray(count) { r.int16().also { if (queryAttributes) r.skip(r.lenenc()) } }.also { st.types = it }
                    } else {
                        st.types ?: IntArray(0)
                    }
                if (types.size < minOf(count, st.paramCount)) cut = true
                for (i in 0 until minOf(count, st.paramCount, types.size)) {
                    val type = types[i] and 0xFF
                    val long = st.longData[i]
                    val value =
                        when {
                            bit(nulls, i) -> MyValue(null, false)
                            long != null ->
                                long.toByteArray().let {
                                    MyValue(
                                        MyValues.render(
                                            it,
                                            MyValues.paramBytes(type),
                                            limits.maxBytes,
                                            it.size > limits.maxBytes,
                                        ),
                                        it.size > limits.maxBytes,
                                    )
                                }
                            else -> MyValues.readBinary(r, type, types[i] and 0x8000 != 0, MyValues.paramBytes(type), limits.maxBytes)
                        }
                    val (text, valueCut) = fit(value)
                    if (valueCut) cut = true
                    params += text
                }
            }
        } catch (_: Exception) {
            cut = true
        }
        st.clearLongData()
        return Run(st.sql, params, cut, start)
    }

    private fun Statement.addLongData(
        param: Int,
        data: ByteArray,
        cut: Boolean,
    ) {
        if (cut) longDataCut = true
        val room = sliceCap - longDataBytes
        if (room <= 0) {
            longDataCut = true
            return
        }
        val n = minOf(room, data.size)
        longData.getOrPut(param) { ByteArrayOutputStream() }.write(data, 0, n)
        longDataBytes += n
        if (n < data.size) longDataCut = true
    }

    private fun Statement.clearLongData() {
        longData.clear()
        longDataBytes = 0
        longDataCut = false
    }

    private fun finish(
        run: Run,
        affected: Long?,
        error: QueryError?,
        now: Instant,
    ) {
        val command = keyword(run.sql)
        val entry =
            QueryEntry(
                id = UUID.randomUUID().toString(),
                time = run.start.toString(),
                durationMs = Duration.between(run.start, now).toNanos() / 1_000_000.0,
                service = service,
                connectionId = connectionId,
                client = client,
                sql = run.sql,
                params = run.params,
                columns = run.columns?.map { it.name },
                rows = run.columns?.let { run.rows.toList() },
                rowCount = run.columns?.let { run.count },
                affected = if (command in AFFECTING) affected else null,
                command = command,
                error = error,
                truncated = run.truncated,
            )
        try {
            emit(entry.capped(limits.maxBytes))
        } catch (_: Exception) {
            // The sink's failure must not reach the relay either.
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

    private fun onHead(now: Instant) {
        while (true) {
            val head = queue.firstOrNull() ?: return
            if (head !is Action) {
                if (head.start == null) head.start = now
                return
            }
            queue.removeFirst()
            head.apply()
        }
    }

    private class Ok(
        val affected: Long,
        val status: Int,
    )

    private fun ok(p: MyPacket): Ok {
        val r = MyReader(p.payload)
        r.byte()
        val affected = r.lenenc()
        r.lenenc()
        return Ok(affected, if (r.remaining >= 2) r.int16() else 0)
    }

    private fun eofStatus(p: MyPacket): Int {
        val r = MyReader(p.payload)
        r.byte()
        if (r.remaining < 4) return 0
        r.int16()
        return r.int16()
    }

    private fun error(p: MyPacket): QueryError {
        val r = MyReader(p.payload)
        r.byte()
        val errno = r.int16()
        val state = if (r.peek() == '#'.code) String(r.bytes(6), 1, 5, UTF_8) else null
        return QueryError(if (state != null) "$errno/$state" else "$errno", r.rest())
    }

    private fun column(p: MyPacket): Column {
        val r = MyReader(p.payload)
        repeat(4) { r.skip(r.lenenc()) }
        val name = r.lenencString()
        r.skip(r.lenenc())
        r.lenenc()
        val charset = r.int16()
        r.int32()
        val type = r.byte()
        val flags = r.int16()
        return Column(name, type, charset, flags and UNSIGNED_FLAG != 0)
    }

    /** [value] cut to maxBytes of UTF-8, and whether anything was lost. */
    private fun fit(value: MyValue): Pair<String?, Boolean> {
        val text = value.text ?: return null to value.cut
        val cut = utf8Prefix(text, limits.maxBytes)
        return cut to (value.cut || cut.length != text.length)
    }

    private companion object {
        const val COM_INIT_DB = 0x02
        const val COM_QUERY = 0x03
        const val COM_FIELD_LIST = 0x04
        const val COM_STATISTICS = 0x09
        const val COM_PING = 0x0E
        const val COM_CHANGE_USER = 0x11
        const val COM_STMT_PREPARE = 0x16
        const val COM_STMT_EXECUTE = 0x17
        const val COM_STMT_SEND_LONG_DATA = 0x18
        const val COM_STMT_CLOSE = 0x19
        const val COM_STMT_RESET = 0x1A
        const val COM_SET_OPTION = 0x1B
        const val COM_STMT_FETCH = 0x1C
        const val COM_RESET_CONNECTION = 0x1F

        const val SERVER_MORE_RESULTS_EXISTS = 0x0008
        const val SERVER_STATUS_CURSOR_EXISTS = 0x0040
        const val PARAMETER_COUNT_AVAILABLE = 0x08
        const val UNSIGNED_FLAG = 0x20

        const val MAX_PENDING = 10_000
        const val MAX_STATEMENTS = 16_384
        const val MAX_COLUMNS = 4096L
        val AFFECTING = setOf("INSERT", "UPDATE", "DELETE", "REPLACE")

        fun bit(
            bitmap: ByteArray,
            i: Int,
        ) = bitmap[i / 8].toInt() shr (i % 8) and 1 == 1
    }
}

/** The first SQL keyword of [sql], upper-cased, after leading whitespace, parentheses and comments. */
internal fun keyword(sql: String): String? {
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        i =
            when {
                c.isWhitespace() || c == '(' -> i + 1
                c == '#' || sql.startsWith("--", i) -> sql.indexOf('\n', i).let { if (it < 0) sql.length else it + 1 }
                sql.startsWith("/*", i) -> sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                else -> break
            }
    }
    var end = i
    while (end < sql.length && sql[end].isLetter()) end++
    return if (end > i) sql.substring(i, end).uppercase() else null
}

/**
 * Splits a multi-statement query at top-level semicolons, skipping quoted strings and identifiers (with backslash
 * escapes) and comments. Only used to label each result; the server's responses decide the entries.
 */
internal fun splitMySql(sql: String): List<String> {
    val pieces = ArrayList<String>()
    var start = 0
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        i =
            when {
                c == '\'' || c == '"' || c == '`' -> skipMyQuoted(sql, i, escapes = c != '`')
                c == '#' || sql.startsWith("--", i) -> sql.indexOf('\n', i).let { if (it < 0) sql.length else it + 1 }
                sql.startsWith("/*", i) -> sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
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

private fun skipMyQuoted(
    sql: String,
    from: Int,
    escapes: Boolean,
): Int {
    val quote = sql[from]
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
