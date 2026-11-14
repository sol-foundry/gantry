package dev.gantry.gateway

import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import dev.gantry.engine.GantryException
import it.krzeminski.snakeyaml.engine.kmp.api.LoadSettings
import it.krzeminski.snakeyaml.engine.kmp.api.lowlevel.Parse
import it.krzeminski.snakeyaml.engine.kmp.comments.CommentType
import it.krzeminski.snakeyaml.engine.kmp.events.CommentEvent
import it.krzeminski.snakeyaml.engine.kmp.exceptions.YamlEngineException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The headers the gateway always removes from incoming requests, so a client cannot pose as an authenticated user. */
val DEFAULT_STRIP =
    listOf("x-user-id", "x-gateway-user-email", "x-gateway-auth-strategy", "x-internal-api-key", "x-error-code")

/**
 * What an app's `gateway.yml` says: which upstream to proxy to, who may call which routes, and how a verified
 * caller is turned into headers for the app. Everything is optional; an empty file is a transparent proxy.
 */
@Serializable
data class GatewayConfig(
    /** Overrides the service's `upstream` option. */
    val upstream: String? = null,
    /** Headers for every route and the default action; a route's own `headers` add to them or replace one by name. */
    val headers: Map<String, String> = emptyMap(),
    val authorizers: Map<String, Authorizer> = emptyMap(),
    val steps: Map<String, Step> = emptyMap(),
    val routes: List<Route> = emptyList(),
    /** What happens to requests no route matches: a transparent proxy unless the file says otherwise. */
    val default: RouteAction = RouteAction(auth = "public"),
    val cors: Cors? = null,
    /** Headers removed from every incoming request before anything else happens. */
    val stripHeaders: List<String> = DEFAULT_STRIP,
    val log: LogSettings = LogSettings(),
)

/**
 * The headers [route] sends to the app: the shared [GatewayConfig.headers] with the route's own on top. Names match
 * in any case; a route's own header replaces a shared one of the same name, with the route's spelling and value.
 */
fun GatewayConfig.headersFor(route: Route): Map<String, String> = mergeHeaders(headers, route.headers)

/** The headers the default action sends to the app, like [headersFor] a route. */
fun GatewayConfig.defaultHeaders(): Map<String, String> = mergeHeaders(headers, default.headers)

private fun mergeHeaders(
    shared: Map<String, String>,
    own: Map<String, String>,
): Map<String, String> {
    if (shared.isEmpty()) return own
    val out = linkedMapOf<String, String>()
    shared.forEach { (k, v) -> if (own.keys.none { it.equals(k, ignoreCase = true) }) out[k] = v }
    return out + own
}

@Serializable
data class Cors(
    val origins: List<String> = listOf("*"),
    /** Cookies and auth headers on cross-origin calls; needs [origins] listed explicitly, never `*`. */
    val credentials: Boolean = false,
    val allowHeaders: List<String> = emptyList(),
    val exposeHeaders: List<String> = emptyList(),
    val maxAge: Int = 600,
)

/** How a caller proves who they are. [type] is `firebase`, `cognito`, `supabase`, `jwt` or `custom`. */
@Serializable
data class Authorizer(
    val type: String,
    /** firebase */
    val projectId: String? = null,
    /** cognito */
    val region: String? = null,
    val userPoolId: String? = null,
    val clientIds: List<String> = emptyList(),
    val tokenUse: String = "id",
    /** The supabase project URL, or the URL of a custom authorizer. */
    val url: String? = null,
    /** supabase legacy HS256 secret, normally `{{secret:NAME}}`. */
    val jwtSecret: String? = null,
    /** jwt: a JWKS endpoint, or an inline key set for tests and dev. */
    val jwksUrl: String? = null,
    @Serializable(with = JsonObjectYamlSerializer::class)
    val jwks: JsonObject? = null,
    val issuer: String? = null,
    val audiences: List<String> = emptyList(),
    val algorithms: List<String> = listOf("RS256"),
    /** custom: the name of a pushed authorizer. */
    val container: String? = null,
    /** custom container: environment variables, templated; `{{secret:NAME}}` is allowed. */
    val env: Map<String, String> = emptyMap(),
    /** Where the token is read from: `header:Authorization` or `query:token`. */
    val tokenFrom: String = "header:Authorization",
    /** `deny` or `continue:<errorCode>`. */
    val onUnverifiedEmail: String = "deny",
    val denyStatus: Int = 403,
    val missingTokenStatus: Int? = null,
)

