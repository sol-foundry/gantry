package dev.gantry.db

import dev.gantry.engine.Docker
import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.SourceSettings
import dev.gantry.engine.checkSourceHost
import kotlinx.serialization.Serializable
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

enum class SourceCheck { OK, AUTH, UNREACHABLE }

/** Outcome of connecting to a source; [version] is the server's `major.minor` when it connected. */
data class SourceProbe(
    val result: SourceCheck,
    val version: String?,
    val message: String,
)

@Serializable
data class SyncJob(
    val id: String,
    val app: String,
    val service: String,
    val phase: String,
    val state: String,
    val log: List<String>,
    val message: String?,
    val startedAt: String,
    val tables: Int? = null,
    val flywayVersion: String? = null,
    /** The local database is half-copied: by this sync, or by an earlier one that a later attempt did not replace. */
    val incomplete: Boolean = false,
    /** The tables this sync copies; empty for a whole-database sync. */
    val scope: List<String> = emptyList(),
)

/** The Docker side of a sync, behind an interface so tests never reach the daemon. */
interface SyncContainers {
    fun upService(
        app: String,
        service: String,
    )

    fun run(
        args: List<String>,
        env: Map<String, String>,
        onLine: (String) -> Unit,
    ): Int

    /** Removes a container by name if it exists; never fails. */
    fun remove(name: String)
}

private class DockerContainers(
    private val docker: Docker,
) : SyncContainers {
    override fun upService(
        app: String,
        service: String,
    ) = docker.upService(app, service)

    override fun run(
        args: List<String>,
        env: Map<String, String>,
        onLine: (String) -> Unit,
    ) = docker.runContainer(args, env, onLine)

    override fun remove(name: String) = docker.removeContainer(name)
}

/**
 * Replaces a local SQL database with a full copy of a source database (usually stage). The dump and restore run
 * in a throwaway container on the app's network, using the database's own tools at a version new enough for both
 * servers, so nothing needs installing on the laptop.
 */
