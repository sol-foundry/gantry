package dev.gantry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.varargValues
import com.github.ajalt.clikt.parameters.types.int
import dev.gantry.db.DatabaseTarget
import dev.gantry.db.INTERRUPTED_PHASE
import dev.gantry.db.Migrations
import dev.gantry.db.SourceCheck
import dev.gantry.db.Sync
import dev.gantry.db.parseTables
import dev.gantry.engine.Catalog
import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.Presets
import dev.gantry.engine.SourceSettings
import dev.gantry.server.startDashboard
import dev.gantry.share.ExportedPath
import dev.gantry.share.Exporter
import dev.gantry.share.ImportRequest
import dev.gantry.share.Importer
import dev.gantry.share.encode
import dev.gantry.share.parseExportFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val DEFAULT_DASHBOARD_PORT = 7070
private const val DEFAULT_LOG_TAIL = 200
private const val MAX_PASSWORD_TRIES = 3

fun main(args: Array<String>) {
    try {
        val engine = Engine()
        Gantry()
            .subcommands(
                Services(engine),
                ListApps(engine),
                Create(engine),
                Add(engine),
                Remove(engine),
                Env(engine),
                EnvMap(engine),
                Start(engine),
                Stop(engine),
                Destroy(engine),
                Logs(engine),
                Migrate(engine),
                Export(engine),
                Import(engine),
                SyncCommand(engine),
                Mcp(engine),
                Dashboard(engine),
            ).main(args)
    } catch (e: GantryException) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
}

private class Gantry : CliktCommand(name = "gantry") {
    override fun help(context: Context) = "Per-app local infrastructure in Docker."

    // The commands' earlier names, kept so existing scripts keep working; not shown in help.
    override fun aliases() = mapOf("up" to listOf("start"), "down" to listOf("stop"))

    override fun run() = Unit
}

private class Services(
    private val engine: Engine,
) : CliktCommand(name = "services") {
    override fun help(context: Context) = "List available services and their options."

    override fun run() {
        engine.catalog.all().forEach { def ->
            echo("${def.id}\n    ${def.description}")
            def.options.forEach { (key, default) ->
                echo("    --set $key=\"$default\"")
                def.optionMeta[key]
                    ?.help
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { echo("        $it") }
            }
        }
    }
}

private class ListApps(
    private val engine: Engine,
) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List apps."

    override fun run() {
        val apps = engine.listApps()
        if (apps.isEmpty()) echo("no apps; run `gantry create <app>`")
        apps.forEach { echo("${it.name.padEnd(20)} ${it.services.joinToString { s -> s.id }}") }
    }
}

internal class Create(
    private val engine: Engine,
) : CliktCommand(name = "create") {
    override fun help(context: Context) = "Create an app and optionally add services (database, cache, queue, ...)."

    private val app by argument()
    private val services by option(
        "--services",
        "-s",
        metavar = "SERVICE...",
        help = "Services to add, e.g. --services database cache queue (see `gantry services`).",
    ).varargValues().default(emptyList())

    // Services listed without the flag, as earlier versions took them; kept so existing scripts keep working.
    private val listed by argument("service", help = "Same as --services (older form).").multiple()
    private val preset by option("--preset", help = "Start from a preset: ${Presets.names().joinToString()}")

    override fun run() {
        val created = engine.create(app, preset, services + listed)
        echo("created '${created.name}' (${created.services.size} services); next: gantry start $app && gantry env $app")
    }
}

internal class Add(
    private val engine: Engine,
) : CliktCommand(name = "add") {
    override fun help(context: Context) = "Add a service to an app, or reconfigure it by running again with new --set values."

    private val app by argument()
    private val serviceOption by option("--service", "-s", metavar = "SERVICE", help = "Service to add, e.g. --service temporal.")

    // The service given without the flag, as earlier versions took it; kept so existing scripts keep working.
    private val listed by argument("service", help = "Same as --service (older form).").optional()
    private val sets by option("--set", metavar = "KEY=VALUE", help = "Service option (repeatable).").multiple()
    private val freshData by option(
        "--fresh-data",
        help = "Delete the data a removed copy of this database left behind, so it starts empty with its new credentials.",
    ).flag()
    private val keepData by option(
        "--keep-data",
        help = "Reuse the data a removed copy of this database left behind (--set its old user and password).",
    ).flag()

    override fun run() {
        if (serviceOption != null && listed != null && serviceOption != listed) {
            throw UsageError("name the service once: --service $serviceOption or $listed").apply { context = currentContext }
        }
        val service =
            serviceOption ?: listed
                ?: throw UsageError("name the service to add, e.g. gantry add $app --service temporal").apply { context = currentContext }
        if (freshData && keepData) throw GantryException("--fresh-data and --keep-data cannot be used together")
        val leftover = engine.leftoverVolume(app, service)
        if (leftover != null && !freshData && !keepData) {
            throw GantryException(
                "volume $leftover still holds the data and credentials of the '$service' removed earlier, so new " +
                    "credentials would not work with it. Pass --fresh-data to delete it, or --keep-data to reuse it " +
                    "(with --set user=... --set password=... matching the old ones)",
            )
        }
        engine.add(app, service, parseAssignments(sets), freshData)
        echo("ok; apply with: gantry start $app")
    }
}

