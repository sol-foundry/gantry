package dev.gantry.share

import dev.gantry.engine.App
import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.LEGACY_SERVICE_IDS
import dev.gantry.engine.ServiceDef
import dev.gantry.engine.ServiceState
import dev.gantry.engine.SourceSettings
import dev.gantry.engine.appNameRule
import dev.gantry.engine.generateSecret
import dev.gantry.engine.upgradeEnvNames
import dev.gantry.gateway.GATEWAY
import dev.gantry.gateway.PushedAuthorizer
import dev.gantry.gateway.authorizerImage
import dev.gantry.gateway.gatewayConfig
import dev.gantry.gateway.gatewayConfigProblems
import dev.gantry.gateway.saveGatewayConfig
import dev.gantry.gateway.secretNames
import dev.gantry.gateway.validAuthorizerName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

private const val MIGRATIONS = "migrations"
private const val MAX_PORT = 65535
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
    /** The file's gateway section, applied with the app when it has the gateway service. */
    val gateway: ExportedGateway? = null,
)

/** [options] is complete: every catalog option, with secrets filled and `migrations` absolute or "" (checkout unknown). */
@Serializable
data class ImportedService(
    val options: Map<String, String>,
    val source: SourceSettings? = null,
)

/**
 * What importing a file's gateway section did: whether `gateway.yml` was written, the authorizers recorded (their
 * image is on this machine), the ones still to push (name to the directory to push from, or a placeholder naming the
 * repository when no checkout was found) and the secrets `gateway.yml` refers to that are not set.
 */
data class ImportedGateway(
    val configWritten: Boolean,
    val recorded: List<String> = emptyList(),
    val toPush: Map<String, String> = emptyMap(),
    val unsetSecrets: List<String> = emptyList(),
)

/** An applied import: the app, and what its gateway section left to do (null without one). */
data class Imported(
    val app: App,
    val gateway: ImportedGateway?,
)

/**
 * Turns an [ExportFile] into an editable [ImportRequest], and applies a confirmed one as a local app. [imageExists]
 * says whether an authorizer's image was built on this machine.
 */
