package dev.gantry.db

import dev.gantry.dblog.GANTRY_CLIENT
import dev.gantry.engine.GantryException
import java.sql.PreparedStatement
import java.sql.Types

/**
 * What differs between the supported SQL databases: identifier quoting, catalog queries and DDL syntax.
 * Catalog queries take `(schema, table)` parameters where noted and return rows in the column order the
 * explorer documents next to each query.
 */
sealed class Dialect {
    abstract val id: String

    /** Whether the database has schemas inside it (Postgres) or the database is the schema (MySQL). */
    abstract val hasSchemas: Boolean

    abstract fun jdbcUrl(
        port: Int,
        database: String,
    ): String

    abstract fun quote(identifier: String): String

    fun qualified(
        schema: String,
        table: String,
    ) = if (hasSchemas) "${quote(schema)}.${quote(table)}" else quote(table)

    /** version, database name, size in bytes */
    abstract val databaseInfoSql: String

    /** schema names (empty for databases without schemas) */
    abstract val schemasSql: String?

    /** schema, name, kind (table/view/...), estimated rows, total bytes */
    abstract val tablesSql: String

    /**
     * names of mirror schemas: ones holding nothing but views that each read a same-named table or view of another
     * schema, like a read-only access layer over `public`. Null for databases without schemas.
     */
    open val mirrorSchemasSql: String? = null

    /** (schema, table) -> name, type, nullable, default expression, part of primary key, extra */
    abstract val columnsSql: String

    /** (schema, table) -> name, unique, primary, columns (comma separated) */
    abstract val indexesSql: String

    /** (schema, table) -> name, kind, definition */
    abstract val constraintsSql: String

    /** Binds a cell value typed in the UI; the database converts the text to the column's type. */
    abstract fun bindText(
        statement: PreparedStatement,
        index: Int,
        value: String?,
    )

    abstract fun renameTable(
        schema: String,
        table: String,
        newName: String,
    ): String

    abstract fun alterColumn(
        schema: String,
        table: String,
        current: ColumnInfo,
        target: ColumnDef,
    ): String

    abstract fun dropIndex(
        schema: String,
        table: String,
        index: String,
    ): String

    fun columnDefinition(column: ColumnDef): String =
        buildString {
            append(quote(column.name)).append(' ').append(sqlFragment(column.type, "type"))
            if (!column.nullable) append(" NOT NULL")
            column.default?.takeIf { it.isNotBlank() }?.let { append(" DEFAULT ").append(sqlFragment(it, "default")) }
            column.extra?.takeIf { it.isNotBlank() }?.let { append(' ').append(sqlFragment(it, "extra")) }
        }

    object Postgres : Dialect() {
        override val id = "postgres"
        override val hasSchemas = true

        override fun jdbcUrl(
            port: Int,
            database: String,
        ) = "jdbc:postgresql://127.0.0.1:$port/${urlPart(database)}?ApplicationName=$GANTRY_CLIENT"

        override fun quote(identifier: String) = "\"" + identifier.replace("\"", "\"\"") + "\""

        override val databaseInfoSql = "SELECT version(), current_database(), pg_database_size(current_database())"

        override val schemasSql =
            """
            SELECT nspname FROM pg_namespace
            WHERE nspname NOT IN ('information_schema') AND nspname NOT LIKE 'pg\_%' ORDER BY 1
            """.trimIndent()

        override val tablesSql =
            """
            SELECT n.nspname, c.relname,
                   CASE c.relkind WHEN 'v' THEN 'view' WHEN 'm' THEN 'materialized view' WHEN 'f' THEN 'foreign table' ELSE 'table' END,
                   GREATEST(c.reltuples, 0)::bigint, pg_total_relation_size(c.oid)
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN ('r', 'p', 'v', 'm', 'f') AND NOT c.relispartition
              AND n.nspname <> 'information_schema' AND n.nspname NOT LIKE 'pg\_%'
            ORDER BY 1, 2
            """.trimIndent()

        // A view's rewrite rule depends on every relation it reads; a mirror reads exactly one. Schemas with
        // functions, tables or anything else are never mirrors, and neither is public.
        override val mirrorSchemasSql =
            """
            WITH refs AS (
                SELECT r.ev_class AS view, array_agg(DISTINCT d.refobjid) AS targets
                FROM pg_rewrite r
                JOIN pg_depend d ON d.classid = 'pg_rewrite'::regclass AND d.objid = r.oid
                 AND d.refclassid = 'pg_class'::regclass AND d.refobjid <> r.ev_class
                GROUP BY r.ev_class
            ),
            objects AS (
                SELECT c.relnamespace AS schema,
                       c.relkind = 'v' AND cardinality(refs.targets) = 1 AND t.relname = c.relname
                         AND t.relnamespace <> c.relnamespace AS mirror
                FROM pg_class c
                LEFT JOIN refs ON refs.view = c.oid
                LEFT JOIN pg_class t ON t.oid = refs.targets[1]
            )
            SELECT n.nspname
            FROM pg_namespace n JOIN objects o ON o.schema = n.oid
            WHERE n.nspname NOT IN ('public', 'information_schema') AND n.nspname NOT LIKE 'pg\_%'
              AND NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.pronamespace = n.oid)
            GROUP BY n.nspname
            HAVING bool_and(COALESCE(o.mirror, false))
            ORDER BY 1
            """.trimIndent()

