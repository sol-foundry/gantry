package dev.gantry.dblog.postgres

import dev.gantry.dblog.QueryEntry
import dev.gantry.dblog.QueryError
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val INT4 = 23
private const val INT8 = 20
private const val TEXT = 25
private const val BOOL = 16
private const val UUID_OID = 2950
private const val NUMERIC = 1700
private const val TIMESTAMPTZ = 1184

/** Builders for the wire messages the tests replay through a session. */
internal object Pg {
    private fun msg(
        type: Char,
        build: PgPayload.() -> Unit = {},
    ) = PgMessage(type, PgPayload().apply(build).toByteArray())

    fun startup(vararg params: Pair<String, String>) =
        msg('\u0000') {
            int32(196608)
            params.forEach { (k, v) -> cstring(k).cstring(v) }
            byte(0)
        }

    fun query(sql: String) = msg('Q') { cstring(sql) }

    fun parse(
        name: String,
        sql: String,
        vararg types: Int,
    ) = msg('P') {
        cstring(name).cstring(sql).int16(types.size)
        types.forEach { int32(it) }
    }

    /** Binds [params] (null = SQL NULL); each is text unless it is a [ByteArray], which goes binary. */
    fun bind(
        portal: String,
        statement: String,
        vararg params: Any?,
        resultFormats: List<Int> = emptyList(),
    ) = msg('B') {
        cstring(portal).cstring(statement)
        int16(params.size)
        params.forEach { int16(if (it is ByteArray) 1 else 0) }
        int16(params.size)
        params.forEach {
            when (it) {
                null -> int32(-1)
                is ByteArray -> int32(it.size).bytes(it)
                else -> it.toString().toByteArray().let { b -> int32(b.size).bytes(b) }
            }
        }
        int16(resultFormats.size)
        resultFormats.forEach { int16(it) }
    }

    fun describe(
        kind: Char,
        name: String,
    ) = msg('D') { byte(kind.code).cstring(name) }

    fun execute(
        portal: String,
        maxRows: Int = 0,
    ) = msg('E') { cstring(portal).int32(maxRows) }

    fun close(
        kind: Char,
        name: String,
    ) = msg('C') { byte(kind.code).cstring(name) }

    fun sync() = msg('S')

    fun parseComplete() = msg('1')

    fun bindComplete() = msg('2')

    fun noData() = msg('n')

    fun portalSuspended() = msg('s')

    fun rowDescription(vararg columns: Pair<String, Int>) =
        msg('T') {
            int16(columns.size)
            columns.forEach { (name, type) ->
                cstring(name)
                    .int32(0)
                    .int16(0)
                    .int32(type)
                    .int16(-1)
                    .int32(-1)
                    .int16(0)
            }
        }

    fun dataRow(vararg values: Any?) =
        msg('D') {
            int16(values.size)
            values.forEach {
                when (it) {
                    null -> int32(-1)
                    is ByteArray -> int32(it.size).bytes(it)
                    else -> it.toString().toByteArray().let { b -> int32(b.size).bytes(b) }
                }
            }
        }

    fun commandComplete(tag: String) = msg('C') { cstring(tag) }

    fun error(
        code: String,
        message: String,
    ) = msg('E') {
        byte('S'.code).cstring("ERROR")
        byte('C'.code).cstring(code)
        byte('M'.code).cstring(message)
        byte(0)
    }

    fun ready(status: Char = 'I') = msg('Z') { byte(status.code) }

    fun int4(v: Int) = PgPayload().int32(v).toByteArray()

    fun int8(v: Long) = PgPayload().int64(v).toByteArray()
}

class PgSessionTest {
    private val entries = mutableListOf<QueryEntry>()

    private fun session(limits: Limits = Limits(maxRows = 1000, maxBytes = 1_048_576)) = PgSession("postgres", 3, limits) { entries += it }

    private fun PgSession.client(vararg messages: PgMessage) = messages.forEach(::fromClient)

    private fun PgSession.server(vararg messages: PgMessage) = messages.forEach(::fromServer)

