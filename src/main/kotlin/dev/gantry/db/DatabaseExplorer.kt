package dev.gantry.db

import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException

/**
 * Browses, edits and changes the schema of an app's SQL services (Postgres, MySQL) over their published
 * host ports. Schema changes are generated here so the UI can preview them, run them, or save them as a
 * Flyway migration.
 */
class DatabaseExplorer(
    private val engine: Engine,
    private val migrations: Migrations = Migrations(engine),
) {
    fun info(
        app: String,
        service: String,
    ): DatabaseInfo {
        val target = target(app, service)
        val dialect = target.dialect
        return target.connect { connection ->
            val (version, database, size) = connection.query(dialect.databaseInfoSql).rows.single()
            DatabaseInfo(
                dialect = dialect.id,
                version = version.orEmpty(),
                database = database.orEmpty(),
                sizeBytes = size?.toLongOrNull() ?: 0,
                hasSchemas = dialect.hasSchemas,
                schemas = dialect.schemasSql?.let { sql -> connection.query(sql).rows.map { it[0].orEmpty() } }.orEmpty(),
                tables = tables(connection, dialect),
            )
        }
    }

    fun table(
        app: String,
        service: String,
        schema: String,
        table: String,
    ): TableDetail {
        val target = target(app, service)
        return target.connect { connection ->
            val summary = requireTable(connection, target.dialect, schema, table)
            val count =
                runCatching {
                    connection.query("SELECT count(*) FROM ${target.dialect.qualified(summary.schema, table)}").rows[0][0]?.toLong()
                }.getOrNull()
            TableDetail(
                schema = summary.schema,
                name = table,
                kind = summary.kind,
                rowCount = count,
                columns = columns(connection, target.dialect, summary.schema, table),
                indexes =
                    connection.query(target.dialect.indexesSql, summary.schema, table).rows.map {
                        IndexInfo(it[0].orEmpty(), it[1].bool(), it[2].bool(), it[3].orEmpty())
                    },
                constraints =
                    connection.query(target.dialect.constraintsSql, summary.schema, table).rows.map {
                        ConstraintInfo(it[0].orEmpty(), it[1].orEmpty(), it[2].orEmpty())
                    },
            )
        }
    }

    @Suppress("LongParameterList")
    fun rows(
        app: String,
        service: String,
        schema: String,
        table: String,
        limit: Int,
        offset: Int,
        orderBy: String?,
        descending: Boolean,
        where: String?,
    ): RowsPage {
        val target = target(app, service)
        val dialect = target.dialect
        return target.connect { connection ->
            val summary = requireTable(connection, dialect, schema, table)
            val columns = columns(connection, dialect, summary.schema, table)
            val primaryKey = columns.filter { it.primaryKey }.map { it.name }
            val filter = where?.takeIf { it.isNotBlank() }?.let { " WHERE " + sqlFragment(it, "filter") }.orEmpty()
            val order =
                when {
                    orderBy.isNullOrEmpty() -> primaryKey.joinToString { dialect.quote(it) }
                    columns.any { it.name == orderBy } -> dialect.quote(orderBy)
                    else -> throw GantryException("unknown column '$orderBy'")
                }.takeIf { it.isNotEmpty() }?.let { " ORDER BY $it${if (descending) " DESC" else ""}" }.orEmpty()
            val from = dialect.qualified(summary.schema, table)
            val page = limit.coerceIn(1, MAX_ROWS)
            val result = connection.query("SELECT * FROM $from$filter$order LIMIT $page OFFSET ${offset.coerceAtLeast(0)}")
            val total = runCatching { connection.query("SELECT count(*) FROM $from$filter").rows[0][0]?.toLong() }.getOrNull()
            RowsPage(result, primaryKey, total)
        }
    }

    fun execute(
        app: String,
        service: String,
        sql: String,
    ): TableResult = target(app, service).connect { it.query(sql) }

    fun insert(
        app: String,
        service: String,
        edit: RowEdit,
    ) = target(app, service).connect { connection ->
        val (dialect, summary, columns) = tableContext(connection, app, service, edit.schema, edit.table)
        val values = edit.values.filterKeys { name -> requireColumn(columns, name) }
        val sql =
            if (values.isEmpty()) {
                // Every column takes its default; MySQL has no DEFAULT VALUES clause.
                if (dialect.hasSchemas) {
                    "INSERT INTO ${dialect.qualified(summary.schema, edit.table)} DEFAULT VALUES"
                } else {
                    "INSERT INTO ${dialect.quote(edit.table)} () VALUES ()"
                }
            } else {
                "INSERT INTO ${dialect.qualified(summary.schema, edit.table)} (${values.keys.joinToString { dialect.quote(it) }}) " +
                    "VALUES (${values.keys.joinToString { "?" }})"
            }
        connection.update(dialect, sql, values.values.toList())
    }

    fun update(
        app: String,
        service: String,
        edit: RowEdit,
    ) = target(app, service).connect { connection ->
        val (dialect, summary, columns) = tableContext(connection, app, service, edit.schema, edit.table)
        val values = edit.values.filterKeys { name -> requireColumn(columns, name) }
        if (values.isEmpty()) throw GantryException("nothing to update")
        val (keySql, keyValues) = keyClause(dialect, columns, edit.key)
        val sql =
            "UPDATE ${dialect.qualified(
                summary.schema,
                edit.table,
            )} SET ${values.keys.joinToString { "${dialect.quote(it)} = ?" }} WHERE $keySql"
        val changed = connection.update(dialect, sql, values.values.toList() + keyValues)
        if (changed != 1) throw GantryException("expected to update 1 row, updated $changed")
    }

    fun delete(
        app: String,
        service: String,
        request: RowsDelete,
    ): Int =
        target(app, service).connect { connection ->
            val (dialect, summary, columns) = tableContext(connection, app, service, request.schema, request.table)
            connection.autoCommit = false
            try {
                val deleted =
                    request.keys.sumOf { key ->
                        val (keySql, keyValues) = keyClause(dialect, columns, key)
                        connection.update(
                            dialect,
                            "DELETE FROM ${dialect.qualified(summary.schema, request.table)} WHERE $keySql",
                            keyValues,
                        )
                    }
                connection.commit()
                deleted
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    /** Builds the SQL for a schema change, then previews, runs, or saves it as a migration per [DdlRequest.mode]. */
    fun ddl(
        app: String,
        service: String,
        request: DdlRequest,
    ): DdlResult {
        val target = target(app, service)
        val statements = target.connect { connection -> ddlStatements(connection, target.dialect, request) }
        val sql = statements.joinToString(";\n", postfix = ";")
        return when (request.mode) {
            "preview" -> {
                DdlResult(sql)
            }

            "execute" -> {
                // Postgres DDL is transactional, so a multi-statement change (alter + rename) applies whole or not at all.
                target.connect { connection ->
                    connection.autoCommit = false
                    try {
                        statements.forEach { statement -> connection.query(statement) }
                        connection.commit()
                    } catch (e: Exception) {
                        connection.rollback()
                        throw e
                    }
                }
                DdlResult(sql, executed = true)
            }

            "migration" -> {
                val description = request.description.ifBlank { "${request.op} ${request.table}".trim() }
                DdlResult(sql, migration = migrations.create(app, service, description, sql + "\n"))
            }

            else -> {
                throw GantryException("unknown mode '${request.mode}' (preview, execute, migration)")
            }
        }
    }

    private fun ddlStatements(
        connection: Connection,
        dialect: Dialect,
        request: DdlRequest,
    ): List<String> {
        val schema = request.schema.ifEmpty { defaultSchema(dialect) }
        val table = dialect.qualified(schema, request.table)

        fun existing() = requireTable(connection, dialect, schema, request.table)

        fun column(name: String) =
            columns(connection, dialect, schema, request.table).firstOrNull { it.name == name }
                ?: throw GantryException("unknown column '$name'")
        return when (request.op) {
            "create_table" -> {
                if (request.columns.isEmpty()) throw GantryException("a table needs at least one column")
                val definitions = request.columns.map { dialect.columnDefinition(it.copy(name = name(it.name, "column"))) }
                val primaryKey = request.columns.filter { it.primaryKey }.map { dialect.quote(it.name) }
                val body = definitions + listOfNotNull(primaryKey.takeIf { it.isNotEmpty() }?.let { "PRIMARY KEY (${it.joinToString()})" })
                listOf("CREATE TABLE ${dialect.qualified(schema, name(request.table, "table"))} (\n  ${body.joinToString(",\n  ")}\n)")
            }

            "drop_table" -> {
                val kind = existing().kind.uppercase()
                listOf("DROP $kind $table${if (request.cascade && dialect.hasSchemas) " CASCADE" else ""}")
            }

            "truncate_table" -> {
                existing()
                listOf("TRUNCATE TABLE $table")
            }

            "rename_table" -> {
                existing()
                listOf(dialect.renameTable(schema, request.table, name(request.newName, "new name")))
            }

            "add_column" -> {
                val definition = request.definition ?: throw GantryException("column definition is required")
                listOf("ALTER TABLE $table ADD COLUMN ${dialect.columnDefinition(definition.copy(name = name(definition.name, "column")))}")
            }

            "drop_column" -> {
                listOf("ALTER TABLE $table DROP COLUMN ${dialect.quote(column(request.column).name)}")
            }

            "alter_column" -> {
                val definition = request.definition ?: throw GantryException("column definition is required")
                dialect
                    .alterColumn(
                        schema,
                        request.table,
                        column(request.column),
                        definition.copy(name = name(definition.name, "column")),
                    ).split(";\n")
            }

            "create_index" -> {
                if (request.indexColumns.isEmpty()) throw GantryException("pick at least one column")
                request.indexColumns.forEach { column(it) }
                val index = request.index.ifBlank { "${request.table}_${request.indexColumns.joinToString("_")}_idx" }
                val unique = if (request.unique) "UNIQUE " else ""
                listOf(
                    "CREATE ${unique}INDEX ${dialect.quote(
                        name(index, "index"),
                    )} ON $table (${request.indexColumns.joinToString { dialect.quote(it) }})",
                )
            }

            "drop_index" -> {
                listOf(dialect.dropIndex(schema, request.table, name(request.index, "index")))
            }

            "create_schema" -> {
                requireSchemas(dialect)
                listOf("CREATE SCHEMA ${dialect.quote(name(request.schema, "schema"))}")
            }

            "drop_schema" -> {
                requireSchemas(dialect)
                listOf("DROP SCHEMA ${dialect.quote(name(request.schema, "schema"))}${if (request.cascade) " CASCADE" else ""}")
            }

            else -> {
                throw GantryException("unknown operation '${request.op}'")
            }
        }
    }

    private fun requireSchemas(dialect: Dialect) {
        if (!dialect.hasSchemas) throw GantryException("${dialect.id} has no schemas; the database is the schema")
    }

    private fun name(
        value: String,
        what: String,
    ): String = value.trim().ifEmpty { throw GantryException("$what is required") }

    private fun defaultSchema(dialect: Dialect) = if (dialect.hasSchemas) "public" else ""

    private data class TableContext(
        val dialect: Dialect,
        val summary: TableSummary,
        val columns: List<ColumnInfo>,
    )

    private fun tableContext(
        connection: Connection,
        app: String,
        service: String,
        schema: String,
        table: String,
    ): TableContext {
        val dialect = target(app, service).dialect
        val summary = requireTable(connection, dialect, schema, table)
        if (summary.kind != "table") throw GantryException("${summary.kind}s cannot be edited")
        return TableContext(dialect, summary, columns(connection, dialect, summary.schema, table))
    }

    private fun requireColumn(
        columns: List<ColumnInfo>,
        name: String,
    ): Boolean {
        if (columns.none { it.name == name }) throw GantryException("unknown column '$name'")
        return true
    }

    /** Rows are addressed by primary key, so edits never hit more than the row the user picked. */
    private fun keyClause(
        dialect: Dialect,
        columns: List<ColumnInfo>,
        key: Map<String, String?>,
    ): Pair<String, List<String?>> {
        val primaryKey = columns.filter { it.primaryKey }.map { it.name }
        if (primaryKey.isEmpty()) throw GantryException("table has no primary key; edit it with SQL instead")
        if (key.keys != primaryKey.toSet()) throw GantryException("row key must be exactly the primary key (${primaryKey.joinToString()})")
        return primaryKey.joinToString(" AND ") { "${dialect.quote(it)} = ?" } to primaryKey.map { key[it] }
    }

    private fun target(
        app: String,
        service: String,
    ) = DatabaseTarget.resolve(engine, app, service)

    private fun tables(
        connection: Connection,
        dialect: Dialect,
    ) = connection.query(dialect.tablesSql).rows.map {
        TableSummary(it[0].orEmpty(), it[1].orEmpty(), it[2].orEmpty(), it[3]?.toLongOrNull() ?: 0, it[4]?.toLongOrNull() ?: 0)
    }

    private fun requireTable(
        connection: Connection,
        dialect: Dialect,
        schema: String,
        table: String,
    ): TableSummary =
        tables(connection, dialect).firstOrNull { it.name == table && (!dialect.hasSchemas || it.schema == schema) }
            ?: throw GantryException("unknown table '${if (dialect.hasSchemas) "$schema." else ""}$table'")

    private fun columns(
        connection: Connection,
        dialect: Dialect,
        schema: String,
        table: String,
    ) = connection.query(dialect.columnsSql, schema, table).rows.map {
        ColumnInfo(it[0].orEmpty(), it[1].orEmpty(), it[2].bool(), it[3], it[4].bool(), it[5].orEmpty())
    }

    private fun String?.bool() = this == "t" || this == "true" || this == "1"

    private fun Connection.update(
        dialect: Dialect,
        sql: String,
        values: List<String?>,
    ): Int =
        prepareStatement(sql).use { statement ->
            statement.queryTimeout = QUERY_TIMEOUT_SECONDS
            values.forEachIndexed { index, value -> dialect.bindText(statement, index + 1, value) }
            try {
                statement.executeUpdate()
            } catch (e: SQLException) {
                throw GantryException(e.message ?: "statement failed")
            }
        }

    private companion object {
        const val MAX_ROWS = 500
    }
}