        override val columnsSql =
            """
            SELECT a.attname, format_type(a.atttypid, a.atttypmod), NOT a.attnotnull,
                   pg_get_expr(d.adbin, d.adrelid),
                   COALESCE(a.attnum = ANY(pk.indkey), false),
                   CASE a.attidentity WHEN 'a' THEN 'GENERATED ALWAYS AS IDENTITY'
                                      WHEN 'd' THEN 'GENERATED BY DEFAULT AS IDENTITY' ELSE '' END
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            LEFT JOIN pg_index pk ON pk.indrelid = c.oid AND pk.indisprimary
            WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
            """.trimIndent()

        override val indexesSql =
            """
            SELECT i.relname, ix.indisunique, ix.indisprimary,
                   (SELECT string_agg(pg_get_indexdef(ix.indexrelid, k + 1, true), ', ' ORDER BY k)
                    FROM generate_subscripts(ix.indkey, 1) k)
            FROM pg_index ix
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN pg_class t ON t.oid = ix.indrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = ? AND t.relname = ?
            ORDER BY ix.indisprimary DESC, 1
            """.trimIndent()

        override val constraintsSql =
            """
            SELECT con.conname,
                   CASE con.contype WHEN 'f' THEN 'foreign key' WHEN 'u' THEN 'unique' WHEN 'c' THEN 'check'
                                    WHEN 'p' THEN 'primary key' WHEN 'x' THEN 'exclusion' ELSE con.contype::text END,
                   pg_get_constraintdef(con.oid)
            FROM pg_constraint con
            JOIN pg_class t ON t.oid = con.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = ? AND t.relname = ? AND con.contype <> 'n'
            ORDER BY 2, 1
            """.trimIndent()

        override fun bindText(
            statement: PreparedStatement,
            index: Int,
            value: String?,
        ) {
            // OTHER sends the text as an untyped literal, so the server casts it to the column's type.
            if (value == null) statement.setNull(index, Types.NULL) else statement.setObject(index, value, Types.OTHER)
        }

        override fun renameTable(
            schema: String,
            table: String,
            newName: String,
        ) = "ALTER TABLE ${qualified(schema, table)} RENAME TO ${quote(newName)}"

        override fun alterColumn(
            schema: String,
            table: String,
            current: ColumnInfo,
            target: ColumnDef,
        ): String {
            val column = quote(current.name)
            val clauses =
                buildList {
                    if (target.type.trim() != current.type) {
                        val type = sqlFragment(target.type, "type")
                        add("ALTER COLUMN $column TYPE $type USING $column::$type")
                    }
                    if (target.nullable != current.nullable) add("ALTER COLUMN $column ${if (target.nullable) "DROP" else "SET"} NOT NULL")
                    val default = target.default?.trim().orEmpty()
                    if (default != current.default.orEmpty()) {
                        add(
                            if (default.isEmpty()) {
                                "ALTER COLUMN $column DROP DEFAULT"
                            } else {
                                "ALTER COLUMN $column SET DEFAULT ${sqlFragment(default, "default")}"
                            },
                        )
                    }
                }
            val statements = mutableListOf<String>()
            if (clauses.isNotEmpty()) statements += "ALTER TABLE ${qualified(schema, table)} ${clauses.joinToString(", ")}"
            if (target.name != current.name) {
                statements += "ALTER TABLE ${qualified(schema, table)} RENAME COLUMN $column TO ${quote(target.name)}"
            }
            if (statements.isEmpty()) throw GantryException("nothing changed")
            return statements.joinToString(";\n")
        }

        override fun dropIndex(
            schema: String,
            table: String,
            index: String,
        ) = "DROP INDEX ${qualified(schema, index)}"
    }

    object MySql : Dialect() {
        override val id = "mysql"
        override val hasSchemas = false

        // Public key retrieval lets the default caching_sha2_password auth work without TLS on localhost.
        override fun jdbcUrl(
            port: Int,
            database: String,
        ) = "jdbc:mysql://127.0.0.1:$port/${urlPart(database)}?allowPublicKeyRetrieval=true&useSSL=false&$MYSQL_SAFE&$MYSQL_CLIENT"

        override fun quote(identifier: String) = "`" + identifier.replace("`", "``") + "`"

        override val databaseInfoSql =
            """
            SELECT VERSION(), DATABASE(),
                   (SELECT COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE())
            """.trimIndent()

        override val schemasSql: String? = null

        override val tablesSql =
            """
            SELECT TABLE_SCHEMA, TABLE_NAME,
                   CASE TABLE_TYPE WHEN 'VIEW' THEN 'view' ELSE 'table' END,
                   COALESCE(TABLE_ROWS, 0), COALESCE(DATA_LENGTH + INDEX_LENGTH, 0)
            FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()
            ORDER BY 2
            """.trimIndent()