/** Extra work for a verified caller, such as looking the user up in the app. [type] is `http-lookup`. */
@Serializable
data class Step(
    val type: String,
    val lookup: HttpCall,
    val create: HttpCall? = null,
    /** Context key to a `$.json.path` in the response. */
    val map: Map<String, String> = emptyMap(),
    val cacheSeconds: Int = 300,
    /** `deny` or `continue:<errorCode>`. */
    val onFailure: String = "deny",
)

@Serializable
data class HttpCall(
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: Map<String, String>? = null,
)

@Serializable
data class Route(
    val name: String,
    val match: Match,
    val auth: String = "public",
    val steps: List<String> = emptyList(),
    val rewrite: Rewrite? = null,
    val upstream: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class Match(
    val host: String = "*",
    val path: String = "/**",
    val methods: List<String> = emptyList(),
)

@Serializable
data class Rewrite(
    val addPrefix: String? = null,
    val stripPrefix: String? = null,
    val replace: String? = null,
)

/** The part of a route that applies to requests no route matches. */
@Serializable
data class RouteAction(
    val auth: String = "public",
    val steps: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val upstream: String? = null,
)

@Serializable
data class LogSettings(
    val maxCalls: Int = 1000,
    val maxBodyBytes: Int = 65536,
    val redact: List<String> = listOf("authorization", "cookie", "set-cookie", "x-internal-api-key"),
)

private const val FILE = "gateway.yml"
private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true))

// Defaults are left out so a file written from the form stays short; strings are quoted only where YAML needs it.
private val writer =
    Yaml(
        configuration =
            YamlConfiguration(
                encodeDefaults = false,
                singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous,
                breakScalarsAt = Int.MAX_VALUE,
                sequenceBlockIndent = 2,
            ),
    )

/** The `gateway.yml` text for a config from the form, or (with no text) why it cannot be saved as given. */
data class FormYaml(
    val text: String?,
    val problems: List<String>,
)

private val everyField = Json { encodeDefaults = true }

/**
 * [config] as `gateway.yml` text, only when that text reads back to an equal config: the file must hold exactly what
 * the form showed. Otherwise the problems name each field that would change (such as a number in an inline key set,
 * which YAML keeps as text), or say why it could not be written or read back.
 */
fun formYaml(
    config: GatewayConfig,
    encode: (GatewayConfig) -> String = ::encodeGatewayConfig,
): FormYaml {
    val text =
        try {
            encode(config)
        } catch (e: Exception) {
            return FormYaml(null, listOf("could not be written as gateway.yml: ${e.message?.lineSequence()?.first()}"))
        }
    val back =
        try {
            parseGatewayConfig(text)
        } catch (e: GantryException) {
            return FormYaml(null, listOf("would not read back as written: ${e.message}"))
        }
    if (back == config) return FormYaml(text, emptyList())
    val changed = mutableListOf<String>()
    jsonDifferences(
        "",
        everyField.encodeToJsonElement(GatewayConfig.serializer(), config),
        everyField.encodeToJsonElement(GatewayConfig.serializer(), back),
        changed,
    )
    return FormYaml(null, changed.ifEmpty { listOf("would not read back as written") })
}

/** A number or true/false that YAML gives back as the same text in a string, as in an inline key set. */
private fun keptAsText(
    given: JsonElement,
    saved: JsonElement?,
): Boolean {
    if (given !is JsonPrimitive || given is JsonNull || given.isString) return false
    return saved is JsonPrimitive && saved.isString && saved.content == given.content
}

/** Appends a problem for each path where [given] and [saved] differ; values themselves are never quoted. */
private fun jsonDifferences(
    path: String,
    given: JsonElement,
    saved: JsonElement?,
    out: MutableList<String>,
) {
    fun at(key: String) = if (path.isEmpty()) key else "$path.$key"
    when {
        given is JsonObject && saved is JsonObject ->
            (given.keys + saved.keys).forEach { k -> jsonDifferences(at(k), given[k] ?: JsonNull, saved[k], out) }
        given is JsonArray && saved is JsonArray && given.size == saved.size ->
            given.forEachIndexed { i, item -> jsonDifferences("$path[$i]", item, saved[i], out) }
        given == saved -> Unit
        keptAsText(given, saved) -> {
            val kind = if (given.toString() == "true" || given.toString() == "false") "a true/false" else "a number"
            out += "$path: $kind would be saved as text; write it as a string"
        }
        else -> out += "$path: would not read back as given"
    }
}