class Sync(
    private val engine: Engine,
    private val connect: (url: String, user: String, password: String) -> Connection = { url, user, password ->
        DriverManager.getConnection(url, user, password)
    },
    private val containers: SyncContainers = DockerContainers(engine.docker),
) {
    private val jobs = ConcurrentHashMap<String, SyncJob>()
    private val running = ConcurrentHashMap.newKeySet<String>()

    // Probing the lock file opens and closes it, and closing any descriptor drops this process's lock on the file,
    // so a probe must never overlap a claim being taken.
    private val claims = Any()

    /**
     * Connects to the source from the host JVM, where VPN routes and tunnels apply. A rejected saved password is
     * forgotten so it is asked for again; errors other than auth and reachability carry the database's message.
     */
    fun check(
        app: String,
        service: String,
        source: SourceSettings,
        password: String,
    ): SourceProbe {
        val id = serviceId(app, service)
        val dialect = Dialect.forService(id)
        if (!source.complete()) throw GantryException("source of $id is incomplete: host, port, database and user are required")
        checkSourceHost(source.host)
        return try {
            connect(sourceUrl(dialect, source), source.user, password).use { connection ->
                val meta = connection.metaData
                SourceProbe(
                    SourceCheck.OK,
                    "${meta.databaseMajorVersion}.${meta.databaseMinorVersion}",
                    "connected to ${describe(source)} (${meta.databaseProductVersion})",
                )
            }
        } catch (e: SQLException) {
            when (classify(e)) {
                SourceCheck.AUTH -> {
                    // Only the saved password is known bad; a newly typed one that fails says nothing about it.
                    if (engine.secrets.password(app, id) == password) engine.secrets.forget(app, id)
                    SourceProbe(SourceCheck.AUTH, null, "Authentication failed")
                }

                SourceCheck.UNREACHABLE -> {
                    SourceProbe(
                        SourceCheck.UNREACHABLE,
                        null,
                        "cannot reach ${source.host}:${source.port} (${e.message}); " +
                            "if the database is on a private network, connect the VPN or start the tunnel",
                    )
                }

                else -> {
                    throw GantryException("${describe(source)}: ${e.message}")
                }
            }
        }
    }

    /** Starts a sync on a background thread and returns its job at once; follow it with [job]. */
    fun start(
        app: String,
        service: String,
        source: SourceSettings,
        password: String,
        tables: List<String> = emptyList(),
        onLine: (String) -> Unit = {},
    ): SyncJob {
        val run = begin(app, service, source, password, tables, onLine)
        val initial = run.job
        thread(name = "gantry-sync-${run.key}", isDaemon = true) { execute(run, source, password) }
        return initial
    }

    /** Runs a sync on the calling thread and returns the finished job, failed or not. */
    fun runBlocking(
        app: String,
        service: String,
        source: SourceSettings,
        password: String,
        tables: List<String> = emptyList(),
        onLine: (String) -> Unit,
    ): SyncJob = execute(begin(app, service, source, password, tables, onLine), source, password)

    /**
     * The running or most recent sync of that database since this process started; without one, a sync that a
     * restart or kill cut off mid-copy (in any gantry process) is reported as failed, since its data is incomplete.
     */
    fun job(
        app: String,
        service: String,
    ): SyncJob? {
        val id = engine.catalog.resolve(service).id
        val key = "$app/$id"
        val current = jobs[key] ?: return interrupted(app, id, key)
        // A later attempt that failed before clearing (wrong password, unreachable host) leaves the earlier half copy.
        val leftover = current.state != "running" && Files.exists(markerFile(app, id))
        return if (leftover) current.copy(incomplete = true) else current
    }

    private fun interrupted(
        app: String,
        id: String,
        key: String,
    ): SyncJob? {
        val marker = markerFile(app, id)
        if (!Files.exists(marker) || synchronized(claims) { key in running || runningElsewhere(app, id) }) return null
        val (jobId, startedAt) = runCatching { Files.readAllLines(marker) }.getOrDefault(emptyList()) + listOf("", "")
        val database = runCatching { engine.serviceOptions(engine.loadApp(app), id)["name"] }.getOrNull() ?: id
        return SyncJob(
            id = jobId.ifEmpty { "interrupted" },
            app = app,
            service = id,
            phase = INTERRUPTED_PHASE,
            state = "failed",
            log = emptyList(),
            message = "A previous sync was interrupted; the local database $database is incomplete. Retry the sync or destroy the app.",
            startedAt = startedAt,
            incomplete = true,
        )
    }

    /** True while another gantry process holds the sync lock, so its marker is a copy in progress, not a leftover. */
    private fun runningElsewhere(
        app: String,
        id: String,
    ): Boolean {
        val lockFile = lockFile(app, id)
        if (!Files.exists(lockFile)) return false
        return runCatching {
            FileChannel.open(lockFile, StandardOpenOption.WRITE).use { channel ->
                val lock = channel.tryLock() ?: return@runCatching true
                lock.release()
                false
            }
        }.getOrDefault(true)
    }

    private fun lockFile(
        app: String,
        id: String,
    ) = engine.appDir(app).resolve(".sync-$id.lock")

    private fun markerFile(
        app: String,
        id: String,
    ) = engine.appDir(app).resolve(".sync-$id.incomplete")

    private fun serviceId(
        app: String,
        service: String,
    ): String {
        val id = engine.catalog.resolve(service).id
        Dialect.forService(id)
        if (engine.loadApp(app).service(id) == null) throw GantryException("app '$app' has no $id service")
        return id
    }

    /** Claims the database, refusing while another sync of it runs here or in another gantry process. */
    private fun begin(
        app: String,
        service: String,
        source: SourceSettings,
        password: String,
        tables: List<String>,
        onLine: (String) -> Unit,
    ): Run {
        val id = serviceId(app, service)
        if (!source.complete()) throw GantryException("source of $id is incomplete: host, port, database and user are required")
        checkDatabaseName(Dialect.forService(id), source.database)
        // Checked before claiming, so a bad name never blocks the database.
        val scope = if (tables.isEmpty()) emptyList() else parseTables(Dialect.forService(id), tables).map { it.toString() }
        val key = "$app/$id"
        val lock = synchronized(claims) { claim(app, id, key) }
        val job =
            SyncJob(
                id = UUID.randomUUID().toString(),
                app = app,
                service = id,
                phase = "starting local database",
                state = "running",
                log = emptyList(),
                message = null,
                startedAt = Instant.now().toString(),
                scope = scope,
            )
        jobs[key] = job
        return Run(key, job, lock, password, onLine)
    }

    private fun claim(
        app: String,
        id: String,
        key: String,
    ): FileLock {
        if (!running.add(key)) throw GantryException("already syncing $id of $app; wait for it to finish")
        return try {
            val lockFile = lockFile(app, id)
            Files.createDirectories(lockFile.parent)
            val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val acquired = runCatching { channel.tryLock() }.getOrElse { if (it is OverlappingFileLockException) null else throw it }
            if (acquired == null) {
                channel.close()
                throw GantryException("already syncing $id of $app in another gantry process; wait for it to finish")
            }
            acquired
        } catch (e: Exception) {
            running.remove(key)
            throw e
        }
    }

    private fun execute(
        run: Run,
        source: SourceSettings,
        password: String,
    ): SyncJob {
        val app = run.job.app
        val id = run.job.service
        val dialect = Dialect.forService(id)
        val started = System.nanoTime()
        try {
            run.phase("starting local database")
            containers.upService(app, id)

            run.phase("checking source")
            val probe = check(app, id, source, password)
            if (probe.result != SourceCheck.OK) return run.fail(probe.message)
            run.log(probe.message)

            val target = DatabaseTarget.resolve(engine, app, id)
            val options = engine.serviceOptions(engine.loadApp(app), id)
            newerSource(dialect, probe.version, options["version"])?.let { return run.fail(it) }
            val image = clientImage(dialect, probe.version, options["version"])
            val env =
                when (dialect) {
                    Dialect.Postgres -> mapOf("PGPASSWORD" to target.password, "SRC_PW" to password)
                    Dialect.MySql -> mapOf("LOCAL_PW" to options.getValue("root_password"), "SRC_PW" to password)
                }

            if (run.job.scope.isNotEmpty()) {
                return copyTables(run, source, password, target, parseTables(dialect, run.job.scope), image, env, started)
            }

            // Built before clearing: it rejects unsafe database names while local data is still intact.
            val container = "gantry-sync-$app-$id"
            val args = listOf("--name=$container") + copyScript(dialect, source, target, "gantry-${app}_default", image)
            // A copy left running by a killed gantry would keep writing into the database this one is about to clear.
            containers.remove(container)

            // Written before the first destructive step and removed only on success, so a crash is still reported.
            val marker = markerFile(app, id)
            Files.writeString(marker, "${run.job.id}\n${run.job.startedAt}\n")
            run.phase("clearing local data")
            val exit =
                containers.run(args, env) { line ->
                    if (line == COPYING_MARKER) run.phase("copying") else run.log(line)
                }
            if (exit != 0) return run.fail(incomplete(target.database, run))
            Files.deleteIfExists(marker)

            val tables = runCatching { query(target, tableCountSql(dialect))?.toInt() }.getOrNull()
            val flyway = runCatching { flywayVersion(target) }.getOrNull()
            // The data is in place from here on: a bookkeeping failure must not be reported as an incomplete copy.
            val saveFailure =
                runCatching {
                    // Source first: moving it to another database drops the saved password, which must not take the new one.
                    engine.setSource(app, id, source)
                    engine.secrets.setPassword(app, id, password)
                }.exceptionOrNull()
            val took = Duration.ofNanos(System.nanoTime() - started).toSeconds()
            val summary =
                buildString {
                    append("Copied ${describe(source)} into ${target.database} in ${took}s")
                    tables?.let { append(": $it tables") }
                    flyway?.let { append(", Flyway version $it") }
                    saveFailure?.let { append(". Copied, but saving the source settings failed: ${it.message ?: it}") }
                }
            return run.succeed(summary, tables, flyway)
        } catch (e: Throwable) {
            // Throwable: on the background thread anything escaping would leave the job running forever.
            val copying = run.job.phase == "clearing local data" || run.job.phase == "copying"
            e.message?.let { run.log(it) }
            val database = runCatching { engine.serviceOptions(engine.loadApp(app), id)["name"] }.getOrNull() ?: id
            return run.fail(if (copying) incomplete(database, run) else e.message ?: e.toString())
        } finally {
            run.release()
        }
    }

    /**
     * Copies only [tables]. Each table is replaced in its own transaction, so a failure leaves that table's local
     * rows as they were: unlike a whole-database sync, there is never a half-copied state to report.
     */
    private fun copyTables(
        run: Run,
        source: SourceSettings,
        password: String,
        target: DatabaseTarget,
        tables: List<TableRef>,
        image: String,
        env: Map<String, String>,
        started: Long,
    ): SyncJob {
        val app = run.job.app
        val id = run.job.service
        val container = "gantry-sync-$app-$id"
        val args = listOf("--name=$container") + tableCopyScript(target.dialect, source, target, tables, "gantry-${app}_default", image)
        containers.remove(container)
        run.phase("copying tables")
        val copied = linkedMapOf<String, String>()
        val exit =
            containers.run(args, env) { line ->
                if (line.startsWith(TABLE_DONE)) {
                    val (table, rows) = line.removePrefix(TABLE_DONE).trim().split(" ", limit = 2) + listOf("?")
                    copied[table] = rows
                    run.log("copied $table ($rows rows)")
                } else {
                    run.log(line)
                }
            }
        if (exit != 0) {
            val failed = tables.map { it.toString() }.firstOrNull { it !in copied } ?: "a table"
            val done = if (copied.isEmpty()) "" else " Already copied: ${copied.keys.joinToString()}."
            return run.fail(
                "Copying $failed failed; its local rows were left unchanged.$done" +
                    run.job.log
                        .takeLast(FAILURE_LOG_LINES)
                        .joinToString("") { "\n$it" },
            )
        }
        val saveFailure =
            runCatching {
                engine.setSource(app, id, source)
                engine.secrets.setPassword(app, id, password)
            }.exceptionOrNull()
        val took = Duration.ofNanos(System.nanoTime() - started).toSeconds()
        val summary =
            buildString {
                append("Copied ${copied.entries.joinToString { (table, rows) -> "$table ($rows rows)" }}")
                append(" from ${describe(source)} into ${target.database} in ${took}s")
                saveFailure?.let { append(". Copied, but saving the source settings failed: ${it.message ?: it}") }
            }
        return run.succeed(summary, null, null)
    }

    private fun incomplete(
        database: String,
        run: Run,
    ) = "Copy failed; the local database $database is incomplete. Retry the sync or destroy the app." +
        run.job.log
            .takeLast(FAILURE_LOG_LINES)
            .joinToString("") { "\n$it" }

    private fun query(
        target: DatabaseTarget,
        sql: String,
    ): String? =
        connect(target.jdbcUrl, target.user, target.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }

    private fun flywayVersion(target: DatabaseTarget): String? {
        val inDatabase = if (target.dialect == Dialect.MySql) "AND table_schema = DATABASE()" else ""
        val schema =
            query(
                target,
                "SELECT table_schema FROM information_schema.tables WHERE table_name = 'flyway_schema_history' " +
                    "AND table_schema NOT IN ('information_schema', 'pg_catalog') $inDatabase",
            ) ?: return null
        return query(
            target,
            "SELECT version FROM ${target.dialect.qualified(schema, "flyway_schema_history")} " +
                "WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",
        )
    }

    /** One claimed sync: publishes each change of its job and releases the claim when done. */
    private inner class Run(
        val key: String,
        @Volatile var job: SyncJob,
        private val lock: FileLock,
        private val password: String,
        private val onLine: (String) -> Unit,
    ) {
        fun phase(name: String) {
            update { it.copy(phase = name) }
            log("==> $name")
        }

        @Synchronized
        fun log(line: String) {
            val masked = if (password.isEmpty()) line else line.replace(password, "****")
            update { it.copy(log = (it.log + masked).takeLast(MAX_LOG_LINES)) }
            // A broken listener (e.g. a closed terminal) must not abort the copy or the failure handling.
            runCatching { onLine(masked) }
        }

        fun fail(message: String): SyncJob = update { it.copy(state = "failed", message = mask(message)) }

        fun succeed(
            message: String,
            tables: Int?,
            flywayVersion: String?,
        ): SyncJob {
            update { it.copy(phase = "done") }
            log("==> done")
            return update { it.copy(state = "succeeded", message = message, tables = tables, flywayVersion = flywayVersion) }
        }

        fun release() {
            runCatching { lock.channel().use { lock.release() } }
            running.remove(key)
        }

        private fun mask(text: String) = if (password.isEmpty()) text else text.replace(password, "****")

        @Synchronized
        private fun update(change: (SyncJob) -> SyncJob): SyncJob {
            job = change(job)
            jobs[key] = job
            return job
        }
    }

    private companion object {
        const val MAX_LOG_LINES = 500
        const val FAILURE_LOG_LINES = 15
    }
}

