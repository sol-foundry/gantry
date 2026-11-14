package dev.gantry.dblog.mysql

import dev.gantry.dblog.QueryEntry
import dev.gantry.dblog.QueryError
import dev.gantry.dblog.postgres.Limits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builders for the MySQL packets the tests replay through a session or a proxy. */
internal object My {
    const val LONG_PASSWORD = 0x1L
    const val CONNECT_WITH_DB = 0x8L
    const val COMPRESS = 0x20L
    const val PROTOCOL_41 = 0x200L
    const val SSL = 0x800L
    const val TRANSACTIONS = 0x2000L
    const val SECURE_CONNECTION = 0x8000L
    const val MULTI_STATEMENTS = 0x10000L
    const val MULTI_RESULTS = 0x20000L
    const val PLUGIN_AUTH = 0x80000L
    const val CONNECT_ATTRS = 0x100000L
    const val LENENC_DATA = 0x200000L
    const val DEPRECATE_EOF = 0x1000000L
    const val QUERY_ATTRIBUTES = 0x8000000L

    const val BASE =
        LONG_PASSWORD or CONNECT_WITH_DB or PROTOCOL_41 or TRANSACTIONS or SECURE_CONNECTION or MULTI_STATEMENTS or
            MULTI_RESULTS or PLUGIN_AUTH or CONNECT_ATTRS or LENENC_DATA
    const val CLIENT = BASE

    const val MORE_RESULTS = 0x0008
    const val AUTOCOMMIT = 0x0002

    const val T_DECIMAL = 0x00
    const val T_TINY = 0x01
    const val T_SHORT = 0x02
    const val T_LONG = 0x03
    const val T_FLOAT = 0x04
    const val T_DOUBLE = 0x05
    const val T_NULL = 0x06
    const val T_LONGLONG = 0x08
    const val T_DATE = 0x0A
    const val T_TIME = 0x0B
    const val T_DATETIME = 0x0C
    const val T_JSON = 0xF5
    const val T_NEWDECIMAL = 0xF6
    const val T_BLOB = 0xFC
    const val T_VAR_STRING = 0xFD

    const val UTF8MB4 = 255
    const val BINARY = 63

    fun packet(
        seq: Int,
        build: MyPayload.() -> Unit,
    ) = MyPacket(seq, MyPayload().apply(build).toByteArray())

    fun greeting(caps: Long) =
        packet(0) {
            byte(10)
                .cstring("8.4.0")
                .int32(7)
                .bytes(ByteArray(8) { 1 })
                .byte(0)
            int16((caps and 0xFFFF).toInt()).byte(UTF8MB4).int16(AUTOCOMMIT).int16((caps shr 16 and 0xFFFF).toInt())
            byte(21)
                .bytes(ByteArray(10))
                .bytes(ByteArray(12) { 2 })
                .byte(0)
                .cstring("caching_sha2_password")
        }

    fun sslRequest(
        caps: Long,
        seq: Int = 1,
    ) = packet(seq) { int32(caps.toInt()).int32(0x1000000).byte(UTF8MB4).bytes(ByteArray(23)) }

    fun login(
        caps: Long,
        seq: Int = 1,
        attrs: List<Pair<String, String>> = emptyList(),
    ) = packet(seq) {
        int32(caps.toInt()).int32(0x1000000).byte(UTF8MB4).bytes(ByteArray(23))
        cstring("app")
        lenencBytes(ByteArray(32) { 5 })
        if (caps and CONNECT_WITH_DB != 0L) cstring("shop")
        cstring("caching_sha2_password")
        if (caps and CONNECT_ATTRS != 0L) {
            val encoded = MyPayload().apply { attrs.forEach { (k, v) -> lenencString(k).lenencString(v) } }.toByteArray()
            lenencBytes(encoded)
        }
    }

    fun ok(
        affected: Long = 0,
        status: Int = AUTOCOMMIT,
        seq: Int = 1,
        header: Int = 0x00,
    ) = packet(seq) {
        byte(header)
            .lenenc(affected)
            .lenenc(0)
            .int16(status)
            .int16(0)
    }

