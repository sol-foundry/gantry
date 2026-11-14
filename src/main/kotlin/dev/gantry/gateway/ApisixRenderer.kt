package dev.gantry.gateway

import dev.gantry.db.containerHost
import dev.gantry.engine.GantryException
import java.net.URI
import java.net.URLEncoder

/** What the renderer needs to know about the app beyond its `gateway.yml`. */
data class RenderContext(
    val app: String,
    /** The service's `upstream` option, used when neither the route nor the config names one. */
    val defaultUpstream: String,
    val authHost: String = "gantry-auth",
    val authPort: Int = 9100,
    /** Pushed authorizer name to its container port. */
    val authorizerPorts: Map<String, Int> = emptyMap(),
    /** Secrets `gateway.yml` refers to that are not set. */
    val unsetSecrets: Set<String> = emptySet(),
    /** Pushed authorizers whose container does not run with its current env yet. */
    val pendingContainers: Set<String> = emptySet(),
)

private val FORWARD_AUTH_HEADERS = listOf("Authorization", "Cookie", "X-Request-Id", "X-Forwarded-Host", "X-User-Timezone")

/** APISIX `config.yaml`: a standalone data plane that reads its routes from `apisix.yaml`. */
fun renderApisixConfig(): String =
    emitYaml(
        linkedMapOf(
            "deployment" to
                linkedMapOf(
                    "role" to "data_plane",
                    "role_data_plane" to linkedMapOf("config_provider" to "yaml"),
                ),
            "apisix" to
                linkedMapOf(
                    "node_listen" to 9080,
                    "enable_admin" to false,
                    // The default radixtree_host_uri router tries every route with hosts before any route without, so a
                    // host route would win over an earlier, stricter one. With this one the priority alone decides.
                    "router" to linkedMapOf("http" to "radixtree_uri"),
                ),
            "plugins" to listOf("request-id", "proxy-rewrite", "forward-auth", "cors", "file-logger"),
            // The default stream plugins include syslog, which fails to load at boot without the prometheus plugin.
            "stream_plugins" to emptyList<String>(),
        ),
    )

/**
 * APISIX `apisix.yaml`: one route per gateway route in order (earlier routes get a higher priority, so the first
 * match wins), then the default action as the catch-all. An upstream URL must not carry a path.
 */
fun renderApisixRoutes(
    config: GatewayConfig,
    ctx: RenderContext,
): String {
    val last = config.routes.size
    val routes =
        config.routes.mapIndexed { i, r ->
            apisixRoute(config, ctx, r.name, r.name, r.match, r.auth, r.rewrite, r.upstream, config.headersFor(r), last - i)
        } +
            apisixRoute(
                config,
                ctx,
                "default",
                "default",
                Match(),
                config.default.auth,
                null,
                config.default.upstream,
                config.defaultHeaders(),
                0,
            )
    return emitYaml(linkedMapOf("routes" to routes)) + "#END\n"
}

@Suppress("LongParameterList")
private fun apisixRoute(
    config: GatewayConfig,
    ctx: RenderContext,
    id: String,
    name: String,
    match: Match,
    auth: String,
    rewrite: Rewrite?,
    upstream: String?,
    headers: Map<String, String>,
    priority: Int,
): Map<String, Any> {
    val route = linkedMapOf<String, Any>("id" to id, "uri" to "/*")
    if (match.host != "*") route["hosts"] = listOf(match.host)
    if (match.methods.isNotEmpty()) route["methods"] = match.methods.map { it.uppercase() }.distinct()
    route["priority"] = priority
    // APISIX proxies a WebSocket upgrade only on routes that allow it; the route's auth still runs on the handshake.
    route["enable_websocket"] = true
    if (id != "default") route["vars"] = listOf(listOf("uri", "~~", globToRegex(match.path).pattern))
    route["upstream"] = apisixUpstream(upstream ?: config.upstream ?: ctx.defaultUpstream)
    // APISIX's 60 s default read timeout would cut an idle WebSocket or a quiet SSE stream.
    route["timeout"] = linkedMapOf("connect" to 6, "send" to 3600, "read" to 3600)

    val plugins = linkedMapOf<String, Any>("request-id" to linkedMapOf("header_name" to "X-Request-Id"))
    val rewriteHeaders = linkedMapOf<String, Any>()
    if (auth == "public" && headers.isNotEmpty()) {
        rewriteHeaders["set"] = headers.mapValues { (_, v) -> if ("{{" in v) "" else v }
    }
    // A protected route's own headers are removed too, so a client cannot supply one the authorizer leaves out. A
    // public route sets its headers itself, which replaces any client copy; proxy-rewrite removes after it sets, so a
    // header both set and removed would arrive missing.
    val removed =
        if (auth == "public") {
            config.stripHeaders.filter { h -> headers.keys.none { it.equals(h, ignoreCase = true) } }
        } else {
            config.stripHeaders + headers.keys
        }
    // APISIX refuses an empty list or block here (and with it the whole route), so empty ones are left out.
    removed.distinctBy { it.lowercase() }.takeIf { it.isNotEmpty() }?.let { rewriteHeaders["remove"] = it }
    val proxyRewrite = linkedMapOf<String, Any>()
    if (rewriteHeaders.isNotEmpty()) proxyRewrite["headers"] = rewriteHeaders
    rewrite?.let { regexUri(it) }?.let { proxyRewrite["regex_uri"] = it }
    if (proxyRewrite.isNotEmpty()) plugins["proxy-rewrite"] = proxyRewrite
    if (auth != "public") plugins["forward-auth"] = forwardAuth(config, ctx, name, auth, headers)
    config.cors?.let { plugins["cors"] = corsPlugin(it) }
    plugins["file-logger"] =
        linkedMapOf("path" to "/gantry-log/calls.log", "include_req_body" to true, "include_resp_body" to true)
    route["plugins"] = plugins
    return route
}

