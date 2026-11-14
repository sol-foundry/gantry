package dev.gantry.gateway

import dev.gantry.engine.App
import dev.gantry.engine.Context
import dev.gantry.engine.Docker
import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.Resources
import dev.gantry.engine.SecretStore
import dev.gantry.engine.quoteEnvValue
import dev.gantry.share.repoPath
import dev.gantry.share.storedDir
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** The container port a pushed authorizer listens on unless its record says otherwise. */
const val DEFAULT_AUTHORIZER_PORT = 8080

/** The context key that carries an app's pushed authorizers (as JSON) to the gateway's computed blocks. */
internal const val AUTHORIZERS_CONTEXT = "authorizers"

private const val TEMPLATES = "authorizer-templates"
private const val NAME_TOKEN = "__NAME__"
private val authorizerNameRule = Regex("^[a-z][a-z0-9-]{0,29}$")
private val envNameRule = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
private val envFileRule = Regex("^auth-[a-z][a-z0-9-]*\\.env$")
private val authorizersSerializer = ListSerializer(PushedAuthorizer.serializer())
private val pendingSerializer = MapSerializer(String.serializer(), String.serializer())

/** Pushed authorizers whose container still runs with an older env file; see [updatePendingAuthorizers]. */
private const val PENDING_FILE = "authorizers-pending.json"

/** The context key that carries the pending authorizers' names (one per line) to the gateway's computed blocks. */
internal const val PENDING_AUTHORIZERS_CONTEXT = "authorizers_pending"

/**
 * A custom authorizer built from the developer's own code and run as the compose service `auth-<name>`. [dir] is
 * where it was pushed from: `<repo>/<path>` inside a git repository, else absolute.
 */
@Serializable
data class PushedAuthorizer(
    val name: String,
    val dir: String,
    val port: Int = DEFAULT_AUTHORIZER_PORT,
    val image: String,
)

/** The Docker side of pushing an authorizer, behind an interface so tests never reach the daemon. */
interface AuthorizerContainers {
    /** Builds [dir] as [image]; returns the exit code. */
    fun build(
        image: String,
        dir: Path,
        onLine: (String) -> Unit,
    ): Int

    /** Whether any of the app's containers is running. */
    fun appRunning(app: String): Boolean

    /** The compose services of the app that are running. */
    fun runningServices(app: String): Set<String>

    /**
     * Compose service to container state (`running`, `exited`, ...) of every container of the app, stopped ones
     * included; null when Docker could not be asked, which is never the same as "no containers".
     */
    fun serviceStates(app: String): Map<String, String>?

    /** Starts [service] alone, replacing its container so a rebuilt image is used; returns the exit code. */
    fun recreate(
        app: String,
        service: String,
        onLine: (String) -> Unit,
    ): Int

    /** Removes a container by name if it exists; never fails. */
    fun remove(container: String)
}

private class DockerAuthorizerContainers(
    private val engine: Engine,
    private val docker: Docker,
) : AuthorizerContainers {
    override fun build(
        image: String,
        dir: Path,
        onLine: (String) -> Unit,
    ) = docker.buildImage(image, dir, onLine)

    override fun appRunning(app: String) = runningServices(app).isNotEmpty()

    override fun runningServices(app: String) = docker.runningServices()[app].orEmpty()

    override fun serviceStates(app: String) = docker.liveStates(engine.loadApp(app))?.mapValues { it.value.first }

    override fun recreate(
        app: String,
        service: String,
        onLine: (String) -> Unit,
    ) = docker.composeLines(app, listOf("up", "-d", "--force-recreate", "--no-deps", service), onLine)

    override fun remove(container: String) = docker.removeContainer(container)
}

/**
 * Custom authorizers: small HTTP servers, in any language, that APISIX asks through forward-auth whether a request
 * may pass. [init] scaffolds one from a template; [push] builds it and runs it next to the app's gateway.
 */