    fun eof(
        status: Int = AUTOCOMMIT,
        seq: Int = 1,
    ) = packet(seq) { byte(0xFE).int16(0).int16(status) }

    fun err(
        errno: Int,
        state: String,
        message: String,
        seq: Int = 1,
    ) = packet(seq) {
        byte(0xFF)
            .int16(errno)
            .byte('#'.code)
            .string(state)
            .string(message)
    }

    fun columnCount(n: Int) = packet(1) { lenenc(n.toLong()) }

    fun column(
        name: String,
        type: Int,
        charset: Int = UTF8MB4,
        flags: Int = 0,
    ) = packet(2) {
        lenencString("def")
            .lenencString("shop")
            .lenencString("t")
            .lenencString("t")
            .lenencString(name)
            .lenencString(name)
        lenenc(0x0C)
            .int16(charset)
            .int32(255)
            .byte(type)
            .int16(flags)
            .byte(0)
            .int16(0)
    }

    fun textRow(vararg values: String?) =
        packet(3) {
            values.forEach { if (it == null) byte(0xFB) else lenencString(it) }
        }

    fun query(sql: String) = packet(0) { byte(0x03).string(sql) }

    fun prepare(sql: String) = packet(0) { byte(0x16).string(sql) }

    fun prepareOk(
        id: Int,
        columns: Int,
        params: Int,
    ) = packet(1) {
        byte(0)
            .int32(id)
            .int16(columns)
            .int16(params)
            .byte(0)
            .int16(0)
    }

    /** COM_STMT_EXECUTE with [values] as (type, encoded value or null); types are sent when [bind] is true. */
    fun execute(
        id: Int,
        values: List<Pair<Int, ByteArray?>>,
        bind: Boolean = true,
    ) = packet(0) {
        byte(0x17).int32(id).byte(0).int32(1)
        if (values.isNotEmpty()) {
            val bitmap = ByteArray((values.size + 7) / 8)
            values.forEachIndexed { i, (_, v) -> if (v == null) bitmap[i / 8] = (bitmap[i / 8].toInt() or (1 shl (i % 8))).toByte() }
            bytes(bitmap).byte(if (bind) 1 else 0)
            if (bind) values.forEach { (type, _) -> byte(type).byte(0) }
            values.forEach { (_, v) -> v?.let { bytes(it) } }
        }
    }

    /** A binary result row; each value is pre-encoded, null for SQL NULL. */
    fun binaryRow(vararg values: ByteArray?) =
        packet(3) {
            val bitmap = ByteArray((values.size + 9) / 8)
            values.forEachIndexed { i, v ->
                if (v == null) bitmap[(i + 2) / 8] = (bitmap[(i + 2) / 8].toInt() or (1 shl ((i + 2) % 8))).toByte()
            }
            byte(0).bytes(bitmap)
            values.forEach { v -> v?.let { bytes(it) } }
        }

    fun int1(v: Int) = MyPayload().byte(v).toByteArray()

    fun int2(v: Int) = MyPayload().int16(v).toByteArray()

    fun int4(v: Int) = MyPayload().int32(v).toByteArray()

    fun int8(v: Long) = MyPayload().int64(v).toByteArray()

    fun str(v: String) = MyPayload().lenencString(v).toByteArray()

    fun bin(v: ByteArray) = MyPayload().lenencBytes(v).toByteArray()

    fun datetime(
        y: Int,
        mo: Int,
        d: Int,
        h: Int,
        mi: Int,
        s: Int,
        micros: Int,
    ) = MyPayload()
        .byte(11)
        .int16(y)
        .byte(mo)
        .byte(d)
        .byte(h)
        .byte(mi)
        .byte(s)
        .int32(micros)
        .toByteArray()
}

class MySessionTest {
    private val entries = mutableListOf<QueryEntry>()