/**
 * True when [text] has a YAML comment, on its own line or after a value; a `#` inside a quoted or block value is not
 * one. Text that is not YAML is checked line by line instead.
 */
fun gatewayYmlHasComments(text: String): Boolean =
    try {
        Parse(LoadSettings(parseComments = true)).parse(text).any { it is CommentEvent && it.commentType != CommentType.BLANK_LINE }
    } catch (e: YamlEngineException) {
        text.lineSequence().any { it.trimStart().startsWith("#") }
    }

/**
 * [config] as `gateway.yml` text, with every defaulted field left out and keys in the model's order; it parses back
 * to an equal config. The defaults alone are an empty file.
 */
fun encodeGatewayConfig(config: GatewayConfig): String =
    if (config == GatewayConfig()) "" else writer.encodeToString(GatewayConfig.serializer(), config).trimEnd('\n') + "\n"

/** Parses `gateway.yml`; unknown keys and malformed YAML are errors that name the line. A blank file is the defaults. */
fun parseGatewayConfig(text: String): GatewayConfig {
    if (text.lineSequence().all { it.isBlank() || it.trimStart().startsWith("#") }) return GatewayConfig()
    try {
        return yaml.decodeFromString(GatewayConfig.serializer(), text)
    } catch (e: YamlException) {
        val where = "line ${e.line}, column ${e.column}"
        val field = e.path.toHumanReadableString().takeIf { it.isNotBlank() && it != "<root>" }
        throw GantryException("$FILE: ${e.message?.lineSequence()?.first()} ($where${field?.let { ", at $it" } ?: ""})")
    } catch (e: SerializationException) {
        throw GantryException("$FILE: ${e.message?.lineSequence()?.first()}")
    }
}

private val AUTHORIZER_TYPES = setOf("firebase", "cognito", "supabase", "jwt", "custom")
private val STEP_TYPES = setOf("http-lookup")
private val FAILURE_SYNTAX = Regex("deny|continue:\\S+")
private val TOKEN_SOURCE = Regex("(header|query):\\S+")

// APISIX checks its routes against these and skips (logs) a route that fails; its requests would then fall through
// to a later route or the public default. So validate() refuses them up front.
private val ROUTE_NAME = Regex("^[A-Za-z0-9._-]{1,64}$")
private val HOST = Regex("^\\*?[0-9a-zA-Z._\\-\\[\\]:]+$")
private val ORIGIN = Regex("^https?://[^/\\s,]+$")

/** The HTTP methods APISIX accepts on a route; the renderer upper-cases the configured ones. */
internal val HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "CONNECT", "TRACE", "PURGE")

/** Route `auth` values that are not authorizers. */
private val RESERVED_AUTH = setOf("public", "deny")
private val ERROR_STATUSES = 400..599

