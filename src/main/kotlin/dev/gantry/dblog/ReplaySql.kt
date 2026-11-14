package dev.gantry.dblog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** A statement with its parameter values written into it; [complete] is false when a value was cut or is missing. */
data class Replay(
    val sql: String,
    val complete: Boolean,
)

/** Rebuilds a logged statement as plain SQL, ready to run in psql, mysql or the SQL explorer. */
object ReplaySql {
    private val number = Regex("""-?\d+(\.\d+)?([eE][-+]?\d+)?""")
    private val pgNumbers = setOf("int2", "int4", "int8", "float4", "float8", "numeric", "oid")
    private val pgTrue = setOf("t", "true", "1", "y", "yes", "on")
    private val pgFalse = setOf("f", "false", "0", "n", "no", "off")
    private val myNumbers =
        setOf("tinyint", "smallint", "mediumint", "int", "bigint", "float", "double", "decimal", "year")
            .flatMap { listOf(it, "$it unsigned") }
            .toSet()
    private val dollarTag = Regex("""\$([A-Za-z_\u0080-￿][A-Za-z_0-9\u0080-￿]*)?\$""")

    fun inline(
        entry: QueryEntry,
        dialect: Dialect,
    ): Replay {
        if (entry.params.isEmpty()) return Replay(entry.sql, !entry.inexact)
        var complete = !entry.inexact

        fun literal(i: Int): String? {
            if (i !in entry.params.indices) {
                complete = false
                return null
            }
            val type = entry.paramTypes.getOrNull(i)
            val value = entry.params[i]
            return if (dialect == Dialect.POSTGRES) pgLiteral(value, type) else myLiteral(value, type)
        }
        val sql = if (dialect == Dialect.POSTGRES) pgReplace(entry.sql, ::literal) else myReplace(entry.sql, ::literal)
        return Replay(sql, complete)
    }

    private fun pgLiteral(
        value: String?,
        type: String?,
    ): String {
        if (value == null) return "NULL"
        val quoted = "'" + value.replace("'", "''") + "'"
        return when {
            type == null -> quoted
            type == "bool" && value.lowercase() in pgTrue -> "TRUE"
            type == "bool" && value.lowercase() in pgFalse -> "FALSE"
            type in pgNumbers && number.matches(value) -> if (value.startsWith("-")) "($value)" else value
            else -> "$quoted::$type"
        }
    }

    private fun myLiteral(
        value: String?,
        type: String?,
    ): String {
        if (value == null) return "NULL"
        return when {
            type == "binary" && value.startsWith("\\x") -> "X'${value.substring(2)}'"
            type in myNumbers && number.matches(value) -> value
            else -> "'" + value.replace("\\", "\\\\").replace("'", "''").replace("\u0000", "\\0") + "'"
        }
    }

    private fun identChar(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_' || c == '$')

    private fun pgReplace(
        sql: String,
        literal: (Int) -> String?,
    ): String {
        val out = StringBuilder(sql.length + 64)
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            val next = sql.getOrNull(i + 1)
            val prev = sql.getOrNull(i - 1)
            val end =
                when {
                    c == '\'' -> quoted(sql, i, '\'', backslash = prev != null && prev in "eE" && !identChar(sql.getOrNull(i - 2)))
                    c == '"' -> quoted(sql, i, '"', backslash = false)
                    c == '-' && next == '-' -> lineEnd(sql, i)
                    c == '/' && next == '*' -> blockEnd(sql, i, nested = true)
                    c == '$' && !identChar(prev) && next != null && next.isDigit() -> {
                        var j = i + 1
                        while (j < sql.length && sql[j].isDigit()) j++
                        val replacement = sql.substring(i + 1, j).toIntOrNull()?.let { literal(it - 1) }
                        out.append(replacement ?: sql.substring(i, j))
                        i = j
                        continue
                    }
                    c == '$' && !identChar(prev) -> dollarEnd(sql, i)
                    else -> i + 1
                }
            out.append(sql, i, end)
            i = end
        }
        return out.toString()
    }

    private fun myReplace(
        sql: String,
        literal: (Int) -> String?,
    ): String {
        val out = StringBuilder(sql.length + 64)
        var index = 0
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            val next = sql.getOrNull(i + 1)
            val end =
                when {
                    c == '\'' || c == '"' -> quoted(sql, i, c, backslash = true)
                    c == '`' -> quoted(sql, i, '`', backslash = false)
                    c == '#' -> lineEnd(sql, i)
                    c == '-' && next == '-' && sql.getOrNull(i + 2).let { it == null || it.isWhitespace() } -> lineEnd(sql, i)
                    c == '/' && next == '*' -> blockEnd(sql, i, nested = false)
                    c == '?' -> {
                        out.append(literal(index++) ?: "?")
                        i++
                        continue
                    }
                    else -> i + 1
                }
            out.append(sql, i, end)
            i = end
        }
        return out.toString()
    }

    private fun quoted(
        sql: String,
        from: Int,
        quote: Char,
        backslash: Boolean,
    ): Int {
        var i = from + 1
        while (i < sql.length) {
            val c = sql[i]
            when {
                backslash && c == '\\' -> i += 2
                c == quote && sql.getOrNull(i + 1) == quote -> i += 2
                c == quote -> return i + 1
                else -> i++
            }
        }
        return sql.length
    }

    private fun lineEnd(
        sql: String,
        from: Int,
    ) = sql.indexOf('\n', from).let { if (it < 0) sql.length else it + 1 }

    private fun blockEnd(
        sql: String,
        from: Int,
        nested: Boolean,
    ): Int {
        var depth = 0
        var i = from
        while (i < sql.length - 1) {
            when {
                sql[i] == '/' && sql[i + 1] == '*' && (nested || depth == 0) -> {
                    depth++
                    i += 2
                }
                sql[i] == '*' && sql[i + 1] == '/' -> {
                    depth--
                    i += 2
                    if (depth == 0) return i
                }
                else -> i++
            }
        }
        return sql.length
    }

    private fun dollarEnd(
        sql: String,
        from: Int,
    ): Int {
        val tag = dollarTag.matchAt(sql, from)?.value ?: return from + 1
        val end = sql.indexOf(tag, from + tag.length)
        return if (end < 0) sql.length else end + tag.length
    }
}

/** The dialect of a query-logged database service. */
fun dialectOfService(service: String): Dialect = if (service == "mysql") Dialect.MYSQL else Dialect.POSTGRES

/** This entry as JSON with `replaySql` and `replayComplete` added, for the detail views. */
fun QueryEntry.withReplay(
    json: Json,
    dialect: Dialect = dialectOfService(service),
): JsonObject {
    val replay = ReplaySql.inline(this, dialect)
    val fields = json.encodeToJsonElement(QueryEntry.serializer(), this).jsonObject
    return JsonObject(fields + mapOf("replaySql" to JsonPrimitive(replay.sql), "replayComplete" to JsonPrimitive(replay.complete)))
}