    @Test
    fun `a simple query with two statements logs two entries`() {
        val s = session()
        s.client(Pg.query("select 1 as one; update t set a = 'x;y' where b = 2"))
        s.server(
            Pg.rowDescription("one" to INT4),
            Pg.dataRow("1"),
            Pg.commandComplete("SELECT 1"),
            Pg.commandComplete("UPDATE 3"),
            Pg.ready(),
        )

        assertEquals(2, entries.size)
        val (select, update) = entries
        assertEquals("select 1 as one", select.sql)
        assertEquals(listOf("one"), select.columns)
        assertEquals(listOf(listOf<String?>("1")), select.rows)
        assertEquals(1L, select.rowCount)
        assertEquals("SELECT", select.command)
        assertEquals("update t set a = 'x;y' where b = 2", update.sql)
        assertEquals(3L, update.affected)
        assertEquals("UPDATE", update.command)
        assertNull(update.columns)
        assertNull(update.rows)
        assertEquals("postgres", select.service)
        assertEquals(3L, select.connectionId)
        assertTrue(select.id != update.id)
        assertTrue(select.durationMs >= 0)
    }

    @Test
    fun `a simple query whose statements cannot be split logs each result with the whole text`() {
        val s = session()
        val sql = "create function f() returns int begin atomic select 1; select 2; end"
        s.client(Pg.query(sql))
        s.server(Pg.commandComplete("CREATE FUNCTION"), Pg.ready())

        assertEquals(1, entries.size)
        assertEquals(sql, entries[0].sql)
        assertEquals("CREATE FUNCTION", entries[0].command)
    }

    @Test
    fun `an extended query logs SQL, decoded binary parameters, columns and rows`() {
        val s = session()
        val id = UUID.fromString("0b6e5f3c-2a1d-4c9e-9f00-1234567890ab")
        val uuidBytes = PgPayload().int64(id.mostSignificantBits).int64(id.leastSignificantBits).toByteArray()
        val numeric =
            PgPayload()
                .int16(3)
                .int16(1)
                .int16(0)
                .int16(2)
                .int16(1)
                .int16(2345)
                .int16(6700)
                .toByteArray()
        // 2026-11-14T10:00:00Z in microseconds since 2000-01-01.
        val micros =
            (
                java.time.Instant
                    .parse("2026-11-14T10:00:00Z")
                    .epochSecond - 946_684_800L
            ) * 1_000_000L
        s.client(
            Pg.parse(
                "",
                "select id, name from users where id = $1 and n = $2 and ok = $3 and amt = $4 and at = $5 and tag = $6",
                INT8,
                INT4,
                BOOL,
                NUMERIC,
                TIMESTAMPTZ,
                UUID_OID,
            ),
            Pg.bind("", "", Pg.int8(42), Pg.int4(-7), byteArrayOf(1), numeric, Pg.int8(micros), uuidBytes, resultFormats = listOf(1, 0)),
            Pg.describe('P', ""),
            Pg.execute(""),
            Pg.sync(),
        )
        s.server(
            Pg.parseComplete(),
            Pg.bindComplete(),
            Pg.rowDescription("id" to INT8, "name" to TEXT),
            Pg.dataRow(Pg.int8(42), "Ada"),
            Pg.dataRow(Pg.int8(43), null),
            Pg.commandComplete("SELECT 2"),
            Pg.ready(),
        )

        val e = entries.single()
        assertTrue(e.sql.startsWith("select id, name from users"))
        assertEquals(listOf("42", "-7", "true", "12345.67", "2026-11-14 10:00:00+00", id.toString()), e.params)
        assertEquals(listOf("id", "name"), e.columns)
        assertEquals(listOf(listOf("42", "Ada"), listOf("43", null)), e.rows)
        assertEquals(2L, e.rowCount)
        assertEquals("SELECT", e.command)
        assertNull(e.error)
        assertEquals(false, e.truncated)
    }

    @Test
    fun `a reused named statement logs its own SQL with each execution's parameters`() {
        val s = session()
        s.client(
            Pg.parse("S_1", "insert into t(a) values ($1)", INT4),
            Pg.bind("", "S_1", "1"),
            Pg.execute(""),
            Pg.sync(),
        )
        s.server(Pg.parseComplete(), Pg.bindComplete(), Pg.commandComplete("INSERT 0 1"), Pg.ready())
        s.client(Pg.parse("", "select 99"), Pg.bind("", ""), Pg.execute(""), Pg.sync())
        s.server(Pg.parseComplete(), Pg.bindComplete(), Pg.dataRow("99"), Pg.commandComplete("SELECT 1"), Pg.ready())
        s.client(Pg.bind("", "S_1", Pg.int4(2)), Pg.execute(""), Pg.sync())
        s.server(Pg.bindComplete(), Pg.commandComplete("INSERT 0 1"), Pg.ready())

        assertEquals(3, entries.size)
        assertEquals("insert into t(a) values ($1)", entries[0].sql)
        assertEquals(listOf<String?>("1"), entries[0].params)
        assertEquals(1L, entries[0].affected)
        assertEquals("INSERT", entries[0].command)
        assertEquals("select 99", entries[1].sql)
        assertEquals("insert into t(a) values ($1)", entries[2].sql)
        assertEquals(listOf<String?>("2"), entries[2].params)
    }

