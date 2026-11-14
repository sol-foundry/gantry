package dev.gantry.gateway

import dev.gantry.engine.App
import dev.gantry.engine.Context
import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.SecretStore
import dev.gantry.engine.writeAtomically
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/** The catalog id of the gateway service. */
const val GATEWAY = "gateway"
private const val CONFIG_FILE = "gateway.yml"

/** The `gateway.yml` that was last rendered, which gantry-auth reads: both halves of the gateway switch together. */
internal const val APPLIED_CONFIG_FILE = "gateway.applied.yml"

/** The context key that carries the names of the app's set secrets (one per line) to the gateway's computed blocks. */
internal const val SET_SECRETS_CONTEXT = "gateway_secrets_set"
private const val SECRETS_FILE = "gateway-secrets.json"
internal const val LOG_DIR = "gateway-log"
private val secretsJson = Json { prettyPrint = true }

internal fun configText(dir: Path): String = dir.resolve(CONFIG_FILE).takeIf { Files.exists(it) }?.let(Files::readString) ?: ""

/** The app's `gateway.yml`, parsed; a missing file is the defaults. */
fun Engine.gatewayConfig(app: String): GatewayConfig = parseGatewayConfig(configText(appDir(app)))

/**
 * Replaces the app's `gateway.yml` with [text] and regenerates its files, so a running gateway picks the change up
 * without a restart. When [text] is invalid nothing is written and the problems are returned instead.
 */
fun Engine.saveGatewayConfig(
    app: String,
    text: String,
): List<String> =
    synchronized(this) {
        val loaded = loadApp(app)
        if (loaded.service(GATEWAY) == null) throw GantryException("'$app' has no gateway (run `gantry add $app gateway`)")
        val problems =
            gatewayConfigProblems(
                app,
                text,
                serviceOptions(loaded, GATEWAY).getValue("upstream"),
                loaded.authorizers.associate { it.name to it.port },
            )
        if (problems.isNotEmpty()) return@synchronized problems

        val file = appDir(app).resolve(CONFIG_FILE)
        val previous = configText(appDir(app))
        writeAtomically(file, text)
        try {
            refresh(app)
        } catch (e: GantryException) {
            writeAtomically(file, previous)
            refresh(app)
            return@synchronized listOf(e.message ?: "could not apply $CONFIG_FILE")
        }
        emptyList()
    }

/**
 * Every problem with [text] as the `gateway.yml` of [app], including those only rendering finds (an upstream with a
 * path, `{{` where it is not allowed); empty when it can be saved. [upstreamOption] is the service's `upstream`
 * option and [authorizerPorts] the pushed authorizers' ports.
 */
fun gatewayConfigProblems(
    app: String,
    text: String,
    upstreamOption: String,
    authorizerPorts: Map<String, Int>,
): List<String> =
    try {
        val config = parseGatewayConfig(text)
        config.validate().ifEmpty {
            apisixRoutes(config, app, upstreamOption, authorizerPorts)
            emptyList()
        }
    } catch (e: GantryException) {
        listOf(e.message ?: "invalid $CONFIG_FILE")
    }

/** APISIX's routes for [config]; the config's own upstream overrides the service option [upstreamOption]. */
private fun apisixRoutes(
    config: GatewayConfig,
    app: String,
    upstreamOption: String,
    authorizerPorts: Map<String, Int>,
    unsetSecrets: Set<String> = emptySet(),
    pendingContainers: Set<String> = emptySet(),
): String {
    val context =
        RenderContext(
            app = app,
            defaultUpstream = config.upstream ?: upstreamOption,
            authorizerPorts = authorizerPorts,
            unsetSecrets = unsetSecrets,
            pendingContainers = pendingContainers,
        )
    val text = renderApisixRoutes(config, context)
    // Generated files go through the template engine, which would treat a leftover {{...}} as one of its own placeholders.
    if ("{{" in text) throw GantryException("$CONFIG_FILE: '{{' is only allowed in header values and step settings")
    return text
}

/** Computed block `{{@apisix_config}}`. */
internal fun apisixConfigBlock(
    @Suppress("UNUSED_PARAMETER") context: Context,
): List<String> = renderApisixConfig().removeSuffix("\n").lines()

/**
 * Computed block `{{@apisix_routes}}`: renders the `gateway.yml` in the app directory. A file edited by hand into an
 * invalid state fails the render with a message that says how to check a fixed one.
 */
internal fun apisixRoutesBlock(context: Context): List<String> {
    val dir = Path.of(context.getValue("dir"))
    val app = context.getValue("app")
    try {
        val config = parseGatewayConfig(configText(dir))
        val problems = config.validate()
        if (problems.isNotEmpty()) throw GantryException(problems.joinToString("; "))
        val authorizers = decodeAuthorizers(context.getValue(AUTHORIZERS_CONTEXT))
        val set = context[SET_SECRETS_CONTEXT].orEmpty().lines().toSet()
        return apisixRoutes(
            config,
            app,
            context.getValue("opt.upstream"),
            authorizers.associate { it.name to it.port },
            config.secretNames() - set,
            context[PENDING_AUTHORIZERS_CONTEXT]
                .orEmpty()
                .lines()
                .filter { it.isNotEmpty() }
                .toSet(),
        ).removeSuffix("\n")
            .lines()
    } catch (e: GantryException) {
        val problems = e.message.orEmpty().removePrefix("$CONFIG_FILE: ")
        throw GantryException(
            "$CONFIG_FILE is invalid: $problems. Fix it, or check it with `gantry gateway config $app --file ${dir.resolve(CONFIG_FILE)}`",
        )
    }
}