/** Human-readable problems with the config; empty means it is valid. */
fun GatewayConfig.validate(): List<String> {
    val problems = mutableListOf<String>()
    // A credentialed wildcard would let any website call the gateway as the logged-in user.
    if (cors?.credentials == true &&
        ("*" in cors.origins || cors.origins.isEmpty())
    ) {
        problems += "cors: credentials need explicit origins, not *"
    }
    cors?.origins?.filter { it != "*" && !ORIGIN.matches(it) }?.forEach {
        problems += "cors: origin '$it' must be scheme://host[:port], or *"
    }
    authorizers.keys.filter { it in RESERVED_AUTH }.forEach { problems += "authorizer name '$it' is reserved" }
    authorizers.forEach { (name, a) -> a.problems("authorizer '$name'", problems) }
    steps.forEach { (name, s) ->
        if (s.type !in STEP_TYPES) problems += "step '$name': unknown type '${s.type}'"
        if (!FAILURE_SYNTAX.matches(s.onFailure)) problems += "step '$name': onFailure must be 'deny' or 'continue:<code>'"
    }

    fun checkAction(
        label: String,
        auth: String,
        stepNames: List<String>,
    ) {
        if (auth !in RESERVED_AUTH && auth !in authorizers) problems += "$label: unknown authorizer '$auth'"
        stepNames.filter { it !in steps }.forEach { problems += "$label: unknown step '$it'" }
    }
    routes
        .groupBy { it.name }
        .filterValues { it.size > 1 }
        .keys
        .forEach { problems += "route '$it' is defined more than once" }
    // The catch-all APISIX route already has the id `default`.
    if (routes.any { it.name == "default" }) problems += "route name 'default' is reserved"
    routes.forEach { route ->
        val label = "route '${route.name}'"
        if (!ROUTE_NAME.matches(route.name)) problems += "$label: name may only use letters, digits, '.', '_' and '-' (at most 64)"
        if (!route.match.path.startsWith("/")) problems += "$label: path must start with '/'"
        route.match.methods
            .filter { it.uppercase() !in HTTP_METHODS }
            .forEach { problems += "$label: unknown method '$it'" }
        // APISIX's methods are uniqueItems after the renderer upper-cases them: a repeat would get the route skipped.
        route.match.methods
            .groupBy { it.uppercase() }
            .values
            .filter { it.size > 1 }
            .forEach { problems += "$label: method '${it[1]}' is listed more than once" }
        if (route.match.host != "*" && !HOST.matches(route.match.host)) {
            problems += "$label: host '${route.match.host}' is not a host name (only a leading *. wildcard is allowed)"
        }
        checkAction(label, route.auth, route.steps)
    }
    checkAction("default", default.auth, default.steps)
    return problems
}

private fun Authorizer.problems(
    label: String,
    out: MutableList<String>,
) {
    when (type) {
        "firebase" -> if (projectId.isNullOrBlank()) out += "$label: firebase needs projectId"
        "cognito" -> {
            if (region.isNullOrBlank() || userPoolId.isNullOrBlank()) out += "$label: cognito needs region and userPoolId"
            if (clientIds.isEmpty()) out += "$label: cognito needs clientIds"
        }
        "supabase" -> if (url.isNullOrBlank()) out += "$label: supabase needs url"
        "jwt" -> if (jwksUrl.isNullOrBlank() && jwks == null) out += "$label: jwt needs jwksUrl or jwks"
        "custom" -> {
            if (container.isNullOrBlank() && url.isNullOrBlank()) out += "$label: custom needs container or url"
            out += customAuthorizerProblems(label, this)
        }
        else -> out += "$label: unknown type '$type' (use ${AUTHORIZER_TYPES.joinToString(", ")})"
    }
    if (!FAILURE_SYNTAX.matches(onUnverifiedEmail)) out += "$label: onUnverifiedEmail must be 'deny' or 'continue:<code>'"
    if (!TOKEN_SOURCE.matches(tokenFrom)) out += "$label: tokenFrom must be 'header:<name>' or 'query:<name>'"
    // A status outside the error range would turn every denial into a pass.
    if (denyStatus !in ERROR_STATUSES) out += "$label: denyStatus must be between 400 and 599"
    if (missingTokenStatus != null && missingTokenStatus !in ERROR_STATUSES) out += "$label: missingTokenStatus must be between 400 and 599"
}

internal val SECRET_REF = Regex("\\{\\{secret:([^}\\s]+)}}")

/** The secret name when [value] is exactly one `{{secret:NAME}}` reference, else null. */
internal fun secretRefName(value: String): String? = SECRET_REF.matchEntire(value.trim())?.groupValues?.get(1)

// Defaults are left out: none of them holds a secret.
private val secretScanJson = Json

/**
 * Every `{{secret:NAME}}` the config refers to, in any string of any field, so the app can be told which secrets it
 * still needs to set and gantry-auth is handed all of them.
 */
fun GatewayConfig.secretNames(): Set<String> {
    val found = linkedSetOf<String>()

    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> element.values.forEach(::walk)
            is JsonArray -> element.forEach(::walk)
            is JsonPrimitive -> if (element.isString) SECRET_REF.findAll(element.content).forEach { found += it.groupValues[1] }
        }
    }
    walk(secretScanJson.encodeToJsonElement(GatewayConfig.serializer(), this))
    return found
}

/**
 * Turns a path pattern into an anchored regex: `*` is exactly one non-empty path segment, `**` is anything including
 * the slash (and a pattern ending in slash-star-star also matches the bare prefix), everything else is literal. A path with a `..` segment,
 * plain or percent-encoded, never matches, so a pattern cannot be escaped by walking up out of it.
 */