/** Phase of the failed job reported for a sync that was cut off mid-copy by a restart or kill. */
const val INTERRUPTED_PHASE = "interrupted"

/** Printed by the copy script once the local database is empty and the copy begins. */
internal const val COPYING_MARKER = "gantry-phase: copying"

/** Printed by the table copy script after each table, with its row count. */
internal const val TABLE_DONE = "gantry-table-done: "

/** A table to sync. [schema] is null for MySQL, where the database is the schema. */
data class TableRef(
    val schema: String?,
    val name: String,
) {
    override fun toString() = if (schema == null) name else "$schema.$name"
}

private val identifierRule = Regex("^[A-Za-z_][A-Za-z0-9_$]*$")

/**
 * Parses table names given by the user: `table` or, for Postgres, `schema.table` (default `public`). Names are kept
 * to plain identifiers, since they end up in SQL, a shell script and pg_dump's table pattern.
 */
internal fun parseTables(
    dialect: Dialect,
    names: List<String>,
): List<TableRef> {
    if (names.isEmpty()) throw GantryException("name at least one table")
    return names
        .map { raw ->
            val parts = raw.trim().split('.')
            val ref =
                when {
                    parts.size == 1 -> TableRef(if (dialect == Dialect.Postgres) "public" else null, parts[0])
                    parts.size == 2 && dialect == Dialect.Postgres -> TableRef(parts[0], parts[1])
                    dialect == Dialect.MySql -> throw GantryException(
                        "table '$raw': give just the table name (the database is the source's)",
                    )
                    else -> throw GantryException("table '$raw': use table or schema.table")
                }
            if (listOfNotNull(ref.schema, ref.name).any { !identifierRule.matches(it) }) {
                throw GantryException("table '$raw' isn't a plain name (letters, digits, '_' and '$')")
            }
            ref
        }.distinct()
}

