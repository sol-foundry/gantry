package dev.gantry.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.gantry.gateway.GatewayConfig
import dev.gantry.gateway.parseGatewayConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthEngineTest {
    private val now = Instant.parse("2026-11-04T12:00:00Z")
    private var clock = now
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("k1").generate()
    private val jwks = JWKSet(key.toPublicJWK()).toString()

    private data class Call(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?,
    )

    private val calls = mutableListOf<Call>()
    private val replies = ArrayDeque<HttpResponse>()
    private val logLines = mutableListOf<String>()

    private val http =
        HttpCaller { method, url, headers, body ->
            calls += Call(method, url, headers, body)
            replies.removeFirstOrNull() ?: error("no reply queued for $method $url")
        }

    private val firebaseYaml =
        """
        upstream: http://app:8080
        authorizers:
          fb:
            type: firebase
            projectId: demo
        """.trimIndent()

    private fun config(rest: String): GatewayConfig = parseGatewayConfig(firebaseYaml + "\n" + rest.trimIndent())

    private fun engine(
        config: GatewayConfig,
        secrets: Map<String, String> = emptyMap(),
        keysUp: Boolean = true,
    ) = AuthEngine(
        config = config,
        verifier =
            TokenVerifier(fetch = {
                if (!keysUp) error("connection refused to keys host 10.0.0.9")
                Fetched(jwks)
            }, clock = { clock }),
        secrets = { secrets[it] },
        app = "shop",
        http = http,
        log = { logLines += it },
        clock = { clock },
    )

    private fun token(configure: JWTClaimsSet.Builder.() -> Unit = {}): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer("https://securetoken.google.com/demo")
                .audience("demo")
                .subject("sub-1")
                .issueTime(Date.from(now.minusSeconds(10)))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("email", "ann@example.com")
                .claim("email_verified", true)
                .apply(configure)
                .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims)
            .apply { sign(RSASSASigner(key)) }
            .serialize()
    }

    private fun request(
        route: String,
        token: String? = token(),
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
    ) = AuthRequest(
        route = route,
        method = "GET",
        uri = "/portal/x",
        headers = (if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()) + headers,
        query = query,
    )

    private val portalWithHeaders =
        """
        routes:
          - name: portal
            match:
              path: /portal/**
            auth: fb
            headers:
              x-user-id: "{{uid}}"
              x-gateway-user-email: "{{email}}"
              x-gateway-auth-strategy: firebase
              x-verified: "{{email_verified}}"
        """

    private fun userStep(
        onFailure: String = "deny",
        create: Boolean = false,
        routeHeaders: String = "x-user-id: \"{{userId}}\"",
    ) = """
        steps:
          user:
            type: http-lookup
            lookup:
              url: "{{upstream}}/internal/users/by-email/{{email}}"
              headers:
                x-internal-api-key: "{{secret:INTERNAL_API_KEY}}"
${if (create) createBlock else ""}
            map:
              userId: "${'$'}.id"
              tier: "${'$'}.profile.tier"
            cacheSeconds: 60
            onFailure: "$onFailure"
        routes:
          - name: portal
            match:
              path: /portal/**
            auth: fb
            steps: [user]
            headers:
              $routeHeaders
              x-error-code: "{{errorCode}}"
        """

    private val createBlock =
        """            create:
              method: POST
              url: "{{upstream}}/internal/users"
              body:
                uid: "{{uid}}"
                email: "{{email}}""""

    private val secrets = mapOf("INTERNAL_API_KEY" to "k-123")

    private fun body(result: AuthResult): JsonObject = Json.parseToJsonElement(result.body!!).jsonObject

    @Test
    fun `firebase allow sets templated headers`() {
        val result = engine(config(portalWithHeaders)).decide(request("portal", token { claim("user_id", "u-9") }))
        assertEquals(200, result.status)
        assertEquals("u-9", result.headers["x-user-id"])
        assertEquals("ann@example.com", result.headers["x-gateway-user-email"])
        assertEquals("firebase", result.headers["x-gateway-auth-strategy"])
        assertEquals("true", result.headers["x-verified"])
        assertEquals("sub-1", result.context["sub"])
        assertEquals("u-9", result.context["uid"])
    }

    @Test
    fun `uid falls back to sub and numbers and booleans become strings`() {
        val result =
            engine(config(portalWithHeaders)).decide(
                request("portal", token { claim("level", 3L).claim("beta", false).claim("nested", mapOf("a" to "b")) }),
            )
        assertEquals("sub-1", result.headers["x-user-id"])
        assertEquals("3", result.context["level"])
        assertEquals("false", result.context["beta"])
        assertFalse("nested" in result.context)
    }

    @Test
    fun `every strip header is returned, empty when unknown`() {
        val result = engine(config(portalWithHeaders)).decide(request("portal"))
        assertEquals(200, result.status)
        val expected =
            setOf(
                "x-user-id",
                "x-gateway-user-email",
                "x-gateway-auth-strategy",
                "x-internal-api-key",
                "x-error-code",
                "x-verified",
            )
        assertEquals(expected, result.headers.keys)
        assertEquals("", result.headers["x-internal-api-key"])
        assertEquals("", result.headers["x-error-code"])
    }

    @Test
    fun `unverified email continues with error code and skips steps`() {
        val yaml =
            firebaseYaml.replace("projectId: demo", "projectId: demo\n    onUnverifiedEmail: continue:email_not_verified") +
                "\n" + userStep().trimIndent()
        val result =
            engine(parseGatewayConfig(yaml), secrets).decide(request("portal", token { claim("email_verified", false) }))
        assertEquals(200, result.status)
        assertEquals("email_not_verified", result.headers["x-error-code"])
        assertEquals("email_not_verified", result.context["errorCode"])
        assertEquals("", result.headers["x-user-id"])
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `unverified email is denied by default with the deny status`() {
        val yaml = firebaseYaml.replace("projectId: demo", "projectId: demo\n    denyStatus: 401") + "\n" + portalWithHeaders.trimIndent()
        val result = engine(parseGatewayConfig(yaml)).decide(request("portal", token { claim("email_verified", "false") }))
        assertEquals(401, result.status)
        assertEquals("unauthorized", body(result)["error"]!!.jsonPrimitive.content)
        assertTrue(result.headers.isEmpty())
    }

    @Test
    fun `user lookup then create on 404, cached`() {
        replies += HttpResponse(404, "")
        replies += HttpResponse(201, """{"id": 42, "profile": {"tier": "gold"}}""")
        val e = engine(config(userStep(create = true)), secrets)
        val first = e.decide(request("portal"))
        assertEquals(200, first.status)
        assertEquals("42", first.headers["x-user-id"])
        assertEquals("gold", first.context["tier"])
        assertEquals(2, calls.size)
        assertEquals("GET", calls[0].method)
        assertEquals("http://app:8080/internal/users/by-email/ann%40example.com", calls[0].url)
        assertEquals("k-123", calls[0].headers["x-internal-api-key"])
        assertEquals("POST", calls[1].method)
        assertEquals("http://app:8080/internal/users", calls[1].url)
        assertEquals("application/json", calls[1].headers["Content-Type"])
        val sent = Json.parseToJsonElement(calls[1].body!!).jsonObject
        assertEquals("sub-1", sent["uid"]!!.jsonPrimitive.content)
        assertEquals("ann@example.com", sent["email"]!!.jsonPrimitive.content)

        val second = e.decide(request("portal"))
        assertEquals("42", second.headers["x-user-id"])
        assertEquals(2, calls.size)

        clock = now.plusSeconds(61)
        replies += HttpResponse(200, """{"id": 43, "profile": {"tier": "gold"}}""")
        assertEquals("43", e.decide(request("portal")).headers["x-user-id"])
        assertEquals(3, calls.size)
    }

    @Test
    fun `lookup failure continues with error code when configured`() {
        replies += HttpResponse(500, "boom")
        val result = engine(config(userStep(onFailure = "continue:user_lookup_failed")), secrets).decide(request("portal"))
        assertEquals(200, result.status)
        assertEquals("user_lookup_failed", result.headers["x-error-code"])
        assertEquals("", result.headers["x-user-id"])
    }

    @Test
    fun `lookup failure denies by default`() {
        replies += HttpResponse(200, """{"profile": {"tier": "gold"}}""")
        val result = engine(config(userStep()), secrets).decide(request("portal"))
        assertEquals(403, result.status)
        assertEquals("""{"error":"forbidden"}""", result.body)
    }

    @Test
    fun `unparseable body and thrown errors are step failures`() {
        replies += HttpResponse(200, "not json")
        val e = engine(config(userStep(onFailure = "continue:bad")), secrets)
        assertEquals("bad", e.decide(request("portal")).headers["x-error-code"])
        // No reply queued: the fake throws, like a timeout.
        assertEquals("bad", e.decide(request("portal")).headers["x-error-code"])
    }

    @Test
    fun `cache does not keep failures`() {
        replies += HttpResponse(500, "")
        val e = engine(config(userStep(onFailure = "continue:failed")), secrets)
        assertEquals("failed", e.decide(request("portal")).headers["x-error-code"])
        replies += HttpResponse(200, """{"id": 7, "profile": {"tier": "free"}}""")
        val ok = e.decide(request("portal"))
        assertEquals("7", ok.headers["x-user-id"])
        assertEquals("", ok.headers["x-error-code"])
        e.decide(request("portal"))
        assertEquals(2, calls.size)
    }

    @Test
    fun `lookup url percent-encodes context values but not the template`() {
        replies += HttpResponse(200, """{"id": 1, "profile": {"tier": "x"}}""")
        engine(config(userStep()), secrets).decide(request("portal", token { claim("email", "a+b c@x.com") }))
        assertEquals("http://app:8080/internal/users/by-email/a%2Bb%20c%40x.com", calls.single().url)
    }

    @Test
    fun `CR and LF are stripped from forwarded and step headers`() {
        val yaml =
            userStep(routeHeaders = "x-gateway-user-email: \"{{email}}\"")
                .replace("x-internal-api-key: \"{{secret:INTERNAL_API_KEY}}\"", "x-who: \"{{email}}\"")
        replies += HttpResponse(200, """{"id": 1, "profile": {"tier": "x"}}""")
        val result =
            engine(config(yaml)).decide(request("portal", token { claim("email", "a@x.com\r\nX-Evil: 1") }))
        assertEquals("a@x.comX-Evil: 1", result.headers["x-gateway-user-email"])
        assertEquals("a@x.comX-Evil: 1", calls.single().headers["x-who"])
    }

    @Test
    fun `missing secret fails only its route`() {
        val yaml =
            portalWithHeaders.trimIndent() + "\n" +
                """
                - name: admin
                  match:
                    path: /admin/**
                  auth: fb
                  headers:
                    x-internal-api-key: "{{secret:ADMIN_KEY}}"
                """.trimIndent().prependIndent("  ")
        val e = engine(config(yaml))
        val admin = e.decide(request("admin"))
        assertEquals(503, admin.status)
        assertEquals("""{"error":"secret ADMIN_KEY is not set; set it with gantry secret set shop ADMIN_KEY"}""", admin.body)
        assertEquals(200, e.decide(request("portal")).status)
    }

    @Test
    fun `missing secret in a step fails the route`() {
        val result = engine(config(userStep())).decide(request("portal"))
        assertEquals(503, result.status)
        assertTrue("INTERNAL_API_KEY" in result.body!!)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `unavailable keys give 503 without exception text`() {
        val result = engine(config(portalWithHeaders), keysUp = false).decide(request("portal"))
        assertEquals(503, result.status)
        assertEquals("""{"error":"authorizer fb is unavailable"}""", result.body)
        assertFalse("10.0.0.9" in result.body!!)
        assertTrue("10.0.0.9" in result.reason!!)
    }

    @Test
    fun `deny route and unknown route give 403`() {
        val yaml =
            """
            routes:
              - name: closed
                match:
                  path: /closed/**
                auth: deny
            """
        val e = engine(config(yaml))
        assertEquals(403, e.decide(request("closed")).status)
        assertEquals(403, e.decide(request("nope")).status)
        assertEquals("""{"error":"forbidden"}""", e.decide(request("nope")).body)
    }

    @Test
    fun `custom authorizer route is unavailable here`() {
        val yaml =
            """
            routes:
              - name: c
                match:
                  path: /c/**
                auth: mine
            """
        val cfg =
            config(
                yaml,
            ).let { it.copy(authorizers = it.authorizers + ("mine" to it.authorizers.getValue("fb").copy(type = "custom"))) }
        val result = engine(cfg).decide(request("c"))
        assertEquals(503, result.status)
        assertEquals("""{"error":"authorizer mine is unavailable"}""", result.body)
    }

    @Test
    fun `default action is the route named default`() {
        val yaml =
            """
            default:
              auth: fb
              headers:
                x-user-id: "{{uid}}"
            """
        val result = engine(config(yaml)).decide(request("default"))
        assertEquals(200, result.status)
        assertEquals("sub-1", result.headers["x-user-id"])
    }

    @Test
    fun `missing token uses missingTokenStatus and a bad token the deny status with its reason`() {
        val yaml =
            firebaseYaml.replace("projectId: demo", "projectId: demo\n    missingTokenStatus: 401") + "\n" + portalWithHeaders.trimIndent()
        val e = engine(parseGatewayConfig(yaml))
        val missing = e.decide(request("portal", token = null))
        assertEquals(401, missing.status)
        assertEquals("""{"error":"unauthorized","reason":"missing token"}""", missing.body)
        val expired = e.decide(request("portal", token { expirationTime(Date.from(now.minusSeconds(3600))) }))
        assertEquals(403, expired.status)
        assertEquals("""{"error":"forbidden","reason":"token expired"}""", expired.body)
    }

    @Test
    fun `token is read from a query parameter or a header in any case`() {
        val yaml =
            firebaseYaml.replace("projectId: demo", "projectId: demo\n    tokenFrom: query:token") + "\n" + portalWithHeaders.trimIndent()
        val fromQuery = engine(parseGatewayConfig(yaml)).decide(request("portal", token = null, query = mapOf("token" to token())))
        assertEquals(200, fromQuery.status)
        val lower = engine(config(portalWithHeaders)).decide(request("portal", token = null, headers = mapOf("authorization" to token())))
        assertEquals(200, lower.status)
    }

    @Test
    fun `decision is logged without secrets`() {
        replies += HttpResponse(200, """{"id": "k-123", "profile": {"tier": "gold"}}""")
        val t = token()
        engine(config(userStep()), secrets).decide(
            request("portal", t, headers = mapOf("x-request-id" to "req-1", "Cookie" to "sid=s3cr3t")),
        )
        val line = logLines.single()
        assertFalse('\n' in line)
        val json = Json.parseToJsonElement(line).jsonObject
        assertEquals("req-1", json["requestId"]!!.jsonPrimitive.content)
        assertEquals("portal", json["route"]!!.jsonPrimitive.content)
        assertEquals("allow", json["decision"]!!.jsonPrimitive.content)
        assertEquals("200", json["status"]!!.jsonPrimitive.content)
        assertTrue(json.containsKey("time"))
        assertTrue(json.containsKey("reason"))
        val context = json["context"]!!.jsonObject
        assertEquals("gold", context["tier"]!!.jsonPrimitive.content)
        assertFalse("userId" in context)
        assertFalse("k-123" in line)
        assertFalse(t in line)
        assertFalse("s3cr3t" in line)
    }

    @Test
    fun `denials are logged with the internal reason`() {
        engine(config(portalWithHeaders), keysUp = false).decide(request("portal"))
        val json = Json.parseToJsonElement(logLines.single()).jsonObject
        assertEquals("deny", json["decision"]!!.jsonPrimitive.content)
        assertEquals("503", json["status"]!!.jsonPrimitive.content)
        assertTrue("10.0.0.9" in json["reason"]!!.jsonPrimitive.content)
    }

    private fun oneStep(
        lookup: String,
        map: String = "userId: \"${'$'}.id\"",
        onFailure: String = "deny",
        routeHeaders: String = "x-user-id: \"{{userId}}\"",
    ) = "steps:\n  user:\n    type: http-lookup\n    lookup:\n" + lookup.trimIndent().prependIndent("      ") +
        "\n    map:\n      $map\n    onFailure: \"$onFailure\"\n" +
        "routes:\n  - name: portal\n    match:\n      path: /portal/**\n    auth: fb\n    steps: [user]\n    headers:\n" +
        "      $routeHeaders\n      x-error-code: \"{{errorCode}}\"\n"

    @Test
    fun `secrets in a step url are encoded and never reach the reason or log`() {
        val secret = "a b+c"
        val cfg = config(oneStep("url: \"{{upstream}}/u?key={{secret:K}}\"", onFailure = "continue:failed"))
        replies += HttpResponse(200, """{"id": 1}""")
        val e = engine(cfg, mapOf("K" to secret))
        assertEquals(200, e.decide(request("portal")).status)
        assertEquals("http://app:8080/u?key=a%20b%2Bc", calls.single().url)

        val leaky =
            AuthEngine(
                config = cfg,
                verifier = TokenVerifier(fetch = { Fetched(jwks) }, clock = { clock }),
                secrets = { secret },
                app = "shop",
                http = { _, url, _, _ -> throw IllegalArgumentException("Illegal character in $url ($secret)") },
                log = { logLines += it },
                clock = { clock },
            )
        logLines.clear()
        val failed = leaky.decide(request("portal"))
        assertEquals("failed", failed.headers["x-error-code"])
        assertFalse(secret in failed.reason!!, failed.reason)
        assertFalse("a%20b%2Bc" in failed.reason!!, failed.reason)
        assertTrue("IllegalArgumentException" in failed.reason!!, failed.reason)
        assertFalse(secret in logLines.single())
        assertFalse("a%20b%2Bc" in logLines.single())
    }

    @Test
    fun `cache key covers the rendered body so users do not share results`() {
        val cfg =
            config(
                oneStep(
                    """
                    method: POST
                    url: "{{upstream}}/internal/users/resolve"
                    body:
                      uid: "{{uid}}"
                    """,
                ),
            )
        val e = engine(cfg)
        replies += HttpResponse(200, """{"id": "A"}""")
        assertEquals("A", e.decide(request("portal", token { subject("user-a") })).headers["x-user-id"])
        replies += HttpResponse(200, """{"id": "B"}""")
        assertEquals("B", e.decide(request("portal", token { subject("user-b") })).headers["x-user-id"])
        assertEquals(2, calls.size)
        assertEquals("A", e.decide(request("portal", token { subject("user-a") })).headers["x-user-id"])
        assertEquals(2, calls.size)
    }

    /** An engine whose secret store fails outright. */
    private fun brokenSecrets(cfg: GatewayConfig) =
        AuthEngine(
            config = cfg,
            verifier = TokenVerifier(fetch = { Fetched(jwks) }, clock = { clock }),
            secrets = { error("vault down at 10.1.1.1") },
            app = "shop",
            http = http,
            log = { logLines += it },
            clock = { clock },
        )

    @Test
    fun `decide never throws and answers 503`() {
        val yaml = portalWithHeaders.trimIndent().replace("x-gateway-auth-strategy: firebase", "x-gateway-auth-strategy: \"{{secret:S}}\"")
        val result = brokenSecrets(config(yaml)).decide(request("portal"))
        assertEquals(503, result.status)
        assertEquals("""{"error":"authorizer fb is unavailable"}""", result.body)
        val json = Json.parseToJsonElement(logLines.single()).jsonObject
        assertEquals("IllegalStateException", json["reason"]!!.jsonPrimitive.content)

        val publicYaml =
            """
            routes:
              - name: open
                match:
                  path: /o/**
                auth: public
                headers:
                  x-k: "{{secret:S}}"
            """
        val open = brokenSecrets(config(publicYaml)).decide(request("open"))
        assertEquals(503, open.status)
        assertEquals("""{"error":"gateway auth is unavailable"}""", open.body)
    }

    @Test
    fun `a context value of only dots is encoded in a step url`() {
        replies += HttpResponse(200, """{"id": 1}""")
        replies += HttpResponse(200, """{"id": 1}""")
        val e = engine(config(oneStep("url: \"{{upstream}}/internal/users/by-email/{{email}}\"")))
        e.decide(request("portal", token { claim("email", "..") }))
        e.decide(request("portal", token { claim("email", ".") }))
        assertEquals("http://app:8080/internal/users/by-email/%2E%2E", calls[0].url)
        assertEquals("http://app:8080/internal/users/by-email/%2E", calls[1].url)
    }

    @Test
    fun `a soft-failed step clears its mapped keys so a claim cannot stand in`() {
        replies += HttpResponse(500, "")
        val cfg =
            config(
                oneStep(
                    "url: \"{{upstream}}/roles/{{uid}}\"",
                    map = "role: \"${'$'}.role\"",
                    onFailure = "continue:role_failed",
                    routeHeaders = "x-role: \"{{role}}\"",
                ),
            )
        val result = engine(cfg).decide(request("portal", token { claim("role", "admin") }))
        assertEquals(200, result.status)
        assertEquals("", result.headers["x-role"])
        assertEquals("role_failed", result.headers["x-error-code"])
        assertFalse("role" in result.context)
    }

    @Test
    fun `a custom authorizer whose env secret is not set answers 503 naming the secret`() {
        // The renderer sends such a route here instead of to the container, which would see the variable unset.
        val cfg =
            parseGatewayConfig(
                """
                authorizers:
                  mine:
                    type: custom
                    container: checker
                    env:
                      API_KEY: "{{secret:API_KEY}}"
                routes:
                  - name: c
                    match:
                      path: /c/**
                    auth: mine
                """.trimIndent(),
            )
        val missing = engine(cfg).decide(request("c"))
        assertEquals(503, missing.status)
        assertEquals("""{"error":"secret API_KEY is not set; set it with gantry secret set shop API_KEY"}""", missing.body)
        // Once it is set, the route reaches gantry-auth only while the container has not restarted with it (or its
        // restart failed): the 503 says so and how to retry.
        val set = engine(cfg, mapOf("API_KEY" to "k")).decide(request("c"))
        assertEquals(503, set.status)
        assertEquals(
            """{"error":"authorizer mine is unavailable: auth-checker has not restarted with its current env; """ +
                """run gantry gateway reload shop"}""",
            set.body,
        )
    }

    @Test
    fun `a step that cannot be reached answers 503 under deny, and a bad answer 403`() {
        // No reply queued: the fake throws, like a refused connection or a timeout.
        val unreachable = engine(config(userStep()), secrets).decide(request("portal"))
        assertEquals(503, unreachable.status)
        assertEquals("""{"error":"step user is unavailable"}""", unreachable.body)

        replies += HttpResponse(500, "boom")
        val failed = engine(config(userStep()), secrets).decide(request("portal"))
        assertEquals(403, failed.status)
        assertEquals("""{"error":"forbidden"}""", failed.body)
    }

    @Test
    fun `outbound calls finish inside the forward-auth budget`() {
        // APISIX gives forward-auth 10 s; gantry-auth must answer (503) before that, not time out.
        assertEquals(java.time.Duration.ofSeconds(3), OUTBOUND_CONNECT_TIMEOUT)
        assertEquals(java.time.Duration.ofSeconds(7), OUTBOUND_REQUEST_TIMEOUT)
        assertTrue(OUTBOUND_REQUEST_TIMEOUT.toMillis() < 10_000)
    }

    @Test
    fun `shared headers are returned on every protected route and the default, a route's own winning`() {
        val yaml =
            """
            headers:
              x-gateway-auth-strategy: firebase
              X-User-Id: "{{uid}}"
              x-key: "{{secret:SHARED_KEY}}"
            routes:
              - name: portal
                match:
                  path: /portal/**
                auth: fb
                headers:
                  x-user-id: "u-{{email}}"
            default:
              auth: fb
            """
        val e = engine(config(yaml), secrets = mapOf("SHARED_KEY" to "k-1"))
        val portal = e.decide(request("portal"))
        assertEquals(200, portal.status)
        assertEquals("firebase", portal.headers["x-gateway-auth-strategy"])
        assertEquals("u-ann@example.com", portal.headers["x-user-id"])
        assertFalse("X-User-Id" in portal.headers)
        assertEquals("k-1", portal.headers["x-key"])
        val default = e.decide(request("default"))
        assertEquals("sub-1", default.headers["X-User-Id"])
        assertEquals("firebase", default.headers["x-gateway-auth-strategy"])
        // A shared header from an unset secret fails every route that uses it.
        assertEquals(503, engine(config(yaml)).decide(request("portal")).status)
    }
}