        // String defaults come back unquoted; QUOTE() turns them back into SQL so they survive an edit.
        override val columnsSql =
            """
            SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE = 'YES',
                   CASE WHEN COLUMN_DEFAULT IS NULL THEN NULL
                        WHEN EXTRA LIKE '%DEFAULT_GENERATED%' THEN COLUMN_DEFAULT
                        ELSE QUOTE(COLUMN_DEFAULT) END,
                   COLUMN_KEY = 'PRI',
                   TRIM(REPLACE(UPPER(EXTRA), 'DEFAULT_GENERATED', ''))
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = COALESCE(NULLIF(?, ''), DATABASE()) AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
            """.trimIndent()

        override val indexesSql =
            """
            SELECT INDEX_NAME, MIN(NON_UNIQUE) = 0, INDEX_NAME = 'PRIMARY',
                   GROUP_CONCAT(COALESCE(COLUMN_NAME, EXPRESSION) ORDER BY SEQ_IN_INDEX SEPARATOR ', ')
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = COALESCE(NULLIF(?, ''), DATABASE()) AND TABLE_NAME = ?
            GROUP BY INDEX_NAME
            ORDER BY INDEX_NAME = 'PRIMARY' DESC, 1
            """.trimIndent()

        override val constraintsSql =
            """
            SELECT tc.CONSTRAINT_NAME, LOWER(tc.CONSTRAINT_TYPE),
                   CASE WHEN tc.CONSTRAINT_TYPE = 'FOREIGN KEY' THEN
                     (SELECT CONCAT('(', GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION), ') REFERENCES ',
                                    MIN(k.REFERENCED_TABLE_NAME), '(', GROUP_CONCAT(k.REFERENCED_COLUMN_NAME ORDER BY k.ORDINAL_POSITION), ')')
                      FROM information_schema.KEY_COLUMN_USAGE k
                      WHERE k.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND k.TABLE_NAME = tc.TABLE_NAME
                        AND k.CONSTRAINT_NAME = tc.CONSTRAINT_NAME)
                   WHEN tc.CONSTRAINT_TYPE = 'CHECK' THEN
                     (SELECT cc.CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS cc
                      WHERE cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME)
                   ELSE '' END
            FROM information_schema.TABLE_CONSTRAINTS tc
            WHERE tc.TABLE_SCHEMA = COALESCE(NULLIF(?, ''), DATABASE()) AND tc.TABLE_NAME = ?
            ORDER BY 2, 1
            """.trimIndent()

        override fun bindText(
            statement: PreparedStatement,
            index: Int,
            value: String?,
        ) {
            if (value == null) statement.setNull(index, Types.NULL) else statement.setString(index, value)
        }

        override fun renameTable(
            schema: String,
            table: String,
            newName: String,
        ) = "RENAME TABLE ${quote(table)} TO ${quote(newName)}"

        /** MODIFY needs the full definition; AUTO_INCREMENT is carried over or it would be silently dropped. */
        override fun alterColumn(
            schema: String,
            table: String,
            current: ColumnInfo,
            target: ColumnDef,
        ): String {
            val extra = target.extra ?: current.extra.takeIf { "AUTO_INCREMENT" in it }
            val definition = columnDefinition(target.copy(extra = extra))
            return "ALTER TABLE ${quote(table)} CHANGE COLUMN ${quote(current.name)} $definition"
        }

        override fun dropIndex(
            schema: String,
            table: String,
            index: String,
        ) = "DROP INDEX ${quote(index)} ON ${quote(table)}"
    }

    companion object {
        fun forService(service: String): Dialect =
            when (service) {
                "postgres" -> Postgres
                "mysql" -> MySql
                else -> throw GantryException("'$service' is not a SQL database")
            }

        val services = listOf("postgres", "mysql")
    }
}

/**
 * Types, defaults and extras are SQL typed in the UI and spliced into DDL. The dashboard already has a SQL
 * console, so this only stops a fragment from smuggling in a second statement or a comment.
 */
internal fun sqlFragment(
    text: String,
    what: String,
): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) throw GantryException("$what is empty")
    if (';' in trimmed || "--" in trimmed || "/*" in trimmed) throw GantryException("$what must be a single SQL expression: $trimmed")
    return trimmed
}

/**
 * MySQL driver features a connection never needs and that turn a crafted URL or a malicious server into file reads or
 * code execution: reading client files for `LOAD DATA LOCAL`, from URLs too, and deserializing Java objects.
 */
internal const val MYSQL_CLIENT = "connectionAttributes=program_name:$GANTRY_CLIENT"

internal const val MYSQL_SAFE = "allowLoadLocalInfile=false&allowUrlInLocalInfile=false&autoDeserialize=false"

/** [value] encoded for a JDBC URL's path, so a database name can't add `?options`. */
internal fun urlPart(value: String): String =
    java.net.URLEncoder
        .encode(value, Charsets.UTF_8)
        .replace("+", "%20")