/**
 * Arguments after `docker run --rm` that replace the rows of [tables] in the local database with the source's. Each
 * table is dumped to a file first and then loaded in one transaction (with foreign-key checks off for it), so a
 * failed dump or load leaves its local rows untouched. A table missing locally is created from the source's
 * definition. Passwords are only referenced as environment variables, as in [copyScript].
 */
internal fun tableCopyScript(
    dialect: Dialect,
    source: SourceSettings,
    target: DatabaseTarget,
    tables: List<TableRef>,
    network: String,
    image: String = clientImage(dialect, null, null),
): List<String> {
    checkSourceHost(source.host)
    checkDatabaseName(dialect, source.database)
    checkDatabaseName(dialect, target.database)
    val srcHost = shq(containerHost(source.host))
    val db = shq(target.database)
    val script =
        when (dialect) {
            Dialect.Postgres -> {
                val local = "-h postgres -p 5432 -U ${shq(target.user)} -d $db"
                listOf(
                    "set -eu",
                    "set -o pipefail",
                    "umask 077",
                    "printf '*:*:*:*:%s\\n' \"\$(printf '%s' \"\$SRC_PW\" | sed 's/[\\\\:]/\\\\&/g')\" > /tmp/source.pgpass",
                ) +
                    tables.flatMap { table ->
                        val quoted = Dialect.Postgres.qualified(table.schema!!, table.name)
                        val dump =
                            "env -u PGPASSWORD PGPASSFILE=/tmp/source.pgpass pg_dump -h $srcHost -p ${source.port} " +
                                "-U ${shq(
                                    source.user,
                                )} -d ${shq(source.database)} --strict-names --no-owner --no-privileges -t ${shq(quoted)}"
                        listOf(
                            "echo ${shq("Copying $table")}",
                            "if [ \"\$(psql $local -tAc ${shq("SELECT to_regclass('$quoted') IS NOT NULL")})\" = t ]; then",
                            "  { echo ${shq("SET session_replication_role = replica;")}; echo ${shq("DELETE FROM $quoted;")}; " +
                                "$dump --data-only; } > /tmp/table.sql",
                            "else",
                            "  $dump > /tmp/table.sql",
                            "  echo ${shq("$table doesn't exist locally; creating it from the source")}",
                            "fi",
                            // Errors still reach the log on stderr; the statements' own results are just noise.
                            "psql $local -v ON_ERROR_STOP=1 --single-transaction -q -f /tmp/table.sql > /dev/null",
                            "echo \"$TABLE_DONE$table \$(psql $local -tAc ${shq("SELECT count(*) FROM $quoted")})\"",
                        )
                    }
            }

            Dialect.MySql -> {
                val local = "--defaults-extra-file=/tmp/local.cnf -h mysql -P 3306 -u root"
                val sqlString = { value: String -> "'" + value.replace("\\", "\\\\").replace("'", "''") + "'" }
                listOf(
                    "set -euo pipefail",
                    "umask 077",
                    "cnf() { printf '[client]\\npassword=\"%s\"\\n' \"\$(printf '%s' \"\$1\" | sed 's/[\\\\\"]/\\\\&/g')\"; }",
                    "cnf \"\$LOCAL_PW\" > /tmp/local.cnf",
                    "cnf \"\$SRC_PW\" > /tmp/source.cnf",
                ) +
                    tables.flatMap { table ->
                        val quoted = Dialect.MySql.quote(table.name)
                        val dump =
                            "mysqldump --defaults-extra-file=/tmp/source.cnf -h $srcHost -P ${source.port} -u ${shq(source.user)} " +
                                "--single-transaction --set-gtid-purged=OFF --no-tablespaces --skip-lock-tables --skip-add-locks " +
                                "--skip-disable-keys ${shq(source.database)} ${shq(table.name)}"
                        val exists =
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ${sqlString(target.database)} " +
                                "AND table_name = ${sqlString(table.name)}"
                        // Definers name source accounts that do not exist locally; without them, objects belong to root.
                        val noDefiners = "sed -E 's/DEFINER=`[^`]+`@`[^`]+`//g'"
                        listOf(
                            "echo ${shq("Copying $table")}",
                            "if [ \"\$(mysql $local -N -e ${shq(exists)})\" = 1 ]; then",
                            "  { echo ${shq("SET FOREIGN_KEY_CHECKS=0; START TRANSACTION; DELETE FROM $quoted;")}; " +
                                "$dump --no-create-info --skip-triggers | $noDefiners; echo ${shq("COMMIT;")}; } > /tmp/table.sql",
                            "else",
                            "  $dump --triggers | $noDefiners > /tmp/table.sql",
                            "  echo ${shq("$table doesn't exist locally; creating it from the source")}",
                            "fi",
                            "mysql $local --default-character-set=utf8mb4 $db < /tmp/table.sql",
                            "echo \"$TABLE_DONE$table \$(mysql $local -N -e ${shq(
                                "SELECT COUNT(*) FROM ${Dialect.MySql.quote(target.database)}.$quoted",
                            )})\"",
                        )
                    }
            }
        }.joinToString("\n")
    val env = if (dialect == Dialect.Postgres) listOf("PGPASSWORD", "SRC_PW") else listOf("LOCAL_PW", "SRC_PW")
    return listOf("--network=$network", "--add-host=host.docker.internal:host-gateway") +
        env.flatMap { listOf("-e", it) } +
        listOf(image, "sh", "-c", script)
}