internal const val QUERY_TIMEOUT_SECONDS = 30
private const val MAX_RESULT_ROWS = 500

/** One cell's text is cut at this many characters; a JSON blob or a file in a text column can be megabytes. */
internal const val MAX_CELL_CHARS = 64 * 1024

/** Characters a whole result may hold before further rows are left out, so one query can't balloon the heap. */
internal const val MAX_RESULT_CHARS = 8 * 1024 * 1024

/** Runs [sql] with string parameters and reads at most [MAX_RESULT_ROWS] rows as text, within the size caps. */
internal fun Connection.query(
    sql: String,
    vararg params: String,
): TableResult =
    prepareStatement(sql).use { statement ->
        statement.queryTimeout = QUERY_TIMEOUT_SECONDS
        params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        try {
            read(statement)
        } catch (e: SQLException) {
            throw GantryException(e.message ?: "query failed")
        }
    }

private fun read(statement: PreparedStatement): TableResult {
    if (!statement.execute()) return TableResult(emptyList(), emptyList(), note = "${statement.updateCount} rows affected")
    statement.resultSet.use { results ->
        val meta = results.metaData
        val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
        val reader = ResultReader(columns.size)
        // accept() only reports full after next() found another row, so `full` means rows were left out.
        while (results.next() && reader.accept { column -> results.getString(column + 1) }) Unit
        return reader.result(columns, hasMore = reader.full)
    }
}

