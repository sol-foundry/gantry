package dev.gantry.gateway

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.gantry.engine.Engine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A real gateway (APISIX, gantry-auth and a pushed custom authorizer) in Docker, so skipped unless GANTRY_E2E=1. The
 * test makes its own throwaway app under `build/gateway-e2e/` with free ports, runs a small Python upstream next to it
 * (`gateway/e2e-echo.py`: echoes requests and WebSocket messages, streams `/sse`, fakes a user service) and removes
 * all of it afterwards.
 *
 * gantry-auth runs from the jars of `./gradlew installDist`, which the test task runs first when GANTRY_E2E=1.
 * Starting the gateway costs most of the run, so the cases share one app and run in order; each still stands alone
 * except `config edit applies live`, which comes late because a config change clears gantry-auth's step cache.
 */
@EnabledIfEnvironmentVariable(named = "GANTRY_E2E", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GatewayEndToEndTest {
    private val app = "gwe2e-" + List(6) { ('a'..'z').random() }.joinToString("")
    private val work: Path = Path.of("build", "gateway-e2e", app).toAbsolutePath()
    private val lib: Path = Path.of("build", "install", "gantry", "lib").toAbsolutePath()
    private val engine by lazy { Engine(home = work.resolve("home"), runtimeLib = lib) }
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("e2e").generate()
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val echoPort = freePort()
    private val gatewayPort = freePort(except = echoPort)
    private val echoContainer = "$app-echo"
    private val timings = mutableListOf<String>()

    @BeforeAll
    fun start() {
        check(Files.isDirectory(lib)) { "$lib is missing; run ./gradlew installDist (the test task does when GANTRY_E2E=1)" }
        Files.createDirectories(work)
        val echo = work.resolve("echo.py")
        Files.writeString(echo, javaClass.getResource("/gateway/e2e-echo.py")!!.readText())
        timed("setup") {
            val publish = listOf("-p", "127.0.0.1:$echoPort:8000", "-v", "$echo:/echo.py:ro")
            val command = listOf("python:3.13-alpine", "python", "-u", "/echo.py")
            docker(*(listOf("run", "-d", "--name", echoContainer) + publish + command).toTypedArray())
            waitFor("echo upstream", 30) { call("http://127.0.0.1:$echoPort/_stats").statusCode() == 200 }

            engine.create(app)
            engine.updatePorts { it["$app/gateway/main"] = gatewayPort }
            engine.add(app, GATEWAY, mapOf("upstream" to "http://localhost:$echoPort"))
            assertEquals(emptyList(), engine.saveGatewayConfig(app, config()))
            engine.docker.up(app)
            // APISIX is healthy once up returns; gantry-auth is ready when a token gets through.
            waitFor("gantry-auth", 60) { gateway("/me/ready", bearer(token("alice"))).statusCode() == 200 }
        }
    }

    @AfterAll
    fun stop() {
        runCatching { engine.docker.destroy(app) }.onFailure { println("[gateway-e2e] destroy: ${it.message}") }
        runCatching { docker("rm", "-f", echoContainer) }
        if (runCatching { docker("image", "inspect", authorizerImage(app, "check")) }.isSuccess) {
            runCatching { docker("rmi", authorizerImage(app, "check")) }.onFailure { println("[gateway-e2e] rmi: ${it.message}") }
        }
        if (runCatching { docker("network", "inspect", "gantry-${app}_default") }.isSuccess) {
            runCatching { docker("network", "rm", "gantry-${app}_default") }
        }
        work.toFile().deleteRecursively()
        // Only when empty: another run's app may still be there.
        runCatching { Files.deleteIfExists(work.parent) }
        val left = runCatching { docker("ps", "-a", "--filter", "name=$app", "--format", "{{.Names}}").trim() }.getOrDefault("?")
        println("[gateway-e2e] timings: ${timings.joinToString("; ")}")
        println("[gateway-e2e] containers left for $app: ${left.ifEmpty { "none" }}")
    }

    @Test
    @Order(1)
    fun `transparent proxy`() =
        timed("transparent proxy") {
            val get = gateway("/anything/here?q=1")
            assertEquals(200, get.statusCode())
            assertEquals("/anything/here?q=1", echoed(get).text("path"))

            val post = gateway("/orders", mapOf("Content-Type" to "application/json"), "POST", """{"n":1}""")
            assertEquals(200, post.statusCode())
            val seen = echoed(post)
            assertEquals("POST", seen.text("method"))
            assertEquals("""{"n":1}""", seen.text("body"))
        }

    @Test
    @Order(2)
    fun `protected route without token gets 403`() =
        timed("403 without token") {
            val response = gateway("/me/profile")
            assertEquals(403, response.statusCode())
            assertTrue("forbidden" in response.body(), response.body())
        }

    @Test
    @Order(3)
    fun `valid token reaches upstream with x-user-id`() =
        timed("valid token") {
            val response = gateway("/me/profile", bearer(token("alice")))
            assertEquals(200, response.statusCode(), response.body())
            assertEquals("alice", echoed(response).header("x-user-id"))
        }

    @Test
    @Order(4)
    fun `client x-user-id is replaced`() =
        timed("x-user-id replaced") {
            val forged = gateway("/me/profile", bearer(token("alice")) + ("x-user-id" to "bob"))
            assertEquals(200, forged.statusCode(), forged.body())
            assertEquals("alice", echoed(forged).header("x-user-id"))

            val public =
                gateway(
                    "/pub/x",
                    mapOf("x-user-id" to "bob", "X-Gateway-User-Email" to "bob@x.test", "X-Gateway-Auth-Strategy" to "forged"),
                )
            assertEquals(200, public.statusCode())
            assertEquals("", echoed(public).header("x-user-id").orEmpty())
            assertEquals("", echoed(public).header("x-gateway-user-email").orEmpty())
            // A fixed value the public route sets for a strip header arrives, in place of the client's copy.
            assertEquals("public", echoed(public).header("x-gateway-auth-strategy"))

            // A public route that sets every strip header itself (as citadel's do) is still a valid route.
            val all = gateway("/pub-all/x", mapOf("x-internal-api-key" to "forged", "X-Gateway-Auth-Strategy" to "forged"))
            assertEquals(200, all.statusCode(), all.body())
            assertEquals("public", echoed(all).header("x-gateway-auth-strategy"))
            assertEquals("", echoed(all).header("x-internal-api-key").orEmpty())
        }

    @Test
    @Order(5)
    fun `lookup and create through a fake user service`() =
        timed("lookup and create") {
            val token = token("carol", email = "carol@x.test")
            val first = gateway("/portal/home", bearer(token))
            assertEquals(200, first.statusCode(), first.body())
            val userId = echoed(first).header("x-user-id")
            assertNotNull(userId)
            assertTrue(Regex("u-\\d+").matches(userId), userId)
            assertEquals("carol@x.test", echoed(first).header("x-gateway-user-email"))
            assertEquals("", echoed(first).header("x-error-code").orEmpty())
            // The route's rewrite reached the upstream.
            assertEquals("/portal/portal/home", echoed(first).text("path"))

            val second = gateway("/portal/home", bearer(token))
            assertEquals(userId, echoed(second).header("x-user-id"))
            val stats = Json.parseToJsonElement(call("http://127.0.0.1:$echoPort/_stats").body()).jsonObject
            assertEquals("1", stats.text("lookups"), "the second request is served from the step cache")
            assertEquals("1", stats.text("creates"))
        }

    @Test
    @Order(6)
    fun `soft failure forwards X-Error-Code`() =
        timed("soft failure") {
            val response = gateway("/portal/home", bearer(token("dave", email = "dave@x.test", verified = false)))
            assertEquals(200, response.statusCode(), response.body())
            val seen = echoed(response)
            assertEquals("email_not_verified", seen.header("x-error-code"))
            assertEquals("", seen.header("x-user-id").orEmpty())
            assertEquals("dave@x.test", seen.header("x-gateway-user-email"))
        }

    @Test
    @Order(7)
    fun `sse streams through the gateway`() =
        timed("sse") {
            val started = System.nanoTime()
            val response =
                http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$gatewayPort/sse")).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofLines(),
                )
            assertEquals(200, response.statusCode())
            var first = -1L
            var events = 0
            response.body().use { lines ->
                lines.forEach { line ->
                    if (line.startsWith("data:")) {
                        events++
                        if (first < 0) first = (System.nanoTime() - started) / 1_000_000
                    }
                }
            }
            val total = (System.nanoTime() - started) / 1_000_000
            println("[gateway-e2e] sse: first event after $first ms, stream ended after $total ms")
            timings += "sse first event $first ms of $total ms"
            assertEquals(3, events)
            // The events are 300 ms apart: a buffered stream would deliver the first one only at the end.
            assertTrue(total - first >= 400, "first event at $first ms, stream ended at $total ms")
        }

    @Test
    @Order(8)
    fun `websocket upgrades pass through on public and protected routes`() =
        timed("websocket") {
            val public = webSocketEcho("/ws/public", emptyMap(), "hello")
            assertEquals("hello", public.text("echo"))
            assertEquals("/ws/public", public.text("path"))
            assertEquals("", public.header("x-user-id").orEmpty())

            val signed = webSocketEcho("/me/ws", bearer(token("alice")) + ("x-user-id" to "forged"), "hi alice")
            assertEquals("hi alice", signed.text("echo"))
            assertEquals("alice", signed.header("x-user-id"), "the handshake went through the route's authorizer")

            // Without a token the handshake is refused by the gateway, before any upgrade.
            val refused = runCatching { webSocketEcho("/me/ws", emptyMap(), "nope") }.exceptionOrNull()
            val handshake = generateSequence(refused) { it.cause }.filterIsInstance<WebSocketHandshakeException>().firstOrNull()
            assertNotNull(handshake, "expected a refused handshake, got $refused")
            assertEquals(403, handshake.response.statusCode())
        }

    @Test
    @Order(9)
    fun `custom authorizer pushed from the node template allows and denies`() =
        timed("custom authorizer") {
            val source = work.resolve("check")
            val authorizers = Authorizers(engine)
            authorizers.init("check", "node", source)
            timed("custom authorizer push") { authorizers.push(app, "check", source) { println("[push] $it") } }

            timed("custom authorizer ready") {
                waitFor("auth-check", 60) { gateway("/custom/x", bearer("dev-token")).statusCode() == 200 }
            }
            val allowed = gateway("/custom/x", bearer("dev-token") + ("x-user-id" to "forged"))
            assertEquals(200, allowed.statusCode())
            assertEquals("dev-user", echoed(allowed).header("x-user-id"))

            val none = gateway("/custom/x")
            assertEquals(401, none.statusCode())
            assertTrue("unauthorized" in none.body(), none.body())
            assertEquals(401, gateway("/custom/x", bearer("wrong")).statusCode())
        }

    @Test
    @Order(10)
    fun `a host route never wins over an earlier route`() =
        timed("host route order") {
            // APISIX's default router would try the host route first and let this through as public.
            assertEquals(403, statusWithHost("/admin/x", "webhook.localhost"))
            assertEquals(200, statusWithHost("/hook", "webhook.localhost"))
            assertEquals(403, gateway("/admin/x").statusCode())
        }

    @Test
    @Order(11)
    fun `stripPrefix of the bare prefix sends slash`() =
        timed("strip prefix") {
            assertEquals("/", echoed(gateway("/strip")).text("path"))
            assertEquals("/a/b?q=1", echoed(gateway("/strip/a/b?q=1")).text("path"))
        }

    @Test
    @Order(12)
    fun `a custom authorizer with an unset env secret answers 503 naming it`() =
        timed("unset authorizer secret") {
            val response = gateway("/custom-unset/x", bearer("dev-token"))
            assertEquals(503, response.statusCode(), response.body())
            assertTrue("secret E2E_UNSET is not set" in response.body(), response.body())
            assertTrue("gantry secret set $app E2E_UNSET" in response.body(), response.body())
        }

    @Test
    @Order(13)
    fun `a config APISIX would skip a route of is refused before it gets there`() =
        timed("invalid route refused") {
            val routes = engine.appDir(app).resolve("apisix.yaml")
            val before = Files.readString(routes)
            val bad =
                """
                |  - name: "bad name"
                |    match:
                |      path: /bad/**
                |      methods: [get, FETCH]
                |    auth: deny
                |
                """.trimMargin()
            val problems = engine.saveGatewayConfig(app, config(bad))
            assertTrue(problems.any { "route 'bad name': name may only use" in it }, problems.toString())
            assertTrue(problems.any { "unknown method 'FETCH'" in it }, problems.toString())
            assertEquals(before, Files.readString(routes))
            assertEquals(200, gateway("/bad/x").statusCode(), "the running gateway is unchanged")

            // APISIX's methods are uniqueItems: a repeated one (in any case) would get the route skipped.
            val twice =
                """
                |  - name: twice
                |    match:
                |      path: /twice/**
                |      methods: [GET, get]
                |    auth: deny
                |
                """.trimMargin()
            val repeated = engine.saveGatewayConfig(app, config(twice))
            assertTrue(repeated.any { "route 'twice': method 'get' is listed more than once" in it }, repeated.toString())
            assertEquals(before, Files.readString(routes))
        }

    @Test
    @Order(14)
    fun `config edit applies live`() =
        timed("config edit") {
            val gatewayStarted = startedAt("$app-gateway")
            val authStarted = startedAt("$app-gantry-auth")
            // Before the edit, /live/** falls through to the public default.
            assertEquals(200, gateway("/live/x").statusCode())

            val live =
                """
                |  - name: live
                |    match:
                |      path: /live/**
                |    auth: jwt
                |    headers:
                |      x-user-id: "{{sub}}"
                |      x-live: "on"
                """.trimMargin()
            val saved = System.nanoTime()
            assertEquals(emptyList(), engine.saveGatewayConfig(app, config(live)))
            // What the gateway last answered, so a timeout says which side (APISIX or gantry-auth) had not caught up.
            var seen = "nothing"
            try {
                waitFor("the new route", 15) {
                    val anonymous = gateway("/live/x")
                    val signed = gateway("/live/x", bearer(token("erin")))
                    val user = runCatching { echoed(signed).header("x-user-id") }.getOrNull()
                    val at = (System.nanoTime() - saved) / 1_000_000
                    seen = "after $at ms: anonymous ${anonymous.statusCode()}, signed ${signed.statusCode()} as $user"
                    anonymous.statusCode() == 403 && signed.statusCode() == 200 && user == "erin"
                }
            } catch (e: AssertionError) {
                throw AssertionError("${e.message}; last seen $seen", e)
            }
            val applied = (System.nanoTime() - saved) / 1_000_000
            println("[gateway-e2e] config edit applied after $applied ms")
            timings += "config edit applied after $applied ms"
            assertEquals("on", echoed(gateway("/live/x", bearer(token("erin")))).header("x-live"))
            assertEquals(gatewayStarted, startedAt("$app-gateway"), "APISIX was not restarted")
            assertEquals(authStarted, startedAt("$app-gantry-auth"), "gantry-auth was not restarted")
        }

    @Test
    @Order(15)
    fun `shared headers reach the upstream on a protected route and are blank on a public one`() =
        timed("shared headers") {
            val shared = "headers:\n  x-shared-static: acme\n  x-shared-user: \"{{sub}}\"\n"
            assertEquals(emptyList(), engine.saveGatewayConfig(app, shared + config()))
            val forged = mapOf("x-shared-static" to "forged", "x-shared-user" to "forged")
            waitFor("the shared headers", 15) {
                runCatching { echoed(gateway("/me/x", bearer(token("frank")) + forged)).header("x-shared-user") }.getOrNull() == "frank"
            }
            val identity = mapOf("x-user-id" to "forged", "x-gateway-user-email" to "forged@x.test")
            val signed = echoed(gateway("/me/x", bearer(token("frank")) + forged + identity))
            assertEquals("acme", signed.header("x-shared-static"))
            assertEquals("frank", signed.header("x-shared-user"))
            // The identity headers still come from gantry-auth: the token's user, and blank where no template sets one.
            assertEquals("frank", signed.header("x-user-id"), "the route's own headers still arrive")
            assertEquals("", signed.header("x-gateway-user-email").orEmpty())

            // A public route has no caller to fill a template from: the client's copy is replaced by a blank.
            val public = echoed(gateway("/pub/x", forged))
            assertEquals("", public.header("x-shared-user").orEmpty())
            assertEquals("acme", public.header("x-shared-static"))

            // A custom authorizer's own identity header still arrives, in place of the client's; a shared header it
            // does not answer with arrives without the client's copy.
            val custom = gateway("/custom/x", bearer("dev-token") + forged + ("x-user-id" to "forged"))
            assertEquals(200, custom.statusCode(), custom.body())
            assertEquals("dev-user", echoed(custom).header("x-user-id"))
            assertEquals("", echoed(custom).header("x-shared-static").orEmpty())
        }

    @Test
    @Order(16)
    fun `calls appear in the call log with redacted authorization`() =
        timed("call log") {
            val log = engine.callLog(app)
            var signed: Call? = null
            waitFor("the call log", 10) {
                signed =
                    log.recent(limit = 500, path = "/me/**").firstOrNull {
                        it.status == 200 && it.requestHeaders.containsKey("authorization") && it.userId == "alice"
                    }
                signed != null
            }
            val call = signed!!
            assertEquals("[redacted]", call.requestHeaders["authorization"])
            assertEquals("allow", call.authDecision)
            assertEquals("me", call.route)
            assertTrue(log.recent(limit = 500, status = "403", path = "/me/**").any { it.authDecision == "deny" })
            assertTrue(log.recent(limit = 500).none { c -> c.requestHeaders.values.any { "Bearer ey" in it } })
        }

    @Test
    @Order(17)
    fun `gantry-auth down answers 503, not 403`() =
        timed("gantry-auth down") {
            docker("stop", "-t", "2", "$app-gantry-auth")
            try {
                // APISIX gives up on forward-auth after its 10 s timeout, so the client waits longer than that.
                val response = call("http://127.0.0.1:$gatewayPort/me/x", bearer(token("alice")), timeout = Duration.ofSeconds(20))
                assertEquals(503, response.statusCode(), response.body())
            } finally {
                docker("start", "$app-gantry-auth")
            }
            waitFor("gantry-auth back", 60) { gateway("/me/ready", bearer(token("alice"))).statusCode() == 200 }
        }

    @Test
    @Order(18)
    fun `apisix accepted every route it was given`() =
        timed("apisix accepted routes") {
            // APISIX skips (and logs) a route its schema refuses; that route's requests would fall through elsewhere.
            val skipped = docker("logs", "$app-gateway").lines().filter { "failed to check item data" in it }
            assertEquals(emptyList(), skipped)
        }

    /** The gateway.yml under test, with [extraRoutes] (indented YAML list items) appended to its routes. */
    private fun config(extraRoutes: String = ""): String =
        """
        |authorizers:
        |  jwt:
        |    type: jwt
        |    jwks: ${JWKSet(key.toPublicJWK())}
        |    issuer: gantry-e2e
        |    onUnverifiedEmail: continue:email_not_verified
        |  mine:
        |    type: custom
        |    container: check
        |  unset:
        |    type: custom
        |    container: check
        |    env:
        |      API_KEY: "{{secret:E2E_UNSET}}"
        |steps:
        |  user:
        |    type: http-lookup
        |    lookup:
        |      url: "{{upstream}}/users/by-email/{{email}}"
        |    create:
        |      method: POST
        |      url: "{{upstream}}/users"
        |      body:
        |        email: "{{email}}"
        |    map:
        |      userId: "$.id"
        |    onFailure: continue:user_resolution_error
        |routes:
        |  - name: me
        |    match:
        |      path: /me/**
        |    auth: jwt
        |    headers:
        |      x-user-id: "{{sub}}"
        |  - name: portal
        |    match:
        |      path: /portal/**
        |    auth: jwt
        |    steps: [user]
        |    rewrite:
        |      addPrefix: /portal
        |    headers:
        |      x-user-id: "{{userId}}"
        |      x-gateway-user-email: "{{email}}"
        |      x-error-code: "{{errorCode}}"
        |  - name: custom
        |    match:
        |      path: /custom/**
        |    auth: mine
        |  - name: custom-unset
        |    match:
        |      path: /custom-unset/**
        |    auth: unset
        |  - name: pub
        |    match:
        |      path: /pub/**
        |    headers:
        |      x-gateway-auth-strategy: public
        |      x-user-id: "{{sub}}"
        |  - name: pub-all
        |    match:
        |      path: /pub-all/**
        |    headers:
        |      x-user-id: "{{sub}}"
        |      x-gateway-user-email: "{{email}}"
        |      x-gateway-auth-strategy: public
        |      x-internal-api-key: "{{secret:E2E_KEY}}"
        |      x-error-code: "{{errorCode}}"
        |  - name: admin
        |    match:
        |      path: /admin/**
        |    auth: deny
        |  - name: webhook
        |    match:
        |      host: webhook.localhost
        |      path: /**
        |  - name: strip
        |    match:
        |      path: /strip/**
        |    rewrite:
        |      stripPrefix: /strip
        |
        """.trimMargin() + extraRoutes + "\ndefault:\n  auth: public\n"

    private fun token(
        sub: String,
        email: String = "$sub@x.test",
        verified: Boolean = true,
    ): String {
        val now = Instant.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer("gantry-e2e")
                .subject(sub)
                .claim("email", email)
                .claim("email_verified", verified)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
            .apply { sign(RSASSASigner(key)) }
            .serialize()
    }

    private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")

    /** The status of a GET for [path] sent with Host: [host] (the JDK client will not set Host itself). */
    private fun statusWithHost(
        path: String,
        host: String,
    ): Int =
        Socket(InetAddress.getLoopbackAddress(), gatewayPort).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            val status = socket.getInputStream().bufferedReader().readLine() ?: fail("no response for $host$path")
            status.split(" ")[1].toInt()
        }

    private fun gateway(
        path: String,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: String? = null,
    ) = call("http://127.0.0.1:$gatewayPort$path", headers, method, body)

    private fun call(
        url: String,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: String? = null,
        timeout: Duration = Duration.ofSeconds(10),
    ): HttpResponse<String> {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(timeout)
                .method(method, publisher)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** Opens a WebSocket through the gateway, sends [message] and returns the echo upstream's JSON reply. */
    private fun webSocketEcho(
        path: String,
        headers: Map<String, String>,
        message: String,
    ): JsonObject {
        val reply = CompletableFuture<String>()
        val listener =
            object : WebSocket.Listener {
                private val text = StringBuilder()

                override fun onText(
                    webSocket: WebSocket,
                    data: CharSequence,
                    last: Boolean,
                ): CompletionStage<*>? {
                    text.append(data)
                    if (last) reply.complete(text.toString())
                    webSocket.request(1)
                    return null
                }

                override fun onError(
                    webSocket: WebSocket,
                    error: Throwable,
                ) {
                    reply.completeExceptionally(error)
                }
            }
        val socket =
            http
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .buildAsync(URI.create("ws://127.0.0.1:$gatewayPort$path"), listener)
                .get(10, TimeUnit.SECONDS)
        try {
            socket.sendText(message, true).get(5, TimeUnit.SECONDS)
            return Json.parseToJsonElement(reply.get(10, TimeUnit.SECONDS)).jsonObject
        } finally {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS) }
        }
    }

    /** What the echo upstream received; fails when the response did not come from it. */
    private fun echoed(response: HttpResponse<String>): JsonObject =
        runCatching { Json.parseToJsonElement(response.body()).jsonObject }.getOrNull()?.takeIf { "headers" in it }
            ?: fail("not an echo response (${response.statusCode()}): ${response.body()}")

    private fun JsonObject.header(name: String): String? = (this["headers"] as JsonObject)[name]?.jsonPrimitive?.content

    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.content

    private fun startedAt(container: String) = docker("inspect", "-f", "{{.State.StartedAt}}", container).trim()

    /** Polls [ready] every 200 ms until it holds, failing after [seconds]. */
    private fun waitFor(
        what: String,
        seconds: Long,
        ready: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        var last: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                if (ready()) return
            } catch (e: Exception) {
                last = e
            }
            Thread.sleep(200)
        }
        fail("$what was not ready within $seconds s" + (last?.let { " (last error: $it)" } ?: ""))
    }

    private fun timed(
        name: String,
        block: () -> Unit,
    ) {
        val started = System.nanoTime()
        try {
            block()
        } finally {
            val ms = (System.nanoTime() - started) / 1_000_000
            println("[gateway-e2e] $name: $ms ms")
            timings += "$name $ms ms"
        }
    }

    private fun docker(vararg args: String): String {
        val process = ProcessBuilder(listOf(engine.dockerCommand) + args).redirectErrorStream(true).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("docker ${args.first()} timed out")
        }
        val text = output.get(10, TimeUnit.SECONDS)
        check(process.exitValue() == 0) { "docker ${args.joinToString(" ")} failed: $text" }
        return text
    }

    private fun freePort(except: Int = -1): Int {
        while (true) {
            val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            if (port != except) return port
        }
    }
}