/** Hosts that mean "this machine" are reached from a container through Docker's host gateway. */
internal fun containerHost(host: String): String =
    if (host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")) "host.docker.internal" else host

/** Client tools must be at least as new as the server they dump; Postgres also restores, so it takes the newer of both. */
internal fun clientImage(
    dialect: Dialect,
    sourceVersion: String?,
    localVersion: String?,
): String =
    when (dialect) {
        Dialect.Postgres -> {
            val major = listOfNotNull(sourceVersion, localVersion).mapNotNull { it.substringBefore('.').toIntOrNull() }.maxOrNull() ?: 16
            "postgres:$major-alpine"
        }

        Dialect.MySql -> {
            val version = sourceVersion?.split('.')?.take(2)?.joinToString(".") ?: localVersion ?: "8.4"
            "mysql:$version"
        }
    }

/**
 * Why a dump of [sourceVersion] cannot go into the local server at [localVersion], or null when it can. Compared on
 * the major version for Postgres and `major.minor` for MySQL; unparsable versions are not refused.
 */
internal fun newerSource(
    dialect: Dialect,
    sourceVersion: String?,
    localVersion: String?,
): String? {
    val parts = if (dialect == Dialect.Postgres) 1 else 2

    fun parse(version: String?) =
        version
            ?.split('.')
            ?.take(parts)
            ?.map { it.toIntOrNull() ?: return null }
            ?.takeIf { it.size == parts }
    val source = parse(sourceVersion) ?: return null
    val local = parse(localVersion) ?: return null
    val newer = source.zip(local).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
    if (!newer) return null
    val name = if (dialect == Dialect.Postgres) "Postgres" else "MySQL"
    val shown = source.joinToString(".")
    return "Source $name $shown is newer than local $name ${local.joinToString(".")}; " +
        "set the ${dialect.id} version option to $shown (or newer) and retry."
}

/**
 * The client tools read some database arguments as more than a name: a leading `-` as an option, and for Postgres
 * `key=value` text or a URI as a whole connection string that overrides `-h`. A shared config must not be able to
 * point the dump, with the saved password, at another host.
 */
internal fun checkDatabaseName(
    dialect: Dialect,
    name: String,
) {
    val conninfo = dialect == Dialect.Postgres && ('=' in name || Regex("^postgres(ql)?://", RegexOption.IGNORE_CASE).containsMatchIn(name))
    if (name.startsWith("-") || conninfo) throw GantryException("database name '$name' is not allowed (no leading '-', '=' or URI)")
}

/**
 * Arguments after `docker run --rm` that clear the local database and pipe a dump of the source into it. Every
 * value from settings is shell-quoted; passwords are only referenced as environment variables (`PGPASSWORD` or
 * `LOCAL_PW` for the local database, `SRC_PW` for the source), so they are never part of the script or argv.
 */
internal fun copyScript(
    dialect: Dialect,
    source: SourceSettings,
    target: DatabaseTarget,
    network: String,
    image: String = clientImage(dialect, null, null),
): List<String> {
    checkSourceHost(source.host)
    checkDatabaseName(dialect, source.database)
    checkDatabaseName(dialect, target.database)
    val srcHost = shq(containerHost(source.host))
    val db = shq(target.database)
    val script =
        when (dialect) {
            Dialect.Postgres -> {
                val local = "-h postgres -p 5432 -U ${shq(target.user)}"
                listOf(
                    "set -eu",
                    "set -o pipefail",
                    "umask 077",
                    // PGPASSWORD holds the local password; the source's goes through a passfile so it never shows in the script.
                    "printf '*:*:*:*:%s\\n' \"\$(printf '%s' \"\$SRC_PW\" | sed 's/[\\\\:]/\\\\&/g')\" > /tmp/source.pgpass",
                    "echo ${shq("Recreating database ${target.database}")}",
                    "dropdb $local --if-exists --force $db",
                    "createdb $local -O ${shq(target.user)} $db",
                    "echo ${shq(COPYING_MARKER)}",
                    "env -u PGPASSWORD PGPASSFILE=/tmp/source.pgpass pg_dump -h $srcHost -p ${source.port} -U ${shq(source.user)} " +
                        "-d ${shq(source.database)} -Fc --no-owner --no-privileges" +
                        " | pg_restore $local -d $db --no-owner --no-privileges --verbose",
                )
            }

            Dialect.MySql -> {
                val local = "--defaults-extra-file=/tmp/local.cnf -h mysql -P 3306 -u root"
                val user = "'" + target.user.replace("\\", "\\\\").replace("'", "''") + "'"
                val quoted = Dialect.MySql.quote(target.database)
                val recreate = "DROP DATABASE IF EXISTS $quoted; CREATE DATABASE $quoted; GRANT ALL ON $quoted.* TO $user@'%'"
                listOf(
                    "set -euo pipefail",
                    "umask 077",
                    "cnf() { printf '[client]\\npassword=\"%s\"\\n' \"\$(printf '%s' \"\$1\" | sed 's/[\\\\\"]/\\\\&/g')\"; }",
                    "cnf \"\$LOCAL_PW\" > /tmp/local.cnf",
                    "cnf \"\$SRC_PW\" > /tmp/source.cnf",
                    "echo ${shq("Recreating database ${target.database}")}",
                    "mysql $local -e ${shq(recreate)}",
                    "echo ${shq(COPYING_MARKER)}",
                    // Definers name source accounts that do not exist locally; without them, objects belong to root.
                    "mysqldump --defaults-extra-file=/tmp/source.cnf -h $srcHost -P ${source.port} -u ${shq(source.user)} " +
                        "--single-transaction --routines --triggers --set-gtid-purged=OFF --no-tablespaces --verbose ${shq(
                            source.database,
                        )}" +
                        " | sed -E 's/DEFINER=`[^`]+`@`[^`]+`//g'" +
                        " | mysql $local --default-character-set=utf8mb4 $db",
                )
            }
        }.joinToString("\n")
    val env = if (dialect == Dialect.Postgres) listOf("PGPASSWORD", "SRC_PW") else listOf("LOCAL_PW", "SRC_PW")
    return listOf("--network=$network", "--add-host=host.docker.internal:host-gateway") +
        env.flatMap { listOf("-e", it) } +
        listOf(image, "sh", "-c", script)
}