private fun regexUri(rewrite: Rewrite): List<String>? =
    when {
        rewrite.addPrefix != null -> listOf("^(.*)$", "${rewrite.addPrefix}$1")
        // The bare prefix becomes "/" (an empty URI is not a request APISIX can send).
        rewrite.stripPrefix != null -> listOf("^${escapeRegex(rewrite.stripPrefix.trimEnd('/'))}(?:/(.*))?$", "/$1")
        rewrite.replace != null -> listOf("^.*$", rewrite.replace)
        else -> null
    }

private fun escapeRegex(text: String): String = text.replace(Regex("[^A-Za-z0-9/_-]")) { "\\" + it.value }

private fun forwardAuth(
    config: GatewayConfig,
    ctx: RenderContext,
    routeName: String,
    auth: String,
    headers: Map<String, String>,
): Map<String, Any> {
    val authorizer = config.authorizers[auth]
    // A container whose env secrets are not all set would see them unset, and one not yet restarted with its new env
    // runs with the old values; gantry-auth answers for either instead, with a 503 (that names an unset secret).
    val unresolved = authorizer?.env?.values?.any { v -> SECRET_REF.findAll(v).any { it.groupValues[1] in ctx.unsetSecrets } } == true
    val stale = authorizer?.container != null && authorizer.container in ctx.pendingContainers
    val custom = authorizer?.takeIf { it.type == "custom" && !(it.container != null && (unresolved || stale)) }
    val uri =
        when {
            custom?.container != null -> {
                val port = ctx.authorizerPorts[custom.container] ?: DEFAULT_AUTHORIZER_PORT
                "http://auth-${custom.container}:$port/"
            }
            custom?.url != null -> hostForContainer(custom.url)
            else -> "http://${ctx.authHost}:${ctx.authPort}/authorize?route=${URLEncoder.encode(
                routeName,
                Charsets.UTF_8,
            ).replace("+", "%20")}"
        }
    val request = FORWARD_AUTH_HEADERS.toMutableList()
    authorizer?.tokenFrom?.takeIf { it.startsWith("header:") }?.removePrefix("header:")?.let { name ->
        if (request.none { it.equals(name, ignoreCase = true) }) request += name
    }
    return linkedMapOf(
        "uri" to uri,
        "request_method" to "POST",
        "request_headers" to request,
        "upstream_headers" to upstreamHeaders(config, headers),
        "client_headers" to listOf("WWW-Authenticate"),
        "timeout" to 10000,
        // An authorizer that cannot be reached is an outage (503), not a denial.
        "status_on_error" to 503,
    )
}

/**
 * The headers forward-auth copies from the authorizer's answer onto the request: the identity headers (the strip
 * list) and every header the route names, its own or shared, in the route's spelling. gantry-auth answers with all of
 * them (a strip header no template sets is empty); a custom authorizer sets its own identity headers.
 */
private fun upstreamHeaders(
    config: GatewayConfig,
    headers: Map<String, String>,
): List<String> {
    fun named(h: String) = headers.keys.firstOrNull { it.equals(h, ignoreCase = true) }
    val strip = config.stripHeaders.map { named(it) ?: it }
    return (strip + headers.keys).distinctBy { it.lowercase() }
}