/** Accumulates rows as text under [MAX_RESULT_ROWS], [MAX_CELL_CHARS] per cell and [MAX_RESULT_CHARS] in total. */
internal class ResultReader(
    private val columnCount: Int,
) {
    private val rows = mutableListOf<List<String?>>()
    private val clipped = mutableListOf<List<Int>>()
    private var chars = 0L

    /** True once no more rows fit; the caller stops reading. */
    var full = false
        private set

    /** Reads one row through [cell] (column index -> text); returns false when the result is full. */
    fun accept(cell: (Int) -> String?): Boolean {
        if (rows.size == MAX_RESULT_ROWS || chars >= MAX_RESULT_CHARS) {
            full = true
            return false
        }
        val row =
            (0 until columnCount).map { column ->
                cell(column)?.let { value ->
                    if (value.length > MAX_CELL_CHARS) {
                        clipped += listOf(rows.size, column)
                        value.take(MAX_CELL_CHARS)
                    } else {
                        value
                    }
                }
            }
        chars += row.sumOf { it?.length ?: 0 }
        rows += row
        return true
    }

    fun result(
        columns: List<String>,
        hasMore: Boolean,
    ): TableResult {
        val notes =
            listOfNotNull(
                "showing the first ${rows.size} rows".takeIf { hasMore },
                "${clipped.size} long value(s) cut at ${MAX_CELL_CHARS / 1024} KB".takeIf { clipped.isNotEmpty() },
            )
        return TableResult(columns, rows, truncated = hasMore, note = notes.joinToString("; ").ifEmpty { null }, clippedCells = clipped)
    }
}
