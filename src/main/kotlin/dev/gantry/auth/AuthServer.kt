package dev.gantry.auth

import dev.gantry.db.containerHost
import dev.gantry.gateway.GatewayConfig
import dev.gantry.gateway.HttpCall
import dev.gantry.gateway.parseGatewayConfig
import dev.gantry.gateway.validate
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import kotlin.system.exitProcess

private const val DEFAULT_PORT = 9100
private const val CHECK_INTERVAL_MS = 1000L
private const val UNAVAILABLE_BODY = """{"error":"gateway auth is unavailable"}"""

/** A scheme, then the host (a bracketed IPv6 address, or anything up to a port, path or query). */
private val URL_HOST = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)(\\[[^\\]]*]|[^/?#:\\s]+)")

/** Points a URL that names this machine at the Docker host instead; a host written as a template is left alone. */
internal fun containerUrl(url: String): String {
    val m = URL_HOST.find(url) ?: return url
    val host = m.groupValues[2]
    if ('{' in host) return url
    val mapped = containerHost(host)
    return if (mapped == host) url else m.groupValues[1] + mapped + url.substring(m.range.last + 1)
}

private fun HttpCall.forContainer() = copy(url = containerUrl(url))

/** The config as gantry-auth must use it inside a container, where `localhost` is the container itself. */
internal fun GatewayConfig.forContainer(): GatewayConfig =
    copy(
        upstream = upstream?.let(::containerUrl),
        authorizers =
            authorizers.mapValues { (_, a) ->
                a.copy(url = a.url?.let(::containerUrl), jwksUrl = a.jwksUrl?.let(::containerUrl))
            },
        steps = steps.mapValues { (_, s) -> s.copy(lookup = s.lookup.forContainer(), create = s.create?.forContainer()) },
    )

/**
 * What gantry-auth is currently running on: the config and secrets files, reloaded when their modification time
 * changes (looked at most once a second), and the engine built from them. A broken new config never replaces a
 * working one. Safe for concurrent use.
 */
@Suppress("LongParameterList")
class AuthServerState(
    private val configFile: Path,
    private val secretsFile: Path,
    private val logFile: Path?,
    private val app: String,
    private val fallbackUpstream: String?,
    private val inContainer: Boolean,
    val verifier: TokenVerifier = TokenVerifier(),
    private val http: HttpCaller = JdkHttpCaller(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logLock = Any()
    private var current: AuthEngine? = null
    private var currentUpstream: String = ""
    private var lastCheck = 0L
    private var configStamp: Stamp? = null
    private var secretsStamp: Stamp? = null
    private var secrets: Map<String, String> = emptyMap()
    private var config: GatewayConfig? = null
    private var started = false

    private data class Stamp(
        val modified: FileTime,
        val size: Long,
    )

    init {
        reload()
    }

    /** The engine to ask, or null when no valid config has ever been loaded. */
    @Synchronized
    fun engine(): AuthEngine? {
        if (clock() - lastCheck >= CHECK_INTERVAL_MS) reload()
        return current
    }

    @Synchronized
    fun secret(name: String): String? = secrets[name]

    /** The upstream the engine renders `{{upstream}}` to. */
    @Synchronized
    fun effectiveUpstream(): String {
        engine()
        return currentUpstream
    }

    fun log(line: String) {
        val file = logFile ?: return
        synchronized(logLock) {
            try {
                file.parent?.let { Files.createDirectories(it) }
                Files.writeString(
                    file,
                    line.replace("\n", " ") + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            } catch (_: IOException) {
                // Logging must never fail a request.
            }
        }
    }

    private fun note(
        event: String,
        message: String,
    ) = log(
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("event", event)
                put("message", message)
            },
        ),
    )

    private fun stamp(file: Path): Stamp? =
        try {
            Stamp(Files.getLastModifiedTime(file), Files.size(file))
        } catch (_: IOException) {
            null
        }

    private fun reload() {
        lastCheck = clock()
        val newConfig = stamp(configFile)
        val newSecrets = stamp(secretsFile)
        val first = !started
        started = true
        val secretsChanged = first || newSecrets != secretsStamp
        val configChanged = first || newConfig != configStamp
        secretsStamp = newSecrets
        configStamp = newConfig
        var rebuild = false
        if (secretsChanged) rebuild = loadSecrets(newSecrets) || rebuild
        if (configChanged) rebuild = loadConfig(newConfig) || rebuild
        if (rebuild) build()
    }

    private fun loadSecrets(stamp: Stamp?): Boolean {
        if (stamp == null) {
            secrets = emptyMap()
            return true
        }
        return try {
            val obj = Json.parseToJsonElement(Files.readString(secretsFile)).jsonObject
            secrets = obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }
            true
        } catch (e: Exception) {
            note("secrets", "cannot read ${secretsFile.fileName}, keeping the previous secrets: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun loadConfig(stamp: Stamp?): Boolean {
        if (stamp == null) {
            note("config", "${configFile.fileName} not found${if (current != null) ", keeping the previous config" else ""}")
            return false
        }
        return try {
            val parsed = parseGatewayConfig(Files.readString(configFile))
            val problems = parsed.validate()
            if (problems.isEmpty()) {
                config = parsed
                true
            } else {
                note("config", "${configFile.fileName} is invalid${keep()}: ${problems.joinToString("; ")}")
                false
            }
        } catch (e: Exception) {
            note("config", "${configFile.fileName} is invalid${keep()}: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun keep() = if (current != null) ", keeping the previous config" else ""

    private fun build() {
        val c = config ?: return
        val effective = if (inContainer) c.forContainer() else c
        val upstream = effective.upstream ?: fallbackUpstream?.let { if (inContainer) containerUrl(it) else it }.orEmpty()
        currentUpstream = upstream
        current =
            AuthEngine(
                config = effective,
                verifier = verifier,
                secrets = ::secret,
                app = app,
                upstream = upstream,
                http = http,
                log = ::log,
                clock = java.time.Instant::now,
            )
    }
}

/** `/authorize` answers APISIX's forward-auth; `/health` is for the container's own checks. */
fun Application.authModule(state: AuthServerState) {
    routing {
        get("/health") { call.respondText("ok") }
        get("/authorize") { authorize(call, state) }
        post("/authorize") { authorize(call, state) }
    }
}

private suspend fun authorize(
    call: RoutingCall,
    state: AuthServerState,
) {
    val engine = state.engine()
    if (engine == null) {
        call.respondText(UNAVAILABLE_BODY, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
        return
    }
    val headers = linkedMapOf<String, String>()
    call.request.headers.forEach { name, values -> headers[name.lowercase()] = values.joinToString(", ") }
    val uri = call.request.headers["X-Forwarded-Uri"] ?: "/"
    val request =
        AuthRequest(
            route = call.request.queryParameters["route"].orEmpty(),
            method = call.request.headers["X-Forwarded-Method"] ?: call.request.local.method.value,
            uri = uri,
            headers = headers,
            query = queryOf(uri),
        )
    val result = withContext(Dispatchers.IO) { engine.decide(request) }
    if (result.status in 200..299) {
        result.headers.forEach { (name, value) ->
            try {
                call.response.headers.append(name, value)
            } catch (_: IllegalArgumentException) {
                // A header the HTTP layer refuses to send; the rest still go.
            }
        }
    }
    val body = result.body
    if (body != null) {
        call.respondText(body, ContentType.Application.Json, HttpStatusCode.fromValue(result.status))
    } else {
        call.respondText("", status = HttpStatusCode.fromValue(result.status))
    }
}

private fun queryOf(uri: String): Map<String, String> {
    val raw = uri.substringAfter('?', "").substringBefore('#')
    if (raw.isEmpty()) return emptyMap()
    val out = linkedMapOf<String, String>()
    raw.split('&').filter { it.isNotEmpty() }.forEach {
        out.putIfAbsent(decode(it.substringBefore('=')), decode(it.substringAfter('=', "")))
    }
    return out
}

private fun decode(s: String): String =
    try {
        URLDecoder.decode(s, StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        s
    }

private class Options(
    val config: Path,
    val secrets: Path,
    val log: Path?,
    val port: Int,
    val app: String,
    val upstream: String?,
    val inContainer: Boolean,
)

private fun parseOptions(args: Array<String>): Options {
    val values = mutableMapOf<String, String>()
    var inContainer = false
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--in-container" -> inContainer = true
            "--config", "--secrets", "--log", "--port", "--app", "--upstream" -> {
                if (i + 1 >= args.size) usage("$a needs a value")
                values[a] = args[++i]
            }
            else -> usage("unknown argument $a")
        }
        i++
    }
    val port = values["--port"]?.let { it.toIntOrNull() ?: usage("--port must be a number") } ?: DEFAULT_PORT
    return Options(
        config = Path.of(values["--config"] ?: usage("--config is required")),
        secrets = Path.of(values["--secrets"] ?: usage("--secrets is required")),
        log = values["--log"]?.let(Path::of),
        port = port,
        app = values["--app"] ?: "gateway",
        upstream = values["--upstream"]?.takeIf { it.isNotBlank() },
        inContainer = inContainer,
    )
}

private fun usage(problem: String): Nothing {
    System.err.println("gantry-auth: $problem")
    System.err.println(
        "usage: gantry-auth --config FILE --secrets FILE [--log FILE] [--port 9100] [--app NAME] [--upstream URL] [--in-container]",
    )
    exitProcess(2)
}

fun main(args: Array<String>) {
    // Quiet by default: one line at startup and nothing per request.
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    val o = parseOptions(args)
    val state = AuthServerState(o.config, o.secrets, o.log, o.app, o.upstream, o.inContainer)
    println("gantry-auth listening on :${o.port}")
    embeddedServer(CIO, host = "0.0.0.0", port = o.port) { authModule(state) }.start(wait = true)
}
