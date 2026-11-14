package dev.gantry.auth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.gantry.gateway.Authorizer
import dev.gantry.gateway.secretRefName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.ParseException
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/** The outcome of checking a token. */
sealed interface Verdict {
    /** The token is good; [claims] are plain values (dates are epoch seconds) ready for templating. */
    data class Valid(
        val claims: Map<String, Any?>,
    ) : Verdict

    /** The token is not acceptable: bad signature, expired, wrong issuer or audience, or missing. */
    data class Invalid(
        val reason: String,
    ) : Verdict

    /** The keys could not be fetched (or the authorizer is not usable), so nothing can be said about the token. */
    data class Unavailable(
        val reason: String,
    ) : Verdict
}

/** A fetched document and the `Cache-Control: max-age` it came with, if any. */
data class Fetched(
    val body: String,
    val maxAgeSeconds: Long? = null,
)

/** A small GET with timeouts; anything but a 2xx throws. */
fun httpGet(url: String): Fetched {
    val request =
        HttpRequest
            .newBuilder(URI.create(url))
            .timeout(OUTBOUND_REQUEST_TIMEOUT)
            .GET()
            .build()
    val response = sharedClient.send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() in 200..299) { "GET $url returned ${response.statusCode()}" }
    val cacheControl = response.headers().firstValue("Cache-Control").orElse("")
    val maxAge =
        MAX_AGE
            .find(cacheControl)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
    return Fetched(response.body(), maxAge)
}

private val sharedClient: HttpClient = HttpClient.newBuilder().connectTimeout(OUTBOUND_CONNECT_TIMEOUT).build()
private val MAX_AGE = Regex("max-age=(\\d+)", RegexOption.IGNORE_CASE)
private val BEARER = Regex("^Bearer\\s+", RegexOption.IGNORE_CASE)

private const val FIREBASE_JWKS = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
private val SKEW: Duration = Duration.ofMinutes(5)
private val REFETCH_GAP: Duration = Duration.ofSeconds(30)
private const val DEFAULT_TTL_SECONDS = 3600L
private const val MIN_TTL_SECONDS = 60L
private const val MAX_TTL_SECONDS = 24 * 3600L

/** What a token must satisfy beyond a valid signature and time window. */
private class Policy(
    val algorithms: Set<String>,
    val issuer: String?,
    val check: (JWTClaimsSet, Instant) -> String?,
)

private class Cached(
    val keys: JWKSet,
    val fetchedAt: Instant,
    val expiresAt: Instant,
)

private class Failure(
    val at: Instant,
    val reason: String,
)

/** Thrown for problems that make the authorizer unusable rather than the token bad. */
private class Unusable(
    message: String,
) : Exception(message)

/**
 * Verifies Firebase, Cognito, Supabase and generic JWTs. [fetch] retrieves JWKS documents and [clock] supplies
 * "now"; both are injectable so tests need no network. Safe for concurrent use.
 */
