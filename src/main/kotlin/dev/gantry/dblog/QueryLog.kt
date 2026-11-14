package dev.gantry.dblog

import dev.gantry.engine.DATABASE_SERVICES
import dev.gantry.engine.Engine
import dev.gantry.engine.ExecResult
import dev.gantry.engine.GantryException
import dev.gantry.engine.scanBytesFromEnd
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

    /** The newest entries first, at most [limit]; [search] matches the SQL or the client, ignoring case; [from] and [to] bound the time. */
    fun recent(
        limit: Int = 100,
        search: String? = null,
        from: Instant? = null,
        to: Instant? = null,
    ): List<QueryEntry> {
        val out = mutableListOf<QueryEntry>()
        if (limit <= 0) return out
        scanBytesFromEnd(file) { raw ->
            val entry = parse(raw)
            if (entry != null && matches(entry, search, from, to)) out += entry
            out.size < limit
        }
        return out
    }

    /** The entry with [id], or null when it is not in the file. */
    fun get(id: String): QueryEntry? {
        var found: QueryEntry? = null
        scanBytesFromEnd(file) { raw ->
            found = parse(raw)?.takeIf { it.id == id }
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
        val amount = m.groupValues[1].toLong()
        val unit =
            when (m.groupValues[2]) {
                "s" -> java.time.temporal.ChronoUnit.SECONDS
                "m" -> java.time.temporal.ChronoUnit.MINUTES
                "h" -> java.time.temporal.ChronoUnit.HOURS
                else -> java.time.temporal.ChronoUnit.DAYS
            }
        return now.minus(amount, unit)
    }
    return try {
        Instant.parse(text.trim())
    } catch (_: DateTimeParseException) {
        throw GantryException("'$text' is not a time: use an ISO instant like 2026-11-14T10:00:00Z, or how long ago like 15m, 2h, 1d")
    }
}