    private fun session(
        caps: Long = My.CLIENT,
        limits: Limits = Limits(maxRows = 1000, maxBytes = 1_048_576),
        attrs: List<Pair<String, String>> = emptyList(),
    ) = MySession("mysql", 9, limits) { entries += it }.apply {
        fromServer(My.greeting(My.BASE or My.DEPRECATE_EOF or My.QUERY_ATTRIBUTES or My.SSL))
        fromClient(My.login(caps, attrs = attrs))
        fromServer(My.ok(seq = 2))
    }

    private fun MySession.client(vararg packets: MyPacket) = packets.forEach(::fromClient)

    private fun MySession.server(vararg packets: MyPacket) = packets.forEach(::fromServer)

    @Test
    fun `a text query logs columns and rows`() {
        val s = session()
        s.client(My.query("select id, name from t"))
        s.server(
            My.columnCount(2),
            My.column("id", My.T_LONG),
            My.column("name", My.T_VAR_STRING),
            My.eof(),
            My.textRow("1", "ann"),
            My.textRow("2", null),
            My.eof(),
        )

        val e = entries.single()
        assertEquals("select id, name from t", e.sql)
        assertEquals(listOf("id", "name"), e.columns)
        assertEquals(listOf(listOf<String?>("1", "ann"), listOf("2", null)), e.rows)
        assertEquals(2L, e.rowCount)
        assertEquals("SELECT", e.command)
        assertNull(e.affected)
        assertNull(e.error)
        assertEquals("mysql", e.service)
        assertEquals(9L, e.connectionId)
        assertEquals(emptyList(), e.params)
        assertTrue(!e.truncated && e.durationMs >= 0)
    }

    @Test
    fun `with CLIENT_DEPRECATE_EOF the columns run straight into rows and an OK ends them`() {
        val s = session(caps = My.CLIENT or My.DEPRECATE_EOF)
        s.client(My.query("SELECT name FROM t"))
        s.server(My.columnCount(1), My.column("name", My.T_VAR_STRING), My.textRow("ann"), My.ok(header = 0xFE))
        s.client(My.query("select 2"))
        s.server(My.columnCount(1), My.column("2", My.T_LONGLONG), My.textRow("2"), My.ok(header = 0xFE))

        assertEquals(listOf(listOf(listOf<String?>("ann")), listOf(listOf("2"))), entries.map { it.rows })
        assertEquals(listOf(1L, 1L), entries.map { it.rowCount })
    }

    @Test
    fun `multiple result sets give one entry each, labelled by statement`() {
        val s = session(caps = My.CLIENT or My.DEPRECATE_EOF)
        s.client(My.query("select 1 as one; update t set a = 'x;y\\'' where b = 2"))
        s.server(
            My.columnCount(1),
            My.column("one", My.T_LONGLONG),
            My.textRow("1"),
            My.ok(header = 0xFE, status = My.AUTOCOMMIT or My.MORE_RESULTS),
            My.ok(affected = 3),
        )

        assertEquals(2, entries.size)
        val (select, update) = entries
        assertEquals("select 1 as one", select.sql)
        assertEquals(listOf(listOf<String?>("1")), select.rows)
        assertEquals("update t set a = 'x;y\\'' where b = 2", update.sql)
        assertEquals("UPDATE", update.command)
        assertEquals(3L, update.affected)
        assertNull(update.rows)
    }