class Importer(
    private val engine: Engine,
    private val imageExists: (String) -> Boolean = { engine.docker.imageExists(it) },
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
            file.services.mapKeys { (service, _) -> LEGACY_SERVICE_IDS[service] ?: service }.mapValues { (service, exported) ->
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
        val hasGateway = file.services.keys.any { runCatching { engine.catalog.resolve(it).id }.getOrNull() == GATEWAY }
        if (file.gateway != null && !hasGateway) warnings += "the file has a gateway.yml but no gateway service; the gateway.yml is ignored"
        return ImportPreview(
            request = ImportRequest(name, services, upgradeEnvNames(file.envNames), file.gateway),
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
            runCatching { engine.withEnvNames(App(request.app, services), upgradeEnvNames(request.envNames)) }.onFailure {
                messages +=
                    it.message!!
            }
        }
        val gateway = request.gateway
        val gatewayService =
            request.services.entries.firstOrNull {
                runCatching {
                    engine.catalog
                        .resolve(
                            it.key,
                        ).id
                }.getOrNull() == GATEWAY
            }
        if (gateway != null && gatewayService != null) {
            messages += validateGateway(gateway, request.app, gatewayService.value.options["upstream"] ?: defaultUpstream())
        }
        return messages
    }

    /**
     * Creates or replaces the app, then applies its gateway section (see [applyGateway]), and says what is left to do
     * by hand. Everything is validated first, so a request that fails changes nothing.
     */
    fun importApp(
        request: ImportRequest,
        home: Path = Path.of(System.getProperty("user.home")),
        cwd: Path = Path.of("").toAbsolutePath(),
    ): Imported {
        val app = apply(request)
        val gateway = request.gateway?.let { applyGateway(app.name, it, home, cwd) }
        return Imported(app, gateway)
    }

    private fun defaultUpstream(): String =
        engine.catalog
            .resolve(GATEWAY)
            .options
            .getValue("upstream")

    /** Creates or replaces the app, without its gateway section ([importApp] applies that too). */
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
        val app = engine.replaceApp(App(request.app, services, upgradeEnvNames(request.envNames)))
        val removed = existing?.services?.map { it.id }.orEmpty() - services.map { it.id }.toSet()
        // A saved password belongs to the source it was typed for; once that source changes or goes, it is stale.
        (forget + removed).forEach { engine.secrets.forget(request.app, it) }
        return app
    }

    /** Every problem with a file's gateway section, as messages; empty when it can be applied. */
    fun validateGateway(
        gateway: ExportedGateway,
        app: String = "app",
        upstream: String = defaultUpstream(),
    ): List<String> {
        val messages = mutableListOf<String>()
        // Rendered as saving would, so problems only rendering finds fail here, before anything is created.
        val ports = gateway.authorizers.associate { it.name to it.port }
        messages +=
            gatewayConfigProblems(app, gateway.config, upstream, ports).map { if (it.startsWith("gateway.yml")) it else "gateway.yml: $it" }
        gateway.authorizers.forEach { a ->
            if (!validAuthorizerName(a.name)) messages += "invalid authorizer name '${a.name}'"
            if (a.port !in 1..MAX_PORT) messages += "authorizer '${a.name}': port ${a.port} is out of range"
        }
        gateway.authorizers
            .groupBy { it.name }
            .filterValues { it.size > 1 }
            .keys
            .forEach { messages += "authorizer '$it' is listed more than once" }
        return messages
    }

    /**
     * Applies a file's gateway section to the imported [app], when it has the gateway service: authorizers whose image
     * exists here are recorded (a record without an image would stop the app from starting), the others are left to
     * push, and `gateway.yml` is replaced. Secret values are never in the file; the ones still to set are returned.
     */
    fun applyGateway(
        app: String,
        gateway: ExportedGateway,
        home: Path = Path.of(System.getProperty("user.home")),
        cwd: Path = Path.of("").toAbsolutePath(),
    ): ImportedGateway {
        val loaded = engine.loadApp(app)
        if (loaded.service(GATEWAY) == null) return ImportedGateway(configWritten = false)
        validateGateway(gateway, app, engine.serviceOptions(loaded, GATEWAY).getValue("upstream"))
            .takeIf {
                it.isNotEmpty()
            }?.let { throw GantryException(it.joinToString("; ")) }
        val recorded = mutableListOf<String>()
        val toPush = linkedMapOf<String, String>()
        synchronized(engine) {
            val current = engine.loadApp(app).authorizers
            val added =
                gateway.authorizers.filter { a -> current.none { it.name == a.name } }.mapNotNull { a ->
                    val image = authorizerImage(app, a.name)
                    if (imageExists(image)) {
                        recorded += a.name
                        PushedAuthorizer(a.name, storedDir(a.dir), a.port, image)
                    } else {
                        toPush[a.name] = pushDir(a.dir, home, cwd)
                        null
                    }
                }
            if (added.isNotEmpty()) engine.setAuthorizers(app, current + added)
        }
        engine.saveGatewayConfig(app, gateway.config).takeIf { it.isNotEmpty() }?.let { throw GantryException(it.joinToString("; ")) }
        val unset =
            engine
                .gatewayConfig(app)
                .secretNames()
                .filter { engine.secrets.secret(app, it) == null }
                .sorted()
        return ImportedGateway(configWritten = true, recorded = recorded, toPush = toPush, unsetSecrets = unset)
    }

    /** Where to push an authorizer from: its checkout here when one is found, else a placeholder naming the repository. */
    private fun pushDir(
        dir: ExportedPath,
        home: Path,
        cwd: Path,
    ): String {
        val repo = dir.repo ?: return dir.path
        checkout(repo, dir.path, home, cwd)?.let { return it.resolve(dir.path).normalize().toString() }
        return listOf("<$repo checkout>", dir.path).filter { it.isNotEmpty() }.joinToString("/")
    }

    /** The checkout of [repo] holding [path], looked for in the usual places. */
    private fun checkout(
        repo: String,
        path: String,
        home: Path,
        cwd: Path,
    ): Path? {
        val checkouts =
            listOfNotNull(cwd.takeIf { it.fileName?.toString() == repo }, cwd.resolve(repo), home.resolve(repo)) +
                checkoutParents.map { home.resolve(it).resolve(repo) }
        return checkouts.firstOrNull { Files.isDirectory(it.resolve(path)) }
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
        val checkout = checkout(repo, exported.path, home, cwd)
        if (checkout == null) {
            warnings += "'$service' migrations are in '${exported.path}' of the $repo repository, but no checkout of $repo " +
                "was found; choose its folder"
            return ""
        }
        guesses[service] = checkout.toString()
        return checkout.resolve(exported.path).normalize().toString()
    }
}