/** JDBC URL of a source, with a short connect timeout so an unreachable host fails fast. */
private fun sourceUrl(
    dialect: Dialect,
    source: SourceSettings,
): String {
    val host = if (':' in source.host && !source.host.startsWith("[")) "[${source.host}]" else source.host
    val database = URLEncoder.encode(source.database, Charsets.UTF_8).replace("+", "%20")
    return when (dialect) {
        Dialect.Postgres -> "jdbc:postgresql://$host:${source.port}/$database?connectTimeout=5&socketTimeout=30"
        Dialect.MySql ->
            "jdbc:mysql://$host:${source.port}/$database?connectTimeout=5000&socketTimeout=30000&allowPublicKeyRetrieval=true&$MYSQL_SAFE"
    }
}

internal fun classify(e: SQLException): SourceCheck? {
    if (e.sqlState == "28P01" || e.sqlState == "28000" || e.errorCode == 1045) return SourceCheck.AUTH
    val network =
        generateSequence<Throwable>(e) { it.cause }.any {
            it is SocketTimeoutException || it is UnknownHostException || it is ConnectException || it is NoRouteToHostException
        }
    return if (network || e.sqlState?.startsWith("08") == true) SourceCheck.UNREACHABLE else null
}

private fun tableCountSql(dialect: Dialect) =
    "SELECT count(*) FROM information_schema.tables WHERE table_type = 'BASE TABLE' AND " +
        if (dialect == Dialect.MySql) "table_schema = DATABASE()" else "table_schema NOT IN ('pg_catalog', 'information_schema')"

private fun describe(source: SourceSettings) = "${source.user}@${source.host}:${source.port}/${source.database}"

private fun shq(value: String) = "'" + value.replace("'", "'\\''") + "'"
