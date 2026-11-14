package dev.gantry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import dev.gantry.dblog.QueryEntry
import dev.gantry.dblog.QueryLog
import dev.gantry.dblog.clearQueryLog
import dev.gantry.dblog.parseQueryTime
import dev.gantry.dblog.queryLog
import dev.gantry.dblog.queryLoggedDatabases
import dev.gantry.dblog.requireQueryLogged
import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private const val DEFAULT_QUERIES = 20
private const val FOLLOW_WINDOW = 200
private const val FOLLOW_INTERVAL_MS = 1000L
private const val SQL_WIDTH = 160
private val queryClock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

internal class DbCommand(
    engine: Engine,
) : CliktCommand(name = "db") {
    init {
        subcommands(DbQueries(engine), DbClear(engine))
    }

    override fun help(context: Context) = "Inspect an app's databases: the statements its apps ran, from the query log."

    override fun run() = Unit
}

/** [service] when given, else the app's only query-logged database; a usage error listing them when there are several. */
private fun resolveService(
    engine: Engine,
    app: String,
    service: String?,
): String {
    if (service != null) return engine.requireQueryLogged(app, service)
    val logged = engine.queryLoggedDatabases(app)
    return when (logged.size) {
        1 -> logged.single()
        0 -> throw GantryException("'$app' has no database with the query log on")
        else -> throw UsageError(
            "'$app' has several query-logged databases (${logged.joinToString()}); name one: gantry db queries $app <service>",
        )
    }
}

private class DbQueries(
    private val engine: Engine,
) : CliktCommand(name = "queries") {
    override fun help(context: Context) = "Show a database's recent statements, oldest first."

    private val app by argument()
    private val service by argument().optional()
    private val limit by option("--limit", help = "How many statements to show.").int().restrictTo(min = 1).default(DEFAULT_QUERIES)
    private val search by option("--search", metavar = "TEXT", help = "Only statements whose SQL or client contains this, ignoring case.")
    private val since by option(
        "--since",
        metavar = "WHEN",
        help = "Only statements from then on: how long ago (15m, 2h, 1d) or an ISO time.",
    )
    private val from by option("--from", metavar = "TIME", help = "Only statements at or after this ISO time (or how long ago).")
    private val to by option("--to", metavar = "TIME", help = "Only statements at or before this ISO time (or how long ago).")
    private val follow by option("--follow", "-f", help = "Keep printing new statements until interrupted.").flag()

    override fun run() {
        val name = resolveService(engine, app, service)
        val lower = (from ?: since)?.let { parseQueryTime(it) }
        val upper = to?.let { parseQueryTime(it) }
        val log = engine.queryLog(app, name)
        echo(queryHeader())
        if (!follow) {
            log.recent(limit, search, lower, upper).asReversed().forEach { echo(queryRow(it)) }
            return
        }
        val follower = QueryFollower(log, search, lower, upper)
        follower.poll().takeLast(limit).forEach { echo(queryRow(it)) }
        while (true) {
            Thread.sleep(FOLLOW_INTERVAL_MS)
            follower.poll().forEach { echo(queryRow(it)) }
        }
    }
}

private class DbClear(
    private val engine: Engine,
) : CliktCommand(name = "clear") {
    override fun help(context: Context) = "Delete a database's recorded statements, so its query log starts over."

    private val app by argument()
    private val service by argument().optional()

    override fun run() {
        val name = resolveService(engine, app, service)
        engine.clearQueryLog(app, name)
        echo("Cleared the query log of '$name' in '$app'")
    }
}

/** Polls a query log for statements it has not returned before, oldest first; statements are told apart by id. */
internal class QueryFollower(
    private val log: QueryLog,
    private val search: String?,
    private val from: Instant?,
    private val to: Instant?,
) {
    private var seen = emptySet<String>()

    fun poll(): List<QueryEntry> {
        val window = log.recent(FOLLOW_WINDOW, search, from, to)
        val fresh = window.filter { it.id !in seen }.asReversed()
        seen = window.map { it.id }.toSet()
        return fresh
    }
}

private fun queryHeader() = "TIME           MS  RESULT        SQL"

private fun queryRow(entry: QueryEntry): String {
    val time =
        try {
            queryClock.format(Instant.parse(entry.time))
        } catch (_: DateTimeParseException) {
            "-"
        }
    val result =
        when {
            entry.error != null -> "ERR ${entry.error.code}"
            entry.rowCount != null -> "${entry.rowCount} rows"
            entry.affected != null -> "${entry.affected} affected"
            else -> "ok"
        }
    val sql =
        entry.sql
            .replace(Regex("\\s+"), " ")
            .trim()
            .let { if (it.length > SQL_WIDTH) it.take(SQL_WIDTH) + "..." else it }
    return "${time.padEnd(8)} ${"%7.1f".format(entry.durationMs)}  ${result.padEnd(13)} $sql"
}