private class Remove(
    private val engine: Engine,
) : CliktCommand(name = "remove") {
    override fun help(context: Context) = "Remove a service from an app."

    private val app by argument()
    private val service by argument()

    override fun run() {
        engine.remove(app, service)
    }
}

private class Env(
    private val engine: Engine,
) : CliktCommand(name = "env") {
    override fun help(context: Context) = "Print the env file content for an app."

    private val app by argument()
    private val write by option("--write", metavar = "FILE", help = "Also write the content to this file.")

    override fun run() {
        val text = engine.envText(engine.refresh(app))
        echo(text, trailingNewline = false)
        write?.let { Files.writeString(Path.of(it), text) }
    }
}

private class EnvMap(
    private val engine: Engine,
) : CliktCommand(name = "envmap") {
    override fun help(context: Context) =
        """
        Show or change the variable names the app reads (default KEY -> your NAME).

        Each service provides variables under default keys (DB_HOST, CACHE_URL, ...). Map a key to the
        name your code reads:

          gantry envmap my-app DB_HOST=POSTGRES_HOST DATABASE_URL=SPRING_DATASOURCE_URL

        Mappings are stored with the app and applied by `gantry env` and the dashboard.
        """.trimIndent()

    private val app by argument()
    private val mappings by argument(help = "KEY=NAME pairs.").multiple()
    private val omit by option("--omit", metavar = "KEY", help = "Drop this default key from the generated env (repeatable).").multiple()
    private val reset by option("--reset", metavar = "KEY", help = "Restore this default key's name (repeatable).").multiple()

    override fun run() {
        val current = engine.loadApp(app)
        var app = current
        if (mappings.isNotEmpty() || omit.isNotEmpty() || reset.isNotEmpty()) {
            val names = current.envNames.toMutableMap()
            names += parseAssignments(mappings)
            omit.forEach { names[it] = "" }
            reset.forEach { names.remove(it) }
            app = engine.setEnvNames(current.name, names)
        }
        engine.envEntries(app).forEach {
            echo("${it.service.padEnd(14)} ${it.key.padEnd(24)} -> ${if (it.omitted) "(omitted)" else it.name}")
        }
    }
}

private class Start(
    private val engine: Engine,
) : CliktCommand(name = "start") {
    override fun help(context: Context) = "Start an app's containers and wait until healthy."

    private val app by argument()

    override fun run() {
        engine.docker.up(app, stream = true)
        Migrations(engine).migrateOnUp(app).forEach { echo(it) }
        echo("'$app' is running. Env block: gantry env $app")
    }
}

private class Stop(
    private val engine: Engine,
) : CliktCommand(name = "stop") {
    override fun help(context: Context) = "Stop an app's containers, keeping data."

    private val app by argument()

    override fun run() = engine.docker.down(app, stream = true)
}

private class Destroy(
    private val engine: Engine,
) : CliktCommand(name = "destroy") {
    override fun help(context: Context) = "Remove an app's containers, data volumes and configuration."

    private val app by argument()
    private val yes by option("--yes", "-y", help = "Skip the confirmation prompt.").flag()

    override fun run() {
        if (!yes) {
            System.err.print("destroy '$app' including all data? [y/N] ")
            if (readlnOrNull()?.trim()?.lowercase()?.startsWith("y") != true) throw GantryException("aborted")
        }
        engine.docker.destroy(app, stream = true)
        echo("destroyed '$app'")
    }
}

private class Logs(
    private val engine: Engine,
) : CliktCommand(name = "logs") {
    override fun help(context: Context) = "Show container logs."

    private val app by argument()
    private val service by argument().multiple()
    private val follow by option("--follow", "-f", help = "Stream logs.").flag()
    private val tail by option("--tail", help = "Lines per container.").int().default(DEFAULT_LOG_TAIL)

    override fun run() {
        engine.docker.logs(app, service.firstOrNull(), tail, follow, stream = true)
    }
}