class Authorizers(
    private val engine: Engine,
    private val containers: AuthorizerContainers = DockerAuthorizerContainers(engine, engine.docker),
) {
    /** The template languages, sorted. */
    fun languages(): List<String> =
        Files.list(Resources.directory(TEMPLATES)).use { dirs ->
            dirs
                .filter { Files.isDirectory(it) }
                .map { it.fileName.toString().removeSuffix("/") }
                .sorted()
                .toList()
        }

    /** Copies the [lang] template to [target], which must not exist or be an empty directory. */
    fun init(
        name: String,
        lang: String,
        target: Path,
    ) {
        checkName(name)
        val supported = languages()
        if (lang !in supported) throw GantryException("unknown language '$lang' (supported: ${supported.joinToString(", ")})")
        if (Files.exists(target)) {
            if (!Files.isDirectory(target)) throw GantryException("$target exists and is not a directory")
            if (Files.list(target).use { it.findAny().isPresent }) throw GantryException("$target is not empty")
        }
        Resources.copyTree(Resources.directory(TEMPLATES).resolve(lang), target)
        val readme = target.resolve("README.md")
        if (Files.exists(readme)) Files.writeString(readme, Files.readString(readme).replace(NAME_TOKEN, name))
    }

    /**
     * Builds [dir] as `gantry-<app>-auth-<name>:latest`, records it in the app and regenerates the app's files. When
     * the app is running, only `auth-<name>` is (re)started, with a fresh container so the new image is used.
     */
    fun push(
        app: String,
        name: String,
        dir: Path,
        onLine: (String) -> Unit,
    ) {
        checkName(name)
        if (engine.loadApp(app).service(GATEWAY) == null) throw GantryException("'$app' has no gateway (run `gantry add $app gateway`)")
        // auth-<name>.env would be the app's own env file.
        if (app == "auth-$name") throw GantryException("an authorizer of '$app' cannot be called '$name'")
        val source = dir.toAbsolutePath().normalize()
        if (!Files.isRegularFile(source.resolve("Dockerfile"))) throw GantryException("$source has no Dockerfile")

        val image = authorizerImage(app, name)
        val exit = containers.build(image, source, onLine)
        if (exit != 0) throw GantryException("docker build of authorizer '$name' failed (exit status $exit)")
        synchronized(engine) {
            val current = engine.loadApp(app).authorizers
            val previous = current.firstOrNull { it.name == name }
            val record = PushedAuthorizer(name, storedDir(repoPath(source)), previous?.port ?: DEFAULT_AUTHORIZER_PORT, image)
            val updated = if (previous == null) current + record else current.map { if (it.name == name) record else it }
            engine.setAuthorizers(app, updated)
        }
        if (containers.appRunning(app)) {
            val started = containers.recreate(app, "auth-$name", onLine)
            if (started != 0) throw GantryException("could not start auth-$name (docker compose exit status $started)")
        }
    }

    fun list(app: String): List<PushedAuthorizer> = engine.loadApp(app).authorizers

    /** Forgets the authorizer, regenerates the app's files and removes its container. The image is kept. */
    fun remove(
        app: String,
        name: String,
    ) {
        synchronized(engine) {
            val current = engine.loadApp(app).authorizers
            if (current.none { it.name == name }) throw GantryException("'$app' has no authorizer '$name'")
            engine.setAuthorizers(app, current.filter { it.name != name })
        }
        containers.remove("$app-auth-$name")
    }

    /**
     * Brings the containers of the pending authorizers (their env file changed since their container started, see
     * [updatePendingAuthorizers]) up to date: a running one is recreated with the current values, a stopped one is
     * removed (a later `docker start` would run the old env; the next `gantry start` creates it from the new file), and
     * one without any container needs nothing. Only then is the app re-rendered, which points their routes back at
     * them. An authorizer whose container could not be checked, recreated or removed stays pending, so its routes stay
     * on gantry-auth (a 503) instead of reaching a container with a stale env. Returns the names recreated.
     */
    fun recreatePending(
        app: String,
        onLine: (String) -> Unit,
    ): List<String> {
        val pending = synchronized(engine) { pendingAuthorizers(engine.appDir(app)).keys }
        if (pending.isEmpty()) return emptyList()
        val recreated = mutableListOf<String>()
        var started = false
        var failure: Throwable? = null
        try {
            val states = serviceStates(app)
            val stopped = pending.filter { states["auth-$it"].let { state -> state != null && state in STOPPED_STATES } }
            stopped.forEach { containers.remove("$app-auth-$it") }
            val after = if (stopped.isEmpty()) states else serviceStates(app)
            pending.forEach { name ->
                // What compose hands a new container: the env file as it is now.
                val env = engine.authorizerEnvFiles(app)[name]
                val service = "auth-$name"
                when (after[service]) {
                    null -> Unit
                    in STOPPED_STATES -> throw GantryException("could not remove the stopped container of $service")
                    else -> {
                        val exit = containers.recreate(app, service, onLine)
                        if (exit != 0) throw GantryException("could not restart $service (docker compose exit status $exit)")
                        recreated += name
                    }
                }
                started = engine.authorizersStartedWith(app, mapOf(name to env)) || started
            }
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            if (started) {
                try {
                    engine.refresh(app)
                } catch (e: GantryException) {
                    // The restart's failure is the one to report; the re-render's goes with it.
                    failure?.addSuppressed(e) ?: throw e
                }
            }
        }
        return recreated
    }

    private fun serviceStates(app: String): Map<String, String> =
        containers.serviceStates(app)
            ?: throw GantryException(
                "could not ask Docker which authorizer containers exist; their routes stay on gantry-auth until " +
                    "`gantry gateway reload $app` succeeds",
            )
}