    @Test
    fun `a prepared statement logs its SQL with binary parameters on every execute`() {
        val s = session()
        val sql = "select n from t where id = ? and name = ? and at > ?"
        s.client(My.prepare(sql))
        s.server(
            My.prepareOk(id = 1, columns = 1, params = 3),
            My.column("?", My.T_LONGLONG),
            My.column("?", My.T_VAR_STRING),
            My.column("?", My.T_DATETIME),
            My.eof(),
            My.column("n", My.T_LONG),
            My.eof(),
        )
        assertTrue(entries.isEmpty())

        s.client(
            My.execute(
                1,
                listOf(
                    My.T_LONGLONG to My.int8(42),
                    My.T_VAR_STRING to My.str("ann"),
                    My.T_DATETIME to My.datetime(2026, 11, 14, 10, 20, 30, 123),
                ),
            ),
        )
        s.server(My.columnCount(1), My.column("n", My.T_LONG), My.eof(), My.binaryRow(My.int4(7)), My.eof())
        // Types are sent once; the second execute relies on the ones remembered.
        s.client(My.execute(1, listOf(My.T_LONGLONG to My.int8(43), My.T_VAR_STRING to My.str("bob"), My.T_DATETIME to null), bind = false))
        s.server(My.columnCount(1), My.column("n", My.T_LONG), My.eof(), My.eof())

        assertEquals(2, entries.size)
        val (first, second) = entries
        assertEquals(sql, first.sql)
        assertEquals(listOf<String?>("42", "ann", "2026-11-14 10:20:30.000123"), first.params)
        assertEquals(listOf(listOf<String?>("7")), first.rows)
        assertEquals("SELECT", first.command)
        assertEquals(sql, second.sql)
        assertEquals(listOf<String?>("43", "bob", null), second.params)
        assertEquals(emptyList(), second.rows)
        assertEquals(0L, second.rowCount)
    }

    @Test
    fun `binary rows decode the common types`() {
        val s = session(caps = My.CLIENT or My.DEPRECATE_EOF)
        s.client(My.prepare("select * from types"))
        s.server(My.prepareOk(id = 4, columns = 0, params = 0))
        s.client(My.execute(4, emptyList()))
        val columns =
            listOf(
                My.column("tiny", My.T_TINY),
                My.column("short", My.T_SHORT),
                My.column("ulong", My.T_LONG, flags = 0x20),
                My.column("big", My.T_LONGLONG),
                My.column("f", My.T_FLOAT),
                My.column("d", My.T_DOUBLE),
                My.column("dec", My.T_NEWDECIMAL),
                My.column("day", My.T_DATE),
                My.column("zero", My.T_DATETIME),
                My.column("dur", My.T_TIME),
                My.column("raw", My.T_BLOB, charset = My.BINARY),
                My.column("doc", My.T_JSON),
                My.column("nothing", My.T_VAR_STRING),
            )
        s.server(My.columnCount(columns.size), *columns.toTypedArray())
        s.server(
            My.binaryRow(
                My.int1(0xFF),
                My.int2(-2),
                My.int4(-1),
                My.int8(Long.MIN_VALUE),
                My.int4(1.5f.toRawBits()),
                My.int8((-0.25).toRawBits()),
                My.str("12.50"),
                MyPayload()
                    .byte(4)
                    .int16(2026)
                    .byte(1)
                    .byte(2)
                    .toByteArray(),
                My.int1(0),
                MyPayload()
                    .byte(8)
                    .byte(1)
                    .int32(1)
                    .byte(2)
                    .byte(3)
                    .byte(4)
                    .toByteArray(),
                My.bin(byteArrayOf(0, 1, -1)),
                My.str("""{"a": 1}"""),
                null,
            ),
            My.ok(header = 0xFE),
        )

        assertEquals(
            listOf<String?>(
                "-1",
                "-2",
                "4294967295",
                Long.MIN_VALUE.toString(),
                "1.5",
                "-0.25",
                "12.50",
                "2026-01-02",
                "0000-00-00 00:00:00",
                "-26:03:04",
                "\\x0001ff",
                """{"a": 1}""",
                null,
            ),
            entries.single().rows!!.single(),
        )
    }

    @Test
    fun `an OK packet logs affected rows`() {
        val s = session()
        s.client(My.query("UPDATE t SET a = 1"))
        s.server(My.ok(affected = 5))
        s.client(My.query("set names utf8mb4"))
        s.server(My.ok(affected = 0))

        val (update, set) = entries
        assertEquals(5L, update.affected)
        assertEquals("UPDATE", update.command)
        assertNull(update.columns)
        assertNull(update.rows)
        assertNull(update.rowCount)
        assertNull(set.affected)
        assertEquals("SET", set.command)
    }

