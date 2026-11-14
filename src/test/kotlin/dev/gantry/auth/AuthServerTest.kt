package dev.gantry.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.gantry.gateway.parseGatewayConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Date
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthServerTest {
    private val dir: Path = Files.createTempDirectory("gantry-auth-server")

    // The compose service points --config at the applied copy that rendering writes next to apisix.yaml.
    private val configFile = dir.resolve("gateway.applied.yml")
    private val secretsFile = dir.resolve("gateway-secrets.json")
    private val logFile = dir.resolve("log/auth.log")
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("k1").generate()
    private val jwks = JWKSet(key.toPublicJWK()).toString()
    private var now = 1_000_000L
    private var mtime = 1_000L

    private val base =
        """
        upstream: http://app:8080
        authorizers:
          j:
            type: jwt
            jwks: $jwks
            tokenFrom: "header:x-token"
          q:
            type: jwt
            jwks: $jwks
            tokenFrom: "query:access_token"
        routes:
          - name: portal
            match: { path: /portal/** }
            auth: j
            headers:
              x-user-id: "{{sub}}"
          - name: viaquery
            match: { path: /q/** }
            auth: q
            headers:
              x-user-id: "{{sub}}"
        """.trimIndent()

    private fun write(
        file: Path,
        text: String,
    ) {
        file.writeText(text)
        mtime += 10_000
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime))
    }

    private fun state(
        upstream: String? = null,
        inContainer: Boolean = false,
    ) = AuthServerState(
        configFile = configFile,
        secretsFile = secretsFile,
        logFile = logFile,
        app = "shop",
        fallbackUpstream = upstream,
        inContainer = inContainer,
        verifier = TokenVerifier(fetch = { error("no network in tests") }),
        clock = { now },
    )

    private fun token(sub: String = "user-7"): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(sub)
                .expirationTime(Date(System.currentTimeMillis() + 3_600_000))
                .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims)
            .apply { sign(RSASSASigner(key)) }
            .serialize()
    }

    @Test
    fun `health`() =
        testApplication {
            application { authModule(state()) }
            val r = client.get("/health")
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals("ok", r.bodyAsText())
        }

    @Test
    fun `authorize maps forwarded headers`() =
        testApplication {
            write(configFile, base)
            application { authModule(state()) }
            val ok =
                client.get("/authorize?route=portal") {
                    header("X-Forwarded-Method", "GET")
                    header("X-Forwarded-Uri", "/portal/x?a=1")
                    header("X-Token", "Bearer ${token("user-7")}")
                }
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals("user-7", ok.headers["x-user-id"])

            // The token can come from the forwarded URI's query string, URL-decoded; POST is accepted too.
            val viaQuery =
                client.post("/authorize?route=viaquery") {
                    header("X-Forwarded-Method", "POST")
                    header("X-Forwarded-Uri", "/q/x?other=1&access_token=${token("user-8")}")
                }
            assertEquals(HttpStatusCode.OK, viaQuery.status)
            assertEquals("user-8", viaQuery.headers["x-user-id"])

            val encoded =
                client.get("/authorize?route=viaquery") {
                    header("X-Forwarded-Uri", "/q/x?access_token=%65${token("user-9").substring(1)}")
                }
            assertEquals("user-9", encoded.headers["x-user-id"])
        }

    @Test
    fun `deny returns status and json body`() =
        testApplication {
            write(configFile, base)
            application { authModule(state()) }
            val r = client.get("/authorize?route=portal") { header("X-Forwarded-Method", "GET") }
            assertTrue(r.status.value in listOf(401, 403))
            assertEquals("application/json", r.headers["Content-Type"]?.substringBefore(";"))
            assertContains(r.bodyAsText(), "error")
            assertNull(r.headers["x-user-id"])
            // The engine's internal reason is logged; the server adds nothing of its own to the response.
            assertFalse(r.headers.names().any { it.lowercase().contains("reason") })
            assertContains(logFile.readText(), "missing token")
        }

    @Test
    fun `config change is picked up`() =
        testApplication {
            write(configFile, base)
            application { authModule(state()) }
            val ask = suspend { client.get("/authorize?route=newroute") }
            assertEquals(403, ask().status.value)

            write(configFile, base + "\n  - name: newroute\n    match: { path: /n/** }\n    auth: public\n    headers:\n      x-a: b\n")
            // Within the same second the old config still answers.
            assertEquals(403, ask().status.value)
            now += 1_500
            val changed = ask()
            assertEquals(200, changed.status.value)
            assertEquals("b", changed.headers["x-a"])
        }

    @Test
    fun `invalid new config keeps the old one`() =
        testApplication {
            write(configFile, base + "\n  - name: ok\n    match: { path: /o/** }\n    auth: public\n    headers:\n      x-a: b\n")
            application { authModule(state()) }
            assertEquals(200, client.get("/authorize?route=ok").status.value)

            write(configFile, "routes: [ this is: not valid")
            now += 1_500
            assertEquals(200, client.get("/authorize?route=ok").status.value)
            write(configFile, "routes:\n  - name: ok\n    match: { path: /o/** }\n    auth: nosuchauthorizer\n")
            now += 1_500
            assertEquals(200, client.get("/authorize?route=ok").status.value)
            assertContains(logFile.readText(), "nosuchauthorizer")
        }

    @Test
    fun `no valid config at startup answers 503`() =
        testApplication {
            application { authModule(state()) }
            val r = client.get("/authorize?route=portal")
            assertEquals(503, r.status.value)
            assertEquals("""{"error":"gateway auth is unavailable"}""", r.bodyAsText())
            assertContains(logFile.readText(), "not found")
            assertEquals(200, client.get("/health").status.value)
        }

    @Test
    fun `secrets are read and reloaded, an unreadable file keeps the last good ones`() {
        write(configFile, "routes: []\n")
        write(secretsFile, """{"A":"one"}""")
        val s = state()
        s.engine()
        assertEquals("one", s.secret("A"))
        write(secretsFile, """{"A":"two","B":"b"}""")
        now += 1_500
        s.engine()
        assertEquals("two", s.secret("A"))
        write(secretsFile, "not json")
        now += 1_500
        s.engine()
        assertEquals("two", s.secret("A"))
        assertContains(logFile.readText(), "gateway-secrets.json")
        Files.delete(secretsFile)
        now += 1_500
        s.engine()
        assertNull(s.secret("A"))
    }

    @Test
    fun `the verifier survives reloads`() {
        write(configFile, base)
        val s = state()
        val first = s.verifier
        write(configFile, "$base\n")
        now += 1_500
        s.engine()
        assertTrue(first === s.verifier)
    }

    @Test
    fun `upstream comes from config then the argument`() {
        write(configFile, "routes: []\n")
        assertEquals("http://arg:1", state(upstream = "http://arg:1").effectiveUpstream())
        write(configFile, "upstream: http://cfg:2\n")
        assertEquals("http://cfg:2", state(upstream = "http://arg:1").effectiveUpstream())
    }

    @Test
    fun `in-container rewrites the host of every outbound url`() {
        val config =
            parseGatewayConfig(
                """
                upstream: http://localhost:8080
                authorizers:
                  c:
                    type: jwt
                    jwksUrl: http://127.0.0.1:9000/keys
                  s:
                    type: supabase
                    url: https://x.supabase.co
                steps:
                  me:
                    type: http-lookup
                    lookup:
                      url: "http://localhost:8080/users/{{uid}}?x={{a}}"
                    create:
                      method: POST
                      url: "{{upstream}}/users"
                  t:
                    type: http-lookup
                    lookup:
                      url: "http://{{host}}:80/x"
                """.trimIndent(),
            )
        val out = config.forContainer()
        assertEquals("http://host.docker.internal:8080", out.upstream)
        assertEquals("http://host.docker.internal:9000/keys", out.authorizers.getValue("c").jwksUrl)
        assertEquals("https://x.supabase.co", out.authorizers.getValue("s").url)
        assertEquals(
            "http://host.docker.internal:8080/users/{{uid}}?x={{a}}",
            out.steps
                .getValue("me")
                .lookup.url,
        )
        assertEquals(
            "{{upstream}}/users",
            out.steps
                .getValue("me")
                .create!!
                .url,
        )
        assertEquals(
            "http://{{host}}:80/x",
            out.steps
                .getValue("t")
                .lookup.url,
        )
        assertEquals("http://host.docker.internal:7", containerUrl("http://localhost:7"))
        assertEquals("http://app:7/p", containerUrl("http://app:7/p"))
        assertEquals("http://host.docker.internal/x", containerUrl("http://[::1]/x"))
    }

    @Test
    fun `in-container applies to the server state`() {
        write(configFile, "upstream: http://localhost:8080\n")
        assertEquals("http://host.docker.internal:8080", state(inContainer = true).effectiveUpstream())
        assertEquals(
            "http://host.docker.internal:5",
            state(upstream = "http://localhost:5", inContainer = true).let {
                write(configFile, "routes: []\n")
                now += 1_500
                it.effectiveUpstream()
            },
        )
    }
}
