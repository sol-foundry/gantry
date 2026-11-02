package dev.gantry.db

import kotlinx.serialization.Serializable

/**
 * A result grid; every cell is rendered as text, with null for SQL NULL. [truncated] means rows were left out;
 * [clippedCells] lists `[row, column]` of cells cut short, which must not be edited back from this text.
 */
@Serializable
data class TableResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val truncated: Boolean = false,
    val note: String? = null,
    val clippedCells: List<List<Int>> = emptyList(),
)

@Serializable
data class DatabaseInfo(
    val dialect: String,
    val version: String,
    val database: String,
    val sizeBytes: Long,
    val hasSchemas: Boolean,
    val schemas: List<String>,
    val tables: List<TableSummary>,
)

@Serializable
data class TableSummary(
    val schema: String,
    val name: String,
    val kind: String,
    val estimatedRows: Long,
    val sizeBytes: Long,
)

@Serializable
data class ColumnInfo(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val default: String?,
    val primaryKey: Boolean,
    val extra: String,
)

@Serializable
data class IndexInfo(
    val name: String,
    val unique: Boolean,
    val primary: Boolean,
    val columns: String,
)

@Serializable
data class ConstraintInfo(
    val name: String,
    val kind: String,
    val definition: String,
)

@Serializable
data class TableDetail(
    val schema: String,
    val name: String,
    val kind: String,
    val rowCount: Long?,
    val columns: List<ColumnInfo>,
    val indexes: List<IndexInfo>,
    val constraints: List<ConstraintInfo>,
)

@Serializable
data class RowsPage(
    val result: TableResult,
    val primaryKey: List<String>,
    val total: Long?,
)

/** A column as typed into the UI. [type], [default] and [extra] are SQL fragments. */
@Serializable
data class ColumnDef(
    val name: String,
    val type: String,
    val nullable: Boolean = true,
    val default: String? = null,
    val extra: String? = null,
    val primaryKey: Boolean = false,
)

@Serializable
data class RowEdit(
    val schema: String = "",
    val table: String,
    /** Primary-key column -> current value; identifies the row for update and delete. */
    val key: Map<String, String?> = emptyMap(),
    /** Column -> new value (null for SQL NULL). */
    val values: Map<String, String?> = emptyMap(),
)

@Serializable
data class RowsDelete(
    val schema: String = "",
    val table: String,
    val keys: List<Map<String, String?>>,
)

/**
 * One schema change from the dashboard. [op] picks which fields are read; [mode] says whether to only show
 * the SQL ("preview"), run it ("execute"), or write it as the next Flyway migration ("migration").
 */
@Serializable
data class DdlRequest(
    val op: String,
    val mode: String = "preview",
    val schema: String = "",
    val table: String = "",
    val newName: String = "",
    val column: String = "",
    val columns: List<ColumnDef> = emptyList(),
    val definition: ColumnDef? = null,
    val index: String = "",
    val indexColumns: List<String> = emptyList(),
    val unique: Boolean = false,
    val cascade: Boolean = false,
    val description: String = "",
)

@Serializable
data class DdlResult(
    val sql: String,
    val executed: Boolean = false,
    val migration: String? = null,
)