    @Test
    fun `an ERR packet logs code and message`() {
        val s = session()
        s.client(My.query("select * from nope"))
        s.server(My.err(1146, "42S02", "Table 'shop.nope' doesn't exist"))
        s.client(My.prepare("selec 1"))
        s.server(My.err(1064, "42000", "You have an error in your SQL syntax"))

        val (query, prepare) = entries
        assertEquals(QueryError("1146/42S02", "Table 'shop.nope' doesn't exist"), query.error)
        assertEquals("select * from nope", query.sql)
        assertNull(query.rows)
        assertEquals(QueryError("1064/42000", "You have an error in your SQL syntax"), prepare.error)
        assertEquals("selec 1", prepare.sql)
    }

    @Test
    fun `rows past the cap are counted, not kept`() {
        val s = session(limits = Limits(maxRows = 2, maxBytes = 1_048_576))
        s.client(My.query("select n from t"))
        s.server(My.columnCount(1), My.column("n", My.T_LONG), My.eof())
        (1..5).forEach { s.server(My.textRow("$it")) }
        s.server(My.eof())

        val e = entries.single()
        assertEquals(listOf(listOf<String?>("1"), listOf("2")), e.rows)
        assertEquals(5L, e.rowCount)
        assertTrue(e.truncated)
    }

    @Test
    fun `sql past the byte cap is cut`() {
        val s = session(limits = Limits(maxRows = 1000, maxBytes = 10))
        s.client(My.query("insert into t values ('${"x".repeat(100)}')"))
        s.server(My.ok())

        val e = entries.single()
        assertEquals("insert int", e.sql)
        assertTrue(e.truncated)
    }

    @Test
    fun `a value past the byte cap is cut and so are the rows that no longer fit`() {
        val s = session(limits = Limits(maxRows = 1000, maxBytes = 10))
        s.client(My.query("select s from t"))
        s.server(My.columnCount(1), My.column("s", My.T_VAR_STRING), My.eof())
        s.server(My.textRow("x".repeat(100)), My.textRow("y"), My.eof())

        val e = entries.single()
        assertEquals(listOf(listOf<String?>("x".repeat(10))), e.rows)
        assertEquals(2L, e.rowCount)
        assertTrue(e.truncated)
    }

    @Test
    fun `the client name comes from program_name`() {
        val s = session(attrs = listOf("_client_name" to "libmysql", "program_name" to "mysql", "_pid" to "42"))
        s.client(My.query("select 1"))
        s.server(My.ok())

        assertEquals("mysql", s.client)
        assertEquals("mysql", entries.single().client)
    }

    @Test
    fun `a command sent before the server finishes authentication is still logged`() {
        val s = MySession("mysql", 1, Limits(1000, 1000)) { entries += it }
        s.server(My.greeting(My.BASE))
        s.client(My.login(My.CLIENT))
        s.server(My.packet(2) { byte(0x01).byte(0x03) })
        // The client already has the OK and sends its query before the server side is observed.
        s.client(My.query("select 1"))
        s.server(My.ok(seq = 3), My.ok(affected = 0))

        assertEquals("select 1", entries.single().sql)
    }

    @Test
    fun `auth exchanges and LOCAL INFILE data are not taken for commands`() {
        val s = MySession("mysql", 1, Limits(1000, 1000)) { entries += it }
        s.server(My.greeting(My.BASE))
        s.client(My.login(My.CLIENT))
        s.server(My.packet(2) { byte(0xFE).cstring("mysql_native_password").bytes(ByteArray(21)) })
        s.client(My.packet(3) { byte(0x03).string("not a query") })
        s.server(My.ok(seq = 4))

        s.client(My.query("load data local infile 'f.csv' into table t"))
        s.server(My.packet(1) { byte(0xFB).string("f.csv") })
        s.client(My.packet(2) { byte(0x03).string("select from a file") }, My.packet(3) {})
        s.server(My.ok(affected = 2, seq = 4))

        val e = entries.single()
        assertEquals("load data local infile 'f.csv' into table t", e.sql)
        assertEquals("LOAD", e.command)
    }