private class Migrate(
    private val engine: Engine,
) : CliktCommand(name = "migrate") {
    override fun help(context: Context) =
        """
        Run Flyway against an app's database (postgres or mysql).

        Migrations are read from the service's `migrations` option (default <app dir>/migrations/<service>);
        point it at your service's own folder with `gantry add <app> --service postgres --set migrations=path/to/db/migration`.
        """.trimIndent()

    private val app by argument()
    private val action by argument(help = "info (default), migrate, validate, repair, baseline, clean").default("info")
    private val service by option("--service", help = "postgres or mysql; needed only when the app has both.")
    private val yes by option("--yes", "-y", help = "Skip the confirmation for clean.").flag()

    override fun run() {
        val migrations = Migrations(engine)
        val targets = DatabaseTarget.all(engine, app)
        val target =
            service?.let { DatabaseTarget.resolve(engine, app, it) }
                ?: targets.singleOrNull()
                ?: throw GantryException(
                    if (targets.isEmpty()) "app '$app' has no postgres or mysql service" else "app has several databases; pass --service",
                )
        if (action == "clean" && !yes) {
            System.err.print("drop every object in ${target.service} of '$app'? [y/N] ")
            if (readlnOrNull()?.trim()?.lowercase()?.startsWith("y") != true) throw GantryException("aborted")
        }
        val status =
            if (action == "info") {
                migrations.status(app, target.service)
            } else {
                migrations.run(app, target.service, action).also { echo(it.message) }.status
            }
        echo("${target.service} migrations: ${status.location}${if (status.locationExists) "" else " (missing)"}")
        echo("current version: ${status.current ?: "none"}, pending: ${status.pending}")
        status.migrations.forEach {
            echo("  ${(it.version ?: "R").padEnd(8)} ${it.state.padEnd(12)} ${it.description}")
        }
    }
}

private class Export(
    private val engine: Engine,
) : CliktCommand(name = "export") {
    override fun help(context: Context) = "Export an app's configuration to a shareable file (no secrets, no ports)."

    private val app by argument()
    private val output by option("--output", "-o", metavar = "FILE", help = "Write to this file instead of stdout.")

    override fun run() {
        val text = Exporter(engine).export(app).encode()
        if (output == null) echo(text) else Files.writeString(Path.of(output!!), text + "\n")
    }
}

private class Import(
    private val engine: Engine,
) : CliktCommand(name = "import") {
    override fun help(context: Context) = "Create (or replace) an app from an exported file, after showing what will happen."

    private val file by argument()
    private val asName by option("--as", metavar = "NAME", help = "Import under this app name.")
    private val sets by option("--set", metavar = "SERVICE.OPTION=VALUE", help = "Override a service option (repeatable).").multiple()
    private val sources by option(
        "--source",
        metavar = "SERVICE.FIELD=VALUE",
        help = "Override a sync source: host, port, database, user (repeatable).",
    ).multiple()
    private val repos by option("--repo", metavar = "NAME=PATH", help = "Where a repository is checked out (repeatable).").multiple()
    private val yes by option("--yes", "-y", help = "Skip the confirmation prompt.").flag()

    override fun run() {
        val exported = parseExportFile(Files.readString(Path.of(file)))
        val importer = Importer(engine)
        val preview = importer.preview(exported, asName)
        val request =
            applyOverrides(
                engine.catalog,
                preview.request,
                sets,
                sources,
                parseAssignments(repos),
                exported.services.mapNotNull { (service, s) -> s.migrations?.let { service to it } }.toMap(),
            )
        importer.validate(request).takeIf { it.isNotEmpty() }?.let { throw GantryException(it.joinToString("; ")) }
        printPreview(request, preview.request, preview.exists, preview.keptSecrets, preview.warnings)
        if (!yes) {
            System.err.print("${if (preview.exists) "replace" else "create"} '${request.app}'? [y/N] ")
            if (readlnOrNull()?.trim()?.lowercase()?.startsWith("y") != true) throw GantryException("aborted")
        }
        val app = importer.apply(request)
        echo("imported '${app.name}' (${app.services.size} services); next: gantry start ${app.name} && gantry env ${app.name}")
        if (request.services.values.any { it.source?.complete() == true }) echo("next: gantry sync ${app.name}")
    }

    private fun printPreview(
        request: ImportRequest,
        previewed: ImportRequest,
        exists: Boolean,
        keptSecrets: List<String>,
        warnings: List<String>,
    ) {
        echo("app ${request.app} (${if (exists) "exists, will be replaced" else "new"})")
        request.services.forEach { (service, imported) ->
            echo("  $service")
            val meta = runCatching { engine.catalog.resolve(service).optionMeta }.getOrDefault(emptyMap())
            imported.options.forEach { (key, value) ->
                val shown =
                    if (meta[key]?.secret == true) {
                        val overridden = value != previewed.services[service]?.options?.get(key)
                        secretLabel(kept = "$service.$key" in keptSecrets, overridden = overridden)
                    } else {
                        value.ifEmpty { "(unset)" }
                    }
                echo("    $key = $shown")
            }
            imported.source?.let { echo("    source: ${it.user}@${it.host}:${it.port}/${it.database}") }
        }
        request.envNames.forEach { (key, name) -> echo("  env $key -> ${name.ifEmpty { "(omitted)" }}") }
        warnings.forEach { echo("warning: $it") }
    }
}

