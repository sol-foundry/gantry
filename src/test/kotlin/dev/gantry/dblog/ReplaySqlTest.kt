package dev.gantry.dblog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplaySqlTest {
    private fun entry(
        sql: String,
        params: List<String?> = emptyList(),
        types: List<String?> = emptyList(),
        inexact: Boolean = false,
    ) = QueryEntry(
        id = "q",
        time = "2026-11-14T10:00:00Z",
        durationMs = 1.0,
        service = "postgres",
        connectionId = 1,
        client = null,
        sql = sql,
        params = params,
        columns = null,
        rows = null,
        rowCount = null,
        affected = null,
        command = null,
        error = null,
        truncated = false,
        paramTypes = types,
        inexact = inexact,
    )

    private fun pg(
        sql: String,
        params: List<String?>,
        types: List<String?> = emptyList(),
    ) = ReplaySql.inline(entry(sql, params, types), Dialect.POSTGRES)

    private fun my(
        sql: String,
        params: List<String?>,
        types: List<String?> = emptyList(),
    ) = ReplaySql.inline(entry(sql, params, types), Dialect.MYSQL)

    @Test
    fun `postgres params are substituted by number, in any order and repeated`() {
        val r = pg("select * from t where b = $2 and a = $1 or a = $1", listOf("x", "y"))
        assertEquals("select * from t where b = 'y' and a = 'x' or a = 'x'", r.sql)
        assertTrue(r.complete)
    }

    @Test
    fun `postgres dollar ten is not dollar one followed by zero`() {
        val params = (1..10).map { "v$it" }
        assertEquals("values ('v1', 'v10')", pg("values ($1, $10)", params).sql)
    }

    @Test
    fun `postgres placeholders inside strings, identifiers, comments and dollar quotes stay`() {
        val sql =
            "select '\$1', 'it''s \$1', E'\\' \$1', \"col\$1\", \$\$ \$1 \$\$, \$fn\$ \$1 \$fn\$ -- \$1\n" +
                "/* \$1 /* nested \$1 */ \$1 */ , a\$1, \$1"
        val r = pg(sql, listOf("v"))
        assertEquals(
            "select '\$1', 'it''s \$1', E'\\' \$1', \"col\$1\", \$\$ \$1 \$\$, \$fn\$ \$1 \$fn\$ -- \$1\n" +
                "/* \$1 /* nested \$1 */ \$1 */ , a\$1, 'v'",
            r.sql,
        )
    }

    @Test
    fun `postgres literals follow their types`() {
        val r =
            pg(
                "select $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11",
                listOf(null, "42", "-7", "12.5", "t", "false", "it's", "\\xdead", "2026-11-14 10:00:00+00", "NaN", "{\"a\":1}"),
                listOf(null, "int8", "int4", "numeric", "bool", "bool", "text", "bytea", "timestamptz", "float8", "jsonb"),
            )
        assertEquals(
            "select NULL, 42, (-7), 12.5, TRUE, FALSE, 'it''s'::text, '\\xdead'::bytea, " +
                "'2026-11-14 10:00:00+00'::timestamptz, 'NaN'::float8, '{\"a\":1}'::jsonb",
            r.sql,
        )
        assertTrue(r.complete)
    }

    @Test
    fun `postgres params of unknown type are quoted as text without a cast`() {
        assertEquals("select '5', 'a'", pg("select $1, $2", listOf("5", "a")).sql)
    }

    @Test
    fun `a postgres placeholder with no recorded value stays and the replay is incomplete`() {
        val r = pg("select $1, $2", listOf("a"))
        assertEquals("select 'a', $2", r.sql)
        assertFalse(r.complete)
    }

    @Test
    fun `mysql params are substituted in order, skipping strings, identifiers and comments`() {
        val sql = "select '?', 'a\\'?', \"?\", `c?`, ? # ?\n, ? -- ?\n, /* ? */ ?"
        val r = my(sql, listOf("1", "2", "3"), listOf("bigint", "bigint", "bigint"))
        assertEquals("select '?', 'a\\'?', \"?\", `c?`, 1 # ?\n, 2 -- ?\n, /* ? */ 3", r.sql)
        assertTrue(r.complete)
    }

    @Test
    fun `mysql literals escape quotes and backslashes and write bytes as hex`() {
        val r =
            my(
                "insert into t values (?, ?, ?, ?, ?, ?)",
                listOf(null, "-3", "it's a \\ path", "\\x00ff", "2026-11-14 10:00:00", "1.5"),
                listOf("varchar", "int", "varchar", "binary", "datetime", "double"),
            )
        assertEquals("insert into t values (NULL, -3, 'it''s a \\\\ path', X'00ff', '2026-11-14 10:00:00', 1.5)", r.sql)
    }

    @Test
    fun `mysql missing params leave the marker and mark the replay incomplete`() {
        val r = my("select ?, ?", listOf("a"), listOf("varchar"))
        assertEquals("select 'a', ?", r.sql)
        assertFalse(r.complete)
    }

    @Test
    fun `an entry without params replays its SQL as is`() {
        val sql = "select '\$1', ? from t; select 2"
        assertEquals(Replay(sql, true), ReplaySql.inline(entry(sql), Dialect.POSTGRES))
        assertEquals(Replay(sql, true), ReplaySql.inline(entry(sql), Dialect.MYSQL))
    }

    @Test
    fun `an inexact entry still replays but is flagged incomplete`() {
        val r = ReplaySql.inline(entry("select $1", listOf("abc"), listOf("text"), inexact = true), Dialect.POSTGRES)
        assertEquals("select 'abc'::text", r.sql)
        assertFalse(r.complete)
    }

    @Test
    fun `the detail json carries the replay next to the entry`() {
        val obj = entry("select $1", listOf("7"), listOf("int4")).withReplay(Json)
        assertEquals("select 7", obj["replaySql"]!!.jsonPrimitive.content)
        assertTrue(obj["replayComplete"]!!.jsonPrimitive.boolean)
        assertEquals("select $1", obj["sql"]!!.jsonPrimitive.content)
    }
}
