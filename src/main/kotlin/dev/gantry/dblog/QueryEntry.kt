package dev.gantry.dblog

import kotlinx.serialization.Serializable

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
)

/** The database's error for a statement: its SQLSTATE or error number as [code]. */
@Serializable
data class QueryError(
    val code: String,
    val message: String,
)
