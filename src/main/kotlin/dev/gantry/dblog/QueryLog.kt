package dev.gantry.dblog

import dev.gantry.engine.Engine
import dev.gantry.engine.scanBytesFromEnd
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException

private val json = Json { ignoreUnknownKeys = true }

/** Reads the query log [file] a [QueryLogWriter] keeps, newest entry first, from the end of the file. */
class QueryLog(
    private val file: Path,
) {
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

/** The query log of [service] in [app]: the file its database proxy writes under the app's `db-log` directory. */
fun Engine.queryLog(
    app: String,
    service: String,
): QueryLog = QueryLog(appDir(app).resolve("db-log").resolve("$service.jsonl"))