private fun corsPlugin(cors: Cors): Map<String, Any> {
    // APISIX rejects a bare "*" next to allow_credential; "**" allows anything and is accepted. Origins are never
    // widened that way: a credentialed "**" would let any website call the gateway as the logged-in user.
    fun list(values: List<String>): String? =
        values.takeIf { it.isNotEmpty() }?.joinToString(",") { if (cors.credentials && it == "*") "**" else it }

    val out = linkedMapOf<String, Any>()
    out["allow_origins"] = cors.origins.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "*"
    out["allow_credential"] = cors.credentials
    if (cors.credentials) out["allow_methods"] = "**"
    (list(cors.allowHeaders) ?: "**".takeIf { cors.credentials })?.let { out["allow_headers"] = it }
    (list(cors.exposeHeaders) ?: "**".takeIf { cors.credentials })?.let { out["expose_headers"] = it }
    out["max_age"] = cors.maxAge
    return out
}

private fun parseUpstream(url: String): URI {
    val uri = runCatching { URI(url) }.getOrNull()
    if (uri?.host == null ||
        uri.scheme !in setOf("http", "https")
    ) {
        throw GantryException("upstream '$url' must be an http(s) URL with a host")
    }
    if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") {
        throw GantryException("upstream '$url' must not have a path; use a route rewrite (addPrefix) to add one")
    }
    return uri
}

private fun hostForContainer(url: String): String {
    val uri = URI(url)
    val host = uri.host ?: return url
    val mapped = containerHost(host)
    return if (mapped == host) url else url.replaceFirst(host, mapped)
}

private fun apisixUpstream(url: String): Map<String, Any> {
    val uri = parseUpstream(url)
    val port =
        if (uri.port != -1) {
            uri.port
        } else if (uri.scheme == "https") {
            443
        } else {
            80
        }
    val host = containerHost(uri.host.removeSurrounding("[", "]"))
    return linkedMapOf(
        "type" to "roundrobin",
        "scheme" to uri.scheme,
        "pass_host" to "node",
        "nodes" to linkedMapOf("$host:$port" to 1),
    )
}

/** Writes maps, lists, numbers, booleans and strings as block YAML; strings are always double-quoted (JSON escaping). */
private fun emitYaml(root: Map<String, Any>): String = StringBuilder().also { emitMap(root, 0, it) }.toString()

private fun emitMap(
    map: Map<*, *>,
    indent: Int,
    out: StringBuilder,
) {
    val pad = " ".repeat(indent)
    for ((k, v) in map) {
        out.append(pad).append(scalar(k.toString())).append(':')
        emitValue(v, indent, out)
    }
}

private fun emitValue(
    v: Any?,
    indent: Int,
    out: StringBuilder,
) {
    when {
        v is Map<*, *> && v.isEmpty() -> out.append(" {}\n")
        v is Map<*, *> -> {
            out.append('\n')
            emitMap(v, indent + 2, out)
        }
        v is List<*> && v.isEmpty() -> out.append(" []\n")
        v is List<*> -> {
            out.append('\n')
            emitList(v, indent + 2, out)
        }
        else -> out.append(' ').append(scalar(v)).append('\n')
    }
}

private fun emitList(
    list: List<*>,
    indent: Int,
    out: StringBuilder,
) {
    val pad = " ".repeat(indent)
    for (item in list) {
        when {
            item is Map<*, *> && item.isNotEmpty() -> {
                val inner = StringBuilder().also { emitMap(item, indent + 2, it) }
                out.append(pad).append("- ").append(inner.substring(indent + 2))
            }
            item is List<*> && item.isNotEmpty() ->
                out
                    .append(
                        pad,
                    ).append("- [")
                    .append(item.joinToString(", ") { scalar(it) })
                    .append("]\n")
            else ->
                out
                    .append(pad)
                    .append("- ")
                    .append(scalar(item))
                    .append('\n')
        }
    }
}

private fun scalar(v: Any?): String =
    when (v) {
        is Int, is Boolean -> v.toString()
        is Map<*, *> -> "{}"
        is List<*> -> "[]"
        else -> quote(v.toString())
    }

private fun quote(s: String): String {
    val b = StringBuilder("\"")
    for (c in s) {
        when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' -> b.append("\\u%04x".format(c.code))
            else -> b.append(c)
        }
    }
    return b.append('"').toString()
}
