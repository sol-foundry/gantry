package dev.gantry.dblog

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8

/** The client name Gantry's own database connections give themselves; the proxy never logs their statements. */
const val GANTRY_CLIENT = "gantry"

/** One statement a database proxy saw: what was asked, how long it took and what came back (rows cut at the configured size). */
@Serializable
data class QueryEntry(
    val id: String,
    val time: String,
    val durationMs: Double,
    val service: String,
    val connectionId: Long,
    val client: String?,
    val sql: String,
    val params: List<String?>,
    val columns: List<String>?,
    val rows: List<List<String?>>?,
    val rowCount: Long?,
    val affected: Long?,
    val command: String?,
    val error: QueryError?,
    /** True when rows or a value were cut at the configured size. */
    val truncated: Boolean,
    /**
     * Each parameter's type when known: a Postgres type name, a MySQL type, or MySQL `binary` for a value shown as the
     * hex of its bytes.
     */
    val paramTypes: List<String?> = emptyList(),
    /** True when the SQL or a parameter was cut, a parameter is missing, or one is only the hex of its wire encoding. */
    val inexact: Boolean = false,
)

/** The database's error for a statement: its SQLSTATE or error number as [code]. */
@Serializable
data class QueryError(
    val code: String,
    val message: String,
)

/** [value] cut to at most [maxBytes] of UTF-8, never splitting a character. */
internal fun utf8Prefix(
    value: String,
    maxBytes: Int,
): String {
    if (value.length.toLong() * 3 <= maxBytes) return value
    val bytes = value.toByteArray(UTF_8)
    if (bytes.size <= maxBytes) return value
    val decoder = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE)
    val out = CharBuffer.allocate(maxBytes)
    decoder.decode(ByteBuffer.wrap(bytes, 0, maxBytes), out, true)
    return out.flip().toString()
}

/** This entry with its SQL and each parameter cut to [maxBytes], marked truncated when anything was cut. */
internal fun QueryEntry.capped(
    maxBytes: Int,
    paramBytes: Int = maxBytes,
): QueryEntry {
    val cutSql = utf8Prefix(sql, maxBytes)
    val cutParams = params.map { it?.let { p -> utf8Prefix(p, paramBytes) } }
    val changed = cutSql.length != sql.length || cutParams.indices.any { cutParams[it]?.length != params[it]?.length }
    return if (changed) copy(sql = cutSql, params = cutParams, truncated = true, inexact = true) else this
}