/** Container states of a container that exists but does not run; any other state is treated as running. */
private val STOPPED_STATES = setOf("created", "exited", "dead")

/** Each pushed authorizer's generated env file as it is now, by name; null when it is not written yet. */
fun Engine.authorizerEnvFiles(app: String): Map<String, String?> {
    val dir = appDir(app)
    return loadApp(app).authorizers.associate { a ->
        a.name to dir.resolve(envFile(a.name)).takeIf { Files.exists(it) }?.let(Files::readString)
    }
}

/**
 * Records that the `auth-<name>` containers in [envs] were (re)started with those env file texts (as taken with
 * [authorizerEnvFiles] just before starting them). One whose env file still says the same is no longer pending; the
 * caller re-renders to point its routes back at it. Returns whether any pending one changed.
 */
fun Engine.authorizersStartedWith(
    app: String,
    envs: Map<String, String?>,
): Boolean =
    synchronized(this) {
        val dir = appDir(app)
        val pending = pendingAuthorizers(dir)
        if (pending.isEmpty()) return@synchronized false
        val current = authorizerEnvFiles(app)
        val updated = pending.toMutableMap()
        envs.forEach { (name, text) ->
            if (name !in pending || text == null) return@forEach
            if (current[name] == text) updated.remove(name) else updated[name] = sha256(text)
        }
        if (updated == pending) return@synchronized false
        writePendingAuthorizers(dir, updated)
        true
    }

/** The image an authorizer of [app] is built as, on this machine. */
fun authorizerImage(
    app: String,
    name: String,
) = "gantry-$app-auth-$name:latest"

/** Whether [name] can name a pushed authorizer. */
internal fun validAuthorizerName(name: String): Boolean = authorizerNameRule.matches(name)

/** The names of the `gateway.yml` custom authorizers that run the pushed authorizer [container]. */
fun GatewayConfig.authorizersUsing(container: String): List<String> =
    authorizers.filterValues { it.type == "custom" && it.container == container }.keys.toList()

private fun checkName(name: String) {
    if (!authorizerNameRule.matches(name)) {
        throw GantryException(
            "invalid authorizer name '$name': use lowercase letters, digits and '-', starting with a letter, at most 30 characters",
        )
    }
}

/** Problems with a `gateway.yml` custom authorizer's container name and env names; empty when fine. */
internal fun customAuthorizerProblems(
    label: String,
    authorizer: Authorizer,
): List<String> =
    buildList {
        authorizer.container?.let {
            if (!authorizerNameRule.matches(it)) add("$label: container '$it' is not a valid authorizer name")
        }
        authorizer.env.keys
            .filter { !envNameRule.matches(it) }
            .forEach { add("$label: env name '$it' is not a valid variable name") }
    }

internal fun encodeAuthorizers(authorizers: List<PushedAuthorizer>): String = Json.encodeToString(authorizersSerializer, authorizers)

internal fun decodeAuthorizers(text: String): List<PushedAuthorizer> = Json.decodeFromString(authorizersSerializer, text)

/**
 * Computed block `{{@authorizer_services}}`: one compose service per pushed authorizer. Every value is literal (the
 * block's output is rendered again as a template), and env values live in the owner-only `auth-<name>.env`.
 */
internal fun authorizerServicesBlock(context: Context): List<String> {
    val app = context.getValue("app")
    val dir = Path.of(context.getValue("dir"))
    return decodeAuthorizers(context[AUTHORIZERS_CONTEXT] ?: "[]").flatMap { a ->
        checkName(a.name)
        listOf(
            "",
            "  auth-${a.name}:",
            "    image: ${literal(a.image)}",
            "    container_name: ${literal("$app-auth-${a.name}")}",
            // Built locally: a missing image is reported as such rather than looked for in a registry.
            "    pull_policy: never",
            "    expose:",
            "      - \"${a.port}\"",
            "    env_file:",
            "      - ${literal(dir.resolve(envFile(a.name)).toString())}",
            "    extra_hosts:",
            "      - \"host.docker.internal:host-gateway\"",
            "    restart: unless-stopped",
        )
    }
}