    @Test
    fun `a closed statement is forgotten`() {
        val s = session()
        s.client(Pg.parse("S_1", "select 1"), Pg.close('S', "S_1"), Pg.bind("", "S_1"), Pg.execute(""), Pg.sync())
        s.server(Pg.parseComplete(), Pg.msg3(), Pg.error("26000", "prepared statement \"S_1\" does not exist"), Pg.ready())

        val e = entries.single()
        assertEquals("", e.sql)
        assertEquals(QueryError("26000", "prepared statement \"S_1\" does not exist"), e.error)
    }

    @Test
    fun `pipelined executes are matched to their own results`() {
        val s = session()
        s.client(
            Pg.parse("a", "select $1::int", INT4),
            Pg.parse("b", "delete from t where id = $1", INT4),
            Pg.bind("p1", "a", "10"),
            Pg.bind("p2", "b", "20"),
            Pg.execute("p1"),
            Pg.execute("p2"),
            Pg.bind("p1", "a", "30"),
            Pg.execute("p1"),
            Pg.sync(),
        )
        s.server(
            Pg.parseComplete(),
            Pg.parseComplete(),
            Pg.bindComplete(),
            Pg.bindComplete(),
            Pg.dataRow("10"),
            Pg.commandComplete("SELECT 1"),
            Pg.commandComplete("DELETE 4"),
            Pg.bindComplete(),
            Pg.dataRow("30"),
            Pg.commandComplete("SELECT 1"),
            Pg.ready(),
        )

        assertEquals(3, entries.size)
        assertEquals(listOf<String?>("10"), entries[0].params)
        assertEquals(listOf(listOf<String?>("10")), entries[0].rows)
        assertEquals("delete from t where id = $1", entries[1].sql)
        assertEquals(listOf<String?>("20"), entries[1].params)
        assertEquals(4L, entries[1].affected)
        assertEquals(listOf<String?>("30"), entries[2].params)
        assertEquals(listOf(listOf<String?>("30")), entries[2].rows)
    }

    @Test
    fun `a suspended portal continues the same statement until it completes`() {
        val s = session()
        s.client(Pg.parse("", "select n from big"), Pg.bind("c1", ""), Pg.execute("c1", 2), Pg.sync())
        s.server(Pg.parseComplete(), Pg.bindComplete(), Pg.dataRow("1"), Pg.dataRow("2"), Pg.portalSuspended(), Pg.ready('T'))
        assertTrue(entries.isEmpty())
        s.client(Pg.execute("c1", 2), Pg.sync())
        s.server(Pg.dataRow("3"), Pg.commandComplete("SELECT 1"), Pg.ready('T'))

        val e = entries.single()
        assertEquals("select n from big", e.sql)
        assertEquals(listOf(listOf<String?>("1"), listOf("2"), listOf("3")), e.rows)
        assertEquals(3L, e.rowCount)
    }

    @Test
    fun `rows past the cap are counted, not kept`() {
        val s = session(Limits(maxRows = 2, maxBytes = 1_048_576))
        s.client(Pg.query("select n from t"))
        s.server(Pg.rowDescription("n" to INT4))
        repeat(5) { s.server(Pg.dataRow("$it")) }
        s.server(Pg.commandComplete("SELECT 5"), Pg.ready())

        val e = entries.single()
        assertEquals(listOf(listOf<String?>("0"), listOf("1")), e.rows)
        assertEquals(5L, e.rowCount)
        assertTrue(e.truncated)
    }

    @Test
    fun `values past the byte cap are cut`() {
        val s = session(Limits(maxRows = 1000, maxBytes = 10))
        s.client(Pg.query("select v from t"))
        s.server(Pg.rowDescription("v" to TEXT), Pg.dataRow("x".repeat(50)), Pg.dataRow("y"), Pg.commandComplete("SELECT 2"), Pg.ready())

        val e = entries.single()
        assertEquals(listOf(listOf<String?>("x".repeat(10))), e.rows)
        assertEquals(2L, e.rowCount)
        assertTrue(e.truncated)
    }

