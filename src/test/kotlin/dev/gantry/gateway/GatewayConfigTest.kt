package dev.gantry.gateway

import dev.gantry.engine.GantryException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayConfigTest {
    private val citadel =
        """
        upstream: http://host.docker.internal:8080
        cors:
          origins: ["http://localhost:3000"]
        authorizers:
          fb:
            type: firebase
            projectId: demo-project
            onUnverifiedEmail: continue:email_not_verified
        steps:
          user:
            type: http-lookup
            lookup:
              url: "{{upstream}}/internal/users/by-uid/{{uid}}"
              headers:
                x-internal-api-key: "{{secret:INTERNAL_API_KEY}}"
            create:
              method: POST
              url: "{{upstream}}/internal/users"
              body:
                email: "{{email}}"
            map:
              userId: "$.id"
            onFailure: continue:user_lookup_failed
        routes:
          - name: portal
            match:
              path: /portal/**
            auth: fb
            steps: [user]
            rewrite:
              addPrefix: /portal
          - name: health
            match:
              path: /actuator/**
              methods: [GET]
        default:
          auth: public
        """.trimIndent()

    @Test
    fun `parses a minimal config`() {
        val config = parseGatewayConfig("")
        assertNull(config.upstream)
        assertEquals(emptyList(), config.routes)
        assertEquals("public", config.default.auth)
        assertTrue(config.stripHeaders.contains("x-user-id"))
        assertEquals(emptyList(), config.validate())
        assertEquals(config, parseGatewayConfig("# only a comment\n"))
    }

    @Test
    fun `parses the citadel-style example`() {
        val config = parseGatewayConfig(citadel)
        assertEquals("http://host.docker.internal:8080", config.upstream)
        assertEquals(listOf("http://localhost:3000"), config.cors?.origins)
        val fb = config.authorizers.getValue("fb")
        assertEquals("firebase", fb.type)
        assertEquals("demo-project", fb.projectId)
        assertEquals("continue:email_not_verified", fb.onUnverifiedEmail)
        val user = config.steps.getValue("user")
        assertEquals("GET", user.lookup.method)
        assertEquals("POST", user.create?.method)
        assertEquals("$.id", user.map["userId"])
        assertEquals(300, user.cacheSeconds)
        assertEquals("/portal", config.routes[0].rewrite?.addPrefix)
        assertEquals(listOf("GET"), config.routes[1].methods())
        assertEquals(emptyList(), config.validate())
    }

    private fun Route.methods() = match.methods

    @Test
    fun `validate reports unknown references, missing authorizer fields, bad onFailure syntax and duplicate route names`() {
        val config =
            parseGatewayConfig(
                """
                authorizers:
                  fb:
                    type: firebase
                  mine:
                    type: custom
                  odd:
                    type: nope
                  soft:
                    type: jwt
                    jwksUrl: https://example.com/jwks
                    onUnverifiedEmail: continue
                steps:
                  s:
                    type: http-lookup
                    onFailure: "continue:"
                    lookup:
                      url: http://x
                routes:
                  - name: portal
                    match: {path: /a/**}
                    auth: missing
                    steps: [ghost]
                  - name: portal
                    match: {path: /b/**}
                default:
                  auth: other
                """.trimIndent(),
            )
        val problems = config.validate()
        assertContains(problems, "route 'portal': unknown authorizer 'missing'")
        assertContains(problems, "route 'portal': unknown step 'ghost'")
        assertContains(problems, "route 'portal' is defined more than once")
        assertContains(problems, "authorizer 'fb': firebase needs projectId")
        assertContains(problems, "authorizer 'mine': custom needs container or url")
        assertContains(problems, "authorizer 'odd': unknown type 'nope' (use firebase, cognito, supabase, jwt, custom)")
        assertContains(problems, "authorizer 'soft': onUnverifiedEmail must be 'deny' or 'continue:<code>'")
        assertContains(problems, "step 's': onFailure must be 'deny' or 'continue:<code>'")
        assertContains(problems, "default: unknown authorizer 'other'")
    }

    @Test
    fun `validate accepts public and deny and checks the other authorizer fields`() {
        val config =
            parseGatewayConfig(
                """
                authorizers:
                  c:
                    type: cognito
                  s:
                    type: supabase
                  j:
                    type: jwt
                routes:
                  - name: a
                    match: {path: /a}
                    auth: deny
                  - name: b
                    match: {path: /b}
                    auth: public
                """.trimIndent(),
            )
        val problems = config.validate()
        assertContains(problems, "authorizer 'c': cognito needs region and userPoolId")
        assertContains(problems, "authorizer 's': supabase needs url")
        assertContains(problems, "authorizer 'j': jwt needs jwksUrl or jwks")
        assertFalse(problems.any { it.startsWith("route") })
    }

    @Test
    fun `validate reserves the route name default`() {
        val config =
            parseGatewayConfig(
                """
                routes:
                  - name: default
                    match: {path: /a}
                """.trimIndent(),
            )
        assertContains(config.validate(), "route name 'default' is reserved")
    }

    @Test
    fun `validate reserves the route name websocket and checks the websocket block`() {
        val config =
            parseGatewayConfig(
                """
                routes:
                  - name: websocket
                    match: {path: /a}
                websocket:
                  auth: nobody
                  steps: [missing]
                  connect: "{{upstream}}/connect/{{connectionId}}/{{email}}"
                  disconnect: "ftp://x/{{connectionId}}"
                  routeSelection: action
                  routes:
                    send: "{{upstream}}/send/{{token}}"
                """.trimIndent(),
            )
        val problems = config.validate()

        assertContains(problems, "route name 'websocket' is reserved")
        assertContains(problems, "websocket: unknown authorizer 'nobody'")
        assertContains(problems, "websocket: unknown step 'missing'")
        assertContains(problems, "websocket.connect: only {{upstream}} and {{connectionId}} may be used, not {{email}}")
        assertContains(problems, "websocket.disconnect: must be an http(s) URL")
        assertContains(problems, "websocket.routeSelection: must be \$request.body.<field>")
        assertContains(problems, "websocket.routes.send: only {{upstream}} and {{connectionId}} may be used, not {{token}}")
    }

    @Test
    fun `a valid websocket block reads back and leaves the file short without one`() {
        val yml =
            """
            websocket:
              connect: "{{upstream}}/connect/{{connectionId}}"
            """.trimIndent()
        val config = parseGatewayConfig(yml)

        assertEquals(emptyList(), config.validate())
        assertEquals("{{upstream}}/connect/{{connectionId}}", config.websocket.connect)
        assertEquals("", encodeGatewayConfig(GatewayConfig()))
    }

    @Test
    fun `secretNames collects every reference`() {
        val config = parseGatewayConfig(citadel)
        assertEquals(setOf("INTERNAL_API_KEY"), config.secretNames())
        val more =
            parseGatewayConfig(
                """
                authorizers:
                  s:
                    type: supabase
                    url: https://x.supabase.co
                    jwtSecret: "{{secret:SUPABASE_JWT}}"
                  c:
                    type: custom
                    container: auth
                    env:
                      TOKEN: "{{secret:CUSTOM_TOKEN}}"
                routes:
                  - name: r
                    match: {path: /r}
                    headers:
                      x-k: "{{secret:ROUTE_KEY}}"
                default:
                  headers:
                    x-d: "a{{secret:DEFAULT_KEY}}b{{name}}"
                """.trimIndent(),
            )
        assertEquals(setOf("SUPABASE_JWT", "CUSTOM_TOKEN", "ROUTE_KEY", "DEFAULT_KEY"), more.secretNames())
    }

    @Test
    fun `secretNames finds references in any field`() {
        val config =
            parseGatewayConfig(
                """
                cors:
                  origins: ["{{secret:CORS_ORIGIN}}"]
                authorizers:
                  j:
                    type: jwt
                    jwks:
                      keys:
                        - kid: "{{secret:INLINE_KID}}"
                    audiences: ["{{secret:AUDIENCE}}"]
                  c:
                    type: cognito
                    region: eu-west-1
                    userPoolId: pool
                    clientIds: ["{{secret:CLIENT_ID}}"]
                routes:
                  - name: r
                    match:
                      path: /x/**
                    rewrite:
                      addPrefix: "/{{secret:PREFIX}}"
                """.trimIndent(),
            )
        assertEquals(setOf("CORS_ORIGIN", "INLINE_KID", "AUDIENCE", "CLIENT_ID", "PREFIX"), config.secretNames())
    }

    @Test
    fun `cors credentials are off by default and need explicit origins`() {
        assertFalse(Cors().credentials)
        assertEquals(emptyList(), GatewayConfig(cors = Cors()).validate())
        assertEquals(emptyList(), GatewayConfig(cors = Cors(origins = listOf("http://localhost:3000"), credentials = true)).validate())
        val problems = parseGatewayConfig("cors:\n  credentials: true\n").validate()
        assertEquals(listOf("cors: credentials need explicit origins, not *"), problems)
        assertEquals(
            listOf("cors: credentials need explicit origins, not *"),
            GatewayConfig(cors = Cors(origins = listOf("http://a", "*"), credentials = true)).validate(),
        )
        assertEquals(1, GatewayConfig(cors = Cors(origins = emptyList(), credentials = true)).validate().size)
    }

    @Test
    fun `glob matching`() {
        val one = globToRegex("/portal/*/x")
        assertTrue(one.matches("/portal/a/x"))
        assertFalse(one.matches("/portal/a/b/x"))
        assertFalse(one.matches("/portal//x"))
        val any = globToRegex("/actuator/**")
        assertTrue(any.matches("/actuator/health/db"))
        assertTrue(any.matches("/actuator"))
        assertTrue(any.matches("/actuator/"))
        assertFalse(any.matches("/actuatorx"))
        assertFalse(any.matches("/other/actuator/health"))
        assertTrue(globToRegex("/**").matches("/"))
        assertTrue(globToRegex("/**").matches("/a/b"))
        assertTrue(globToRegex("/a.b+c").matches("/a.b+c"))
        assertFalse(globToRegex("/a.b").matches("/aXb"))
        assertFalse(any.matches("/actuator/../admin"))
        assertFalse(globToRegex("/**").matches("/a/../b"))
        assertFalse(globToRegex("/**").matches("/.."))
        assertTrue(globToRegex("/**").matches("/a/..b"))
    }

    @Test
    fun `bad yaml names the line`() {
        val e = assertFailsWith<GantryException> { parseGatewayConfig("upstream: x\nroutes: [\n  - name: a\n  bad: : :\n") }
        assertContains(e.message!!, "gateway.yml")
        assertContains(e.message!!, "line")
    }

    @Test
    fun `unknown keys are rejected with their line`() {
        val e = assertFailsWith<GantryException> { parseGatewayConfig("upstream: http://x\nbogus: 1\n") }
        assertContains(e.message!!, "line 2")
    }

    @Test
    fun `the default file parses and validates clean`() {
        val config = parseGatewayConfig(DEFAULT_GATEWAY_YML)
        assertEquals(emptyList(), config.validate())
        assertEquals("public", config.default.auth)
        assertEquals(emptyList(), config.routes)
        assertContains(DEFAULT_GATEWAY_YML, "# authorizers:")
        assertContains(DEFAULT_GATEWAY_YML, "http-lookup")
    }

    @Test
    fun `the commented example in the default file is valid once uncommented`() {
        val body =
            DEFAULT_GATEWAY_YML
                .lines()
                .dropWhile { !it.startsWith("# authorizers:") }
                .joinToString("\n") { it.removePrefix("# ").removePrefix("#") }
        assertEquals(emptyList(), parseGatewayConfig(body).validate())
    }

    @Test
    fun `validate requires client ids for a cognito authorizer`() {
        val config =
            parseGatewayConfig(
                """
                authorizers:
                  c:
                    type: cognito
                    region: eu-west-1
                    userPoolId: pool
                """.trimIndent(),
            )
        assertContains(config.validate(), "authorizer 'c': cognito needs clientIds")
    }

    @Test
    fun `validate rejects values APISIX would refuse, so a route is never silently skipped`() {
        val config =
            parseGatewayConfig(
                """
                cors:
                  origins: ["http://localhost:3000", "localhost:5170", "http://a.test/path", "*"]
                routes:
                  - name: "has space"
                    match: {path: /a/**}
                  - name: "${"x".repeat(65)}"
                    match: {path: /b/**}
                  - name: ok.name_1-2
                    match: {path: /c/**, methods: [get, Post, FETCH]}
                  - name: hosts
                    match: {path: /d/**, host: "api.*.test"}
                  - name: nopath
                    match: {path: "e/**"}
                """.trimIndent(),
            )
        val problems = config.validate()
        assertContains(problems, "route 'has space': name may only use letters, digits, '.', '_' and '-' (at most 64)")
        assertContains(problems, "route '${"x".repeat(65)}': name may only use letters, digits, '.', '_' and '-' (at most 64)")
        assertContains(problems, "route 'ok.name_1-2': unknown method 'FETCH'")
        assertFalse(problems.any { "method 'get'" in it || "method 'Post'" in it }, problems.toString())
        assertContains(problems, "route 'hosts': host 'api.*.test' is not a host name (only a leading *. wildcard is allowed)")
        assertContains(problems, "route 'nopath': path must start with '/'")
        assertContains(problems, "cors: origin 'localhost:5170' must be scheme://host[:port], or *")
        assertContains(problems, "cors: origin 'http://a.test/path' must be scheme://host[:port], or *")
        assertFalse(problems.any { "'http://localhost:3000'" in it || "origin '*'" in it }, problems.toString())
        assertEquals(
            emptyList(),
            parseGatewayConfig("routes:\n  - name: w\n    match: {host: \"*.example.com\", path: /x}\n").validate(),
        )
    }

    @Test
    fun `validate reserves the authorizer names public and deny`() {
        val config =
            parseGatewayConfig(
                """
                authorizers:
                  public: {type: jwt, jwksUrl: "http://keys"}
                  deny: {type: jwt, jwksUrl: "http://keys"}
                """.trimIndent(),
            )
        val problems = config.validate()
        assertContains(problems, "authorizer name 'public' is reserved")
        assertContains(problems, "authorizer name 'deny' is reserved")
    }

    @Test
    fun `validate keeps deny statuses in the error range`() {
        val config =
            parseGatewayConfig(
                """
                authorizers:
                  a: {type: jwt, jwksUrl: "http://keys", denyStatus: 200}
                  b: {type: jwt, jwksUrl: "http://keys", missingTokenStatus: 302}
                  c: {type: jwt, jwksUrl: "http://keys", denyStatus: 401, missingTokenStatus: 599}
                """.trimIndent(),
            )
        val problems = config.validate()
        assertContains(problems, "authorizer 'a': denyStatus must be between 400 and 599")
        assertContains(problems, "authorizer 'b': missingTokenStatus must be between 400 and 599")
        assertFalse(problems.any { it.startsWith("authorizer 'c'") }, problems.toString())
    }

    @Test
    fun `validate rejects a method listed twice, in any case, since APISIX would skip the route`() {
        val config =
            parseGatewayConfig(
                """
                routes:
                  - name: mixed
                    match: {path: /a/**, methods: [GET, get]}
                  - name: same
                    match: {path: /b/**, methods: [POST, POST, put]}
                  - name: fine
                    match: {path: /c/**, methods: [get, post]}
                """.trimIndent(),
            )
        val problems = config.validate()
        assertContains(problems, "route 'mixed': method 'get' is listed more than once")
        assertContains(problems, "route 'same': method 'POST' is listed more than once")
        assertFalse(problems.any { it.startsWith("route 'fine'") }, problems.toString())
    }

    private val shared =
        """
        headers:
          x-gateway-auth-strategy: firebase
          X-User-Id: "{{uid}}"
          x-tenant: acme
        routes:
          - name: r
            match: {path: /r/**}
            headers:
              x-user-id: "{{userId}}"
              x-extra: "1"
          - name: plain
            match: {path: /p/**}
        default:
          headers:
            X-TENANT: other
        """.trimIndent()

    @Test
    fun `shared headers apply to every route and the default, and a route's own win in any case with its spelling`() {
        val config = parseGatewayConfig(shared)
        assertEquals(
            mapOf("x-gateway-auth-strategy" to "firebase", "x-tenant" to "acme", "x-user-id" to "{{userId}}", "x-extra" to "1"),
            config.headersFor(config.routes[0]),
        )
        assertEquals(config.headers, config.headersFor(config.routes[1]))
        assertEquals(
            mapOf("x-gateway-auth-strategy" to "firebase", "X-User-Id" to "{{uid}}", "X-TENANT" to "other"),
            config.defaultHeaders(),
        )
    }

    @Test
    fun `without shared headers a route's headers are exactly its own`() {
        val config = parseGatewayConfig(citadel)
        config.routes.forEach { assertEquals(it.headers, config.headersFor(it)) }
        assertEquals(config.default.headers, config.defaultHeaders())
    }

    @Test
    fun `secretNames sees shared headers`() {
        assertEquals(setOf("SHARED"), parseGatewayConfig("headers:\n  x-k: \"{{secret:SHARED}}\"\n").secretNames())
    }

    @Test
    fun `the YAML encoder leaves out defaults, keeps the model's key order and round-trips`() {
        val config =
            parseGatewayConfig(
                """
                log:
                  maxCalls: 50
                cors:
                  origins: [http://localhost:3000]
                default:
                  auth: fb
                routes:
                  - name: r
                    match: {path: /r/**}
                    auth: fb
                authorizers:
                  fb:
                    type: jwt
                    jwks:
                      keys:
                        - kid: k1
                          e: AQAB
                          n: "123"
                headers:
                  x-tenant: acme
                  x-on: "true"
                upstream: http://localhost:8080
                """.trimIndent(),
            )
        val text = encodeGatewayConfig(config)
        assertEquals(config, parseGatewayConfig(text))
        val keys = text.lines().filter { it.isNotEmpty() && !it.startsWith(" ") && !it.startsWith("-") }.map { it.substringBefore(':') }
        assertEquals(listOf("upstream", "headers", "authorizers", "routes", "default", "cors", "log"), keys)
        // Defaulted fields are left out, at every level.
        listOf("stripHeaders", "tokenFrom", "algorithms", "denyStatus", "redact", "maxBodyBytes", " host:", "credentials").forEach {
            assertFalse(it in text, "$it in\n$text")
        }
        assertEquals("", encodeGatewayConfig(GatewayConfig()))
        assertEquals(GatewayConfig(), parseGatewayConfig(encodeGatewayConfig(GatewayConfig())))
    }

    @Test
    fun `comments are found on their own line and after a value, but not inside a value`() {
        assertTrue(gatewayYmlHasComments("# top\ndefault:\n  auth: public\n"))
        assertTrue(gatewayYmlHasComments("upstream: http://localhost:8080   # overrides the option\n"))
        assertTrue(gatewayYmlHasComments("routes:\n  - name: a # first\n    match: {path: /a}\n"))
        assertTrue(gatewayYmlHasComments("headers:\n  x-a: \"v # in quotes\"  # but this is one\n"))
        assertFalse(gatewayYmlHasComments("headers:\n  x-a: \"v # in quotes\"\n  x-b: 'it''s # also quoted'\n"))
        assertFalse(gatewayYmlHasComments("upstream: http://localhost:8080/#frag\nheaders:\n  x-a: a#b\n"))
        assertFalse(gatewayYmlHasComments("headers:\n  x-a: |\n    not # a comment\n\n  x-b: c\n"))
        assertFalse(gatewayYmlHasComments(""))
        assertFalse(gatewayYmlHasComments("default:\n\n  auth: public\n"))
    }

    @Test
    fun `a config that would not read back as given is refused, naming the field`() {
        val ok = parseGatewayConfig("headers:\n  x-tenant: acme\n")
        assertEquals(FormYaml("headers:\n  x-tenant: acme\n", emptyList()), formYaml(ok))

        val jwks =
            Json.parseToJsonElement("""{"keys":[{"kid":"k1","n":123,"ok":true}]}""") as JsonObject
        val lossy = GatewayConfig(authorizers = mapOf("j" to Authorizer(type = "jwt", jwks = jwks)))
        assertEquals(
            FormYaml(
                null,
                listOf(
                    "authorizers.j.jwks.keys[0].n: a number would be saved as text; write it as a string",
                    "authorizers.j.jwks.keys[0].ok: a true/false would be saved as text; write it as a string",
                ),
            ),
            formYaml(lossy),
        )

        // A character YAML cannot hold as written (NEL) does not read back at all.
        val nel = formYaml(GatewayConfig(headers = mapOf("x-a" to "\u0085")))
        assertNull(nel.text)
        assertContains(nel.problems.single(), "would not read back as written: ")
        assertContains(nel.problems.single(), "headers.x-a")

        val thrown = formYaml(ok) { throw IllegalStateException("boom") }
        assertEquals(FormYaml(null, listOf("could not be written as gateway.yml: boom")), thrown)
    }
}
