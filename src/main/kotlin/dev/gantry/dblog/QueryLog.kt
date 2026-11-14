package dev.gantry.dblog

import dev.gantry.engine.DATABASE_SERVICES
import dev.gantry.engine.Engine
import dev.gantry.engine.ExecResult
import dev.gantry.engine.GantryException
import dev.gantry.engine.scanBytesFromEnd
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.format.DateTimeParseException

private val json = Json { ignoreUnknownKeys = true }

/**
 * Reads the query log [file] a [QueryLogWriter] keeps, newest entry first, from the end of the file.
 *
 * [writer] empties the file from inside the proxy container that appends to it: truncating from the host side of a
 * virtiofs mount corrupts the log. Without a writer, or with the container stopped, [clear] empties it here.
 */
class QueryLog(
    private val file: Path,
    private val writer: ((content: ByteArray) -> ExecResult)? = null,
) {
    /** Empties the file in place, so the log starts over. */
    fun clear() {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return
        when (writer?.invoke(ByteArray(0))) {
            ExecResult.DONE -> return
            ExecResult.FAILED -> throw GantryException("could not clear $file: the proxy container could not empty it")
            ExecResult.NOT_RUNNING, null ->
                try {
                    val options =
                        arrayOf<OpenOption>(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)
                    FileChannel.open(file, *options).close()
                } catch (e: IOException) {
                    throw GantryException("could not clear $file: ${e.message}")
                }
        }
    }

    /**
     * The newest entries first, at most [limit]; [search] matches the SQL or the client, ignoring case; [from] and [to]
     * bound the time. Entries come without their [QueryEntry.columns] and [QueryEntry.rows] unless [withRows]: [get] has
     * them. With [after], reading stops at the entry with that id, so only later ones come back.
     *
     * Lines are pre-filtered on their raw bytes before decoding, and reading stops after a run of entries that ended
     * before [from]: the file is in completion order, give or take a multi-statement query's earlier results.
     */
    fun recent(
        limit: Int = 100,
        search: String? = null,
        from: Instant? = null,
        to: Instant? = null,
        after: String? = null,
        withRows: Boolean = false,
    ): List<QueryEntry> {
        val out = mutableListOf<QueryEntry>()
        if (limit <= 0) return out
        val needle = search?.let(::rawNeedle)
        var old = 0
        scanBytesFromEnd(file) { raw ->
            val head = headOf(raw)
            if (after != null && head?.id == after) return@scanBytesFromEnd false
            if (from != null && head != null) {
                if (head.end < from) {
                    old++
                    return@scanBytesFromEnd old < OLD_RUN
                }
                old = 0
            }
            if (to != null && head != null && head.start > to) return@scanBytesFromEnd true
            if (needle != null && !containsIgnoringAsciiCase(raw, needle)) return@scanBytesFromEnd true
            val entry = if (withRows) parse(raw) else parseSummary(raw)
            if (entry != null && matches(entry, search, from, to)) out += entry
            out.size < limit
        }
        return out
    }

    /** The id of the newest entry in the file, or null when it has none. */
    fun newestId(): String? {
        var id: String? = null
        scanBytesFromEnd(file) { raw ->
            id = headOf(raw)?.id ?: parseSummary(raw)?.id
            id == null
        }
        return id
    }

    /** The entry with [id], or null when it is not in the file. */
    fun get(id: String): QueryEntry? {
        var found: QueryEntry? = null
        val prefix = "{\"id\":${Json.encodeToString(String.serializer(), id)},".toByteArray(Charsets.UTF_8)
        scanBytesFromEnd(file) { raw ->
            if (raw.size >= prefix.size && (0 until prefix.size).all { raw[it] == prefix[it] }) found = parse(raw)
            found == null
        }
        return found
    }

    private fun matches(
        entry: QueryEntry,
        search: String?,
        from: Instant?,
        to: Instant?,
    ): Boolean {
        if (search != null &&
            !entry.sql.contains(search, ignoreCase = true) &&
            entry.client?.contains(search, ignoreCase = true) != true
        ) {
            return false
        }
        if (from == null && to == null) return true
        val time = instantOf(entry.time) ?: return false
        return (from == null || time >= from) && (to == null || time <= to)
    }

    private fun instantOf(text: String): Instant? =
        try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun parse(raw: ByteArray): QueryEntry? =
        try {
            json.decodeFromString<QueryEntry>(String(raw, Charsets.UTF_8))
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun parseSummary(raw: ByteArray): QueryEntry? =
        try {
            json.decodeFromString<Summary>(String(raw, Charsets.UTF_8)).let {
                QueryEntry(
                    it.id,
                    it.time,
                    it.durationMs,
                    it.service,
                    it.connectionId,
                    it.client,
                    it.sql,
                    it.params,
                    null,
                    null,
                    it.rowCount,
                    it.affected,
                    it.command,
                    it.error,
                    it.truncated,
                )
            }
        } catch (_: IllegalArgumentException) {
            null
        }

    /** A [QueryEntry] without its columns and rows, so decoding a listed line never builds them. */
    @Serializable
    private class Summary(
        val id: String,
        val time: String,
        val durationMs: Double,
        val service: String,
        val connectionId: Long,
        val client: String?,
        val sql: String,
        val params: List<String?>,
        val rowCount: Long?,
        val affected: Long?,
        val command: String?,
        val error: QueryError?,
        val truncated: Boolean,
    )

    private class Head(
        val id: String,
        val start: Instant,
        val end: Instant,
    )

    private companion object {
        /** How many entries in a row may end before `from` before reading stops. */
        const val OLD_RUN = 64

        // The writer puts id, time and durationMs first; a line that does not start so is decoded in full instead.
        val headPattern = Regex("""^\{"id":"([^"\\]*)","time":"([^"]*)","durationMs":([-0-9.eE+]+)""")

        fun headOf(raw: ByteArray): Head? {
            val text = String(raw, 0, minOf(raw.size, 160), Charsets.UTF_8)
            val m = headPattern.find(text) ?: return null
            return try {
                val start = Instant.parse(m.groupValues[2])
                val nanos = (m.groupValues[3].toDouble() * 1_000_000).toLong()
                Head(m.groupValues[1], start, start.plusNanos(nanos))
            } catch (_: RuntimeException) {
                null
            }
        }

        /** [search] as lowercase bytes when it appears verbatim in a JSON line (printable ASCII, no quote or backslash). */
        fun rawNeedle(search: String): ByteArray? =
            if (search.isNotEmpty() && search.all { it in ' '..'~' && it != '"' && it != '\\' }) {
                search.lowercase().toByteArray(Charsets.US_ASCII)
            } else {
                null
            }

        fun containsIgnoringAsciiCase(
            raw: ByteArray,
            needle: ByteArray,
        ): Boolean {
            val last = raw.size - needle.size
            var i = 0
            while (i <= last) {
                var j = 0
                while (j < needle.size && lower(raw[i + j]) == needle[j]) j++
                if (j == needle.size) return true
                i++
            }
            return false
        }

        fun lower(b: Byte): Byte = if (b in 'A'.code..'Z'.code) (b + 32).toByte() else b
    }
}

/** The services of [app] that run a database behind the query-log proxy. */
fun Engine.queryLoggedDatabases(app: String): List<String> {
    val loaded = readApp(app)
    return loaded.services.map { it.id }.filter { it in DATABASE_SERVICES && serviceOptions(loaded, it)["query_log"] == "true" }
}

/** [service] when it is a query-logged database of [app]; else a [GantryException] saying what is. */
fun Engine.requireQueryLogged(
    app: String,
    service: String,
): String {
    val logged = queryLoggedDatabases(app)
    if (service in logged) return service
    val hint = if (logged.isEmpty()) "it has no database with the query log on" else "query-logged databases: ${logged.joinToString()}"
    throw GantryException("'$service' is not a query-logged database of '$app' ($hint)")
}

/**
 * The query log of [service] in [app]: the file its database proxy writes under the app's `db-log` directory, emptied
 * from inside the proxy container `<app>-<service>`.
 */
fun Engine.queryLog(
    app: String,
    service: String,
): QueryLog =
    QueryLog(appDir(app).resolve("db-log").resolve("$service.jsonl")) { content ->
        docker.execWithInput("$app-$service", listOf("sh", "-c", "cat > /db-log/$service.jsonl"), content)
    }

/** Empties the query log of [service] in [app]. */
fun Engine.clearQueryLog(
    app: String,
    service: String,
) = queryLog(app, requireQueryLogged(app, service)).clear()

private val agoPattern = Regex("(\\d+)([smhd])")

/** [text] as an instant (ISO-8601), or as that long ago before [now]: `30s`, `15m`, `2h`, `1d`. */
fun parseQueryTime(
    text: String,
    now: Instant = Instant.now(),
): Instant {
    agoPattern.matchEntire(text.trim())?.let { m ->
        val tooFar = GantryException("'$text' is too far back")
        val amount = m.groupValues[1].toLongOrNull() ?: throw tooFar
        val unit =
            when (m.groupValues[2]) {
                "s" -> java.time.temporal.ChronoUnit.SECONDS
                "m" -> java.time.temporal.ChronoUnit.MINUTES
                "h" -> java.time.temporal.ChronoUnit.HOURS
                else -> java.time.temporal.ChronoUnit.DAYS
            }
        return try {
            now.minus(amount, unit)
        } catch (_: java.time.DateTimeException) {
            throw tooFar
        } catch (_: ArithmeticException) {
            throw tooFar
        }
    }
    return try {
        Instant.parse(text.trim())
    } catch (_: DateTimeParseException) {
        throw GantryException("'$text' is not a time: use an ISO instant like 2026-11-14T10:00:00Z, or how long ago like 15m, 2h, 1d")
    }
}