    @Test
    fun `an error response is logged with its code and message`() {
        val s = session()
        s.client(Pg.query("select 1; select nope; select 3"))
        s.server(
            Pg.rowDescription("?column?" to INT4),
            Pg.dataRow("1"),
            Pg.commandComplete("SELECT 1"),
            Pg.error("42703", "column \"nope\" does not exist"),
            Pg.ready(),
        )

        assertEquals(2, entries.size)
        assertNull(entries[0].error)
        assertEquals("select nope", entries[1].sql)
        assertEquals(QueryError("42703", "column \"nope\" does not exist"), entries[1].error)
        assertNull(entries[1].command)
    }

    @Test
    fun `an error in a pipeline skips the executes before sync`() {
        val s = session()
        s.client(
            Pg.parse("", "insert into t values ($1)", INT4),
            Pg.bind("", "", "1"),
            Pg.execute(""),
            Pg.parse("", "insert into t values ($1)", INT4),
            Pg.bind("", "", "2"),
            Pg.execute(""),
            Pg.sync(),
            Pg.parse("", "select 1"),
            Pg.bind("", ""),
            Pg.execute(""),
            Pg.sync(),
        )
        s.server(
            Pg.parseComplete(),
            Pg.bindComplete(),
            Pg.error("23505", "duplicate key"),
            Pg.ready(),
            Pg.parseComplete(),
            Pg.bindComplete(),
            Pg.dataRow("1"),
            Pg.commandComplete("SELECT 1"),
            Pg.ready(),
        )

        assertEquals(2, entries.size)
        assertEquals(listOf<String?>("1"), entries[0].params)
        assertEquals("23505", entries[0].error?.code)
        assertEquals("select 1", entries[1].sql)
        assertNull(entries[1].error)
    }

    @Test
    fun `a parse error is logged with the statement's SQL`() {
        val s = session()
        s.client(Pg.parse("S_9", "selec 1"), Pg.describe('S', "S_9"), Pg.sync())
        s.server(Pg.error("42601", "syntax error at or near \"selec\""), Pg.ready())

        val e = entries.single()
        assertEquals("selec 1", e.sql)
        assertEquals("42601", e.error?.code)
    }

    @Test
    fun `the client name comes from application_name`() {
        val s = session()
        s.fromClient(Pg.startup("user" to "app", "database" to "db", "application_name" to "PostgreSQL JDBC Driver"))
        assertEquals("PostgreSQL JDBC Driver", s.client)
        s.client(Pg.query("select 1"))
        s.server(Pg.commandComplete("SELECT 0"), Pg.ready())
        assertEquals("PostgreSQL JDBC Driver", entries.single().client)
    }

    @Test
    fun `a bind cut at the frame cap keeps the parameters that fit`() {
        val s = session()
        val full = Pg.bind("", "S_1", "small", "x".repeat(100)).payload
        s.client(Pg.parse("S_1", "insert into t values ($1, $2)"))
        s.client(PgMessage('B', full.copyOf(30), cut = true), Pg.execute(""), Pg.sync())
        s.server(Pg.parseComplete(), Pg.bindComplete(), Pg.commandComplete("INSERT 0 1"), Pg.ready())

        val e = entries.single()
        assertEquals("insert into t values ($1, $2)", e.sql)
        assertEquals(listOf<String?>("small"), e.params)
        assertTrue(e.truncated)
    }

    @Test
    fun `unexpected input never throws`() {
        val s = session()
        s.fromClient(PgMessage('P', byteArrayOf(1, 2)))
        s.fromClient(PgMessage('B', byteArrayOf()))
        s.fromServer(PgMessage('D', byteArrayOf(0)))
        s.fromServer(PgMessage('C', byteArrayOf()))
        s.fromServer(PgMessage('?', byteArrayOf(9)))
        s.fromServer(Pg.ready())
        s.client(Pg.query("select 1"))
        s.server(Pg.commandComplete("SELECT 0"), Pg.ready())
        assertEquals("select 1", entries.last().sql)
    }
}

private fun Pg.msg3() = PgMessage('3', ByteArray(0))