private class Dashboard(
    private val engine: Engine,
) : CliktCommand(name = "dashboard") {
    override fun help(context: Context) = "Start the web dashboard (localhost only)."

    private val port by option("--port", help = "Port to listen on.").int().default(DEFAULT_DASHBOARD_PORT)

    override fun run() = startDashboard(engine, port)
}

private fun parseAssignments(pairs: List<String>): Map<String, String> =
    pairs.associate {
        val (key, value) =
            it.split("=", limit = 2).takeIf { parts -> parts.size == 2 && parts[1].isNotEmpty() }
                ?: throw GantryException("expected KEY=VALUE, got '$it' (use --omit KEY to drop a variable)")
        key to value
    }

/** Applies `--set`, `--source` and `--repo` to an import [request]; [migrations] are the exported paths, keyed by service as in the file. */
internal fun applyOverrides(
    catalog: Catalog,
    request: ImportRequest,
    sets: List<String>,
    sources: List<String>,
    repos: Map<String, String>,
    migrations: Map<String, ExportedPath> = emptyMap(),
): ImportRequest {
    val services = request.services.toMutableMap()

    fun keyOf(service: String): String {
        val id = catalog.resolve(service).id
        return services.keys.firstOrNull { runCatching { catalog.resolve(it).id }.getOrNull() == id }
            ?: throw GantryException("the import has no '$service' service")
    }

    fun assignments(
        pairs: List<String>,
        apply: (key: String, name: String, value: String) -> Unit,
    ) = pairs.forEach {
        val (target, value) = it.split("=", limit = 2).takeIf { parts -> parts.size == 2 } ?: throw bad(it)
        val (service, name) =
            target.split(".", limit = 2).takeIf { parts -> parts.size == 2 && parts.none(String::isEmpty) }
                ?: throw bad(it)
        apply(keyOf(service), name, value)
    }
    assignments(sets) { key, name, value ->
        services[key] = services.getValue(key).let { it.copy(options = it.options + (name to value)) }
    }
    assignments(sources) { key, name, value ->
        val imported = services.getValue(key)
        services[key] = imported.copy(source = withSourceField(imported.source ?: SourceSettings(), name, value))
    }
    repos.forEach { (repo, path) ->
        val root = if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.removePrefix("~") else path
        migrations.filterValues { it.repo == repo }.forEach { (service, exported) ->
            val key = keyOf(service)
            services[key] =
                services.getValue(key).let { it.copy(options = it.options + ("migrations" to Path.of(root, exported.path).toString())) }
        }
    }
    return request.copy(services = services)
}

/**
 * How the import preview shows a secret option: a kept secret that `--set` replaces is called out, because the
 * existing database volume keeps the old value and will refuse the new one.
 */
internal fun secretLabel(
    kept: Boolean,
    overridden: Boolean,
): String =
    when {
        overridden && kept -> "set (kept value replaced — the existing database won't accept it until its volume is recreated)"
        overridden -> "set"
        kept -> "kept"
        else -> "generated"
    }

/** [source] with one field set from a `--source` value. */
internal fun withSourceField(
    source: SourceSettings,
    name: String,
    value: String,
): SourceSettings =
    when (name) {
        "host" -> source.copy(host = value)
        "port" -> source.copy(port = value.toIntOrNull() ?: throw GantryException("source port must be a number, got '$value'"))
        "database" -> source.copy(database = value)
        "user" -> source.copy(user = value)
        else -> throw GantryException("unknown source field '$name' (host, port, database, user)")
    }