/** A double-quoted YAML scalar that the template engine leaves alone. */
private fun literal(value: String): String {
    if ("{{" in value) throw GantryException("'$value' cannot contain '{{'")
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private fun envFile(name: String) = "auth-$name.env"

/**
 * After rendering: each pushed authorizer gets an owner-only `auth-<name>.env` with `PORT` plus the `env` of every
 * `gateway.yml` custom authorizer naming it as its container. `{{secret:NAME}}` references are filled from the secret
 * store; a variable whose secret is not set is left out, for the authorizer to handle. Files of authorizers no longer
 * pushed (or of an app without a gateway) are removed.
 */
internal fun writeAuthorizerEnvFiles(
    app: App,
    dir: Path,
    secrets: SecretStore,
) {
    val texts = authorizerEnvTexts(app, dir, secrets)
    val wanted = texts.keys.map { envFile(it) }.toSet()
    // The app's own env file is never one of them, even for an app called auth-<something>.
    val stale =
        Files.list(dir).use { files ->
            files.map { it.fileName.toString() }.filter { envFileRule.matches(it) && it !in wanted && it != "${app.name}.env" }.toList()
        }
    stale.forEach { Files.deleteIfExists(dir.resolve(it)) }
    texts.forEach { (name, text) -> writeOwnerOnly(dir.resolve(envFile(name)), text) }
}

/** The text of each pushed authorizer's `auth-<name>.env`, by name, as [writeAuthorizerEnvFiles] writes it. */
private fun authorizerEnvTexts(
    app: App,
    dir: Path,
    secrets: SecretStore,
): Map<String, String> {
    val pushed = if (app.service(GATEWAY) == null) emptyList() else app.authorizers
    if (pushed.isEmpty()) return emptyMap()
    val config = parseGatewayConfig(configText(dir))
    return pushed.associate { authorizer ->
        val values = linkedMapOf<String, String>()
        config.authorizers.values
            .filter { it.type == "custom" && it.container == authorizer.name }
            .forEach { custom ->
                custom.env.forEach { (key, value) ->
                    resolveSecrets(value) { secrets.secret(app.name, it) }?.let { values[key] = it }
                }
            }
        values.remove("PORT")
        values["PORT"] = authorizer.port.toString()
        authorizer.name to
            "# Generated by gantry for authorizer '${authorizer.name}' from gateway.yml; do not edit.\n" +
            values.entries.joinToString("") { (key, value) -> "$key=${quoteEnvValue(value)}\n" }
    }
}

/**
 * Before rendering: an authorizer whose `auth-<name>.env` is about to change becomes pending, and stays so until its
 * container is started with the new file ([authorizersStartedWith]). APISIX reloads routes at once but a container
 * only reads its env when created, so meanwhile the renderer sends its routes to gantry-auth (a 503): a route never
 * reaches a container whose env differs from the rendered config, e.g. one that lacks a just-set secret, or still
 * holds a removed one. The record keeps a hash of the env the container runs with, so a change that is undone (or a
 * failed save rolled back) is not pending. A `gateway.yml` that does not parse is left to the render to report.
 */
internal fun updatePendingAuthorizers(
    app: App,
    dir: Path,
    secrets: SecretStore,
) {
    val texts =
        try {
            authorizerEnvTexts(app, dir, secrets)
        } catch (_: GantryException) {
            return
        }
    val pending = pendingAuthorizers(dir)
    val updated =
        texts
            .mapNotNull { (name, text) ->
                // No env file yet: no container was started from one, so there is nothing stale to avoid.
                val runs =
                    pending[name] ?: dir.resolve(envFile(name)).takeIf { Files.exists(it) }?.let { sha256(Files.readString(it)) }
                runs?.takeIf { it != sha256(text) }?.let { name to it }
            }.toMap()
    if (updated != pending) writePendingAuthorizers(dir, updated)
}

/** The pending authorizers of the app in [dir]: name to the SHA-256 of the env their container runs with. */
internal fun pendingAuthorizers(dir: Path): Map<String, String> {
    val file = dir.resolve(PENDING_FILE)
    if (!Files.exists(file)) return emptyMap()
    return runCatching { Json.decodeFromString(pendingSerializer, Files.readString(file)) }.getOrElse {
        // Unreadable: every authorizer whose env file exists is treated as pending until it is started again.
        Files
            .list(dir)
            .use { files -> files.map { it.fileName.toString() }.filter { envFileRule.matches(it) }.toList() }
            .associate { it.removePrefix("auth-").removeSuffix(".env") to "" }
    }
}

private fun writePendingAuthorizers(
    dir: Path,
    pending: Map<String, String>,
) {
    val file = dir.resolve(PENDING_FILE)
    if (pending.isEmpty()) Files.deleteIfExists(file) else writeOwnerOnly(file, Json.encodeToString(pendingSerializer, pending) + "\n")
}

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** [value] with every `{{secret:NAME}}` replaced, or null when one of them is not set. */
private fun resolveSecrets(
    value: String,
    secret: (String) -> String?,
): String? {
    var missing = false
    val resolved = SECRET_REF.replace(value) { m -> secret(m.groupValues[1]) ?: "".also { missing = true } }
    return resolved.takeUnless { missing }
}