fun globToRegex(glob: String): Regex {
    val out = StringBuilder("^(?!.*(?:^|/)(?:\\.|%2[eE]){2}(?:/|$))")
    var i = 0
    while (i < glob.length) {
        when {
            glob.startsWith("/**", i) && i + 3 == glob.length -> {
                out.append("(?:/.*)?")
                i += 3
            }
            glob.startsWith("**", i) -> {
                out.append(".*")
                i += 2
            }
            glob[i] == '*' -> {
                out.append("[^/]+")
                i++
            }
            else -> {
                out.append(Regex.escape(glob[i].toString()))
                i++
            }
        }
    }
    return Regex(out.append('$').toString())
}

/** Reads a YAML mapping (an inline JWKS) into a [JsonObject] and writes it back; scalars stay strings. */
@OptIn(ExperimentalSerializationApi::class)
internal object JsonObjectYamlSerializer : KSerializer<JsonObject?> {
    override val descriptor: SerialDescriptor = YamlNode.serializer().descriptor

    override fun deserialize(decoder: Decoder): JsonObject? {
        val element =
            when (decoder) {
                is YamlInput -> decoder.decodeSerializableValue(YamlNode.serializer()).toJson()
                is JsonDecoder -> decoder.decodeJsonElement()
                else -> throw SerializationException("jwks can only be read from YAML or JSON")
            }
        if (element is JsonNull) return null
        return element as? JsonObject ?: throw SerializationException("jwks must be a mapping")
    }

    /** JSON is written as it is; YAML as nested mappings and lists of strings, which is how it reads back. */
    override fun serialize(
        encoder: Encoder,
        value: JsonObject?,
    ) = when {
        value == null -> encoder.encodeNull()
        encoder is JsonEncoder -> encoder.encodeJsonElement(value)
        else -> JsonAsYaml.serialize(encoder, value)
    }

    /** Writes any JSON value with the plain map, list and string encoders, so a non-JSON format can hold it. */
    private object JsonAsYaml : KSerializer<JsonElement> {
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

        override fun serialize(
            encoder: Encoder,
            value: JsonElement,
        ) = when (value) {
            is JsonObject -> encoder.encodeSerializableValue(MapSerializer(String.serializer(), this), value)
            is JsonArray -> encoder.encodeSerializableValue(ListSerializer(this), value)
            is JsonNull -> encoder.encodeNull()
            is JsonPrimitive -> encoder.encodeString(value.content)
        }

        override fun deserialize(decoder: Decoder): JsonElement = throw SerializationException("only written")
    }

    private fun YamlNode.toJson(): JsonElement =
        when (this) {
            is YamlScalar -> JsonPrimitive(content)
            is YamlNull -> JsonNull
            is YamlList -> JsonArray(items.map { it.toJson() })
            is YamlMap -> JsonObject(entries.entries.associate { (k, v) -> k.content to v.toJson() })
            is YamlTaggedNode -> innerNode.toJson()
        }
}

/** Written by `gantry add` as the app's first `gateway.yml`: a transparent proxy plus a commented-out example. */
const val DEFAULT_GATEWAY_YML: String =
    """# Gateway configuration for this app.
#
# With nothing below, the gateway is a transparent proxy: every request is forwarded
# to the upstream (the service's `upstream` option) and nobody is asked to log in.
# Uncomment the example at the bottom to verify tokens and look the user up in your app.
#
# upstream: http://host.docker.internal:8080   # overrides the service option

default:
  auth: public

# Example: Firebase login, the user looked up in the app, and two routes.
#
# authorizers:
#   fb:
#     type: firebase
#     projectId: my-firebase-project
#     onUnverifiedEmail: continue:email_not_verified
#
# steps:
#   user:
#     type: http-lookup
#     lookup:
#       url: "{{upstream}}/internal/users/by-uid/{{uid}}"
#       headers:
#         x-internal-api-key: "{{secret:INTERNAL_API_KEY}}"
#     map:
#       userId: "$.id"
#     onFailure: continue:user_lookup_failed
#
# routes:
#   - name: portal
#     match:
#       path: /portal/**
#     auth: fb
#     steps: [user]
#     rewrite:
#       addPrefix: /portal
#   - name: health
#     match:
#       path: /actuator/**
#     auth: public
"""