/** Applies `gantry sync --source FIELD=VALUE` assignments over the saved source. */
internal fun applySourceFields(
    source: SourceSettings,
    assignments: List<String>,
): SourceSettings =
    assignments.fold(source) { current, text ->
        val (name, value) =
            text.split("=", limit = 2).takeIf { it.size == 2 && it[0].isNotEmpty() }
                ?: throw GantryException("expected <field>=<value>, got '$text' (fields: host, port, database, user)")
        withSourceField(current, name, value)
    }

private fun bad(text: String) = GantryException("expected <service>.<option>=<value>, got '$text'")

private class SyncCommand(
    private val engine: Engine,
) : CliktCommand(name = "sync") {
    override fun help(context: Context) =
        "Replace a local database, or just some of its tables (--table), with a copy from a source database (usually stage)."

    private val app by argument()
    private val service by option("--service", help = "postgres or mysql; needed only when the app has both.")
    private val sources by option(
        "--source",
        metavar = "FIELD=VALUE",
        help = "Change the saved source: host, port, database, user (repeatable). Saved once the sync succeeds.",
    ).multiple()
    private val tables by option(
        "--table",
        "-t",
        metavar = "TABLE",
        help = "Copy only this table (repeatable): table, or schema.table on Postgres. Other tables are left alone.",
    ).multiple()
    private val yes by option("--yes", "-y", help = "Skip the confirmation prompt.").flag()

    override fun run() {
        val targets = DatabaseTarget.all(engine, app)
        val target =
            service?.let { DatabaseTarget.resolve(engine, app, it) }
                ?: targets.singleOrNull()
                ?: throw GantryException(
                    if (targets.isEmpty()) "app '$app' has no postgres or mysql service" else "app has several databases; pass --service",
                )
        val id = target.service
        // Checked before any prompt, so a typo doesn't cost the password round trip.
        val scope = if (tables.isEmpty()) emptyList() else parseTables(target.dialect, tables).map { it.toString() }
        val sync = Sync(engine)
        sync
            .job(app, id)
            ?.takeIf { it.phase == INTERRUPTED_PHASE }
            ?.message
            ?.let { System.err.println("warning: $it") }
        val saved = engine.loadApp(app).service(id)?.source
        val given = applySourceFields(saved ?: SourceSettings(), sources)
        // Not saved here: the sync saves the source once it has copied from it, so a typo is never kept.
        val source =
            SourceSettings(
                host = given.host.ifEmpty { ask("source host") },
                port =
                    given.port.takeIf { it > 0 } ?: ask("source port").toIntOrNull()?.takeIf { it > 0 }
                        ?: throw GantryException("invalid port"),
                database = given.database.ifEmpty { ask("source database") },
                user = given.user.ifEmpty { ask("source user") },
            )

        val savedPassword = if (saved?.sameDatabase(source) == true) engine.secrets.password(app, id) else null
        var password = savedPassword ?: askPassword(source)
        var tries = 1
        while (true) {
            val probe = sync.check(app, id, source, password)
            when (probe.result) {
                SourceCheck.OK -> break
                SourceCheck.UNREACHABLE -> throw GantryException(probe.message)
                SourceCheck.AUTH -> {
                    if (tries >= MAX_PASSWORD_TRIES) throw GantryException("authentication failed $MAX_PASSWORD_TRIES times")
                    System.err.println("authentication failed")
                    password = askPassword(source)
                    tries++
                }
            }
        }

        if (!yes) {
            val local = target.database
            val from = "${source.user}@${source.host}:${source.port}/${source.database}"
            System.err.print(
                if (scope.isEmpty()) {
                    "Replace all data in $local of $app with a copy of $from? Local data will be deleted. [y/N] "
                } else {
                    "Replace all rows of ${scope.joinToString()} in $local of $app with the rows from $from? " +
                        "Other tables are left alone. [y/N] "
                },
            )
            if (readlnOrNull()?.trim()?.lowercase()?.startsWith("y") != true) throw GantryException("aborted")
        }
        val job = sync.runBlocking(app, id, source, password, scope) { echo(it) }
        if (job.state != "succeeded") throw GantryException(job.message ?: "sync failed")
        echo(job.message ?: "synced $id of '$app'")
    }

    private fun ask(label: String): String {
        System.err.print("$label: ")
        return readlnOrNull()?.trim().orEmpty().ifEmpty { throw GantryException("$label is required") }
    }

    private fun askPassword(source: SourceSettings): String {
        val prompt = "password for ${source.user}@${source.host}: "
        val console = System.console()
        val typed =
            if (console != null) {
                console.readPassword(prompt)?.concatToString()
            } else {
                System.err.print(prompt)
                readlnOrNull()
            }
        return typed.orEmpty().ifEmpty { throw GantryException("password is required") }
    }
}
