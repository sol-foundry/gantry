package dev.gantry.auth

import dev.gantry.gateway.Authorizer
import dev.gantry.gateway.GatewayConfig
import dev.gantry.gateway.HttpCall
import dev.gantry.gateway.SECRET_REF
import dev.gantry.gateway.Step
import dev.gantry.gateway.WEBSOCKET_ROUTE
import dev.gantry.gateway.defaultHeaders
import dev.gantry.gateway.headersFor
import dev.gantry.gateway.websocketHeaders
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Collections
import kotlinx.serialization.json.Json as KJson

/** What APISIX's forward-auth tells gantry-auth about a request. [route] is the gateway route's name. */
data class AuthRequest(
    val route: String,
    val method: String,
    val uri: String,
    val headers: Map<String, String>,
    val query: Map<String, String>,
)

/**
 * The decision: 200 with the [headers] APISIX sets upstream, or a deny status with a JSON [body] for the client.
 * [reason] is internal (for the log) and never reaches the client.
 */
data class AuthResult(
    val status: Int,
    val headers: Map<String, String>,
    val body: String?,
    val context: Map<String, String>,
    val reason: String?,
)

/** Makes the HTTP calls of `http-lookup` steps; a fake in tests. Throws on transport errors and timeouts. */
fun interface HttpCaller {
    fun call(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResponse
}

data class HttpResponse(
    val status: Int,
    val body: String,
)

/**
 * Time limits for gantry-auth's own calls (JWKS, steps). APISIX waits 10 s for forward-auth, so a stalled call must
 * fail first and be answered with a 503 rather than leave APISIX to time out.
 */
internal val OUTBOUND_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
internal val OUTBOUND_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(7)

/** [HttpCaller] on the JDK client, within [OUTBOUND_CONNECT_TIMEOUT] and [OUTBOUND_REQUEST_TIMEOUT]. */
class JdkHttpCaller : HttpCaller {
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(OUTBOUND_CONNECT_TIMEOUT).build()

    override fun call(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResponse {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(OUTBOUND_REQUEST_TIMEOUT)
                .method(method, publisher)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
        val response = client.send(request, BodyHandlers.ofString())
        return HttpResponse(response.statusCode(), response.body())
    }
}

/** A `{{secret:NAME}}` or a `{{name}}` context reference. */
private val PLACEHOLDER = Regex("${SECRET_REF.pattern}|\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}")
private val CR_LF = Regex("[\r\n]")
private val BEARER_PREFIX = Regex("^\\s*Bearer\\s*", RegexOption.IGNORE_CASE)
private const val CACHE_LIMIT = 10_000
private const val UPSTREAM = "upstream"
private const val ERROR_CODE = "errorCode"

/** The parts of a route (or the default action) a decision needs. */
private class Flow(
    val auth: String,
    val steps: List<String>,
    val headers: Map<String, String>,
)

private class Denied(
    val result: Outcome,
) : Exception(null, null, false, false)

/** An early end of the flow: the status, the client body and the internal reason. */
private class Outcome(
    val status: Int,
    val body: String,
    val reason: String,
)

/** A step failure we describe ourselves; any other exception is reported by its class name only. */
private class StepFailure(
    message: String,
) : Exception(message)

/** A step call with every template rendered. */
private data class Prepared(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
)

private class CachedStep(
    val values: Map<String, String>,
    val expiresAt: Instant,
)

/**
 * Decides whether a request may pass and which headers APISIX forwards upstream. Verifies the token with the route's
 * authorizer, turns claims into a context, runs the route's steps and renders the route's header templates over it.
 * [upstream] is what `{{upstream}}` renders to; [app] names the app in missing-secret hints; [log] receives one JSON
 * line per decision. Safe for concurrent use.
 */
@Suppress("LongParameterList")
class AuthEngine(
    private val config: GatewayConfig,
    private val verifier: TokenVerifier,
    private val secrets: (String) -> String?,
    private val app: String,
    private val upstream: String = config.upstream.orEmpty(),
    private val http: HttpCaller = JdkHttpCaller(),
    private val log: (String) -> Unit = {},
    private val clock: () -> Instant = Instant::now,
) {
    private val cache: MutableMap<String, CachedStep> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, CachedStep>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedStep>) = size > CACHE_LIMIT
            },
        )

    fun decide(request: AuthRequest): AuthResult {
        val context = linkedMapOf<String, String>()
        val resolved = mutableMapOf<String, String>()
        val reasons = mutableListOf<String>()
        var auth: String? = null
        val decided =
            try {
                val flow = flowFor(request.route) ?: throw deny(403, forbidden(), "unknown route ${request.route}")
                auth = flow.auth
                resolveSecrets(flow, resolved)
                val emailVerified = authorize(flow, request, context, resolved)
                if (emailVerified) flow.steps.forEach { runStep(it, context, resolved, reasons) }
                AuthResult(200, forwarded(flow, context, resolved), null, context.toMap(), reasons.joinToString("; ").ifEmpty { null })
            } catch (d: Denied) {
                AuthResult(d.result.status, emptyMap(), d.result.body, context.toMap(), d.result.reason)
            } catch (e: Exception) {
                // Whatever went wrong, the gateway gets an answer; the client never sees the exception.
                val what = auth?.takeIf { it in config.authorizers }?.let { "authorizer $it" } ?: "gateway auth"
                AuthResult(503, emptyMap(), errorBody("$what is unavailable"), context.toMap(), e.javaClass.simpleName)
            }
        val secretValues = resolved.values.toSet()
        val result = decided.copy(reason = decided.reason?.let { redact(it, secretValues) })
        writeLog(request, result, secretValues)
        return result
    }

    /** Defense in depth: no resolved secret, raw or URL-encoded, survives in an internal reason. */
    private fun redact(
        text: String,
        secretValues: Set<String>,
    ): String =
        secretValues.filter { it.isNotEmpty() }.sortedByDescending { it.length }.fold(text) { acc, v ->
            acc.replace(v, "[secret]").replace(encodeUrl(v), "[secret]")
        }

    private fun flowFor(route: String): Flow? {
        config.routes.firstOrNull { it.name == route }?.let { return Flow(it.auth, it.steps, config.headersFor(it)) }
        if (route == "default") return Flow(config.default.auth, config.default.steps, config.defaultHeaders())
        if (route == WEBSOCKET_ROUTE) return Flow(config.websocket.auth, config.websocket.steps, config.websocketHeaders())
        return null
    }

    /** Every secret the route's flow uses, up front, so a missing one fails the route before anything runs. */
    private fun resolveSecrets(
        flow: Flow,
        resolved: MutableMap<String, String>,
    ) {
        val texts = mutableListOf<String>()
        val authorizer = config.authorizers[flow.auth]
        authorizer?.jwtSecret?.let { texts += it }
        // A custom authorizer reaches gantry-auth only while its env names a secret that is not set: say which one.
        if (authorizer?.type == "custom") texts += authorizer.env.values
        flow.steps.mapNotNull { config.steps[it] }.forEach { step ->
            listOfNotNull(step.lookup, step.create).forEach { call ->
                texts += call.url
                texts += call.headers.values
                call.body?.let { texts += it.values }
            }
        }
        texts += flow.headers.values
        texts.flatMap { t -> SECRET_REF.findAll(t).map { it.groupValues[1] } }.distinct().forEach { name ->
            val value = secrets(name)?.takeIf { it.isNotEmpty() }
            if (value == null) {
                val body = errorBody("secret $name is not set; set it with gantry secret set $app $name")
                throw Denied(Outcome(503, body, "secret $name is not set"))
            }
            resolved[name] = value
        }
    }

    /** Verifies the caller and fills [context] from the claims; false when the email is unverified and steps are skipped. */
    private fun authorize(
        flow: Flow,
        request: AuthRequest,
        context: MutableMap<String, String>,
        resolved: Map<String, String>,
    ): Boolean {
        when (flow.auth) {
            "public" -> return true
            "deny" -> throw deny(403, forbidden(), "route denies every request")
        }
        val configured = config.authorizers[flow.auth]
        configured?.container?.takeIf { configured.type == "custom" }?.let { container ->
            // The renderer sends a custom authorizer's routes here while its container has not restarted with its
            // current env (its secrets are all set, so this is the only reason): a stale container is never asked.
            val problem = "auth-$container has not restarted with its current env"
            throw deny(
                503,
                errorBody("authorizer ${flow.auth} is unavailable: $problem; run gantry gateway reload $app"),
                "authorizer ${flow.auth}: $problem",
            )
        }
        val authorizer =
            configured?.takeIf { it.type != "custom" }
                ?: throw unavailable("authorizer ${flow.auth}", "authorizer ${flow.auth} is not verified by gantry-auth")
        val token = tokenFrom(authorizer, request)
        when (val verdict = verifier.verify(authorizer, token) { resolved[it] ?: secrets(it) }) {
            is Verdict.Unavailable -> throw unavailable("authorizer ${flow.auth}", verdict.reason)
            is Verdict.Invalid -> {
                val noToken = token?.replace(BEARER_PREFIX, "").isNullOrBlank()
                val status = if (noToken) authorizer.missingTokenStatus ?: authorizer.denyStatus else authorizer.denyStatus
                throw deny(status, clientError(status, verdict.reason), verdict.reason)
            }
            is Verdict.Valid -> {
                context += contextFrom(verdict.claims)
                if (context["email_verified"] == "false") {
                    val action = authorizer.onUnverifiedEmail
                    if (!action.startsWith("continue:")) {
                        throw deny(authorizer.denyStatus, clientError(authorizer.denyStatus, "email not verified"), "email not verified")
                    }
                    context[ERROR_CODE] = action.removePrefix("continue:")
                    return false
                }
            }
        }
        return true
    }

    private fun tokenFrom(
        a: Authorizer,
        request: AuthRequest,
    ): String? {
        val (kind, name) = a.tokenFrom.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (kind) {
            "query" -> request.query[name]
            else ->
                request.headers.entries
                    .firstOrNull { it.key.equals(name, ignoreCase = true) }
                    ?.value
        }
    }

    private fun contextFrom(claims: Map<String, Any?>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        claims.forEach { (k, v) -> scalar(v)?.let { out[k] = it } }
        val sub = scalar(claims["sub"])
        sub?.let { out["sub"] = it }
        (scalar(claims["user_id"]) ?: sub)?.let { out["uid"] = it }
        // Set only by a soft failure, never by a token.
        out.remove(ERROR_CODE)
        return out
    }

    private fun scalar(v: Any?): String? =
        when (v) {
            is String -> v
            is Boolean, is Number -> v.toString()
            else -> null
        }

    private fun runStep(
        name: String,
        context: MutableMap<String, String>,
        resolved: Map<String, String>,
        reasons: MutableList<String>,
    ) {
        val step = config.steps[name] ?: throw unavailable("step $name", "step $name is not defined")
        val lookup = prepare(step.lookup, context, resolved)
        val key = cacheKey(name, lookup)
        val now = clock()
        val cached = cache[key]?.takeIf { now < it.expiresAt }
        if (cached != null) {
            context += cached.values
            return
        }
        val values =
            try {
                fetch(step, lookup, context, resolved)
            } catch (e: Exception) {
                val reason = "step $name failed: ${if (e is StepFailure) e.message else e.javaClass.simpleName}"
                if (!step.onFailure.startsWith("continue:")) {
                    // A step that answered wrongly denies; one that could not be reached is an outage.
                    throw if (e is StepFailure) deny(403, forbidden(), reason) else unavailable("step $name", reason)
                }
                // A same-named claim must not stand in for a value the step failed to provide.
                step.map.keys.forEach { context.remove(it) }
                context[ERROR_CODE] = step.onFailure.removePrefix("continue:")
                reasons += reason
                return
            }
        context += values
        if (step.cacheSeconds > 0) cache[key] = CachedStep(values, now.plusSeconds(step.cacheSeconds.toLong()))
    }

    private fun fetch(
        step: Step,
        lookup: Prepared,
        context: Map<String, String>,
        resolved: Map<String, String>,
    ): Map<String, String> {
        var response = send(lookup)
        if (response.status == 404 && step.create != null) response = send(prepare(step.create, context, resolved))
        if (response.status !in 200..299) throw StepFailure("HTTP ${response.status}")
        val json =
            try {
                KJson.parseToJsonElement(response.body)
            } catch (_: Exception) {
                throw StepFailure("response is not JSON")
            }
        return step.map.mapValues { (key, path) -> select(json, path) ?: throw StepFailure("$path is missing for $key") }
    }

    private fun prepare(
        call: HttpCall,
        context: Map<String, String>,
        resolved: Map<String, String>,
    ): Prepared {
        val headers = linkedMapOf<String, String>()
        call.headers.forEach { (k, v) -> headers[k] = headerValue(render(v, context, resolved)) }
        val body =
            call.body?.let { fields ->
                if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) headers["Content-Type"] = "application/json"
                JsonObject(fields.mapValues { (_, v) -> JsonPrimitive(render(v, context, resolved)) }).toString()
            }
        return Prepared(call.method, render(call.url, context, resolved, encode = true), headers, body)
    }

    private fun send(p: Prepared): HttpResponse = http.call(p.method, p.url, p.headers, p.body)

    /** The whole rendered lookup, hashed: two callers share a cached result only when they would send the same request. */
    private fun cacheKey(
        step: String,
        p: Prepared,
    ): String {
        val parts = listOf(step, p.method, p.url) + p.headers.toSortedMap().map { "${it.key}:${it.value}" } + listOf(p.body ?: "")
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Reads a `$.a.b` path; objects and arrays come back as JSON text, a JSON null as missing. */
    private fun select(
        root: JsonElement,
        path: String,
    ): String? {
        val parts = path.removePrefix("$").split('.').filter { it.isNotEmpty() }
        var at: JsonElement = root
        for (p in parts) at = (at as? JsonObject)?.get(p) ?: return null
        return when (at) {
            is JsonNull -> null
            is JsonPrimitive -> at.content
            else -> at.toString()
        }
    }

    /** The union of the strip headers and the route's own headers, every one present; unknown values are empty. */
    private fun forwarded(
        flow: Flow,
        context: Map<String, String>,
        resolved: Map<String, String>,
    ): Map<String, String> {
        val out = linkedMapOf<String, String>()
        flow.headers.forEach { (k, v) -> out[k] = headerValue(render(v, context, resolved)) }
        config.stripHeaders.forEach { h -> if (out.keys.none { it.equals(h, ignoreCase = true) }) out[h] = "" }
        return out
    }

    /**
     * Substitutes placeholders in one pass, so a value is never itself rendered. With [encode], context values are
     * and secrets are percent-encoded for use in a URL; `{{upstream}}` and the template itself are inserted as they are.
     */
    private fun render(
        template: String,
        context: Map<String, String>,
        resolved: Map<String, String>,
        encode: Boolean = false,
    ): String =
        PLACEHOLDER.replace(template) { m ->
            val secret = m.groupValues[1]
            val name = m.groupValues[2]
            when {
                secret.isNotEmpty() -> {
                    val value = resolved[secret] ?: error("secret $secret was not resolved")
                    if (encode) encodeUrl(value) else value
                }
                name == UPSTREAM -> upstream
                else -> {
                    val value = context[name].orEmpty()
                    if (encode) encodeUrl(value) else value
                }
            }
        }

    /** Percent-encodes a value for a URL; one made only of dots is encoded too, so it cannot become a `.` or `..` segment. */
    private fun encodeUrl(value: String): String =
        if (value.isNotEmpty() && value.all { it == '.' }) {
            "%2E".repeat(value.length)
        } else {
            URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
        }

    private fun headerValue(value: String) = value.replace(CR_LF, "")

    private fun writeLog(
        request: AuthRequest,
        result: AuthResult,
        secretValues: Set<String>,
    ) {
        val line =
            buildJsonObject {
                put("time", clock().toString())
                put(
                    "requestId",
                    request.headers.entries
                        .firstOrNull { it.key.equals("X-Request-Id", ignoreCase = true) }
                        ?.value,
                )
                put("route", request.route)
                put("decision", if (result.status == 200) "allow" else "deny")
                put("status", result.status)
                put("reason", result.reason)
                put(
                    "context",
                    JsonObject(result.context.filterValues { it !in secretValues }.mapValues { JsonPrimitive(it.value) }),
                )
            }.toString()
        try {
            log(line)
        } catch (_: Exception) {
            // A failing log must not change the decision.
        }
    }

    private fun deny(
        status: Int,
        body: String,
        reason: String,
    ) = Denied(Outcome(status, body, reason))

    private fun unavailable(
        what: String,
        reason: String,
    ) = deny(503, errorBody("$what is unavailable"), reason)

    private fun forbidden() = errorBody("forbidden")

    private fun clientError(
        status: Int,
        reason: String,
    ): String =
        buildJsonObject {
            put("error", if (status == 401) "unauthorized" else "forbidden")
            put("reason", reason)
        }.toString()

    private fun errorBody(message: String) = buildJsonObject { put("error", message) }.toString()
}
