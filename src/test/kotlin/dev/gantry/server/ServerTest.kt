package dev.gantry.server

import com.sun.net.httpserver.HttpServer
import dev.gantry.engine.Engine
import dev.gantry.engine.NO_DOCKER
import dev.gantry.gateway.AuthorizerContainers
import dev.gantry.gateway.Authorizers
import dev.gantry.gateway.parseGatewayConfig
import dev.gantry.gateway.saveGatewayConfig
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ServerTest {
    private val gantry =
        Engine(
            home = Files.createTempDirectory("gantry-server-test"),
            dockerCommand = NO_DOCKER,
            gatewayLib = Files.createTempDirectory("gantry-lib").also { it.resolve("gantry-0.0.0.jar").writeText("jar bytes") },
        )

    private fun dashboard(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { dashboardModule(gantry) }
            block()
        }

    @Test
    fun `guard rejects foreign hosts and origins`() =
        dashboard {
            assertEquals(HttpStatusCode.OK, client.get("/api/apps") { header(HttpHeaders.Host, "localhost:7070") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/apps") { header(HttpHeaders.Host, "evil.example:7070") }.status)
            val crossSite =
                client.get("/api/apps") {
                    header(HttpHeaders.Host, "localhost:7070")
                    header(HttpHeaders.Origin, "http://evil.example")
                }
            assertEquals(HttpStatusCode.Forbidden, crossSite.status)
            val formPut =
                client.put("/api/apps/shop/gateway/config/form") {
                    header(HttpHeaders.Host, "localhost:7070")
                    header(HttpHeaders.Origin, "http://evil.example")
                    contentType(ContentType.Application.Json)
                    setBody("""{"config":{}}""")
                }
            assertEquals(HttpStatusCode.Forbidden, formPut.status)
        }

    @Test
    fun `unknown api paths are 404 not the dashboard page`() =
        dashboard {
            val response = client.get("/api/apps/shop/cache/info")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertContains(response.bodyAsText(), "no such endpoint")
        }

    @Test
    fun `unknown app is a user error`() =
        dashboard {
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/apps/nope").status)
        }

    @Test
    fun `env names endpoint renames variables`() =
        dashboard {
            gantry.create("shop", services = listOf("postgres"))

            val response =
                client.put("/api/apps/shop/env-names") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"names":{"DB_HOST":"PG_HOST","DB_PORT":""}}""")
                }

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            assertContains(body, "PG_HOST=localhost")
            assertFalse("DB_PORT=" in body)
        }

    @Test
    fun `queue explorer parses ElasticMQ responses`() =
        dashboard {
            gantry.create("shop", services = listOf("elasticmq"))
            val fake = fakeElasticMq()
            val state = gantry.appDir("shop").resolve("app.json")
            // The allocated port depends on what is free on this machine, so rewrite whatever was picked.
            state.writeText(state.readText().replace(Regex("\"main\": \\d+"), "\"main\": ${fake.address.port}"))

            val queues = client.get("/api/apps/shop/queue/queues").bodyAsText()
            assertContains(queues, "\"name\":\"jobs\"")
            assertContains(queues, "\"visible\":\"3\"")
            assertContains(client.get("/api/apps/shop/queue/jobs/messages").bodyAsText(), "\"body\":\"hello\"")

            fake.stop(0)
        }

    private fun fakeElasticMq(): HttpServer {
        val ns = " xmlns=\"http://queue.amazonaws.com/doc/2012-11-05/\""
        val responses =
            mapOf(
                "ListQueues" to
                    "<ListQueuesResponse$ns><ListQueuesResult><QueueUrl>http://x/000000000000/jobs</QueueUrl></ListQueuesResult></ListQueuesResponse>",
                "GetQueueAttributes" to
                    "<GetQueueAttributesResponse$ns><GetQueueAttributesResult><Attribute><Name>ApproximateNumberOfMessages</Name><Value>3</Value></Attribute></GetQueueAttributesResult></GetQueueAttributesResponse>",
                "ReceiveMessage" to
                    "<ReceiveMessageResponse$ns><ReceiveMessageResult><Message><MessageId>m1</MessageId><Body>hello</Body></Message></ReceiveMessageResult></ReceiveMessageResponse>",
            )
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val action =
                    exchange.requestURI.query
                        .split("&")
                        .first { it.startsWith("Action=") }
                        .removePrefix("Action=")
                val body = (responses[action] ?: "<Response$ns/>").toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    private fun gatewayCall(id: String) =
        """{"request":{"uri":"/a?q=1","method":"GET","headers":{"authorization":"Bearer secret","x-request-id":"$id"}},""" +
            """"start_time":1793814923986,"latency":2.0,"response":{"status":404,"headers":{}},"route_id":"default"}"""

    @Test
    fun `gateway routes are 404 for an app without a gateway`() =
        dashboard {
            gantry.create("shop", services = listOf("postgres"))

            listOf("calls", "calls/x", "config", "config/form", "authorizers").forEach {
                val response = client.get("/api/apps/shop/gateway/$it")
                assertEquals(HttpStatusCode.NotFound, response.status, it)
                assertContains(response.bodyAsText(), "no gateway")
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client
                    .put("/api/apps/shop/gateway/config") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"text":""}""")
                    }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client
                    .put("/api/apps/shop/gateway/config/form") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"config":{}}""")
                    }.status,
            )
        }

    @Test
    fun `gateway calls and call detail round-trip`() =
        dashboard {
            gantry.create("shop", services = listOf("gateway"))
            val logs = Files.createDirectories(gantry.appDir("shop").resolve("gateway-log"))
            logs.resolve("calls.log").writeText(listOf(gatewayCall("one"), gatewayCall("two")).joinToString("\n", postfix = "\n"))

            val list = client.get("/api/apps/shop/gateway/calls?limit=1&status=4xx&path=/a").bodyAsText()
            assertContains(list, "\"id\":\"two\"")
            assertFalse("\"id\":\"one\"" in list)
            assertContains(list, "[redacted]")
            assertFalse("secret" in list)

            assertContains(client.get("/api/apps/shop/gateway/calls/one").bodyAsText(), "\"id\":\"one\"")
            assertEquals(HttpStatusCode.NotFound, client.get("/api/apps/shop/gateway/calls/nope").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/apps/shop/gateway/calls?status=bad").status)
            assertEquals("[]", client.get("/api/apps/shop/gateway/authorizers").bodyAsText())

            assertEquals(HttpStatusCode.OK, client.delete("/api/apps/shop/gateway/calls").status)
            assertEquals("[]", client.get("/api/apps/shop/gateway/calls").bodyAsText())
        }

    @Test
    fun `gateway config is read and saved, and problems are a 400`() =
        dashboard {
            gantry.create("shop", services = listOf("gateway"))

            assertContains(client.get("/api/apps/shop/gateway/config").bodyAsText(), "\"text\":\"# Gateway configuration")

            val saved =
                client.put("/api/apps/shop/gateway/config") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"default:\n  auth: public\n"}""")
                }
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            assertEquals("default:\n  auth: public\n", gantry.appDir("shop").resolve("gateway.yml").readText())

            val bad =
                client.put("/api/apps/shop/gateway/config") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"default:\n  auth: nobody\n"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertContains(bad.bodyAsText(), "\"problems\":[")
            assertContains(bad.bodyAsText(), "unknown authorizer 'nobody'")
            assertEquals("default:\n  auth: public\n", gantry.appDir("shop").resolve("gateway.yml").readText())
        }

    @Test
    fun `saving gateway config restarts running authorizers whose env changed`() {
        val containers = RecordingContainers()
        val authorizers = Authorizers(gantry, containers)
        gantry.create("shop", services = listOf("gateway"))
        val src = Files.createTempDirectory("gantry-auth-src").also { it.resolve("Dockerfile").writeText("FROM scratch\n") }
        authorizers.push("shop", "check", src) {}
        authorizers.push("shop", "spare", src) {}
        gantry.secrets.setSecret("shop", "API_KEY", "k")
        containers.calls.clear()
        containers.running = setOf("auth-check", "auth-spare")

        testApplication {
            application { dashboardModule(gantry, authorizers = authorizers) }
            val text =
                "authorizers:\\n  mine:\\n    type: custom\\n    container: check\\n    env:\\n      KEY: \\\"{{secret:API_KEY}}\\\"\\n"
            val saved =
                client.put("/api/apps/shop/gateway/config") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"$text"}""")
                }
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        }

        assertEquals(listOf("recreate auth-check"), containers.calls)
    }

    @Test
    fun `saving gateway config points a route back at its authorizer only after the restart`() {
        val containers = RecordingContainers()
        val authorizers = Authorizers(gantry, containers)
        gantry.create("shop", services = listOf("gateway"))
        val src = Files.createTempDirectory("gantry-auth-src").also { it.resolve("Dockerfile").writeText("FROM scratch\n") }
        authorizers.push("shop", "check", src) {}
        gantry.secrets.setSecret("shop", "API_KEY", "k")
        val routes = gantry.appDir("shop").resolve("apisix.yaml")
        val toContainer = "\"uri\": \"http://auth-check:8080/\""
        val yml =
            """
            authorizers:
              mine:
                type: custom
                container: check
                env:
                  KEY: "{{secret:API_KEY}}"
            default:
              auth: mine
            """.trimIndent() + "\n"
        assertEquals(emptyList(), gantry.saveGatewayConfig("shop", yml))
        containers.running = setOf("auth-check", "gateway")
        containers.calls.clear()
        containers.onRecreate = {
            assertFalse(toContainer in routes.readText(), routes.readText())
            assertContains(gantry.appDir("shop").resolve("auth-check.env").readText(), "MODE=strict")
        }
        val changed = yml.replace("      KEY:", "      MODE: strict\n      KEY:")

        testApplication {
            application { dashboardModule(gantry, authorizers = authorizers) }
            val saved =
                client.put("/api/apps/shop/gateway/config") {
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(mapOf("text" to changed)))
                }
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        }

        assertEquals(listOf("recreate auth-check"), containers.calls)
        assertContains(routes.readText(), toContainer)
    }

    private val richGateway =
        """
        upstream: http://localhost:8080
        headers:
          x-gateway-auth-strategy: gantry
          x-internal-api-key: "{{secret:INTERNAL_API_KEY}}"
        authorizers:
          fb:
            type: firebase
            projectId: demo
            onUnverifiedEmail: continue:email_not_verified
          cog:
            type: cognito
            region: eu-west-1
            userPoolId: eu-west-1_abc
            clientIds: [c1, c2]
            tokenUse: access
          sb:
            type: supabase
            url: https://abc.supabase.co
            jwtSecret: "{{secret:SUPABASE_JWT}}"
            tokenFrom: query:token
          inline:
            type: jwt
            jwks:
              keys:
                - kty: RSA
                  kid: k1
                  e: AQAB
                  n: "0vx7agoebGcQSuu"
            issuer: https://issuer.example
            audiences: [api]
            algorithms: [RS256, ES256]
            denyStatus: 401
            missingTokenStatus: 401
          ext:
            type: custom
            url: http://localhost:9300/check
          box:
            type: custom
            container: check
            env:
              KEY: "{{secret:API_KEY}}"
        steps:
          user:
            type: http-lookup
            lookup:
              url: "{{upstream}}/internal/users/{{uid}}"
              headers:
                x-internal-api-key: "{{secret:INTERNAL_API_KEY}}"
            create:
              method: POST
              url: "{{upstream}}/internal/users"
              body:
                email: "{{email}}"
            map:
              userId: "$.id"
            cacheSeconds: 60
            onFailure: continue:user_lookup_failed
        routes:
          - name: portal
            match: { host: app.example.com, path: /portal/**, methods: [GET, POST] }
            auth: fb
            steps: [user]
            rewrite: { addPrefix: /app }
            headers:
              x-user-id: "{{userId}}"
          - name: admin
            match: { path: /admin/** }
            auth: inline
            rewrite: { stripPrefix: /admin }
            upstream: http://localhost:9000
          - name: legacy
            match: { path: /old }
            auth: deny
            rewrite: { replace: /new }
        default:
          auth: cog
          steps: [user]
          headers:
            x-tenant: acme
        cors:
          origins: [http://localhost:3000]
          credentials: true
          allowHeaders: [content-type]
          maxAge: 60
        stripHeaders: [x-user-id, x-tenant]
        log:
          maxCalls: 200
          maxBodyBytes: 1024
          redact: [authorization, x-api-key]
        """.trimIndent() + "\n"

    @Test
    fun `gateway config form round-trips a rich config through JSON`() =
        dashboard {
            gantry.create("shop", services = listOf("gateway"))
            gantry.secrets.setSecret("shop", "INTERNAL_API_KEY", "sekret-value-1")
            assertEquals(emptyList(), gantry.saveGatewayConfig("shop", richGateway))

            val got = client.get("/api/apps/shop/gateway/config/form")
            assertEquals(HttpStatusCode.OK, got.status, got.bodyAsText())
            val body = got.bodyAsText()
            assertFalse("sekret-value-1" in body)
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals(JsonPrimitive(false), json["comments"])
            val jwks = json["config"]!!.jsonObject["authorizers"]!!.jsonObject["inline"]!!.jsonObject["jwks"] as JsonObject
            assertContains(jwks.toString(), "\"kid\":\"k1\"")

            val saved =
                client.put("/api/apps/shop/gateway/config/form") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            val file = gantry.appDir("shop").resolve("gateway.yml").readText()
            assertEquals(parseGatewayConfig(richGateway), parseGatewayConfig(file))
            assertFalse("sekret-value-1" in file)
            // Defaulted fields stay out of the file.
            assertFalse("tokenFrom: header" in file || "pass_host" in file || "algorithms" in file.substringBefore("inline:"), file)
        }

    @Test
    fun `an invalid gateway config form is a 400 that leaves the file alone`() =
        dashboard {
            gantry.create("shop", services = listOf("gateway"))
            val file = gantry.appDir("shop").resolve("gateway.yml")
            val before = file.readText()

            val bad =
                client.put("/api/apps/shop/gateway/config/form") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"config":{"default":{"auth":"nobody"}}}""")
                }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals(
                """{"error":"gateway.yml has problems","problems":["default: unknown authorizer 'nobody'"]}""",
                bad.bodyAsText(),
            )
            assertEquals(before, file.readText())

            // A value gateway.yml would not hold as given (a number in an inline key set) is refused, naming it.
            val lossy =
                client.put("/api/apps/shop/gateway/config/form") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"config":{"authorizers":{"j":{"type":"jwt","jwks":{"keys":[{"kid":"k1","e":65537}]}}}}}""")
                }
            assertEquals(HttpStatusCode.BadRequest, lossy.status)
            assertEquals(
                """{"error":"gateway.yml has problems","problems":["authorizers.j.jwks.keys[0].e: a number would be saved as text; write it as a string"]}""",
                lossy.bodyAsText(),
            )
            assertEquals(before, file.readText())

            // A field the model does not have is refused, not silently dropped.
            val unknown =
                client.put("/api/apps/shop/gateway/config/form") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"config":{"routs":[]}}""")
                }
            assertEquals(HttpStatusCode.BadRequest, unknown.status)
            assertContains(unknown.bodyAsText(), "\"problems\":[")
            assertEquals(before, file.readText())
        }

    @Test
    fun `the gateway config form reports comments the form would drop`() =
        dashboard {
            gantry.create("shop", services = listOf("gateway"))
            val first = Json.parseToJsonElement(client.get("/api/apps/shop/gateway/config/form").bodyAsText()).jsonObject
            assertEquals(JsonPrimitive(true), first["comments"])
            assertEquals(JsonPrimitive("public"), first["config"]!!.jsonObject["default"]!!.jsonObject["auth"])

            val saved =
                client.put("/api/apps/shop/gateway/config/form") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"config":{"headers":{"x-tenant":"acme"}}}""")
                }
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            assertEquals("headers:\n  x-tenant: acme\n", gantry.appDir("shop").resolve("gateway.yml").readText())
            val after = Json.parseToJsonElement(client.get("/api/apps/shop/gateway/config/form").bodyAsText()).jsonObject
            assertEquals(JsonPrimitive(false), after["comments"])

            // A comment after a value counts too; a # inside a quoted value does not.
            assertEquals(emptyList(), gantry.saveGatewayConfig("shop", "headers:\n  x-a: \"a # b\"\n"))
            val quoted = Json.parseToJsonElement(client.get("/api/apps/shop/gateway/config/form").bodyAsText()).jsonObject
            assertEquals(JsonPrimitive(false), quoted["comments"])
            assertEquals(emptyList(), gantry.saveGatewayConfig("shop", "headers:\n  x-a: b   # why\n"))
            val inline = Json.parseToJsonElement(client.get("/api/apps/shop/gateway/config/form").bodyAsText()).jsonObject
            assertEquals(JsonPrimitive(true), inline["comments"])
        }

    @Test
    fun `saving the gateway config form restarts running authorizers whose env changed`() {
        val containers = RecordingContainers()
        val authorizers = Authorizers(gantry, containers)
        gantry.create("shop", services = listOf("gateway"))
        val src = Files.createTempDirectory("gantry-auth-src").also { it.resolve("Dockerfile").writeText("FROM scratch\n") }
        authorizers.push("shop", "check", src) {}
        gantry.secrets.setSecret("shop", "API_KEY", "k")
        containers.calls.clear()
        containers.running = setOf("auth-check")

        testApplication {
            application { dashboardModule(gantry, authorizers = authorizers) }
            val saved =
                client.put("/api/apps/shop/gateway/config/form") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"config":{"authorizers":{"mine":{"type":"custom","container":"check","env":{"KEY":"{{secret:API_KEY}}"}}}}}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        }

        assertEquals(listOf("recreate auth-check"), containers.calls)
    }

    private class RecordingContainers : AuthorizerContainers {
        val calls = mutableListOf<String>()
        var running = emptySet<String>()
        var onRecreate: () -> Unit = {}

        override fun build(
            image: String,
            dir: Path,
            onLine: (String) -> Unit,
        ) = 0

        override fun appRunning(app: String) = running.isNotEmpty()

        override fun runningServices(app: String) = running

        override fun serviceStates(app: String) = running.associateWith { "running" }

        override fun recreate(
            app: String,
            service: String,
            onLine: (String) -> Unit,
        ): Int {
            calls += "recreate $service"
            onRecreate()
            return 0
        }

        override fun remove(container: String) {
            calls += "remove $container"
        }
    }
}
