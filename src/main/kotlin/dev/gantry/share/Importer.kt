package dev.gantry.share

import dev.gantry.engine.App
import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.ServiceDef
import dev.gantry.engine.ServiceState
import dev.gantry.engine.SourceSettings
import dev.gantry.engine.appNameRule
import dev.gantry.engine.generateSecret
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

private const val MIGRATIONS = "migrations"
private val sqlServices = setOf("postgres", "mysql")
private val checkoutParents = listOf("code", "src", "projects")

/** What importing a file would do, as an editable [request] plus what the importer should know before confirming. */
@Serializable
data class ImportPreview(
    val request: ImportRequest,
    val exists: Boolean,
    val keptSecrets: List<String>,
    val warnings: List<String>,
    val checkoutGuesses: Map<String, String>,
)

@Serializable
data class ImportRequest(
    val app: String,
    val services: Map<String, ImportedService>,
    val envNames: Map<String, String>,
)

/** [options] is complete: every catalog option, with secrets filled and `migrations` absolute or "" (checkout unknown). */
@Serializable
data class ImportedService(
    val options: Map<String, String>,
    val source: SourceSettings? = null,
)

/** Turns an [ExportFile] into an editable [ImportRequest], and applies a confirmed one as a local app. */
class Importer(
    private val engine: Engine,
) {
    fun preview(
        file: ExportFile,
        asName: String? = null,
        home: Path = Path.of(System.getProperty("user.home")),
        cwd: Path = Path.of("").toAbsolutePath(),
    ): ImportPreview {
        val name = asName ?: file.app
        val existing = existingApp(name)
        val keptSecrets = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val guesses = mutableMapOf<String, String>()
        val services =
            file.services.mapValues { (service, exported) ->
                // An unknown service is passed through as in the file, for validate() to report.
                val def =
                    runCatching { engine.catalog.resolve(service) }.getOrNull()
                        ?: return@mapValues ImportedService(exported.options, exported.source)
                val kept = existing?.service(def.id)?.let { engine.serviceOptions(existing, def.id) }
                val options = defaults(name, def) + exported.options
                val secrets =
                    def.optionMeta.filterValues { it.secret }.keys.filter { it in def.options }.associateWith { key ->
                        if (kept != null) {
                            keptSecrets += "$service.$key"
                            kept.getValue(key)
                        } else {
                            exported.options[key] ?: generateSecret()
                        }
                    }
                val migrations =
                    if (MIGRATIONS !in def.options || exported.migrations == null) {
                        emptyMap()
                    } else {
                        mapOf(MIGRATIONS to migrationsDir(service, exported.migrations, home, cwd, guesses, warnings))
                    }
                if (kept == null && def.id in sqlServices) {
                    val volume = "gantry-${name}_${def.id}-data"
                    if (engine.docker.volumeExists(volume)) {
                        warnings += "Docker volume $volume is left from an earlier '${def.id}' of '$name'; it keeps its old " +
                            "credentials, which will not match the new ones (destroy the app, or set the password option to the volume's " +
                            "old one, to fix it)"
                    }
                }
                ImportedService(options + secrets + migrations, exported.source)
            }
        return ImportPreview(
            request = ImportRequest(name, services, file.envNames),
            exists = existing != null,
            keptSecrets = keptSecrets,
            warnings = warnings,
            checkoutGuesses = guesses,
        )
    }

    /** Every problem with [request], as messages; empty when it can be applied. */
    fun validate(request: ImportRequest): List<String> {
        val messages = mutableListOf<String>()
        if (!appNameRule.matches(request.app)) messages += "invalid app name '${request.app}' (must match [a-z][a-z0-9-]*)"
        val services =
            request.services.mapNotNull { (service, imported) ->
                val def =
                    runCatching { engine.catalog.resolve(service) }.getOrElse {
                        messages += it.message ?: "unknown service '$service'"
                        return@mapNotNull null
                    }
                imported.options.forEach { option ->
                    runCatching { engine.normalizeOptions(def, mapOf(option.toPair())) }.onFailure { messages += it.message!! }
                }
                ServiceState(def.id)
            }
        if (services.size == request.services.size) {
            val ids = services.map { it.id }
            ids
                .groupBy { it }
                .filterValues { it.size > 1 }
                .keys
                .forEach { messages += "'$it' is listed more than once" }
            services.forEach { state ->
                engine.catalog
                    .resolve(state.id)
                    .requires
                    .filter { it !in ids }
                    .forEach { messages += "'${state.id}' requires '$it'" }
            }
            runCatching { engine.withEnvNames(App(request.app, services), request.envNames) }.onFailure { messages += it.message!! }
        }
        return messages
    }

    /** Creates or replaces the app. Secrets of services it already had are whatever the request carries (the preview kept them). */
    fun apply(request: ImportRequest): App {
        validate(request).takeIf { it.isNotEmpty() }?.let { throw GantryException(it.joinToString("; ")) }
        val existing = existingApp(request.app)
        val forget = mutableListOf<String>()
        val services =
            request.services.map { (service, imported) ->
                val def = engine.catalog.resolve(service)
                // An unknown checkout is applied as the default folder rather than resolved against the working directory.
                val given = imported.options.filter { (key, value) -> !(def.optionMeta[key]?.type == "path" && value.isEmpty()) }
                val options = engine.normalizeOptions(def, given)
                val defaults = engine.normalizeOptions(def, defaults(request.app, def))
                val stored = options.filter { (key, value) -> def.optionMeta[key]?.secret == true || value != defaults[key] }
                val previous = existing?.service(def.id)?.source
                val source =
                    if (previous != null && imported.source != null && previous.sameDatabase(imported.source)) {
                        imported.source.copy(user = imported.source.user.ifEmpty { previous.user })
                    } else {
                        forget += def.id
                        imported.source
                    }
                ServiceState(def.id, stored, source = source)
            }
        val app = engine.replaceApp(App(request.app, services, request.envNames))
        val removed = existing?.services?.map { it.id }.orEmpty() - services.map { it.id }.toSet()
        // A saved password belongs to the source it was typed for; once that source changes or goes, it is stale.
        (forget + removed).forEach { engine.secrets.forget(request.app, it) }
        return app
    }

    private fun existingApp(name: String): App? =
        if (appNameRule.matches(name) && Files.exists(engine.appDir(name).resolve("app.json"))) engine.loadApp(name) else null

    /** Catalog defaults as they render for an app called [app]. */
    private fun defaults(
        app: String,
        def: ServiceDef,
    ): Map<String, String> = engine.serviceOptions(App(app, listOf(ServiceState(def.id))), def.id)

    private fun migrationsDir(
        service: String,
        exported: ExportedPath,
        home: Path,
        cwd: Path,
        guesses: MutableMap<String, String>,
        warnings: MutableList<String>,
    ): String {
        val repo = exported.repo ?: return exported.path
        val checkouts =
            listOfNotNull(cwd.takeIf { it.fileName?.toString() == repo }, cwd.resolve(repo), home.resolve(repo)) +
                checkoutParents.map { home.resolve(it).resolve(repo) }
        val checkout = checkouts.firstOrNull { Files.isDirectory(it.resolve(exported.path)) }
        if (checkout == null) {
            warnings += "'$service' migrations are in '${exported.path}' of the $repo repository, but no checkout of $repo " +
                "was found; choose its folder"
            return ""
        }
        guesses[service] = checkout.toString()
        return checkout.resolve(exported.path).normalize().toString()
    }
}