/**
 * Whether the call log directory is made owner-only. Only on macOS: Docker Desktop's file sharing ignores the host
 * owner, while on Linux the containers' users (APISIX's uid 636, gantry-auth's root) are not the host owner and need
 * to write there.
 */
internal fun ownerOnlyCallLogs(osName: String = System.getProperty("os.name")): Boolean = osName.startsWith("Mac")

/** rwx-wx-wx plus the sticky bit: others may create and write files but not list, rename or delete them. */
private const val SHARED_LOG_DIR_MODE = 0b1_111_011_011
private val LOG_FILES = listOf("calls.log", "auth.log")

/**
 * Before rendering: an app with a gateway gets the default `gateway.yml` when it has none (an existing file is never
 * touched), and its call log directory gets the permissions its containers need. On macOS it is owner-only, as logged
 * calls carry request and response bodies. Elsewhere it is 01733, and `calls.log` and `auth.log` are created here, owned
 * by the host user and writable by all (APISIX opens with append): the sticky bit stops anyone else replacing them,
 * and gantry can trim them. Other local users can read them there.
 */
internal fun prepareGatewayFiles(
    app: App,
    dir: Path,
    ownerOnlyLogs: Boolean = ownerOnlyCallLogs(),
) {
    if (app.service(GATEWAY) == null) return
    val file = dir.resolve(CONFIG_FILE)
    if (!Files.exists(file)) writeAtomically(file, DEFAULT_GATEWAY_YML)
    val logs = Files.createDirectories(dir.resolve(LOG_DIR))
    try {
        if (ownerOnlyLogs) {
            Files.setPosixFilePermissions(logs, PosixFilePermissions.fromString("rwx------"))
        } else {
            Files.setAttribute(logs, "unix:mode", SHARED_LOG_DIR_MODE)
            LOG_FILES.forEach { precreateLog(logs.resolve(it)) }
        }
    } catch (_: UnsupportedOperationException) {
        // Not a POSIX file system: nothing to set.
    }
}

/** Makes [file] a regular file, mode 0666; an existing one keeps its content, anything else in its place is replaced. */
private fun precreateLog(file: Path) {
    if (Files.isSymbolicLink(file)) Files.delete(file)
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        try {
            Files.createFile(file)
        } catch (_: FileAlreadyExistsException) {
            // A container created it in the meantime.
        }
    }
    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
        Files.getOwner(file, LinkOption.NOFOLLOW_LINKS) == Files.getOwner(file.parent)
    ) {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"))
    }
}

/**
 * After rendering: `gateway-secrets.json` (owner-only) holds the values of the secrets `gateway.yml` refers to, for
 * gantry-auth. Secrets that are not set are left out; gantry-auth reports them when a request needs one. Without a
 * gateway the file is removed.
 */
internal fun writeGatewaySecrets(
    app: App,
    dir: Path,
    secrets: SecretStore,
) {
    val file = dir.resolve(SECRETS_FILE)
    if (app.service(GATEWAY) == null) {
        Files.deleteIfExists(file)
        return
    }
    val values =
        parseGatewayConfig(configText(dir))
            .secretNames()
            .sorted()
            .mapNotNull { name -> secrets.secret(app.name, name)?.let { name to it } }
            .toMap()
    writeOwnerOnly(file, secretsJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), values) + "\n")
}

/**
 * After rendering: `gateway.applied.yml` is the `gateway.yml` the routes were just rendered from, for gantry-auth. A
 * hand edit of `gateway.yml` so reaches APISIX and gantry-auth together, on the next render (`gantry gateway reload`).
 * Without a gateway the file is removed.
 */
internal fun writeAppliedGatewayConfig(
    app: App,
    dir: Path,
) {
    val file = dir.resolve(APPLIED_CONFIG_FILE)
    if (app.service(GATEWAY) == null) {
        Files.deleteIfExists(file)
        return
    }
    val text = configText(dir)
    // Rewritten only on a change, so gantry-auth does not reload (and drop its step cache) on every render.
    if (!Files.exists(file) || Files.readString(file) != text) writeAtomically(file, text)
}

/** Replaces [file] with [text], readable and writable by the owner only from the moment it exists. */
internal fun writeOwnerOnly(
    file: Path,
    text: String,
) {
    val temp =
        Files.createTempFile(
            file.parent,
            ".${file.fileName}",
            ".tmp",
            PosixFilePermissions.asFileAttribute(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)),
        )
    try {
        Files.writeString(temp, text)
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp)
    }
}