    @Test
    fun `close logs the statement in flight as truncated`() {
        val s = session()
        s.client(My.query("select n from big"))
        s.server(My.columnCount(1), My.column("n", My.T_LONG), My.eof(), My.textRow("1"))
        s.close()
        s.server(My.textRow("2"), My.eof())

        val e = entries.single()
        assertEquals("select n from big", e.sql)
        assertEquals(listOf(listOf<String?>("1")), e.rows)
        assertTrue(e.truncated)
    }

    @Test
    fun `a closed statement and a reset connection forget prepared statements`() {
        val s = session(caps = My.CLIENT or My.DEPRECATE_EOF)
        s.client(My.prepare("delete from t where id = ?"))
        s.server(My.prepareOk(id = 1, columns = 0, params = 1), My.column("?", My.T_LONG))
        s.client(My.execute(1, listOf(My.T_LONG to My.int4(5))))
        s.server(My.ok(affected = 1))
        s.client(My.packet(0) { byte(0x19).int32(1) }, My.execute(1, listOf(My.T_LONG to My.int4(6))))
        s.server(My.err(1243, "HY000", "Unknown prepared statement handler"))

        s.client(My.prepare("select 1"))
        s.server(My.prepareOk(id = 2, columns = 1, params = 0), My.column("1", My.T_LONGLONG))
        s.client(My.packet(0) { byte(0x1F) })
        s.server(My.ok())
        s.client(My.execute(2, emptyList()))
        s.server(My.err(1243, "HY000", "Unknown prepared statement handler"))

        assertEquals(listOf("delete from t where id = ?", "", ""), entries.map { it.sql })
        assertEquals(listOf<String?>("5"), entries[0].params)
        assertEquals(1L, entries[0].affected)
        assertEquals("DELETE", entries[0].command)
    }

    @Test
    fun `a parameter sent as long data is logged in its place`() {
        val s = session(caps = My.CLIENT or My.DEPRECATE_EOF)
        s.client(My.prepare("insert into t (id, body) values (?, ?)"))
        s.server(My.prepareOk(id = 3, columns = 0, params = 2), My.column("?", My.T_LONG), My.column("?", My.T_BLOB))
        s.client(
            My.packet(0) { byte(0x18).int32(3).int16(1).string("hello ") },
            My.packet(0) { byte(0x18).int32(3).int16(1).string("world") },
            // The long-data parameter has its type but no value in the execute packet.
            My.packet(0) {
                byte(0x17)
                    .int32(3)
                    .byte(0)
                    .int32(1)
                    .byte(0)
                    .byte(1)
                byte(My.T_LONG)
                    .byte(0)
                    .byte(My.T_BLOB)
                    .byte(0)
                    .bytes(My.int4(1))
            },
        )
        s.server(My.ok(affected = 1))

        val e = entries.single()
        assertEquals(listOf<String?>("1", "hello world"), e.params)
        assertEquals(1L, e.affected)
    }

    @Test
    fun `query attributes ahead of the SQL are skipped`() {
        val s = session(caps = My.CLIENT or My.QUERY_ATTRIBUTES)
        s.client(
            My.packet(0) {
                byte(0x03)
                    .lenenc(1)
                    .lenenc(1)
                    .byte(0)
                    .byte(1)
                    .byte(My.T_VAR_STRING)
                    .byte(0)
                    .lenencString("traceparent")
                lenencString("00-abc-01").string("select 7")
            },
            My.packet(0) { byte(0x03).lenenc(0).lenenc(1).string("select 8") },
        )
        s.server(My.ok(), My.ok())

        assertEquals(listOf("select 7", "select 8"), entries.map { it.sql })
    }

    @Test
    fun `malformed packets are ignored and the session carries on`() {
        val s = session()
        s.client(My.packet(0) { byte(0x17).byte(1) })
        s.server(My.packet(1) { byte(0x00) })
        s.server(My.packet(1) { byte(0x42).byte(0xFF) })
        s.client(My.query("select 1"))
        s.server(My.ok())

        assertEquals("select 1", entries.last().sql)
    }
}