class TokenVerifier(
    private val fetch: (String) -> Fetched = ::httpGet,
    private val clock: () -> Instant = Instant::now,
) {
    private val cache = ConcurrentHashMap<String, Cached>()
    private val failures = ConcurrentHashMap<String, Failure>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun verify(
        authorizer: Authorizer,
        token: String?,
        secret: (String) -> String?,
    ): Verdict {
        val raw = token?.trim()?.replace(BEARER, "")?.trim()
        if (raw.isNullOrEmpty()) return Verdict.Invalid("missing token")
        return try {
            check(authorizer, raw, secret)
        } catch (e: Exception) {
            // Anything unexpected (including a failing secret lookup) must never read as a valid token.
            Verdict.Unavailable(e.message ?: "authorizer is not usable")
        }
    }

    private fun check(
        authorizer: Authorizer,
        raw: String,
        secret: (String) -> String?,
    ): Verdict {
        val jwt =
            try {
                SignedJWT.parse(raw)
            } catch (_: ParseException) {
                return Verdict.Invalid("malformed token")
            }
        val alg = jwt.header.algorithm
        val kid = jwt.header.keyID
        val policy = policyFor(authorizer)
        if (alg.name !in policy.algorithms) return Verdict.Invalid("algorithm ${alg.name} is not allowed")

        val verifier: JWSVerifier =
            if (alg.name.startsWith("HS")) {
                val key = hmacKey(authorizer, secret)
                try {
                    MACVerifier(key.toByteArray())
                } catch (e: JOSEException) {
                    throw Unusable("secret is not usable as an HMAC key: ${e.message}")
                }
            } else {
                val jwk = signingKey(authorizer, kid) ?: return Verdict.Invalid("unknown signing key")
                verifierFor(jwk) ?: return Verdict.Invalid("unsupported signing key type")
            }
        val signed =
            try {
                jwt.verify(verifier)
            } catch (_: JOSEException) {
                false
            }
        if (!signed) return Verdict.Invalid("invalid signature")

        val claims =
            try {
                jwt.jwtClaimsSet
            } catch (_: ParseException) {
                return Verdict.Invalid("malformed claims")
            }
        val now = clock()
        timeProblem(claims, now)?.let { return Verdict.Invalid(it) }
        if (policy.issuer != null && claims.issuer != policy.issuer) return Verdict.Invalid("wrong iss claim")
        policy.check(claims, now)?.let { return Verdict.Invalid(it) }
        return Verdict.Valid(plain(claims.claims))
    }

    private fun policyFor(a: Authorizer): Policy =
        when (a.type) {
            "firebase" -> {
                val project = a.projectId?.takeIf { it.isNotBlank() } ?: throw Unusable("firebase authorizer needs projectId")
                Policy(setOf("RS256"), "https://securetoken.google.com/$project") { claims, now ->
                    when {
                        project !in claims.audience -> "wrong aud claim"
                        claims.subject.isNullOrEmpty() -> "missing sub claim"
                        (claims.claims["auth_time"] as? Number)?.let { Instant.ofEpochSecond(it.toLong()) > now + SKEW } == true ->
                            "auth_time is in the future"
                        else -> null
                    }
                }
            }
            "cognito" -> {
                val region = a.region?.takeIf { it.isNotBlank() } ?: throw Unusable("cognito authorizer needs region")
                val pool = a.userPoolId?.takeIf { it.isNotBlank() } ?: throw Unusable("cognito authorizer needs userPoolId")
                if (a.clientIds.isEmpty()) throw Unusable("no allowed client ids configured")
                Policy(setOf("RS256"), cognitoIssuer(region, pool)) { claims, _ ->
                    val use = claims.claims["token_use"]
                    when {
                        use != a.tokenUse -> "wrong token_use claim"
                        use == "access" -> if (claims.claims["client_id"] in a.clientIds) null else "wrong client_id claim"
                        else -> if (claims.audience.any { it in a.clientIds }) null else "wrong aud claim"
                    }
                }
            }
            "supabase" -> {
                val base = supabaseIssuer(a)
                val algs = if (a.jwtSecret.isNullOrEmpty()) setOf("RS256", "ES256") else setOf("HS256")
                Policy(algs, base) { _, _ -> null }
            }
            "jwt" ->
                Policy(a.algorithms.toSet(), a.issuer) { claims, _ ->
                    if (a.audiences.isNotEmpty() && claims.audience.none { it in a.audiences }) "wrong aud claim" else null
                }
            else -> throw Unusable("authorizer type ${a.type} cannot be verified here")
        }

    private fun timeProblem(
        claims: JWTClaimsSet,
        now: Instant,
    ): String? {
        val exp = claims.expirationTime ?: return "token has no exp claim"
        if (exp.toInstant() + SKEW < now) return "token expired"
        val nbf = claims.notBeforeTime
        if (nbf != null && nbf.toInstant() - SKEW > now) return "token is not valid yet"
        return null
    }

    private fun cognitoIssuer(
        region: String,
        pool: String,
    ) = "https://cognito-idp.$region.amazonaws.com/$pool"

    private fun supabaseIssuer(a: Authorizer): String {
        val url = a.url?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: throw Unusable("supabase authorizer needs url")
        return "$url/auth/v1"
    }

    private fun hmacKey(
        a: Authorizer,
        secret: (String) -> String?,
    ): String {
        val configured = a.jwtSecret ?: throw Unusable("authorizer has no shared secret")
        if (!configured.contains("{{secret:")) return configured
        val name = secretRefName(configured) ?: throw Unusable("jwtSecret is not a valid {{secret:NAME}} reference")
        return secret(name)?.takeIf { it.isNotEmpty() } ?: throw Unusable("secret $name is not set")
    }

    private fun signingKey(
        a: Authorizer,
        kid: String?,
    ): JWK? {
        val inline = a.jwks
        if (a.type == "jwt" && inline != null) return pick(parseKeys(inline.toString(), "inline jwks"), kid)
        val url =
            when (a.type) {
                "firebase" -> FIREBASE_JWKS
                "cognito" -> "${cognitoIssuer(a.region.orEmpty(), a.userPoolId.orEmpty())}/.well-known/jwks.json"
                "supabase" -> "${supabaseIssuer(a)}/.well-known/jwks.json"
                else -> a.jwksUrl?.takeIf { it.isNotBlank() } ?: throw Unusable("jwt authorizer needs jwks or jwksUrl")
            }
        return keyFromUrl(url, kid)
    }

    private fun pick(
        set: JWKSet,
        kid: String?,
    ): JWK? = if (kid == null) set.keys.singleOrNull() else set.getKeyByKeyId(kid)

    /**
     * Looks [kid] up in the cached set for [url], refetching when the set is stale or the key is new. Fetches (successful
     * or not) happen at most once per 30s per URL; while a failure is recent the answer is Unavailable without a request.
     */
    private fun keyFromUrl(
        url: String,
        kid: String?,
    ): JWK? =
        synchronized(locks.computeIfAbsent(url) { Any() }) {
            val now = clock()
            val entry = cache[url]
            val stale = entry == null || now >= entry.expiresAt
            val unknownKey = entry != null && pick(entry.keys, kid) == null && now >= entry.fetchedAt + REFETCH_GAP
            if (!stale && !unknownKey) return@synchronized pick(entry!!.keys, kid)
            val failure = failures[url]
            if (failure != null && now < failure.at + REFETCH_GAP) throw Unusable(failure.reason)
            pick(load(url, now).keys, kid)
        }

    private fun load(
        url: String,
        now: Instant,
    ): Cached {
        val fetched: Fetched
        val keys: JWKSet
        try {
            fetched = fetch(url)
            keys = parseKeys(fetched.body, "signing keys from $url")
        } catch (e: Exception) {
            val reason = if (e is Unusable) e.message.orEmpty() else "could not fetch signing keys from $url: ${e.message}"
            failures[url] = Failure(now, reason)
            throw Unusable(reason)
        }
        failures.remove(url)
        val ttl = (fetched.maxAgeSeconds ?: DEFAULT_TTL_SECONDS).coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
        return Cached(keys, now, now.plusSeconds(ttl)).also { cache[url] = it }
    }

    private fun parseKeys(
        text: String,
        what: String,
    ): JWKSet =
        try {
            JWKSet.parse(text)
        } catch (e: ParseException) {
            throw Unusable("$what are not a valid JWKS: ${e.message}")
        }

    private fun verifierFor(jwk: JWK): JWSVerifier? =
        try {
            when (jwk) {
                is RSAKey -> RSASSAVerifier(jwk.toPublicJWK())
                is ECKey -> ECDSAVerifier(jwk.toPublicJWK())
                else -> null
            }
        } catch (_: JOSEException) {
            null
        }

    private fun plain(value: Map<String, Any?>): Map<String, Any?> = value.mapValues { (_, v) -> plainValue(v) }

    private fun plainValue(v: Any?): Any? =
        when (v) {
            is Date -> v.toInstant().epochSecond
            is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to plainValue(x) }
            is Iterable<*> -> v.map { plainValue(it) }
            else -> v
        }
}
